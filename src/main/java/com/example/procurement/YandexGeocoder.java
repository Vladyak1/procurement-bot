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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Координаты по АДРЕСУ лота через Яндекс Геокодер — последняя попытка перед тем, как
 * просить точку у админа.
 *
 * ЗАЧЕМ. Участки в садовых товариществах («ТСН СНТ "Гидростроитель", з/у 153») есть
 * в ФИАС, но без собственных координат: DaData отдаёт по ним qc_geo=4, то есть точку
 * центра Севастополя — ровно то, от чего мы уходим. В ЕГРН через НСПД такие участки
 * достаются не всегда. На Яндекс.Картах они при этом находятся, и точно: проверено на
 * лоте 23000051770000001051_2 — геокодер вернул ту же точку до последнего знака, что
 * админ до этого поставил руками.
 *
 * ЗАПРОС ПЕРЕСОБИРАЕМ. Адрес из извещения геокодер не понимает: на «г Севастополь,
 * ТСН СНТ Гидростроитель, уч 153» отвечает «Севастополь» (центр города). Мешают именно
 * аббревиатуры формы собственности — «Севастополь Гидростроитель 153» находится точно.
 * Поэтому из адреса вынимаются название и номер, а служебные слова отбрасываются.
 *
 * ОТВЕТ СВЕРЯЕМ. Яндекс охотно отвечает соседним объектом: по «Югрыба 70» (такого
 * участка на карте нет) возвращает «СТСН Югрыба, 72», а по «СНТ Севастопольская бухта
 * 15» — «СНТ Севастополь, 15». Поэтому мало высокой точности: название и номер в ответе
 * должны совпасть с тем, что было в адресе лота. Не совпали — отдаём лот админу.
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
     * Вместе с rspn=1 это обязательное условие: садовые товарищества с такими названиями
     * есть в десятке регионов, и без ограничения метка уехала бы, например, под Волгоград.
     */
    private static final String SEVASTOPOL_BBOX = "33.30,44.34~33.95,44.90";
    private static final double BBOX_MIN_LON = 33.30;
    private static final double BBOX_MAX_LON = 33.95;
    private static final double BBOX_MIN_LAT = 44.34;
    private static final double BBOX_MAX_LAT = 44.90;
    /**
     * Точность ответа: exact — попадание в объект, number — дом с номером. Остальные
     * (near, range, street, other) означают «примерно тут»: по «Югрыба 70» это как раз
     * near с чужим участком 72. Приблизительная метка хуже вопроса админу — её никто
     * не перепроверит.
     */
    private static final Set<String> ACCEPTED_PRECISION = Set.of("exact", "number");
    /** Служебные слова: в запросе мешают геокодеру, при сверке названий — только шум. */
    private static final Pattern SERVICE_WORDS = Pattern.compile(
            "(?iU)\\b(?:тсн|снт|стсн|днп|днт|гск|пк|ст|товарищество|садоводческое|садовое|"
            + "некоммерческое|потребительский|кооператив|территория|тер|улица|ул|проспект|просп|пр-кт|"
            + "переулок|пер|шоссе|ш|бульвар|б-р|набережная|наб|аллея|проезд|квартал|кв-л|город|гор|г)\\b\\.?");
    /** Номер участка или дома: последнее такое вхождение в адресе и есть номер объекта. */
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "(?iU)\\b(?:з/у|участок|уч|дом|д|№)\\s*\\.?\\s*([0-9]+(?:[/\\-][0-9]+)?[а-яё]?)\\b");
    /** Название в кавычках — самый надёжный признак имени товарищества. */
    private static final Pattern QUOTED_NAME = Pattern.compile("[«\"']([^«»\"']{2,60})[»\"']");
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

    /** Разобранный адрес лота: по чему ищем и с чем потом сверяем ответ. */
    private static class LotAddress {
        /** Строка запроса к Яндексу — без аббревиатур, которые сбивают поиск. */
        final String query;
        /** Название товарищества или улицы, как в извещении. */
        final String place;
        /** Номер участка или дома. */
        final String number;

        LotAddress(String query, String place, String number) {
            this.query = query;
            this.place = place;
            this.number = number;
        }
    }

    private YandexGeocoder() {
    }

    /**
     * Ищет координаты по адресу лота.
     *
     * @return точка в WGS84 либо null, если ключа нет, адрес пуст, Яндекс не знает объект,
     *         нашёл его приблизительно или вернул не тот объект
     */
    public static Point resolveByAddress(String address) {
        String key = Config.getYandexGeocoderKey();
        if (key == null) {
            return null;
        }
        LotAddress parsed = parseAddress(address);
        if (parsed == null) {
            return null;
        }
        if (UNKNOWN_ADDRESSES.containsKey(parsed.query)) {
            log.debug("Яндекс: «{}» уже не нашёлся в этом запуске, не переспрашиваем", parsed.query);
            return null;
        }

        Point direct = geocode(key, "geocode=" + encode(parsed.query)
                + "&rspn=1&bbox=" + encode(SEVASTOPOL_BBOX), parsed);
        if (direct != null) {
            return direct;
        }

        // Саджест прощает расхождения в написании и находит то, что геокодер по строке
        // пропускает; координат он не отдаёт — за ними возвращаемся в геокодер по uri
        String uri = suggestUri(parsed.query);
        if (uri != null) {
            Point viaSuggest = geocode(key, "uri=" + encode(uri), parsed);
            if (viaSuggest != null) {
                return viaSuggest;
            }
        }

        UNKNOWN_ADDRESSES.put(parsed.query, Boolean.TRUE);
        return null;
    }

    /**
     * Собирает поисковый запрос из адреса извещения: город, название объекта и номер.
     *
     * Аббревиатуры выбрасываются намеренно — проверено на живом ключе: «Севастополь СНТ
     * Гидростроитель 153» геокодер понимает как «Севастополь» (центр города), а
     * «Севастополь Гидростроитель 153» находит точно.
     */
    static LotAddress parseAddress(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String cleaned = address.trim()
                .replaceAll("(?iU)вн\\.?\\s*тер\\.?\\s*г\\.?", " ")
                .replaceAll("(?iU)\\b[А-Яа-яЁё\\-]+\\s+муниципальн\\w*\\s+округ\\w*", " ")
                .replaceAll("(?iU)\\b[А-Яа-яЁё\\-]+\\s+район\\b", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();

        String number = null;
        Matcher numberMatcher = NUMBER_PATTERN.matcher(cleaned);
        while (numberMatcher.find()) {
            number = numberMatcher.group(1);
        }

        String place = null;
        Matcher quoted = QUOTED_NAME.matcher(cleaned);
        if (quoted.find()) {
            place = quoted.group(1).trim();
        } else {
            // Без кавычек берём слова после аббревиатуры формы собственности или после «ул.»
            Matcher named = Pattern.compile(
                    "(?iU)\\b(?:тсн|снт|стсн|днп|днт|гск|пк|ул|улица|пер|переулок|пр-кт|проспект|ш|шоссе)\\b\\.?\\s+"
                    + "((?:[А-ЯЁ][А-Яа-яЁё\\-]+\\s*){1,3})").matcher(cleaned);
            if (named.find()) {
                place = named.group(1).trim();
            }
        }
        if (place != null) {
            place = SERVICE_WORDS.matcher(place).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
            if (place.isBlank()) {
                place = null;
            }
        }

        String city = cleaned.matches("(?is).*Севастопол.*") ? "Севастополь" : null;

        String query;
        if (place != null && number != null) {
            query = (city == null ? "" : city + " ") + place + " " + number;
        } else {
            // Адрес нестандартный — отдаём как есть, только без кавычек и служебных слов
            query = SERVICE_WORDS.matcher(cleaned.replaceAll("[«»\"']", " "))
                    .replaceAll(" ").replaceAll("\\s*,\\s*", ", ").replaceAll("\\s{2,}", " ")
                    .replaceAll("(^[\\s,]+)|([\\s,.]+$)", "");
        }
        if (query.isBlank()) {
            return null;
        }
        // Адрес без объекта — это снова центр города: искать по нему нечего
        if (normalizeForCompare(query).equals("севастополь")) {
            return null;
        }
        return new LotAddress(query, place, number);
    }

    /** Один запрос к геокодеру с уже готовой частью строки параметров. */
    private static Point geocode(String key, String params, LotAddress lot) {
        String url = GEOCODE_URL + "?apikey=" + encode(key) + "&format=json&results=1&lang=ru_RU&" + params;
        String body = request(url, "геокодер");
        if (body == null) {
            return null;
        }
        try {
            JsonNode members = MAPPER.readTree(body)
                    .path("response").path("GeoObjectCollection").path("featureMember");
            if (!members.isArray() || members.isEmpty()) {
                log.info("Яндекс: по «{}» ничего не найдено", lot.query);
                return null;
            }
            JsonNode geoObject = members.get(0).path("GeoObject");
            JsonNode meta = geoObject.path("metaDataProperty").path("GeocoderMetaData");
            String precision = meta.path("precision").asText("");
            String kind = meta.path("kind").asText("");
            String foundAddress = meta.path("text").asText("");

            String[] pos = geoObject.path("Point").path("pos").asText("").split("\\s+");
            if (pos.length != 2) {
                log.warn("Яндекс: неожиданный формат координат по «{}»", lot.query);
                return null;
            }
            double lon = Double.parseDouble(pos[0]);
            double lat = Double.parseDouble(pos[1]);

            if (!ACCEPTED_PRECISION.contains(precision)) {
                log.info("Яндекс: «{}» → {} (точность {}, {}) — слишком приблизительно, не берём",
                        lot.query, foundAddress, precision, kind);
                return null;
            }
            if (!sameObject(meta, lot, foundAddress)) {
                return null;
            }
            if (lat < BBOX_MIN_LAT || lat > BBOX_MAX_LAT || lon < BBOX_MIN_LON || lon > BBOX_MAX_LON) {
                log.warn("Яндекс: «{}» → {},{} ({}) — вне Севастополя, не берём",
                        lot.query, lat, lon, foundAddress);
                return null;
            }
            log.info("Яндекс: «{}» → {},{} (точность {}, {}) — {}",
                    lot.query, lat, lon, precision, kind, foundAddress);
            return new Point(lat, lon, foundAddress);
        } catch (Exception e) {
            log.warn("Яндекс: не удалось разобрать ответ геокодера по «{}»: {}", lot.query, e.getMessage());
            return null;
        }
    }

    /**
     * Тот ли объект нашёлся. Сверяем по компонентам адреса из ответа: номер дома и
     * название (у участка в товариществе Яндекс кладёт его в locality, у городского
     * адреса — в street).
     */
    private static boolean sameObject(JsonNode meta, LotAddress lot, String foundAddress) {
        JsonNode components = meta.path("Address").path("Components");
        String foundHouse = null;
        String foundLocality = null;
        String foundStreet = null;
        for (JsonNode c : components) {
            String ckind = c.path("kind").asText("");
            String name = c.path("name").asText("");
            if ("house".equals(ckind)) {
                foundHouse = name;
            } else if ("locality".equals(ckind)) {
                foundLocality = name;
            } else if ("street".equals(ckind)) {
                foundStreet = name;
            }
        }

        if (lot.number != null) {
            if (foundHouse == null || !normalizeForCompare(foundHouse).equals(normalizeForCompare(lot.number))) {
                log.info("Яндекс: по «{}» вернулся участок {} вместо {} ({}) — не берём",
                        lot.query, foundHouse, lot.number, foundAddress);
                return false;
            }
        }
        if (lot.place != null) {
            String expected = normalizeForCompare(lot.place);
            boolean matches = (foundLocality != null && normalizeForCompare(foundLocality).equals(expected))
                    || (foundStreet != null && normalizeForCompare(foundStreet).equals(expected));
            if (!matches) {
                log.info("Яндекс: по «{}» вернулся другой объект ({}) — не берём", lot.query, foundAddress);
                return false;
            }
        }
        return true;
    }

    /**
     * Приводит название к виду, пригодному для сравнения: без служебных слов, регистра,
     * знаков и «ё». Сравнение потом строгое — «Севастопольская бухта» не должна сойти
     * за «Севастополь», хотя одно начинается с другого.
     */
    static String normalizeForCompare(String value) {
        if (value == null) {
            return "";
        }
        String s = SERVICE_WORDS.matcher(value).replaceAll(" ");
        return s.toLowerCase().replace('ё', 'е').replaceAll("[^а-яa-z0-9]", "");
    }

    /**
     * Спрашивает Геосаджест и возвращает uri первого объекта-адреса.
     * Подсказки про населённые пункты и районы пропускаем: по ним геокодер вернёт
     * центр города, то есть ту же метку, от которой мы уходим.
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
            for (JsonNode r : MAPPER.readTree(body).path("results")) {
                boolean isHouse = false;
                for (JsonNode tag : r.path("tags")) {
                    if ("house".equals(tag.asText())) {
                        isHouse = true;
                        break;
                    }
                }
                String uri = r.path("uri").asText("");
                if (isHouse && !uri.isBlank()) {
                    log.info("Яндекс-саджест: «{}» → {}", query, r.path("title").path("text").asText(""));
                    return uri;
                }
            }
            log.info("Яндекс-саджест: по «{}» подсказок с конкретным объектом нет", query);
        } catch (Exception e) {
            log.warn("Яндекс-саджест: не удалось разобрать ответ по «{}»: {}", query, e.getMessage());
        }
        return null;
    }

    private static String request(String url, String what) {
        HttpURLConnection conn = null;
        try {
            // Только напрямую с адреса бот-сервера: он российский, а SOCKS-прокси в настройках
            // держится для Telegram и вывел бы запрос за пределы страны — Яндекс такие обращения
            // считает чужими и ключ за них наказывает
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection(java.net.Proxy.NO_PROXY);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                // 403 — ключ не активирован либо исчерпан лимит; в обоих случаях бот просто
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
