package crawler;

import java.util.List;

/**
 * Abstracts "how do I get one page's title and links" so a single crawler
 * implementation can work against either a simulated in-memory graph or a real
 * website, without knowing which.
 *
 * fetch() is expected to also handle any waiting the source needs - simulated
 * latency (Thread.sleep) or a real network round-trip plus a politeness delay -
 * so the crawler calling it doesn't need to know which kind of source it's
 * talking to.
 */
public interface PageFetcher {

    /**
     * Result of fetching one page: its title, links, and a short text excerpt.
     */
    class FetchResult {

        public final String title;
        public final List<String> links;
        public final String excerpt;
        public final int statusCode;
        public final long responseTimeMs;

        /**
         * Backward-compatible: simulated fetches have no real text, so excerpt
         * is null.
         */
        public FetchResult(String title, List<String> links) {
            this(title, links, null, 200, 0L);
        }

        public FetchResult(String title, List<String> links, String excerpt) {
            this(title, links, excerpt, 200, 0L);
        }

        public FetchResult(String title, List<String> links, String excerpt, int statusCode, long responseTimeMs) {
            this.title = title;
            this.links = links;
            this.excerpt = excerpt;
            this.statusCode = statusCode;
            this.responseTimeMs = responseTimeMs;
        }
    }

    /**
     * Fetches one page by id (a simulated "P1"-style id, or a real URL). Throws
     * if the page can't be found/fetched - the crawler is expected to catch
     * this, log it, and skip the page rather than abort the crawl.
     */
    FetchResult fetch(String pageId) throws Exception;
}
