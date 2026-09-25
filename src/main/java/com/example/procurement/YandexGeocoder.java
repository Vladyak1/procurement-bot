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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Координаты по АДРЕСУ лота через Яндекс Геокодер — последняя попытка перед тем, как
 * просить точку у админа.
 *
 * ЗАЧЕМ. Участки в садовых товариществах («ТСН СНТ "Гидростроитель", з/у 153») есть
 * в ФИАС, но без собственных координат: DaData отдаёт по ним qc_geo=4, то есть точку
 * центра Севастополя — ровно то, от чего мы уходим. В ЕГРН через НСПД такие участки
 * тоже достаются не всегда. При этом на Яндекс.Картах они разыскиваются по названию
 * товарищества и номеру участка, поэтому геокодер закрывает как раз эту дыру.
 *
 * ДВА ЗАПРОСА. Сначала геокодер по адресу как есть. Если он вернул приблизительную
 * точку (улица, посёлок, город), пробуем Геосаджест: он прощает расхождения в написании
 * («ТСН СНТ "Гидростроитель"» против «СТСН Гидростроитель») и отдаёт uri объекта,
 * по которому геокодер выдаёт уже точные координаты.
 *
 * БЕРЕЖЁМ ЛИМИТ. Ключ бесплатный, поэтому запрос уходит только для лота, который
 * действительно собираются публиковать и у которого не нашлось ни точки из ЕГРН, ни
 * достоверной точки из torgi. Отрицательные ответы запоминаются на время работы
 * процесса — за один прогон один и тот же адрес не спрашиваем дважды.
 */
@Slf4j
public class YandexGeocoder {

    private static final String GEOCODE_URL = "https://geocode-maps.yandex.ru/v1/";
    private static final String SUGGEST_URL = "https://suggest-maps.yandex.ru/v1/suggest";
    /**
     * Границы поиска — Севастополь с окрестностями (нижний левый ~ верхний правый угол).
     * Вместе с rspn=1 это обязательное условие: «СНТ Гидростроитель» есть в десятке
     * регионов, и без ограничения метка уехала бы, например, под Волгоград.
     */
    private static final String SEVASTOPOL_BBOX = "33.30,44.34~33.95,44.90";
    private static final double BBOX_MIN_LON = 33.30;
    private static final double BBOX_MAX_LON = 33.95;
    private static final double BBOX_MIN_LAT = 44.34;
    private static final double BBOX_MAX_LAT = 44.90;
    /**
     * Точность ответа геокодера: exact — попадание в объект, number — дом с номером.
     * Остальные (street, near, range, other) означают «примерно тут», а приблизительная
     * метка хуже вопроса админу: её никто не перепроверит.
     */
    private static final Set<String> ACCEPTED_PRECISION = Set.of("exact", "number");
    private static final int TIMEOUT_MS = 12000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Адреса, по которым Яндекс уже ответил «не знаю», — в пределах текущего процесса. */
    private static final Map<String, Boolean> UNKNOWN_ADDRESSES = new ConcurrentHashMap<>();

    @Getter
    @AllArgsConstructor
    public static class Point {
        private final double lat;
        private final double lon;
        /** Адрес, как его понял Яндекс, — для логов: по нему видно, во что попали. */
        private final String address;
    }

    private YandexGeocoder() {
    }

    /**
     * Ищет координаты по адресу лота.
     *
     * @return точка в WGS84 либо null, если ключа нет, адрес пуст, Яндекс не знает объект
     *         или нашёл его слишком приблизительно
     */
    public static Point resolveByAddress(String address) {
        String key = Config.getYandexGeocoderKey();
        if (key == null) {
            return null;
        }
        String query = normalizeAddress(address);
        if (query == null) {
            return null;
        }
        if (UNKNOWN_ADDRESSES.containsKey(query)) {
            log.debug("Яндекс: адрес «{}» уже не нашёлся в этом запуске, не переспрашиваем", query);
            return null;
        }

        Point direct = geocode(key, "geocode=" + encode(query) + "&rspn=1&bbox=" + encode(SEVASTOPOL_BBOX), query);
        if (direct != null) {
            return direct;
        }

        String uri = suggestUri(query);
        if (uri != null) {
            Point viaSuggest = geocode(key, "uri=" + encode(uri), query);
            if (viaSuggest != null) {
                return viaSuggest;
            }
        }

        UNKNOWN_ADDRESSES.put(query, Boolean.TRUE);
        return null;
    }

