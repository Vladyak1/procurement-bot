package com.example.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class LotPageParser {

    /**
     * Обогащает закупку данными из XHR API
     */
    public void enrichProcurement(Procurement procurement, String xhrBaseUrl) {
        if (procurement.getNumber() == null) {
            log.warn("Skipping enrichment for procurement with null number: {}", procurement.getTitle());
            return;
        }
        try {
            String urlStr = xhrBaseUrl + procurement.getNumber();
            java.net.URL url = URI.create(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            // Полноценные браузерные заголовки для обхода анти-бот защиты
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Referer", "https://torgi.gov.ru/");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Sec-Fetch-Dest", "empty");
            conn.setRequestProperty("Sec-Fetch-Mode", "cors");
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("XHR API returned non-200 for {}: {}", procurement.getNumber(), responseCode);
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
            if (imageUrls.isEmpty()) {
                log.warn("No images found for lot {}: lotImages field is {}",
                    procurement.getNumber(),
                    images.isMissingNode() ? "missing" : (images.isArray() ? "empty array" : "not an array"));
            } else {
                log.info("Enriched procurement from XHR JSON: {} ({} images)", procurement.getNumber(), imageUrls.size());
            }
            log.info("title: {}", procurement.getTitle());
            log.info("address: {}", procurement.getAddress());
            log.info("price: {}", procurement.getPrice());
            log.info("area: {}", procurement.getArea());
            log.info("deadline: {}", procurement.getDeadline());
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
                    if (procurement.getPrice() != null) {
                        procurement.setMonthlyPrice(procurement.getPrice() / 12.0);
                    }
                } else if (pricePeriod != null && pricePeriod.contains("месяц")) {
                    if (procurement.getPrice() != null) {
                        procurement.setMonthlyPrice(procurement.getPrice());
                        procurement.setPrice(procurement.getPrice() * 12.0);
                    }
                }
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (java.net.SocketTimeoutException e) {
            log.error("Timeout while enriching procurement {}: {} (API took >10 seconds)", procurement.getNumber(), e.getMessage());
        } catch (java.io.IOException e) {
            log.error("Network error while enriching procurement {}: {}", procurement.getNumber(), e.getMessage());
        } catch (Exception e) {
            log.error("Error enriching procurement {}: {}", procurement.getNumber(), e.getMessage(), e);
        }
    }
    
    /**
     * Обогащает закупку данными из XHR API (использует URL из конфига)
     * Для обратной совместимости
     */
    public void enrichProcurement(Procurement procurement) {
        enrichProcurement(procurement, Config.getXhrUrl());
    }
}
