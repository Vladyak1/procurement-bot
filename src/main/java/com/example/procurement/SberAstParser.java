package com.example.procurement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер для сайта Сбербанк-АСТ (https://www.sberbank-ast.ru)
 * Работает через ElasticSearch API для получения данных о лотах
 */
@Slf4j
public class SberAstParser {
    private static final String BASE_URL = "https://www.sberbank-ast.ru";
    private static final String API_URL = BASE_URL + "/SearchQuery.aspx?name=Main";

    // Паттерны для извлечения данных
    private static final Pattern AREA_PATTERN = Pattern.compile("([\\d\\s]+[,.]?\\d*)\\s*(?:кв\\.?\\s*м|м2)");

    // Фильтр по ключевым словам
    private final LotFilter lotFilter;

    /**
     * Конструктор по умолчанию с фильтром из конфигурации
     */
    public SberAstParser() {
        this.lotFilter = LotFilter.createDefault();
    }

    /**
     * Конструктор с заданным фильтром
     */
    public SberAstParser(LotFilter lotFilter) {
        this.lotFilter = lotFilter;
    }

    /**
     * Парсит лоты с сайта Сбербанк-АСТ через ElasticSearch API
     * 
     * @param maxCount        максимальное количество лотов
     * @param checkDuplicates проверять ли дубликаты в базе
     * @return список лотов
     */
    public List<Procurement> parse(int maxCount, boolean checkDuplicates) {
        List<Procurement> procurements = new ArrayList<>();

        try {
            log.info("Starting SberAst API parsing from {}", API_URL);

            // Шаг 1: Получаем cookies через GET запрос к главной странице
            log.info("Step 1: Obtaining session cookies from {}/UnitedPurchaseList.aspx", BASE_URL);
            Map<String, String> cookies = Jsoup.connect(BASE_URL + "/UnitedPurchaseList.aspx")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(300000)
                    .execute()
                    .cookies();
            log.info("Obtained {} cookies: {}", cookies.size(), cookies.keySet());

            // Формируем XML payload для запроса
            String xmlPayload = buildElasticSearchRequest(maxCount);
            log.info("XML payload length: {}, first 300 chars: {}", xmlPayload.length(),
                    xmlPayload.substring(0, Math.min(300, xmlPayload.length())));

            // Шаг 2: Выполняем POST запрос к API с cookies и дополнительными заголовками
            log.info("Step 2: Sending POST request with {} cookies...", cookies.size());
            log.info("POST data: xmlData length={}, orgId=0, targetPageCode=UnitedPurchaseList, PID=0",
                    xmlPayload.length());

            // Convert cookies to header string
            String cookieHeader = cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining("; "));

            // Use HttpClient instead of Jsoup for better control and timeout handling
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(60))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();

            // Encode the payload
            String encodedPayload = "xmlData="
                    + java.net.URLEncoder.encode(xmlPayload, "UTF-8")
                    + "&shortdictionary=" + java.net.URLEncoder.encode("", "UTF-8")
                    + "&targetPageCode="
                    + java.net.URLEncoder.encode("UnitedPurchaseList", "UTF-8")
                    + "&orgId=" + java.net.URLEncoder.encode("0", "UTF-8")
                    + "&PID=" + java.net.URLEncoder.encode("0", "UTF-8");

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(API_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Cookie", cookieHeader)
                    .header("Referer", BASE_URL + "/UnitedPurchaseList.aspx")
                    .header("Origin", BASE_URL)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(encodedPayload))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .build();

            log.info("Sending SberAst API request...");
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            String jsonResponse = response.body();
            log.info("SberAst API response: status={}, body={}", statusCode, jsonResponse);
            log.debug("API response (first 500 chars): {}",
                    jsonResponse.substring(0, Math.min(500, jsonResponse.length())));

            // Парсим JSON ответ
            JsonObject responseJson = JsonParser.parseString(jsonResponse).getAsJsonObject();
            log.debug("Response JSON keys: {}", responseJson.keySet());

            // Проверяем наличие поля result
            if (responseJson.has("result")) {
                String resultValue = responseJson.get("result").getAsString();
                log.info("API result field: '{}'", resultValue);

                if (!"success".equals(resultValue)) {
                    log.error("API returned result='{}' (expected 'success')", resultValue);
                    if (responseJson.has("message")) {
                        log.error("API error message: {}", responseJson.get("message").getAsString());
                    }
                    log.error("Full API response: {}", jsonResponse);
                    return procurements;
                }
            } else {
                log.error("API response missing 'result' field. Keys present: {}", responseJson.keySet());
                log.error("Full API response: {}", jsonResponse);
                return procurements;
            }

            // Извлекаем вложенный JSON из поля "data"
            if (!responseJson.has("data")) {
                log.warn("No data field in API response");
                return procurements;
            }

            String dataString = responseJson.get("data").getAsString();
            log.info("Data string length: {}, first 500 chars: {}", dataString.length(),
                    dataString.substring(0, Math.min(500, dataString.length())));

            JsonObject dataJson = JsonParser.parseString(dataString).getAsJsonObject();
            log.info("Data JSON keys: {}", dataJson.keySet());

            // Проверяем структуру ответа - данные могут быть в поле "data" или "tableXml"
            JsonArray hits = null;

            // Вариант 1: Пробуем извлечь из вложенного поля "data" (JSON)
            if (dataJson.has("data") && !dataJson.get("data").isJsonNull()) {
                try {
                    String innerDataString = dataJson.get("data").getAsString();
                    log.info("Found inner 'data' field, length: {}, first 300 chars: {}",
                            innerDataString.length(),
                            innerDataString.substring(0, Math.min(300, innerDataString.length())));

                    JsonObject innerDataJson = JsonParser.parseString(innerDataString).getAsJsonObject();
                    log.info("Inner data JSON keys: {}", innerDataJson.keySet());

                    if (innerDataJson.has("hits") && innerDataJson.getAsJsonObject("hits").has("hits")) {
                        hits = innerDataJson.getAsJsonObject("hits").getAsJsonArray("hits");
                        log.info("Found hits in inner data field");
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse inner data field as JSON: {}", e.getMessage());
                }
            }

            // Вариант 2: Если не нашли в "data", пробуем прямо в основном JSON
            if (hits == null && dataJson.has("hits") && dataJson.getAsJsonObject("hits").has("hits")) {
                hits = dataJson.getAsJsonObject("hits").getAsJsonArray("hits");
                log.info("Found hits in main data field");
            }

            if (hits == null) {
                log.warn("No hits found in any known location");
                log.warn("Available keys in data JSON: {}", dataJson.keySet());
                return procurements;
            }
            log.info("Found {} lots in SberAst API response", hits.size());

            DatabaseManager db = checkDuplicates ? AppContext.getDatabaseManager() : null;

            // Обрабатываем каждый лот
            for (JsonElement hitElement : hits) {
                if (procurements.size() >= maxCount) {
                    log.info("Reached maxCount={}, stopping", maxCount);
                    break;
                }

                try {
                    JsonObject hit = hitElement.getAsJsonObject();
                    JsonObject source = hit.getAsJsonObject("_source");

                    log.info("Processing lot from JSON...");
                    Procurement procurement = parseLotFromJson(source);

                    if (procurement == null) {
                        log.warn("Failed to parse procurement from JSON, skipping");
                        continue;
                    }

                    log.info("Parsed lot: {} - {}", procurement.getNumber(), procurement.getTitle());

                    // Проверяем фильтры
                    log.info("Checking filters for lot {}...", procurement.getNumber());
                    if (!matchesFilters(procurement)) {
                        log.info("Lot {} doesn't match filters, skipping. Title: {}, Address: {}",
                                procurement.getNumber(), procurement.getTitle(), procurement.getAddress());
                        continue;
                    }

                    log.info("Lot {} passed filters", procurement.getNumber());

                    // Проверяем дубликаты по описанию
                    if (checkDuplicates && db != null && db.isDuplicateByDescription(procurement.getTitle())) {
                        log.info("Duplicate lot found by description, skipping: {}", procurement.getTitle());
                        continue;
                    }

                    procurements.add(procurement);
                    log.info("✓ Added SberAst lot: {} - {}", procurement.getNumber(), procurement.getTitle());
                } catch (Exception e) {
                    log.error("Error parsing lot from JSON: {}", e.getMessage(), e);
                }
            }

        } catch (Exception e) {
            log.error("Error parsing SberAst API: {}", e.getMessage(), e);
        }

        log.info("Total SberAst procurements found: {}", procurements.size());
        return procurements;
    }

    /**
     * Формирует XML запрос для ElasticSearch API
     */
    /**
     * Формирует XML запрос для ElasticSearch API
     */
    private String buildElasticSearchRequest(int size) {
        // XML payload reverse-engineered from browser cURL
        return "<elasticrequest>" +
                "<personid>0</personid>" +
                "<buid>0</buid>" +
                "<filters>" +
                "<mainSearchBar><value></value><type>phrase_prefix</type><minimum_should_match>100%</minimum_should_match></mainSearchBar>"
                +
                "<purchAmount><minvalue></minvalue><maxvalue></maxvalue></purchAmount>" +
                "<PublicDate><minvalue></minvalue><maxvalue></maxvalue></PublicDate>" +
                "<PurchaseStageTerm><value>Подача заявок|;|Опубликовано</value><visiblepart>Подача заявок,Опубликовано</visiblepart></PurchaseStageTerm>"
                +
                "<SourceTerm><value>Приватизация, аренда и продажа прав|;|Реализация имущества|;|Торги коммерческих заказчиков</value><visiblepart>Приватизация, аренда и продажа прав,Реализация иму...</visiblepart></SourceTerm>"
                +
                "<RegionNameTerm><value>г Севастополь|;|Севастополь</value><visiblepart>г Севастополь,Севастополь</visiblepart></RegionNameTerm>"
                +
                "<RequestStartDate><minvalue></minvalue><maxvalue></maxvalue></RequestStartDate>" +
                "<RequestDate><minvalue></minvalue><maxvalue></maxvalue></RequestDate>" +
                "<AuctionBeginDate><minvalue></minvalue><maxvalue></maxvalue></AuctionBeginDate>" +
                "<okdp2MultiMatch><value></value></okdp2MultiMatch>" +
                "<okdp2tree><value></value><productField></productField><branchField></branchField></okdp2tree>" +
                "<classifier><visiblepart></visiblepart></classifier>" +
                "<orgCondition><value></value></orgCondition>" +
                "<orgDictionary><value></value></orgDictionary>" +
                "<organizator><visiblepart></visiblepart></organizator>" +
                "<CustomerCondition><value></value></CustomerCondition>" +
                "<CustomerDictionary><value></value></CustomerDictionary>" +
                "<customer><visiblepart></visiblepart></customer>" +
                "<PurchaseWayTerm><value></value><visiblepart></visiblepart></PurchaseWayTerm>" +
                "<PurchaseTypeNameTerm><value></value><visiblepart></visiblepart></PurchaseTypeNameTerm>" +
                "<BranchNameTerm><value></value><visiblepart></visiblepart></BranchNameTerm>" +
                "<isSharedTerm><value></value><visiblepart></visiblepart></isSharedTerm>" +
                "<isHasComplaint><value></value></isHasComplaint>" +
                "<isPurchCostDetails><value></value></isPurchCostDetails>" +
                "<notificationFeatures><value></value><visiblepart></visiblepart></notificationFeatures>" +
                "</filters>" +
                "<fields>" +
                "<field>TradeSectionId</field><field>purchAmount</field><field>purchCurrency</field><field>purchCodeTerm</field>"
                +
                "<field>PurchaseTypeName</field><field>purchStateName</field><field>BidStatusName</field><field>OrgName</field>"
                +
                "<field>SourceTerm</field><field>PublicDate</field><field>RequestDate</field><field>RequestStartDate</field>"
                +
                "<field>RequestAcceptDate</field><field>EndDate</field><field>CreateRequestHrefTerm</field><field>CreateRequestAlowed</field>"
                +
                "<field>purchName</field><field>BidName</field><field>SourceHrefTerm</field><field>objectHrefTerm</field>"
                +
                "<field>needPayment</field><field>IsSMP</field><field>isIncrease</field><field>isHasComplaint</field>" +
                "<field>isPurchCostDetails</field><field>purchType</field>" +
                "</fields>" +
                "<sort><value>default</value><direction></direction></sort>" +
                "<aggregations><empty><filterType>filter_aggregation</filterType><field></field></empty></aggregations>"
                +
                "<size>" + size + "</size>" +
                "<from>0</from>" +
                "</elasticrequest>";
    }

    /**
     * Парсит данные лота из JSON объекта (_source из ElasticSearch)
     */
    private Procurement parseLotFromJson(JsonObject source) {
        try {
            // Извлекаем номер лота (purchCodeTerm)
            String lotNumber = getJsonString(source, "purchCodeTerm");
            if (lotNumber == null || lotNumber.isEmpty()) {
                log.debug("No lot number found in JSON");
                return null;
            }

            // Извлекаем название лота (BidName)
            String bidName = getJsonString(source, "BidName");

            // Извлекаем название закупки (purchName)
            String purchName = getJsonString(source, "purchName");

            // Формируем полное название (BidName + purchName)
            String title = bidName != null ? bidName : "";
            if (purchName != null && !purchName.isEmpty()) {
                title = title.isEmpty() ? purchName : title + " - " + purchName;
            }

            if (title.isEmpty()) {
                log.warn("Empty title for lot {}, skipping", lotNumber);
                return null;
            }

            // Адрес: пробуем извлечь из текста названия по шаблонам "по адресу:" и т.п.
            String address = extractAddressFromTitle(title);
            if (address == null || address.trim().isEmpty()) {
                address = "г. Севастополь";
            }

            // Извлекаем ссылку на лот (objectHrefTerm)
            String lotLink = getJsonString(source, "objectHrefTerm");
            if (lotLink == null || lotLink.isEmpty()) {
                lotLink = BASE_URL + "/OpenTradeInfo.aspx";
            }

            // Тип закупки (PurchaseTypeName)
            String purchaseType = getJsonString(source, "PurchaseTypeName");

            // Статус заявки (BidStatusName)
            String bidStatus = getJsonString(source, "BidStatusName");

            // Организация (OrgName)
            String orgName = getJsonString(source, "OrgName");

            // Дата окончания приема заявок (RequestDate)
            String requestDate = getJsonString(source, "RequestDate");
            String deadline = formatDate(requestDate);

            // Стоимость (purchAmount)
            Double price = getJsonDouble(source, "purchAmount");

            // Извлекаем площадь из названия
            Double area = extractAreaFromText(title);

            // Источник (SourceTerm)
            String sourceTerm = getJsonString(source, "SourceTerm");

            // Создаем объект лота
            Procurement procurement = Procurement.builder()
                    .number(lotNumber)
                    .title(title)
                    .address(address)
                    .link(lotLink)
                    .lotType(purchaseType)
                    .biddTypeName(bidStatus)
                    .deadline(deadline)
                    .area(area)
                    .price(price)
                    .imageUrls(new ArrayList<>())
                    .source("sberbank-ast.ru")
                    .build();

            log.debug("Parsed SberAst lot from JSON: {} - {}", lotNumber, title);

            // ПРИМЕЧАНИЕ: Детальная страница Сбербанк-АСТ рендерится через JavaScript,
            // поэтому Jsoup не может парсить данные (получаются страницы-заглушки ~1883
            // символа).
            // Вся необходимая информация уже есть в JSON API.
            // Парсинг детальной страницы отключен для оптимизации.

            // if (lotLink != null && !lotLink.isEmpty()) {
            // try {
            // fetchAndParseDetailPage(procurement, lotLink);
            // } catch (Exception e) {
            // log.warn("Failed to fetch detail page for lot {}: {}", lotNumber,
            // e.getMessage());
            // }
            // }

            return procurement;

        } catch (Exception e) {
            log.error("Error parsing lot from JSON: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Пытается извлечь адрес из строки названия: после подстроки "по адресу:" до
     * конца или до следующего разделителя
     */
    private String extractAddressFromTitle(String title) {
        if (title == null)
            return null;
        String lower = title.toLowerCase();
        int idx = lower.indexOf("по адресу:");
        if (idx >= 0) {
            String tail = title.substring(idx + "по адресу:".length()).trim();
            // Обрезаем по разделителям, если есть
            String[] stops = { " - ", " — ", ";", ". ", "\n" };
            for (String stop : stops) {
                int s = tail.indexOf(stop);
                if (s > 5) { // чтобы не резать слишком рано
                    tail = tail.substring(0, s).trim();
                    break;
                }
            }
            return tail;
        }
        // Если явного маркера нет, пробуем найти сегмент с городом или улицей
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(г\\.\\s*Севастополь[^,]*,?[^\\n]*)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(title);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Безопасно извлекает строковое значение из JSON
     */
    private String getJsonString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /**
     * Безопасно извлекает числовое значение из JSON
     */
    private Double getJsonDouble(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsDouble();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Форматирует дату из формата "05.11.2025 18:00" в "05-11-2025"
     */
    private String formatDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            // Извлекаем только дату (до пробела)
            String datePart = dateStr.split(" ")[0];
            // Конвертируем из dd.MM.yyyy в dd-MM-yyyy
            SimpleDateFormat inputFormat = new SimpleDateFormat("dd.MM.yyyy");
            SimpleDateFormat outputFormat = new SimpleDateFormat("dd-MM-yyyy");
            Date date = inputFormat.parse(datePart);
            return outputFormat.format(date);
        } catch (Exception e) {
            log.debug("Failed to format date: {}", dateStr);
            return dateStr;
        }
    }

    /**
     * Извлекает площадь из текста (кв.м, м2, м.кв)
     */
    private Double extractAreaFromText(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = AREA_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                String areaStr = matcher.group(1).replaceAll("[\\s,]", ".");
                return Double.parseDouble(areaStr);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse area from: {}", matcher.group(1));
            }
        }
        return null;
    }

    /**
     * Получает и парсит страницу детального описания лота
     * Извлекает дополнительную информацию: срок договора, задаток, организатора,
     * тип аренды
     */
    private void fetchAndParseDetailPage(Procurement procurement, String detailUrl) {
        try {
            log.info("Fetching detail page for lot {}: {}", procurement.getNumber(), detailUrl);

            // Делаем запрос к странице лота
            org.jsoup.nodes.Document doc = Jsoup.connect(detailUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(20000)
                    .get();

            log.info("Detail page fetched successfully for lot {}, page length: {} chars",
                    procurement.getNumber(), doc.html().length());

            // Сохраняем HTML для отладки (только первые 1000 символов)
            if (Config.getParserVerbose()) {
                log.debug("Detail page HTML sample (first 1000 chars): {}",
                        doc.html().substring(0, Math.min(1000, doc.html().length())));
            }

            // Извлекаем срок договора (contract term)
            // Ищем по различным вариантам: "Срок договора", "Срок аренды", "Срок"
            log.debug("Looking for contract term in lot {}...", procurement.getNumber());
            String contractTerm = extractDetailField(doc,
                    "срок договора", "срок аренды", "срок действия договора", "срок действия");
            if (contractTerm != null && !contractTerm.isEmpty()) {
                procurement.setContractTerm(contractTerm);
                log.info("✓ Found contract term for lot {}: {}", procurement.getNumber(), contractTerm);
            } else {
                log.warn("✗ Contract term not found for lot {}", procurement.getNumber());
            }

            // Извлекаем задаток (deposit)
            // Ищем по вариантам: "Задаток", "Обеспечение заявки", "Размер задатка"
            log.debug("Looking for deposit in lot {}...", procurement.getNumber());
            String depositStr = extractDetailField(doc,
                    "задаток", "обеспечение заявки", "размер задатка", "обеспечение");
            if (depositStr != null && !depositStr.isEmpty()) {
                log.debug("Found deposit string: {}", depositStr);
                Double deposit = parseAmount(depositStr);
                if (deposit != null) {
                    procurement.setDeposit(deposit);
                    log.info("✓ Found deposit for lot {}: {} руб", procurement.getNumber(), deposit);
                } else {
                    log.warn("Failed to parse deposit amount from: {}", depositStr);
                }
            } else {
                log.warn("✗ Deposit not found for lot {}", procurement.getNumber());
            }

            // Извлекаем организатора торгов (organizer)
            // Ищем по вариантам: "Организатор", "Организатор торгов", "Организатор
            // аукциона"
            log.debug("Looking for organizer in lot {}...", procurement.getNumber());
            String organizer = extractDetailField(doc,
                    "организатор торгов", "организатор", "организатор аукциона");
            if (organizer != null && !organizer.isEmpty()) {
                procurement.setDepositRecipientName(organizer);
                log.info("✓ Found organizer for lot {}: {}", procurement.getNumber(), organizer);
            } else {
                log.warn("✗ Organizer not found for lot {}", procurement.getNumber());
            }

            // Определяем тип аренды (месяц/год)
            // Ищем в тексте страницы упоминания "руб./мес", "руб./год", "в месяц", "в год"
            String pageText = doc.text().toLowerCase();
            log.debug("Looking for rental period in lot {}...", procurement.getNumber());
            if (pageText.contains("руб./мес") || pageText.contains("в месяц") ||
                    pageText.contains("ежемесячная плата") || pageText.contains("руб/мес")) {
                procurement.setPricePeriod("месяц");
                log.info("✓ Found rental period for lot {}: месяц", procurement.getNumber());

                // Если есть цена и она не записана как monthlyPrice
                if (procurement.getPrice() != null && procurement.getMonthlyPrice() == null) {
                    procurement.setMonthlyPrice(procurement.getPrice());
                }
            } else if (pageText.contains("руб./год") || pageText.contains("в год") ||
                    pageText.contains("ежегодная плата") || pageText.contains("руб/год")) {
                procurement.setPricePeriod("год");
                log.info("✓ Found rental period for lot {}: год", procurement.getNumber());
            } else {
                log.debug("Rental period not found in page text for lot {}", procurement.getNumber());
            }

            // Также пробуем найти ежемесячную арендную плату отдельно
            String monthlyPriceStr = extractDetailField(doc,
                    "ежемесячная арендная плата", "ежемесячная плата", "арендная плата в месяц");
            if (monthlyPriceStr != null && !monthlyPriceStr.isEmpty()) {
                log.debug("Found monthly price string: {}", monthlyPriceStr);
                Double monthlyPrice = parseAmount(monthlyPriceStr);
                if (monthlyPrice != null) {
                    procurement.setMonthlyPrice(monthlyPrice);
                    procurement.setPricePeriod("месяц");
                    log.info("✓ Found monthly price for lot {}: {} руб", procurement.getNumber(), monthlyPrice);
                }
            }

            log.info("Detail parsing completed for lot {}", procurement.getNumber());

        } catch (java.io.IOException e) {
            log.error("IO Error fetching detail page for lot {}: {}", procurement.getNumber(), e.getMessage());
        } catch (Exception e) {
            log.error("Error fetching detail page for lot {}: {}", procurement.getNumber(), e.getMessage(), e);
        }
    }

    /**
     * Извлекает значение поля из HTML документа по названию поля
     * Ищет элементы, содержащие указанный текст (case-insensitive)
     */
    private String extractDetailField(org.jsoup.nodes.Document doc, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                log.debug("Searching for field: '{}'", fieldName);

                // Вариант 1: Поиск в таблицах через CSS селекторы (наиболее надежный для Sber)
                // Ищем td/th, содержащий название поля, и берем следующий td
                String[] tableSelectors = {
                        "td:containsOwn(" + fieldName + ") + td",
                        "th:containsOwn(" + fieldName + ") + td",
                        "td:matches((?i).*" + Pattern.quote(fieldName) + ".*) + td"
                };

                for (String selector : tableSelectors) {
                    try {
                        org.jsoup.select.Elements cells = doc.select(selector);
                        if (!cells.isEmpty()) {
                            String value = cells.first().text().trim();
                            if (!value.isEmpty() && !value.equalsIgnoreCase(fieldName)) {
                                log.debug("✓ Extracted field '{}' from table (selector: {}): {}", fieldName, selector,
                                        value);
                                return value;
                            }
                        }
                    } catch (Exception e) {
                        log.trace("Selector '{}' failed: {}", selector, e.getMessage());
                    }
                }

                // Вариант 2: Поиск элементов, содержащих название поля (общий подход)
                org.jsoup.select.Elements elements = doc.getElementsContainingOwnText(fieldName);
                log.debug("Found {} elements containing '{}'", elements.size(), fieldName);

                for (org.jsoup.nodes.Element element : elements) {
                    // Вариант 2.1: значение в следующем элементе (sibling)
                    org.jsoup.nodes.Element nextSibling = element.nextElementSibling();
                    if (nextSibling != null && !nextSibling.text().isEmpty()) {
                        String value = nextSibling.text().trim();
                        if (!value.toLowerCase().contains(fieldName.toLowerCase()) && value.length() > 2) {
                            log.debug("✓ Extracted field '{}' from next sibling: {}", fieldName, value);
                            return value;
                        }
                    }

                    // Вариант 2.2: значение после двоеточия в том же элементе
                    String elementText = element.text();
                    if (elementText.contains(":")) {
                        String[] parts = elementText.split(":", 2);
                        if (parts.length == 2 && parts[1].trim().length() > 0) {
                            String value = parts[1].trim();
                            // Убедимся, что это не просто повтор названия поля
                            if (!value.equalsIgnoreCase(parts[0].trim()) && value.length() > 2) {
                                log.debug("✓ Extracted field '{}' from same element (after colon): {}", fieldName,
                                        value);
                                return value;
                            }
                        }
                    }

                    // Вариант 2.3: значение в parent, после удаления названия поля
                    org.jsoup.nodes.Element parent = element.parent();
                    if (parent != null && parent.children().size() > 1) {
                        // Если есть несколько дочерних элементов, возможно значение в другом child
                        for (org.jsoup.nodes.Element child : parent.children()) {
                            if (child != element && !child.text().isEmpty()) {
                                String childText = child.text().trim();
                                if (!childText.toLowerCase().contains(fieldName.toLowerCase())
                                        && childText.length() > 2) {
                                    log.debug("✓ Extracted field '{}' from parent's child: {}", fieldName, childText);
                                    return childText;
                                }
                            }
                        }
                    }
                }

                // Вариант 3: Поиск через регулярное выражение в тексте всей страницы (последняя
                // попытка)
                String pageText = doc.text();
                Pattern pattern = Pattern.compile(
                        "(?i)" + Pattern.quote(fieldName) + "\\s*:?\\s*([^\\n\\.]{3,100})",
                        Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(pageText);
                if (matcher.find()) {
                    String value = matcher.group(1).trim();
                    if (!value.isEmpty() && !value.equalsIgnoreCase(fieldName)) {
                        log.debug("✓ Extracted field '{}' from page text (regex): {}", fieldName, value);
                        return value;
                    }
                }

            } catch (Exception e) {
                log.debug("Error extracting field '{}': {}", fieldName, e.getMessage());
            }
        }
        log.debug("✗ Field not found for any of: {}", java.util.Arrays.toString(fieldNames));
        return null;
    }

    /**
     * Парсит числовое значение (сумму) из строки
     * Поддерживает форматы: "10 000", "10000", "10 000.50", "10000,50"
     */
    private Double parseAmount(String amountStr) {
        if (amountStr == null || amountStr.isEmpty()) {
            return null;
        }
        try {
            // Удаляем все нечисловые символы кроме цифр, точки и запятой
            String cleaned = amountStr.replaceAll("[^\\d.,]", "");
            // Заменяем запятую на точку
            cleaned = cleaned.replace(",", ".");
            // Удаляем пробелы
            cleaned = cleaned.replaceAll("\\s+", "");

            if (!cleaned.isEmpty()) {
                return Double.parseDouble(cleaned);
            }
        } catch (NumberFormatException e) {
            log.debug("Failed to parse amount from: {}", amountStr);
        }
        return null;
    }

    /**
     * Проверяет, соответствует ли лот заданным фильтрам
     *
     * Для Сбербанк-АСТ фильтры уже применены на уровне API:
     * - Регион: Севастополь
     * - Источник: Приватизация, аренда и продажа прав, Реализация имущества, Торги
     * коммерческих заказчиков
     * - Этап: Подача заявок, Опубликовано
     *
     * Дополнительно проверяем регион и ключевые слова для фильтрации недвижимости
     */
    private boolean matchesFilters(Procurement procurement) {
        String title = procurement.getTitle();
        String address = procurement.getAddress();

        // Проверяем регион (Севастополь) - должен быть уже отфильтрован на уровне API
        String addressLower = address != null ? address.toLowerCase() : "";
        String titleLower = title != null ? title.toLowerCase() : "";
        boolean matchesRegion = addressLower.contains("севастополь") || titleLower.contains("севастополь");

        if (!matchesRegion) {
            log.debug("Lot {} doesn't match region filter", procurement.getNumber());
            return false;
        }

        // Применяем фильтр по ключевым словам недвижимости
        boolean matchesKeywords = lotFilter.isRealEstateLot(
                title,
                address,
                true, // отправлять уведомление админу если не подошло
                procurement.getNumber(),
                procurement.getLink());

        if (!matchesKeywords) {
            log.debug("Lot {} doesn't match keyword filter", procurement.getNumber());
            return false;
        }

        log.debug("Lot {} passed all filters (region: Севастополь, keywords: matched)", procurement.getNumber());
        return true;
    }
}
