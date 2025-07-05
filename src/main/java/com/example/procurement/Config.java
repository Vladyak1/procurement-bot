package com.example.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    public static String getParserUrl() {
        return properties.getProperty("parser.url");
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

    public static boolean getParserVerbose() {
        return Boolean.parseBoolean(properties.getProperty("parser.verbose", "false"));
    }
}