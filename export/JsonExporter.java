package export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Day 5: reads the crawl results out of the SQLite database and produces a
 * single, self-contained HTML report with the real data baked in.
 *
 * Why not export a separate JSON file? Browsers block fetch() of local files
 * opened directly (file://) for security reasons, so a JSON file would need a
 * local web server to actually load. Baking the data directly into the HTML
 * avoids that entirely - just double-click the output file and it works.
 *
 * How it works: 1. Reads report/report.html as a template. 2. Queries
 * pages+links, the latest single-threaded run, the latest multi-threaded run,
 * and that multi-threaded run's crawl log. 3. Builds the same JSON shape the
 * template expects. 4. Replaces the text between /*CRAWL_DATA_START*\/ and
 * /*CRAWL_DATA_END*\/ markers in the template with the real data. 5. Writes the
 * result to report/crawl-report.html.
 */
public class JsonExporter {

    private static final String DB_URL = resolveDatabaseUrl();
    private static final String TEMPLATE_PATH = "report/report.html";

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
    private static final String OUTPUT_PATH = "report/crawl-report.html";
    private static final String START_MARKER = "/*CRAWL_DATA_START*/";
    private static final String END_MARKER = "/*CRAWL_DATA_END*/";

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String dataJson = buildDataJson(conn);
            writeReport(dataJson);
            System.out.println("Report written to " + OUTPUT_PATH);
        }
    }

    /**
     * Builds the full data JSON (pages + both run logs + stats) from the
     * database. Public so the live server (server.CrawlServer) can reuse it to
     * serve the same data over HTTP instead of baking it into a file.
     */
    public static String buildDataJson(Connection conn) throws SQLException {
        String pagesJson = buildPagesJson(conn);

        int latestMultiRunId = getLatestRunId(conn, "multi");
        int latestSingleRunId = getLatestRunId(conn, "single");

        String singleLogJson = buildCrawlLogJson(conn, latestSingleRunId);
        String multiLogJson = buildCrawlLogJson(conn, latestMultiRunId);
        String statsJson = buildStatsJson(conn, latestSingleRunId, latestMultiRunId);

        return "{\n"
                + "  \"pages\": " + pagesJson + ",\n"
                + "  \"runs\": {\n"
                + "    \"single\": { \"crawlLog\": " + singleLogJson + " },\n"
                + "    \"multi\": { \"crawlLog\": " + multiLogJson + " }\n"
                + "  },\n"
                + "  \"stats\": " + statsJson + "\n"
                + "}";
    }

    /**
     * Builds the pages array: [{ "id": "P1", "links": ["P2","P3"] }, ...]
     */
    private static String buildPagesJson(Connection conn) throws SQLException {
        Map<String, StringBuilder> pageLinks = new LinkedHashMap<>();

        try (PreparedStatement stmt = conn.prepareStatement("SELECT page_id FROM pages"); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                pageLinks.put(rs.getString("page_id"), new StringBuilder());
            }
        }

        try (PreparedStatement stmt = conn.prepareStatement("SELECT from_page, to_page FROM links"); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String from = rs.getString("from_page");
                String to = rs.getString("to_page");
                StringBuilder links = pageLinks.get(from);
                if (links != null) {
                    if (links.length() > 0) {
                        links.append(",");
                    }
                    links.append("\"").append(escape(to)).append("\"");
                }
            }
        }

        StringBuilder json = new StringBuilder("[\n");
        boolean first = true;
        for (Map.Entry<String, StringBuilder> entry : pageLinks.entrySet()) {
            if (!first) {
                json.append(",\n");
            }
            first = false;
            json.append("    { \"id\": \"").append(escape(entry.getKey()))
                    .append("\", \"links\": [").append(entry.getValue()).append("] }");
        }
        json.append("\n  ]");
        return json.toString();
    }

    /**
     * Builds the crawlLog array for one specific run, ordered by visit
     * sequence.
     */
    private static String buildCrawlLogJson(Connection conn, int runId) throws SQLException {
        StringBuilder json = new StringBuilder("[\n");
        String sql = "SELECT page_id, thread_name, elapsed_ms, depth FROM crawl_log "
                + "WHERE run_id = ? ORDER BY id ASC";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, runId);
            try (ResultSet rs = stmt.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) {
                        json.append(",\n");
                    }
                    first = false;
                    json.append("    { \"page\": \"").append(escape(rs.getString("page_id")))
                            .append("\", \"thread\": \"").append(escape(rs.getString("thread_name")))
                            .append("\", \"timeMs\": ").append(rs.getLong("elapsed_ms"))
                            .append(", \"depth\": ").append(rs.getInt("depth"))
                            .append(" }");
                }
            }
        }
        json.append("\n  ]");
        return json.toString();
    }

    /**
     * Builds the stats object from the latest single and latest multi runs.
     */
    private static String buildStatsJson(Connection conn, int singleRunId, int multiRunId) throws SQLException {
        long singleMs = getRunField(conn, singleRunId, "total_time_ms");
        long multiMs = getRunField(conn, multiRunId, "total_time_ms");
        long threadCount = getRunField(conn, multiRunId, "thread_count");

        return "{\n"
                + "    \"singleThreadedMs\": " + singleMs + ",\n"
                + "    \"multiThreadedMs\": " + multiMs + ",\n"
                + "    \"threadsUsed\": " + threadCount + "\n"
                + "  }";
    }

    private static long getRunField(Connection conn, int runId, String column) throws SQLException {
        String sql = "SELECT " + column + " FROM crawl_runs WHERE run_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, runId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(column);
                }
            }
        }
        return 0;
    }

    /**
     * Finds the most recent run_id for a given mode ("single" or "multi").
     */
    private static int getLatestRunId(Connection conn, String mode) throws SQLException {
        String sql = "SELECT run_id FROM crawl_runs WHERE mode = ? ORDER BY run_id DESC LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, mode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("run_id");
                }
            }
        }
        throw new SQLException("No '" + mode + "' run found in crawl_runs. Run ComparisonRunner first.");
    }

    /**
     * Reads the HTML template, injects the real data, writes the final report.
     */
    private static void writeReport(String dataJson) throws IOException {
        String template = new String(Files.readAllBytes(Paths.get(TEMPLATE_PATH)), StandardCharsets.UTF_8);

        int startIdx = template.indexOf(START_MARKER);
        int endIdx = template.indexOf(END_MARKER);
        if (startIdx == -1 || endIdx == -1) {
            throw new IOException("Could not find data markers in " + TEMPLATE_PATH
                    + " - make sure report.html has the /*CRAWL_DATA_START*/ ... /*CRAWL_DATA_END*/ markers.");
        }

        String before = template.substring(0, startIdx + START_MARKER.length());
        String after = template.substring(endIdx);
        String result = before + dataJson + after;

        Files.write(Paths.get(OUTPUT_PATH), result.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Minimal escaping - safe for our simple alphanumeric page IDs and thread
     * names.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
