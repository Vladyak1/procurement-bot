package com.example.procurement;

import lombok.NoArgsConstructor;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@NoArgsConstructor
public class ProcurementJob implements Job {
    private static final Logger logger = LoggerFactory.getLogger(ProcurementJob.class);

    @Override
    public void execute(JobExecutionContext context) {
        logger.info("Starting procurement parsing job");
        TelegramBot bot = new TelegramBot();
        RssParser rssParser = new RssParser(); // Убрали аргумент bot
        LotPageParser lotParser = new LotPageParser();
        DatabaseManager db = new DatabaseManager();

        // Парсинг RSS
        List<Procurement> procurements = rssParser.parseUntilEnough(5);

        // Дополнение данных
        for (Procurement p : procurements) {
            lotParser.enrichProcurement(p);
        }

        // Проверка новых лотов
        List<Procurement> newProcurements = db.getNewProcurements(procurements);
        db.saveProcurements(newProcurements);

        // Отправка новых лотов в Telegram
        long chatId = Long.parseLong(Config.getChatId());
        for (Procurement p : newProcurements) {
            bot.sendProcurementMessage(chatId, p);
            db.markAsSent(p.getNumber());
        }
        logger.info("Job completed, processed {} procurements", newProcurements.size());
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
            logger.info("Scheduler started");
        } catch (SchedulerException e) {
            logger.error("Error scheduling job: {}", e.getMessage());
        }
    }
}