package crawler;

import db.DatabaseManager;
import scraper.WebScraper;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step C: same BFS traversal pattern as SingleThreadedCrawler, but crawls
 * REAL pages over the network instead of a simulated in-memory graph.
 * The graph isn't known ahead of time - it's discovered page by page, as
 * each fetch reveals its own outbound links.
 *
 * politenessDelayMs is an extra wait added after each real fetch, on top
 * of whatever the actual network response took - this is standard crawler
 * etiquette, so a small demo crawl doesn't hammer a real site with rapid
 * requests.
 */
public class RealSingleThreadedCrawler {

    private final DatabaseManager db;
    private final int maxDepth;
    private final int maxPages;
    private final int politenessDelayMs;
    private final ProgressListener listener;
    private final Set<String> visited = new HashSet<>();

    public RealSingleThreadedCrawler(DatabaseManager db, int maxDepth, int maxPages,
                                      int politenessDelayMs, ProgressListener listener) {
        this.db = db;
        this.maxDepth = maxDepth;
        this.maxPages = maxPages;
        this.politenessDelayMs = politenessDelayMs;
        this.listener = listener;
    }

    public void crawl(String seedUrl, int runId) throws SQLException {
        long startTime = System.currentTimeMillis();
        Deque<CrawlItem> queue = new ArrayDeque<>();
        queue.add(new CrawlItem(seedUrl, 0));
        visited.add(seedUrl);

        while (!queue.isEmpty() && visited.size() <= maxPages) {
            CrawlItem item = queue.poll();

            WebScraper.PageData page;
            try {
                page = WebScraper.fetch(item.url);
            } catch (Exception e) {
                System.out.println("[main] failed to fetch " + item.url + ": " + e.getMessage());
                continue; // one bad page shouldn't stop the whole crawl
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[main] visited " + item.url + " (depth " + item.depth + ", +" + elapsed + "ms)");

            db.insertPage(item.url, page.title);
            for (String link : page.links) {
                db.insertLink(item.url, link);
            }
            db.logVisit(runId, item.url, "main", item.depth, elapsed);

            if (listener != null) {
                listener.onVisit(item.url, "main", item.depth, elapsed, page.links);
            }

            politeWait();

            if (item.depth < maxDepth) {
                for (String link : page.links) {
                    if (!visited.contains(link) && visited.size() < maxPages) {
                        visited.add(link);
                        queue.add(new CrawlItem(link, item.depth + 1));
                    }
                }
            }
        }

        System.out.println("Real crawl finished. Total pages visited: " + visited.size());
    }

    public int getVisitedCount() {
        return visited.size();
    }

    private void politeWait() {
        if (politenessDelayMs <= 0) return;
        try {
            Thread.sleep(politenessDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class CrawlItem {
        final String url;
        final int depth;

        CrawlItem(String url, int depth) {
            this.url = url;
            this.depth = depth;
        }
    }
}
