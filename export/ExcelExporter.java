package export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ExcelExporter {

    private static final String[] HEADERS = {
        "Page Number / ID",
        "Crawled URL",
        "Page Title",
        "HTTP Status Code",
        "Crawl Depth",
        "Discovered Links",
        "Number of Links",
        "Thread Name",
        "Response Time / Fetch Duration",
        "Crawl Timestamp"
    };

    public static String exportLatestRun(Connection conn, String outputPath) throws SQLException, IOException {
        int runId = getLatestRunId(conn);
        if (runId <= 0) {
            throw new SQLException("No crawl run found in the database.");
        }

        List<ExportRow> rows = loadRows(conn, runId);
        if (rows.isEmpty()) {
            throw new SQLException("No crawled records were found for run " + runId + ".");
        }

        Path target = Paths.get(outputPath);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Workbook workbook = new XSSFWorkbook(); FileOutputStream out = new FileOutputStream(target.toFile())) {
            Sheet sheet = workbook.createSheet("Crawler Report");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (ExportRow row : rows) {
                Row dataRow = sheet.createRow(rowIndex++);
                dataRow.createCell(0).setCellValue(row.pageNumber);
                dataRow.createCell(1).setCellValue(nvl(row.crawledUrl));
                dataRow.createCell(2).setCellValue(nvl(row.pageTitle));
                if (row.httpStatusCode != null) {
                    dataRow.createCell(3).setCellValue(row.httpStatusCode);
                } else {
                    dataRow.createCell(3).setCellValue("");
                }
                if (row.depth != null) {
                    dataRow.createCell(4).setCellValue(row.depth);
                } else {
                    dataRow.createCell(4).setCellValue("");
                }
                dataRow.createCell(5).setCellValue(nvl(row.discoveredLinks));
                dataRow.createCell(6).setCellValue(row.linkCount);
                dataRow.createCell(7).setCellValue(nvl(row.threadName));
                if (row.responseTimeMs != null) {
                    dataRow.createCell(8).setCellValue(row.responseTimeMs);
                } else {
                    dataRow.createCell(8).setCellValue("");
                }
                dataRow.createCell(9).setCellValue(nvl(row.crawlTimestamp));
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
        }

        return target.toString();
    }

    public static String exportLatestRunToDefaultFolder(Connection conn) throws SQLException, IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
        String fileName = "crawler_report_" + timestamp + ".xlsx";
        return exportLatestRun(conn, Paths.get("report", fileName).toString());
    }

    private static int getLatestRunId(Connection conn) throws SQLException {
        String sql = "SELECT run_id FROM crawl_runs ORDER BY run_id DESC LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt("run_id");
            }
        }
        return -1;
    }

    private static List<ExportRow> loadRows(Connection conn, int runId) throws SQLException {
        List<ExportRow> rows = new ArrayList<>();
        String sql = "SELECT cl.id, cl.page_id, cl.page_url, cl.thread_name, cl.depth, cl.elapsed_ms, "
                + "cl.status_code, cl.discovered_links, cl.visited_at, p.title "
                + "FROM crawl_log cl LEFT JOIN pages p ON p.page_id = cl.page_id "
                + "WHERE cl.run_id = ? ORDER BY cl.id ASC";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, runId);
            try (ResultSet rs = stmt.executeQuery()) {
                int pageNumber = 1;
                while (rs.next()) {
                    ExportRow row = new ExportRow();
                    row.pageNumber = pageNumber++;
                    row.crawledUrl = rs.getString("page_url");
                    if (row.crawledUrl == null || row.crawledUrl.trim().isEmpty()) {
                        row.crawledUrl = rs.getString("page_id");
                    }
                    row.pageTitle = rs.getString("title");
                    if (row.pageTitle == null || row.pageTitle.trim().isEmpty()) {
                        row.pageTitle = rs.getString("page_id");
                    }
                    row.httpStatusCode = rs.getObject("status_code") == null ? null : rs.getInt("status_code");
                    row.depth = rs.getObject("depth") == null ? null : rs.getInt("depth");
                    row.discoveredLinks = rs.getString("discovered_links");
                    row.linkCount = safeCount(row.discoveredLinks);
                    row.threadName = rs.getString("thread_name");
                    row.responseTimeMs = rs.getObject("elapsed_ms") == null ? null : rs.getLong("elapsed_ms");
                    row.crawlTimestamp = rs.getString("visited_at");
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static int safeCount(String discoveredLinks) {
        if (discoveredLinks == null || discoveredLinks.trim().isEmpty()) {
            return 0;
        }
        String trimmed = discoveredLinks.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return 0;
        }
        String[] parts = trimmed.split(";");
        int count = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static class ExportRow {

        int pageNumber;
        String crawledUrl;
        String pageTitle;
        Integer httpStatusCode;
        Integer depth;
        String discoveredLinks;
        int linkCount;
        String threadName;
        Long responseTimeMs;
        String crawlTimestamp;
    }
}
