package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import crawler.GenericCrawler;
import crawler.PageFetcher;
import crawler.ProgressListener;
import crawler.RealPageFetcher;
import crawler.SimulatedPageFetcher;
import db.DatabaseManager;
import export.ExcelExporter;
import export.JsonExporter;
import graph.Page;
import graph.WebGraph;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step A + B + C: embedded HTTP server that can trigger crawls on demand,
 * stream live progress to the browser, and crawl either a simulated graph or a
 * real website.
 *
 * Uses com.sun.net.httpserver.HttpServer, which ships with the JDK - no
 * external web framework needed, so the project stays pure Java.
 *
 * Endpoints: GET / -> serves report/live-report.html GET /api/results -> latest
 * saved crawl data as JSON (from DB) POST /api/run -> starts a fresh crawl in
 * the background params: source (simulated|real), pages, threads, depth,
 * latency, seedUrl (real mode only) GET /api/progress -> events since ?since=N,
 * plus running/done state
 *
 * Start it with: java -cp .;sqlite-jdbc-3.53.4.0.jar server.CrawlServer Then
 * open: http://localhost:8080
 */
public class CrawlServer {

    private static final int PORT = resolvePort();
    private static final String LANDING_PAGE_PATH = "index.html";
    private static final String DASHBOARD_PAGE_PATH = "report/live-report.html";

    private static int resolvePort() {
        String configured = System.getenv("PORT");
        if (configured != null && !configured.trim().isEmpty()) {
            try {
                return Integer.parseInt(configured.trim());
            } catch (NumberFormatException ignored) {
                // Fall back to the local default when Render or another platform
                // provides an unexpected port format.
            }
        }
        return 8080;
    }

    private static final AtomicBoolean crawlInProgress = new AtomicBoolean(false);

