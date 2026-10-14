package db;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Handles all database access for the crawler project.
 * Supports BOTH MySQL (XAMPP / phpMyAdmin) and embedded SQLite:
 * 
 * 1. MySQL (Default for XAMPP):
 *    - Automatically creates the database 'crawler_db' if it doesn't exist yet.
 *    - Automatically creates all tables (pages, links, crawl_runs, crawl_log).
 *    - Configurable via 'db.properties' or environment variables.
 *    - Default: localhost:3306, user 'root', password '' (standard XAMPP).
 * 
 * 2. SQLite (Automatic Fallback):
 *    - If MySQL is offline or not installed, automatically falls back to SQLite
 *      so the crawler never crashes unexpectedly.
 */
public class DatabaseManager implements AutoCloseable {

    private static final Properties CONFIG = loadConfiguration();
    private static volatile Boolean mySqlAvailable = null;

    private final Connection connection;
    private final boolean isMySQL;

    public DatabaseManager() throws SQLException {
        this.connection = createConnection();
        this.isMySQL = checkIsMySQL(this.connection);
        initializeSchema();
    }

    /**
     * Creates and returns a new Connection according to the configured database
     * engine (MySQL with SQLite fallback).
     */
    public static Connection createConnection() throws SQLException {
        String dbType = getProp("db.type", "DB_TYPE", "mysql").trim().toLowerCase();

        if ("mysql".equals(dbType)) {
            // Attempt MySQL connection if not permanently known to be failing
            if (mySqlAvailable == null || mySqlAvailable) {
                try {
                    Connection conn = connectMySQL();
                    if (mySqlAvailable == null) {
                        System.out.println("[DatabaseManager] Successfully connected to MySQL (XAMPP / phpMyAdmin).");
                        mySqlAvailable = true;
                    }
                    return conn;
                } catch (Exception e) {
                    if (mySqlAvailable == null || mySqlAvailable) {
                        System.out.println("[DatabaseManager] Notice: Could not connect to MySQL at "
                                + getMySQLHost() + ":" + getMySQLPort() + " (" + e.getMessage() + ").");
                        System.out.println("[DatabaseManager] If using XAMPP, start MySQL in XAMPP Control Panel.");
                        System.out.println("[DatabaseManager] Temporarily falling back to local SQLite database.");
                        mySqlAvailable = false;
                    }
                }
            }
        }

        // SQLite fallback or explicit SQLite mode
        return connectSQLite();
    }

    private static Connection connectMySQL() throws SQLException {
        String host = getMySQLHost();
        int port = getMySQLPort();
        String dbName = getProp("mysql.database", "MYSQL_DB", "crawler_db").trim();
        String user = getProp("mysql.username", "MYSQL_USER", "root").trim();
        String password = getProp("mysql.password", "MYSQL_PASSWORD", "");

        // Step 1: Ensure the database exists by connecting to the MySQL server
        String rootUrl = "jdbc:mysql://" + host + ":" + port + "/?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC";
        try (Connection serverConn = DriverManager.getConnection(rootUrl, user, password);
             Statement stmt = serverConn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS " + dbName + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException ignored) {
            // If the user doesn't have server-level permissions, proceed to connect to the DB directly
        }

        // Step 2: Connect to the specific database
        String dbUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8";
        return DriverManager.getConnection(dbUrl, user, password);
    }

