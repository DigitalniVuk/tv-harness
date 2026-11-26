# Architecture

## App flow

`CatalogActivity` is the launcher screen. It fetches every configured
tab page (`SiteConfig.TABS`) plus the category discovery page over HTTPS
on a single background executor. `CatalogScraper` parses each page's HTML
into `CatalogItem`/`CatalogCategory` records without running website
JavaScript; all endpoints, classes, and regexes come from `SiteConfig`.
The activity builds native Android TV cards and loads posters on a small
worker pool.

Selecting an item opens `WatchActivity` with the item URL. That activity
hosts the site's player page in a WebView, customises it with CSS built
from `SiteConfig` player selectors, and supplies native, focusable
controls for the TV remote (pause, prev/next, section picker, autoplay,
reload, browse). `AdBlocker` filters WebView navigation and resource
requests against the bundled host list and URL path rules, never blocking
hosts in `SiteConfig.FIRST_PARTY_HOSTS`; known media file paths always
pass so filtering cannot break streams.

## Refresh and caching

Refresh reloads every tab and the category page. Catalogue requests send
no-cache headers, disable URLConnection caching, and include a unique
query parameter to avoid stale intermediary responses. Refresh also
clears the activity's in-memory poster bitmap cache. Poster downloads use
no-cache headers and run off the UI thread.

## Main components

| Component | Responsibility |
| --- | --- |
| `SiteConfig` | **The only site-specific file.** Endpoints, item selectors, player hooks, host allowlists. Edit only this to plug in a website (see `docs/AGENT_GUIDE.md`). |
| `CatalogActivity` | Catalogue UI, scrollable tab bar, Continue Watching shelf, `RECENT` tab, optional native search, remote navigation, refresh, multi-target fan-out poster loading. Tabs from `SiteConfig`. |
| `DetailActivity` | Optional native TV detail screen (`SiteConfig.SHOW_DETAIL_SCREEN`) with poster, backdrop, overview, Resume / Start Over / Play CTA, and TV season/episode pickers with progress indicators. |
| `CatalogScraper` | Site-agnostic HTTPS fetching and HTML or JSON API extraction (with 429 retry and deduplication). |
| `CatalogItem` | Title, item URL, poster/backdrop URLs, details, media ID/type, overview. |
| `CatalogCategory` | Category label and path. |
| `WatchHistoryStore` | Local `SharedPreferences` + JSON persistence for watch progress, Continue Watching, Recently Watched, and preferred subtitle language. |
| `SubtitleEngine` | Site-agnostic subtitle API track discovery, language normalization/deduplication, and WebVTT/SRT cue parser with binary-search lookup. |
| `WatchActivity` | WebView playback, direct-embed server failover, generation-gated `<video>` watchdog, HLS-resilient auto-resume, native 1080p subtitle overlay + `CC` picker, chrome hiding, and native remote OSD. |
| `AdBlocker` | Ad domain/TLD/URL-substring checks, strict main-frame allowlisting, and subresource CDN allowlisting from `SiteConfig`. |
| `App` | Process-level application context for bundled filter loading. |

## Boundaries

- The app needs internet access only and no user account.
- Catalogue pages are parsed as HTML data; their scripts are not run in
  the catalogue screen.
- The playback screen must load the configured website's player, so site
  behaviour and playback availability remain controlled by that site and
  its providers.
- The bundled ad list is a build-time snapshot; it is not automatically
  updated while the app runs.
- Nothing site-specific exists outside `SiteConfig.java`
  (`scripts/check-site-refs.sh` enforces this).
