package com.example.procurement;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LotPageParser {
    private static final Logger logger = LoggerFactory.getLogger(LotPageParser.class);
    private static final Pattern PRICE_PATTERN = Pattern.compile("([\\d\\s,.]+)\\s*₽");
    private static final Pattern DEADLINE_PATTERN = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");

    public void enrichProcurement(Procurement procurement) {
        if (procurement.getNumber() == null) {
            logger.warn("Skipping enrichment for procurement with null number: {}", procurement.getTitle());
            return;
        }

        try {
            String url = "https://torgi.gov.ru/new/public/lots/lot/" + procurement.getNumber();
            logger.debug("Fetching lot page: {}", url);
            Connection.Response response = Jsoup.connect(url)
                    .timeout(10000)
                    .userAgent("Mozilla/5.0")
                    .header("Accept", "text/html")
                    .execute();
            if (response.statusCode() != 200) {
                logger.error("Failed to fetch lot page {}: HTTP {}", url, response.statusCode());
                return;
            }
            Document doc = response.parse();

            // Начальная цена
            String priceText = doc.select("div.attr_title:contains(Начальная цена) + div.attr_value").text();
            if (!priceText.isEmpty()) {
                Double price = parseDouble(priceText);
                procurement.setPrice(price);
                logger.debug("Set price: {}", price);
            }

            // Тип цены (за год или за месяц)
            String priceType = doc.select("div.attr_title:contains(Начальная цена указана за) + div.attr_value").text();
            if (priceType.contains("Арендный платеж за год") && procurement.getPrice() != null) {
                procurement.setMonthlyPrice(procurement.getPrice() / 12);
                logger.debug("Set monthly price (yearly/12): {}", procurement.getMonthlyPrice());
            } else if (priceType.contains("Арендный платеж за месяц") && procurement.getPrice() != null) {
                procurement.setPrice(procurement.getPrice() * 12);
                procurement.setMonthlyPrice(procurement.getPrice() / 12);
                logger.debug("Set yearly price (monthly*12): {}", procurement.getPrice());
            }

            // Задаток
            String depositText = doc.select("div.attr_title:contains(Размер задатка) + div.attr_value").text();
            if (!depositText.isEmpty()) {
                Double deposit = parseDouble(depositText);
                procurement.setDeposit(deposit);
                logger.debug("Set deposit: {}", deposit);
            }

            // Срок договора
            String contractTerm = doc.select("div.attr_title:contains(Срок действия договора - лет) + div.attr_value").text();
            if (!contractTerm.isEmpty()) {
                procurement.setContractTerm(contractTerm);
                logger.debug("Set contract term: {}", contractTerm);
            }

            // Дата окончания подачи заявок
            String deadline = doc.select("div.lotAttributeName:contains(Дата и время окончания подачи заявок) + div.lotAttributeValue").text();
            if (!deadline.isEmpty()) {
                Matcher matcher = DEADLINE_PATTERN.matcher(deadline);
                if (matcher.find()) {
                    String formattedDeadline = matcher.group(1).replace(".", "-");
                    procurement.setDeadline(formattedDeadline);
                    logger.debug("Set deadline: {}", formattedDeadline);
                }
            }

            // Изображения (первые 4)
            List<String> imageUrls = new ArrayList<>();
            doc.select("div.lotPhoto img.image-list-item").stream().limit(4).forEach(img -> {
                String src = img.attr("src");
                if (!src.isEmpty()) {
                    String fullSrc = src.startsWith("http") ? src : "https://torgi.gov.ru/" + src;
                    // Удаляем параметры для проверки
                    String cleanSrc = fullSrc.split("\\?")[0];
                    if (isImageAccessible(cleanSrc)) {
                        imageUrls.add(cleanSrc);
                        logger.debug("Added image URL: {}", cleanSrc);
                    } else {
                        logger.warn("Image URL not accessible: {}", cleanSrc);
                    }
                }
            });
            if (!imageUrls.isEmpty()) {
                procurement.setImageUrls(imageUrls);
                logger.debug("Set {} image URLs for procurement: {}", imageUrls.size(), procurement.getNumber());
            } else {
                logger.warn("No accessible images found for procurement: {}", procurement.getNumber());
            }

            logger.info("Enriched procurement: {}", procurement.getNumber());
        } catch (IOException e) {
            logger.error("Error enriching procurement {}: {}", procurement.getNumber(), e.getMessage());
        }
    }

    private Double parseDouble(String text) {
        try {
            Matcher matcher = PRICE_PATTERN.matcher(text);
            if (matcher.find()) {
                String cleanText = matcher.group(1).replaceAll("[\\s,]", "");
                return Double.parseDouble(cleanText);
            }
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse double from text: {}", text);
        }
        return null;
    }

    private boolean isImageAccessible(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int statusCode = connection.getResponseCode();
            return statusCode == 200;
        } catch (IOException e) {
            logger.warn("Failed to check image accessibility: {} - {}", imageUrl, e.getMessage());
            return false;
        }
    }
}