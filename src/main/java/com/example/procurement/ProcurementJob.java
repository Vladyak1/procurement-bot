package com.example.procurement;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.util.List;

@NoArgsConstructor
@Slf4j
public class ProcurementJob implements Job {

    @Override
    public void execute(JobExecutionContext context) {
        log.info("Starting procurement parsing job");
        TelegramBot bot = new TelegramBot();
        DatabaseManager db = AppContext.getDatabaseManager();
        ParserService parserService = AppContext.getParserService();

        List<Procurement> procurements = parserService.parseAndEnrich(Integer.MAX_VALUE, false);

        List<Procurement> newProcurements = db.getNewProcurements(procurements);
        db.saveProcurements(newProcurements);

        long chatId = Config.getParseGroupId(); // Публикация в группу парсинга
        for (Procurement p : newProcurements) {
            bot.sendProcurementMessage(chatId, p);
            db.markAsSent(p.getNumber());
        }
        log.info("Job completed, processed {} procurements", newProcurements.size());
    }

    public static void scheduleJob() {
        try {
            Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
            scheduler.start();

            JobDetail job = JobBuilder.newJob(ProcurementJob.class)
                    .withIdentity("procurementJob", "group1")
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("procurementTrigger", "group1")
                    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 10,18 * * ?"))
                    .build();

            scheduler.scheduleJob(job, trigger);
            log.info("Scheduler started");
        } catch (SchedulerException e) {
            log.error("Error scheduling job: {}", e.getMessage());
        }
    }
}
