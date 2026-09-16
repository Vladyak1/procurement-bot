package com.example.procurement;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Общий сервис для обработки парсинга и публикации закупок.
 * Используется как в шедулере, так и при ручном запуске.
 */
@Slf4j
public class ProcurementProcessingService {
    private final DatabaseManager databaseManager;
    private final ParserService parserService;
    private final TelegramBot bot;

    public ProcurementProcessingService(DatabaseManager databaseManager, 
                                       ParserService parserService, 
                                       TelegramBot bot) {
        this.databaseManager = databaseManager;
        this.parserService = parserService;
        this.bot = bot;
    }

    /**
     * Признак «публикация остановлена»: выставляется, когда бот больше не может сохранять
     * состояние (заполнен диск или отметка isSent не записалась). Пока флаг стоит, публикация
     * не идёт — иначе лоты уходят в чат повторно на каждом прогоне, как это случилось
     * 26–31.08.2026. Сбрасывается автоматически в начале прогона, если место снова есть.
     */
    private volatile boolean publishingHalted = false;
    private long lastHaltAlertMs = 0L;
    private static final long HALT_ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L; // не чаще раза в 6 часов

    /**
     * Останавливает публикацию и уведомляет админов. Сообщение шлётся не чаще раза в 6 часов,
     * чтобы предохранитель сам не превратился в спам.
     */
    private void haltPublishing(String reason) {
        boolean firstTime = !publishingHalted;
        publishingHalted = true;
        log.error("ПУБЛИКАЦИЯ ОСТАНОВЛЕНА: {}", reason);

        long now = System.currentTimeMillis();
        if (!firstTime && now - lastHaltAlertMs < HALT_ALERT_INTERVAL_MS) {
            return;
        }
        lastHaltAlertMs = now;
        try {
            if (bot != null) {
                long freeMb = databaseManager.getFreeDiskSpace() / (1024 * 1024);
                bot.sendMessageWithRetry(Config.getAdminGroupId(),
                        "⛔ Публикация лотов ОСТАНОВЛЕНА.\n\n"
                                + "Причина: " + reason + "\n"
                                + "Свободно на диске: "
                                + (freeMb >= 0 ? freeMb + " МБ" : "неизвестно") + "\n\n"
                                + "Бот продолжит парсинг, но публиковать не будет, пока проблема не устранена — "
                                + "иначе лоты уйдут в канал повторно.");
            }
        } catch (Exception e) {
            log.warn("Не удалось отправить уведомление об остановке публикации: {}", e.getMessage());
        }
    }

    /**
     * Предполётная проверка перед прогоном: есть ли место под запись БД и логов.
     * @return true, если можно работать
     */
    private boolean preflightCheck() {
        if (databaseManager.hasEnoughDiskSpace()) {
            if (publishingHalted) {
                log.info("Свободное место восстановлено — публикация снова разрешена");
                publishingHalted = false;
            }
            return true;
        }
        long freeMb = databaseManager.getFreeDiskSpace() / (1024 * 1024);
        haltPublishing("на диске почти нет места (" + freeMb + " МБ). "
                + "В таком состоянии не сохраняются ни отметки об отправке, ни логи");
        return false;
    }

