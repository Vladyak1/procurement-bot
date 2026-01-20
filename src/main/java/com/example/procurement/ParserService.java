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

                    return procurements;
                } else {
                    log.warn("Region validation FAILED on attempt {}/{}: {}",
                        attempt, MAX_RETRY_ATTEMPTS, validation.message);

                    // Отправляем уведомление админам
                    sendValidationFailureNotification(attempt, validation);

                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        // Ждем перед следующей попыткой
                        log.info("Waiting {} minutes before retry...", RETRY_DELAY_MINUTES);
                        Thread.sleep(RETRY_DELAY_MINUTES * 60 * 1000);
                    } else {
                        // Последняя попытка провалена
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
     * Отправляет уведомление о неудачной валидации региона
     */
    private void sendValidationFailureNotification(int attempt, RegionValidator.ValidationResult validation) {
        try {
            TelegramBot bot = AppContext.getBot();
            if (bot != null) {
                StringBuilder message = new StringBuilder();
                message.append("⚠️ <b>Парсинг обнаружил лоты из неправильных регионов</b>\n\n");
                message.append("Попытка: ").append(attempt).append("/").append(MAX_RETRY_ATTEMPTS).append("\n");
                message.append("Проблема: ").append(validation.message).append("\n\n");

                if (!validation.wrongRegions.isEmpty()) {
                    message.append("Обнаруженные регионы:\n");
                    for (String region : validation.wrongRegions) {
                        message.append("• ").append(region).append("\n");
                    }
                    message.append("\n");
                }

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    message.append("RSS API может временно работать некорректно.\n");
                    message.append("Ожидается автоматический перезапуск через ")
                           .append(RETRY_DELAY_MINUTES).append(" минут.");
                }

                org.telegram.telegrambots.meta.api.methods.send.SendMessage msg =
                    new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
                msg.setChatId(Config.getAdminGroupId());
                msg.setText(message.toString());
                msg.setParseMode("HTML");
                bot.execute(msg);
            }
        } catch (Exception e) {
            log.error("Failed to send validation failure notification: {}", e.getMessage());
        }
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
