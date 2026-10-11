package db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Handles all database access for the crawler project. Uses SQLite by default
 * (file-based, no server setup needed).
 *
 * Setup: 1. Download the SQLite JDBC driver jar:
 * https://github.com/xerial/sqlite-jdbc (or via Maven: org.xerial:sqlite-jdbc)
 * 2. Put the jar on your classpath when compiling/running. 3. Run schema.sql
 * once against crawler.db before first use (e.g. using the `sqlite3` CLI:
 * sqlite3 crawler.db < schema.sql)
 *
 * Concurrency note: SQLite allows only one writer at a time. We handle this
 * with `synchronized` on the write methods so multiple crawler threads don't
 * corrupt the database - effectively a single-writer pattern, which is the
 * safest approach for a mini project.
 */
public class DatabaseManager implements AutoCloseable {

    private static final String DB_URL = resolveDatabaseUrl();
    private final Connection connection;

    private static String resolveDatabaseUrl() {
        String configured = System.getenv("DB_PATH");
        Path databasePath = (configured != null && !configured.trim().isEmpty())
                ? Paths.get(configured)
                : Paths.get("data", "crawler.db");

        Path parent = databasePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create SQLite directory: " + parent, e);
            }
        }

        return "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    public DatabaseManager() throws SQLException {
        this.connection = DriverManager.getConnection(DB_URL);
        initializeSchema();
    }

    /**
     * Creates all required tables if they don't already exist. This means you
     * never have to manually run schema.sql or use the sqlite3 CLI - just
     * running the Java program sets up the database automatically, every time,
     * safely.
     */
    private void initializeSchema() throws SQLException {
        String[] statements = {
            "CREATE TABLE IF NOT EXISTS pages ("
            + "page_id VARCHAR(20) PRIMARY KEY,"
            + "title VARCHAR(255),"
            + "excerpt TEXT)",
            "CREATE TABLE IF NOT EXISTS links ("
            + "from_page VARCHAR(20) NOT NULL,"
            + "to_page VARCHAR(20) NOT NULL,"
            + "FOREIGN KEY (from_page) REFERENCES pages(page_id),"
            + "FOREIGN KEY (to_page) REFERENCES pages(page_id))",
            "CREATE TABLE IF NOT EXISTS crawl_runs ("
            + "run_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "mode VARCHAR(20),"
            + "thread_count INTEGER,"
            + "total_time_ms INTEGER,"
            + "pages_visited INTEGER,"
            + "run_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE IF NOT EXISTS crawl_log ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "run_id INTEGER NOT NULL,"
            + "page_id VARCHAR(20) NOT NULL,"
            + "thread_name VARCHAR(50),"
            + "depth INTEGER,"
            + "elapsed_ms INTEGER,"
            + "status_code INTEGER,"
            + "discovered_links TEXT,"
            + "page_url TEXT,"
            + "visited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
            + "FOREIGN KEY (page_id) REFERENCES pages(page_id),"
            + "FOREIGN KEY (run_id) REFERENCES crawl_runs(run_id))"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }

        // Migration: for older crawler.db files, add any columns that were not in the
        // original schema; the project should keep running on existing databases without
        // forcing a manual reset.
        addColumnIfMissing("pages", "excerpt", "TEXT");
        addColumnIfMissing("pages", "url", "TEXT");
        addColumnIfMissing("crawl_log", "status_code", "INTEGER");
        addColumnIfMissing("crawl_log", "discovered_links", "TEXT");
        addColumnIfMissing("crawl_log", "page_url", "TEXT");
        addColumnIfMissing("crawl_log", "visited_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        // Ensure links are deduplicated with a unique index
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_links_unique ON links(from_page, to_page)");
        } catch (SQLException ignored) {
        }
    }

    private void addColumnIfMissing(String tableName, String columnName, String columnDefinition) {
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")");
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
            // Ignore migration failures if the DB is already in the expected shape.
        }
    }

    /**
     * Clears the stored graph (pages + links) before a new crawl persists its
     * own graph. Without this, every run's pages accumulate in the database, so
     * the report would mix the current graph with all previous ones. Crawl
     * history (crawl_runs / crawl_log) is intentionally kept.
     */
    public synchronized void clearGraph() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM links");
            stmt.executeUpdate("DELETE FROM crawl_log");
            stmt.executeUpdate("DELETE FROM pages");
        }
    }

    /**
     * Insert a page if it doesn't already exist.
     */
    public synchronized void insertPage(String pageId, String title) throws SQLException {
        insertPage(pageId, title, null);
    }

    /**
     * Insert a page with its extracted text excerpt (used for real scraped
     * pages).
     */
    public synchronized void insertPage(String pageId, String title, String excerpt) throws SQLException {
        String sql = "INSERT OR IGNORE INTO pages (page_id, title, excerpt) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, pageId);
            stmt.setString(2, title);
            stmt.setString(3, excerpt);
            stmt.executeUpdate();
        }
    }

    /**
     * Insert a link between two pages, ignoring duplicate links.
     */
    public synchronized void insertLink(String fromPage, String toPage) throws SQLException {
        String sql = "INSERT OR IGNORE INTO links (from_page, to_page) VALUES (?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, fromPage);
            stmt.setString(2, toPage);
            stmt.executeUpdate();
        }
    }

    /**
     * Record that a page was visited during a crawl run. Called by worker
     * threads.
     */
    public synchronized void logVisit(int runId, String pageId, String threadName, int depth, long elapsedMs) throws SQLException {
        logVisit(runId, pageId, threadName, depth, elapsedMs, 200, null, pageId, null);
    }

    /**
     * Record visit metadata for Excel export and report generation.
     */
    public synchronized void logVisit(int runId, String pageId, String threadName, int depth, long elapsedMs,
            int statusCode, String discoveredLinks, String pageUrl, String pageTitle) throws SQLException {
        String sql = "INSERT INTO crawl_log (run_id, page_id, thread_name, depth, elapsed_ms, status_code, discovered_links, page_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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

    /**
     * Create a new crawl run record and return its generated run_id.
     */
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

    /**
     * Update a run's final stats once the crawl finishes.
     */
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

    public void close() throws SQLException {
        connection.close();
    }

    /**
     * Quick manual test - proves the DB connection and basic writes work.
     */
    public static void main(String[] args) {
        try {
            DatabaseManager db = new DatabaseManager();
            db.insertPage("P1", "Home");
            db.insertPage("P2", "About");
            db.insertLink("P1", "P2");

            int runId = db.startRun("single", 1);
            db.logVisit(runId, "P1", "main", 0, 0);
            db.logVisit(runId, "P2", "main", 1, 15);
            db.finishRun(runId, 150, 2);

            System.out.println("Test run complete. Run id: " + runId);
            db.close();
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
