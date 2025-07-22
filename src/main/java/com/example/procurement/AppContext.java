package com.example.procurement;

public class AppContext {
    private static DatabaseManager databaseManager;
    private static ParserService parserService;

    public static void init() {
        databaseManager = new DatabaseManager();
        parserService = new ParserService();
    }

    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public static ParserService getParserService() {
        return parserService;
    }
}
