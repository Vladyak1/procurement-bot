package com.example.procurement;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DatabaseManager {
    private final String dbUrl;

    public DatabaseManager() {
        dbUrl = "jdbc:sqlite:" + Config.getDbUrl();
        log.info("Current working directory: {}", System.getProperty("user.dir"));
        createDbDirectoryIfNotExists();
        initializeDatabase();
    }

    private void createDbDirectoryIfNotExists() {
        String dbDirPath = Config.getDbUrl().substring(0, Config.getDbUrl().lastIndexOf('/'));
        File dbDir = new File(dbDirPath);
        if (!dbDir.exists()) {
            if (dbDir.mkdirs()) {
                log.info("Created database directory: {}", dbDirPath);
            } else {
                log.error("Failed to create database directory: {}", dbDirPath);
            }
        } else {
            log.info("Database directory already exists: {}", dbDirPath);
        }
    }

    private void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS procurements (" +
                    "number TEXT PRIMARY KEY, " +
                    "title TEXT, " +
                    "link TEXT, " +
                    "lotType TEXT, " +
                    "address TEXT, " +
                    "price REAL, " +
                    "monthlyPrice REAL, " +
                    "deposit REAL, " +
                    "contractTerm TEXT, " +
                    "deadline TEXT, " +
                    "cadastralNumber TEXT, " +
                    "area REAL, " +
                    "source TEXT, " +
                    "lotStatus TEXT DEFAULT 'ACTIVE', " +
                    "isSent INTEGER DEFAULT 0, " +
                    "contractTypeName TEXT, " +
                    "pricePeriod TEXT, " +
                    "biddTypeName TEXT, " +
                    "depositRecipientName TEXT, " +
                    "lat REAL, " +
                    "lon REAL, " +
                    "subjectRfCode TEXT, " +
                    "categoryCode TEXT, " +
                    "categoryName TEXT, " +
                    "pointSource TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS message_mappings (" +
                    "procurementNumber TEXT, " +
                    "messageId INTEGER, " +
                    "chatId INTEGER, " +
                    "PRIMARY KEY (procurementNumber, messageId, chatId))");
            stmt.execute("CREATE TABLE IF NOT EXISTS no_match_lots (" +
                    "lotId TEXT PRIMARY KEY)");
            // Отметки об отсеве лота фильтрами. НУЖНА ТОЛЬКО ДЛЯ ЛОГОВ И ДИАГНОСТИКИ:
            // решение фильтра переоценивается каждый прогон, лот здесь НЕ «сгорает».
            // Настройки бота, переживающие перезапуск контейнера (например, пауза парсинга).
            // Хранить в памяти нельзя: после рестарта пауза бы тихо снялась.
            stmt.execute("CREATE TABLE IF NOT EXISTS bot_settings (" +
                    "key TEXT PRIMARY KEY, value TEXT, updatedAt TEXT)");
            // История результативности источников — для обнаружения ТИХИХ поломок парсеров:
            // площадка меняет API/переименовывает параметр, отвечает 200, а данные уже не те.
            // Так было со Сбербанк-АСТ (.aspx → 404) и ЦДТРФ (RegionId → RegionIds, молча вся Россия).
            stmt.execute("CREATE TABLE IF NOT EXISTS source_health (" +
                    "source TEXT, ts TEXT, lotCount INTEGER)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_source_health ON source_health (source, ts)");
            stmt.execute("CREATE TABLE IF NOT EXISTS filtered_lots (" +
                    "number TEXT PRIMARY KEY, " +
                    "reason TEXT, " +
                    "lastSeen TEXT)");
            // Координаты объектов по кадастровому номеру из НСПД. Геометрия в ЕГРН не меняется,
            // поэтому кэшируем навсегда; отрицательный результат (found=0) — с TTL, см. cleanupExpiredRecords.
            stmt.execute("CREATE TABLE IF NOT EXISTS cadastral_cache (" +
                    "cadastralNumber TEXT PRIMARY KEY, " +
                    "lat REAL, " +
                    "lon REAL, " +
                    "address TEXT, " +
                    "found INTEGER DEFAULT 1, " +
                    "fetchedAt TEXT)");
            // Запросы к админам на ручную установку точки: бот не смог получить координаты
            // из ЕГРН и ждёт, что админ ответит на сообщение ссылкой на Яндекс.Карты.
            stmt.execute("CREATE TABLE IF NOT EXISTS pending_point_requests (" +
                    "lotNumber TEXT PRIMARY KEY, " +
                    "messageId INTEGER, " +
                    "chatId INTEGER, " +
                    "createdAt TEXT)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pending_point_msg " +
                    "ON pending_point_requests (chatId, messageId)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_number ON procurements (number)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_isSent ON procurements (isSent)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_title ON procurements (title)");

            // Migration: Add 'source' column if it doesn't exist (for backward compatibility)
            try {
                ResultSet rs = conn.getMetaData().getColumns(null, null, "procurements", "source");
                if (!rs.next()) {
                    log.info("Adding missing 'source' column to procurements table");
                    stmt.execute("ALTER TABLE procurements ADD COLUMN source TEXT");
                    log.info("Successfully added 'source' column");
                }
                rs.close();
            } catch (SQLException e) {
                log.warn("Migration for 'source' column failed (might already exist): {}", e.getMessage());
            }

            // Migration: Add 'lotStatus' column if it doesn't exist
            try {
                ResultSet rs = conn.getMetaData().getColumns(null, null, "procurements", "lotStatus");
                if (!rs.next()) {
                    log.info("Adding missing 'lotStatus' column to procurements table");
                    stmt.execute("ALTER TABLE procurements ADD COLUMN lotStatus TEXT DEFAULT 'ACTIVE'");
                    log.info("Successfully added 'lotStatus' column");
                }
                rs.close();
            } catch (SQLException e) {
                log.warn("Migration for 'lotStatus' column failed (might already exist): {}", e.getMessage());
            }

            // Migration: Add contract/price detail columns if they don't exist
            for (String[] colDef : new String[][]{
                    {"contractTypeName", "TEXT"},
                    {"pricePeriod", "TEXT"},
                    {"biddTypeName", "TEXT"},
                    {"depositRecipientName", "TEXT"},
                    {"lat", "REAL"},
                    {"lon", "REAL"},
                    {"subjectRfCode", "TEXT"},
                    {"categoryCode", "TEXT"},
                    {"categoryName", "TEXT"},
                    {"pointSource", "TEXT"}
            }) {
                try {
                    ResultSet rs = conn.getMetaData().getColumns(null, null, "procurements", colDef[0]);
                    if (!rs.next()) {
                        log.info("Adding missing '{}' column to procurements table", colDef[0]);
                        stmt.execute("ALTER TABLE procurements ADD COLUMN " + colDef[0] + " " + colDef[1]);
                    }
                    rs.close();
                } catch (SQLException e) {
                    log.warn("Migration for '{}' column failed: {}", colDef[0], e.getMessage());
                }
            }

            log.info("Database tables initialized at {}", dbUrl);
        } catch (SQLException e) {
            log.error("Error initializing database at {}: {}", dbUrl, e.getMessage(), e);
        }
    }

    public Procurement getProcurementByNumber(String number) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM procurements WHERE number = ?")) {
            stmt.setString(1, number);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Procurement p = Procurement.builder()
                            .number(rs.getString("number"))
                            .title(rs.getString("title"))
                            .link(rs.getString("link"))
                            .lotType(rs.getString("lotType"))
                            .address(rs.getString("address"))
                            .price(rs.getDouble("price"))
                            .monthlyPrice(rs.getDouble("monthlyPrice"))
                            .deposit(rs.getDouble("deposit"))
                            .contractTerm(rs.getString("contractTerm"))
                            .deadline(rs.getString("deadline"))
                            .cadastralNumber(rs.getString("cadastralNumber"))
                            .area(rs.getDouble("area"))
                            .source(rs.getString("source"))
                            .lotStatus(rs.getString("lotStatus"))
                            .contractTypeName(rs.getString("contractTypeName"))
                            .pricePeriod(rs.getString("pricePeriod"))
                            .biddTypeName(rs.getString("biddTypeName"))
                            .depositRecipientName(rs.getString("depositRecipientName"))
                            .lat((Double) rs.getObject("lat"))
                            .lon((Double) rs.getObject("lon"))
                            .subjectRfCode(rs.getString("subjectRfCode"))
                            .categoryCode(rs.getString("categoryCode"))
                            .categoryName(rs.getString("categoryName"))
                            .pointSource(rs.getString("pointSource"))
                            .build();
                    log.debug("Fetched procurement by number: {}", number);
                    return p;
                }
            }
        } catch (SQLException e) {
            log.error("Error fetching procurement by number {}: {}", number, e.getMessage(), e);
        }
        return null;
    }

    public List<Procurement> getNewProcurements(List<Procurement> procurements) {
        List<Procurement> newProcurements = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement("SELECT isSent FROM procurements WHERE number = ?")) {
            for (Procurement p : procurements) {
                stmt.setString(1, p.getNumber());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next() || rs.getInt("isSent") == 0) {
                        newProcurements.add(p);
                        log.debug("Found new or unsent procurement: {}", p.getNumber());
                    }
                }
            }
            log.info("Found {} new or unsent procurements", newProcurements.size());
        } catch (SQLException e) {
            log.error("Error checking new procurements: {}", e.getMessage(), e);
        }
        return newProcurements;
    }

    public void saveProcurements(List<Procurement> procurements) {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            conn.setAutoCommit(false);
            try (PreparedStatement selectStmt = conn.prepareStatement("SELECT isSent, lotStatus FROM procurements WHERE number = ?");
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT OR REPLACE INTO procurements (number, title, link, lotType, address, price, monthlyPrice, deposit, contractTerm, deadline, cadastralNumber, area, source, lotStatus, isSent, contractTypeName, pricePeriod, biddTypeName, depositRecipientName, lat, lon, subjectRfCode, categoryCode, categoryName, pointSource) " +
                                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            ) {
                for (Procurement p : procurements) {
                    int isSent = 0;
                    String existingLotStatus = null;
                    selectStmt.setString(1, p.getNumber());
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (rs.next()) {
                            isSent = rs.getInt("isSent");
                            existingLotStatus = rs.getString("lotStatus");
                        }
                    }
                    stmt.setString(1, p.getNumber());
                    stmt.setString(2, p.getTitle());
                    stmt.setString(3, p.getLink());
                    stmt.setString(4, p.getLotType());
                    stmt.setString(5, p.getAddress());
                    stmt.setObject(6, p.getPrice());
                    stmt.setObject(7, p.getMonthlyPrice());
                    stmt.setObject(8, p.getDeposit());
                    stmt.setString(9, p.getContractTerm());
                    stmt.setString(10, p.getDeadline());
                    stmt.setString(11, p.getCadastralNumber());
                    stmt.setObject(12, p.getArea());
                    stmt.setString(13, p.getSource());
                    // Используем новый статус, если он указан, иначе сохраняем существующий или ACTIVE
                    String lotStatusToSave = p.getLotStatus() != null ? p.getLotStatus() :
                                            (existingLotStatus != null ? existingLotStatus : "ACTIVE");
                    stmt.setString(14, lotStatusToSave);
                    stmt.setInt(15, isSent);
                    stmt.setString(16, p.getContractTypeName());
                    stmt.setString(17, p.getPricePeriod());
                    stmt.setString(18, p.getBiddTypeName());
                    stmt.setString(19, p.getDepositRecipientName());
                    stmt.setObject(20, p.getLat());
                    stmt.setObject(21, p.getLon());
                    stmt.setString(22, p.getSubjectRfCode());
                    stmt.setString(23, p.getCategoryCode());
                    stmt.setString(24, p.getCategoryName());
                    stmt.setString(25, p.getPointSource());
                    stmt.executeUpdate();
                    log.debug("Saved procurement: {} (isSent={}, lotStatus={})", p.getNumber(), isSent, lotStatusToSave);
                }
                conn.commit();
                log.info("Saved {} procurements to database", procurements.size());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Error saving procurements: {}", e.getMessage(), e);
        }
    }

    public void saveMessageId(String procurementNumber, int messageId, long chatId) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR IGNORE INTO message_mappings (procurementNumber, messageId, chatId) VALUES (?, ?, ?)")) {
            stmt.setString(1, procurementNumber);
            stmt.setInt(2, messageId);
            stmt.setLong(3, chatId);
            stmt.executeUpdate();
            log.info("Saved message mapping: procurementNumber={}, messageId={}, chatId={}", procurementNumber, messageId, chatId);
        } catch (SQLException e) {
            log.error("Error saving message mapping: {}", e.getMessage(), e);
        }
    }

    public String getProcurementNumberByMessageId(int messageId, long chatId) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT procurementNumber FROM message_mappings WHERE messageId = ? AND chatId = ?")) {
            stmt.setInt(1, messageId);
            stmt.setLong(2, chatId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String procurementNumber = rs.getString("procurementNumber");
                    log.debug("Found procurementNumber={} for messageId={} and chatId={}", procurementNumber, messageId, chatId);
                    return procurementNumber;
                }
            }
        } catch (SQLException e) {
            log.error("Error retrieving procurement number: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Фиксирует, что лот отсеян фильтром (регион / категория / описание / активный дубль).
     *
     * ВАЖНО: намеренно НЕ помечает лот как отправленный. Раньше фильтры звали markAsSent,
     * и лот выпадал из очереди НАВСЕГДА — если отказ был ошибочным или временным
     * (сбой обогащения → адрес остался «Российская Федерация»; либо дубль, который потом
     * завершился), лот не публиковался уже никогда. Так были потеряны 23000001720000000019_1/_2.
     * Теперь решение переоценивается на каждом прогоне.
     *
     * @return true, если причина зафиксирована впервые или сменилась — тогда пишем WARN;
     *         false для повтора — достаточно DEBUG, иначе лог засоряется каждый прогон
     */
    public boolean recordFiltered(String procurementNumber, String reason) {
        if (procurementNumber == null) {
            return false;
        }
        boolean firstTime = true;
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT reason FROM filtered_lots WHERE number = ?")) {
                sel.setString(1, procurementNumber);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        String prev = rs.getString("reason");
                        firstTime = prev == null || !prev.equals(reason);
                    }
                }
            }
            try (PreparedStatement upd = conn.prepareStatement(
                    "INSERT OR REPLACE INTO filtered_lots (number, reason, lastSeen) VALUES (?, ?, ?)")) {
                upd.setString(1, procurementNumber);
                upd.setString(2, reason);
                upd.setString(3, java.time.OffsetDateTime.now().toString());
                upd.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Error recording filtered lot {}: {}", procurementNumber, e.getMessage());
        }
        return firstTime;
    }

    /** Всего лотов в базе — для команды /status. */
    public int getTotalProcurementsCount() {
        return countBy("SELECT COUNT(*) FROM procurements");
    }

    /** Лоты, ожидающие публикации (isSent = 0) — для команды /status. */
    public int getUnsentProcurementsCount() {
        return countBy("SELECT COUNT(*) FROM procurements WHERE isSent = 0");
    }

    private int countBy(String sql) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("Error counting procurements: {}", e.getMessage());
            return -1;
        }
    }

    /** Возвращает настройку бота или значение по умолчанию. */
    public String getSetting(String key, String defaultValue) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement("SELECT value FROM bot_settings WHERE key = ?")) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString("value");
                    return v != null ? v : defaultValue;
                }
            }
        } catch (SQLException e) {
            log.error("Error reading setting {}: {}", key, e.getMessage());
        }
        return defaultValue;
    }

    /** Сохраняет настройку бота. @return true, если запись прошла */
    public boolean setSetting(String key, String value) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO bot_settings (key, value, updatedAt) VALUES (?, ?, ?)")) {
            stmt.setString(1, key);
            stmt.setString(2, value);
            stmt.setString(3, java.time.OffsetDateTime.now().toString());
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Error saving setting {}: {}", key, e.getMessage());
            return false;
        }
    }

    /** Записывает, сколько лотов дал источник в этом прогоне; чистит историю старше 60 дней. */
    public void recordSourceRun(String source, int lotCount) {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO source_health (source, ts, lotCount) VALUES (?, ?, ?)")) {
                stmt.setString(1, source);
                stmt.setString(2, java.time.OffsetDateTime.now().toString());
                stmt.setInt(3, lotCount);
                stmt.executeUpdate();
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM source_health WHERE length(ts) >= 10 "
                        + "AND substr(ts, 1, 10) < date('now', '-60 days')");
            }
        } catch (SQLException e) {
            log.error("Error recording source health for {}: {}", source, e.getMessage());
        }
    }

    /**
     * Последние значения по источнику, СВЕЖИЕ ПЕРВЫМИ (включая только что записанное).
     */
    public List<Integer> getRecentSourceCounts(String source, int limit) {
        List<Integer> counts = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT lotCount FROM source_health WHERE source = ? ORDER BY ts DESC LIMIT ?")) {
            stmt.setString(1, source);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    counts.add(rs.getInt("lotCount"));
                }
            }
        } catch (SQLException e) {
            log.error("Error reading source health for {}: {}", source, e.getMessage());
        }
        return counts;
    }

    /** Снимает отметку об отсеве — лот прошёл фильтры (например, мешавший дубль завершился). */
    public void clearFiltered(String procurementNumber) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM filtered_lots WHERE number = ?")) {
            stmt.setString(1, procurementNumber);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Error clearing filtered lot {}: {}", procurementNumber, e.getMessage());
        }
    }

    /**
     * Помечает лот как отправленный.
     *
     * @return true, если отметка РЕАЛЬНО сохранилась. False означает, что запись не прошла
     *         (нет строки в БД, либо диск заполнен и SQLite не может писать) — вызывающий
     *         ОБЯЗАН прекратить публикацию, иначе лот уйдёт в чат ещё раз на каждом прогоне.
     *         Ровно так 26–31.08.2026 в канал улетели 17 лотов ЦДТРФ по кругу.
     */
    public boolean markAsSent(String procurementNumber) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE procurements SET isSent = 1 WHERE number = ?")) {
            stmt.setString(1, procurementNumber);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                log.info("Marked procurement {} as sent", procurementNumber);
                return true;
            }
            log.warn("No procurement found to mark as sent: {}", procurementNumber);
            return false;
        } catch (SQLException e) {
            log.error("Error marking procurement as sent: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Минимум свободного места, ниже которого работать нельзя (SQLite и логи перестают писаться). */
    private static final long MIN_FREE_BYTES = 200L * 1024 * 1024; // 200 МБ

    /**
     * Проверяет, есть ли на диске с базой достаточно места для записи.
     * Предполётная проверка: при заполненном диске бот обязан ОСТАНОВИТЬСЯ, а не публиковать
     * вслепую — отправка в Telegram диска не требует, а сохранение отметки требует.
     *
     * @return свободные байты, или -1 если определить не удалось (тогда не блокируем)
     */
    public long getFreeDiskSpace() {
        try {
            java.io.File dbFile = new java.io.File(Config.getDbUrl()).getAbsoluteFile();
            java.io.File dir = dbFile.getParentFile() != null ? dbFile.getParentFile() : dbFile;
            long usable = dir.getUsableSpace();
            return usable > 0 ? usable : -1;
        } catch (Exception e) {
            log.warn("Не удалось определить свободное место: {}", e.getMessage());
            return -1;
        }
    }

    /** true, если места достаточно (или определить не удалось — тогда не мешаем работе). */
    public boolean hasEnoughDiskSpace() {
        long free = getFreeDiskSpace();
        return free < 0 || free >= MIN_FREE_BYTES;
    }

    public boolean isNoMatchSent(String lotId) {
        if (lotId == null) return false;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement("SELECT lotId FROM no_match_lots WHERE lotId = ?")) {
            stmt.setString(1, lotId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Error checking no_match_lots: {}", e.getMessage(), e);
        }
        return false;
    }

    public void markNoMatchSent(String lotId) {
        if (lotId == null) return;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO no_match_lots (lotId) VALUES (?)")) {
            stmt.setString(1, lotId);
            stmt.executeUpdate();
            log.info("Marked NO MATCH lot {} as sent", lotId);
        } catch (SQLException e) {
            log.error("Error marking NO MATCH as sent: {}", e.getMessage(), e);
        }
    }

    /**
     * Проверяет, существует ли лот с похожим описанием (для дедупликации)
     * Использует нормализацию текста для сравнения
     */
    public boolean isDuplicateByDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            return false;
        }
        
        String normalizedDescription = normalizeDescription(description);
        
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement("SELECT title FROM procurements")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String existingTitle = rs.getString("title");
                    if (existingTitle != null) {
                        String normalizedExisting = normalizeDescription(existingTitle);
                        
                        // Проверяем на полное совпадение
                        if (normalizedExisting.equals(normalizedDescription)) {
                            log.debug("Found exact duplicate by description: {} vs {}", description, existingTitle);
                            return true;
                        }
                        
                        // Проверяем на высокую степень схожести (один текст содержится в другом)
                        if (normalizedExisting.contains(normalizedDescription) || normalizedDescription.contains(normalizedExisting)) {
                            // Проверяем, что разница в длине не слишком большая
                            double lengthRatio = (double) Math.min(normalizedExisting.length(), normalizedDescription.length()) 
                                               / Math.max(normalizedExisting.length(), normalizedDescription.length());
                            if (lengthRatio > 0.7) {
                                log.debug("Found similar duplicate by description (ratio: {}): {} vs {}", 
                                    lengthRatio, description, existingTitle);
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error checking duplicate by description: {}", e.getMessage(), e);
        }
        
        return false;
    }

    /**
     * Нормализует описание для сравнения:
     * - приводит к нижнему регистру
     * - удаляет лишние пробелы
     * - удаляет знаки препинания
     */
    private String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }

        return description.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[^а-яёa-z0-9\\s]", "")
                .trim();
    }

    /**
     * Проверяет, является ли кандидат АКТИВНЫМ дублем уже опубликованного лота
     * (кросс-источниковая дедупликация для доп. площадок против torgi).
     *
     * Дубль подавляется ТОЛЬКО если совпадающий лот ещё «живой»: статус ACTIVE (или NULL)
     * И дедлайн приёма заявок не прошёл. Если совпадающий лот завершён (терминальный статус)
     * ИЛИ его дедлайн прошёл — это законное повторное выставление (несостоявшиеся торги
     * выставили заново) → НЕ дубль, публикуем.
     *
     * Совпадение: по кадастровому номеру, иначе по похожести заголовка+адреса.
     */
    public boolean isActiveDuplicate(Procurement candidate) {
        if (candidate == null) {
            return false;
        }
        String candCad = candidate.getCadastralNumber();
        boolean hasCad = candCad != null && !candCad.trim().isEmpty();
        String candNorm = normalizeDescription(
                (candidate.getTitle() != null ? candidate.getTitle() : "") + " "
                        + (candidate.getAddress() != null ? candidate.getAddress() : ""));
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT title, address, cadastralNumber, lotStatus, deadline FROM procurements WHERE isSent = 1")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    boolean match;
                    String exCad = rs.getString("cadastralNumber");
                    boolean exHasCad = exCad != null && !exCad.trim().isEmpty();
                    if (hasCad && exHasCad) {
                        // У обоих есть кадастр — решает ТОЛЬКО он. Разные кадастры = разные объекты
                        // (защита от мульти-лотовых аукционов с похожими заголовками).
                        match = candCad.trim().equalsIgnoreCase(exCad.trim());
                    } else {
                        // Хотя бы у одного нет кадастра — сверяем по похожести заголовка+адреса.
                        String exNorm = normalizeDescription(
                                (rs.getString("title") != null ? rs.getString("title") : "") + " "
                                        + (rs.getString("address") != null ? rs.getString("address") : ""));
                        match = isSimilarDescription(candNorm, exNorm);
                    }
                    if (match && isLotLive(rs.getString("lotStatus"), rs.getString("deadline"))) {
                        log.info("Кандидат {} — активный дубль существующего лота, не публикуем",
                                candidate.getNumber());
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error checking active duplicate: {}", e.getMessage(), e);
        }
        return false;
    }

    /** Совпадение по нормализованному тексту: точное или взаимное вхождение с длиной >0.7. */
    private boolean isSimilarDescription(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        if (a.contains(b) || b.contains(a)) {
            double ratio = (double) Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
            return ratio > 0.7;
        }
        return false;
    }

    /** Лот «живой»: статус ACTIVE/NULL и дедлайн приёма заявок не прошёл. */
    private boolean isLotLive(String lotStatus, String deadline) {
        if (lotStatus != null && !lotStatus.isEmpty() && !"ACTIVE".equalsIgnoreCase(lotStatus)) {
            return false; // терминальный статус — раунд торгов завершён
        }
        return !isDeadlinePassed(deadline);
    }

    /** true, если дедлайн распарсился и он в прошлом. Пустой/непарсящийся → НЕ считаем прошедшим. */
    private boolean isDeadlinePassed(String deadline) {
        if (deadline == null || deadline.isEmpty()) {
            return false;
        }
        try { // ISO: 2026-06-22T13:00:00Z / +03:00
            return java.time.OffsetDateTime.parse(deadline).toInstant().isBefore(java.time.Instant.now());
        } catch (Exception ignore) {
        }
        try { // dd-MM-yyyy
            return java.time.LocalDate.parse(deadline,
                    java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")).isBefore(java.time.LocalDate.now());
        } catch (Exception ignore) {
        }
        return false;
    }

    /**
     * Получает все отправленные лоты со статусом ACTIVE
     * Используется для проверки изменений deadline и статуса
     *
     * @return Список активных отправленных лотов
     */
    public List<Procurement> getActiveSentProcurements() {
        List<Procurement> procurements = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM procurements WHERE isSent = 1 AND (lotStatus = 'ACTIVE' OR lotStatus IS NULL)")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Procurement p = Procurement.builder()
                            .number(rs.getString("number"))
                            .title(rs.getString("title"))
                            .link(rs.getString("link"))
                            .lotType(rs.getString("lotType"))
                            .address(rs.getString("address"))
                            .price(rs.getDouble("price"))
                            .monthlyPrice(rs.getDouble("monthlyPrice"))
                            .deposit(rs.getDouble("deposit"))
                            .contractTerm(rs.getString("contractTerm"))
                            .deadline(rs.getString("deadline"))
                            .cadastralNumber(rs.getString("cadastralNumber"))
                            .area(rs.getDouble("area"))
                            .source(rs.getString("source"))
                            .lotStatus(rs.getString("lotStatus"))
                            .contractTypeName(rs.getString("contractTypeName"))
                            .pricePeriod(rs.getString("pricePeriod"))
                            .biddTypeName(rs.getString("biddTypeName"))
                            .depositRecipientName(rs.getString("depositRecipientName"))
                            .lat((Double) rs.getObject("lat"))
                            .lon((Double) rs.getObject("lon"))
                            .subjectRfCode(rs.getString("subjectRfCode"))
                            .categoryCode(rs.getString("categoryCode"))
                            .categoryName(rs.getString("categoryName"))
                            .pointSource(rs.getString("pointSource"))
                            .build();
                    procurements.add(p);
                }
            }
            log.info("Fetched {} active sent procurements", procurements.size());
        } catch (SQLException e) {
            log.error("Error fetching active sent procurements: {}", e.getMessage(), e);
        }
        return procurements;
    }

    /**
     * Обновляет статус лота
     *
     * @param procurementNumber Номер лота
     * @param newStatus Новый статус
     * @return true если статус был обновлен
     */
    public boolean updateLotStatus(String procurementNumber, String newStatus) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE procurements SET lotStatus = ? WHERE number = ?")) {
            stmt.setString(1, newStatus);
            stmt.setString(2, procurementNumber);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                log.info("Updated lot status for {}: {}", procurementNumber, newStatus);
                return true;
            } else {
                log.warn("No procurement found to update status: {}", procurementNumber);
                return false;
            }
        } catch (SQLException e) {
            log.error("Error updating lot status: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Обновляет deadline лота
     *
     * @param procurementNumber Номер лота
     * @param newDeadline Новый deadline
     * @return true если deadline был обновлен
     */
    public boolean updateDeadline(String procurementNumber, String newDeadline) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE procurements SET deadline = ? WHERE number = ?")) {
            stmt.setString(1, newDeadline);
            stmt.setString(2, procurementNumber);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                log.info("Updated deadline for {}: {}", procurementNumber, newDeadline);
                return true;
            } else {
                log.warn("No procurement found to update deadline: {}", procurementNumber);
                return false;
            }
        } catch (SQLException e) {
            log.error("Error updating deadline: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Получает все сообщения (messageId и chatId), связанные с лотом
     *
     * @param procurementNumber Номер лота
     * @return Список пар [messageId, chatId]
     */
    public List<MessageMapping> getMessageMappings(String procurementNumber) {
        List<MessageMapping> mappings = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT messageId, chatId FROM message_mappings WHERE procurementNumber = ?")) {
            stmt.setString(1, procurementNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    mappings.add(new MessageMapping(
                            rs.getInt("messageId"),
                            rs.getLong("chatId")
                    ));
                }
            }
            log.debug("Found {} message mappings for procurement {}", mappings.size(), procurementNumber);
        } catch (SQLException e) {
            log.error("Error fetching message mappings: {}", e.getMessage(), e);
        }
        return mappings;
    }

    /**
     * Удаляет устаревшие записи: лоты с дедлайном старше 90 дней.
     * Каскадно удаляет связанные message_mappings и no_match_lots.
     *
     * @return количество удалённых лотов
     */
    public int cleanupExpiredRecords() {
        // substr(deadline,1,10) даёт YYYY-MM-DD из ISO-строки вида "2025-01-15T10:00:00+03:00"
        String condition = "deadline IS NOT NULL AND length(deadline) >= 10 " +
                           "AND substr(deadline, 1, 10) < date('now', '-90 days')";
        int deleted = 0;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            try {
                stmt.executeUpdate(
                    "DELETE FROM message_mappings WHERE procurementNumber IN " +
                    "(SELECT number FROM procurements WHERE " + condition + ")"
                );
                stmt.executeUpdate(
                    "DELETE FROM no_match_lots WHERE lotId IN " +
                    "(SELECT number FROM procurements WHERE " + condition + ")"
                );
                stmt.executeUpdate(
                    "DELETE FROM filtered_lots WHERE number IN " +
                    "(SELECT number FROM procurements WHERE " + condition + ")"
                );
                deleted = stmt.executeUpdate("DELETE FROM procurements WHERE " + condition);

                // Подчистка filtered_lots: она пополняется на КАЖДОМ прогоне, поэтому чистим её
                // по двум признакам, иначе таблица растёт бесконечно.
                // 1) Осиротевшие: лота уже нет в procurements (удалён здесь же или раньше).
                int orphans = stmt.executeUpdate(
                    "DELETE FROM filtered_lots WHERE number NOT IN (SELECT number FROM procurements)"
                );
                // 2) Протухшие: лот ещё в базе, но фильтр по нему давно не срабатывал (lastSeen
                //    обновляется при каждом отсеве, значит старая дата = запись неактуальна).
                //    Формат lastSeen гарантированно ISO — пишем его сами через OffsetDateTime,
                //    поэтому строковое сравнение substr(...,1,10) корректно (в отличие от deadline,
                //    где источники когда-то присылали dd-MM-yyyy и это давало ложное «устаревание»).
                int stale = stmt.executeUpdate(
                    "DELETE FROM filtered_lots WHERE lastSeen IS NOT NULL AND length(lastSeen) >= 10 " +
                    "AND substr(lastSeen, 1, 10) < date('now', '-90 days')"
                );
                // Лоты чужих регионов: опубликованы они не будут никогда, но пока лежат в базе
                // с isSent=0, каждый прогон попадают в выборку и проходят все проверки заново.
                // Новые сюда уже не попадают (отсев идёт до сохранения), это подчистка наследия.
                int foreign = stmt.executeUpdate(
                    "DELETE FROM procurements WHERE subjectRfCode IS NOT NULL " +
                    "AND subjectRfCode <> '' AND subjectRfCode <> '92' " +
                    "AND number NOT IN (SELECT procurementNumber FROM message_mappings)"
                );

                // Отрицательные записи кэша координат: объект мог быть не найден из-за временной
                // недоступности НСПД или ещё не попасть в ЕГРН — через 30 дней пробуем снова.
                // Найденные координаты не трогаем: геометрия объекта неизменна.
                int noCoords = stmt.executeUpdate(
                    "DELETE FROM cadastral_cache WHERE found = 0 AND fetchedAt IS NOT NULL " +
                    "AND length(fetchedAt) >= 10 AND substr(fetchedAt, 1, 10) < date('now', '-30 days')"
                );
                conn.commit();
                if (deleted > 0) {
                    log.info("Cleanup: removed {} expired records (deadline > 90 days ago)", deleted);
                }
                if (orphans > 0 || stale > 0) {
                    log.info("Cleanup filtered_lots: {} осиротевших, {} протухших", orphans, stale);
                }
                if (noCoords > 0) {
                    log.info("Cleanup cadastral_cache: {} ненайденных объектов сброшено для повторной попытки", noCoords);
                }
                if (foreign > 0) {
                    log.info("Cleanup: удалено {} лотов чужих регионов", foreign);
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.error("Error during cleanup of expired records: {}", e.getMessage(), e);
        }
        return deleted;
    }

    /**
     * Запись кэша координат по кадастровому номеру. found=false означает «в НСПД такого объекта нет» —
     * это тоже результат, и его надо помнить, иначе бот будет ходить за ним каждый прогон.
     */
    public static class CachedPoint {
        private final Double lat;
        private final Double lon;
        private final String address;
        private final boolean found;

        public CachedPoint(Double lat, Double lon, String address, boolean found) {
            this.lat = lat;
            this.lon = lon;
            this.address = address;
            this.found = found;
        }

        public Double getLat() { return lat; }
        public Double getLon() { return lon; }
        public String getAddress() { return address; }
        public boolean isFound() { return found; }
    }

    /**
     * Опубликованные лоты с проставленным завершённым статусом, у которых сохранена привязка
     * к сообщению. Нужны для разовой перепроверки: статусы, записанные до 07.09.2026, брались
     * из эха фильтра в RSS и поголовно были FAILED.
     */
    public List<String> getSentLotsWithFinalStatus() {
        List<String> numbers = new ArrayList<>();
        // Только torgi: сверка идёт по карточке lotcards, а у лотов ЦДТРФ номер — внутренний
        // id площадки, и такой запрос к torgi возвращает HTTP 400. Их статусы приходят
        // из собственного API ЦДТРФ и этим багом не затронуты.
        String sql = "SELECT DISTINCT p.number FROM procurements p " +
                     "JOIN message_mappings m ON m.procurementNumber = p.number " +
                     "WHERE p.lotStatus IS NOT NULL AND p.lotStatus <> 'ACTIVE' " +
                     "AND p.source LIKE 'Torgi%'";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                numbers.add(rs.getString("number"));
            }
        } catch (SQLException e) {
            log.error("Не удалось получить лоты с финальным статусом: {}", e.getMessage());
        }
        return numbers;
    }

    /** Запоминает, что по лоту у админов запрошена точка — ответ придёт reply на это сообщение. */
    public void savePendingPointRequest(String lotNumber, int messageId, long chatId) {
        String sql = "INSERT OR REPLACE INTO pending_point_requests " +
                "(lotNumber, messageId, chatId, createdAt) VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lotNumber);
            pstmt.setInt(2, messageId);
            pstmt.setLong(3, chatId);
            pstmt.setString(4, java.time.OffsetDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Не удалось сохранить запрос точки по лоту {}: {}", lotNumber, e.getMessage());
        }
    }

    /** @return номер лота, по которому админ ответил на сообщение, либо null */
    public String getPendingLotByMessage(long chatId, int messageId) {
        String sql = "SELECT lotNumber FROM pending_point_requests WHERE chatId = ? AND messageId = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            pstmt.setInt(2, messageId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("lotNumber");
                }
            }
        } catch (SQLException e) {
            log.warn("Не удалось найти запрос точки по сообщению {}: {}", messageId, e.getMessage());
        }
        return null;
    }

    public boolean hasPendingPointRequest(String lotNumber) {
        String sql = "SELECT 1 FROM pending_point_requests WHERE lotNumber = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, lotNumber);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void deletePendingPointRequest(String lotNumber) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(
                     "DELETE FROM pending_point_requests WHERE lotNumber = ?")) {
            pstmt.setString(1, lotNumber);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Не удалось удалить запрос точки по лоту {}: {}", lotNumber, e.getMessage());
        }
    }

    /** Обновляет координаты лота и пометку об их происхождении. */
    public void updateCoordinates(String number, Double lat, Double lon, String pointSource) {
        String sql = "UPDATE procurements SET lat = ?, lon = ?, pointSource = ? WHERE number = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, lat);
            pstmt.setObject(2, lon);
            pstmt.setString(3, pointSource);
            pstmt.setString(4, number);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Не удалось обновить координаты лота {}: {}", number, e.getMessage());
        }
    }

    /** @return запись кэша либо null, если по этому номеру ещё не ходили */
    public CachedPoint getCadastralPoint(String cadastralNumber) {
        String sql = "SELECT lat, lon, address, found FROM cadastral_cache WHERE cadastralNumber = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, cadastralNumber);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    boolean found = rs.getInt("found") == 1;
                    return new CachedPoint(
                            (Double) rs.getObject("lat"),
                            (Double) rs.getObject("lon"),
                            rs.getString("address"),
                            found);
                }
            }
        } catch (SQLException e) {
            log.warn("Не удалось прочитать кэш координат по {}: {}", cadastralNumber, e.getMessage());
        }
        return null;
    }

    /** Сохраняет результат обращения к НСПД, включая отрицательный (point == null). */
    public void saveCadastralPoint(String cadastralNumber, CadastralGeocoder.Point point) {
        String sql = "INSERT OR REPLACE INTO cadastral_cache " +
                "(cadastralNumber, lat, lon, address, found, fetchedAt) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, cadastralNumber);
            if (point != null) {
                pstmt.setDouble(2, point.getLat());
                pstmt.setDouble(3, point.getLon());
                pstmt.setString(4, point.getAddress());
            } else {
                pstmt.setNull(2, java.sql.Types.REAL);
                pstmt.setNull(3, java.sql.Types.REAL);
                pstmt.setNull(4, java.sql.Types.VARCHAR);
            }
            pstmt.setInt(5, point != null ? 1 : 0);
            pstmt.setString(6, java.time.OffsetDateTime.now().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            log.warn("Не удалось сохранить координаты по {}: {}", cadastralNumber, e.getMessage());
        }
    }

    /**
     * Точка «общая» — то есть у другого лота ровно те же координаты, но другой адрес.
     * Так выглядит точка организатора, проставленная одна на всё многолотовое извещение:
     * доверять ей нельзя, объекты в таком извещении разбросаны по всему городу.
     */
    public boolean isSharedPoint(Double lat, Double lon, String address) {
        if (lat == null || lon == null) {
            return false;
        }
        String sql = "SELECT 1 FROM procurements WHERE lat = ? AND lon = ? " +
                     "AND address IS NOT NULL AND address <> ? LIMIT 1";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setDouble(1, lat);
            pstmt.setDouble(2, lon);
            pstmt.setString(3, address == null ? "" : address);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("Не удалось проверить общность точки {},{}: {}", lat, lon, e.getMessage());
            return false;
        }
    }

    /**
     * Внутренний класс для представления связи сообщения с лотом
     */
    public static class MessageMapping {
        public final int messageId;
        public final long chatId;

        public MessageMapping(int messageId, long chatId) {
            this.messageId = messageId;
            this.chatId = chatId;
        }
    }
}