    private static final List<ProgressEvent> events = new CopyOnWriteArrayList<>();
    private static final AtomicInteger seqCounter = new AtomicInteger(0);
    private static volatile boolean running = false;
    private static volatile String currentPagesJson = "[]";
    private static volatile String lastSource = "none";
    private static volatile String lastSeedUrl = "";

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get("report"));
        Files.createDirectories(Paths.get("data"));

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", CrawlServer::handlePage);
        server.createContext("/api/results", CrawlServer::handleResults);
        server.createContext("/api/run", CrawlServer::handleRun);
        server.createContext("/api/progress", CrawlServer::handleProgress);
        server.createContext("/api/export/xlsx", CrawlServer::handleExportXlsx);

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Crawl server running at http://localhost:" + PORT);
        System.out.println("Press Ctrl+C to stop.");
    }

    private static void handlePage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if ("/".equals(path) || "/index.html".equals(path)) {
            serveStaticFile(exchange, LANDING_PAGE_PATH, "text/html; charset=utf-8");
            return;
        }

        if ("/dashboard".equals(path) || "/dashboard/".equals(path)
                || "/live-report.html".equals(path)
                || "/report/live-report.html".equals(path)) {
            serveStaticFile(exchange, DASHBOARD_PAGE_PATH, "text/html; charset=utf-8");
            return;
        }

        send(exchange, 404, "text/plain", "Not found");
    }

    private static void serveStaticFile(HttpExchange exchange, String filePath, String contentType)
            throws IOException {
        try {
            byte[] content = Files.readAllBytes(Paths.get(filePath));
            send(exchange, 200, contentType, content);
        } catch (IOException e) {
            send(exchange, 500, "text/plain",
                    "Could not read " + filePath + " - run the server from the project root.");
        }
    }

    private static void handleResults(HttpExchange exchange) throws IOException {
        try (Connection conn = DatabaseManager.createConnection()) {
            String dataJson = JsonExporter.buildDataJson(conn);
            // Inject source/seedUrl metadata so the browser can label whose
            // results these actually are, instead of assuming it matches
            // whatever the form currently shows.
            String withMeta = "{\"source\":\"" + lastSource + "\",\"seedUrl\":\""
                    + escapeJson(lastSeedUrl) + "\"," + dataJson.substring(1);
            send(exchange, 200, "application/json; charset=utf-8", withMeta);
        } catch (Exception e) {
            sendError(exchange, e.getMessage());
        }
    }

    private static void handleRun(HttpExchange exchange) throws IOException {
        if (!crawlInProgress.compareAndSet(false, true)) {
            sendError(exchange, "A crawl is already running. Please wait for it to finish.");
            return;
        }

        try {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String source = params.getOrDefault("source", "simulated");
            int threadCount = intParam(params, "threads", 8);
            int maxDepth = intParam(params, "depth", 8);
            int latencyMs = intParam(params, "latency", 20);
            int numPages = intParam(params, "pages", 300);

            events.clear();
            seqCounter.set(0);
            running = true;

            if ("real".equals(source)) {
                String seedUrl = params.getOrDefault("seedUrl", "");
                if (seedUrl.isEmpty()) {
                    running = false;
                    crawlInProgress.set(false);
                    sendError(exchange, "seedUrl is required for real crawling.");
                    return;
                }
                seedUrl = URLDecoder.decode(seedUrl, "UTF-8");
                lastSource = "real";
                lastSeedUrl = seedUrl;

                // Cap real-mode page counts conservatively - courtesy to the target site.
                int realMaxPages = Math.min(numPages, 60);
                int politenessMs = Math.max(latencyMs, 150);

                currentPagesJson = "[]"; // graph unknown upfront in real mode - grows via events

                send(exchange, 200, "application/json; charset=utf-8",
                        "{\"started\": true, \"pages\": []}");

                final String seed = seedUrl;
                Thread worker = new Thread(() -> {
                    try {
                        runRealComparison(seed, threadCount, maxDepth, realMaxPages, politenessMs);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        running = false;
                        crawlInProgress.set(false);
                    }
                }, "real-crawl-worker");
                worker.start();

            } else {
                lastSource = "simulated";
                lastSeedUrl = "";
                WebGraph graph = WebGraph.buildRandomGraph(numPages, 6);
                currentPagesJson = buildPagesJsonFromGraph(graph);

                send(exchange, 200, "application/json; charset=utf-8",
                        "{\"started\": true, \"pages\": " + currentPagesJson + "}");

                Thread worker = new Thread(() -> {
                    try {
                        runSimulatedComparison(graph, threadCount, maxDepth, latencyMs);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        running = false;
                        crawlInProgress.set(false);
                    }
                }, "sim-crawl-worker");
                worker.start();
            }

        } catch (Exception e) {
            e.printStackTrace();
            running = false;
            crawlInProgress.set(false);
            sendError(exchange, e.getMessage());
        }
    }

    private static void handleProgress(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        int since = intParam(params, "since", 0);

        StringBuilder json = new StringBuilder();
        json.append("{\"running\": ").append(running).append(",");
        json.append("\"latestSeq\": ").append(seqCounter.get()).append(",");
        json.append("\"events\": [");
        boolean first = true;
        for (ProgressEvent event : events) {
            if (event.seq <= since) {
                continue;
            }
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append(event.toJson());
        }
        json.append("]}");

        send(exchange, 200, "application/json; charset=utf-8", json.toString());
    }

    private static void handleExportXlsx(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\": \"Only GET is supported for this endpoint.\"}");
            return;
        }

        try (Connection conn = DatabaseManager.createConnection()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
            String filename = "crawler_report_" + timestamp + ".xlsx";
            Path exportDir = Paths.get("report");
            Files.createDirectories(exportDir);
            Path outputPath = exportDir.resolve(filename);

            ExcelExporter.exportLatestRun(conn, outputPath.toString());
            byte[] workbookBytes = Files.readAllBytes(outputPath);

            exchange.getResponseHeaders().set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            send(exchange, 200, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes);
        } catch (Exception e) {
            sendError(exchange, e.getMessage());
        }
    }

    /**
     * Simulated graph: single-threaded run (threadCount=1), then multi-threaded
     * run.
     */
    private static void runSimulatedComparison(WebGraph graph, int threadCount, int maxDepth, int latencyMs)
            throws Exception {
        try (DatabaseManager db = new DatabaseManager()) {
            db.clearGraph();
            for (Page page : graph.getAllPages().values()) {
                db.insertPage(page.getId(), page.getTitle());
            }
            for (Page page : graph.getAllPages().values()) {
                for (String link : page.getLinks()) {
                    db.insertLink(page.getId(), link);
                }
            }

            int totalPages = graph.size();
            PageFetcher fetcher = new SimulatedPageFetcher(graph, latencyMs);

            int singleRunId = db.startRun("single", 1);
            ProgressListener singleListener = (pageId, thread, depth, elapsedMs, links)
                    -> addEvent("single", pageId, thread, depth, elapsedMs, links);
            long singleStart = System.currentTimeMillis();
            GenericCrawler single = new GenericCrawler(fetcher, db, maxDepth, totalPages, singleRunId, 1, singleListener);
            single.crawl("P1");
            long singleTime = System.currentTimeMillis() - singleStart;
            db.finishRun(singleRunId, (int) singleTime, single.getVisitedCount());

            int multiRunId = db.startRun("multi", threadCount);
            ProgressListener multiListener = (pageId, thread, depth, elapsedMs, links)
                    -> addEvent("multi", pageId, thread, depth, elapsedMs, links);
            long multiStart = System.currentTimeMillis();
            GenericCrawler multi = new GenericCrawler(fetcher, db, maxDepth, totalPages, multiRunId, threadCount, multiListener);
            multi.crawl("P1");
            long multiTime = System.currentTimeMillis() - multiStart;
            db.finishRun(multiRunId, (int) multiTime, multi.getVisitedCount());

            System.out.printf("Simulated done. Single: %dms, Multi: %dms (%.2fx)%n",
                    singleTime, multiTime, (double) singleTime / multiTime);
        }
    }

    /**
     * Real web: single-threaded scrape (threadCount=1) of the seed site, then
     * multi-threaded scrape of the same seed - each pass hits the real site
     * fresh, capped and rate-limited.
     */
    private static void runRealComparison(String seedUrl, int threadCount, int maxDepth,
            int maxPages, int politenessMs) throws Exception {
        try (DatabaseManager db = new DatabaseManager()) {
            db.clearGraph();
            PageFetcher fetcher = new RealPageFetcher(politenessMs);

            int singleRunId = db.startRun("single", 1);
            ProgressListener singleListener = (pageId, thread, depth, elapsedMs, links)
                    -> addEvent("single", pageId, thread, depth, elapsedMs, links);
            long singleStart = System.currentTimeMillis();
            GenericCrawler single = new GenericCrawler(fetcher, db, maxDepth, maxPages, singleRunId, 1, singleListener);
            single.crawl(seedUrl);
            long singleTime = System.currentTimeMillis() - singleStart;
            db.finishRun(singleRunId, (int) singleTime, single.getVisitedCount());

            int multiRunId = db.startRun("multi", threadCount);
            ProgressListener multiListener = (pageId, thread, depth, elapsedMs, links)
                    -> addEvent("multi", pageId, thread, depth, elapsedMs, links);
            long multiStart = System.currentTimeMillis();
            GenericCrawler multi = new GenericCrawler(fetcher, db, maxDepth, maxPages, multiRunId, threadCount, multiListener);
            multi.crawl(seedUrl);
            long multiTime = System.currentTimeMillis() - multiStart;
            db.finishRun(multiRunId, (int) multiTime, multi.getVisitedCount());

            System.out.printf("Real crawl done. Single: %dms, Multi: %dms (%.2fx)%n",
                    singleTime, multiTime, (double) singleTime / multiTime);
        }
    }

    private static void addEvent(String mode, String pageId, String threadName, int depth,
            long elapsedMs, List<String> links) {
        int seq = seqCounter.incrementAndGet();
        events.add(new ProgressEvent(seq, mode, pageId, threadName, depth, elapsedMs, links));
    }

    private static String buildPagesJsonFromGraph(WebGraph graph) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (Page page : graph.getAllPages().values()) {
            if (!first) {
                json.append(",");
            }
            first = false;
            json.append("{\"id\":\"").append(page.getId()).append("\",\"links\":[");
            List<String> links = page.getLinks();
            for (int i = 0; i < links.size(); i++) {
                if (i > 0) {
                    json.append(",");
                }
                json.append("\"").append(links.get(i)).append("\"");
            }
            json.append("]}");
        }
        json.append("]");
        return json.toString();
    }

    // ---------- small helpers ----------
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    private static int intParam(Map<String, String> params, String key, int defaultValue) {
        try {
            return params.containsKey(key) ? Integer.parseInt(params.get(key)) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendError(HttpExchange exchange, String message) throws IOException {
        String safe = message == null ? "Unknown error" : message.replace("\"", "'");
        send(exchange, 500, "application/json; charset=utf-8",
                "{\"error\": \"" + safe + "\"}");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class ProgressEvent {

        final int seq;
        final String mode;
        final String page;
        final String thread;
        final int depth;
        final long elapsedMs;
        final List<String> links;

        ProgressEvent(int seq, String mode, String page, String thread, int depth, long elapsedMs, List<String> links) {
            this.seq = seq;
            this.mode = mode;
            this.page = page;
            this.thread = thread;
            this.depth = depth;
            this.elapsedMs = elapsedMs;
            this.links = links;
        }

        String toJson() {
            StringBuilder linksJson = new StringBuilder("[");
            if (links != null) {
                for (int i = 0; i < links.size(); i++) {
                    if (i > 0) {
                        linksJson.append(",");
                    }
                    linksJson.append("\"").append(escapeJson(links.get(i))).append("\"");
                }
            }
            linksJson.append("]");

            return "{\"seq\":" + seq
                    + ",\"mode\":\"" + mode + "\""
                    + ",\"page\":\"" + escapeJson(page) + "\""
                    + ",\"thread\":\"" + escapeJson(thread) + "\""
                    + ",\"depth\":" + depth
                    + ",\"elapsedMs\":" + elapsedMs
                    + ",\"links\":" + linksJson + "}";
        }
    }
}
