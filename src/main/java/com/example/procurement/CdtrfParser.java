package com.example.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер банкротных торгов ЦДТРФ (новый сайт torgi.cdtrf.ru, JSON API).
 * Заменяет устаревший {@link BankrotCdtrfParser} — старый bankrot.cdtrf.ru (ASP.NET) умер,
 * редиректит на Nuxt SPA torgi.cdtrf.ru с публичным JSON API webapi.torgi.cdtrf.ru.
 *
 * Листинг: Trade/trades?RegionId=92 (title, price, status, categories, imageId).
 * Деталь:  Trade/public/{tradeId} — публично, содержит задаток (%), lotInfo (адрес/площадь/
 *          кадастр текстом), координаты, массив фото.
 */
@Slf4j
public class CdtrfParser {
    private static final String API = "https://webapi.torgi.cdtrf.ru/";
    // ВНИМАНИЕ: параметр региона у ЦДТРФ переименован в RegionIds (мн. ч.).
    // Старый RegionId=92 API молча ИГНОРИРУЕТ — отвечает 200 и отдаёт всю Россию
    // (проверено 31.08.2026: RegionId=92 и пустой RegionId дают идентичную выдачу,
    // всего в базе ~362 тыс. лотов). Из-за этого в выдачу лезли Екатеринбург,
    // Краснодарский край, Чечня, Иркутская область — 91 чужой лот ждал публикации.
    private static final String LIST_URL = API + "Trade/trades?PageSize=200&PageNum=1&RegionIds=92&Sort=";
    /** Код региона Севастополя в ЦДТРФ — страховка на случай нового переименования параметра. */
    private static final String SEVASTOPOL_REGION_ID = "92";
    private static final String DETAIL_URL = API + "Trade/public/"; // + tradeId
    private static final String IMAGE_URL = API + "LotImage/public?LotImageSize=Large&ImageId=%s&LotId=%s&TradeId=%s";
    private static final String LOT_PAGE = "https://torgi.cdtrf.ru/trades/"; // + tradeId
    private static final String DEFAULT_IMAGE_PATH = "default_bankrot_image.jpg";
    private static final int MAX_IMAGES = 4;
    private static final ZoneOffset MSK = ZoneOffset.ofHours(3);

    // Категории недвижимости (Catalog/LotCategory): квартира/дом/помещения/здания/участки
    private static final Set<String> REAL_ESTATE_CATEGORIES = Set.of("21", "22", "31", "32", "33", "34", "41");
    // Активные статусы (публикуем). В ЛИСТИНГЕ статус — это ТЕКСТ tradeStatusDescription
    // (поле-enum tradeStatus есть только в детали), поэтому фильтруем по описанию.
    private static final Set<String> ACTIVE_STATUS_DESCRIPTIONS = Set.of("Торги объявлены", "Прием заявок");

    // Завершённые статусы → наш lotStatus (для обновления карточки, как torgi CompletedLotsParser).
    private static final Map<String, String> COMPLETED_STATUS_MAP = Map.of(
            "Торги не состоялись", "FAILED",
            "Торги завершены", "SUCCEED",
            "Подписан договор", "SUCCEED",
            "Торги отменены", "CANCELED",
            "Торги приостановлены", "SUSPENDED");

    // Адрес: грабим весь текст после «по адресу», хвост «площадью…/кадастр…» отрезаем отдельно
    // (одной регуляркой нельзя — точки в абревиатурах «п.»/«г.»/«д.» рвут захват).
    private static final Pattern ADDR = Pattern.compile("по адресу[:\\s]+(.+)", Pattern.CASE_INSENSITIVE);
    // Второй формат lotInfo: адрес идёт ПОСЛЕ «кадастровый номер X)», без «по адресу»
    private static final Pattern ADDR_AFTER_CAD = Pattern.compile(
            "(?i)кадастров\\w*\\s+номер\\s*\\d{2}:\\d{2}:\\d{6,7}:\\d+\\)?\\s*,?\\s*(.+)");
    // Хвост «площадью…/кадастр…» отрезаем (для формата 1)
    private static final Pattern ADDR_TAIL = Pattern.compile("(?i)\\s*,?\\s*(площад|кадастр).*$");
    // Хвост-обременение (залог/находится/ограничение) отрезаем из адреса (для формата 2)
    private static final Pattern ADDR_TAIL2 = Pattern.compile("(?i)\\s*\\.?\\s*(находится|обременени|ограничени|залог).*$");
    private static final Pattern AREA = Pattern.compile(
            "площад[ьюи]{1,2}[:\\s]*([\\d.,]+)\\s*кв", Pattern.CASE_INSENSITIVE);
    private static final Pattern CADASTRAL = Pattern.compile("(\\d{2}:\\d{2}:\\d{6,7}:\\d+)");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");
    private static final Pattern ABS_RUB = Pattern.compile("([\\d][\\d ]{3,}[.,]?\\d*)\\s*(?:руб|₽)");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    // Кеш листинга: parse() и parseCompletedStatuses() в одном прогоне используют одну выдачу,
    // чтобы не дёргать Trade/trades дважды.
    private JsonNode cachedListingItems;

