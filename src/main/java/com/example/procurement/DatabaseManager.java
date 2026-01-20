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
                    "isSent INTEGER DEFAULT 0)");
            stmt.execute("CREATE TABLE IF NOT EXISTS message_mappings (" +
                    "procurementNumber TEXT, " +
                    "messageId INTEGER, " +
                    "chatId INTEGER, " +
                    "PRIMARY KEY (procurementNumber, messageId, chatId))");
            stmt.execute("CREATE TABLE IF NOT EXISTS no_match_lots (" +
                    "lotId TEXT PRIMARY KEY)");
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
                         "INSERT OR REPLACE INTO procurements (number, title, link, lotType, address, price, monthlyPrice, deposit, contractTerm, deadline, cadastralNumber, area, source, lotStatus, isSent) " +
                                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
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

    public void markAsSent(String procurementNumber) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE procurements SET isSent = 1 WHERE number = ?")) {
            stmt.setString(1, procurementNumber);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                log.info("Marked procurement {} as sent", procurementNumber);
            } else {
                log.warn("No procurement found to mark as sent: {}", procurementNumber);
            }
        } catch (SQLException e) {
            log.error("Error marking procurement as sent: {}", e.getMessage(), e);
        }
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
