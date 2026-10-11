package crawler;

import graph.Page;
import graph.WebGraph;

/**
 * PageFetcher backed by the simulated in-memory graph. "Fetching" a page
 * means looking it up in the graph and waiting an artificial delay to
 * represent the network latency a real crawler would experience.
 */
public class SimulatedPageFetcher implements PageFetcher {

    private final WebGraph graph;
    private final int simulatedLatencyMs;

    public SimulatedPageFetcher(WebGraph graph, int simulatedLatencyMs) {
        this.graph = graph;
        this.simulatedLatencyMs = simulatedLatencyMs;
    }

    @Override
    public FetchResult fetch(String pageId) throws Exception {
        Page page = graph.getPage(pageId);
        if (page == null) {
            throw new Exception("Page '" + pageId + "' does not exist in the simulated graph.");
        }

        if (simulatedLatencyMs > 0) {
            Thread.sleep(simulatedLatencyMs);
        }

        return new FetchResult(page.getTitle(), page.getLinks());
    }
}
