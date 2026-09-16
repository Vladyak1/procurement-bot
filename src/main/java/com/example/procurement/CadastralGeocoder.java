package com.example.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Координаты объекта по кадастровому номеру через НСПД (nspd.gov.ru) —
 * преемник публичной кадастровой карты Росреестра (pkk.rosreestr.ru редиректит туда же).
 *
 * ЗАЧЕМ. Организаторы многолотовых извещений на torgi.gov.ru проставляют в поле point
 * ОДНУ точку на всё извещение — как правило собственный юридический адрес. Так все лоты
 * ДИЗО Севастополя приезжают с координатами центра города, хотя объекты разбросаны по всему
 * Севастополю. Кадастровый номер такой ошибки не допускает: точка приходит из ЕГРН.
 *
 * ДВА ИСТОЧНИКА. Основной — DaData (findById/address по кадастровому номеру): работает с любых
 * адресов, отдаёт координаты и адрес из ФИАС, бесплатный лимит 10 000 запросов в сутки.
 * Запасной — НСПД: точнее (геометрия из ЕГРН), но с 08.09.2026 адрес бот-сервера у него в бане,
 * а Java-клиента портал отбивает и в обычное время (см. request).
 *
 * НСПД отдаёт геометрию в EPSG:3857 (Web Mercator) — переводим в WGS84, который ждёт Яндекс.
 * Его сертификат выписан тем же Russian Trusted CA, что и у torgi.gov.ru, — он уже
 * импортирован в truststore образа (см. Dockerfile), отдельной настройки не требуется.
 */
@Slf4j
public class CadastralGeocoder {

    private static final String API_URL = "https://nspd.gov.ru/api/geoportal/v2/search/geoportal?query=";
    private static final String DADATA_URL = "https://suggestions.dadata.ru/suggestions/api/4_1/rs/findById/address";
    /**
     * Насколько точны координаты DaData: 0 — точные, 1 — ближайший дом, 2 — улица,
     * 3 — населённый пункт, 4 — город, 5 — не определены. Точку на карте имеет смысл ставить
     * до уровня улицы; «город» — это снова метка в центре Севастополя, от которой мы и уходим.
     */
    private static final int DADATA_MAX_QC_GEO = 2;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Кадастровый номер: округ:район:квартал:объект. */
    private static final Pattern CADASTRAL_PATTERN = Pattern.compile("\\d{2}:\\d{2}:\\d{6,7}:\\d+");
    /** Полуокружность Web Mercator в метрах — константа перевода EPSG:3857 ↔ EPSG:4326. */
    private static final double MERCATOR_HALF_WORLD = 20037508.34;
    private static final int TIMEOUT_MS = 15000;
    /** WAF НСПД отбивает 403 при частых обращениях, поэтому повтор всего один и после паузы. */
    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 8000;
    /**
     * Минимальный промежуток между обращениями. У НСПД два независимых ограничения:
     * рукопожатие клиента (Java-стек отбивается всегда — поэтому ходим через curl, см. request)
     * и частота. Второе ловится даже настоящим Chrome с жилого адреса: серия из восьми
     * запросов подряд уходит в 403, одиночный запрос после паузы проходит.
     */
    private static final long MIN_INTERVAL_MS = 20000;
    /** Сколько 403 подряд считаем сигналом «нас придержали» и делаем перерыв. */
    private static final int BLOCKS_BEFORE_PAUSE = 3;
    private static final long PAUSE_AFTER_BLOCKS_MS = 20 * 60 * 1000L;

    private static long lastRequestMs = 0L;
    private static int consecutiveBlocks = 0;
    private static long pausedUntilMs = 0L;

    @Getter
    @AllArgsConstructor
    public static class Point {
        private final double lat;
        private final double lon;
        /** Адрес из ЕГРН — точнее того, что пишет организатор торгов. Пока только для логов. */
        private final String address;
    }

