package com.example.procurement;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
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

    public List<Procurement> parseUntilEnough(int maxCount, boolean notifyAdminOnNoMatch) {
        List<Procurement> procurements = new ArrayList<>();
        Set<String> seenNumbers = new java.util.HashSet<>();
        try {
            URL url = new URL(Config.getRssUrl());
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(url));
            List<SyndEntry> entries = feed.getEntries();
            log.info("Found {} items in RSS feed", entries.size());

            for (SyndEntry entry : entries) {
                String title = entry.getTitle();
                log.info("Processing RSS lot: {}", title);
                if (procurements.size() >= maxCount) {
                    break;
                }

                String link = entry.getLink();
                String description = entry.getDescription().getValue();
                String number = extractNumberFromLink(link);

                boolean isSuitable = isRealEstateLot(title, notifyAdminOnNoMatch);
                if (!isSuitable) {
                    log.info("Lot {} discarded by filter", title);
                    continue;
                }

                if (number == null) {
                    if (Config.getParserVerbose()) {
                        log.debug("No valid number found in link: {}", link);
                        log.debug("Skipping procurement with null number: {}", title);
                    }
                    continue;
                }

                if (seenNumbers.contains(number)) {
                    log.debug("Duplicate number {} skipped", number);
                    continue;
                }
                seenNumbers.add(number);

                Procurement procurement = new Procurement();
                procurement.setNumber(number);
                procurement.setTitle(title);
                procurement.setLink(link);
                procurement.setLotType(extractLotType(title));
                procurement.setAddress(extractAddress(title));
                procurement.setPrice(extractPrice(description));
                procurement.setMonthlyPrice(extractMonthlyPrice(title));
                procurement.setDeposit(extractDeposit(title));
                procurement.setContractTerm(extractContractTerm(title));
                procurement.setDeadline(extractDeadline(entry.getPublishedDate()));
                procurement.setCadastralNumber(extractCadastralNumber(title));
                procurement.setArea(extractArea(title));
                procurement.setImageUrls(new ArrayList<>());
                procurements.add(procurement);
                log.info("Added suitable procurement: {}", title);
                if (procurements.size() >= maxCount) {
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Error parsing RSS feed: {}", e.getMessage());
        }
        log.info("Total suitable procurements found: {}", procurements.size());
        return procurements;
    }

    // Перегрузка для обратной совместимости
    public List<Procurement> parseUntilEnough(int maxCount) {
        return parseUntilEnough(maxCount, false);
    }

    private boolean isRealEstateLot(String title, boolean notifyAdminOnNoMatch) {
        String titleLower = title.toLowerCase();
        String[] exclude = {"автомобиль", "камаз", "маз", "трактор", "погрузчик", "лом", "судно", "гидроцикл"};
        for (String bad : exclude) {
            if (titleLower.contains(bad)) {
                log.debug("Excluded by '{}': {}", bad, title);
                return false;
            }
        }
        String[] include = {"нежилое", "помещение", "нежилые", "помещения", "здание", "жилое", "квартира", "земельный", "участок", "имущественный", "комплекс", "недвижимого", "недвижимое"};
        for (String good : include) {
            if (titleLower.contains(good)) {
                log.debug("Included by '{}': {}", good, title);
                return true;
            }
        }
        log.debug("No match for include/exclude in: {}", title);
        if (notifyAdminOnNoMatch) {
            try {
                String lotId = null;
                if (title.contains("http")) {
                    lotId = extractNumberFromLink(title);
                }
                String lotKey = lotId != null ? lotId : Integer.toString(title.hashCode());
                DatabaseManager db = AppContext.getDatabaseManager();
                if (db.isNoMatchSent(lotKey)) {
                    log.info("NO MATCH lot {} уже отправлялся, пропускаем отправку", lotKey);
                    return false;
                }
                String msg = "❓ Не удалось определить пригодность лота\n";
                if (lotId != null) {
                    msg += "ID: " + lotId + "\nСсылка: https://torgi.gov.ru/new/public/lots/lot/" + lotId + "/(lotInfo:info)?fromRec=false";
                } else {
                    msg += "Текст: " + title;
                }
                log.info("NO MATCH: отправка сообщения в админ-группу: {}", msg);
                new TelegramBot().sendMessageWithRetry(Config.getAdminGroupId(), msg);
                db.markNoMatchSent(lotKey);
            } catch (Exception e) {
                log.warn("Failed to send NO MATCH lot id to admin group: {}", e.getMessage());
            }
        }
        return false;
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
                Double area = Double.parseDouble(areaText);
                log.debug("Extracted area: {}", area);
                return area;
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
                Double price = Double.parseDouble(matcher.group(1));
                log.debug("Extracted price: {}", price);
                return price;
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
            String address = matcher.group(1).trim();
            log.debug("Extracted address: {}", address);
            return address;
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
        Pattern depositPattern = Pattern.compile("залог\\s*(\\d+[,.]\\d+)");
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
                SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy");
                return formatter.format(publishedDate);
            } catch (Exception e) {
                log.warn("Failed to format deadline: {}", publishedDate);
            }
        }
        return "Не указан";
    }
}
