package com.example.procurement;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер для завершенных/неактуальных лотов с torgi.gov.ru
 * Извлекает статусы лотов: Состоялся, Не состоялся, Отменен, Прием заявок приостановлен
 */
@Slf4j
public class CompletedLotsParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("lot/([\\d:_]+)");
    private static final Pattern STATUS_PATTERN = Pattern.compile("<b>Статус лота:</b>\\s*([^<]+)<br>");

    private final String completedLotsRssUrl;

    public CompletedLotsParser(String completedLotsRssUrl) {
        this.completedLotsRssUrl = completedLotsRssUrl;
    }

    /**
     * Парсит RSS-ленту завершенных лотов и возвращает карту: номер лота -> статус
     *
     * @return Map где ключ - номер лота, значение - статус лота
     */
    public Map<String, String> parseCompletedLots() {
        return parseCompletedLots(null);
    }

    /**
     * Парсит RSS-ленту завершенных лотов с оптимизацией
     * Останавливается когда встречает лот, которого нет в списке активных (т.к. дальше только старые)
     *
     * @param activeLotNumbers Список номеров активных лотов из БД для оптимизации (может быть null)
     * @return Map где ключ - номер лота, значение - статус лота
     */
    public Map<String, String> parseCompletedLots(java.util.Set<String> activeLotNumbers) {
        Map<String, String> lotStatuses = new HashMap<>();
        log.info("Starting parsing of completed lots from URL: {}", completedLotsRssUrl);
        if (activeLotNumbers != null) {
            log.info("Using optimization: will stop when encountering lot not in DB (active lots count: {})", activeLotNumbers.size());
        }

        try {
            java.net.URL url = URI.create(completedLotsRssUrl).toURL();
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed;
            try (InputStream is = url.openStream(); XmlReader xr = new XmlReader(is)) {
                feed = input.build(xr);
            }
            List<SyndEntry> entries = feed.getEntries();
            log.info("Found {} items in completed lots RSS feed", entries.size());

            int processedCount = 0;
            int statusExtractedCount = 0;
            int consecutiveNotInDb = 0;
            final int MAX_CONSECUTIVE_NOT_IN_DB = 5; // Останавливаемся после 5 подряд отсутствующих лотов

            for (SyndEntry entry : entries) {
                processedCount++;
                String link = entry.getLink();
                String description = entry.getDescription() != null ? entry.getDescription().getValue() : "";

                if (Config.getParserVerbose()) {
                    log.debug("Processing completed lot #{}: {}", processedCount, entry.getTitle());
                }

                String number = extractNumberFromLink(link);
                if (number == null) {
                    log.warn("No valid number found in link: {}", link);
                    continue;
                }

                // Оптимизация: если лота нет в списке активных, вероятно дальше только старые лоты
                if (activeLotNumbers != null && !activeLotNumbers.contains(number)) {
                    consecutiveNotInDb++;
                    if (consecutiveNotInDb >= MAX_CONSECUTIVE_NOT_IN_DB) {
                        log.info("Found {} consecutive lots not in DB, stopping parsing (optimization)", consecutiveNotInDb);
                        break;
                    }
                    if (Config.getParserVerbose()) {
                        log.debug("Lot {} not in active list, skipping (consecutive: {})", number, consecutiveNotInDb);
                    }
                    continue;
                } else {
                    consecutiveNotInDb = 0; // Сбрасываем счетчик если нашли актуальный лот
                }

                String status = extractStatus(description);
                if (status != null) {
                    lotStatuses.put(number, status);
                    statusExtractedCount++;
                    log.debug("Extracted status for lot {}: {}", number, status);
                } else {
                    log.debug("No status found for lot {}", number);
                }
            }

            log.info("Completed lots parsing summary: processed={}, status_extracted={}, stopped_early={}",
                    processedCount, statusExtractedCount, consecutiveNotInDb >= MAX_CONSECUTIVE_NOT_IN_DB);
        } catch (Exception e) {
            log.error("Error parsing completed lots RSS feed from {}: {}", completedLotsRssUrl, e.getMessage(), e);
        }

        log.info("Total completed lots with statuses: {}", lotStatuses.size());
        return lotStatuses;
    }

    /**
     * Извлекает номер лота из ссылки
     */
    private String extractNumberFromLink(String link) {
        if (link == null) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(link);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Извлекает статус лота из description RSS-записи
     * Ищет паттерн: <b>Статус лота:</b> Состоялся<br>
     *
     * @param description HTML-описание из RSS
     * @return Нормализованный статус (SUCCEED, FAILED, CANCELED, SUSPENDED) или null
     */
    private String extractStatus(String description) {
        if (description == null || description.isEmpty()) {
            return null;
        }

        Matcher matcher = STATUS_PATTERN.matcher(description);
        if (matcher.find()) {
            String rawStatus = matcher.group(1).trim();
            // Нормализуем статус для удобства обработки
            return normalizeStatus(rawStatus);
        }
        return null;
    }

    /**
     * Нормализует русский текст статуса в константу
     *
     * @param rawStatus Текст статуса на русском языке
     * @return Нормализованная константа статуса
     */
    private String normalizeStatus(String rawStatus) {
        if (rawStatus == null) {
            return null;
        }

        String statusLower = rawStatus.toLowerCase().trim();

        if (statusLower.contains("состоялся") && !statusLower.contains("не состоялся")) {
            return "SUCCEED";
        } else if (statusLower.contains("не состоялся")) {
            return "FAILED";
        } else if (statusLower.contains("отменен")) {
            return "CANCELED";
        } else if (statusLower.contains("приостановлен")) {
            return "SUSPENDED";
        }

        // Возвращаем оригинальный текст, если не удалось распознать
        log.warn("Unknown status format: {}", rawStatus);
        return rawStatus;
    }

    /**
     * Получает человекочитаемое название статуса на русском языке
     *
     * @param status Нормализованный статус
     * @return Текст статуса на русском
     */
    public static String getStatusDisplayName(String status) {
        if (status == null) {
            return null;
        }

        switch (status) {
            case "SUCCEED":
                return "Состоялся";
            case "FAILED":
                return "Не состоялся";
            case "CANCELED":
                return "Отменен";
            case "SUSPENDED":
                return "Прием заявок приостановлен";
            case "ACTIVE":
                return "Активный";
            default:
                return status; // Возвращаем как есть для неизвестных статусов
        }
    }
}
