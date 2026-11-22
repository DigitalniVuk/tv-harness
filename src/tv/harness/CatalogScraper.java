package tv.harness;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Fetches catalogue data without running website scripts. Supports both
 * server-rendered HTML blocks and JSON API endpoints, driven entirely by
 * {@link SiteConfig}.
 */
final class CatalogScraper {
    static final class TvEpisode {
        final int season;
        final int episode;
        final String name;
        final String overview;
        final int runtimeMinutes;

        TvEpisode(int season, int episode, String name, String overview, int runtimeMinutes) {
            this.season = season;
            this.episode = episode;
            this.name = name == null ? "" : name;
            this.overview = overview == null ? "" : overview;
            this.runtimeMinutes = runtimeMinutes;
        }
    }

    private static final Pattern DIV = Pattern.compile("(?is)<(/?)div\\b([^>]*)>");
    private static final Pattern ATTR = Pattern.compile(
            "(?is)([a-z_:][-a-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern WATCH_ANCHOR = Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>");
    private static final Pattern IMG = Pattern.compile("(?is)<img\\b([^>]*)>");
    private static final Pattern ITEM_CLASS = Pattern.compile(
            "(?is)\\bclass\\s*=\\s*(['\"])[^'\"]*\\b" + Pattern.quote(SiteConfig.ITEM_CLASS) + "\\b[^'\"]*\\1");
    private static final Pattern ITEM_TYPE = compile(SiteConfig.ITEM_TYPE_REGEX);
    private static final Pattern ITEM_RATING = compile(SiteConfig.ITEM_RATING_REGEX);
    private static final Pattern ITEM_EPISODES = compile(SiteConfig.ITEM_EPISODES_REGEX);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String[] ITEM_TITLE_TOKENS = parseTitleTokens(SiteConfig.ITEM_TITLE_CLASSES);