    /**
     * Парсит и публикует закупки из указанных источников
     *
     * @param sources список источников для парсинга
     * @param maxCount максимальное количество лотов для публикации (Integer.MAX_VALUE для всех)
     * @param chatId ID чата для публикации
     * @param notifyAdminOnNoMatch отправлять ли уведомления админу о неопределенных лотах
     * @return количество опубликованных лотов
     */
    public int parseAndPublish(List<ParsingSource> sources, int maxCount, long chatId, boolean notifyAdminOnNoMatch, boolean markSent) {
        List<Procurement> allProcurements = new ArrayList<>();

        // Парсим из всех источников
        for (ParsingSource source : sources) {
            log.info("Parsing from source: {}", source.getName());
            List<Procurement> procurements = parserService.parseAndEnrich(
                source,
                Integer.MAX_VALUE,
                notifyAdminOnNoMatch
            );
            allProcurements.addAll(procurements);
        }

        // Чужие регионы отбрасываем ДО сохранения. Раньше они доживали до самой публикации:
        // ложились в БД, каждый прогон снова попадали в выборку «неотправленных» и отсеивались
        // заново — так накопилось больше трёх сотен мёртвых записей.
        // Убираем только заведомо чужие (код субъекта известен и не 92). Лоты без кода региона
        // (ЦДТРФ, Сбербанк-АСТ — они его не отдают) проходят дальше и проверяются как прежде.
        int foreignDropped = 0;
        java.util.Set<String> foreignRegions = new java.util.LinkedHashSet<>();
        java.util.Iterator<Procurement> it = allProcurements.iterator();
        while (it.hasNext()) {
            Procurement p = it.next();
            String code = p.getSubjectRfCode();
            if (code != null && !code.isEmpty() && !"92".equals(code)) {
                foreignRegions.add(code);
                foreignDropped++;
                it.remove();
            }
        }
        if (foreignDropped > 0) {
            log.info("Отсеяно до сохранения: {} лотов чужих регионов (коды субъектов: {})",
                    foreignDropped, String.join(", ", foreignRegions));
        }

        // Контроль источника ДО публикации: всплеск придержим, чтобы чужие лоты не ушли в канал
        boolean torgiHealthy = checkSourceHealth("Torgi.gov.ru", allProcurements.size());

        // Сохраняем все закупки в БД
        databaseManager.saveProcurements(allProcurements);

        // Проверяем изменения в существующих активных лотах
        checkForUpdates(allProcurements, chatId);

        // Получаем только новые закупки
        List<Procurement> newProcurements = databaseManager.getNewProcurements(allProcurements);

        // Публикуем новые закупки
        int published = 0;
        List<Procurement> postponedForPoint = new ArrayList<>();
        for (Procurement p : newProcurements) {
            if (published >= maxCount || publishingHalted || !torgiHealthy) break;

            // Проверяем регион на уровне лота: адрес должен содержать Севастополь,
            // либо кадастровый/номер лота начинается с 91
            if (!isFromSevastopol(p)) {
                noteFiltered(p, "регион", "не из Севастополя (адрес: " + p.getAddress() + ")");
                continue;
            }

            if (isBlockedCategory(p)) {
                noteFiltered(p, "категория", String.format(
                        "запрещённая категория движимого имущества/прочее (code=%s, name=%s, title=%s)",
                        p.getCategoryCode(), p.getCategoryName(), p.getTitle()));
                continue;
            }

            if (isExcludedByDescription(p)) {
                noteFiltered(p, "описание", String.format(
                        "описание указывает на движимое имущество (описание='%s', title=%s)",
                        p.getLotDescription(), p.getTitle()));
                continue;
            }

            // Точные координаты важнее скорости: лот с меткой в центре города бесполезен.
            // Если ЕГРН недоступен, лот подождёт следующего прогона — но только пока есть
            // запас по сроку подачи заявок.
            if (!ensureAccuratePoint(p)) {
                postponedForPoint.add(p);
                continue;
            }

            log.info("Публикация лота {}...", p.getNumber());
            boolean sent = bot.sendProcurementMessage(chatId, p);
            if (sent) {
                if (markSent && !databaseManager.markAsSent(p.getNumber())) {
                    // Лот ушёл в чат, но отметка НЕ сохранилась — дальше публиковать нельзя,
                    // иначе он и следующие уйдут повторно на каждом прогоне.
                    haltPublishing("не удалось сохранить отметку об отправке лота " + p.getNumber()
                            + " (лот уже опубликован, повтор недопустим)");
                    break;
                }
                databaseManager.clearFiltered(p.getNumber());
                published++;
                log.info("Лот {} опубликован (markSent={})", p.getNumber(), markSent);
            } else {
                log.error("Лот {} НЕ опубликован (ошибка отправки)", p.getNumber());
            }
        }

        if (!postponedForPoint.isEmpty()) {
            notifyPostponedForPoint(postponedForPoint);
        }

        log.info("Parsing completed. Total procurements: {}, new: {}, published: {}",
            allProcurements.size(), newProcurements.size(), published);

        databaseManager.cleanupExpiredRecords();

        return published;
    }

    /**
     * Публикует один лот по номеру — используется при одобрении no-match лота админом.
     * Заново обогащает лот, проверяет регион как страховку (admin override не отменяет город),
     * сохраняет, публикует в группу парсинга и помечает как отправленный.
     *
     * @return true если лот опубликован
     */
    public boolean publishSingleLotByNumber(String number) {
        if (number == null || number.isEmpty()) {
            return false;
        }
        String link = "https://torgi.gov.ru/new/public/lots/lot/" + number + "/(lotInfo:info)?fromRec=false";
        Procurement p = Procurement.builder()
                .number(number)
                .link(link)
                .source("Torgi.gov.ru (Севастополь)")
                .imageUrls(new ArrayList<>())
                .build();
        parserService.enrichOne(p);

        // Страховка по региону: одобрение админа не отменяет фильтр по городу
        if (!isFromSevastopol(p)) {
            log.warn("Одобренный no-match лот {} не из Севастополя (subjRF={}) — не публикуем",
                    number, p.getSubjectRfCode());
            return false;
        }

        long parseGroupId = Config.getParseGroupId();
        databaseManager.saveProcurements(List.of(p));
        boolean sent = bot.sendProcurementMessage(parseGroupId, p);
        if (sent) {
            databaseManager.markAsSent(number);
            log.info("Одобренный no-match лот {} опубликован в группу {}", number, parseGroupId);
        } else {
            log.error("Не удалось опубликовать одобренный no-match лот {}", number);
        }
        return sent;
    }

