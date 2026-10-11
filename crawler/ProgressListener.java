package crawler;

import java.util.List;

/**
 * Step B/C: lets a crawler report each page visit the instant it happens,
 * instead of only being visible after the whole crawl finishes. The server
 * uses this to stream live progress to the browser for animation.
 *
 * discoveredLinks lets the listener also learn the page's outbound links
 * as they're found - needed for real web scraping (Step C), where the
 * graph isn't known ahead of time and has to be built live, page by page.
 * For the simulated crawlers the graph is already fully known upfront, but
 * they pass the links too for consistency (it costs nothing extra).
 *
 * Optional - crawlers work fine with no listener (pass null), which is
 * what the plain command-line runs (Day 2-6) still do.
 */
public interface ProgressListener {
    void onVisit(String pageId, String threadName, int depth, long elapsedMs, List<String> discoveredLinks);
}
