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

        long chatId = Config.getParseGroupId(); // Публикация в группу парсинга
        // Парсим все источники: Torgi.gov.ru + Сбербанк-АСТ
        int published = processingService.parseAndPublishAllSources(Integer.MAX_VALUE, chatId, false);
        
        log.info("Job completed, published {} procurements from all sources", published);
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

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("procurementTrigger", "group1")
                    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 10,18 * * ?"))
                    .build();

            scheduler.scheduleJob(job, trigger);
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
