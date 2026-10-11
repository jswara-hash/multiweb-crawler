# Multithreaded Web Crawler + SQL Backend + Real Web Scraping

A pure-Java project that crawls either a simulated "fake internet" (a randomly
generated graph of pages) or a REAL website, both single-threaded and
multi-threaded, records everything to a SQL database via JDBC, and shows it
all live in a browser control panel with real-time thread-by-thread
animation.

## What this demonstrates

- **Concurrency**: a thread pool of worker threads crawling pages in parallel,
  with a thread-safe visited set (no duplicate visits) and correct
  termination detection.
- **Graph traversal**: BFS-based crawling with depth limiting and correct
  cycle handling.
- **JDBC / SQL**: all crawl results are persisted to a SQLite database,
  auto-created on first run - no manual SQL setup needed.
- **Real web scraping**: fetches actual pages over HTTP (java.net.http.HttpClient),
  extracts real titles/links AND a short text excerpt (2nd-3rd real
  paragraphs, nav/date junk filtered out) via regex, same-domain
  restricted, with a politeness delay - all pure Java, no external
  libraries.
- **A live control panel**: an embedded HTTP server (com.sun.net.httpserver)
  lets you trigger crawls from a browser button and watch threads claim
  pages in real time, instead of reading a static report.
- **Clean design**: a `PageFetcher` interface abstracts "how do I get one
  page" so a single `GenericCrawler` class handles simulated AND real
  crawling, single-threaded AND multi-threaded, just by swapping the
  fetcher and thread count.

## Folder structure

```
your-project-folder/
├── sqlite-jdbc-3.53.4.0.jar   - SQLite JDBC driver (download separately)
├── graph/
│   ├── Page.java               - a single simulated page: id, title, links
│   └── WebGraph.java           - builds test/random simulated graphs
├── db/
│   └── DatabaseManager.java    - JDBC access; auto-creates schema on first run
├── crawler/
│   ├── PageFetcher.java             - interface: "how to fetch one page"
│   ├── SimulatedPageFetcher.java    - fetches from the in-memory graph
│   ├── RealPageFetcher.java         - fetches a real page over HTTP
│   ├── GenericCrawler.java          - ONE crawler for single/multi + sim/real
│   │                                  (this is what the live server uses)
│   ├── ProgressListener.java        - callback for live per-page progress
│   ├── ComparisonRunner.java        - command-line: runs single vs multi, times it
│   │
│   ├── SingleThreadedCrawler.java       } original standalone versions -
│   ├── MultiThreadedCrawler.java        } kept as a working fallback,
│   ├── RealSingleThreadedCrawler.java   } not used by the server, but
│   └── RealMultiThreadedCrawler.java    } fully functional on their own
│
├── scraper/
│   └── WebScraper.java         - real HTTP fetch + title/link extraction
├── export/
│   └── JsonExporter.java       - builds report JSON from the DB
├── server/
│   └── CrawlServer.java        - embedded HTTP server (the main way to use this project)
└── report/
    ├── live-report.html         - the live control panel (open via the server)
    └── report.html              - static report template (used by JsonExporter
                                    for the older, non-live workflow)
```

## Setup (one-time)

1. Download the SQLite JDBC driver jar from
   https://github.com/xerial/sqlite-jdbc/releases and place it in your
   project's root folder.
2. Nothing else to install. `crawler.db` and its tables are created
   automatically the first time anything runs.

## How to run (recommended: the live control panel)

**1. Compile everything:**
```
javac -cp sqlite-jdbc-3.53.4.0.jar graph\*.java db\*.java crawler\*.java export\*.java server\*.java scraper\*.java
```

**2. Start the server:**
```
java -cp .;sqlite-jdbc-3.53.4.0.jar server.CrawlServer
```

**3. Open your browser to:** `http://localhost:8080`

**4. Choose a source and click Run crawl:**
- **Simulated graph** - crawls a randomly generated fake graph (set page
  count, threads, max depth, artificial per-page latency)
- **Real website** - crawls an actual site (set a seed URL, e.g.
  `https://en.wikipedia.org/wiki/Web_crawler`; capped at 60 pages max,
  same-domain only, with a politeness delay between requests)

You'll see the graph draw itself live as threads claim pages, colored by
which thread visited each one, with a toggle to compare the single-threaded
pass against the multi-threaded pass.

## Accessing the scraped/crawled data directly

Everything gets written to `crawler.db` (a normal SQLite file), regardless
of whether it came from a simulated or real crawl:

- **`pages`** - every page's id (URL for real crawls, "P1" style for
  simulated), its title, and (for real crawls only) a short text `excerpt`
  pulled from the page's own body paragraphs
- **`links`** - every discovered link relationship (from_page -> to_page)
- **`crawl_log`** - every single visit event: run id, page, which thread,
  depth, elapsed time
- **`crawl_runs`** - one row per run: mode (single/multi), thread count,
  total time, pages visited

Three ways to look at this data:
1. **Open `crawler.db` in DB Browser for SQLite** (free tool) - browse the
   tables visually, or run your own SQL queries against it.
2. **Visit `http://localhost:8080/api/results` in a browser** while the
   server is running - returns the latest run's full data as raw JSON.
3. **Use it from your own Java code** - `DatabaseManager` in `db/` gives you
   a plain JDBC `Connection` you can query however you like.

## Individual pieces (optional, command-line testing)

- `java -cp .;sqlite-jdbc-3.53.4.0.jar crawler.SingleThreadedCrawler`
- `java -cp .;sqlite-jdbc-3.53.4.0.jar crawler.MultiThreadedCrawler`
- `java -cp .;sqlite-jdbc-3.53.4.0.jar crawler.ComparisonRunner`
- `java -cp .;sqlite-jdbc-3.53.4.0.jar export.JsonExporter` (generates
  `report/crawl-report.html` from the latest DB run - the older,
  non-live workflow, kept for reference)

## Key design points (good talking points for a viva)

- **No duplicate visits under concurrency**: `ConcurrentHashMap.newKeySet()`
  for the visited set; its atomic `add()` means two threads racing for the
  same page can never both succeed.
- **Correct termination detection**: an `AtomicInteger` tracks in-flight
  tasks; the crawl only signals "done" when it hits zero, avoiding the race
  where the task queue looks momentarily empty but more work is about to be
  added.
- **`threadCount = 1` legitimately IS single-threaded**: a fixed thread pool
  with one worker processes its internal queue strictly FIFO, so execution
  order equals submission order - identical to a plain BFS queue loop, just
  built on the same machinery as the multi-threaded case.
- **Simulated network latency**: an artificial delay represents the network
  wait a real crawler experiences - this is what gives multithreading
  something real to overlap. Without it, there's no idle time for a thread
  pool to fill, and speedup measures close to 1.0x.
- **Real scraping stays safe/polite**: same-domain only, non-HTML links
  filtered, page cap enforced, and a minimum delay between requests.
- **Excerpt extraction skips junk paragraphs**: only paragraphs at least
  40 characters long qualify (filters out nav labels, dates, single
  links), and the 2nd/3rd qualifying paragraph is preferred over the 1st
  (which is often an intro fragment or infobox leftover).
- **Schema migrations happen automatically**: `DatabaseManager` uses
  `CREATE TABLE IF NOT EXISTS` plus a guarded `ALTER TABLE` for new
  columns (like `excerpt`), so an existing `crawler.db` from an earlier
  version of the project upgrades itself the next time it runs - no
  manual DB reset needed.
- **SQL used only for data recording**: all schema creation and querying
  happens from Java - no manual SQL scripts or CLI steps required to run
  the project.
