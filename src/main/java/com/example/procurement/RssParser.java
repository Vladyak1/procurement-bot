package com.example.procurement;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class RssParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("lot/([\\d:_]+)");
    private static final Pattern CADASTRAL_PATTERN = Pattern.compile("(\\d{2}:\\d{2}:\\d{6,7}:\\d+)");
    private static final Pattern AREA_PATTERN = Pattern.compile("площадью\\s*([\\d,.]+)\\s*кв\\.?\\s*м");
    private static final Pattern PRICE_PATTERN = Pattern.compile("Начальная цена:\\s*([\\d.]+)");

    private final ParsingSource source;
    private final LotFilter lotFilter;

    public RssParser(ParsingSource source) {
        this.source = source;
        this.lotFilter = LotFilter.createDefault();
    }

    public List<Procurement> parseUntilEnough(final int maxCount, final boolean notifyAdminOnNoMatch) {
        List<Procurement> procurements = new ArrayList<>();
        Set<String> seenNumbers = new java.util.HashSet<>();
        log.info("Starting RSS parsing from URL: {}", source.getRssUrl());
        log.info("Max count requested: {}, notify on no match: {}", maxCount, notifyAdminOnNoMatch);

        try {
            java.net.URL url = URI.create(source.getRssUrl()).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            // Полноценные браузерные заголовки для обхода анти-бот защиты
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Referer", "https://torgi.gov.ru/");
            conn.setRequestProperty("Connection", "keep-alive");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.error("RSS feed returned HTTP {}: {}", responseCode, source.getRssUrl());
                throw new RuntimeException("RSS feed returned HTTP " + responseCode);
            }

            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed;
            try (InputStream is = conn.getInputStream(); XmlReader xr = new XmlReader(is)) {
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

                boolean isSuitable = isRealEstateLot(title, notifyAdminOnNoMatch);
                if (!isSuitable) {
                    filteredOutCount++;
                    log.info("Lot #{} discarded by filter: {}", processedCount, title);
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
        Pattern pattern = Pattern.compile("по адресу:([^,]+)");
        Matcher matcher = pattern.matcher(title);
        if (matcher.find()) {
            return matcher.group(1).trim();
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
}
