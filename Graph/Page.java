package graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single "page" in our simulated web.
 * Each page has an id, a fake title, and a list of ids it links to.
 */
public class Page {
    private final String id;
    private final String title;
    private final List<String> links; // ids of pages this page links to

    public Page(String id, String title) {
        this.id = id;
        this.title = title;
        this.links = new ArrayList<>();
    }

    public void addLink(String targetPageId) {
        if (!links.contains(targetPageId) && !targetPageId.equals(this.id)) {
            links.add(targetPageId);
        }
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getLinks() {
        return links;
    }

    @Override
    public String toString() {
        return id + " (" + title + ") -> " + links;
    }
}
