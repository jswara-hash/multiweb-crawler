package scraper;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles robots.txt discovery, fetching, caching, and rule evaluation.
 * Respects standard robots.txt exclusion rules:
 *   - User-agent matching (specific "BTechMiniProjectCrawler" or wildcard "*")
 *   - Disallow and Allow directives
 *   - Crawl-delay directive (if specified)
 * Pure Java implementation with zero external dependencies.
 */
public class RobotsTxtHandler {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static final String CRAWLER_USER_AGENT = "BTechMiniProjectCrawler";

    // Cache parsed rules per origin (e.g. "https://en.wikipedia.org" -> HostRules)
    private static final Map<String, HostRules> CACHE = new ConcurrentHashMap<>();

    public static class HostRules {
        public final List<String> disallow = new ArrayList<>();
        public final List<String> allow = new ArrayList<>();
        public int crawlDelayMs = -1;
        public final boolean present;

        public HostRules(boolean present) {
            this.present = present;
        }

        public boolean isAllowed(String path) {
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            // Check if an Allow rule explicitly permits this path
            int bestAllowLen = -1;
            for (String a : allow) {
                if (path.startsWith(a) && a.length() > bestAllowLen) {
                    bestAllowLen = a.length();
                }
            }

            int bestDisallowLen = -1;
            for (String d : disallow) {
                if (path.startsWith(d) && d.length() > bestDisallowLen) {
                    bestDisallowLen = d.length();
                }
            }

            if (bestDisallowLen >= 0) {
                // If allow rule is at least as specific (prefix length >= disallow), allow
                return bestAllowLen >= bestDisallowLen;
            }
            return true;
        }
    }

    /**
     * Checks whether the given URL is allowed by the site's robots.txt rules.
     */
    public static boolean isAllowed(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        try {
            URI uri = URI.create(url);
            String origin = getOrigin(uri);
            if (origin == null) return true;

            HostRules rules = getRulesForOrigin(origin);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
                path = path + "?" + uri.getQuery();
            }
            return rules.isAllowed(path);
        } catch (Exception e) {
            // Malformed URL or error - default to allow
            return true;
        }
    }

    /**
     * Returns the recommended crawl delay in milliseconds, or -1 if not specified.
     */
    public static int getCrawlDelayMs(String url) {
        if (url == null || url.trim().isEmpty()) return -1;
        try {
            URI uri = URI.create(url);
            String origin = getOrigin(uri);
            if (origin == null) return -1;
            HostRules rules = getRulesForOrigin(origin);
            return rules.crawlDelayMs;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String getOrigin(URI uri) {
        if (uri.getHost() == null || uri.getScheme() == null) return null;
        int port = uri.getPort();
        String portPart = (port != -1 && port != 80 && port != 443) ? ":" + port : "";
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + portPart;
    }

    private static HostRules getRulesForOrigin(String origin) {
        return CACHE.computeIfAbsent(origin, k -> fetchAndParse(origin));
    }

    private static HostRules fetchAndParse(String origin) {
        String robotsUrl = origin + "/robots.txt";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(robotsUrl))
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "Mozilla/5.0 (compatible; " + CRAWLER_USER_AGENT + "/1.0)")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                return parseRobotsTxt(response.body());
            }
        } catch (Exception e) {
            // Server has no robots.txt or request timed out - permit crawling
        }
        return new HostRules(false);
    }

    public static HostRules parseRobotsTxt(String content) {
        HostRules wildcardRules = new HostRules(true);
        HostRules specificRules = new HostRules(true);
        boolean hasSpecificRules = false;

        boolean inWildcard = false;
        boolean inSpecific = false;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                int hashIdx = line.indexOf('#');
                if (hashIdx >= 0) {
                    line = line.substring(0, hashIdx).trim();
                }
                if (line.isEmpty()) continue;

                int colonIdx = line.indexOf(':');
                if (colonIdx <= 0) continue;

                String key = line.substring(0, colonIdx).trim().toLowerCase();
                String value = line.substring(colonIdx + 1).trim();

                if ("user-agent".equals(key)) {
                    String agent = value.toLowerCase();
                    if ("*".equals(agent)) {
                        inWildcard = true;
                        inSpecific = false;
                    } else if (agent.contains(CRAWLER_USER_AGENT.toLowerCase())) {
                        inSpecific = true;
                        hasSpecificRules = true;
                        inWildcard = false;
                    } else {
                        inWildcard = false;
                        inSpecific = false;
                    }
                } else if ("disallow".equals(key)) {
                    if (!value.isEmpty()) {
                        if (inSpecific) specificRules.disallow.add(value);
                        else if (inWildcard) wildcardRules.disallow.add(value);
                    }
                } else if ("allow".equals(key)) {
                    if (!value.isEmpty()) {
                        if (inSpecific) specificRules.allow.add(value);
                        else if (inWildcard) wildcardRules.allow.add(value);
                    }
                } else if ("crawl-delay".equals(key)) {
                    try {
                        double sec = Double.parseDouble(value);
                        int ms = (int) (sec * 1000);
                        if (inSpecific) specificRules.crawlDelayMs = ms;
                        else if (inWildcard) wildcardRules.crawlDelayMs = ms;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}

        return hasSpecificRules ? specificRules : wildcardRules;
    }

    /**
     * Clear the cache (for testing or reset).
     */
    public static void clearCache() {
        CACHE.clear();
    }
}