    /**
     * Проверяет изменения в активных лотах (deadline) и статусы завершенных лотов
     *
     * @param currentProcurements Текущий список лотов из RSS
     * @param adminChatId ID чата админов для уведомлений
     */
    private void checkForUpdates(List<Procurement> currentProcurements, long adminChatId) {
        log.info("Checking for updates in active lots...");

        // Получаем все активные отправленные лоты из БД
        List<Procurement> activeLots = databaseManager.getActiveSentProcurements();
        log.info("Found {} active lots in database", activeLots.size());

        // Создаем карту текущих лотов для быстрого поиска
        java.util.Map<String, Procurement> currentLotsMap = new java.util.HashMap<>();
        for (Procurement p : currentProcurements) {
            currentLotsMap.put(p.getNumber(), p);
        }

        // Создаем множество номеров активных лотов для оптимизации
        java.util.Set<String> activeLotNumbers = new java.util.HashSet<>();
        for (Procurement p : activeLots) {
            activeLotNumbers.add(p.getNumber());
        }

        // Парсим завершенные лоты из второй RSS-ссылки с оптимизацией
        String completedLotsUrl = "https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=SUCCEED,FAILED,CANCELED,APPLICATIONS_SUBMISSION_SUSPENDED&matchPhrase=false&byFirstVersion=true";
        CompletedLotsParser completedParser = new CompletedLotsParser(completedLotsUrl);
        java.util.Map<String, String> completedStatuses = completedParser.parseCompletedLots(activeLotNumbers);
        log.info("Found {} completed lots in RSS (with optimization)", completedStatuses.size());

        int deadlineUpdates = 0;
        int statusUpdates = 0;

        for (Procurement activeLot : activeLots) {
            String lotNumber = activeLot.getNumber();

            // Проверяем, изменился ли deadline (если лот все еще в активном списке)
            Procurement currentLot = currentLotsMap.get(lotNumber);
            if (currentLot != null) {
                String oldDeadline = activeLot.getDeadline();
                String newDeadline = currentLot.getDeadline();

                if (oldDeadline != null && newDeadline != null && !oldDeadline.equals(newDeadline)) {
                    log.info("Deadline changed for lot {}: {} -> {}", lotNumber, oldDeadline, newDeadline);
                    databaseManager.updateDeadline(lotNumber, newDeadline);
                    sendDeadlineChangeNotification(adminChatId, activeLot, oldDeadline, newDeadline);

                    // Обновляем опубликованные сообщения с новой датой
                    Procurement updatedLot = databaseManager.getProcurementByNumber(lotNumber);
                    if (updatedLot != null) {
                        List<DatabaseManager.MessageMapping> mappings = databaseManager.getMessageMappings(lotNumber);
                        for (DatabaseManager.MessageMapping mapping : mappings) {
                            try {
                                bot.updateProcurementMessage(mapping.chatId, mapping.messageId, updatedLot);
                                log.info("Updated message {} in chat {} after deadline change for lot {}", mapping.messageId, mapping.chatId, lotNumber);
                            } catch (Exception e) {
                                log.error("Failed to update message {} after deadline change for lot {}: {}", mapping.messageId, lotNumber, e.getMessage());
                            }
                        }
                    }
                    deadlineUpdates++;
                }
            }

            // Проверяем, появился ли лот в списке завершенных
            if (completedStatuses.containsKey(lotNumber)) {
                String newStatus = completedStatuses.get(lotNumber);
                log.info("Lot {} has new status: {}", lotNumber, newStatus);

                // Обновляем статус в БД
                databaseManager.updateLotStatus(lotNumber, newStatus);

                // Получаем обновленный лот из БД
                Procurement updatedLot = databaseManager.getProcurementByNumber(lotNumber);
                if (updatedLot != null) {
                    // Обновляем все связанные сообщения
                    List<DatabaseManager.MessageMapping> mappings = databaseManager.getMessageMappings(lotNumber);
                    for (DatabaseManager.MessageMapping mapping : mappings) {
                        try {
                            bot.updateProcurementMessage(mapping.chatId, mapping.messageId, updatedLot);
                            log.info("Updated message {} in chat {} for lot {}", mapping.messageId, mapping.chatId, lotNumber);
                        } catch (Exception e) {
                            log.error("Failed to update message {} for lot {}: {}", mapping.messageId, lotNumber, e.getMessage());
                        }
                    }
                    statusUpdates++;
                }
            }
        }

        log.info("Updates completed: {} deadline changes, {} status updates", deadlineUpdates, statusUpdates);
    }

    /**
     * Отправляет уведомление админам об изменении deadline
     */
    private void sendDeadlineChangeNotification(long adminChatId, Procurement lot, String oldDeadline, String newDeadline) {
        StringBuilder notification = new StringBuilder();
        notification.append("⚠️ <b>Изменение срока подачи заявок</b>\n\n");
        notification.append("Лот: ").append(lot.getTitle()).append("\n\n");
        notification.append("Старый срок: <s>").append(oldDeadline).append("</s>\n");
        notification.append("Новый срок: <b>").append(newDeadline).append("</b>\n\n");
        notification.append("<a href=\"").append(lot.getLink()).append("\">Перейти к лоту</a>");

        org.telegram.telegrambots.meta.api.methods.send.SendMessage msg =
            new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
        msg.setChatId(adminChatId);
        msg.setText(notification.toString());
        msg.setParseMode("HTML");

        try {
            bot.execute(msg);
            log.info("Sent deadline change notification for lot {}", lot.getNumber());
        } catch (Exception e) {
            log.error("Failed to send deadline change notification: {}", e.getMessage());
        }
    }

