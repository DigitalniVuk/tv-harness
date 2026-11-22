package tv.harness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Single plug-in point for the harness.
 *
 * <p>To point this app at any website, an agent edits ONLY this file
 * (see docs/AGENT_GUIDE.md). Every other class reads its site-specific
 * values from here; there is no site-specific code anywhere else.
 *
 * <p>Values below are TODO placeholders, not a real site. Nothing in the
 * app will work until they are filled in. Keep the placeholder host
 * (example.com) in the repo so no real site is endorsed by the template.
 */
public final class SiteConfig {

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /** Shown in the top bar. Replace with the site's public name. */
    public static final String DISPLAY_NAME = "TV Harness";

    /**
     * Origin of the website, no trailing slash, https only.
     * Used for catalogue fetch, Referer, relative URL resolution, and
     * validating URLs opened in the player.
     *
     * TODO: e.g. "https://www.example.com"
     */
    public static final String BASE_URL = "https://example.com";

    /** Home page inside the player WebView (used when playback has no URL). */
    public static final String HOME_PATH = "/home";

    /**
     * Browsers rarely identify as a TV, so the harness presents a mobile
     * Android UA. Change only if the site rejects it.
     */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; AFTSS) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

    // ------------------------------------------------------------------
    // Catalogue mode & tabs (the "endpoints" the app must fill)
    //
    // CATALOG_MODE:
    //   "html"     -> scrapes balanced <div> blocks via ITEM_* rules below
    //   "json_api" -> parses JSON API responses via JSON_* field mappings
    // ------------------------------------------------------------------

    public static final String CATALOG_MODE = "html";

    /** When true, clicking a catalogue card opens native DetailActivity before WatchActivity. */
    public static final boolean SHOW_DETAIL_SCREEN = false;

    public static final class Tab {
        final String id;
        final String label;
        final String path;
        final String mediaType;

        private Tab(String id, String label, String path) {
            this(id, label, path, "");
        }

        private Tab(String id, String label, String path, String mediaType) {
            this.id = id;
            this.label = label;
            this.path = path;
            this.mediaType = mediaType == null ? "" : mediaType;
        }
    }

    /** Landing view: a row of tab panels rendered from these paths. */
    public static final List<Tab> TABS = Collections.unmodifiableList(Arrays.asList(
            new Tab("home", "HOME", "/latest-updated"),
            new Tab("updated", "RECENT", "/latest-updated"),
            new Tab("popular", "TOP", "/most-viewed"),
            new Tab("genres", "GENRES", null)
    ));

    // ------------------------------------------------------------------
    // Category discovery (the "genres" tab)
    // ------------------------------------------------------------------

    public static final String CATEGORY_DISCOVERY_PATH = "/home";
    public static final String CATEGORY_PATH_PREFIX = "/genre/";

    // ------------------------------------------------------------------
    // JSON API catalogue configuration (used when CATALOG_MODE = "json_api")
    // ------------------------------------------------------------------

    public static final String JSON_RESULTS_KEY = "results";
    public static final String JSON_ID_KEY = "id";
    public static final String JSON_TITLE_KEY = "title";
    public static final String JSON_ALT_TITLE_KEY = "name";
    public static final String JSON_POSTER_KEY = "poster_path";
    public static final String JSON_BACKDROP_KEY = "backdrop_path";
    public static final String JSON_OVERVIEW_KEY = "overview";
    public static final String JSON_DATE_KEY = "release_date";
    public static final String JSON_ALT_DATE_KEY = "first_air_date";
    public static final String JSON_RATING_KEY = "vote_average";
    public static final String JSON_MEDIA_TYPE_KEY = "media_type";
    public static final String JSON_IMAGE_PREFIX = "";
    public static final String JSON_BACKDROP_PREFIX = "";
    public static final String JSON_MOVIE_URL_TEMPLATE = "";
    public static final String JSON_TV_URL_TEMPLATE = "";
    public static final String JSON_GENRE_LIST_PATH = "";
    public static final String JSON_GENRE_ARRAY_KEY = "genres";
    public static final String JSON_GENRE_ID_KEY = "id";
    public static final String JSON_GENRE_NAME_KEY = "name";
    public static final String JSON_GENRE_ITEMS_PATH_TEMPLATE = "";

    /** Optional search API path containing {query}; empty disables native search tab. */
    public static final String SEARCH_API_PATH_TEMPLATE = "";

    /** Optional TV detail & season episode JSON paths containing {id} and {season}. */
    public static final String TV_DETAIL_PATH_TEMPLATE = "";
    public static final String TV_SEASON_PATH_TEMPLATE = "";

    // ------------------------------------------------------------------
    // HTML item parsing (used when CATALOG_MODE = "html")
    // ------------------------------------------------------------------

    /** CSS class marking one item block in the listing HTML. */
    public static final String ITEM_CLASS = "item";

    /** Item links contain this path fragment, e.g. "/watch/". */
    public static final String ITEM_LINK_PATH = "/watch/";

    /** Anchor classes that hold the item title (comma separated). */
    public static final String ITEM_TITLE_CLASSES = "name,d-title";

    /** Regex matched against the item's HTML; group 1 is the type label. */
    public static final String ITEM_TYPE_REGEX =
            "(?is)<div[^>]*class=[\"'][^\"']*right[^\"']*[\"'][^>]*>(.*?)</div>";

    /** Regex for the rating text (shown after a middle dot). */
    public static final String ITEM_RATING_REGEX =
            "(?is)class=[\"'][^\"']*rated[^\"']*[\"'][^>]*>.*?<span[^>]*>(.*?)</span>";

    /** Regex for the episode/status count text (shown after "Ep "). */
    public static final String ITEM_EPISODES_REGEX =
            "(?is)class=[\"'][^\"']*ep-status sub[^\"']*[\"'][^>]*>.*?<span[^>]*>(.*?)</span>";

    /** Fallback label when ITEM_TYPE_REGEX does not match. */
    public static final String ITEM_DEFAULT_TYPE = "Item";

    // ------------------------------------------------------------------
    // Player (WebView playback screen)
    // ------------------------------------------------------------------

    /** CSS selectors to hide (site navigation, footers, comments…). */
    public static final String PLAYER_HIDE_CSS =
            "body>header,header,#footer,.footer,.sidebar,.breadcrumb,"
            + ".share-buttons,.comment,.comments";

    /** CSS selectors that must stay visible and fill the player area. */
    public static final String PLAYER_KEEP_SELECTORS =
            "body,#watch-main,.watch-container,.aside-wrapper,aside.main,"
            + "#w-media,#w-player";

    /** Main player element; harness centres it in 16:9. */
    public static final String PLAYER_ELEMENT_SELECTOR = "#w-player";

    /** Player iframe/video element inside PLAYER_ELEMENT_SELECTOR. */
    public static final String PLAYER_FRAME_SELECTOR = "#player iframe,#player video";

    /** First element the harness focuses on page load (e.g. play button). */
    public static final String PLAYER_FOCUS_SELECTOR = "#player .play";

    /** Episode list container; episodes are its <a> children. */
    public static final String EPISODE_LIST_SELECTOR = "#w-episodes .episodes a, #w-episodes a[data-num]";

    /** Currently-playing episode (inside EPISODE_LIST_SELECTOR). */
    public static final String EPISODE_ACTIVE_SELECTOR = "#w-episodes a.active";

    /** Attribute holding the episode number (fallbacks to text content). */
    public static final String EPISODE_NUM_ATTR = "data-num";

    /** Previous/next episode buttons. */
    public static final String PREV_EPISODE_SELECTOR = ".ctrl.prev";
    public static final String NEXT_EPISODE_SELECTOR = ".ctrl.next";

    /** Autoplay toggle (its state also lives in localStorage auto_play). */
    public static final String AUTOPLAY_SELECTOR = "#controls .auto-play";

    /** Click target that toggles play/pause; empty = direct <video> / Space key. */
    public static final String PAUSE_TOGGLE_SELECTOR = "";

    // ------------------------------------------------------------------
    // Direct-embed player & stuck-media watchdog (optional)
    //
    // When non-empty, WatchActivity navigates directly to the embed URL
    // (avoiding cross-origin iframe control barriers) and fails over
    // automatically across servers if playback stalls.
    // Templates support {id}, {season}, {episode}.
    // ------------------------------------------------------------------

    public static final String[] DIRECT_EMBED_MOVIE_URLS = {};
    public static final String[] DIRECT_EMBED_TV_URLS = {};
    public static final String[] DIRECT_EMBED_LABELS = {};
    public static final String DIRECT_EMBED_REFERER = "";
    public static final String DIRECT_EMBED_ID_REGEX = "";
    public static final String DIRECT_EMBED_TV_MARKER = "";

    /** Milliseconds without video progress before failing over to next server. */
    public static final int STALL_TIMEOUT_MS = 16000;

    /** Automatically unmute HTML5 <video> when playback begins. */
    public static final boolean AUTO_UNMUTE = true;

    // ------------------------------------------------------------------
    // Host allowlists & blocking rules (also feed AdBlocker)
    // ------------------------------------------------------------------

    /**
     * Hosts belonging to the site and its embed/API servers. Never ad-blocked
     * (except by BLOCKED_URL_SUBSTRINGS). When STRICT_MAIN_FRAME_ALLOWLIST is
     * true, main-frame navigations to any host outside this list are blocked.
     */
    public static final String[] FIRST_PARTY_HOSTS = {
            "example.com", "www.example.com"
    };

    /** Block any top-level WebView navigation whose host is not in FIRST_PARTY_HOSTS. */
    public static final boolean STRICT_MAIN_FRAME_ALLOWLIST = false;

    /** Additional ad/popunder/tracker domains beyond assets/blocking-domains.txt. */
    public static final String[] EXTRA_BLOCKED_DOMAINS = {};

    /** TLDs (without leading dot, lowercase) to block for non-media third-party requests. */
    public static final String[] BLOCKED_TLDS = {};

    /**
     * URL substrings blocked unconditionally (evaluated BEFORE FIRST_PARTY_HOSTS),
     * e.g. anti-devtool redirect scripts or push-ad service workers.
     */
    public static final String[] BLOCKED_URL_SUBSTRINGS = {};

    /**
     * Hosts poster/backdrop images may be loaded from. Images from other hosts
     * are silently skipped (blocking keeps the UI fast and private).
     */
    public static final String[] ALLOWED_IMAGE_PREFIXES = {
            "https://cdn.example.com/"
    };

    private SiteConfig() {}
}
