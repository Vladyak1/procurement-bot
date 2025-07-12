package com.example.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            TelegramBot bot = new TelegramBot();
            botsApi.registerBot(bot);
            logger.info("Telegram bot registered successfully");

            // Запуск планировщика парсинга
            ProcurementJob.scheduleJob();
            logger.info("Procurement scheduler started");
        } catch (TelegramApiException e) {
            logger.error("Error registering Telegram bot: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Error starting application: {}", e.getMessage());
        }
    }
}
