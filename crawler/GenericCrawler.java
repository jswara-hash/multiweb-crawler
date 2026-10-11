package crawler;

import db.DatabaseManager;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single crawler implementation that works for BOTH single-threaded and
 * multi-threaded runs, and for BOTH simulated and real crawling - the only
 * things that vary are the PageFetcher (how to get one page) and the thread
 * count.
 *
 * With threadCount = 1, this behaves identically to the old dedicated
 * single-threaded crawler: Executors.newFixedThreadPool(1) processes its
 * internal task queue strictly FIFO, so with one worker, execution order equals
 * submission order - exactly BFS order, same as a plain queue loop. With
 * threadCount > 1, it's the same self-feeding thread-pool pattern as before:
 * atomic visited-set check-and-mark, AtomicInteger tracking in-flight tasks for
 * correct termination detection.
 *
 * The four original crawler classes (SingleThreadedCrawler,
 * MultiThreadedCrawler, RealSingleThreadedCrawler, RealMultiThreadedCrawler)
 * are left untouched and still work standalone - this class is an alternative,
 * not a replacement of them in the codebase.
 */
public class GenericCrawler {

    private final PageFetcher fetcher;
    private final DatabaseManager db;
    private final int maxDepth;
    private final int maxPages;
    private final int runId;
    private final ProgressListener listener;

    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final AtomicInteger pagesFetched = new AtomicInteger(0);
    private final ExecutorService pool;

    private final AtomicInteger successfulVisits = new AtomicInteger(0);

    private final Object doneLock = new Object();
    private volatile boolean finished = false;
    private volatile long startTime;

    public GenericCrawler(PageFetcher fetcher, DatabaseManager db, int maxDepth, int maxPages,
            int runId, int threadCount, ProgressListener listener) {
        this.fetcher = fetcher;
        this.db = db;
        this.maxDepth = maxDepth;
        this.maxPages = maxPages;
        this.runId = runId;
        this.listener = listener;
        this.pool = Executors.newFixedThreadPool(threadCount);
    }

    public void crawl(String startPageId) throws InterruptedException {
        startTime = System.currentTimeMillis();
        submitTask(startPageId, 0);

        synchronized (doneLock) {
            while (!finished) {
                doneLock.wait();
            }
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
    }

    private void submitTask(String pageId, int depth) {
        if (pagesFetched.get() >= maxPages) {
            return;
        }
        if (!visited.add(pageId)) {
            return; // atomic check-and-mark
        }
        pendingTasks.incrementAndGet();
        pool.submit(() -> processPage(pageId, depth));
    }

    private void processPage(String pageId, int depth) {
        try {
            if (pagesFetched.incrementAndGet() > maxPages) {
                return;
            }

            String threadName = Thread.currentThread().getName();

            PageFetcher.FetchResult result;
            long responseTimeMs = 0L;
            long fetchStart = System.currentTimeMillis();
            try {
                result = fetcher.fetch(pageId);
                responseTimeMs = System.currentTimeMillis() - fetchStart;
            } catch (Exception e) {
                System.out.println("[" + threadName + "] failed to fetch " + pageId + ": " + e.getMessage());
                return;
            }

            long elapsedMs = responseTimeMs;
            System.out.println("[" + threadName + "] visited " + pageId + " (depth " + depth + ", +" + elapsedMs + "ms)");

            try {
                db.insertPage(pageId, result.title, result.excerpt);
                for (String link : result.links) {
                    db.insertLink(pageId, link);
                }
                db.logVisit(runId, pageId, threadName, depth, elapsedMs,
                        result.statusCode,
                        result.links == null ? "" : String.join("; ", result.links),
                        pageId,
                        result.title);
                successfulVisits.incrementAndGet();
            } catch (Exception e) {
                System.err.println("DB write failed for " + pageId + ": " + e.getMessage());
            }

            if (listener != null) {
                listener.onVisit(pageId, threadName, depth, elapsedMs, result.links);
            }

            if (depth < maxDepth) {
                for (String link : result.links) {
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

    public int getVisitedCount() {
        return successfulVisits.get();
    }
}
