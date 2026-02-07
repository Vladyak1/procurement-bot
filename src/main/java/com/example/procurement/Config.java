package com.example.procurement;

import lombok.extern.slf4j.Slf4j;
import lombok.experimental.UtilityClass;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

@Slf4j
@UtilityClass
public class Config {
    private static final Properties properties = new Properties();
    private static final String CONFIG_FILE = "application.properties";

    static {
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is == null) {
                throw new IOException("Resource not found: " + CONFIG_FILE);
            }
            // Загружаем properties с поддержкой UTF-8
            java.io.InputStreamReader reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
            properties.load(reader);
        } catch (IOException e) {
            log.error("Error loading config: {}", e.getMessage());
        }
    }

    /**
     * Получает значение сначала из переменной окружения, затем из properties
     * @param envKey ключ переменной окружения
     * @param propertyKey ключ в properties файле
     * @param defaultValue значение по умолчанию
     * @return значение конфигурации
     */
    private static String getEnvOrProperty(String envKey, String propertyKey, String defaultValue) {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }
        return properties.getProperty(propertyKey, defaultValue);
    }

    public static String getBotToken() {
        return getEnvOrProperty("BOT_TOKEN", "bot.token", null);
    }

    public static String getChatId() {
        return properties.getProperty("bot.chatId");
    }

    public static String getAdminIds() {
        return getEnvOrProperty("ADMIN_IDS", "bot.adminIds", "");
    }

    public static void addAdminId(String newAdminId) {
        String currentAdminIds = getAdminIds();
        String updatedAdminIds = currentAdminIds.isEmpty() ? newAdminId : currentAdminIds + "," + newAdminId;
        properties.setProperty("bot.adminIds", updatedAdminIds);
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            properties.store(fos, "Updated admin IDs");
            log.info("Updated bot.adminIds: {}", updatedAdminIds);
        } catch (IOException e) {
            log.error("Error updating admin IDs: {}", e.getMessage());
        }
    }

    public static void removeAdminId(String adminIdToRemove) {
        String currentAdminIds = getAdminIds();
        List<String> adminList = new ArrayList<>(Arrays.asList(currentAdminIds.split(",")));
        adminList.removeIf(id -> id.equals(adminIdToRemove));
        String updatedAdminIds = String.join(",", adminList);
        properties.setProperty("bot.adminIds", updatedAdminIds);
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            properties.store(fos, "Updated admin IDs");
            log.info("Removed adminId: {}. Updated bot.adminIds: {}", adminIdToRemove, updatedAdminIds);
        } catch (IOException e) {
            log.error("Error updating admin IDs: {}", e.getMessage());
        }
    }

    public static boolean getParserVerbose() {
        return Boolean.parseBoolean(properties.getProperty("parser.verbose", "false"));
    }

    public static long getAdminGroupId() {
        return Long.parseLong(getEnvOrProperty("ADMIN_GROUP_ID", "bot.adminGroupId", "-4913799316"));
    }

    public static long getParseGroupId() {
        return Long.parseLong(getEnvOrProperty("PARSE_GROUP_ID", "bot.parseGroupId", "-1002775576766"));
    }

    public static String getRssUrl() {
        return getEnvOrProperty("RSS_URL", "parser.rssUrl", "https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=PUBLISHED,APPLICATIONS_SUBMISSION&byFirstVersion=true");
    }

    public static String getXhrUrl() {
        return getEnvOrProperty("XHR_URL", "parser.xhrUrl", "https://torgi.gov.ru/new/api/public/lotcards/");
    }

    public static String getDbUrl() {
        return getEnvOrProperty("DB_URL", "db.url", "data/procurements.db");
    }

    /**
     * Получает список источников парсинга из конфигурации
     * Формат: parser.sources=name1|url1;name2|url2;...
     */
    public static List<ParsingSource> getParsingSources() {
        String sourcesConfig = properties.getProperty("parser.sources");
        List<ParsingSource> sources = new ArrayList<>();
        
        // Если источники не сконфигурированы, используем RSS URL по умолчанию
        if (sourcesConfig == null || sourcesConfig.trim().isEmpty()) {
            sources.add(new ParsingSource("Torgi.gov.ru (Севастополь)", getRssUrl(), getXhrUrl()));
            return sources;
        }
        
        // Парсим источники из конфигурации
        String[] sourceEntries = sourcesConfig.split(";");
        for (String entry : sourceEntries) {
            String[] parts = entry.split("\\|");
            if (parts.length >= 2) {
                String name = parts[0].trim();
                String rssUrl = parts[1].trim();
                String xhrUrl = parts.length > 2 ? parts[2].trim() : getXhrUrl();
                sources.add(new ParsingSource(name, rssUrl, xhrUrl));
            }
        }
        
        // Если парсинг не удался, используем источник по умолчанию
        if (sources.isEmpty()) {
            sources.add(new ParsingSource("Torgi.gov.ru (Севастополь)", getRssUrl(), getXhrUrl()));
        }
        
        return sources;
    }

    /**
     * Получает список ключевых слов для включения лотов
     */
    public static List<String> getIncludeKeywords() {
        String includeStr = getEnvOrProperty("FILTER_INCLUDE_KEYWORDS", "filter.include.keywords",
            "нежилое,нежилые,нежилого,нежилых,помещение,помещения,помещений,здание,жилое,жилой,жилая,жилые,квартира,земельный,участок,имущественный,комплекс,недвижимого,недвижимое,дом,строение,комната");
        return parseKeywordsList(includeStr);
    }

    /**
     * Получает список ключевых слов для исключения лотов
     */
    public static List<String> getExcludeKeywords() {
        String excludeStr = getEnvOrProperty("FILTER_EXCLUDE_KEYWORDS", "filter.exclude.keywords",
            "автомобиль,камаз,маз,трактор,погрузчик,лом,судно,гидроцикл,транспорт,оборудование,станок");
        return parseKeywordsList(excludeStr);
    }

    /**
     * Получает список исключённых видов торгов (из поля "Вид торгов" в RSS)
     */
    public static List<String> getExcludedLotTypes() {
        String typesStr = getEnvOrProperty("FILTER_EXCLUDED_LOT_TYPES", "filter.excluded.lotTypes",
            "управление многоквартирными домами,отбор региональных операторов,обращение с тко,управляющих организаций");
        return parseKeywordsList(typesStr);
    }

    /**
     * Парсит строку с ключевыми словами, разделенными запятыми
     */
    private static List<String> parseKeywordsList(String keywordsStr) {
        if (keywordsStr == null || keywordsStr.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] keywords = keywordsStr.split(",");
        List<String> result = new ArrayList<>();
        for (String keyword : keywords) {
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
