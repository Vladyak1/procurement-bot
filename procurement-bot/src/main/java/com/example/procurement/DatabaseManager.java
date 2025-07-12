package com.example.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DB_URL = "jdbc:sqlite:data/procurements.db";

    public DatabaseManager() {
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
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
            logger.info("Database tables initialized");
        } catch (SQLException e) {
            logger.error("Error initializing database: {}", e.getMessage(), e);
        }
    }

    public List<Procurement> getNewProcurements(List<Procurement> procurements) {
        List<Procurement> newProcurements = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            PreparedStatement stmt = conn.prepareStatement("SELECT isSent FROM procurements WHERE number = ?");
            for (Procurement p : procurements) {
                stmt.setString(1, p.getNumber());
                ResultSet rs = stmt.executeQuery();
                if (!rs.next() || rs.getInt("isSent") == 0) {
                    newProcurements.add(p);
                    logger.debug("Found new or unsent procurement: {}", p.getNumber());
                }
            }
            logger.info("Found {} new or unsent procurements", newProcurements.size());
        } catch (SQLException e) {
            logger.error("Error checking new procurements: {}", e.getMessage(), e);
        }
        return newProcurements;
    }

    public void saveProcurements(List<Procurement> procurements) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
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
                logger.debug("Saved procurement: {} (isSent={})", p.getNumber(), isSent);
            }
            conn.commit();
            logger.info("Saved {} procurements to database", procurements.size());
        } catch (SQLException e) {
            logger.error("Error saving procurements: {}", e.getMessage(), e);
        }
    }

    public void saveMessageId(String procurementNumber, int messageId, long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR IGNORE INTO message_mappings (procurementNumber, messageId, chatId) VALUES (?, ?, ?)");
            stmt.setString(1, procurementNumber);
            stmt.setInt(2, messageId);
            stmt.setLong(3, chatId);
            stmt.executeUpdate();
            logger.info("Saved message mapping: procurementNumber={}, messageId={}, chatId={}", procurementNumber, messageId, chatId);
        } catch (SQLException e) {
            logger.error("Error saving message mapping: {}", e.getMessage(), e);
        }
    }

    public String getProcurementNumberByMessageId(int messageId, long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT procurementNumber FROM message_mappings WHERE messageId = ? AND chatId = ?");
            stmt.setInt(1, messageId);
            stmt.setLong(2, chatId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String procurementNumber = rs.getString("procurementNumber");
                logger.debug("Found procurementNumber={} for messageId={} and chatId={}", procurementNumber, messageId, chatId);
                return procurementNumber;
            }
        } catch (SQLException e) {
            logger.error("Error retrieving procurement number: {}", e.getMessage(), e);
        }
        return null;
    }

    public void markAsSent(String procurementNumber) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE procurements SET isSent = 1 WHERE number = ?");
            stmt.setString(1, procurementNumber);
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                logger.info("Marked procurement {} as sent", procurementNumber);
            } else {
                logger.warn("No procurement found to mark as sent: {}", procurementNumber);
            }
        } catch (SQLException e) {
            logger.error("Error marking procurement as sent: {}", e.getMessage(), e);
        }
    }

    // --- NO MATCH ---
    public boolean isNoMatchSent(String lotId) {
        if (lotId == null) return false;
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            PreparedStatement stmt = conn.prepareStatement("SELECT lotId FROM no_match_lots WHERE lotId = ?");
            stmt.setString(1, lotId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            logger.error("Error checking no_match_lots: {}", e.getMessage(), e);
        }
        return false;
    }

    public void markNoMatchSent(String lotId) {
        if (lotId == null) return;
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO no_match_lots (lotId) VALUES (?)");
            stmt.setString(1, lotId);
            stmt.executeUpdate();
            logger.info("Marked NO MATCH lot {} as sent", lotId);
        } catch (SQLException e) {
            logger.error("Error marking NO MATCH as sent: {}", e.getMessage(), e);
        }
    }
}