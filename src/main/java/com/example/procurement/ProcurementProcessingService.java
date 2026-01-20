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
     * Парсит и публикует закупки из указанных источников
     *
     * @param sources список источников для парсинга
     * @param maxCount максимальное количество лотов для публикации (Integer.MAX_VALUE для всех)
     * @param chatId ID чата для публикации
     * @param notifyAdminOnNoMatch отправлять ли уведомления админу о неопределенных лотах
     * @return количество опубликованных лотов
     */
    public int parseAndPublish(List<ParsingSource> sources, int maxCount, long chatId, boolean notifyAdminOnNoMatch) {
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

        // Сохраняем все закупки в БД
        databaseManager.saveProcurements(allProcurements);

        // Проверяем изменения в существующих активных лотах
        checkForUpdates(allProcurements, chatId);

        // Получаем только новые закупки
        List<Procurement> newProcurements = databaseManager.getNewProcurements(allProcurements);

        // Публикуем новые закупки
        int published = 0;
        for (Procurement p : newProcurements) {
            if (published >= maxCount) break;

            log.info("Публикация лота {}...", p.getNumber());
            bot.sendProcurementMessage(chatId, p);
            databaseManager.markAsSent(p.getNumber());
            published++;
            log.info("Лот {} опубликован и помечен как отправленный", p.getNumber());
        }

        log.info("Parsing completed. Total procurements: {}, new: {}, published: {}",
            allProcurements.size(), newProcurements.size(), published);

        return published;
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
    public int parseAndPublishDefault(int maxCount, long chatId, boolean notifyAdminOnNoMatch) {
        List<ParsingSource> sources = List.of(
            new ParsingSource("Torgi.gov.ru (Севастополь)", Config.getRssUrl())
        );
        return parseAndPublish(sources, maxCount, chatId, notifyAdminOnNoMatch);
    }
    
    /**
     * Парсит и публикует все закупки из всех сконфигурированных источников
     */
    public int parseAndPublishAll(long chatId, boolean notifyAdminOnNoMatch) {
        List<ParsingSource> sources = Config.getParsingSources();
        return parseAndPublish(sources, Integer.MAX_VALUE, chatId, notifyAdminOnNoMatch);
    }

    /**
     * Парсит и публикует закупки с Сбербанк-АСТ
     * 
     * @param maxCount максимальное количество лотов для публикации (Integer.MAX_VALUE для всех)
     * @param chatId ID чата для публикации
     * @return количество опубликованных лотов
     */
    public int parseAndPublishSberAst(int maxCount, long chatId) {
        log.info("Starting SberAst parsing and publishing");
        
        SberAstParser sberParser = new SberAstParser();
        List<Procurement> procurements = sberParser.parse(Integer.MAX_VALUE, true);
        
        // Сохраняем все закупки в БД
        databaseManager.saveProcurements(procurements);
        
        // Получаем только новые закупки
        List<Procurement> newProcurements = databaseManager.getNewProcurements(procurements);
        
        // Публикуем новые закупки
        int published = 0;
        for (Procurement p : newProcurements) {
            if (published >= maxCount) break;
            
            log.info("Публикация SberAst лота {}...", p.getNumber());
            bot.sendProcurementMessage(chatId, p);
            databaseManager.markAsSent(p.getNumber());
            published++;
            log.info("SberAst лот {} опубликован и помечен как отправленный", p.getNumber());
        }
        
        log.info("SberAst parsing completed. Total procurements: {}, new: {}, published: {}", 
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
            if (published >= maxCount) break;
            
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
     * Парсит и публикует все новые закупки (только Torgi.gov.ru)
     * SberAst и ЦДТРФ временно отключены
     *
     * @param maxCount максимальное количество лотов для публикации из каждого источника
     * @param chatId ID чата для публикации
     * @param notifyAdminOnNoMatch отправлять ли уведомления админу о неопределенных лотах
     * @return общее количество опубликованных лотов
     */
    public int parseAndPublishAllSources(int maxCount, long chatId, boolean notifyAdminOnNoMatch) {
        int totalPublished = 0;

        // Парсим Torgi.gov.ru
        totalPublished += parseAndPublishDefault(maxCount, chatId, notifyAdminOnNoMatch);

        // TODO: Временно отключены SberAst и ЦДТРФ парсеры
        // totalPublished += parseAndPublishSberAst(maxCount, chatId);
        // totalPublished += parseAndPublishBankrotCdtrf(maxCount, chatId, notifyAdminOnNoMatch);

        log.info("All sources parsing completed. Total published: {}", totalPublished);
        return totalPublished;
    }
}

