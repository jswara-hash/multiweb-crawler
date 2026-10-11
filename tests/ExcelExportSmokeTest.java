
import db.DatabaseManager;
import export.ExcelExporter;

import java.io.File;
import java.sql.Connection;

public class ExcelExportSmokeTest {

    public static void main(String[] args) throws Exception {
        File dbFile = new File("crawler.db");
        if (dbFile.exists()) {
            dbFile.delete();
        }

        try (DatabaseManager db = new DatabaseManager()) {
            db.clearGraph();
            int runId = db.startRun("real", 4);
            db.insertPage("https://example.com/", "Example", "excerpt");
            db.logVisit(runId, "https://example.com/", "pool-1-thread-1", 0, 120L, 200, "https://example.com/about", "https://example.com/", "Example");
            db.insertLink("https://example.com/", "https://example.com/about");
            db.insertPage("https://example.com/about", "About", "about excerpt");
            db.logVisit(runId, "https://example.com/about", "pool-1-thread-2", 1, 80L, 200, "", "https://example.com/about", "About");
            db.finishRun(runId, 250, 2);

            try (Connection conn = db.getConnection()) {
                File output = File.createTempFile("crawler_export_test", ".xlsx");
                ExcelExporter.exportLatestRun(conn, output.getAbsolutePath());
                if (!output.exists() || output.length() == 0) {
                    throw new IllegalStateException("Export file was not created");
                }
                System.out.println("Excel export smoke test passed: " + output.getAbsolutePath());
            }
        }
    }
}