    /**
     * Парсит и публикует закупки из источника по умолчанию (из конфига)
     */
    public int parseAndPublishDefault(int maxCount, long chatId, boolean notifyAdminOnNoMatch, boolean markSent) {
        List<ParsingSource> sources = List.of(
            new ParsingSource("Torgi.gov.ru (Севастополь)", Config.getRssUrl())
        );
        return parseAndPublish(sources, maxCount, chatId, notifyAdminOnNoMatch, markSent);
    }

    /**
     * Парсит и публикует все закупки из всех сконфигурированных источников
     */
    public int parseAndPublishAll(long chatId, boolean notifyAdminOnNoMatch) {
        List<ParsingSource> sources = Config.getParsingSources();
        return parseAndPublish(sources, Integer.MAX_VALUE, chatId, notifyAdminOnNoMatch, true);
    }

    /**
     * Парсит и публикует закупки с Сбербанк-АСТ
     * 
     * @param maxCount максимальное количество лотов для публикации (Integer.MAX_VALUE для всех)
     * @param chatId ID чата для публикации
     * @return количество опубликованных лотов
     */
    public int parseAndPublishSberAst(int maxCount, long chatId, boolean markSent) {
        log.info("Starting SberAst parsing and publishing (chatId={}, markSent={})", chatId, markSent);

        SberAstParser sberParser = new SberAstParser();
        List<Procurement> procurements = sberParser.parse(Integer.MAX_VALUE, true);

        databaseManager.saveProcurements(procurements);
        List<Procurement> newProcurements = databaseManager.getNewProcurements(procurements);

        boolean healthy = checkSourceHealth("Сбербанк-АСТ", procurements.size());

        int published = 0;
        for (Procurement p : newProcurements) {
            if (published >= maxCount || publishingHalted || !healthy) break;

            if (databaseManager.isActiveDuplicate(p)) {
                noteFiltered(p, "дубль", "активный дубль существующего лота");
                continue;
            }

            log.info("Публикация SberAst лота {}...", p.getNumber());
            boolean sent = bot.sendProcurementMessage(chatId, p);
            if (sent) {
                if (markSent && !databaseManager.markAsSent(p.getNumber())) {
                    // Лот ушёл в чат, но отметка НЕ сохранилась — дальше публиковать нельзя,
                    // иначе он и следующие уйдут повторно на каждом прогоне.
                    haltPublishing("не удалось сохранить отметку об отправке лота " + p.getNumber()
                            + " (лот уже опубликован, повтор недопустим)");
                    break;
                }
                databaseManager.clearFiltered(p.getNumber());
                published++;
                log.info("SberAst лот {} опубликован (markSent={})", p.getNumber(), markSent);
            } else {
                log.error("SberAst лот {} НЕ опубликован (ошибка отправки)", p.getNumber());
            }
        }

        log.info("SberAst parsing completed. Total: {}, new: {}, published: {}",
            procurements.size(), newProcurements.size(), published);

        return published;
    }

    /**
     * Парсит и публикует закупки с ЦДТРФ (Центр дистанционных торгов)
     *
     * @param maxCount максимальное количество лотов для публикации (Integer.MAX_VALUE для всех)
     * @param chatId ID чата для публикации
     * @param notifyAdminOnNoMatch отправлять ли уведомления админу о неопределенных лотах
     * @return количество опубликованных лотов
     */
    public int parseAndPublishBankrotCdtrf(int maxCount, long chatId, boolean notifyAdminOnNoMatch) {
        log.info("Starting BankrotCdtrf parsing and publishing");

        BankrotCdtrfParser bankrotParser = new BankrotCdtrfParser();
        List<Procurement> procurements = bankrotParser.parse(Integer.MAX_VALUE, true, notifyAdminOnNoMatch);
        
        // Сохраняем все закупки в БД
        databaseManager.saveProcurements(procurements);
        
        // Получаем только новые закупки
        List<Procurement> newProcurements = databaseManager.getNewProcurements(procurements);
        
        // Публикуем новые закупки
        int published = 0;
        for (Procurement p : newProcurements) {
            if (published >= maxCount || publishingHalted) break;
            
            log.info("Публикация BankrotCdtrf лота {}...", p.getNumber());
            bot.sendProcurementMessage(chatId, p);
            databaseManager.markAsSent(p.getNumber());
            published++;
            log.info("BankrotCdtrf лот {} опубликован и помечен как отправленный", p.getNumber());
        }
        
        log.info("BankrotCdtrf parsing completed. Total procurements: {}, new: {}, published: {}", 
            procurements.size(), newProcurements.size(), published);
        
        return published;
    }

