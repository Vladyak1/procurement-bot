package com.example.procurement;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Парсер для завершенных/неактуальных лотов с torgi.gov.ru.
 * Лента даёт список завершившихся лотов, точный статус каждого берётся из его карточки.
 */
@Slf4j
public class CompletedLotsParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("lot/([\\d:_]+)");

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
            log.info("Scanning completed lots RSS for {} active lots from DB", activeLotNumbers.size());
        }

        try {
            java.net.URL url = URI.create(completedLotsRssUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);

            // Актуальные браузерные заголовки
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"");
            conn.setRequestProperty("Sec-Ch-Ua-Mobile", "?0");
            conn.setRequestProperty("Sec-Ch-Ua-Platform", "\"Windows\"");
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            conn.setRequestProperty("Sec-Fetch-Site", "none");
            conn.setRequestProperty("Connection", "keep-alive");

            int responseCode = conn.getResponseCode();
            log.info("Completed lots RSS response: code={}, content-type={}", responseCode, conn.getContentType());

            // Обработка редиректов
            if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                String location = conn.getHeaderField("Location");
                log.warn("!!! REDIRECT in completed lots RSS: {} -> {}", completedLotsRssUrl, location);
                throw new RuntimeException("Redirect detected in completed lots RSS: " + location);
            }

            if (responseCode != 200) {
                log.error("Completed lots RSS returned HTTP {}", responseCode);
                throw new RuntimeException("Completed lots RSS returned HTTP " + responseCode);
            }

            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed;
            try (InputStream is = getDecodedInputStream(conn); XmlReader xr = new XmlReader(is)) {
                feed = input.build(xr);
            }
            List<SyndEntry> entries = feed.getEntries();
            log.info("Found {} items in completed lots RSS feed", entries.size());

            int processedCount = 0;
            int statusExtractedCount = 0;

            for (SyndEntry entry : entries) {
                processedCount++;
                String link = entry.getLink();

                if (Config.getParserVerbose()) {
                    log.debug("Processing completed lot #{}: {}", processedCount, entry.getTitle());
                }

                String number = extractNumberFromLink(link);
                if (number == null) {
                    log.warn("No valid number found in link: {}", link);
                    continue;
                }

                // Пропускаем лоты, которых нет в нашем активном списке — они нам не нужны
                if (activeLotNumbers != null && !activeLotNumbers.contains(number)) {
                    if (Config.getParserVerbose()) {
                        log.debug("Lot {} not in active list, skipping", number);
                    }
                    continue;
                }

                // Статус берём из карточки лота, а НЕ из описания RSS: в поле «Статус лота»
                // ленты приходит эхо фильтра запроса — буквально «Состоялся, Не состоялся,
                // Отменен, Прием заявок приостановлен» для каждого элемента. Старый разбор
                // видел там «не состоялся» и всем подряд проставлял FAILED.
                // Сама лента остаётся полезной как признак «лот завершился»: запрос к API
                // делаем только для наших активных лотов, попавших в неё, — это единицы.
                String status = fetchStatusFromApi(number);
                if (status != null) {
                    lotStatuses.put(number, status);
                    statusExtractedCount++;
                    log.info("Статус лота {} из карточки: {}", number, status);
                } else {
                    log.warn("Не удалось получить статус лота {} из карточки", number);
                }
            }

            log.info("Completed lots parsing summary: processed={}, status_extracted={}", processedCount, statusExtractedCount);
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
     * Берёт актуальный статус лота из его карточки в API torgi.
     * Значения приходят готовыми константами (SUCCEED, FAILED, CANCELED,
     * APPLICATIONS_SUBMISSION_SUSPENDED), нормализация не нужна.
     *
     * @return статус либо null, если карточка недоступна — тогда лучше не трогать
     *         сохранённый статус, чем записать выдуманный
     */
    public static String fetchStatusFromApi(String number) {
        HttpURLConnection conn = null;
        try {
            String url = Config.getXhrUrl() + number;
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Referer", "https://torgi.gov.ru/new/public/lots/lot/" + number);

            if (conn.getResponseCode() != 200) {
                log.warn("Карточка лота {}: HTTP {}", number, conn.getResponseCode());
                return null;
            }
            try (InputStream is = getDecodedInputStream(conn)) {
                com.fasterxml.jackson.databind.JsonNode root =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(is);
                String status = root.path("lotStatus").asText(null);
                return (status != null && !status.isEmpty()) ? status : null;
            }
        } catch (Exception e) {
            log.warn("Не удалось прочитать статус лота {}: {}", number, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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

    /**
     * Получает декодированный InputStream с учетом Content-Encoding (gzip, deflate)
     */
    private static InputStream getDecodedInputStream(HttpURLConnection conn) throws Exception {
        String encoding = conn.getContentEncoding();
        InputStream is = conn.getInputStream();

        if ("gzip".equalsIgnoreCase(encoding)) {
            log.debug("Decoding gzip response");
            return new GZIPInputStream(is);
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            log.debug("Decoding deflate response");
            return new java.util.zip.InflaterInputStream(is);
        }

        return is;
    }
}
