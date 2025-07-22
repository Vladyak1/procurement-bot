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
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            Statement stmt = conn.createStatement();
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
            log.info("Database tables initialized at {}", dbUrl);
        } catch (SQLException e) {
            log.error("Error initializing database at {}: {}", dbUrl, e.getMessage(), e);
        }
    }

    public Procurement getProcurementByNumber(String number) {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement stmt = conn.prepareStatement("SELECT * FROM procurements WHERE number = ?");
            stmt.setString(1, number);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Procurement p = new Procurement();
                p.setNumber(rs.getString("number"));
                p.setTitle(rs.getString("title"));
                p.setLink(rs.getString("link"));
                p.setLotType(rs.getString("lotType"));
                p.setAddress(rs.getString("address"));
                p.setPrice(rs.getDouble("price"));
                p.setMonthlyPrice(rs.getDouble("monthlyPrice"));
                p.setDeposit(rs.getDouble("deposit"));
                p.setContractTerm(rs.getString("contractTerm"));
                p.setDeadline(rs.getString("deadline"));
                p.setCadastralNumber(rs.getString("cadastralNumber"));
                p.setArea(rs.getDouble("area"));
                log.debug("Fetched procurement by number: {}", number);
                return p;
            }
        } catch (SQLException e) {
            log.error("Error fetching procurement by number {}: {}", number, e.getMessage(), e);
        }
        return null;
    }

    public List<Procurement> getNewProcurements(List<Procurement> procurements) {
        List<Procurement> newProcurements = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement stmt = conn.prepareStatement("SELECT isSent FROM procurements WHERE number = ?");
            for (Procurement p : procurements) {
                stmt.setString(1, p.getNumber());
                ResultSet rs = stmt.executeQuery();
                if (!rs.next() || rs.getInt("isSent") == 0) {
                    newProcurements.add(p);
                    log.debug("Found new or unsent procurement: {}", p.getNumber());
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
            PreparedStatement selectStmt = conn.prepareStatement("SELECT isSent FROM procurements WHERE number = ?");
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO procurements (number, title, link, lotType, address, price, monthlyPrice, deposit, contractTerm, deadline, cadastralNumber, area, isSent) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            for (Procurement p : procurements) {
                int isSent = 0;
                selectStmt.setString(1, p.getNumber());
                ResultSet rs = selectStmt.executeQuery();
                if (rs.next()) {
                    isSent = rs.getInt("isSent");
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
                stmt.setInt(13, isSent);
                stmt.executeUpdate();
                log.debug("Saved procurement: {} (isSent={})", p.getNumber(), isSent);
            }
            conn.commit();
            log.info("Saved {} procurements to database", procurements.size());
        } catch (SQLException e) {
            log.error("Error saving procurements: {}", e.getMessage(), e);
        }
    }

    public void saveMessageId(String procurementNumber, int messageId, long chatId) {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR IGNORE INTO message_mappings (procurementNumber, messageId, chatId) VALUES (?, ?, ?)");
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
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT procurementNumber FROM message_mappings WHERE messageId = ? AND chatId = ?");
            stmt.setInt(1, messageId);
            stmt.setLong(2, chatId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String procurementNumber = rs.getString("procurementNumber");
                log.debug("Found procurementNumber={} for messageId={} and chatId={}", procurementNumber, messageId, chatId);
                return procurementNumber;
            }
        } catch (SQLException e) {
            log.error("Error retrieving procurement number: {}", e.getMessage(), e);
        }
        return null;
    }

    public void markAsSent(String procurementNumber) {
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE procurements SET isSent = 1 WHERE number = ?");
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
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement stmt = conn.prepareStatement("SELECT lotId FROM no_match_lots WHERE lotId = ?");
            stmt.setString(1, lotId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            log.error("Error checking no_match_lots: {}", e.getMessage(), e);
        }
        return false;
    }

    public void markNoMatchSent(String lotId) {
        if (lotId == null) return;
        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO no_match_lots (lotId) VALUES (?)");
            stmt.setString(1, lotId);
            stmt.executeUpdate();
            log.info("Marked NO MATCH lot {} as sent", lotId);
        } catch (SQLException e) {
            log.error("Error marking NO MATCH as sent: {}", e.getMessage(), e);
        }
    }
}
