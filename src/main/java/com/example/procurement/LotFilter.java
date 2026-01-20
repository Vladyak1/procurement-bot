package com.example.procurement;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Общий фильтр для проверки лотов по ключевым словам
 */
@Slf4j
public class LotFilter {
    private final List<String> includeKeywords;
    private final List<String> excludeKeywords;

    public LotFilter() {
        this.includeKeywords = Config.getIncludeKeywords();
        this.excludeKeywords = Config.getExcludeKeywords();
        log.info("LotFilter initialized with {} include and {} exclude keywords", 
            includeKeywords.size(), excludeKeywords.size());
    }

    public LotFilter(List<String> includeKeywords, List<String> excludeKeywords) {
        this.includeKeywords = includeKeywords;
        this.excludeKeywords = excludeKeywords;
    }

    /**
     * Проверяет, подходит ли лот по ключевым словам
     * 
     * @param title заголовок лота
     * @param address адрес лота (опционально)
     * @param notifyAdminOnNoMatch отправлять ли уведомление админу если не подошло
     * @param lotId ID лота для уведомления
     * @param lotUrl URL лота для уведомления
     * @return true если лот подходит, false если нет
     */
    public boolean isRealEstateLot(String title, String address, boolean notifyAdminOnNoMatch, 
                                   String lotId, String lotUrl) {
        String titleLower = title != null ? title.toLowerCase() : "";
        String addressLower = address != null ? address.toLowerCase() : "";
        String combined = titleLower + " " + addressLower;

        // Проверяем exclude keywords
        for (String excludeWord : excludeKeywords) {
            if (combined.contains(excludeWord.toLowerCase())) {
                log.debug("Excluded by '{}': {}", excludeWord, title);
                return false;
            }
        }

        // Проверяем include keywords
        for (String includeWord : includeKeywords) {
            if (combined.contains(includeWord.toLowerCase())) {
                log.debug("Included by '{}': {}", includeWord, title);
                return true;
            }
        }

        // Если не подошло ни одно ключевое слово
        log.debug("No match for include/exclude in: {}", title);
        
        if (notifyAdminOnNoMatch) {
            notifyAdminAboutUnmatchedLot(title, lotId, lotUrl);
        }
        
        return false;
    }

    /**
     * Упрощенная версия для проверки только заголовка
     */
    public boolean isRealEstateLot(String title) {
        return isRealEstateLot(title, null, false, null, null);
    }

    /**
     * Версия с уведомлением админа
     */
    public boolean isRealEstateLot(String title, boolean notifyAdminOnNoMatch) {
        return isRealEstateLot(title, null, notifyAdminOnNoMatch, null, null);
    }

    /**
     * Отправляет уведомление админу о лоте, который не прошел фильтр
     */
    private void notifyAdminAboutUnmatchedLot(String title, String lotId, String lotUrl) {
        try {
            String lotKey = lotId != null ? lotId : Integer.toString(title.hashCode());
            DatabaseManager db = AppContext.getDatabaseManager();
            
            if (db.isNoMatchSent(lotKey)) {
                log.info("NO MATCH lot {} уже отправлялся, пропускаем отправку", lotKey);
                return;
            }

            StringBuilder msg = new StringBuilder();
            msg.append("❓ Не удалось определить пригодность лота\n");
            
            if (lotId != null && lotUrl != null) {
                msg.append("ID: ").append(lotId).append("\n");
                msg.append("Ссылка: ").append(lotUrl).append("\n");
            }
            
            msg.append("Текст: ").append(title);

            log.info("NO MATCH: отправка сообщения в админ-группу: {}", msg);
            TelegramBot bot = AppContext.getBot();
            
            if (bot != null) {
                bot.sendMessageWithRetry(Config.getAdminGroupId(), msg.toString());
            } else {
                log.warn("TelegramBot not available, skipping NO MATCH message");
            }
            
            db.markNoMatchSent(lotKey);
        } catch (Exception e) {
            log.warn("Failed to send NO MATCH lot to admin group: {}", e.getMessage());
        }
    }

    /**
     * Получить список включающих ключевых слов
     */
    public List<String> getIncludeKeywords() {
        return new ArrayList<>(includeKeywords);
    }

    /**
     * Получить список исключающих ключевых слов
     */
    public List<String> getExcludeKeywords() {
        return new ArrayList<>(excludeKeywords);
    }

    /**
     * Создает дефолтный фильтр из конфигурации
     */
    public static LotFilter createDefault() {
        return new LotFilter();
    }
}

