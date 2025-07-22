package com.example.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class Config {
    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final Properties properties = new Properties();
    private static final String CONFIG_FILE = "application.properties";

    static {
        try (InputStream is = Config.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is == null) {
                throw new IOException("Resource not found: " + CONFIG_FILE);
            }
            properties.load(is);
        } catch (IOException e) {
            logger.error("Error loading config: {}", e.getMessage());
        }
    }

    public static String getBotToken() {
        return properties.getProperty("bot.token");
    }

    public static String getChatId() {
        return properties.getProperty("bot.chatId");
    }

    public static String getAdminIds() {
        String adminIds = properties.getProperty("bot.adminIds");
        return adminIds != null ? adminIds : "";
    }

    public static void addAdminId(String newAdminId) {
        String currentAdminIds = getAdminIds();
        String updatedAdminIds = currentAdminIds.isEmpty() ? newAdminId : currentAdminIds + "," + newAdminId;
        properties.setProperty("bot.adminIds", updatedAdminIds);
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            properties.store(fos, "Updated admin IDs");
            logger.info("Updated bot.adminIds: {}", updatedAdminIds);
        } catch (IOException e) {
            logger.error("Error updating admin IDs: {}", e.getMessage());
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
            logger.info("Removed adminId: {}. Updated bot.adminIds: {}", adminIdToRemove, updatedAdminIds);
        } catch (IOException e) {
            logger.error("Error updating admin IDs: {}", e.getMessage());
        }
    }

    public static boolean getParserVerbose() {
        return Boolean.parseBoolean(properties.getProperty("parser.verbose", "false"));
    }

    public static long getAdminGroupId() {
        return Long.parseLong(properties.getProperty("bot.adminGroupId", "-4913799316"));
    }

    public static long getParseGroupId() {
        return Long.parseLong(properties.getProperty("bot.parseGroupId", "-1002775576766"));
    }

    public static String getRssUrl() {
        return properties.getProperty("parser.rssUrl", "https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=PUBLISHED,APPLICATIONS_SUBMISSION&byFirstVersion=true");
    }

    public static String getXhrUrl() {
        return properties.getProperty("parser.xhrUrl", "https://torgi.gov.ru/new/api/public/lotcards/");
    }

    public static String getDbUrl() {
        return properties.getProperty("db.url", "data/procurements.db");
    }
}
