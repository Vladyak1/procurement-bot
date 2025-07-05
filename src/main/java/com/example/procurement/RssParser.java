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
    private static final String RSS_URL = "https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=PUBLISHED,APPLICATIONS_SUBMISSION&byFirstVersion=true&size=100";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("lot/([\\d:_]+)");
    private static final Pattern CADASTRAL_PATTERN = Pattern.compile("(\\d{2}:\\d{2}:\\d{6,7}:\\d+)");
    private static final Pattern AREA_PATTERN = Pattern.compile("площадью\\s*([\\d,.]+)\\s*кв\\.?\\s*м");
    private static final Pattern PRICE_PATTERN = Pattern.compile("Начальная цена:\\s*([\\d.]+)");

    public List<Procurement> parseUntilEnough(int maxCount) {
        List<Procurement> procurements = new ArrayList<>();
        int page = 1;

        while (procurements.size() < maxCount) {
            try {
                URL url = new URL(RSS_URL + "&page=" + page);
                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(new XmlReader(url));
                List<SyndEntry> entries = feed.getEntries();
                logger.info("Found {} items on page {}", entries.size(), page);

                if (entries.isEmpty()) {
                    break;
                }

                for (SyndEntry entry : entries) {
                    if (procurements.size() >= maxCount) {
                        break;
                    }

                    String title = entry.getTitle();
                    String link = entry.getLink();
                    String description = entry.getDescription().getValue();
                    String number = extractNumberFromLink(link);

                    if (!isRealEstateLot(title)) {
                        logger.info("Skipping non-real estate lot: {}", title);
                        continue;
                    }

                    if (number == null) {
                        logger.warn("No valid number found in link: {}", link);
                        logger.warn("Skipping procurement with null number: {}", title);
                        continue;
                    }

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
                }
                page++;
            } catch (Exception e) {
                logger.error("Error parsing RSS feed on page {}: {}", page, e.getMessage());
                break;
            }
        }

        logger.info("Total suitable procurements found: {}", procurements.size());
        return procurements;
    }

    private boolean isRealEstateLot(String title) {
        String titleLower = title.toLowerCase();
        if (titleLower.contains("автомобиль") ||
                titleLower.contains("мотоцикл") ||
                titleLower.contains("судно") ||
                titleLower.contains("трактор") ||
                titleLower.contains("лом ") ||
                titleLower.contains("шин") ||
                titleLower.contains("гидротехни") ||
                titleLower.contains("земельный участок") ||
                titleLower.contains("квартира") ||
                titleLower.contains("жилое помещение") ||
                titleLower.contains("имущественный комплекс")) {
            return false;
        }
        return titleLower.contains("нежилое помещение") ||
                titleLower.contains("нежилые помещения") ||
                titleLower.contains("нежилое здание");
    }

    private String extractNumberFromLink(String link) {
        if (link == null) {
            logger.warn("Link is null");
            return null;
        }
        logger.debug("Extracting number from link: {}", link);
        Matcher matcher = NUMBER_PATTERN.matcher(link);
        if (matcher.find()) {
            String number = matcher.group(1);
            logger.debug("Extracted number: {}", number);
            return number;
        }
        logger.warn("No number found in link: {}", link);
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