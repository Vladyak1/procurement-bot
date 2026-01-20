package com.example.procurement;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.UtilityClass;

@UtilityClass
public class AppContext {
    @Getter
    private DatabaseManager databaseManager;
    @Getter
    private ParserService parserService;
    @Getter
    private ProcurementProcessingService processingService;
    @Getter @Setter
    private TelegramBot bot;

    public void init() {
        databaseManager = new DatabaseManager();
        parserService = new ParserService();
    }
    
    public void initProcessingService(TelegramBot bot) {
        processingService = new ProcurementProcessingService(databaseManager, parserService, bot);
    }
}