    /**
     * Парсит и публикует активные лоты недвижимости ЦДТРФ (банкротные торги, torgi.cdtrf.ru).
     * Кросс-дедуп против torgi (и своих) через isActiveDuplicate — не публикуем активный дубль.
     *
     * @param maxCount максимум лотов
     * @param chatId   куда публиковать (в debug-период — админ-группа)
     * @return количество опубликованных лотов
     */
    public int parseAndPublishCdtrf(int maxCount, long chatId, boolean markSent) {
        log.info("Starting ЦДТРФ parsing and publishing (chatId={}, markSent={})", chatId, markSent);

        CdtrfParser parser = new CdtrfParser();
        List<Procurement> procurements = parser.parse(maxCount);
        databaseManager.saveProcurements(procurements);
        List<Procurement> newProcurements = databaseManager.getNewProcurements(procurements);
        boolean healthy = checkSourceHealth("ЦДТРФ", procurements.size());

        int published = 0;
        for (Procurement p : newProcurements) {
            if (published >= maxCount || publishingHalted || !healthy) break;

            if (databaseManager.isActiveDuplicate(p)) {
                noteFiltered(p, "дубль", "активный дубль существующего лота");
                continue;
            }

            log.info("Публикация ЦДТРФ лота {}...", p.getNumber());
            boolean sent = bot.sendProcurementMessage(chatId, p);
            if (sent) {
                if (markSent && !databaseManager.markAsSent(p.getNumber())) {
                    // Лот ушёл в чат, но отметка НЕ сохранилась — дальше публиковать нельзя,
                    // иначе он и следующие уйдут повторно на каждом прогоне.
                    haltPublishing("не удалось сохранить отметку об отправке лота " + p.getNumber()
                            + " (лот уже опубликован, повтор недопустим)");
                    break;
                }
                databaseManager.clearFiltered(p.getNumber());
                published++;
                log.info("ЦДТРФ лот {} опубликован (markSent={})", p.getNumber(), markSent);
            } else {
                log.error("ЦДТРФ лот {} НЕ опубликован (ошибка отправки)", p.getNumber());
            }
        }
        // Обновляем статусы завершённых ЦДТРФ-лотов на уже опубликованных карточках
        updateCdtrfStatuses(parser);

        log.info("ЦДТРФ parsing completed. Total: {}, new: {}, published: {}",
                procurements.size(), newProcurements.size(), published);
        return published;
    }

    /**
     * Обновляет статус («Состоялся»/«Не состоялся»/…) на уже опубликованных ЦДТРФ-карточках.
     * Аналог torgi-шного обновления статусов, но по данным ЦДТРФ.
     */
    private void updateCdtrfStatuses(CdtrfParser parser) {
        java.util.Map<String, String> completed = parser.parseCompletedStatuses();
        if (completed.isEmpty()) {
            return;
        }
        List<Procurement> activeLots = databaseManager.getActiveSentProcurements();
        int updates = 0;
        for (Procurement lot : activeLots) {
            if (lot.getSource() == null || !lot.getSource().contains("ЦДТРФ")) {
                continue; // только ЦДТРФ-лоты
            }
            String newStatus = completed.get(lot.getNumber());
            if (newStatus == null) {
                continue;
            }
            databaseManager.updateLotStatus(lot.getNumber(), newStatus);
            Procurement updated = databaseManager.getProcurementByNumber(lot.getNumber());
            if (updated != null) {
                for (DatabaseManager.MessageMapping m : databaseManager.getMessageMappings(lot.getNumber())) {
                    try {
                        bot.updateProcurementMessage(m.chatId, m.messageId, updated);
                        updates++;
                    } catch (Exception e) {
                        log.error("ЦДТРФ: не удалось обновить сообщение {} лота {}: {}",
                                m.messageId, lot.getNumber(), e.getMessage());
                    }
                }
            }
        }
        log.info("ЦДТРФ: обновлено статусов на карточках: {}", updates);
    }

    /**
     * Парсит и публикует все новые закупки.
     * Torgi.gov.ru → публичный канал; новые источники (ЦДТРФ) → админ-группа на обкатку (debug).
     *
     * @param maxCount максимальное количество лотов для публикации из каждого источника
     * @param chatId ID чата для публикации (публичный канал torgi)
     * @param notifyAdminOnNoMatch отправлять ли уведомления админу о неопределенных лотах
     * @return общее количество опубликованных лотов
     */
    public int parseAndPublishAllSources(int maxCount, long chatId, boolean notifyAdminOnNoMatch) {
        int totalPublished = 0;

        // Предохранитель: при заполненном диске бот НЕ публикует вовсе и пишет админам.
        // Публиковать в таком состоянии нельзя — отметка isSent не сохранится, и те же лоты
        // уйдут в чат снова на следующем прогоне (инцидент 26–31.08.2026, 17 лотов по кругу).
        if (!preflightCheck()) {
            log.error("Прогон прерван предполётной проверкой — публикация не выполняется");
            return 0;
        }

        // Torgi.gov.ru → публичный канал (планировщик — публикует по-настоящему, markSent=true)
        totalPublished += parseAndPublishDefault(maxCount, chatId, notifyAdminOnNoMatch, true);

        // ЦДТРФ выведен в публичный канал (обкатка в админ-группе пройдена).
        // Откат на обкатку: заменить chatId на Config.getAdminGroupId().
        totalPublished += parseAndPublishCdtrf(maxCount, chatId, true);

        // SberAst → АДМИН-группа (обкатка). markSent=true намеренно: лот помечается отправленным,
        // поэтому в админ-группу он приходит ОДИН раз, а не на каждом прогоне.
        // Когда решим выводить SberAst в общий канал: заменить getAdminGroupId() на chatId и
        // сбросить флаг разом по всем его лотам —
        //   UPDATE procurements SET isSent = 0 WHERE source = 'sberbank-ast.ru';
        totalPublished += parseAndPublishSberAst(maxCount, Config.getAdminGroupId(), true);

        log.info("All sources parsing completed. Total published: {}", totalPublished);
        return totalPublished;
    }

