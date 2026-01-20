package com.example.procurement;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
public class Main {
    
    private static TelegramBotsApi botsApi;
    private static TelegramBot bot;
    private static BotSession botSession;

    public static void main(String[] args) {
        try {
            AppContext.init();
            
            // Проверяем, не зарегистрирован ли уже бот
            if (AppContext.getBot() != null) {
                log.warn("TelegramBot already exists in AppContext, skipping initialization");
                return;
            }
            
            log.info("Starting Telegram bot registration...");
            botsApi = new TelegramBotsApi(DefaultBotSession.class);
            bot = new TelegramBot();
            AppContext.setBot(bot);
            botSession = botsApi.registerBot(bot);
            log.info("Telegram bot registered successfully");

            // Инициализируем сервис обработки закупок
            AppContext.initProcessingService(bot);
            log.info("Procurement processing service initialized");

            // Запуск планировщика парсинга
            ProcurementJob.scheduleJob();
            log.info("Procurement scheduler started");
            
            // Добавляем shutdown hook для корректного завершения
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down application...");
                shutdownApplication();
            }));
            
            log.info("Application started successfully");
            
        } catch (TelegramApiException e) {
            log.error("Error registering Telegram bot: {}", e.getMessage());
            if (e.getMessage().contains("409")) {
                log.error("Bot conflict detected. This usually means another instance is running.");
                log.error("Please check for other running instances and stop them first.");
            }
            cleanup();
            System.exit(1);
        } catch (Exception e) {
            log.error("Error starting application: {}", e.getMessage());
            cleanup();
            System.exit(1);
        }
    }

    private static void shutdownApplication() {
        try {
            log.info("Shutting down procurement scheduler...");
            ProcurementJob.shutdownScheduler();
            
            if (botSession != null) {
                try {
                    log.info("Stopping Telegram bot session (long polling)...");
                    botSession.stop();
                } catch (Exception e) {
                    log.warn("Error stopping bot session: {}", e.getMessage());
                }
            }

            if (bot != null) {
                log.info("Shutting down Telegram bot...");
                bot.shutdown();
                
                // Даём время на корректное закрытие соединения с Telegram API
                log.info("Waiting for bot session to close...");
                Thread.sleep(2000);
                
                // Очищаем ссылку на бота
                AppContext.setBot(null);
            }
            
            // TelegramBotsApi не требует явного завершения
            
            log.info("Application shutdown completed");
        } catch (InterruptedException e) {
            log.warn("Shutdown interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error during shutdown: {}", e.getMessage());
        } finally {
            cleanup();
        }
    }

    private static void cleanup() {
        log.info("Cleanup completed");
    }
}
