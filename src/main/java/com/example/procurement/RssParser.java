package com.example.procurement;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RssParser {
    private static final Logger logger = LoggerFactory.getLogger(RssParser.class);
    private static final String RSS_URL = "https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=PUBLISHED,APPLICATIONS_SUBMISSION&byFirstVersion=true";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("lot/([\\d:_]+)");
    private static final Pattern CADASTRAL_PATTERN = Pattern.compile("(\\d{2}:\\d{2}:\\d{6,7}:\\d+)");
    private static final Pattern AREA_PATTERN = Pattern.compile("площадью\\s*([\\d,.]+)\\s*кв\\.?\\s*м");
    private static final Pattern PRICE_PATTERN = Pattern.compile("Начальная цена:\\s*([\\d.]+)");

    public List<Procurement> parseUntilEnough(int maxCount) {
        List<Procurement> procurements = new ArrayList<>();
        java.util.Set<String> seenNumbers = new java.util.HashSet<>();
        try {
            URL url = new URL(RSS_URL + "&page=1");
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(url));
            List<SyndEntry> entries = feed.getEntries();
            logger.info("Found {} items on page 1", entries.size());

            for (SyndEntry entry : entries) {
                String title = entry.getTitle();
                logger.info("RSS lot: {}", title);
                if (procurements.size() >= maxCount) {
                    break;
                }

                String link = entry.getLink();
                String description = entry.getDescription().getValue();
                String number = extractNumberFromLink(link);

                if (!isRealEstateLot(title)) {
                    if (Config.getParserVerbose()) {
                        logger.debug("Skipping non-real estate lot: {}", title);
                    }
                    continue;
                }

                if (number == null) {
                    if (Config.getParserVerbose()) {
                        logger.debug("No valid number found in link: {}", link);
                        logger.debug("Skipping procurement with null number: {}", title);
                    }
                    continue;
                }

                // Пропуск дубликатов
                if (seenNumbers.contains(number)) {
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

                // Задержка между запросами для снижения нагрузки на сайт
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing RSS feed: {}", e.getMessage());
        }
        logger.info("Total suitable procurements found: {}", procurements.size());
        return procurements;
    }

    private boolean isRealEstateLot(String title) {
        String titleLower = title.toLowerCase();
        // Хардкодированные слова для фильтрации
        String[] exclude = {"автомобиль", "камаз", "маз", "трактор", "погрузчик", "лом", "судно", "гидроцикл"};
        for (String bad : exclude) {
            if (titleLower.contains(bad)) {
                logger.info("FILTER: EXCLUDE ('{}') -> {}", bad, title);
                return false;
            }
        }
        String[] include = {"нежилое", "помещение", "нежилые", "помещения", "здание", "жилое", "квартира", "земельный", "участок", "имущественный", "комплекс"};
        for (String good : include) {
            if (titleLower.contains(good)) {
                logger.info("FILTER: INCLUDE ('{}') -> {}", good, title);
                return true;
            }
        }
        logger.info("FILTER: NO MATCH -> {}", title);
        // Отправить id лота в чат, если возможно
        try {
            String chatId = Config.getChatId();
            if (chatId != null && !chatId.isEmpty()) {
                String lotId = extractNumberFromLink(title); // или другой способ получить id
                if (lotId != null) {
                    new TelegramBot().sendMessageWithRetry(Long.parseLong(chatId), "NO MATCH: " + lotId);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to send NO MATCH lot id to chat: {}", e.getMessage());
        }
        return false;
    }

    private String extractNumberFromLink(String link) {
        if (link == null) {
            if (Config.getParserVerbose()) {
                logger.debug("Link is null");
            }
            return null;
        }
        if (Config.getParserVerbose()) {
            logger.debug("Extracting number from link: {}", link);
        }
        Matcher matcher = NUMBER_PATTERN.matcher(link);
        if (matcher.find()) {
            String number = matcher.group(1);
            if (Config.getParserVerbose()) {
                logger.debug("Extracted number: {}", number);
            }
            return number;
        }
        if (Config.getParserVerbose()) {
            logger.debug("No number found in link: {}", link);
        }
        return null;
    }

    private String extractCadastralNumber(String title) {
        Matcher matcher = CADASTRAL_PATTERN.matcher(title);
        if (matcher.find()) {
            String cadastralNumber = matcher.group(1);
            logger.debug("Extracted cadastral number: {}", cadastralNumber);
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
                logger.debug("Extracted area: {}", area);
                return area;
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse area: {}", matcher.group(1));
            }
        }
        return null;
    }

    private Double extractPrice(String description) {
        Matcher matcher = PRICE_PATTERN.matcher(description);
        if (matcher.find()) {
            try {
                Double price = Double.parseDouble(matcher.group(1));
                logger.debug("Extracted price: {}", price);
                return price;
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse price: {}", matcher.group(1));
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
            logger.debug("Extracted address: {}", address);
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
                logger.warn("Failed to parse monthly price: {}", matcher.group(1));
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
                logger.warn("Failed to parse deposit: {}", matcher.group(1));
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
                logger.warn("Failed to format deadline: {}", publishedDate);
            }
        }
        return "Не указан";
    }
}