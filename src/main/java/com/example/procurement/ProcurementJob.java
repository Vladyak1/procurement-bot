package com.example.procurement;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.util.List;

@NoArgsConstructor
@Slf4j
@DisallowConcurrentExecution
public class ProcurementJob implements Job {

    @Override
    public void execute(JobExecutionContext context) {
        log.info("Starting procurement parsing job (all sources)");
        
        ProcurementProcessingService processingService = AppContext.getProcessingService();
        if (processingService == null) {
            log.error("ProcurementProcessingService not initialized in AppContext; skipping job execution");
            return;
        }

        // Пауза, поставленная админом командой /stop. Хранится в БД, поэтому переживает
        // перезапуск контейнера — иначе после рестарта парсинг тихо возобновился бы.
        DatabaseManager db = AppContext.getDatabaseManager();
        if (db != null && "true".equals(db.getSetting(PAUSE_KEY, "false"))) {
            String since = db.getSetting(PAUSE_SINCE_KEY, "");
            log.warn("Парсинг на паузе (с {}) — прогон пропущен. Снять: /resume в чате админов", since);
            remindPausedOncePerDay(since);
            return;
        }

        long chatId = Config.getParseGroupId(); // Публикация в группу парсинга
        // Парсим все источники: Torgi.gov.ru + Сбербанк-АСТ.
        // notifyAdminOnNoMatch=true — неопределённые севастопольские лоты идут админам на ревью (кнопки ✅/❌)
        int published = processingService.parseAndPublishAllSources(Integer.MAX_VALUE, chatId, true);
        
        log.info("Job completed, published {} procurements from all sources", published);
    }

    public static final String PAUSE_KEY = "parsing_paused";
    public static final String PAUSE_SINCE_KEY = "parsing_paused_at";
    public static final String PAUSE_BY_KEY = "parsing_paused_by";

    private static volatile long lastPauseReminderMs = 0L;
    private static final long PAUSE_REMINDER_INTERVAL_MS = 24 * 60 * 60 * 1000L;

    /**
     * Раз в сутки напоминает админам, что парсинг стоит на паузе.
     * Без этого легко забыть выключенного бота и неделю гадать, почему нет лотов.
     */
    private void remindPausedOncePerDay(String since) {
        long now = System.currentTimeMillis();
        if (now - lastPauseReminderMs < PAUSE_REMINDER_INTERVAL_MS) {
            return;
        }
        lastPauseReminderMs = now;
        try {
            TelegramBot bot = AppContext.getBot();
            if (bot != null) {
                bot.sendMessageWithRetry(Config.getAdminGroupId(),
                        "⏸ Напоминание: парсинг на паузе"
                                + (since.isEmpty() ? "" : " с " + since.substring(0, Math.min(16, since.length())))
                                + ". Лоты не публикуются. Возобновить: /resume");
            }
        } catch (Exception e) {
            log.warn("Не удалось отправить напоминание о паузе: {}", e.getMessage());
        }
    }

    private static Scheduler scheduler;

    public static void scheduleJob() {
        try {
            log.info("Initializing procurement scheduler...");
            // Создаем планировщик с настройками для корректного завершения
            org.quartz.SchedulerFactory schedulerFactory = new StdSchedulerFactory();
            scheduler = schedulerFactory.getScheduler();
            
            // Настраиваем планировщик для использования демонических потоков
            scheduler.getContext().put("org.quartz.scheduler.jmx.export", "false");
            
            scheduler.start();
            log.info("Scheduler started successfully");

            JobDetail job = JobBuilder.newJob(ProcurementJob.class)
                    .withIdentity("procurementJob", "group1")
                    .build();

            // Два прогона по будням: утренний в 10:00 и вечерний в 17:30.
            // Разными триггерами, а не одним выражением: минуты у запусков отличаются,
            // и списком часов ("0 0 10,17") это не выражается — получилось бы 17:00.
            Trigger morningTrigger = TriggerBuilder.newTrigger()
                    .withIdentity("procurementTrigger", "group1")
                    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 10 ? * MON-FRI"))
                    .build();

            Trigger eveningTrigger = TriggerBuilder.newTrigger()
                    .withIdentity("procurementTriggerEvening", "group1")
                    .withSchedule(CronScheduleBuilder.cronSchedule("0 30 17 ? * MON-FRI"))
                    .forJob(job)
                    .build();

            scheduler.scheduleJob(job, morningTrigger);
            scheduler.scheduleJob(eveningTrigger);
            log.info("Запуски по расписанию: 10:00 и 17:30 (пн-пт, {})",
                    java.util.TimeZone.getDefault().getID());
            log.info("Scheduler started");
            
            // Добавляем shutdown hook для корректного завершения планировщика
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down scheduler...");
                shutdownScheduler();
            }));
            
        } catch (SchedulerException e) {
            log.error("Error scheduling job: {}", e.getMessage());
        }
    }

    public static void shutdownScheduler() {
        if (scheduler != null) {
            try {
                // Останавливаем планировщик немедленно, не ждем завершения задач
                scheduler.shutdown(false);
                log.info("Scheduler shutdown completed");
            } catch (SchedulerException e) {
                log.error("Error shutting down scheduler: {}", e.getMessage());
            } finally {
                scheduler = null;
            }
        }
    }
}