    /**
     * Ищет координаты объекта по кадастровому номеру. Результат кэшируется в БД:
     * геометрия объекта в ЕГРН не меняется, дёргать НСПД каждый прогон незачем.
     *
     * @return точка в WGS84 либо null, если номер невалиден, объект не найден или сервис недоступен
     */
    public static Point resolve(String cadastralNumber) {
        String normalized = normalize(cadastralNumber);
        if (normalized == null) {
            return null;
        }

        DatabaseManager db = AppContext.getDatabaseManager();
        if (db != null) {
            DatabaseManager.CachedPoint cached = db.getCadastralPoint(normalized);
            if (cached != null) {
                // Отрицательный результат тоже кэшируем — иначе каждый прогон будет ломиться
                // за одним и тем же ненайденным объектом.
                return cached.isFound() ? new Point(cached.getLat(), cached.getLon(), cached.getAddress()) : null;
            }
        }

        // Сначала DaData: она отвечает стабильно и не привязана к адресу сервера.
        Point fromDadata = fetchFromDadata(normalized);
        if (fromDadata != null) {
            if (db != null) {
                db.saveCadastralPoint(normalized, fromDadata);
            }
            return fromDadata;
        }

        // Пережидаем перерыв целиком: пока WAF нас держит, каждый новый запрос только продлевает бан.
        // Лот не потеряется — координаты подтянутся на следующем прогоне.
        if (System.currentTimeMillis() < pausedUntilMs) {
            log.debug("НСПД: пропускаем {} — пауза после серии 403", normalized);
            return null;
        }

        FetchResult result = fetchFromNspd(normalized);
        // Кэшируем только ОТВЕТ сервиса. Сетевую ошибку или 403 от WAF записывать как
        // «объект не найден» нельзя: иначе одна неудача заморозит лот без координат на месяц.
        if (db != null && result.definitive) {
            db.saveCadastralPoint(normalized, result.point);
        }
        return result.point;
    }

