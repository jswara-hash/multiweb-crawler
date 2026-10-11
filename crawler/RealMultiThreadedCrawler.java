package crawler;

import db.DatabaseManager;
import scraper.WebScraper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step C: same self-feeding thread-pool pattern as MultiThreadedCrawler
 * (atomic visited-set check, AtomicInteger in-flight counter for correct
 * termination detection), but each "task" is a REAL HTTP fetch instead of
 * a simulated delay. This is where the concurrency techniques from the
 * simulated crawler pay off against real, unpredictable network latency.
 *
 * A shared AtomicInteger caps the total pages fetched across all threads,
 * so a demo run can't accidentally balloon into fetching hundreds of real
 * pages.
 */
public class RealMultiThreadedCrawler {

    private final DatabaseManager db;
    private final int maxDepth;
    private final int maxPages;
    private final int politenessDelayMs;
    private final int runId;
    private final ProgressListener listener;

    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final AtomicInteger pagesFetched = new AtomicInteger(0);
    private final ExecutorService pool;

    private final Object doneLock = new Object();
    private volatile boolean finished = false;
    private volatile long startTime;

    public RealMultiThreadedCrawler(DatabaseManager db, int maxDepth, int maxPages, int politenessDelayMs,
                                     int runId, int threadCount, ProgressListener listener) {
        this.db = db;
        this.maxDepth = maxDepth;
        this.maxPages = maxPages;
        this.politenessDelayMs = politenessDelayMs;
        this.runId = runId;
        this.listener = listener;
        this.pool = Executors.newFixedThreadPool(threadCount);
    }

    public void crawl(String seedUrl) throws InterruptedException {
        startTime = System.currentTimeMillis();
        submitTask(seedUrl, 0);

        synchronized (doneLock) {
            while (!finished) {
                doneLock.wait();
            }
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
    }

    private void submitTask(String url, int depth) {
        if (pagesFetched.get() >= maxPages) return;
        if (!visited.add(url)) return; // atomic check-and-mark

        pendingTasks.incrementAndGet();
        pool.submit(() -> processPage(url, depth));
    }

    private void processPage(String url, int depth) {
        try {
            if (pagesFetched.incrementAndGet() > maxPages) return;

            String threadName = Thread.currentThread().getName();

            WebScraper.PageData page;
            try {
                page = WebScraper.fetch(url);
            } catch (Exception e) {
                System.out.println("[" + threadName + "] failed to fetch " + url + ": " + e.getMessage());
                return;
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            System.out.println("[" + threadName + "] visited " + url + " (depth " + depth + ", +" + elapsedMs + "ms)");

            try {
                db.insertPage(url, page.title);
                for (String link : page.links) {
                    db.insertLink(url, link);
                }
                db.logVisit(runId, url, threadName, depth, elapsedMs);
            } catch (Exception e) {
                System.err.println("DB write failed for " + url + ": " + e.getMessage());
            }

            if (listener != null) {
                listener.onVisit(url, threadName, depth, elapsedMs, page.links);
            }

            politeWait();

            if (depth < maxDepth) {
                for (String link : page.links) {
                    submitTask(link, depth + 1);
                }
            }
        } finally {
            if (pendingTasks.decrementAndGet() == 0) {
                synchronized (doneLock) {
                    finished = true;
                    doneLock.notifyAll();
                }
            }
        }
    }

    private void politeWait() {
        if (politenessDelayMs <= 0) return;
        try {
            Thread.sleep(politenessDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getVisitedCount() {
        return visited.size();
    }
}
