package com.example.procurement;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

@Slf4j
public class RssParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("lot/([\\d:_]+)");
    private static final Pattern CADASTRAL_PATTERN = Pattern.compile("(\\d{2}:\\d{2}:\\d{6,7}:\\d+)");
    private static final Pattern AREA_PATTERN = Pattern.compile("площадью\\s*([\\d,.]+)\\s*кв\\.?\\s*м");
    private static final Pattern PRICE_PATTERN = Pattern.compile("Начальная цена:\\s*([\\d.]+)");
    private static final Pattern LOT_TYPE_PATTERN = Pattern.compile("Вид торгов:(?:</b>|</B>)?\\s*([^<]+)");

    private final ParsingSource source;
    private final LotFilter lotFilter;
    // Лоты с неопределённой пригодностью (ни include, ни exclude) — кандидаты на ревью админами
    private final List<Procurement> noMatchLots = new ArrayList<>();

    public RssParser(ParsingSource source) {
        this.source = source;
        this.lotFilter = LotFilter.createDefault();
    }

    /** Возвращает no-match лоты, собранные за последний прогон parseUntilEnough. */
    public List<Procurement> getNoMatchLots() {
        return noMatchLots;
    }

    /**
     * Прогревает сессию: делает запрос к странице с фильтром Севастополя,
     * чтобы сервер установил сессионные куки с региональными предпочтениями.
     * Без этих кук torgi.gov.ru игнорирует параметр dynSubjRF и возвращает
     * нефильтрованный общероссийский фид. Выполняется перед каждым RSS-запросом.
     */
    private static void ensureSession(String rssUrl) {
        try {
            // Извлекаем базовый хост и dynSubjRF из RSS URL для warmup-запроса
            String warmupUrl = "https://torgi.gov.ru/new/public/lots/hotOffers";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("dynSubjRF=(\\d+)").matcher(rssUrl);
            if (m.find()) {
                warmupUrl += "?dynSubjRF=" + m.group(1)
                        + "&lotStatus=PUBLISHED,APPLICATIONS_SUBMISSION"
                        + "&matchPhrase=false&byFirstVersion=true";
            }
            log.info("Warming up session with: {}", warmupUrl);
            java.net.HttpURLConnection warmup = (java.net.HttpURLConnection)
                    java.net.URI.create(warmupUrl).toURL().openConnection();
            warmup.setRequestMethod("GET");
            warmup.setConnectTimeout(15000);
            warmup.setReadTimeout(15000);
            warmup.setInstanceFollowRedirects(true);
            warmup.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            warmup.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            warmup.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            warmup.setRequestProperty("Accept-Encoding", "gzip, deflate");
            warmup.setRequestProperty("Connection", "keep-alive");
            int code = warmup.getResponseCode();
            warmup.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            warmup.disconnect();
            log.info("Session warmup done (HTTP {}), cookies established", code);
        } catch (Exception e) {
            log.warn("Session warmup failed (will proceed anyway): {}", e.getMessage());
        }
    }

    public List<Procurement> parseUntilEnough(final int maxCount, final boolean notifyAdminOnNoMatch) {
        List<Procurement> procurements = new ArrayList<>();
        Set<String> seenNumbers = new java.util.HashSet<>();
        noMatchLots.clear();
        log.info("Starting RSS parsing from URL: {}", source.getRssUrl());
        log.info("Max count requested: {}, notify on no match: {}", maxCount, notifyAdminOnNoMatch);

        // Прогреваем сессию перед первым RSS-запросом
        ensureSession(source.getRssUrl());

        try {
            java.net.URL url = URI.create(source.getRssUrl()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            // ВАЖНО: Отключаем автоматическое следование редиректам для диагностики
            conn.setInstanceFollowRedirects(false);

            // Актуальные браузерные заголовки (Chrome 122, январь 2024)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"");
            conn.setRequestProperty("Sec-Ch-Ua-Mobile", "?0");
            conn.setRequestProperty("Sec-Ch-Ua-Platform", "\"Windows\"");
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            conn.setRequestProperty("Sec-Fetch-Site", "none");
            conn.setRequestProperty("Sec-Fetch-User", "?1");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            conn.setRequestProperty("Connection", "keep-alive");

            int responseCode = conn.getResponseCode();
            String contentType = conn.getContentType();
            String finalUrl = conn.getURL().toString();

            // Детальное логирование для диагностики
            log.info("=== RSS Response Diagnostics ===");
            log.info("Request URL: {}", source.getRssUrl());
            log.info("Response Code: {}", responseCode);
            log.info("Content-Type: {}", contentType);
            log.info("Final URL: {}", finalUrl);
            log.info("Content-Encoding: {}", conn.getContentEncoding());
            log.info("Content-Length: {}", conn.getContentLength());

            // Обработка редиректов
            if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                String location = conn.getHeaderField("Location");
                log.warn("!!! REDIRECT DETECTED !!!");
                log.warn("Redirect from: {}", source.getRssUrl());
                log.warn("Redirect to: {}", location);
                log.warn("This may indicate bot detection or API changes!");

                // Уведомляем админов о редиректе
                notifyAdminAboutRedirect(responseCode, location);

                throw new RuntimeException("Redirect detected (HTTP " + responseCode + ") to: " + location +
                    " - possible bot detection or filter not applied by server");
            }

            if (responseCode != 200) {
                log.error("RSS feed returned HTTP {}: {}", responseCode, source.getRssUrl());
                // Попытаемся прочитать тело ошибки для диагностики
                try {
                    InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        String errorBody = readFirstBytes(errorStream, 500);
                        log.error("Error response body (first 500 chars): {}", errorBody);
                    }
                } catch (Exception ex) {
                    log.debug("Could not read error stream: {}", ex.getMessage());
                }
                throw new RuntimeException("RSS feed returned HTTP " + responseCode);
            }

            // Проверяем Content-Type - должен быть XML/RSS
            if (contentType != null && !contentType.contains("xml") && !contentType.contains("rss")) {
                log.warn("!!! UNEXPECTED CONTENT-TYPE !!!");
                log.warn("Expected XML/RSS, got: {}", contentType);
                log.warn("This may indicate a redirect to HTML page (captcha, block, etc.)");

                // Читаем первые байты для диагностики
                InputStream is = getDecodedInputStream(conn);
                String preview = readFirstBytes(is, 1000);
                log.warn("Response preview (first 1000 chars): {}", preview);
                is.close();

                // Если это HTML - скорее всего блокировка
                if (contentType.contains("html") || preview.contains("<!DOCTYPE") || preview.contains("<html")) {
                    notifyAdminAboutHtmlResponse(contentType, preview);
                    throw new RuntimeException("Received HTML instead of RSS - possible bot block or captcha");
                }
            }

            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed;
            try (InputStream is = getDecodedInputStream(conn); XmlReader xr = new XmlReader(is)) {
                feed = input.build(xr);
            }
            List<SyndEntry> entries = feed.getEntries();
            log.info("Found {} items in RSS feed", entries.size());

            int processedCount = 0;
            int filteredOutCount = 0;
            int nullNumberCount = 0;
            int duplicateCount = 0;

            for (SyndEntry entry : entries) {
                processedCount++;
                String title = entry.getTitle();
                String link = entry.getLink();
                log.info("Processing RSS lot #{}: {}", processedCount, title);
                log.debug("Link: {}", link);

                if (procurements.size() >= maxCount) {
                    log.info("Reached max count of {}, stopping", maxCount);
                    break;
                }

                String description = entry.getDescription() != null ? entry.getDescription().getValue() : "";
                String number = extractNumberFromLink(link);

                // Проверяем "Вид торгов" из описания — отсеиваем непрофильные процедуры (МКД, ТКО и т.д.)
                if (isExcludedLotType(description)) {
                    filteredOutCount++;
                    log.info("Lot #{} discarded by lot type (not real estate): {}", processedCount, title);
                    continue;
                }

                LotFilter.Classification cls = lotFilter.classify(title, null);
                if (cls == LotFilter.Classification.EXCLUDED) {
                    filteredOutCount++;
                    log.info("Lot #{} discarded by filter (excluded): {}", processedCount, title);
                    continue;
                }
                if (cls == LotFilter.Classification.NO_MATCH) {
                    filteredOutCount++;
                    // Собираем для ревью админами (обогащение и фильтр по городу — позже, в ParserService)
                    if (notifyAdminOnNoMatch && number != null && seenNumbers.add(number)) {
                        noMatchLots.add(Procurement.builder()
                                .number(number)
                                .title(title)
                                .link(link)
                                .source(source.getName())
                                .build());
                        log.info("Lot #{} no-match — собран на ревью админам: {}", processedCount, title);
                    } else {
                        log.info("Lot #{} no-match (undetermined), пропущен: {}", processedCount, title);
                    }
                    continue;
                }

                if (number == null) {
                    nullNumberCount++;
                    log.warn("No valid number found in link for lot #{}: {}", processedCount, link);
                    log.warn("Skipping procurement with null number: {}", title);
                    continue;
                }

                if (seenNumbers.contains(number)) {
                    duplicateCount++;
                    log.debug("Duplicate number {} skipped for lot #{}", number, processedCount);
                    continue;
                }
                seenNumbers.add(number);

                Procurement procurement = Procurement.builder()
                        .number(number)
                        .title(title)
                        .link(link)
                        .lotType(extractLotType(title))
                        .address(extractAddress(title))
                        .price(extractPrice(description))
                        .monthlyPrice(extractMonthlyPrice(title))
                        .deposit(extractDeposit(title))
                        .contractTerm(extractContractTerm(title))
                        .deadline(extractDeadline(entry.getPublishedDate()))
                        .cadastralNumber(extractCadastralNumber(title))
                        .area(extractArea(title))
                        .imageUrls(new ArrayList<>())
                        .source(source.getName())
                        .build();
                procurements.add(procurement);
                log.info("✓ Added suitable procurement #{}: {}", procurements.size(), title);

                if (procurements.size() >= maxCount) {
                    break;
                }
            }

            log.info("RSS parsing summary: processed={}, filtered_out={}, null_number={}, duplicates={}, added={}",
                    processedCount, filteredOutCount, nullNumberCount, duplicateCount, procurements.size());
        } catch (Exception e) {
            log.error("Error parsing RSS feed from {}: {}", source.getRssUrl(), e.getMessage(), e);
        }
        log.info("Total suitable procurements found: {}", procurements.size());
        return procurements;
    }

    // Перегрузка для обратной совместимости
    public List<Procurement> parseUntilEnough(int maxCount) {
        return parseUntilEnough(maxCount, false);
    }

    /**
     * Проверяет, является ли лот непрофильным по "Виду торгов" из RSS description.
     * Например, отбор управляющих организаций для МКД — не продажа/аренда недвижимости.
     */
    private boolean isExcludedLotType(String description) {
        if (description == null || description.isEmpty()) {
            return false;
        }
        Matcher matcher = LOT_TYPE_PATTERN.matcher(description);
        if (matcher.find()) {
            String lotType = matcher.group(1).trim().toLowerCase();
            for (String excluded : Config.getExcludedLotTypes()) {
                if (lotType.contains(excluded.toLowerCase())) {
                    log.debug("Excluded by lot type '{}': {}", excluded, lotType);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRealEstateLot(String title, boolean notifyAdminOnNoMatch) {
        // Извлекаем lotId для уведомления
        String lotId = null;
        String lotUrl = null;
        if (title.contains("http")) {
            lotId = extractNumberFromLink(title);
            if (lotId != null) {
                lotUrl = "https://torgi.gov.ru/new/public/lots/lot/" + lotId + "/(lotInfo:info)?fromRec=false";
            }
        }
        
        // Используем общий фильтр
        return lotFilter.isRealEstateLot(title, null, notifyAdminOnNoMatch, lotId, lotUrl);
    }

    private String extractNumberFromLink(String link) {
        if (link == null) {
            if (Config.getParserVerbose()) {
                log.debug("Link is null");
            }
            return null;
        }
        if (Config.getParserVerbose()) {
            log.debug("Extracting number from link: {}", link);
        }
        Matcher matcher = NUMBER_PATTERN.matcher(link);
        if (matcher.find()) {
            String number = matcher.group(1);
            if (Config.getParserVerbose()) {
                log.debug("Extracted number: {}", number);
            }
            return number;
        }
        if (Config.getParserVerbose()) {
            log.debug("No number found in link: {}", link);
        }
        return null;
    }

    private String extractCadastralNumber(String title) {
        Matcher matcher = CADASTRAL_PATTERN.matcher(title);
        if (matcher.find()) {
            String cadastralNumber = matcher.group(1);
            log.debug("Extracted cadastral number: {}", cadastralNumber);
            return cadastralNumber;
        }
        return null;
    }

    private Double extractArea(String title) {
        Matcher matcher = AREA_PATTERN.matcher(title);
        if (matcher.find()) {
            try {
                String areaText = matcher.group(1).replace(",", ".");
                return Double.parseDouble(areaText);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse area: {}", matcher.group(1));
            }
        }
        return null;
    }

    private Double extractPrice(String description) {
        Matcher matcher = PRICE_PATTERN.matcher(description);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse price: {}", matcher.group(1));
            }
        }
        return null;
    }

    private String extractLotType(String title) {
        String titleLower = title.toLowerCase();
        if (titleLower.contains("аренды")) {
            return "Аукцион на право заключения договора аренды на недвижимое имущество";
        } else if (titleLower.contains("нежилое помещение") || titleLower.contains("нежилые помещения")) {
            return "Аукцион на право заключения договора аренды на недвижимое имущество";
        } else if (titleLower.contains("нежилое здание")) {
            return "Аукцион на право заключения договора аренды на недвижимое имущество";
        }
        return "Неизвестный тип";
    }

    private String extractAddress(String title) {
        // Берём весь адрес после «по адресу:» до конца строки, а НЕ до первой запятой.
        // Иначе для «...по адресу: Российская Федерация, г Севастополь, ...» адрес
        // обрезался до «Российская Федерация» — без слова «Севастополь», из-за чего
        // RegionValidator (работает ДО обогащения) считал лот не-севастопольским и слал
        // админам ложный «❌ Парсинг не удался». Хвост с характеристиками отсекаем.
        Pattern pattern = Pattern.compile("по адресу:\\s*(.+)$");
        Matcher matcher = pattern.matcher(title);
        if (matcher.find()) {
            String addr = matcher.group(1).trim();
            String lower = addr.toLowerCase();
            for (String tail : new String[] { "общей площадью", "площадью", "кадастров" }) {
                int idx = lower.indexOf(tail);
                if (idx > 5) {
                    addr = addr.substring(0, idx).replaceAll("[,;\\s]+$", "").trim();
                    break;
                }
            }
            return addr.isEmpty() ? "г. Севастополь" : addr;
        }
        return "г. Севастополь";
    }

    private Double extractMonthlyPrice(String title) {
        Pattern monthlyPricePattern = Pattern.compile("(\\d+[,.]\\d+)\\s*руб\\.?/мес");
        Matcher matcher = monthlyPricePattern.matcher(title);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1).replace(",", "."));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse monthly price: {}", matcher.group(1));
            }
        }
        return null;
    }

    private Double extractDeposit(String title) {
        Pattern depositPattern = Pattern.compile("залог\\s*(\\д+[,.]\\d+)");
        Matcher matcher = depositPattern.matcher(title);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1).replace(",", "."));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse deposit: {}", matcher.group(1));
            }
        }
        return null;
    }

    private String extractContractTerm(String title) {
        Pattern termPattern = Pattern.compile("срок\\s*(?:контракта|аренды)[^\\d]*(\\d+\\s*(?:год|лет|месяц))");
        Matcher matcher = termPattern.matcher(title);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractDeadline(Date publishedDate) {
        if (publishedDate != null) {
            try {
                return new SimpleDateFormat("dd-MM-yyyy").format(publishedDate);
            } catch (Exception e) {
                log.warn("Failed to format deadline: {}", publishedDate);
            }
        }
        return "Не указан";
    }

    /**
     * Получает декодированный InputStream с учетом Content-Encoding (gzip, deflate)
     */
    private InputStream getDecodedInputStream(HttpURLConnection conn) throws Exception {
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

    /**
     * Читает первые N байтов из потока для диагностики
     */
    private String readFirstBytes(InputStream is, int maxBytes) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int totalRead = 0;

            while (totalRead < maxBytes) {
                int read = reader.read(buffer, 0, Math.min(buffer.length, maxBytes - totalRead));
                if (read == -1) break;
                sb.append(buffer, 0, read);
                totalRead += read;
            }

            return sb.toString();
        } catch (Exception e) {
            return "[Error reading stream: " + e.getMessage() + "]";
        }
    }

    /**
     * Уведомляет админов о редиректе (возможная блокировка)
     */
    private void notifyAdminAboutRedirect(int statusCode, String location) {
        try {
            TelegramBot bot = AppContext.getBot();
            if (bot != null) {
                StringBuilder msg = new StringBuilder();
                msg.append("⚠️ <b>RSS: Обнаружен редирект!</b>\n\n");
                msg.append("HTTP код: ").append(statusCode).append("\n");
                msg.append("Перенаправление на:\n<code>").append(location).append("</code>\n\n");
                msg.append("Возможные причины:\n");
                msg.append("• Сервер определил бота и блокирует\n");
                msg.append("• Требуется капча или авторизация\n");
                msg.append("• Изменился API torgi.gov.ru\n");
                msg.append("• Временная проблема на сервере\n\n");
                msg.append("Фильтр региона может не применяться!");

                org.telegram.telegrambots.meta.api.methods.send.SendMessage message =
                    new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
                message.setChatId(Config.getAdminGroupId());
                message.setText(msg.toString());
                message.setParseMode("HTML");
                bot.execute(message);
            }
        } catch (Exception e) {
            log.error("Failed to notify admin about redirect: {}", e.getMessage());
        }
    }

    /**
     * Уведомляет админов о получении HTML вместо RSS
     */
    private void notifyAdminAboutHtmlResponse(String contentType, String preview) {
        try {
            TelegramBot bot = AppContext.getBot();
            if (bot != null) {
                StringBuilder msg = new StringBuilder();
                msg.append("🚫 <b>RSS: Получен HTML вместо RSS!</b>\n\n");
                msg.append("Content-Type: <code>").append(contentType).append("</code>\n\n");
                msg.append("Это означает, что сервер не вернул RSS-ленту.\n");
                msg.append("Возможные причины:\n");
                msg.append("• Блокировка по IP (автоматические запросы)\n");
                msg.append("• Страница капчи или авторизации\n");
                msg.append("• Сервер перегружен\n\n");

                // Добавляем превью только если оно не слишком длинное и безопасное
                if (preview.length() > 200) {
                    preview = preview.substring(0, 200) + "...";
                }
                // Экранируем HTML для Telegram
                preview = preview.replace("<", "&lt;").replace(">", "&gt;");
                msg.append("Начало ответа:\n<code>").append(preview).append("</code>");

                org.telegram.telegrambots.meta.api.methods.send.SendMessage message =
                    new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
                message.setChatId(Config.getAdminGroupId());
                message.setText(msg.toString());
                message.setParseMode("HTML");
                bot.execute(message);
            }
        } catch (Exception e) {
            log.error("Failed to notify admin about HTML response: {}", e.getMessage());
        }
    }
}