    private static String[] parseTitleTokens(String classList) {
        if (classList == null || classList.isEmpty()) return new String[0];
        String[] parts = classList.split(",");
        String[] tokens = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            tokens[i] = parts[i].trim();
        }
        return tokens;
    }

    private static Pattern compile(String regex) {
        return regex == null || regex.isEmpty() ? null : Pattern.compile(regex);
    }

    private static boolean isJsonMode() {
        return "json_api".equalsIgnoreCase(SiteConfig.CATALOG_MODE);
    }

    private CatalogScraper() {}

    /** Fetches and parses one listing page (SiteConfig tab or category path). */
    static List<CatalogItem> fetchItems(String path) throws Exception {
        return fetchItems(path, "");
    }

    /** Fetches and parses one listing page with an optional default media type ("movie"/"tv"). */
    static List<CatalogItem> fetchItems(String path, String defaultMediaType) throws Exception {
        if (isJsonMode()) {
            String body = fetchText(path, false, "application/json,text/plain,*/*");
            return parseJsonItems(body, defaultMediaType);
        }
        String html = fetchText(path, true, "text/html,application/xhtml+xml");
        LinkedHashMap<String, CatalogItem> deduped = new LinkedHashMap<>();
        for (String block : itemBlocks(html)) {
            CatalogItem item = parseHtmlItem(block);
            if (item != null && !deduped.containsKey(item.watchUrl)) {
                deduped.put(item.watchUrl, item);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    /** Searches the catalogue via SEARCH_API_PATH_TEMPLATE if configured. */
    static List<CatalogItem> searchItems(String query) throws Exception {
        if (SiteConfig.SEARCH_API_PATH_TEMPLATE == null
                || SiteConfig.SEARCH_API_PATH_TEMPLATE.isEmpty()) {
            return new ArrayList<>();
        }
        String encoded = URLEncoder.encode(query.trim(), "UTF-8");
        String path = SiteConfig.SEARCH_API_PATH_TEMPLATE.replace("{query}", encoded);
        String body = fetchText(path, false, "application/json,text/plain,*/*");
        return parseJsonItems(body, "");
    }

    /** Discovers category links or JSON genres. */
    static List<CatalogCategory> fetchCategories() throws Exception {
        if (isJsonMode() && SiteConfig.JSON_GENRE_LIST_PATH != null
                && !SiteConfig.JSON_GENRE_LIST_PATH.isEmpty()) {
            return fetchJsonCategories();
        }
        String html = fetchText(SiteConfig.CATEGORY_DISCOVERY_PATH, true, "text/html,application/xhtml+xml");
        LinkedHashMap<String, CatalogCategory> categories = new LinkedHashMap<>();
        Matcher links = WATCH_ANCHOR.matcher(html);
        while (links.find()) {
            Map<String, String> attrs = attributes(links.group(1));
            String href = attrs.get("href");
            if (href == null) continue;
            String path = href;
            if (href.startsWith(SiteConfig.BASE_URL)) path = href.substring(SiteConfig.BASE_URL.length());
            if (!path.startsWith(SiteConfig.CATEGORY_PATH_PREFIX)) continue;
            String slug = path.substring(SiteConfig.CATEGORY_PATH_PREFIX.length()).split("[/?#]", 2)[0];
            if (slug.isEmpty() || slug.contains("%")) continue;
            String name = plainText(links.group(2));
            if (name.isEmpty()) name = slug.replace('-', ' ');
            categories.put(slug, new CatalogCategory(name, SiteConfig.CATEGORY_PATH_PREFIX + slug));
        }
        return new ArrayList<>(categories.values());
    }

    /** Fetches available season numbers (> 0) for a TV show when TV_DETAIL_PATH_TEMPLATE is set. */
    static List<Integer> fetchTvSeasons(String mediaId) throws Exception {
        List<Integer> out = new ArrayList<>();
        if (mediaId == null || mediaId.isEmpty()
                || SiteConfig.TV_DETAIL_PATH_TEMPLATE == null
                || SiteConfig.TV_DETAIL_PATH_TEMPLATE.isEmpty()) {
            return out;
        }
        String path = SiteConfig.TV_DETAIL_PATH_TEMPLATE.replace("{id}", mediaId);
        String body = fetchText(path, false, "application/json,text/plain,*/*");
        JSONObject root = new JSONObject(body);
        JSONArray seasons = root.optJSONArray("seasons");
        if (seasons != null) {
            for (int i = 0; i < seasons.length(); i++) {
                JSONObject s = seasons.optJSONObject(i);
                if (s == null) continue;
                int num = s.optInt("season_number", 0);
                if (num > 0) out.add(num);
            }
        }
        if (out.isEmpty()) {
            int count = root.optInt("number_of_seasons", 1);
            for (int i = 1; i <= Math.max(1, count); i++) out.add(i);
        }
        return out;
    }

    /** Fetches episodes for a TV show season when TV_SEASON_PATH_TEMPLATE is set. */
    static List<TvEpisode> fetchTvEpisodes(String mediaId, int season) throws Exception {
        List<TvEpisode> out = new ArrayList<>();
        if (mediaId == null || mediaId.isEmpty()
                || SiteConfig.TV_SEASON_PATH_TEMPLATE == null
                || SiteConfig.TV_SEASON_PATH_TEMPLATE.isEmpty()) {
            return out;
        }
        String path = SiteConfig.TV_SEASON_PATH_TEMPLATE
                .replace("{id}", mediaId)
                .replace("{season}", String.valueOf(season));
        String body = fetchText(path, false, "application/json,text/plain,*/*");
        JSONObject root = new JSONObject(body);
        JSONArray eps = root.optJSONArray("episodes");
        if (eps == null) return out;
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            int epNum = e.optInt("episode_number", i + 1);
            String name = e.optString("name", "Episode " + epNum);
            String overview = e.optString("overview", "");
            int runtime = e.optInt("runtime", 0);
            out.add(new TvEpisode(season, epNum, name, overview, runtime));
        }
        return out;
    }

    private static List<CatalogCategory> fetchJsonCategories() throws Exception {
        String body = fetchText(SiteConfig.JSON_GENRE_LIST_PATH, false, "application/json,text/plain,*/*");
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray(SiteConfig.JSON_GENRE_ARRAY_KEY);
        List<CatalogCategory> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString(SiteConfig.JSON_GENRE_ID_KEY, "");
            String name = obj.optString(SiteConfig.JSON_GENRE_NAME_KEY, "").trim();
            if (id.isEmpty() || name.isEmpty()) continue;
            String path = SiteConfig.JSON_GENRE_ITEMS_PATH_TEMPLATE.replace("{id}", id);
            out.add(new CatalogCategory(name, path));
        }
        return out;
    }

    private static List<CatalogItem> parseJsonItems(String body, String defaultMediaType) throws Exception {
        List<CatalogItem> out = new ArrayList<>();
        JSONArray arr;
        String trimmed = body.trim();
        if (trimmed.startsWith("[")) {
            arr = new JSONArray(trimmed);
        } else {
            JSONObject root = new JSONObject(trimmed);
            arr = root.optJSONArray(SiteConfig.JSON_RESULTS_KEY);
        }
        if (arr == null) return out;

        LinkedHashMap<String, CatalogItem> deduped = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;

            String mediaType = optNonEmpty(obj, SiteConfig.JSON_MEDIA_TYPE_KEY);
            if (mediaType.isEmpty()) {
                if (defaultMediaType != null && !defaultMediaType.isEmpty()) {
                    mediaType = defaultMediaType;
                } else if (obj.has(SiteConfig.JSON_ALT_TITLE_KEY) && !obj.has(SiteConfig.JSON_TITLE_KEY)) {
                    mediaType = "tv";
                } else {
                    mediaType = "movie";
                }
            }
            if ("person".equalsIgnoreCase(mediaType)) continue;

            String id = optNonEmpty(obj, SiteConfig.JSON_ID_KEY);
            String title = optNonEmpty(obj, SiteConfig.JSON_TITLE_KEY);
            if (title.isEmpty()) title = optNonEmpty(obj, SiteConfig.JSON_ALT_TITLE_KEY);
            if (id.isEmpty() || title.isEmpty()) continue;

            String posterRaw = optNonEmpty(obj, SiteConfig.JSON_POSTER_KEY);
            String posterUrl = resolveJsonImage(posterRaw, SiteConfig.JSON_IMAGE_PREFIX);

            String backdropRaw = optNonEmpty(obj, SiteConfig.JSON_BACKDROP_KEY);
            String backdropUrl = resolveJsonImage(backdropRaw, SiteConfig.JSON_BACKDROP_PREFIX);

            String overview = optNonEmpty(obj, SiteConfig.JSON_OVERVIEW_KEY);
            String date = optNonEmpty(obj, SiteConfig.JSON_DATE_KEY);
            if (date.isEmpty()) date = optNonEmpty(obj, SiteConfig.JSON_ALT_DATE_KEY);
            String year = date.length() >= 4 ? date.substring(0, 4) : "";

            String rating = "";
            if (obj.has(SiteConfig.JSON_RATING_KEY) && !obj.isNull(SiteConfig.JSON_RATING_KEY)) {
                double val = obj.optDouble(SiteConfig.JSON_RATING_KEY, 0.0);
                if (val > 0.0) rating = String.format(Locale.US, "%.1f", val);
            }

            String typeLabel = "tv".equalsIgnoreCase(mediaType) ? "TV" : "Movie";
            StringBuilder details = new StringBuilder(typeLabel);
            if (!year.isEmpty()) details.append("  ·  ").append(year);
            if (!rating.isEmpty()) details.append("  ·  ★ ").append(rating);

            String template = "tv".equalsIgnoreCase(mediaType)
                    ? SiteConfig.JSON_TV_URL_TEMPLATE
                    : SiteConfig.JSON_MOVIE_URL_TEMPLATE;
            String watchPath = template.replace("{id}", id);
            String watchUrl = absolute(watchPath);

            String key = mediaType + ":" + id;
            if (!deduped.containsKey(key)) {
                deduped.put(key, new CatalogItem(
                        title, watchUrl, posterUrl, details.toString(),
                        id, mediaType.toLowerCase(Locale.ROOT), overview,
                        backdropUrl, year, rating));
            }
        }
        out.addAll(deduped.values());
        return out;
    }

    private static String optNonEmpty(JSONObject obj, String key) {
        if (key == null || key.isEmpty() || !obj.has(key) || obj.isNull(key)) return "";
        String v = obj.optString(key, "").trim();
        return "null".equalsIgnoreCase(v) ? "" : v;
    }

    private static String resolveJsonImage(String raw, String prefix) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.startsWith("http")) return raw;
        if (raw.startsWith("//")) return "https:" + raw;
        if (prefix != null && !prefix.isEmpty()) return prefix + raw;
        return absolute(raw);
    }

    private static String fetchText(String path, boolean cacheBust, String accept) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return fetchTextOnce(path, cacheBust, accept);
            } catch (RateLimitException e) {
                lastError = e;
                try {
                    Thread.sleep(1100L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw lastError != null ? lastError : new Exception("Request failed");
    }

    private static final class RateLimitException extends Exception {
        private static final long serialVersionUID = 1L;
        RateLimitException(String msg) { super(msg); }
    }

    private static String fetchTextOnce(String path, boolean cacheBust, String accept) throws Exception {
        String requestPath = path;
        if (cacheBust) {
            int query = path.indexOf('?');
            String separator = query < 0 ? "?" : "&";
            requestPath += separator + "__tv_refresh=" + System.nanoTime();
        }
        URL url = new URL(path.startsWith("http") ? requestPath : SiteConfig.BASE_URL + requestPath);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setUseCaches(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", SiteConfig.USER_AGENT);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
        connection.setRequestProperty("Referer", SiteConfig.BASE_URL + "/");
        connection.setRequestProperty("Origin", SiteConfig.BASE_URL);
        if (cacheBust) {
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
            connection.setRequestProperty("Pragma", "no-cache");
        }
        try {
            int code = connection.getResponseCode();
            if (code == 429) throw new RateLimitException("Site returned HTTP 429");
            if (code < 200 || code >= 300) throw new Exception("Site returned HTTP " + code);
            String encoding = connection.getContentEncoding();
            InputStream rawIn = connection.getInputStream();
            InputStream in = ("gzip".equalsIgnoreCase(encoding))
                    ? new GZIPInputStream(rawIn)
                    : rawIn;
            try (InputStream stream = in;
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
                return new String(out.toByteArray(), Charset.forName("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<String> itemBlocks(String html) {
        List<String> out = new ArrayList<>();
        Matcher tags = DIV.matcher(html);
        int start = -1, depth = 0;
        while (tags.find()) {
            boolean close = !tags.group(1).isEmpty();
            if (start < 0) {
                if (!close && ITEM_CLASS.matcher(tags.group(2)).find()) {
                    start = tags.start();
                    depth = 1;
                }
                continue;
            }
            if (close) depth--;
            else depth++;
            if (depth == 0) {
                out.add(html.substring(start, tags.end()));
                start = -1;
            }
        }
        return out;
    }

    private static CatalogItem parseHtmlItem(String block) {
        Matcher anchors = WATCH_ANCHOR.matcher(block);
        String watchUrl = null, title = null;
        while (anchors.find()) {
            Map<String, String> attrs = attributes(anchors.group(1));
            String href = attrs.get("href");
            if (href == null || !href.contains(SiteConfig.ITEM_LINK_PATH)) continue;
            if (watchUrl == null) watchUrl = absolute(href);
            String classes = attrs.get("class");
            if (classes != null && hasTitleClass(classes)) {
                title = plainText(anchors.group(2));
                break;
            }
            if (title == null) title = plainText(anchors.group(2));
        }
        if (watchUrl == null || title == null || title.isEmpty()) return null;

        String poster = null;
        Matcher images = IMG.matcher(block);
        if (images.find()) {
            Map<String, String> attrs = attributes(images.group(1));
            poster = attrs.get("data-src");
            if (poster == null || poster.isEmpty()) poster = attrs.get("src");
            poster = poster == null ? null : absolute(poster);
        }

        String details = SiteConfig.ITEM_DEFAULT_TYPE;
        if (ITEM_TYPE != null) {
            Matcher type = ITEM_TYPE.matcher(block);
            if (type.find()) details = plainText(type.group(1));
        }
        if (ITEM_RATING != null) {
            Matcher rating = ITEM_RATING.matcher(block);
            if (rating.find()) details += "  ·  " + plainText(rating.group(1));
        }
        if (ITEM_EPISODES != null) {
            Matcher episodes = ITEM_EPISODES.matcher(block);
            if (episodes.find()) details += "  ·  Ep " + plainText(episodes.group(1));
        }
        return new CatalogItem(title, watchUrl, poster, details);
    }

    private static boolean hasTitleClass(String classes) {
        for (String token : ITEM_TITLE_TOKENS) {
            if (!token.isEmpty() && classes.contains(token)) return true;
        }
        return false;
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = ATTR.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            out.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return out;
    }

    private static String absolute(String value) {
        if (value.startsWith("//")) return "https:" + value;
        if (value.startsWith("/")) return SiteConfig.BASE_URL + value;
        return value;
    }

    private static String plainText(String html) {
        String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
        return WHITESPACE.matcher(text.replace('\u00a0', ' ')).replaceAll(" ").trim();
    }
}
