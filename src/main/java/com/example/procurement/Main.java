package com.example.procurement;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
public class Main {

    public static void main(String[] args) {
        AppContext.init();
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            TelegramBot bot = new TelegramBot();
            botsApi.registerBot(bot);
            log.info("Telegram bot registered successfully");

            // Запуск планировщика парсинга
            ProcurementJob.scheduleJob();
            log.info("Procurement scheduler started");
        } catch (TelegramApiException e) {
            log.error("Error registering Telegram bot: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error starting application: {}", e.getMessage());
        }
    }
}
