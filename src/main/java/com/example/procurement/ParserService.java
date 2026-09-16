package com.example.procurement;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ParserService {
    private final LotPageParser lotPageParser = new LotPageParser();
    private final RegionValidator regionValidator = new RegionValidator();

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MINUTES = 3;

    /**
     * Парсит и обогащает закупки из указанного источника с проверкой региона
     */
    public List<Procurement> parseAndEnrich(ParsingSource source, int maxCount, boolean notifyAdminOnNoMatch) {
        int attempt = 0;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            attempt++;
            log.info("Parsing attempt {}/{} from source: {}", attempt, MAX_RETRY_ATTEMPTS, source.getName());

            try {
                RssParser rssParser = new RssParser(source);
                List<Procurement> procurements = rssParser.parseUntilEnough(maxCount, notifyAdminOnNoMatch);

                // Валидация региона
                RegionValidator.ValidationResult validation = regionValidator.validate(procurements);

                if (validation.isValid) {
                    log.info("Region validation passed: {}", validation.message);

                    // Обогащаем лоты дополнительной информацией
                    for (Procurement p : procurements) {
                        lotPageParser.enrichProcurement(p, source.getXhrUrl());
                    }

                    // Обрабатываем неопределённые (no-match) лоты: обогащаем, фильтруем по городу,
                    // и для севастопольских шлём админам на ревью с кнопками
                    if (notifyAdminOnNoMatch) {
                        processNoMatchLots(rssParser.getNoMatchLots(), source);
                    }

                    return procurements;
                } else {
                    log.warn("Region validation FAILED on attempt {}/{}: {}",
                        attempt, MAX_RETRY_ATTEMPTS, validation.message);

                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        // Ждем перед следующей попыткой — уведомление не отправляем, ретрай может помочь
                        log.info("Waiting {} minutes before retry...", RETRY_DELAY_MINUTES);
                        Thread.sleep(RETRY_DELAY_MINUTES * 60 * 1000);
                    } else {
                        // Все попытки провалены — уведомляем
                        log.error("All {} attempts failed. Returning empty list.", MAX_RETRY_ATTEMPTS);
                        sendFinalFailureNotification(validation);
                        return new ArrayList<>();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Parsing interrupted during retry wait", e);
                return new ArrayList<>();
            } catch (Exception e) {
                log.error("Error during parsing attempt {}/{}: {}", attempt, MAX_RETRY_ATTEMPTS, e.getMessage(), e);

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        log.info("Waiting {} minutes before retry after error...", RETRY_DELAY_MINUTES);
                        Thread.sleep(RETRY_DELAY_MINUTES * 60 * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new ArrayList<>();
                    }
                }
            }
        }

        return new ArrayList<>();
    }

    /**
     * Обрабатывает неопределённые (no-match) лоты: обогащает, отсекает не-севастопольские
     * (по subjectRfCode), а севастопольские отправляет админам на ревью с кнопками ✅/❌.
     * Помечает все обработанные в no_match_lots, чтобы не повторять каждый прогон.
     */
    private void processNoMatchLots(List<Procurement> noMatchLots, ParsingSource source) {
        if (noMatchLots == null || noMatchLots.isEmpty()) {
            return;
        }
        DatabaseManager db = AppContext.getDatabaseManager();
        TelegramBot bot = AppContext.getBot();
        if (bot == null || db == null) {
            return;
        }
        log.info("Обработка {} no-match лотов для ревью админами", noMatchLots.size());
        for (Procurement p : noMatchLots) {
            try {
                if (db.isNoMatchSent(p.getNumber())) {
                    continue; // уже отправляли на ревью ранее
                }
                lotPageParser.enrichProcurement(p, source.getXhrUrl());
                // Фильтр по городу: беспокоим админов только севастопольскими лотами
                if (!"92".equals(p.getSubjectRfCode())) {
                    log.info("No-match лот {} не из Севастополя (subjRF={}), на ревью не отправляем",
                            p.getNumber(), p.getSubjectRfCode());
                    db.markNoMatchSent(p.getNumber()); // чтобы не обогащать снова каждый прогон
                    continue;
                }
                // Авто-отсев заведомо движимого имущества (категория 406/101, признак в описании):
                // на ревью идут только genuinely-ambiguous лоты, а не известный мусор
                ProcurementProcessingService pps = AppContext.getProcessingService();
                if (pps != null && pps.isMovableProperty(p)) {
                    log.info("No-match лот {} — заведомо движимое (cat={}), на ревью не шлём",
                            p.getNumber(), p.getCategoryCode());
                    db.markNoMatchSent(p.getNumber());
                    continue;
                }
                bot.sendNoMatchLotForReview(p);
                db.markNoMatchSent(p.getNumber());
                log.info("No-match лот {} отправлен админам на ревью", p.getNumber());
            } catch (Exception e) {
                log.warn("Ошибка обработки no-match лота {}: {}", p.getNumber(), e.getMessage());
            }
        }
    }

    /**
     * Обогащает один лот (используется при одобрении no-match лота админом).
     */
    public void enrichOne(Procurement p) {
        lotPageParser.enrichProcurement(p, Config.getXhrUrl());
    }

    /**
     * Отправляет финальное уведомление о провале всех попыток
     */
    private void sendFinalFailureNotification(RegionValidator.ValidationResult validation) {
        try {
            TelegramBot bot = AppContext.getBot();
            if (bot != null) {
                StringBuilder message = new StringBuilder();
                message.append("❌ <b>Парсинг не удался после ").append(MAX_RETRY_ATTEMPTS).append(" попыток</b>\n\n");
                message.append("Проблема: ").append(validation.message).append("\n\n");

                if (!validation.wrongRegions.isEmpty()) {
                    message.append("Обнаруженные регионы:\n");
                    for (String region : validation.wrongRegions) {
                        message.append("• ").append(region).append("\n");
                    }
                    message.append("\n");
                }

                message.append("Возможные причины:\n");
                message.append("• RSS URL настроен неправильно\n");
                message.append("• API torgi.gov.ru недоступно или работает некорректно\n");
                message.append("• На сайте временно нет лотов из Севастополя\n\n");
                message.append("Проверьте настройки RSS_URL в .env файле.");

                org.telegram.telegrambots.meta.api.methods.send.SendMessage msg =
                    new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
                msg.setChatId(Config.getAdminGroupId());
                msg.setText(message.toString());
                msg.setParseMode("HTML");
                bot.execute(msg);
            }
        } catch (Exception e) {
            log.error("Failed to send final failure notification: {}", e.getMessage());
        }
    }

    /**
     * Парсит и обогащает закупки из источника по умолчанию (для обратной совместимости)
     */
    public List<Procurement> parseAndEnrich(int maxCount, boolean notifyAdminOnNoMatch) {
        ParsingSource defaultSource = new ParsingSource("Default", Config.getRssUrl(), Config.getXhrUrl());
        return parseAndEnrich(defaultSource, maxCount, notifyAdminOnNoMatch);
    }
}
