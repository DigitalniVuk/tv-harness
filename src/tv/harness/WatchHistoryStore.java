package tv.harness;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight SharedPreferences + JSON persistence for watch history,
 * Continue Watching progress, Recently Watched listings, and preferred
 * subtitle language. Site-agnostic; no external dependencies.
 */
final class WatchHistoryStore {
    private static final String PREFS_NAME = "tv_harness_history";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_PREF_SUB_LANG = "pref_sub_lang";
    private static final int MAX_ENTRIES = 60;
    private static final double MIN_RESUME_SEC = 10.0;
    private static final double MIN_DURATION_SEC = 60.0;
    private static final double COMPLETE_RATIO = 0.92;

    private static int changeVersion = 0;

    static final class Entry {
        final String key;
        final String mediaId;
        final String mediaType;
        final String title;
        final String posterUrl;
        final String backdropUrl;
        final String detailUrl;
        final String overview;
        final String details;
        final String year;
        final String rating;
        final int season;
        final int episode;
        final String episodeTitle;
        final double positionSec;
        final double durationSec;
        final long updatedAt;
        final boolean completed;
        final int serverIdx;

        Entry(String key, String mediaId, String mediaType, String title,
              String posterUrl, String backdropUrl, String detailUrl,
              String overview, String details, String year, String rating,
              int season, int episode, String episodeTitle,
              double positionSec, double durationSec, long updatedAt,
              boolean completed, int serverIdx) {
            this.key = nonNull(key);
            this.mediaId = nonNull(mediaId);
            this.mediaType = nonNull(mediaType).isEmpty() ? "movie" : mediaType.toLowerCase(Locale.ROOT);
            this.title = nonNull(title);
            this.posterUrl = emptyToNull(posterUrl);
            this.backdropUrl = emptyToNull(backdropUrl);
            this.detailUrl = nonNull(detailUrl);
            this.overview = nonNull(overview);
            this.details = nonNull(details);
            this.year = nonNull(year);
            this.rating = nonNull(rating);
            this.season = Math.max(1, season);
            this.episode = Math.max(1, episode);
            this.episodeTitle = nonNull(episodeTitle);
            this.positionSec = Math.max(0.0, positionSec);
            this.durationSec = Math.max(0.0, durationSec);
            this.updatedAt = updatedAt;
            this.completed = completed;
            this.serverIdx = Math.max(0, serverIdx);
        }

        boolean isInProgress() {
            return !completed && positionSec >= MIN_RESUME_SEC && durationSec > MIN_DURATION_SEC;
        }

        boolean isTv() {
            return "tv".equalsIgnoreCase(mediaType);
        }

        float progressFraction() {
            if (completed) return 1.0f;
            if (durationSec <= 0.0) return 0.0f;
            return (float) Math.max(0.0, Math.min(1.0, positionSec / durationSec));
        }

        String remainingLabel() {
            if (completed) return "Watched";
            double rem = Math.max(0.0, durationSec - positionSec);
            int totalMin = (int) Math.round(rem / 60.0);
            if (totalMin >= 60) {
                int h = totalMin / 60;
                int m = totalMin % 60;
                return h + "h " + m + "m left";
            }
            if (totalMin >= 1) return totalMin + "m left";
            return "<1m left";
        }

        String cardSubtitle(boolean continueMode) {
            StringBuilder sb = new StringBuilder();
            if (isTv()) {
                sb.append("S").append(season).append(" E").append(episode).append("  ·  ");
            }
            if (completed) {
                sb.append("✓ Watched");
            } else if (continueMode) {
                sb.append(formatTime(positionSec)).append("  ·  ").append(remainingLabel());
            } else if (positionSec >= MIN_RESUME_SEC) {
                sb.append(formatTime(positionSec)).append(" / ").append(formatTime(durationSec));
            } else if (!details.isEmpty()) {
                return details;
            } else {
                sb.append(isTv() ? "TV" : "Movie");
            }
            return sb.toString();
        }

        CatalogItem toCatalogItem() {
            String itemDetails = !details.isEmpty() ? details : cardSubtitle(false);
            return new CatalogItem(title, detailUrl, posterUrl, itemDetails,
                    mediaId, mediaType, overview, backdropUrl, year, rating);
        }
    }

    private WatchHistoryStore() {}

    static synchronized int version() {
        return changeVersion;
    }

    static String canonicalKey(String mediaType, String mediaId, String detailUrl) {
        if (mediaId != null && !mediaId.trim().isEmpty()) {
            String t = (mediaType == null || mediaType.trim().isEmpty())
                    ? "movie" : mediaType.trim().toLowerCase(Locale.ROOT);
            return t + ":" + mediaId.trim();
        }
        return detailUrl == null ? "" : detailUrl.trim();
    }

    static boolean isCompletedPosition(double positionSec, double durationSec) {
        return durationSec > MIN_DURATION_SEC && (positionSec / durationSec) >= COMPLETE_RATIO;
    }