    /**
     * Приводит адрес лота к тому виду, в котором его ищут на карте: внутригородское
     * деление и слова-связки для поиска только помеха, а кавычки вокруг названия
     * товарищества геокодер понимает хуже, чем их отсутствие.
     */
    static String normalizeAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String s = address.trim()
                .replaceAll("(?i)вн\\.?\\s*тер\\.?\\s*г\\.?", " ")
                .replaceAll("(?i)\\b[А-Яа-яЁё\\-]+\\s+муниципальн\\w*\\s+округ\\w*", " ")
                .replaceAll("[«»\"]", " ")
                .replaceAll("(?i)\\bз/у\\b", "уч")
                .replaceAll("(?i)\\bземельный участок\\b", " ")
                .replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("(^[\\s,]+)|([\\s,.]+$)", "");
        if (s.isBlank()) {
            return null;
        }
        // Адрес без объекта — это снова центр города: спрашивать по нему нечего
        String bare = s.replaceAll("(?i)[^а-яёa-z]", "").toLowerCase();
        if (bare.equals("гсевастополь") || bare.equals("севастополь")) {
            return null;
        }
        return s;
    }

    /** Один запрос к геокодеру с уже готовой частью строки параметров. */
    private static Point geocode(String key, String params, String queryForLog) {
        String url = GEOCODE_URL + "?apikey=" + encode(key) + "&format=json&results=1&lang=ru_RU&" + params;
        String body = request(url, "геокодер");
        if (body == null) {
            return null;
        }
        try {
            JsonNode members = MAPPER.readTree(body)
                    .path("response").path("GeoObjectCollection").path("featureMember");
            if (!members.isArray() || members.isEmpty()) {
                log.info("Яндекс: по адресу «{}» ничего не найдено", queryForLog);
                return null;
            }
            JsonNode geoObject = members.get(0).path("GeoObject");
            JsonNode meta = geoObject.path("metaDataProperty").path("GeocoderMetaData");
            String precision = meta.path("precision").asText("");
            String kind = meta.path("kind").asText("");
            String foundAddress = meta.path("text").asText("");

            String pos = geoObject.path("Point").path("pos").asText("");
            String[] parts = pos.split("\\s+");
            if (parts.length != 2) {
                log.warn("Яндекс: неожиданный формат координат «{}» по адресу «{}»", pos, queryForLog);
                return null;
            }
            double lon = Double.parseDouble(parts[0]);
            double lat = Double.parseDouble(parts[1]);

            if (!ACCEPTED_PRECISION.contains(precision)) {
                log.info("Яндекс: «{}» → {} (точность {}, {}) — слишком приблизительно, не берём",
                        queryForLog, foundAddress, precision, kind);
                return null;
            }
            if (lat < BBOX_MIN_LAT || lat > BBOX_MAX_LAT || lon < BBOX_MIN_LON || lon > BBOX_MAX_LON) {
                log.warn("Яндекс: «{}» → {},{} ({}) — вне Севастополя, не берём",
                        queryForLog, lat, lon, foundAddress);
                return null;
            }
            log.info("Яндекс: «{}» → {},{} (точность {}, {}) — {}",
                    queryForLog, lat, lon, precision, kind, foundAddress);
            return new Point(lat, lon, foundAddress);
        } catch (Exception e) {
            log.warn("Яндекс: не удалось разобрать ответ геокодера по «{}»: {}", queryForLog, e.getMessage());
            return null;
        }
    }

    /**
     * Спрашивает Геосаджест и возвращает uri первого подходящего объекта.
     * Саджест ищет по обиходному написанию и потому находит то, что геокодер по строгому
     * адресу пропускает; сами координаты он не отдаёт — за ними идём в геокодер по uri.
     */
    private static String suggestUri(String query) {
        String key = Config.getYandexSuggestKey();
        if (key == null) {
            return null;
        }
        String url = SUGGEST_URL + "?apikey=" + encode(key) + "&text=" + encode(query)
                + "&lang=ru&results=5&types=geo&attrs=uri&bbox=" + encode(SEVASTOPOL_BBOX) + "&strict_bounds=1";
        String body = request(url, "саджест");
        if (body == null) {
            return null;
        }
        try {
            JsonNode results = MAPPER.readTree(body).path("results");
            for (JsonNode r : results) {
                String uri = r.path("uri").asText("");
                if (!uri.isBlank()) {
                    log.info("Яндекс-саджест: «{}» → {} ({})", query,
                            r.path("title").path("text").asText(""),
                            r.path("subtitle").path("text").asText(""));
                    return uri;
                }
            }
            log.info("Яндекс-саджест: по «{}» подсказок с объектом нет", query);
        } catch (Exception e) {
            log.warn("Яндекс-саджест: не удалось разобрать ответ по «{}»: {}", query, e.getMessage());
        }
        return null;
    }

    private static String request(String url, String what) {
        HttpURLConnection conn = null;
        try {
            // Только напрямую с адреса бот-сервера: он российский, а SOCKS-прокси в настройках
            // держится для Telegram и выводит запрос за пределы страны — Яндекс такие обращения
            // считает чужими и ключ за них наказывает
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                // 403 — ключ не активирован либо превышен лимит; в обоих случаях бот просто
                // спросит точку у админа, как делал до подключения геокодера
                log.warn("Яндекс-{}: HTTP {} — {}", what, code, readBody(conn, code));
                return null;
            }
            return readBody(conn, code);
        } catch (Exception e) {
            log.warn("Яндекс-{}: ошибка запроса: {}", what, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection conn, int code) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code == 200 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
