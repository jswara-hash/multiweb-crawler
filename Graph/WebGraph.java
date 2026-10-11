package graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Holds the full simulated web: a map of pageId -> Page.
 * Provides two ways to build a graph:
 *   1. A small hardcoded graph (with a cycle) for correctness testing.
 *   2. A randomly generated graph for stress-testing multithreading.
 */
public class WebGraph {
    private final Map<String, Page> pages = new LinkedHashMap<>();

    public void addPage(Page page) {
        pages.put(page.getId(), page);
    }

    public Page getPage(String id) {
        return pages.get(id);
    }

    public int size() {
        return pages.size();
    }

    public Map<String, Page> getAllPages() {
        return pages;
    }

    /**
     * Small, fixed graph for correctness testing.
     * Includes a cycle (P1 -> P2 -> P4 -> P1) to prove the crawler
     * doesn't loop forever or revisit pages.
     */
    public static WebGraph buildTestGraph() {
        WebGraph graph = new WebGraph();

        Page p1 = new Page("P1", "Home");
        Page p2 = new Page("P2", "About");
        Page p3 = new Page("P3", "Blog");
        Page p4 = new Page("P4", "Contact");
        Page p5 = new Page("P5", "Blog Post 1");
        Page p6 = new Page("P6", "Blog Post 2");

        p1.addLink("P2");
        p1.addLink("P3");
        p2.addLink("P4");
        p3.addLink("P5");
        p3.addLink("P6");
        p4.addLink("P1"); // cycle back to home
        p5.addLink("P3"); // cycle back to blog

        graph.addPage(p1);
        graph.addPage(p2);
        graph.addPage(p3);
        graph.addPage(p4);
        graph.addPage(p5);
        graph.addPage(p6);

        return graph;
    }

    /**
     * Randomly generated graph for performance/demo testing.
     *
     * @param numPages       total pages to create (e.g. 100)
     * @param maxLinksPerPage max outbound links per page (e.g. 5)
     */
    public static WebGraph buildRandomGraph(int numPages, int maxLinksPerPage) {
        WebGraph graph = new WebGraph();
        Random random = new Random();

        // First create all pages
        for (int i = 1; i <= numPages; i++) {
            graph.addPage(new Page("P" + i, "Page " + i));
        }

        // Then randomly link them to each other
        for (int i = 1; i <= numPages; i++) {
            Page current = graph.getPage("P" + i);
            int numLinks = 1 + random.nextInt(maxLinksPerPage);
            for (int j = 0; j < numLinks; j++) {
                int targetIndex = random.nextInt(numPages) + 1;
                current.addLink("P" + targetIndex);
            }
        }

        return graph;
    }

    /** Quick manual check that the graph looks right. */
    public static void main(String[] args) {
        System.out.println("=== Test graph ===");
        WebGraph testGraph = buildTestGraph();
        for (Page page : testGraph.getAllPages().values()) {
            System.out.println(page);
        }

        System.out.println("\n=== Random graph (10 pages) ===");
        WebGraph randomGraph = buildRandomGraph(10, 4);
        for (Page page : randomGraph.getAllPages().values()) {
            System.out.println(page);
        }
    }
}
