package com.example.procurement;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер для сайта Центра дистанционных торгов (bankrot.cdtrf.ru)
 * Банкротные торги
 */
@Slf4j
public class BankrotCdtrfParser {
    private static final String BASE_URL = "https://bankrot.cdtrf.ru";
    private static final String SEARCH_URL = BASE_URL + "/public/undef/card/tradel.aspx";
    private static final String DEFAULT_IMAGE_PATH = "default_bankrot_image.jpg";

    // Паттерны для извлечения данных
    private static final Pattern PRICE_PATTERN = Pattern.compile("([\\d\\s]+[,.]?\\d*)\\s*(?:руб|₽)");
    private static final Pattern AREA_PATTERN = Pattern.compile("([\\d\\s]+[,.]?\\d*)\\s*(?:кв\\.?\\s*м|м2|м\\.кв)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("trade\\.aspx\\?id=(\\d+)");

    private final LotFilter lotFilter = LotFilter.createDefault();

    /**
     * Парсит лоты с сайта bankrot.cdtrf.ru
     *
     * @param maxCount             максимальное количество лотов
     * @param checkDuplicates      проверять ли дубликаты в базе
     * @param notifyAdminOnNoMatch отправлять ли уведомление админу о
     *                             несоответствующих лотах
     * @return список лотов
     */
    public List<Procurement> parse(int maxCount, boolean checkDuplicates, boolean notifyAdminOnNoMatch) {
        List<Procurement> procurements = new ArrayList<>();

        try {
            log.info("Starting BankrotCdtrf parsing from {}", SEARCH_URL);

            // ШАГ 1: Получаем начальную страницу GET запросом для извлечения ASP.NET полей
            // и cookies
            log.info("Step 1: Fetching initial page to extract ASP.NET hidden fields and session cookies");
            Connection.Response initialResponse = Jsoup.connect(SEARCH_URL)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(120000) // 2 минуты
                    .execute();

            Document initialPage = initialResponse.parse();
            Map<String, String> cookies = initialResponse.cookies();
            log.info("Extracted {} cookies from initial page", cookies.size());

            // Извлекаем скрытые поля ASP.NET
            Map<String, String> searchParams = new HashMap<>();

            // Извлекаем все скрытые поля из формы
            for (Element input : initialPage.select("input[type=hidden]")) {
                String name = input.attr("name");
                String value = input.attr("value");
                if (!name.isEmpty()) {
                    searchParams.put(name, value);
                }
            }

            log.info("Extracted {} hidden fields from initial page", searchParams.size());

            // ШАГ 2: Добавляем параметры для ASP.NET AJAX UpdatePanel
            searchParams.put("ctl00$ToolkitScriptManager1", "ctl00$cph1$upList|ctl00$cph1$btFilter");
            searchParams.put("__EVENTTARGET", "ctl00$cph1$btFilter");
            searchParams.put("__EVENTARGUMENT", "");
            searchParams.put("__ASYNCPOST", "true");

            // Параметры поиска из cURL
            searchParams.put("ctl00$cph1$tbFind", "севастополь,"); // ВАЖНО: с запятой, как в cURL
            searchParams.put("ctl00$cph1$hiddenFind", ""); // В cURL это поле пустое!

            // Чекбоксы (в cURL они есть)
            searchParams.put("ctl00$cph1$cbDeclare", "on");
            searchParams.put("ctl00$cph1$cbRecieveReq", "on");

            // Остальные поля (пустые в cURL)
            searchParams.put("ctl00$cph1$hiddenTradeId", "");
            searchParams.put("ctl00$cph1$ddlTradeTypeID", "0");
            searchParams.put("ctl00$cph1$hiddenTradeTypeID", "");
            searchParams.put("ctl00$cph1$hiddenFilterShowed", "1");
            searchParams.put("ctl00$cph1$ddlPriceTypeID", "0");
            searchParams.put("ctl00$cph1$hiddenPriceTypeID", "");
            searchParams.put("ctl00$cph1$tbRequestTimeBegin1", "");
            searchParams.put("ctl00$cph1$hiddenRequestTimeBegin1", "");
            searchParams.put("ctl00$cph1$tbRequestTimeBegin2", "");
            searchParams.put("ctl00$cph1$hiddenRequestTimeBegin2", "");
            searchParams.put("ctl00$cph1$tbRequestTimeEnd1", "");
            searchParams.put("ctl00$cph1$hiddenRequestTimeEnd1", "");
            searchParams.put("ctl00$cph1$tbRequestTimeEnd2", "");
            searchParams.put("ctl00$cph1$hiddenRequestTimeEnd2", "");
            searchParams.put("ctl00$cph1$tbTradeTime1", "");
            searchParams.put("ctl00$cph1$hiddenTradeTime1", "");
            searchParams.put("ctl00$cph1$tbTradeTime2", "");
            searchParams.put("ctl00$cph1$hiddenTradeTime2", "");

            // Hidden поля статусов (пустые в cURL)
            searchParams.put("ctl00$cph1$hiddenPrepare", "");
            searchParams.put("ctl00$cph1$hiddenFormed", "");
            searchParams.put("ctl00$cph1$hiddenRegister", "");
            searchParams.put("ctl00$cph1$hiddenDeclare", "");
            searchParams.put("ctl00$cph1$hiddenRecieveReq", "");
            searchParams.put("ctl00$cph1$hiddenDefinePart", "");
            searchParams.put("ctl00$cph1$hiddenTradeGo", "");
            searchParams.put("ctl00$cph1$hiddenSummingUp", "");
            searchParams.put("ctl00$cph1$hiddenComplete", "");
            searchParams.put("ctl00$cph1$hiddenNotHeld", "");
            searchParams.put("ctl00$cph1$hiddenSignContract", "");
            searchParams.put("ctl00$cph1$hiddenSuspend", "");
            searchParams.put("ctl00$cph1$hiddenCancel", "");
            searchParams.put("ctl00$cph1$hiddenDelete", "");
            searchParams.put("ctl00$cph1$hiddenNotProt", "");
            searchParams.put("ctl00$cph1$tbOrgName", "");
            searchParams.put("ctl00$cph1$hiddenOrgId", "");
            searchParams.put("ctl00$cph1$hiddenOrgName", "");
            searchParams.put("ctl00$cph1$pgvTrades$ctl22$ddlPager", "Номер страницы");

            log.info("Step 2: Sending POST request with search params: севастополь, cbDeclare=on, cbRecieveReq=on");

            // ШАГ 3: Отправляем POST запрос с параметрами поиска (ASP.NET AJAX) + cookies
            // из первого запроса
            Connection.Response response = Jsoup.connect(SEARCH_URL)
                    .userAgent(
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("X-MicrosoftAjax", "Delta=true")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", BASE_URL)
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .cookies(cookies) // Передаем cookies из первого запроса (важно для ASP.NET сессии!)
                    .timeout(600000) // 10 minutes - site is very slow via VPN
                    .data(searchParams)
                    .method(Connection.Method.POST)
                    .referrer(SEARCH_URL)
                    .followRedirects(true)
                    .ignoreContentType(true) // ASP.NET AJAX возвращает text/plain
                    .execute();

            // ASP.NET AJAX возвращает специальный формат: длина|тип|id|данные|
            // Нужно извлечь HTML из updatePanel
            String responseBody = response.body();
            log.debug("Response body length: {}", responseBody.length());

            String html = extractHtmlFromAjaxResponse(responseBody);
            Document doc = Jsoup.parse(html);

            // Ищем строки таблицы с лотами (новая структура с классом product-table)
            Elements lotRows = doc.select("table.product-table tr, table#ctl00_cph1_pgvTrades tr");
            log.info("Found {} lot rows on BankrotCdtrf", lotRows.size());

            // Определяем индексы колонок по заголовку таблицы, чтобы не зависеть от
            // смещений
            int idxTitle = -1;
            int idxPrice = -1;
            int idxDeadline = -1;
            for (Element r : lotRows) {
                Elements ths = r.select("th");
                if (!ths.isEmpty()) {
                    for (int i = 0; i < ths.size(); i++) {
                        String h = ths.get(i).text().toLowerCase();
                        if (idxTitle == -1 && h.contains("наименование торгов"))
                            idxTitle = i;
                        if (idxPrice == -1 && h.contains("начальная цена"))
                            idxPrice = i;
                        if (idxDeadline == -1 && h.contains("окончания предоставления заявок"))
                            idxDeadline = i;
                    }
                    break;
                }
            }
            // Фолбэки на стандартные позиции, если не нашли
            if (idxTitle == -1)
                idxTitle = 2;
            if (idxPrice == -1)
                idxPrice = 7;
            if (idxDeadline == -1)
                idxDeadline = 10;

            DatabaseManager db = checkDuplicates ? AppContext.getDatabaseManager() : null;

            for (Element row : lotRows) {
                if (procurements.size() >= maxCount) {
                    break;
                }

                // Пропускаем заголовок таблицы
                if (row.select("th").size() > 0) {
                    continue;
                }

                try {
                    Procurement procurement = parseLotRow(row, idxTitle, idxPrice, idxDeadline);
                    if (procurement != null) {
                        // ПРИМЕЧАНИЕ: Фильтрация по региону Севастополь происходит на стороне сервера
                        // через параметры поиска (севастополь,), дополнительная проверка не нужна

                        // Проверяем фильтры по ключевым словам (недвижимость)
                        if (!matchesFilters(procurement, notifyAdminOnNoMatch)) {
                            log.info("Lot {} doesn't match keyword filters, skipping. Title: '{}', Address: '{}'",
                                    procurement.getNumber(), procurement.getTitle(), procurement.getAddress());
                            continue;
                        }

                        // Проверяем дубликаты по описанию
                        if (checkDuplicates && db != null && db.isDuplicateByDescription(procurement.getTitle())) {
                            log.info("Duplicate lot found by description, skipping: {}", procurement.getTitle());
                            continue;
                        }

                        procurements.add(procurement);
                        log.info("Added BankrotCdtrf lot: {}", procurement.getTitle());
                    }
                } catch (Exception e) {
                    log.error("Error parsing lot row: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Error parsing BankrotCdtrf: {}", e.getMessage(), e);
        }

        log.info("Total BankrotCdtrf procurements found: {}", procurements.size());
        return procurements;
    }

    /**
     * Извлекает HTML из ASP.NET AJAX UpdatePanel ответа
     * Формат ответа: 1|#||4|26824|updatePanel|ctl00_cph1_upList|[HTML]|other_data|
     */
    private String extractHtmlFromAjaxResponse(String ajaxResponse) {
        if (ajaxResponse == null || ajaxResponse.isEmpty()) {
            log.warn("Empty AJAX response");
            return "";
        }

        try {
            // Ищем маркер updatePanel с нужным ID
            String marker = "updatePanel|ctl00_cph1_upList|";
            int startIndex = ajaxResponse.indexOf(marker);

            if (startIndex == -1) {
                log.warn("Could not find updatePanel marker in AJAX response");
                log.debug("Response preview: {}", ajaxResponse.substring(0, Math.min(500, ajaxResponse.length())));
                return "";
            }

            // Сдвигаемся к началу HTML контента
            startIndex += marker.length();

            // Находим следующий разделитель |, который обозначает конец HTML
            int endIndex = ajaxResponse.indexOf("|", startIndex);
            if (endIndex == -1) {
                // Если не нашли, берем до конца строки
                endIndex = ajaxResponse.length();
            }

            String html = ajaxResponse.substring(startIndex, endIndex);
            log.info("Extracted {} characters of HTML from AJAX response", html.length());
            log.debug("HTML preview: {}", html.substring(0, Math.min(200, html.length())));

            return html;
        } catch (Exception e) {
            log.error("Error extracting HTML from AJAX response: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * Парсит одну строку таблицы с лотом (новая структура HTML)
     */
    private Procurement parseLotRow(Element row, int idxTitle, int idxPrice, int idxDeadline) {
        try {
            Elements cells = row.select("td");
            if (cells.size() < 3) {
                log.debug("Skipping row with {} cells", cells.size());
                return null;
            }

            // Пропускаем строку с пагинацией
            if (row.select("td[colspan]").size() > 0) {
                log.debug("Skipping pagination row");
                return null;
            }

            // Ищем ссылку на лот (во второй ячейке находится код торгов со ссылкой)
            Element linkElement = row.select("a[href*=trade.aspx]").first();
            if (linkElement == null) {
                log.debug("Skipping row without trade link");
                return null;
            }

            String relativeUrl = linkElement.attr("href");
            // Формируем полный URL (ссылка может быть относительной без начального слэша)
            String lotUrl;
            if (relativeUrl.startsWith("http")) {
                lotUrl = relativeUrl;
            } else if (relativeUrl.startsWith("/")) {
                lotUrl = BASE_URL + relativeUrl;
            } else {
                lotUrl = BASE_URL + "/" + relativeUrl;
            }
            String lotNumber = extractLotNumber(lotUrl);

            // Описание лота берем по индексу заголовка (фолбэк на td[2])
            String title = "";
            if (idxTitle >= 0 && cells.size() > idxTitle) {
                title = cells.get(idxTitle).text().trim();
            } else if (cells.size() > 2) {
                title = cells.get(2).text().trim();
            }
            log.info("Parsed title for lot {}: '{}'", lotNumber, title);

            // Если описание не найдено, пытаемся извлечь из других ячеек
            if (title.isEmpty()) {
                for (Element cell : cells) {
                    String text = cell.text().trim();
                    if (!text.isEmpty() && text.length() > 20 && !text.matches("^\\d+$")) {
                        title = text;
                        break;
                    }
                }
            }

            if (title.isEmpty()) {
                log.warn("Empty title for lot {}, skipping", lotNumber);
                return null;
            }

            // Извлекаем начальную цену по индексу колонки
            Double price = null;
            if (idxPrice >= 0 && cells.size() > idxPrice) {
                String priceText = cells.get(idxPrice).text().trim();
                price = extractPrice(priceText);
            }

            // Извлекаем дату окончания приема заявок по индексу колонки
            String deadline = null;
            if (idxDeadline >= 0 && cells.size() > idxDeadline) {
                String deadlineText = cells.get(idxDeadline).text().trim();
                deadline = extractDeadline(deadlineText);
            }

            // Создаем базовый объект Procurement
            Procurement procurement = Procurement.builder()
                    .number(lotNumber)
                    .title(title)
                    .link(lotUrl)
                    .price(price)
                    .deadline(deadline)
                    .imageUrls(new ArrayList<>())
                    .source("ЦДТРФ (Банкрот)")
                    .build();

            // Парсим дополнительные детали со страницы лота
            procurement = parseLotDetails(lotUrl, lotNumber, title, procurement);

            return procurement;

        } catch (Exception e) {
            log.error("Error parsing lot row: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Парсит детальную страницу лота
     */
    private Procurement parseLotDetails(String lotUrl, String lotNumber, String title, Procurement procurement) {
        try {
            log.info("Parsing lot details from: {}", lotUrl);

            Document doc = Jsoup.connect(lotUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(30000) // 30 seconds for details page
                    .referrer(SEARCH_URL)
                    .get();

            // Извлекаем адрес (если еще не установлен)
            if (procurement.getAddress() == null) {
                String address = extractAddress(doc, title);
                procurement.setAddress(address != null ? address : "г. Севастополь");
            }

            // Извлекаем цену (если еще не установлена)
            if (procurement.getPrice() == null) {
                Double price = extractPriceFromDetails(doc, title);
                procurement.setPrice(price);
            }

            // Извлекаем площадь
            Double area = extractAreaFromDetails(doc, title);
            procurement.setArea(area);

            // Извлекаем дедлайн (если еще не установлен)
            if (procurement.getDeadline() == null) {
                String deadline = extractDeadlineFromDetails(doc);
                procurement.setDeadline(deadline);
            }

            // Извлекаем залог (сохраняем полный текст в contractTerm)
            String depositText = extractDepositTextFromDetails(doc, procurement.getPrice());
            if (depositText != null && !depositText.isEmpty()) {
                procurement.setContractTerm(depositText);
                // Пытаемся извлечь числовое значение
                Double deposit = extractDepositFromDetails(doc);
                procurement.setDeposit(deposit);
            }

            // Определяем тип лота
            String lotType = determineLotType(title, doc);
            procurement.setLotType(lotType);

            // Извлекаем кадастровый номер
            String cadastralNumber = extractCadastralNumber(doc, title);
            procurement.setCadastralNumber(cadastralNumber);

            // Добавляем путь к дефолтной картинке
            if (procurement.getImageUrls().isEmpty()) {
                procurement.getImageUrls().add(DEFAULT_IMAGE_PATH);
            }

            // Небольшая задержка между запросами
            Thread.sleep(500);

        } catch (Exception e) {
            log.error("Error parsing lot details for {}: {}", lotNumber, e.getMessage());
        }

        return procurement;
    }

    /**
     * Извлекает номер лота из URL
     */
    private String extractLotNumber(String url) {
        try {
            Matcher matcher = NUMBER_PATTERN.matcher(url);
            if (matcher.find()) {
                return "cdtrf-" + matcher.group(1);
            }
            return "cdtrf-" + Math.abs(url.hashCode());
        } catch (Exception e) {
            return "cdtrf-" + System.currentTimeMillis();
        }
    }

    /**
     * Извлекает адрес из детальной страницы или заголовка
     */
    private String extractAddress(Document doc, String title) {
        try {
            // Ищем адрес в различных местах
            String[] addressSelectors = {
                    "td:contains(Адрес) + td",
                    "td:contains(Местонахождение) + td",
                    "td:contains(Место нахождения) + td",
                    "div:contains(адрес)",
                    "span:contains(адрес)"
            };

            for (String selector : addressSelectors) {
                Element addressElement = doc.selectFirst(selector);
                if (addressElement != null && !addressElement.text().trim().isEmpty()) {
                    String addr = addressElement.text().trim();
                    if (addr.toLowerCase().contains("севастополь")) {
                        return addr;
                    }
                }
            }

            // Ищем в заголовке
            if (title.toLowerCase().contains("адрес")) {
                Pattern pattern = Pattern.compile("адрес[:\\s]+([^.;]+)", Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(title);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }

            // Ищем адрес в тексте страницы
            String pageText = doc.text();
            if (pageText.contains("Севастополь") || pageText.contains("севастополь")) {
                Pattern pattern = Pattern.compile("(?:г\\.?\\s*)?Севастополь[,\\s]+[^.;]{10,100}",
                        Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(pageText);
                if (matcher.find()) {
                    return matcher.group(0).trim();
                }
            }

        } catch (Exception e) {
            log.warn("Error extracting address: {}", e.getMessage());
        }
        return "г. Севастополь";
    }

    /**
     * Извлекает цену из детальной страницы
     */
    private Double extractPriceFromDetails(Document doc, String title) {
        try {
            String[] priceSelectors = {
                    "td:matches(^Начальная цена$) + td",
                    "td:matches(^Начальная цена:$) + td",
                    "td:contains(Начальная цена) + td",
                    "td:contains(начальная цена) + td"
            };

            for (String selector : priceSelectors) {
                Element priceElement = doc.selectFirst(selector);
                if (priceElement != null) {
                    String text = priceElement.text();
                    log.debug("Found potential price text with selector '{}': '{}'", selector, text);
                    Double price = extractPrice(text);
                    if (price != null)
                        return price;
                }
            }

            // Ищем в заголовке
            return extractPrice(title);
        } catch (Exception e) {
            log.warn("Error extracting price from details: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Извлекает площадь из детальной страницы
     */
    private Double extractAreaFromDetails(Document doc, String title) {
        try {
            String[] areaSelectors = {
                    "td:contains(Площадь) + td",
                    "td:contains(Общая площадь) + td",
                    "td:contains(площадь)"
            };

            for (String selector : areaSelectors) {
                Element areaElement = doc.selectFirst(selector);
                if (areaElement != null) {
                    Matcher matcher = AREA_PATTERN.matcher(areaElement.text());
                    if (matcher.find()) {
                        String areaStr = matcher.group(1).replaceAll("\\s", "").replace(",", ".");
                        return Double.parseDouble(areaStr);
                    }
                }
            }

            // Ищем в заголовке
            Matcher matcher = AREA_PATTERN.matcher(title);
            if (matcher.find()) {
                String areaStr = matcher.group(1).replaceAll("\\s", "").replace(",", ".");
                return Double.parseDouble(areaStr);
            }
        } catch (Exception e) {
            log.warn("Error extracting area from details: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Извлекает дедлайн из детальной страницы
     */
    private String extractDeadlineFromDetails(Document doc) {
        try {
            String[] deadlineSelectors = {
                    "td:contains(Дата окончания) + td",
                    "td:contains(Окончание приема заявок) + td",
                    "td:contains(Прием заявок до) + td",
                    "td:contains(Подача заявок до) + td"
            };

            for (String selector : deadlineSelectors) {
                Element deadlineElement = doc.selectFirst(selector);
                if (deadlineElement != null) {
                    return extractDeadline(deadlineElement.text());
                }
            }
        } catch (Exception e) {
            log.warn("Error extracting deadline from details: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Извлекает залог из детальной страницы
     */
    /**
     * Извлекает полный текст задатка (для отображения)
     */
    private String extractDepositTextFromDetails(Document doc, Double price) {
        try {
            String[] depositSelectors = {
                    "td:contains(Задаток) + td",
                    "td:contains(Обеспечение заявки) + td",
                    "td:contains(Размер задатка) + td"
            };

            for (String selector : depositSelectors) {
                Element depositElement = doc.selectFirst(selector);
                if (depositElement != null) {
                    String text = depositElement.text().trim();
                    if (!text.isEmpty()) {
                        // Пытаемся извлечь процент из текста
                        Pattern percentPattern = Pattern.compile("(\\d+)\\s*%");
                        Matcher matcher = percentPattern.matcher(text);

                        if (matcher.find() && price != null) {
                            int percent = Integer.parseInt(matcher.group(1));
                            double calculatedDeposit = price * percent / 100.0;
                            java.text.DecimalFormat df = new java.text.DecimalFormat("#,###");
                            String formattedDeposit = df.format(calculatedDeposit);
                            return "💰Задаток: " + percent + "% от начальной цены (" + formattedDeposit + " ₽)";
                        } else {
                            return "💰Задаток: " + text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting deposit text from details: {}", e.getMessage());
        }
        return null;
    }

    private Double extractDepositFromDetails(Document doc) {
        try {
            String[] depositSelectors = {
                    "td:contains(Задаток) + td",
                    "td:contains(Обеспечение заявки) + td",
                    "td:contains(Размер задатка) + td"
            };

            for (String selector : depositSelectors) {
                Element depositElement = doc.selectFirst(selector);
                if (depositElement != null) {
                    return extractPrice(depositElement.text());
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting deposit number from details: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Извлекает кадастровый номер
     */
    private String extractCadastralNumber(Document doc, String title) {
        try {
            Pattern cadastralPattern = Pattern.compile("(\\d{2}:\\d{2}:\\d{6,7}:\\d+)");

            // Ищем в документе
            String pageText = doc.text();
            Matcher matcher = cadastralPattern.matcher(pageText);
            if (matcher.find()) {
                return matcher.group(1);
            }

            // Ищем в заголовке
            matcher = cadastralPattern.matcher(title);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("Error extracting cadastral number: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Определяет тип лота
     */
    private String determineLotType(String title, Document doc) {
        String titleLower = title.toLowerCase();
        String docText = doc.text().toLowerCase();
        String combined = titleLower + " " + docText;

        if (combined.contains("аренда") || combined.contains("арендa")) {
            return "Реализация имущества должников";
        } else if (combined.contains("продажа") || combined.contains("реализация")) {
            return "Реализация имущества должников";
        } else if (combined.contains("право требования")) {
            return "Реализация имущества должников";
        }

        return "Реализация имущества должников";
    }

    /**
     * Извлекает цену из текста
     * Поддерживает форматы: "9 810 900,00", "9 810 900.00", "9810900,00" и т.д.
     */
    private Double extractPrice(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Если текст слишком длинный, это скорее всего описание, а не цена
        if (text.length() > 50) {
            log.debug("Text too long for price extraction ({} chars): {}", text.length(),
                    text.substring(0, Math.min(50, text.length())));
            return null;
        }

        try {
            // Сначала пробуем стандартный паттерн
            Matcher matcher = PRICE_PATTERN.matcher(text);
            if (matcher.find()) {
                String priceStr = matcher.group(1).replaceAll("\\s", "").replace(",", ".");
                return Double.parseDouble(priceStr);
            }

            // Если не нашли, пробуем найти число с пробелами и запятой/точкой
            // Формат: "9 810 900,00" или "9 810 900.00"
            Pattern numberPattern = Pattern.compile("([\\d\\s]+[,.]?\\d*)");
            matcher = numberPattern.matcher(text.replaceAll("&nbsp;", " "));
            if (matcher.find()) {
                String priceStr = matcher.group(1).replaceAll("\\s", "").replace(",", ".");
                return Double.parseDouble(priceStr);
            }
        } catch (Exception e) {
            log.warn("Failed to parse price from: {}", text);
        }
        return null;
    }

    /**
     * Извлекает дедлайн из текста
     */
    private String extractDeadline(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        try {
            // Пытаемся распарсить различные форматы дат
            String[] dateFormats = {
                    "dd.MM.yyyy HH:mm",
                    "dd.MM.yyyy HH:mm:ss",
                    "dd.MM.yyyy",
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd"
            };

            for (String format : dateFormats) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat(format);
                    Date date = sdf.parse(text.trim());
                    return new SimpleDateFormat("dd-MM-yyyy").format(date);
                } catch (Exception ignored) {
                }
            }

            // Если не удалось распарсить, возвращаем как есть
            return text.trim();
        } catch (Exception e) {
            log.warn("Failed to parse deadline from: {}", text);
        }
        return null;
    }

    /**
     * Проверяет, соответствует ли лот заданным фильтрам
     * 
     * @param procurement          лот для проверки
     * @param notifyAdminOnNoMatch отправлять ли уведомление админу если не подошел
     */
    private boolean matchesFilters(Procurement procurement, boolean notifyAdminOnNoMatch) {
        String title = procurement.getTitle();
        String address = procurement.getAddress();

        // Проверяем регион (Севастополь)
        String addressLower = address != null ? address.toLowerCase() : "";
        String titleLower = title != null ? title.toLowerCase() : "";
        boolean matchesRegion = addressLower.contains("севастополь") || titleLower.contains("севастополь");

        if (!matchesRegion) {
            log.info("Lot {} failed region check. Address: '{}', Title: '{}'", procurement.getNumber(), address, title);
        }

        // Используем общий фильтр по ключевым словам
        boolean matchesKeywords = lotFilter.isRealEstateLot(title, address, notifyAdminOnNoMatch,
                procurement.getNumber(), procurement.getLink());

        if (!matchesKeywords) {
            log.info("Lot {} failed keyword check. Title: '{}'", procurement.getNumber(), title);
        } else {
            log.info("Lot {} passed all filters", procurement.getNumber());
        }

        return matchesRegion && matchesKeywords;
    }

    /**
     * Возвращает путь к дефолтной картинке для банкротных торгов
     */
    public static String getDefaultImagePath() {
        return DEFAULT_IMAGE_PATH;
    }
}