    /**
     * Координаты по кадастровому номеру через DaData. В отличие от НСПД, отдаёт не геометрию
     * объекта из ЕГРН, а точку адреса из ФИАС — для метки на карте этого достаточно.
     *
     * @return точка либо null, если ключа нет, объект не найден или координаты слишком грубые
     */
    private static Point fetchFromDadata(String cadastralNumber) {
        String apiKey = Config.getDadataApiKey();
        if (apiKey == null) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(DADATA_URL).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Token " + apiKey);
            byte[] body = ("{\"query\": \"" + cadastralNumber + "\"}").getBytes(StandardCharsets.UTF_8);
            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("DaData: HTTP {} по кадастровому номеру {}", code, cadastralNumber);
                return null;
            }
            JsonNode suggestions;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                suggestions = MAPPER.readTree(reader).path("suggestions");
            }
            if (!suggestions.isArray() || suggestions.isEmpty()) {
                log.info("DaData: объект {} не найден", cadastralNumber);
                return null;
            }
            JsonNode data = suggestions.get(0).path("data");
            String latText = data.path("geo_lat").asText(null);
            String lonText = data.path("geo_lon").asText(null);
            if (latText == null || lonText == null) {
                log.info("DaData: у объекта {} нет координат", cadastralNumber);
                return null;
            }
            int qcGeo = data.path("qc_geo").asInt(9);
            if (qcGeo > DADATA_MAX_QC_GEO) {
                log.info("DaData: координаты по {} слишком приблизительные (qc_geo={}), не берём",
                        cadastralNumber, qcGeo);
                return null;
            }
            double lat = Double.parseDouble(latText);
            double lon = Double.parseDouble(lonText);
            String address = suggestions.get(0).path("value").asText(null);
            log.info("DaData: {} → {},{} (точность {}) — {}", cadastralNumber, lat, lon, qcGeo, address);
            return new Point(lat, lon, address);
        } catch (Exception e) {
            log.warn("DaData: ошибка запроса по {}: {}", cadastralNumber, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Выдерживает паузу между обращениями, чтобы не нарваться на ограничение по частоте. */
    private static synchronized void throttle() {
        long wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestMs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestMs = System.currentTimeMillis();
    }

    /** Ответ НСПД: definitive=true, если сервис ответил по существу (нашёл или уверенно не нашёл). */
    private record FetchResult(Point point, boolean definitive) {
        static final FetchResult FAILED = new FetchResult(null, false);
        static FetchResult of(Point point) {
            return new FetchResult(point, true);
        }
    }

    /**
     * Приводит номер к каноническому виду. Организаторы пишут кадастр по-разному:
     * с пробелами, через точку, с ведущими нулями в квартале.
     */
    private static String normalize(String cadastralNumber) {
        if (cadastralNumber == null || cadastralNumber.isBlank()) {
            return null;
        }
        String cleaned = cadastralNumber.trim().replace(" ", "").replace('.', ':');
        var matcher = CADASTRAL_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            log.debug("Некадастровый номер, пропускаем: {}", cadastralNumber);
            return null;
        }
        return matcher.group();
    }

    private static FetchResult fetchFromNspd(String cadastralNumber) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String body = request(cadastralNumber);
                if (body != null) {
                    return FetchResult.of(parse(body, cadastralNumber));
                }
            } catch (Exception e) {
                log.warn("НСПД: ошибка запроса по {} (попытка {}/{}): {}",
                        cadastralNumber, attempt, MAX_ATTEMPTS, e.getMessage());
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return FetchResult.FAILED;
                }
            }
        }
        return FetchResult.FAILED;
    }

    /**
     * Запрос к НСПД идёт через системный curl, а не через Java-клиент.
     *
     * Проверено 08.09.2026: с одного адреса, в одну секунду и с одинаковыми заголовками
     * curl получает 200, а HttpURLConnection и java.net.http.HttpClient — 403. Оба Java-клиента
     * ходят через JSSE, и WAF портала отличает его рукопожатие от привычного браузерного.
     * Поэтому здесь внешний процесс: curl использует OpenSSL, и запрос проходит.
     *
     * @return тело ответа либо null, если запрос не удался
     */
    private static String request(String cadastralNumber) throws Exception {
        throttle();
        String urlStr = API_URL + URLEncoder.encode(cadastralNumber, StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-s", "--max-time", String.valueOf(TIMEOUT_MS / 1000),
                "-w", "\n%{http_code}",
                "-H", "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "-H", "Accept: application/json, text/plain, */*",
                "-H", "Accept-Language: ru-RU,ru;q=0.9,en;q=0.8",
                "-H", "Referer: https://nspd.gov.ru/map",
                urlStr);
        // Поток ошибок отбрасываем на уровне ОС. Если его не читать и не перенаправить,
        // буфер рано или поздно переполнится, curl встанет в ожидании чтения, а наш поток
        // так и будет висеть на readLine — взаимная блокировка, из которой --max-time не спасает.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = pb.start();
        } catch (java.io.IOException e) {
            // curl в образе отсутствует — работаем как раньше, пусть и с меньшими шансами
            log.warn("НСПД: curl недоступен ({}), пробуем Java-клиентом", e.getMessage());
            return requestViaJava(cadastralNumber);
        }
        String output;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            output = sb.toString();
        }
        if (!process.waitFor(TIMEOUT_MS + 5000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            log.warn("НСПД: curl не завершился вовремя по {}", cadastralNumber);
            return null;
        }
        if (process.exitValue() != 0) {
            log.warn("НСПД: curl вернул код {} по {}", process.exitValue(), cadastralNumber);
            return null;
        }

        // Тело и HTTP-код разделены последним переводом строки (см. -w выше)
        int lastBreak = output.lastIndexOf('\n', output.length() - 2);
        String statusText = lastBreak >= 0 ? output.substring(lastBreak + 1).trim() : output.trim();
        String body = lastBreak >= 0 ? output.substring(0, lastBreak) : "";
        int code;
        try {
            code = Integer.parseInt(statusText);
        } catch (NumberFormatException e) {
            log.warn("НСПД: не удалось разобрать код ответа по {} ({})", cadastralNumber, statusText);
            return null;
        }
        if (code != 200) {
            if (code == 403 || code == 429) {
                if (++consecutiveBlocks >= BLOCKS_BEFORE_PAUSE) {
                    pausedUntilMs = System.currentTimeMillis() + PAUSE_AFTER_BLOCKS_MS;
                    consecutiveBlocks = 0;
                    log.warn("НСПД: {} отказов подряд — прекращаем обращения на {} минут",
                            BLOCKS_BEFORE_PAUSE, PAUSE_AFTER_BLOCKS_MS / 60000);
                }
            }
            log.warn("НСПД: HTTP {} по кадастровому номеру {}", code, cadastralNumber);
            return null;
        }
        consecutiveBlocks = 0;
        return body;
    }

    /** Резервный путь через Java-клиент — на случай, если curl в системе не окажется. */
    private static String requestViaJava(String cadastralNumber) throws Exception {
        String urlStr = API_URL + URLEncoder.encode(cadastralNumber, StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            conn.setRequestProperty("Referer", "https://nspd.gov.ru/map");

            int code = conn.getResponseCode();
            if (code != 200) {
                if (code == 403 || code == 429) {
                    if (++consecutiveBlocks >= BLOCKS_BEFORE_PAUSE) {
                        pausedUntilMs = System.currentTimeMillis() + PAUSE_AFTER_BLOCKS_MS;
                        consecutiveBlocks = 0;
                        log.warn("НСПД: {} отказов подряд — прекращаем обращения на {} минут",
                                BLOCKS_BEFORE_PAUSE, PAUSE_AFTER_BLOCKS_MS / 60000);
                    }
                }
                log.warn("НСПД: HTTP {} по кадастровому номеру {}", code, cadastralNumber);
                return null;
            }
            consecutiveBlocks = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static Point parse(String body, String cadastralNumber) throws Exception {
        JsonNode features = MAPPER.readTree(body).path("data").path("features");
        if (!features.isArray() || features.isEmpty()) {
            log.info("НСПД: объект {} не найден", cadastralNumber);
            return null;
        }

        // Поиск нестрогий — по «91:02:001003:8205» может вернуться и родительское здание.
        // Берём объект с точным совпадением номера, иначе первый из выдачи.
        JsonNode chosen = features.get(0);
        for (JsonNode feature : features) {
            String cad = feature.path("properties").path("options").path("cad_number").asText("");
            if (cadastralNumber.equals(cad)) {
                chosen = feature;
                break;
            }
        }

        JsonNode geometry = chosen.path("geometry");
        double[] xy = extractCenter(geometry.path("coordinates"));
        if (xy == null) {
            log.warn("НСПД: у объекта {} нет пригодной геометрии ({})", cadastralNumber, geometry.path("type").asText("?"));
            return null;
        }

        // Обычно EPSG:3857, но сервис волен вернуть и градусы — проверяем явно.
        String crs = geometry.path("crs").path("properties").path("name").asText("EPSG:3857");
        double lat;
        double lon;
        if (crs.endsWith("4326")) {
            lon = xy[0];
            lat = xy[1];
        } else {
            lon = xy[0] / MERCATOR_HALF_WORLD * 180.0;
            lat = Math.toDegrees(2 * Math.atan(Math.exp(Math.toRadians(xy[1] / MERCATOR_HALF_WORLD * 180.0))) - Math.PI / 2);
        }

        if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) {
            log.warn("НСПД: получены неправдоподобные координаты {},{} для {}", lat, lon, cadastralNumber);
            return null;
        }

        String address = chosen.path("properties").path("options").path("readable_address").asText(null);
        log.info("НСПД: {} → {},{} ({})", cadastralNumber, lat, lon, address);
        return new Point(lat, lon, address);
    }

    /**
     * Центр геометрии в исходной проекции. Point отдаётся как есть, у контуров
     * (Polygon/MultiPolygon) берём центр описанного прямоугольника — он устойчив
     * к неравномерной плотности вершин, в отличие от среднего арифметического.
     */
    private static double[] extractCenter(JsonNode coordinates) {
        List<double[]> points = new ArrayList<>();
        collectPoints(coordinates, points);
        if (points.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : points) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        return new double[]{(minX + maxX) / 2, (minY + maxY) / 2};
    }

    /** Рекурсивно вытаскивает пары координат из вложенных массивов GeoJSON любой глубины. */
    private static void collectPoints(JsonNode node, List<double[]> out) {
        if (!node.isArray() || node.isEmpty()) {
            return;
        }
        if (node.get(0).isNumber() && node.size() >= 2) {
            out.add(new double[]{node.get(0).asDouble(), node.get(1).asDouble()});
            return;
        }
        for (JsonNode child : node) {
            collectPoints(child, out);
        }
    }
}