    // Коды категорий torgi, которые НЕ публикуем (block-list движимого имущества/прочего).
    // 406 "Иное" — свалка движимого: лом, высвобождаемое военное имущество, суда и т.п.
    // ВНИМАНИЕ: 208 "Иной объект недвижимости" — это НЕДВИЖИМОСТЬ, его НЕ блокируем (похожее имя).
    // Полярность block-list: всё, кроме перечисленного, проходит (610/612/614 право размещения,
    // 304 лесной фонд, вся недвижимость). Новые категории torgi по умолчанию публикуются.
    // ВАЖНО: нумерация категорий НЕ иерархична — резать по префиксу/диапазону нельзя.
    // Транспорт и недвижимость перемешаны в одном диапазоне: 8 Здания, 9 Жилые, 11 Нежилые,
    // 16 Водный транспорт, 31 Автобусы, 47 Сооружения. Поэтому только явный перечень кодов.
    // Перечень собран по живой ленте Севастополя (280 лотов, 30.07.2026).
    private static final java.util.Set<String> BLOCKED_CATEGORIES = java.util.Set.of(
        "406",     // Иное (движимое: лом, военное движимое, «годные остатки»)
        "101",     // Спецтехника (самоходные машины, экскаваторы и т.п.)
        "100000",  // Мототехника (мотоциклы, мопеды, скутеры)
        "100001",  // Легковые автомобили
        "100002",  // Грузовые автомобили
        "31",      // Автобусы
        "110",     // Иной транспорт
        "16",      // Водный транспорт (суда — дублирует стем «судн» в описании)
        "400",     // Оборудование
        "402"      // Драгкамни и металлы, ювелирные изделия
    );

