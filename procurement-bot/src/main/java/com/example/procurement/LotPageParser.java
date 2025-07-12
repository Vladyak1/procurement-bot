package com.example.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.List;

public class LotPageParser {
    private static final Logger logger = LoggerFactory.getLogger(LotPageParser.class);
    private static final String XHR_URL = "https://torgi.gov.ru/new/api/public/lotcards/";

    public void enrichProcurement(Procurement procurement) {
        if (procurement.getNumber() == null) {
            logger.warn("Skipping enrichment for procurement with null number: {}", procurement.getTitle());
            return;
        }
        try {
            String urlStr = XHR_URL + procurement.getNumber();
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                logger.warn("XHR API returned non-200 for {}: {}", procurement.getNumber(), responseCode);
                return;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            String json = response.toString();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            // Основные поля
            procurement.setTitle(root.path("lotName").asText(procurement.getTitle()));
            procurement.setAddress(root.path("estateAddress").asText(null));
            Double price = root.path("priceMin").asDouble(0);
            procurement.setPrice(price == 0 ? null : price);
            // Площадь: сначала из area, если нет — ищем в characteristics
            Double area = root.path("area").asDouble(0);
            if (area == 0) {
                JsonNode characteristics = root.path("characteristics");
                if (characteristics.isArray()) {
                    for (JsonNode ch : characteristics) {
                        if (ch.path("code").asText("").equals("totalAreaRealty")) {
                            area = ch.path("characteristicValue").asDouble(0);
                            break;
                        }
                    }
                }
            }
            procurement.setArea(area == 0 ? null : area);
            procurement.setDeadline(root.path("biddEndTime").asText(null));
            procurement.setCadastralNumber(root.path("cadastralNumber").asText(null));
            procurement.setDeposit(root.path("deposit").asDouble(0) == 0 ? null : root.path("deposit").asDouble());
            procurement.setContractTerm(root.path("contractTerm").asText(null));
            procurement.setDepositRecipientName(root.path("depositRecipientName").asText(null));
            // Фото (только первые 4)
            List<String> imageUrls = new ArrayList<>();
            JsonNode images = root.path("lotImages");
            int maxImages = 4;
            if (images.isArray()) {
                int count = 0;
                for (JsonNode img : images) {
                    if (count >= maxImages) break;
                    String fileId = img.asText("");
                    if (!fileId.isEmpty()) {
                        imageUrls.add("https://torgi.gov.ru/new/image-preview/v1/" + fileId + "?disposition=inline&resize=600x600!");
                        count++;
                    }
                }
            }
            procurement.setImageUrls(imageUrls);
            logger.info("Enriched procurement from XHR JSON: {} ({} images)", procurement.getNumber(), imageUrls.size());
            logger.info("lotImages: {}", images);
            logger.info("title: {}", procurement.getTitle());
            logger.info("address: {}", procurement.getAddress());
            logger.info("price: {}", procurement.getPrice());
            logger.info("area: {}", procurement.getArea());
            logger.info("deadline: {}", procurement.getDeadline());
            // Тип торгов
            procurement.setBiddTypeName(root.path("biddType").path("name").asText(null));
            // contractTypeName и pricePeriod из attributes
            String contractTypeName = procurement.getContractTypeName();
            String pricePeriod = procurement.getPricePeriod();
            JsonNode attributes = root.path("attributes");
            if (attributes.isArray()) {
                for (JsonNode attr : attributes) {
                    String code = attr.path("code").asText("");
                    String fullName = attr.path("fullName").asText("");
                    JsonNode value = attr.path("value");
                    if ("contractTypeName".equals(code) || "Вид договора".equals(fullName)) {
                        if (value.isObject()) {
                            contractTypeName = value.path("name").asText(contractTypeName);
                        } else if (value.isTextual()) {
                            contractTypeName = value.asText(contractTypeName);
                        }
                    }
                    if ("pricePeriod".equals(code) || "Начальная цена указана за:".equals(fullName)) {
                        if (value.isObject()) {
                            pricePeriod = value.path("name").asText(pricePeriod);
                        } else if (value.isTextual()) {
                            pricePeriod = value.asText(pricePeriod);
                        }
                    }
                }
            }
            procurement.setContractTypeName(contractTypeName);
            procurement.setPricePeriod(pricePeriod);
            // Вычисляем месячную/годовую цену аренды
            if (contractTypeName != null && contractTypeName.contains("аренды")) {
                if (pricePeriod != null && pricePeriod.contains("год")) {
                    // Цена за год, считаем за месяц
                    if (procurement.getPrice() != null) {
                        procurement.setMonthlyPrice(procurement.getPrice() / 12.0);
                    }
                } else if (pricePeriod != null && pricePeriod.contains("месяц")) {
                    // Цена за месяц, считаем за год
                    if (procurement.getPrice() != null) {
                        procurement.setMonthlyPrice(procurement.getPrice());
                        procurement.setPrice(procurement.getPrice() * 12.0);
                    }
                }
            }
            // Пауза между запросами к XHR API
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            logger.error("Error enriching procurement {}: {}", procurement.getNumber(), e.getMessage());
        }
    }
}