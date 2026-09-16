package com.example.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

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
            conn.setInstanceFollowRedirects(false);

            // Актуальные браузерные заголовки (Chrome 122)
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
            conn.setRequestProperty("Referer", "https://torgi.gov.ru/new/public/lots/lot/" + procurement.getNumber());
            conn.setRequestProperty("Origin", "https://torgi.gov.ru");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"");
            conn.setRequestProperty("Sec-Ch-Ua-Mobile", "?0");
            conn.setRequestProperty("Sec-Ch-Ua-Platform", "\"Windows\"");
            conn.setRequestProperty("Sec-Fetch-Dest", "empty");
            conn.setRequestProperty("Sec-Fetch-Mode", "cors");
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin");

            int responseCode = conn.getResponseCode();

            // Обработка редиректов
            if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                String location = conn.getHeaderField("Location");
                log.warn("XHR API redirect for {}: {} -> {}", procurement.getNumber(), responseCode, location);
                return;
            }

            if (responseCode != 200) {
                log.warn("XHR API returned non-200 for {}: {} (Content-Type: {})",
                    procurement.getNumber(), responseCode, conn.getContentType());
                return;
            }
            // Поддержка gzip
            InputStream is = conn.getInputStream();
            String encoding = conn.getContentEncoding();
            if ("gzip".equalsIgnoreCase(encoding)) {
                is = new GZIPInputStream(is);
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                is = new java.util.zip.InflaterInputStream(is);
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            String json = response.toString();

            // Проверка на HTML вместо JSON
            if (json.trim().startsWith("<!") || json.trim().startsWith("<html")) {
                log.warn("XHR API returned HTML instead of JSON for {}", procurement.getNumber());
                log.debug("HTML preview: {}", json.substring(0, Math.min(200, json.length())));
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            // Основные поля
            procurement.setTitle(root.path("lotName").asText(procurement.getTitle()));
            String apiAddress = root.path("estateAddress").asText(null);
            if (apiAddress != null && !apiAddress.isEmpty()) {
                procurement.setAddress(apiAddress);
            }
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
            String apiDeadline = root.path("biddEndTime").asText(null);
            if (apiDeadline != null && !apiDeadline.isEmpty()) {
                procurement.setDeadline(apiDeadline);
            }
            String apiCadastral = root.path("cadastralNumber").asText(null);
            if (apiCadastral != null && !apiCadastral.isEmpty()) {
                procurement.setCadastralNumber(apiCadastral);
            }
            procurement.setDeposit(root.path("deposit").asDouble(0) == 0 ? null : root.path("deposit").asDouble());
            // contractTerm из root может быть числом (лет) без единицы, будет переопределён из attributes если там есть
            String rootContractTerm = root.path("contractTerm").asText(null);
            if (rootContractTerm != null && !rootContractTerm.isEmpty() && !"0".equals(rootContractTerm.trim())) {
                procurement.setContractTerm(rootContractTerm);
            }
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
            // Координаты точки (WGS84) — для прямой ссылки на карту с меткой
            JsonNode pointNode = root.path("point");
            if (pointNode.isObject()) {
                double lat = pointNode.path("lat").asDouble(0);
                double lon = pointNode.path("lon").asDouble(0);
                if (lat != 0 && lon != 0) {
                    procurement.setLat(lat);
                    procurement.setLon(lon);
                    procurement.setPointSource(Procurement.POINT_SOURCE_TORGI);
                }
            }
            // Уточнением по ЕГРН здесь не занимаемся: обогащение проходят все лоты подряд,
            // включая давно опубликованные и те, что отсеют фильтры. Координаты запрашиваются
            // непосредственно перед публикацией — см. TelegramBot.resolveCadastralPoint.
            // Код субъекта РФ (authoritative для проверки региона; Севастополь = 92)
            String subjectRfCode = root.path("subjectRFCode").asText(null);
            if (subjectRfCode != null && !subjectRfCode.isEmpty()) {
                procurement.setSubjectRfCode(subjectRfCode);
            }
            // Категория объекта — для фильтрации недвижимости (allow-list по code)
            JsonNode categoryNode = root.path("category");
            if (categoryNode.isObject()) {
                String catCode = categoryNode.path("code").asText(null);
                String catName = categoryNode.path("name").asText(null);
                if (catCode != null && !catCode.isEmpty()) procurement.setCategoryCode(catCode);
                if (catName != null && !catName.isEmpty()) procurement.setCategoryName(catName);
            }
            // Краткое описание объекта — для фильтра движимого имущества по описанию
            // (напр. "Судно «ПС-379»": в заголовке/адресе слова нет, только здесь)
            String lotDescription = root.path("lotDescription").asText(null);
            if (lotDescription != null && !lotDescription.isEmpty()) {
                procurement.setLotDescription(lotDescription);
            }
            // contractTypeName и pricePeriod из attributes
            String contractTypeName = procurement.getContractTypeName();
            String pricePeriod = procurement.getPricePeriod();
            JsonNode attributes = root.path("attributes");
            int termYears = -1, termMonths = -1, termDays = -1;
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
                    if (fullName.startsWith("Срок действия договора")) {
                        String termValue = null;
                        if (value.isNumber()) {
                            termValue = value.asText();
                        } else if (value.isTextual() && !value.asText().isEmpty()) {
                            termValue = value.asText();
                        } else if (value.isObject()) {
                            termValue = value.path("name").asText(null);
                        }
                        if (termValue != null && !termValue.isEmpty()) {
                            try {
                                int val = Integer.parseInt(termValue.trim());
                                // Единицу определяем по code атрибута, а имя — только запасной вариант:
                                // torgi уже сменили формат с «Срок действия договора (лет)» на
                                // «Срок действия договора - лет», из-за чего срок «5 лет» молча
                                // превращался в «0» (последним затирало нулевое число дней).
                                String lowerName = fullName.toLowerCase();
                                if (code.contains("contractYears") || lowerName.contains("лет")) {
                                    termYears = val;
                                } else if (code.contains("contractMonths") || lowerName.contains("месяц")) {
                                    termMonths = val;
                                } else if (code.contains("contractDays") || lowerName.contains("дн")) {
                                    termDays = val;
                                } else {
                                    // Единица неизвестна: голое число без неё в карточке бессмысленно
                                    log.warn("Неопознанная единица срока договора у {}: {} = {}",
                                            procurement.getNumber(), fullName, termValue);
                                }
                            } catch (NumberFormatException e) {
                                // Не число (например, текстовое описание) — ставим как есть
                                procurement.setContractTerm(termValue);
                            }
                        }
                    }
                }
            }
            // Собираем срок из компонентов, пропуская нулевые
            if (termYears >= 0 || termMonths >= 0 || termDays >= 0) {
                StringBuilder term = new StringBuilder();
                if (termYears > 0) term.append(termYears).append(" лет ");
                if (termMonths > 0) term.append(termMonths).append(" мес. ");
                if (termDays > 0) term.append(termDays).append(" дн.");
                String combined = term.toString().trim();
                if (!combined.isEmpty()) {
                    procurement.setContractTerm(combined);
                } else {
                    // Все компоненты нулевые — срок не задан. Значение из root тут не спасёт:
                    // там лежит тот же ноль, а «Срок договора: 0» в карточке только путает.
                    procurement.setContractTerm(null);
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