    static String formatTime(double seconds) {
        int total = (int) Math.max(0, Math.floor(seconds));
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    static synchronized void saveProgress(
            String mediaId, String mediaType, String title,
            String posterUrl, String backdropUrl, String detailUrl,
            String overview, String details, String year, String rating,
            int season, int episode, String episodeTitle,
            double positionSec, double durationSec, int serverIdx) {
        String key = canonicalKey(mediaType, mediaId, detailUrl);
        if (key.isEmpty() || title == null || title.trim().isEmpty()) return;
        if (durationSec <= MIN_DURATION_SEC || positionSec < MIN_RESUME_SEC) return;

        List<Entry> entries = loadAllLocked();
        Entry existing = null;
        for (int i = 0; i < entries.size(); i++) {
            if (key.equals(entries.get(i).key)) {
                existing = entries.remove(i);
                break;
            }
        }

        // Preserve previously saved artwork/overview if caller didn't pass them
        String finalPoster = !isEmpty(posterUrl) ? posterUrl : (existing != null ? existing.posterUrl : null);
        String finalBackdrop = !isEmpty(backdropUrl) ? backdropUrl : (existing != null ? existing.backdropUrl : null);
        String finalOverview = !isEmpty(overview) ? overview : (existing != null ? existing.overview : "");
        String finalDetails = !isEmpty(details) ? details : (existing != null ? existing.details : "");
        String finalYear = !isEmpty(year) ? year : (existing != null ? existing.year : "");
        String finalRating = !isEmpty(rating) ? rating : (existing != null ? existing.rating : "");
        String finalEpTitle = !isEmpty(episodeTitle) ? episodeTitle
                : (existing != null && existing.season == season && existing.episode == episode
                ? existing.episodeTitle : "");

        boolean completed = isCompletedPosition(positionSec, durationSec);
        Entry updated = new Entry(
                key, mediaId, mediaType, title.trim(),
                finalPoster, finalBackdrop, detailUrl,
                finalOverview, finalDetails, finalYear, finalRating,
                season, episode, finalEpTitle,
                positionSec, durationSec, System.currentTimeMillis(),
                completed, serverIdx);

        entries.add(0, updated);
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        persistAllLocked(entries);
        changeVersion++;
    }

    static synchronized Entry get(String key) {
        if (key == null || key.isEmpty()) return null;
        for (Entry e : loadAllLocked()) {
            if (key.equals(e.key)) return e;
        }
        return null;
    }

    static synchronized List<Entry> getContinueWatching() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : loadAllLocked()) {
            if (e.isInProgress()) out.add(e);
        }
        return out;
    }

    static synchronized List<Entry> getRecentlyWatched() {
        return loadAllLocked();
    }

    static synchronized void remove(String key) {
        if (key == null || key.isEmpty()) return;
        List<Entry> entries = loadAllLocked();
        boolean removed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (key.equals(entries.get(i).key)) {
                entries.remove(i);
                removed = true;
            }
        }
        if (removed) {
            persistAllLocked(entries);
            changeVersion++;
        }
    }

    static synchronized void clearAll() {
        prefs().edit().remove(KEY_ENTRIES).apply();
        changeVersion++;
    }

    /**
     * Preferred subtitle language code/label (e.g. "en", "english", "es"),
     * "off" when explicitly disabled, or "" when unset.
     */
    static synchronized String getPreferredSubLang() {
        String val = prefs().getString(KEY_PREF_SUB_LANG, "");
        return val == null ? "" : val.trim();
    }

    static synchronized void setPreferredSubLang(String lang) {
        String clean = lang == null ? "" : lang.trim();
        prefs().edit().putString(KEY_PREF_SUB_LANG, clean).apply();
    }

    private static SharedPreferences prefs() {
        return App.context().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static List<Entry> loadAllLocked() {
        List<Entry> out = new ArrayList<>();
        String raw = prefs().getString(KEY_ENTRIES, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String key = o.optString("key", "");
                String title = o.optString("title", "");
                if (key.isEmpty() || title.isEmpty()) continue;
                out.add(new Entry(
                        key,
                        o.optString("mediaId", ""),
                        o.optString("mediaType", "movie"),
                        title,
                        o.optString("posterUrl", ""),
                        o.optString("backdropUrl", ""),
                        o.optString("detailUrl", ""),
                        o.optString("overview", ""),
                        o.optString("details", ""),
                        o.optString("year", ""),
                        o.optString("rating", ""),
                        o.optInt("season", 1),
                        o.optInt("episode", 1),
                        o.optString("episodeTitle", ""),
                        o.optDouble("positionSec", 0.0),
                        o.optDouble("durationSec", 0.0),
                        o.optLong("updatedAt", 0L),
                        o.optBoolean("completed", false),
                        o.optInt("serverIdx", 0)));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    private static void persistAllLocked(List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            try {
                JSONObject o = new JSONObject();
                o.put("key", e.key);
                o.put("mediaId", e.mediaId);
                o.put("mediaType", e.mediaType);
                o.put("title", e.title);
                o.put("posterUrl", nonNull(e.posterUrl));
                o.put("backdropUrl", nonNull(e.backdropUrl));
                o.put("detailUrl", e.detailUrl);
                o.put("overview", e.overview);
                o.put("details", e.details);
                o.put("year", e.year);
                o.put("rating", e.rating);
                o.put("season", e.season);
                o.put("episode", e.episode);
                o.put("episodeTitle", e.episodeTitle);
                o.put("positionSec", e.positionSec);
                o.put("durationSec", e.durationSec);
                o.put("updatedAt", e.updatedAt);
                o.put("completed", e.completed);
                o.put("serverIdx", e.serverIdx);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs().edit().putString(KEY_ENTRIES, arr.toString()).apply();
    }

    private static String nonNull(String v) {
        return v == null ? "" : v;
    }

    private static String emptyToNull(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static boolean isEmpty(String v) {
        return v == null || v.trim().isEmpty();
    }
}
