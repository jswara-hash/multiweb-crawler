package crawler;

import scraper.RobotsTxtHandler;
import scraper.WebScraper;

/**
 * PageFetcher backed by a real website. "Fetching" a page means an actual HTTP
 * request via WebScraper, followed by a politeness delay so a demo crawl
 * doesn't hammer the target site with rapid requests.
 */
public class RealPageFetcher implements PageFetcher {

    private final int politenessDelayMs;

    public RealPageFetcher(int politenessDelayMs) {
        this.politenessDelayMs = politenessDelayMs;
    }

    @Override
    public FetchResult fetch(String pageId) throws Exception {
        WebScraper.PageData page = WebScraper.fetch(pageId);

        int delay = politenessDelayMs;
        int robotsDelay = RobotsTxtHandler.getCrawlDelayMs(pageId);
        if (robotsDelay > delay) {
            delay = robotsDelay;
        }

        if (delay > 0) {
            Thread.sleep(delay);
        }

        return new FetchResult(page.title, page.links, page.excerpt, page.statusCode, page.responseTimeMs);
    }
}
