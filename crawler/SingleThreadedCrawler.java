package crawler;

import db.DatabaseManager;
import graph.Page;
import graph.WebGraph;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Day 2 goal: single-threaded crawler that correctly visits every
 * reachable page exactly once, respects a max depth, and logs each
 * visit to the database. Get this right before adding threads -
 * multithreading only adds concurrency bugs on top of correctness
 * bugs if the base logic isn't solid first.
 */
public class SingleThreadedCrawler {

    private final WebGraph graph;
    private final DatabaseManager db;
    private final int maxDepth;
    private final Set<String> visited = new HashSet<>();
    private final int simulatedLatencyMs;
    private final ProgressListener listener;

    public SingleThreadedCrawler(WebGraph graph, DatabaseManager db, int maxDepth) {
        this(graph, db, maxDepth, 20, null);
    }

    public SingleThreadedCrawler(WebGraph graph, DatabaseManager db, int maxDepth, int simulatedLatencyMs) {
        this(graph, db, maxDepth, simulatedLatencyMs, null);
    }

    public SingleThreadedCrawler(WebGraph graph, DatabaseManager db, int maxDepth,
                                  int simulatedLatencyMs, ProgressListener listener) {
        this.graph = graph;
        this.db = db;
        this.maxDepth = maxDepth;
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.listener = listener;
    }

    /**
     * Crawls starting from startPageId using BFS (queue-based),
     * which naturally respects "depth" as distance from the start.
     */
    public void crawl(String startPageId, int runId) throws SQLException {
        if (graph.getPage(startPageId) == null) {
            System.out.println("Cannot start crawl: page '" + startPageId + "' does not exist in the graph.");
            return;
        }

        long startTime = System.currentTimeMillis();
        Deque<CrawlItem> queue = new ArrayDeque<>();
        queue.add(new CrawlItem(startPageId, 0));
        visited.add(startPageId);

        int order = 0;
        while (!queue.isEmpty()) {
            CrawlItem item = queue.poll();
            Page page = graph.getPage(item.pageId);
            if (page == null) continue; // link to a page that doesn't exist - skip

            order++;
            System.out.println("[main] visiting " + page.getId() +
                    " (depth " + item.depth + ", order " + order + ")");

            // Simulate the time a real crawler would spend waiting on a
            // network response for this page. This is the "I/O wait" that
            // multithreading is actually designed to overlap - without it,
            // there's no idle time for a thread pool to fill.
            try {
                Thread.sleep(simulatedLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long elapsed = System.currentTimeMillis() - startTime;
            db.logVisit(runId, page.getId(), "main", item.depth, elapsed);
            if (listener != null) {
                listener.onVisit(page.getId(), "main", item.depth, elapsed, page.getLinks());
            }

            if (item.depth >= maxDepth) {
                continue; // don't expand further from here
            }

            for (String linkedId : page.getLinks()) {
                if (!visited.contains(linkedId)) {
                    visited.add(linkedId);
                    queue.add(new CrawlItem(linkedId, item.depth + 1));
                }
            }
        }

        System.out.println("Crawl finished. Total pages visited: " + visited.size());
    }

    public int getVisitedCount() {
        return visited.size();
    }

    /** Simple holder for a queued page + the depth it was discovered at. */
    private static class CrawlItem {
        final String pageId;
        final int depth;

        CrawlItem(String pageId, int depth) {
            this.pageId = pageId;
            this.depth = depth;
        }
    }

    /** Runs a full crawl against the test graph and times it. */
    public static void main(String[] args) throws Exception {
        WebGraph graph = WebGraph.buildTestGraph();

        try (DatabaseManager db = new DatabaseManager()) {
            for (Page page : graph.getAllPages().values()) {
                db.insertPage(page.getId(), page.getTitle());
            }
            for (Page page : graph.getAllPages().values()) {
                for (String link : page.getLinks()) {
                    db.insertLink(page.getId(), link);
                }
            }

            int runId = db.startRun("single", 1);

            long start = System.currentTimeMillis();
            SingleThreadedCrawler crawler = new SingleThreadedCrawler(graph, db, 5);
            crawler.crawl("P1", runId);
            long elapsed = System.currentTimeMillis() - start;

            db.finishRun(runId, (int) elapsed, crawler.getVisitedCount());
            System.out.println("Time taken: " + elapsed + "ms");
        }
        // db.close() runs automatically here, even if an exception was thrown above.
    }
}