    private static Connection connectSQLite() throws SQLException {
        String configured = getProp("sqlite.path", "DB_PATH", "data/crawler.db");
        Path databasePath = Paths.get(configured);

        Path parent = databasePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create SQLite directory: " + parent, e);
            }
        }

        String url = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        return DriverManager.getConnection(url);
    }

    private static boolean checkIsMySQL(Connection conn) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            String name = meta.getDatabaseProductName().toLowerCase();
            return name.contains("mysql") || name.contains("mariadb");
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Initializes all required tables for either MySQL or SQLite.
     */
    private void initializeSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            if (isMySQL) {
                stmt.execute("CREATE TABLE IF NOT EXISTS pages ("
                        + "page_id VARCHAR(255) NOT NULL,"
                        + "title VARCHAR(500),"
                        + "excerpt TEXT,"
                        + "url TEXT,"
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (page_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

                stmt.execute("CREATE TABLE IF NOT EXISTS links ("
                        + "id INT AUTO_INCREMENT PRIMARY KEY,"
                        + "from_page VARCHAR(255) NOT NULL,"
                        + "to_page VARCHAR(255) NOT NULL,"
                        + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "INDEX idx_from_page (from_page),"
                        + "INDEX idx_to_page (to_page),"
                        + "UNIQUE KEY uq_link (from_page(191), to_page(191))"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

                stmt.execute("CREATE TABLE IF NOT EXISTS crawl_runs ("
                        + "run_id INT AUTO_INCREMENT PRIMARY KEY,"
                        + "mode VARCHAR(50),"
                        + "thread_count INT,"
                        + "total_time_ms INT,"
                        + "pages_visited INT,"
                        + "run_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

                stmt.execute("CREATE TABLE IF NOT EXISTS crawl_log ("
                        + "id INT AUTO_INCREMENT PRIMARY KEY,"
                        + "run_id INT NOT NULL,"
                        + "page_id VARCHAR(255) NOT NULL,"
                        + "thread_name VARCHAR(100),"
                        + "depth INT,"
                        + "elapsed_ms BIGINT,"
                        + "status_code INT,"
                        + "discovered_links MEDIUMTEXT,"
                        + "page_url TEXT,"
                        + "visited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                        + "INDEX idx_run_id (run_id),"
                        + "INDEX idx_page_id (page_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            } else {
                // SQLite Schema
                stmt.execute("CREATE TABLE IF NOT EXISTS pages ("
                        + "page_id VARCHAR(20) PRIMARY KEY,"
                        + "title VARCHAR(255),"
                        + "excerpt TEXT,"
                        + "url TEXT)");

                stmt.execute("CREATE TABLE IF NOT EXISTS links ("
                        + "from_page VARCHAR(20) NOT NULL,"
                        + "to_page VARCHAR(20) NOT NULL)");

                stmt.execute("CREATE TABLE IF NOT EXISTS crawl_runs ("
                        + "run_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "mode VARCHAR(20),"
                        + "thread_count INTEGER,"
                        + "total_time_ms INTEGER,"
                        + "pages_visited INTEGER,"
                        + "run_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

                stmt.execute("CREATE TABLE IF NOT EXISTS crawl_log ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "run_id INTEGER NOT NULL,"
                        + "page_id VARCHAR(20) NOT NULL,"
                        + "thread_name VARCHAR(50),"
                        + "depth INTEGER,"
                        + "elapsed_ms INTEGER,"
                        + "status_code INTEGER,"
                        + "discovered_links TEXT,"
                        + "page_url TEXT,"
                        + "visited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

                addColumnIfMissing("pages", "excerpt", "TEXT");
                addColumnIfMissing("pages", "url", "TEXT");
                addColumnIfMissing("crawl_log", "status_code", "INTEGER");
                addColumnIfMissing("crawl_log", "discovered_links", "TEXT");
                addColumnIfMissing("crawl_log", "page_url", "TEXT");
                addColumnIfMissing("crawl_log", "visited_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");

                try {
                    stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_links_unique ON links(from_page, to_page)");
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String columnDefinition) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            boolean exists = false;
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
            }
        } catch (SQLException ignored) {
        }
    }

    public synchronized void clearGraph() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM links");
            stmt.executeUpdate("DELETE FROM crawl_log");
            stmt.executeUpdate("DELETE FROM pages");
        }
    }

    public synchronized void insertPage(String pageId, String title) throws SQLException {
        insertPage(pageId, title, null);
    }

    public synchronized void insertPage(String pageId, String title, String excerpt) throws SQLException {
        String sql = isMySQL
                ? "INSERT IGNORE INTO pages (page_id, title, excerpt) VALUES (?, ?, ?)"
                : "INSERT OR IGNORE INTO pages (page_id, title, excerpt) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, pageId);
            stmt.setString(2, title);
            stmt.setString(3, excerpt);
            stmt.executeUpdate();
        }
    }

    public synchronized void insertLink(String fromPage, String toPage) throws SQLException {
        String sql = isMySQL
                ? "INSERT IGNORE INTO links (from_page, to_page) VALUES (?, ?)"
                : "INSERT OR IGNORE INTO links (from_page, to_page) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, fromPage);
            stmt.setString(2, toPage);
            stmt.executeUpdate();
        }
    }

    public synchronized void logVisit(int runId, String pageId, String threadName, int depth, long elapsedMs) throws SQLException {
        logVisit(runId, pageId, threadName, depth, elapsedMs, 200, null, pageId, null);
    }

    public synchronized void logVisit(int runId, String pageId, String threadName, int depth, long elapsedMs,
            int statusCode, String discoveredLinks, String pageUrl, String pageTitle) throws SQLException {
        String sql = "INSERT INTO crawl_log (run_id, page_id, thread_name, depth, elapsed_ms, status_code, discovered_links, page_url) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, runId);
            stmt.setString(2, pageId);
            stmt.setString(3, threadName);
            stmt.setInt(4, depth);
            stmt.setLong(5, elapsedMs);
            stmt.setInt(6, statusCode);
            stmt.setString(7, discoveredLinks);
            stmt.setString(8, pageUrl == null ? pageId : pageUrl);
            stmt.executeUpdate();
        }

        if (pageTitle != null && !pageTitle.trim().isEmpty()) {
            String updatePage = "UPDATE pages SET title = ?, url = ? WHERE page_id = ? AND (title IS NULL OR title = '')";
            try (PreparedStatement stmt = connection.prepareStatement(updatePage)) {
                stmt.setString(1, pageTitle);
                stmt.setString(2, pageUrl == null ? pageId : pageUrl);
                stmt.setString(3, pageId);
                stmt.executeUpdate();
            }
        }
    }

    public synchronized int startRun(String mode, int threadCount) throws SQLException {
        String sql = "INSERT INTO crawl_runs (mode, thread_count) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, mode);
            stmt.setInt(2, threadCount);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Failed to create crawl run - no id generated");
    }

    public synchronized void finishRun(int runId, int totalTimeMs, int pagesVisited) throws SQLException {
        String sql = "UPDATE crawl_runs SET total_time_ms = ?, pages_visited = ? WHERE run_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, totalTimeMs);
            stmt.setInt(2, pagesVisited);
            stmt.setInt(3, runId);
            stmt.executeUpdate();
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean isMySQL() {
        return isMySQL;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    // --- Configuration helpers ---

    private static String getMySQLHost() {
        return getProp("mysql.host", "MYSQL_HOST", "localhost").trim();
    }

    private static int getMySQLPort() {
        String p = getProp("mysql.port", "MYSQL_PORT", "3306").trim();
        try {
            return Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return 3306;
        }
    }

    private static String getProp(String propKey, String envKey, String defaultValue) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.trim().isEmpty()) {
            return envVal.trim();
        }
        if (CONFIG != null && CONFIG.containsKey(propKey)) {
            String val = CONFIG.getProperty(propKey);
            if (val != null && !val.trim().isEmpty()) {
                return val.trim();
            }
        }
        return defaultValue;
    }

    private static Properties loadConfiguration() {
        Properties props = new Properties();
        Path propFile = Paths.get("db.properties");
        if (Files.exists(propFile)) {
            try (InputStream in = new FileInputStream(propFile.toFile())) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
        return props;
    }

    public static void main(String[] args) {
        try (DatabaseManager db = new DatabaseManager()) {
            System.out.println("Active Database Engine: " + (db.isMySQL() ? "MySQL (XAMPP / phpMyAdmin)" : "SQLite"));
            db.insertPage("P1", "Home Test");
            db.insertLink("P1", "P2");
            int runId = db.startRun("single", 1);
            db.logVisit(runId, "P1", "main", 0, 10L, 200, "P2", "P1", "Home Test");
            db.finishRun(runId, 100, 1);
            System.out.println("Test run successfully recorded with run ID: " + runId);
        } catch (Exception e) {
            System.err.println("Database test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
