package scraper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step C: fetches a REAL page over HTTP and extracts its title and outbound
 * links, using only java.net.http.HttpClient (built into the JDK since Java 11)
 * - no external HTML parsing library, so the project stays pure Java.
 * Extraction is done with regex, which is fragile compared to a real HTML
 * parser but good enough for a demo against reasonably clean pages (e.g.
 * Wikipedia).
 *
 * Safety / politeness, by design: - Only links on the SAME domain as the seed
 * are kept, so a crawl can't wander off across the entire internet. - A short
 * delay is expected between requests (enforced by the caller, see
 * RealSingleThreadedCrawler / RealMultiThreadedCrawler), so the target site
 * isn't hammered with rapid concurrent requests. - A descriptive User-Agent
 * identifies the crawler. - Non-HTML links (images, PDFs, mailto:, javascript:,
 * anchors) are filtered out before being returned.
 */
public class WebScraper {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern TITLE_PATTERN
            = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern LINK_PATTERN
            = Pattern.compile("<a\\s[^>]*href\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern PARAGRAPH_PATTERN
            = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private static final int MAX_LINKS_PER_PAGE = 10;
    private static final int MIN_PARAGRAPH_LENGTH = 40; // skips short nav/date junk
    private static final int MAX_EXCERPT_LENGTH = 500;

    /**
     * Holds the result of fetching one real page.
     */
    public static class PageData {

        public final String url;
        public final String title;
        public final List<String> links;
        public final String excerpt;
        public final int statusCode;
        public final long responseTimeMs;

        public PageData(String url, String title, List<String> links, String excerpt, int statusCode, long responseTimeMs) {
            this.url = url;
            this.title = title;
            this.links = links;
            this.excerpt = excerpt;
            this.statusCode = statusCode;
            this.responseTimeMs = responseTimeMs;
        }
    }

    /**
     * Fetches one page and extracts its title and same-domain links. Throws on
     * any network/parsing failure - callers should catch and skip the page
     * rather than let one bad page crash the whole crawl.
     */
    public static PageData fetch(String url) throws Exception {
        if (!RobotsTxtHandler.isAllowed(url)) {
            throw new java.io.IOException("Access restricted by robots.txt: " + url);
        }

        long start = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 (compatible; " + RobotsTxtHandler.CRAWLER_USER_AGENT + "/1.0)")
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        long responseTimeMs = System.currentTimeMillis() - start;
        if (statusCode == 401 || statusCode == 403) {
            throw new java.io.IOException("HTTP " + statusCode + " Access Restricted/Forbidden for: " + url);
        } else if (statusCode == 404) {
            throw new java.io.IOException("HTTP 404 Page Not Found: " + url);
        } else if (statusCode == 429) {
            throw new java.io.IOException("HTTP 429 Rate Limit Exceeded: " + url);
        } else if (statusCode < 200 || statusCode >= 300) {
            throw new java.io.IOException("HTTP " + statusCode + " error fetching: " + url);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.isEmpty() && !contentType.toLowerCase().contains("text/html")
                && !contentType.toLowerCase().contains("application/xhtml")) {
            throw new java.io.IOException("Non-HTML Content-Type (" + contentType + ") for: " + url);
        }

        String html = response.body();

        String title = extractTitle(html);
        List<String> links = extractLinks(html, url);
        String excerpt = extractExcerpt(html);

        return new PageData(url, title, links, excerpt, statusCode, responseTimeMs);
    }

    private static String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html);
        if (matcher.find()) {
            return stripTags(matcher.group(1));
        }
        return "(no title)";
    }

    /**
     * Extracts links, resolves relative URLs against the page's own URL, keeps
     * only same-domain http(s) links, filters out non-HTML targets, checks
     * robots.txt allowances, and caps the count so one page can't explode the
     * crawl.
     */
    private static List<String> extractLinks(String html, String pageUrl) {
        String baseHost;
        URI baseUri;
        try {
            baseUri = URI.create(pageUrl);
            baseHost = baseUri.getHost();
        } catch (Exception e) {
            return new ArrayList<>();
        }

        Set<String> found = new LinkedHashSet<>(); // preserves order, no duplicates
        Matcher matcher = LINK_PATTERN.matcher(html);

        while (matcher.find() && found.size() < MAX_LINKS_PER_PAGE) {
            String raw = matcher.group(1).trim();
            if (raw.isEmpty() || raw.startsWith("javascript:") || raw.startsWith("mailto:")) {
                continue;
            }

            try {
                URI resolved = baseUri.resolve(raw);
                String scheme = resolved.getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    continue;
                }
                if (baseHost == null || !baseHost.equalsIgnoreCase(resolved.getHost())) {
                    continue; // stay on the same domain
                }
                if (looksLikeNonHtmlFile(resolved.getPath())) {
                    continue;
                }

                // Normalize: include port if non-standard, path, query.
                String portPart = (resolved.getPort() != -1 && resolved.getPort() != 80 && resolved.getPort() != 443)
                        ? ":" + resolved.getPort() : "";
                String pathPart = resolved.getPath() == null ? "" : resolved.getPath();
                String queryPart = (resolved.getQuery() != null && !resolved.getQuery().isEmpty())
                        ? "?" + resolved.getQuery() : "";
                String clean = resolved.getScheme() + "://" + resolved.getHost() + portPart + pathPart + queryPart;

                // Respect robots.txt before adding link to crawl candidates
                if (!RobotsTxtHandler.isAllowed(clean)) {
                    continue;
                }

                found.add(clean);
            } catch (Exception ignored) {
                // Malformed URL - skip it, don't let one bad link stop extraction.
            }
        }

        return new ArrayList<>(found);
    }

    /**
     * Extracts a short text excerpt from the page's 2nd-3rd real paragraphs.
     * Skips very short paragraphs (nav labels, dates, single links) since those
     * aren't representative body content. Falls back gracefully if a page
     * doesn't have enough qualifying paragraphs.
     */
    private static String extractExcerpt(String html) {
        Matcher matcher = PARAGRAPH_PATTERN.matcher(html);
        List<String> qualifyingParagraphs = new ArrayList<>();

        while (matcher.find() && qualifyingParagraphs.size() < 3) {
            String raw = matcher.group(1);
            String plain = stripTags(raw);
            if (plain.length() >= MIN_PARAGRAPH_LENGTH) {
                qualifyingParagraphs.add(plain);
            }
        }

        if (qualifyingParagraphs.isEmpty()) {
            return "(no excerpt found)";
        }

        // Prefer the 2nd and 3rd qualifying paragraphs (index 1, 2) - the
        // 1st is often an intro fragment or infobox leftover. Fall back to
        // whatever is available if the page has fewer than that.
        StringBuilder excerpt = new StringBuilder();
        int start = qualifyingParagraphs.size() > 1 ? 1 : 0;
        for (int i = start; i < qualifyingParagraphs.size(); i++) {
            if (excerpt.length() > 0) {
                excerpt.append(" ");
            }
            excerpt.append(qualifyingParagraphs.get(i));
        }

        String result = excerpt.toString();
        if (result.length() > MAX_EXCERPT_LENGTH) {
            result = result.substring(0, MAX_EXCERPT_LENGTH) + "…";
        }
        return result;
    }

    /**
     * Removes HTML tags and decodes the handful of common entities.
     */
    private static String stripTags(String html) {
        String noTags = TAG_PATTERN.matcher(html).replaceAll("");
        return noTags
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean looksLikeNonHtmlFile(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".svg")
                || lower.endsWith(".zip") || lower.endsWith(".mp4") || lower.endsWith(".css")
                || lower.endsWith(".js");
    }
}