    /**
     * Парсит активные лоты недвижимости из Севастополя.
     *
     * @param maxCount максимум лотов для сборки (детали тянутся только для них)
     */
    public List<Procurement> parse(int maxCount) {
        List<Procurement> result = new ArrayList<>();
        try {
            JsonNode listing = getJson(LIST_URL);
            JsonNode items = listing.path("items");
            cachedListingItems = items; // переиспользуем в parseCompletedStatuses()
            if (!items.isArray()) {
                log.warn("ЦДТРФ: поле items не массив, ответ: {}", listing.toString().substring(0, Math.min(200, listing.toString().length())));
                return result;
            }
            log.info("ЦДТРФ: в листинге региона 92 — {} лотов", items.size());

            for (JsonNode it : items) {
                if (result.size() >= maxCount) {
                    break;
                }
                // Страховка по региону на нашей стороне: даже если параметр фильтра снова
                // переименуют, чужие регионы в канал не уйдут.
                String regionId = it.path("regionId").asText("");
                if (!regionId.isEmpty() && !SEVASTOPOL_REGION_ID.equals(regionId)) {
                    continue;
                }

                String categories = it.path("categories").asText("");
                if (!isRealEstate(categories)) {
                    continue;
                }
                String statusDesc = it.path("tradeStatusDescription").asText("");
                if (!ACTIVE_STATUS_DESCRIPTIONS.contains(statusDesc)) {
                    continue;
                }
                String tradeId = nodeText(it, "tradeId");
                String tradeLotId = nodeText(it, "tradeLotId");
                if (tradeId == null || tradeLotId == null) {
                    continue;
                }
                Procurement p = buildFromDetail(tradeId, tradeLotId, it);
                if (p != null) {
                    result.add(p);
                }
                Thread.sleep(300);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("ЦДТРФ: ошибка парсинга листинга: {}", e.getMessage(), e);
        }
        log.info("ЦДТРФ: собрано {} активных лотов недвижимости", result.size());
        return result;
    }

    /**
     * Возвращает карту {tradeLotId → наш lotStatus} для лотов в завершённых статусах —
     * для обновления уже опубликованных карточек (аналог torgi CompletedLotsParser).
     */
    public Map<String, String> parseCompletedStatuses() {
        Map<String, String> result = new HashMap<>();
        try {
            // Переиспользуем листинг из parse() (если был), иначе тянем заново
            JsonNode items = cachedListingItems != null ? cachedListingItems : getJson(LIST_URL).path("items");
            if (items.isArray()) {
                for (JsonNode it : items) {
                    String desc = it.path("tradeStatusDescription").asText("");
                    String mapped = COMPLETED_STATUS_MAP.get(desc);
                    if (mapped != null) {
                        String lotId = nodeText(it, "tradeLotId");
                        if (lotId != null) {
                            result.put(lotId, mapped);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("ЦДТРФ: ошибка парсинга завершённых статусов: {}", e.getMessage());
        }
        log.info("ЦДТРФ: завершённых лотов со статусом в ленте: {}", result.size());
        return result;
    }

    private boolean isRealEstate(String categories) {
        for (String c : categories.split(",")) {
            if (REAL_ESTATE_CATEGORIES.contains(c.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Тянет деталь Trade/public/{tradeId} и собирает Procurement.
     */
    private Procurement buildFromDetail(String tradeId, String tradeLotId, JsonNode listItem) {
        try {
            JsonNode d = getJson(DETAIL_URL + tradeId);
            JsonNode lot = d.path("lot");
            String lotInfo = stripHtml(lot.path("lotInfo").asText(""));
            String name = lot.path("name").asText(listItem.path("name").asText(""));
            String title = !lotInfo.isEmpty() ? lotInfo : name;

            Double price = parseRuNumber(nodeText(lot, "priceBegin"));

            // адрес: из lotInfo (два формата — «по адресу» или после кадастра), иначе структурный location
            String address = extractAddress(lotInfo);
            if (address == null || address.isEmpty()) {
                address = nodeText(lot, "location");
            }
            if (address == null || address.isEmpty()) {
                address = "г. Севастополь";
            }

            Double area = parseRuNumber(firstGroup(AREA, lotInfo));
            String cadastral = firstGroup(CADASTRAL, lotInfo);
            Double deposit = computeDeposit(d.path("requestDepositInfo").asText(""), price);

            // фото: массив lot.images → LotImage URL; если нет — заглушка (иначе атомарная публикация не выпустит)
            List<String> images = new ArrayList<>();
            JsonNode imgArr = lot.path("images");
            if (imgArr.isArray()) {
                int cnt = 0;
                for (JsonNode img : imgArr) {
                    if (cnt >= MAX_IMAGES) {
                        break;
                    }
                    String imgId = nodeText(img, "id");
                    if (imgId != null) {
                        images.add(String.format(IMAGE_URL, imgId, tradeLotId, tradeId));
                        cnt++;
                    }
                }
            }
            if (images.isEmpty()) {
                images.add(DEFAULT_IMAGE_PATH);
            }

            Procurement.ProcurementBuilder b = Procurement.builder()
                    .number(tradeLotId)
                    .title(title)
                    .link(LOT_PAGE + tradeId)
                    .address(address.trim())
                    .price(price)
                    .deposit(deposit)
                    .area(area)
                    .cadastralNumber(cadastral)
                    .deadline(formatDeadline(nodeText(d, "requestTimeEnd")))
                    .source("ЦДТРФ (банкрот)")
                    .lotStatus("ACTIVE")
                    .imageUrls(images);

            // координаты (часто пусты)
            double lat = lot.path("latitude").asDouble(0);
            double lon = lot.path("longitude").asDouble(0);
            if (lat != 0 && lon != 0) {
                b.lat(lat).lon(lon);
            }
            // Уточнение по ЕГРН делается перед публикацией (TelegramBot.resolveCadastralPoint),
            // чтобы не ходить в НСПД за лотами, которые так и не будут опубликованы
            JsonNode catIds = lot.path("categoryIDs");
            if (catIds.isArray() && catIds.size() > 0) {
                b.categoryCode(catIds.get(0).asText());
            }

            Procurement p = b.build();
            log.info("ЦДТРФ лот {}: {} | цена={} задаток={} кадастр={} фото={}",
                    tradeLotId, title.substring(0, Math.min(50, title.length())), price, deposit, cadastral, images.size());
            return p;
        } catch (Exception e) {
            log.warn("ЦДТРФ: ошибка детали лота tradeId={}: {}", tradeId, e.getMessage());
            return null;
        }
    }

    /**
     * Вычисляет сумму задатка: обычно «N% от начальной цены» → price*N/100; иначе абсолютная сумма.
     */
    private Double computeDeposit(String depositInfo, Double price) {
        if (depositInfo == null || depositInfo.isEmpty()) {
            return null;
        }
        Matcher pm = PERCENT.matcher(depositInfo);
        if (pm.find() && price != null) {
            double pct = Double.parseDouble(pm.group(1));
            return Math.round(price * pct / 100.0 * 100.0) / 100.0;
        }
        Matcher abs = ABS_RUB.matcher(depositInfo);
        if (abs.find()) {
            return parseRuNumber(abs.group(1));
        }
        return null;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .header("Origin", "https://torgi.cdtrf.ru")
                .header("Referer", "https://torgi.cdtrf.ru/")
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(resp.body());
    }

    private static String nodeText(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.isEmpty()) ? null : s;
    }

    /**
     * Приводит дату окончания ЦДТРФ («05.08.2026 15:00») к ISO с оффсетом («2026-08-05T15:00+03:00»),
     * то есть к тому же формату, в котором хранит torgi.
     *
     * ВАЖНО (регрессия, из-за которой лоты публиковались повторно): хранить надо именно ISO,
     * а НЕ «05-08-2026». cleanupExpiredRecords сравнивает substr(deadline,1,10) СТРОКОЙ с
     * date('now','-90 days'): при «05-08-2026» выходит "0" < "2026-.." → лот считается устаревшим,
     * удаляется, а следующий парсинг вставляет его заново как новый и публикует повторно.
     * В карточке dd-MM-yyyy рендерит уже sendProcurementMessage (через OffsetDateTime.parse).
     */
    private static String formatDeadline(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String s = raw.trim();
        try { // «05.08.2026 15:00»
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                    .atOffset(MSK).toString();
        } catch (Exception ignore) {
        }
        try { // «05.08.2026» (без времени)
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())), DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    .atStartOfDay().atOffset(MSK).toString();
        } catch (Exception ignore) {
        }
        return raw;
    }

    private static String stripHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    private static String firstGroup(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Извлекает адрес из lotInfo. Два формата:
     *  1) «…по адресу: АДРЕС, площадью…»  → грабим после «по адресу», отрезаем хвост площадь/кадастр;
     *  2) «…(кадастровый номер X), АДРЕС. Находится в залоге…» → грабим после кадастра, отрезаем хвост-обременение.
     */
    private static String extractAddress(String lotInfo) {
        if (lotInfo == null || lotInfo.isEmpty()) {
            return null;
        }
        String addr = firstGroup(ADDR, lotInfo);
        if (addr == null) {
            addr = firstGroup(ADDR_AFTER_CAD, lotInfo);
        }
        if (addr == null) {
            return null;
        }
        addr = ADDR_TAIL.matcher(addr).replaceAll("");
        addr = ADDR_TAIL2.matcher(addr).replaceAll("");
        return addr.replaceAll("[\\s,.]+$", "").replaceAll("^[\\s,.]+", "").trim();
    }

    /**
     * Парсит число в русском формате: «28 151 355,60» → 28151355.60.
     */
    private static Double parseRuNumber(String s) {
        if (s == null) {
            return null;
        }
        String clean = s.replaceAll("\\s", "").replace(",", ".");
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