    // --- Контроль работоспособности источников ---
    // Ловит ТИХИЕ поломки: площадка отвечает 200, но данные уже не те. Именно так ломались
    // Сбербанк-АСТ (переезд на новый API) и ЦДТРФ (переименование RegionId → RegionIds,
    // из-за чего в очередь на публикацию встал 91 лот со всей России вместо Севастополя).
    private static final int ZERO_RUNS_TO_ALERT = 6;  // ~3 дня подряд по нулям (2 прогона в день)
    private static final int SPIKE_MIN = 10;          // всплеском считаем только заметные числа
    private static final int SPIKE_FACTOR = 5;        // во сколько раз больше обычного
    private static final long HEALTH_ALERT_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private final java.util.Map<String, Long> lastHealthAlertMs = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Записывает результат прогона источника и сравнивает его с обычным поведением.
     *
     * Порог считается от МЕДИАНЫ прошлых прогонов, а не задан числом: у torgi норма — десятки
     * лотов, у ЦДТРФ и SberAst — единицы или ноль, общий порог для них бессмысленен.
     *
     * Две тревоги:
     *  • источник давал лоты, а теперь {@value #ZERO_RUNS_TO_ALERT} прогонов подряд ноль — вероятно, парсер сломался;
     *  • лотов внезапно в {@value #SPIKE_FACTOR}+ раз больше обычного — вероятно, перестал работать фильтр.
     */
    private boolean checkSourceHealth(String source, int lotCount) {
        try {
            databaseManager.recordSourceRun(source, lotCount);
            java.util.List<Integer> recent = databaseManager.getRecentSourceCounts(source, 30);
            if (recent.size() < 5) {
                return true; // истории мало — выводы делать рано
            }
            java.util.List<Integer> previous = new ArrayList<>(recent.subList(1, recent.size()));
            java.util.List<Integer> sorted = new ArrayList<>(previous);
            java.util.Collections.sort(sorted);
            int median = sorted.get(sorted.size() / 2);

            String problem = null;
            boolean spike = false;
            if (lotCount == 0) {
                boolean allZero = true;
                for (int i = 0; i < Math.min(ZERO_RUNS_TO_ALERT, recent.size()); i++) {
                    if (recent.get(i) != 0) {
                        allZero = false;
                        break;
                    }
                }
                // ВАЖНО: сравниваем с периодом ДО начала тишины. Если брать медиану всего окна,
                // она сама обнуляется, когда источник молчит дольше половины истории, и поломка
                // перестаёт замечаться — эту дыру поймал тест на сценарии «SberAst молчит 6 прогонов».
                if (allZero && recent.size() > ZERO_RUNS_TO_ALERT) {
                    List<Integer> older = new ArrayList<>(recent.subList(ZERO_RUNS_TO_ALERT, recent.size()));
                    java.util.Collections.sort(older);
                    int olderMedian = older.get(older.size() / 2);
                    if (olderMedian > 0) {
                        problem = "источник " + ZERO_RUNS_TO_ALERT + " прогонов подряд не даёт ни одного лота"
                                + " (до этого обычно " + olderMedian + "). Похоже, парсер сломался — площадка"
                                + " могла сменить API или переименовать параметр";
                    }
                }
            } else if (lotCount >= SPIKE_MIN && lotCount > Math.max(SPIKE_MIN, median * SPIKE_FACTOR)) {
                spike = true;
                problem = "лотов внезапно " + lotCount + " при обычных " + median
                        + ". Похоже, перестал работать фильтр (например, по региону) — проверить до публикации";
            }

            if (problem == null) {
                return true;
            }
            log.warn("Контроль источника [{}]: {}", source, problem);

            long now = System.currentTimeMillis();
            Long last = lastHealthAlertMs.get(source);
            boolean alertNow = last == null || now - last >= HEALTH_ALERT_INTERVAL_MS;
            if (alertNow) {
                lastHealthAlertMs.put(source, now);
            }
            if (alertNow && bot != null) {
                bot.sendMessageWithRetry(Config.getAdminGroupId(),
                        "⚠ Источник «" + source + "» ведёт себя необычно.\n\n"
                                + problem + "." + "\n\n"
                                + "Лотов в этом прогоне: " + lotCount);
            }
            // Всплеск придерживаем: лоты никуда не денутся (isSent не ставится) и выйдут
            // следующим прогоном, когда причина подтвердится или отпадёт.
            return !spike;
        } catch (Exception e) {
            log.warn("Не удалось проверить работоспособность источника {}: {}", source, e.getMessage());
            return true;
        }
    }

    /**
     * Отмечает, что лот отсеян фильтром, и логирует это: WARN при первом появлении причины,
     * DEBUG при повторах (иначе лог засоряется одними и теми же лотами каждый прогон).
     *
     * КЛЮЧЕВОЕ: лот НЕ помечается отправленным. Он остаётся в очереди и проверяется заново
     * на каждом прогоне — поэтому лот, отсеянный по ошибке (временный сбой обогащения) или
     * временно (живой дубль), опубликуется, как только причина отпадёт. Например, перевыпуск
     * торгов с новыми датами: пока старый раунд идёт — новый лот подавляется как дубль, а как
     * только старый завершится (FAILED/CANCELED/SUCCEED) или у него пройдёт дедлайн,
     * isActiveDuplicate перестанет срабатывать и новый лот уйдёт в канал.
     */
    private void noteFiltered(Procurement p, String reason, String details) {
        if (databaseManager.recordFiltered(p.getNumber(), reason)) {
            log.warn("Лот {} пропущен [{}]: {}", p.getNumber(), reason, details);
        } else {
            log.debug("Лот {} снова пропущен [{}]: {}", p.getNumber(), reason, details);
        }
    }

    /**
     * Проверяет, относится ли лот к запрещённым категориям (движимое имущество/прочее).
     * Если категория не получена (обогащение не удалось) — не блокируем,
     * пусть работают прочие фильтры (регион/ключевые слова).
     */
    private boolean isBlockedCategory(Procurement p) {
        return p.getCategoryCode() != null && BLOCKED_CATEGORIES.contains(p.getCategoryCode());
    }

    // Признаки движимого имущества, проверяемые в ОПИСАНИИ лота (lotDescription) после обогащения.
    // Нужно потому, что keyword-фильтр на RSS-стадии видит только заголовок+адрес, а такие лоты
    // (напр. "Судно «ПС-379»", категория 208 = недвижимость по ГК ст.130) выдают себя только здесь.
    // Набор узкий и "физический" — полный exclude-список сюда нельзя ("транспорт" поймает
    // "транспортная доступность", "оборудование" — "с оборудованием" и т.п.).
    // Стемы: "судн" покрывает судно/судна/судну/судном/судне.
    private static final java.util.List<String> DESCRIPTION_EXCLUDE_STEMS = java.util.List.of(
        "судн", "плавсредств", "гидроцикл", "барж", "катер", "теплоход", "понтон", "яхт"
    );

    /**
     * Проверяет описание лота на признаки движимого имущества.
     * Стем матчится как ПРЕФИКС СЛОВА (судн*), а не свободная подстрока — иначе "судн"
     * поймал бы "посудный", "безрассудно" и т.п. Слова выделяются по не-буквам (\p{L}).
     * Если описание пустое — не блокируем.
     */
    private boolean isExcludedByDescription(Procurement p) {
        String d = p.getLotDescription();
        if (d == null || d.isEmpty()) {
            return false;
        }
        String[] words = d.toLowerCase().split("[^\\p{L}]+");
        for (String w : words) {
            for (String stem : DESCRIPTION_EXCLUDE_STEMS) {
                if (w.startsWith(stem)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * true, если лот — заведомо движимое имущество (по категории из block-list или по описанию).
     * Используется в no-match-пути, чтобы не слать админам на ревью известный мусор.
     */
    public boolean isMovableProperty(Procurement p) {
        return isBlockedCategory(p) || isExcludedByDescription(p);
    }

    /** Сколько суток до окончания подачи заявок ещё можно ждать точные координаты. */
    private static final int POINT_WAIT_MIN_DAYS_LEFT = 3;
    private long lastPostponeAlertMs = 0L;
    private static final long POSTPONE_ALERT_INTERVAL_MS = 12 * 60 * 60 * 1000L;

    /** Не больше стольких запросов точки за один прогон, чтобы не завалить админ-чат. */
    private static final int MAX_POINT_REQUESTS_PER_RUN = 5;

    /**
     * По каждому отложенному лоту просит админов проставить точку вручную — отдельным
     * сообщением, на которое можно ответить ссылкой на Яндекс.Карты. Повторно по тому же лоту
     * бот не спрашивает, так что поток сообщений ограничен новыми лотами.
     */
    private void notifyPostponedForPoint(List<Procurement> postponed) {
        log.warn("Отложено из-за отсутствия координат: {} лот(ов)", postponed.size());
        if (bot == null) {
            return;
        }
        int asked = 0;
        for (Procurement p : postponed) {
            if (asked >= MAX_POINT_REQUESTS_PER_RUN) {
                log.info("Достигнут предел запросов точки за прогон, остальные лоты подождут следующего");
                break;
            }
            bot.requestPointFromAdmins(p);
            asked++;
        }
    }

    /**
     * Готовит координаты лота перед публикацией.
     *
     * Если точка лота недостоверна (её нет, либо она общая для нескольких разных адресов —
     * так организаторы проставляют свой адрес на всё многолотовое извещение), пытаемся взять
     * координаты объекта из ЕГРН. НСПД жёстко ограничивает частоту обращений, поэтому ответ
     * приходит не всегда.
     *
     * @return true — публиковать; false — отложить лот до следующего прогона.
     *         Откладываем, только пока есть запас по времени: у лота с близким окончанием
     *         подачи заявок неточная метка лучше, чем пропущенная публикация.
     */
    private boolean ensureAccuratePoint(Procurement p) {
        boolean hasPoint = p.getLat() != null && p.getLon() != null;
        boolean alreadyExact = Procurement.POINT_SOURCE_CADASTRAL.equals(p.getPointSource())
                || Procurement.POINT_SOURCE_MANUAL.equals(p.getPointSource());
        if (alreadyExact) {
            return true;
        }
        boolean pointSuspicious = !hasPoint
                || databaseManager.isSharedPoint(p.getLat(), p.getLon(), p.getAddress());
        if (!pointSuspicious) {
            return true;
        }
        if (p.getCadastralNumber() == null || p.getCadastralNumber().isBlank()) {
            return true; // уточнять нечем — публикуем с тем, что есть
        }

        CadastralGeocoder.Point egrn = CadastralGeocoder.resolve(p.getCadastralNumber());
        if (egrn != null) {
            p.setLat(egrn.getLat());
            p.setLon(egrn.getLon());
            p.setPointSource(Procurement.POINT_SOURCE_CADASTRAL);
            databaseManager.updateCoordinates(p.getNumber(), egrn.getLat(), egrn.getLon(),
                    Procurement.POINT_SOURCE_CADASTRAL);
            return true;
        }

        long daysLeft = daysUntilDeadline(p);
        if (daysLeft >= 0 && daysLeft < POINT_WAIT_MIN_DAYS_LEFT) {
            log.warn("Лот {}: координаты из ЕГРН не получены, но до окончания подачи {} дн. — публикуем как есть",
                    p.getNumber(), daysLeft);
            return true;
        }
        log.info("Лот {} отложен: НСПД не отдал координаты по кадастру {} (в запасе {} дн.)",
                p.getNumber(), p.getCadastralNumber(), daysLeft);
        return false;
    }

    /** Дней до окончания подачи заявок; -1, если дату разобрать не удалось. */
    private long daysUntilDeadline(Procurement p) {
        if (p.getDeadline() == null || p.getDeadline().isBlank()) {
            return -1;
        }
        try {
            java.time.OffsetDateTime deadline = java.time.OffsetDateTime.parse(p.getDeadline());
            return java.time.Duration.between(java.time.OffsetDateTime.now(), deadline).toDays();
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isFromSevastopol(Procurement p) {
        // subjectRfCode — authoritative-код субъекта РФ (Севастополь = 92).
        // Если он известен, доверяем только ему: это отсекает лоты с битым estateAddress
        // (например, лот из Находки приходил с estateAddress="г. Севастополь", но subjectRFCode=25).
        if (p.getSubjectRfCode() != null && !p.getSubjectRfCode().isEmpty()) {
            return "92".equals(p.getSubjectRfCode());
        }
        // Fallback для лотов без обогащения (subjectRfCode не получен)
        if (p.getNumber() != null && p.getNumber().startsWith("91")) return true;
        if (p.getCadastralNumber() != null && p.getCadastralNumber().startsWith("91:")) return true;
        if (p.getAddress() != null && p.getAddress().toLowerCase().contains("севастополь")) return true;
        return false;
    }
}

