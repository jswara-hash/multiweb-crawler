package crawler;

import db.DatabaseManager;
import graph.Page;
import graph.WebGraph;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Day 3: multithreaded version of the crawler.
 *
 * Key differences from SingleThreadedCrawler:
 *  - A fixed thread pool processes pages concurrently instead of one at a time.
 *  - "visited" is a ConcurrentHashMap-backed set. Its add() method is atomic,
 *    so calling visited.add(id) doubles as both the "have we seen this?"
 *    check AND the "mark as seen" step in one indivisible operation -
 *    this is what prevents two threads from both claiming the same page.
 *  - Because pages get discovered dynamically (a worker finds new links
 *    and submits new tasks), we can't just wait for the pool to "run out
 *    of submitted work" - we track how many tasks are in-flight with an
 *    AtomicInteger and only shut down once that count hits zero AFTER
 *    a task finishes (not before, to avoid a race where it hits zero
 *    momentarily between two threads handing off work to each other).
 */
public class MultiThreadedCrawler {

    private final WebGraph graph;
    private final DatabaseManager db;
    private final int maxDepth;
    private final int runId;

    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final ExecutorService pool;
    private final int simulatedLatencyMs;
    private final ProgressListener listener;

    // Used to block main thread until the crawl is fully done.
    private final Object doneLock = new Object();
    private volatile boolean finished = false;
    private volatile long startTime;

    public MultiThreadedCrawler(WebGraph graph, DatabaseManager db, int maxDepth, int runId, int threadCount) {
        this(graph, db, maxDepth, runId, threadCount, 20, null);
    }

    public MultiThreadedCrawler(WebGraph graph, DatabaseManager db, int maxDepth, int runId, int threadCount, int simulatedLatencyMs) {
        this(graph, db, maxDepth, runId, threadCount, simulatedLatencyMs, null);
    }

    public MultiThreadedCrawler(WebGraph graph, DatabaseManager db, int maxDepth, int runId, int threadCount,
                                 int simulatedLatencyMs, ProgressListener listener) {
        this.graph = graph;
        this.db = db;
        this.maxDepth = maxDepth;
        this.runId = runId;
        this.pool = Executors.newFixedThreadPool(threadCount);
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.listener = listener;
    }

    public void crawl(String startPageId) throws InterruptedException {
        if (graph.getPage(startPageId) == null) {
            System.out.println("Cannot start crawl: page '" + startPageId + "' does not exist in the graph.");
            pool.shutdown();
            return;
        }

        startTime = System.currentTimeMillis();
        submitTask(startPageId, 0);

        // Block here until the last task signals completion.
        synchronized (doneLock) {
            while (!finished) {
                doneLock.wait();
            }
        }

        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Submits a page to be crawled. Called both for the start page
     * and, recursively, by worker threads when they discover new links.
     */
    private void submitTask(String pageId, int depth) {
        // Atomic check-and-mark: add() returns false if already present,
        // meaning some other thread already claimed this page first.
        if (!visited.add(pageId)) {
            return;
        }

        pendingTasks.incrementAndGet();
        pool.submit(() -> processPage(pageId, depth));
    }

    private void processPage(String pageId, int depth) {
        try {
            Page page = graph.getPage(pageId);
            if (page != null) {
                String threadName = Thread.currentThread().getName();
                System.out.println("[" + threadName + "] visiting " + pageId + " (depth " + depth + ")");

                // Same simulated network wait as the single-threaded version.
                // This is where the multithreaded version wins: while this
                // thread sleeps, OTHER threads are free to process their
                // own pages instead of the CPU sitting idle.
                try {
                    Thread.sleep(simulatedLatencyMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                long elapsedMs = System.currentTimeMillis() - startTime;
                try {
                    db.logVisit(runId, pageId, threadName, depth, elapsedMs);
                } catch (SQLException e) {
                    System.err.println("DB write failed for " + pageId + ": " + e.getMessage());
                }
                if (listener != null) {
                    listener.onVisit(pageId, threadName, depth, elapsedMs, page.getLinks());
                }

                if (depth < maxDepth) {
                    for (String linkedId : page.getLinks()) {
                        submitTask(linkedId, depth + 1);
                    }
                }
            }
        } finally {
            // This task is done. If it was the last one in flight, signal completion.
            if (pendingTasks.decrementAndGet() == 0) {
                synchronized (doneLock) {
                    finished = true;
                    doneLock.notifyAll();
                }
            }
        }
    }

    public int getVisitedCount() {
        return visited.size();
    }

    /** Runs the multithreaded crawl against the test graph and times it. */
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

            int threadCount = 4;
            int runId = db.startRun("multi", threadCount);

            long start = System.currentTimeMillis();
            MultiThreadedCrawler crawler = new MultiThreadedCrawler(graph, db, 5, runId, threadCount);
            crawler.crawl("P1");
            long elapsed = System.currentTimeMillis() - start;

            db.finishRun(runId, (int) elapsed, crawler.getVisitedCount());
            System.out.println("Crawl finished. Total pages visited: " + crawler.getVisitedCount());
            System.out.println("Time taken: " + elapsed + "ms");
        }
    }
}
