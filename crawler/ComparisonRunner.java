package crawler;

import db.DatabaseManager;
import graph.Page;
import graph.WebGraph;

import java.sql.SQLException;

/**
 * Day 4: runs the SAME graph both single-threaded and multi-threaded,
 * timing each, so we get a real speedup number for the demo/report.
 *
 * On the tiny 6-page test graph the difference is meaningless (JVM/DB
 * startup overhead dominates), so this uses a much bigger randomly
 * generated graph where the actual crawl work is large enough for
 * multithreading to show a real advantage.
 */
public class ComparisonRunner {

    public static void main(String[] args) throws Exception {
        int numPages = 300;
        int maxLinksPerPage = 6;
        int maxDepth = 8;
        int threadCount = 8;

        // Build ONE graph and reuse it for both runs, so the comparison is fair -
        // both crawlers see the exact same set of pages and links.
        WebGraph graph = WebGraph.buildRandomGraph(numPages, maxLinksPerPage);

        try (DatabaseManager db = new DatabaseManager()) {
            persistGraph(graph, db);

            System.out.println("=== Single-threaded run ===");
            long singleTime = runSingleThreaded(graph, db, maxDepth);
            System.out.println("Single-threaded time: " + singleTime + "ms");

            System.out.println("\n=== Multi-threaded run (" + threadCount + " threads) ===");
            long multiTime = runMultiThreaded(graph, db, maxDepth, threadCount);
            System.out.println("Multi-threaded time: " + multiTime + "ms");

            System.out.println("\n=== Result ===");
            double speedup = (double) singleTime / (double) multiTime;
            System.out.printf("Speedup: %.2fx faster with %d threads%n", speedup, threadCount);
        }
    }

    private static long runSingleThreaded(WebGraph graph, DatabaseManager db, int maxDepth) throws SQLException {
        int runId = db.startRun("single", 1);

        long start = System.currentTimeMillis();
        SingleThreadedCrawler crawler = new SingleThreadedCrawler(graph, db, maxDepth);
        crawler.crawl("P1", runId);
        long elapsed = System.currentTimeMillis() - start;

        db.finishRun(runId, (int) elapsed, crawler.getVisitedCount());
        return elapsed;
    }

    private static long runMultiThreaded(WebGraph graph, DatabaseManager db, int maxDepth, int threadCount) throws Exception {
        int runId = db.startRun("multi", threadCount);

        long start = System.currentTimeMillis();
        MultiThreadedCrawler crawler = new MultiThreadedCrawler(graph, db, maxDepth, runId, threadCount);
        crawler.crawl("P1");
        long elapsed = System.currentTimeMillis() - start;

        db.finishRun(runId, (int) elapsed, crawler.getVisitedCount());
        return elapsed;
    }

    /** Writes the graph's pages and links into the DB once, before either run. */
    private static void persistGraph(WebGraph graph, DatabaseManager db) throws SQLException {
        db.clearGraph();
        for (Page page : graph.getAllPages().values()) {
            db.insertPage(page.getId(), page.getTitle());
        }
        for (Page page : graph.getAllPages().values()) {
            for (String link : page.getLinks()) {
                db.insertLink(page.getId(), link);
            }
        }
    }
}
