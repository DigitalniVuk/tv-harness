package tv.harness;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Site-agnostic subtitle track discovery and WebVTT/SRT cue parser.
 * Fetches tracks from {@link SiteConfig#SUBTITLE_API_MOVIE_URLS} /
 * {@link SiteConfig#SUBTITLE_API_TV_URLS} (supporting both array and wrapped
 * JSON schemas) and parses WebVTT/SRT cues for native TV rendering.
 */
final class SubtitleEngine {
    static final class Track {
        final String id;
        final String displayName;
        final String langCode;
        final String url;
        final boolean forced;
        final boolean html5Fallback;
        final int html5Index;

        Track(String id, String displayName, String langCode, String url,
              boolean forced, boolean html5Fallback, int html5Index) {
            this.id = id;
            this.displayName = displayName;
            this.langCode = langCode;
            this.url = url;
            this.forced = forced;
            this.html5Fallback = html5Fallback;
            this.html5Index = html5Index;
        }

        String shortBadge() {
            if (langCode != null && !langCode.isEmpty()) {
                return langCode.toUpperCase(Locale.ROOT);
            }
            if (displayName.length() >= 2) {
                return displayName.substring(0, 2).toUpperCase(Locale.ROOT);
            }
            return "ON";
        }
    }

    static final class Cue {
        final double startSec;
        final double endSec;
        final String text;

        Cue(double startSec, double endSec, String text) {
            this.startSec = startSec;
            this.endSec = endSec;
            this.text = text;
        }
    }

    private static final int MAX_VARIANTS_PER_LANG = 3;
    private static final Pattern TIME_LINE = Pattern.compile(
            "(\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{2,3})\\s*-->\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{2,3})");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SPAM_CUE = Pattern.compile("(?i)watch\\s+free\\b.*\\bat\\s+\\S+");

    private static final Map<String, String> LANG_NAME_TO_CODE = buildLangMap();

    private static Map<String, String> buildLangMap() {
        Map<String, String> m = new HashMap<>();
        m.put("english", "en"); m.put("en", "en"); m.put("eng", "en");
        m.put("spanish", "es"); m.put("español", "es"); m.put("espanol", "es"); m.put("es", "es"); m.put("spa", "es");
        m.put("french", "fr"); m.put("français", "fr"); m.put("francais", "fr"); m.put("fr", "fr"); m.put("fre", "fr");
        m.put("german", "de"); m.put("deutsch", "de"); m.put("de", "de"); m.put("ger", "de");
        m.put("italian", "it"); m.put("italiano", "it"); m.put("it", "it"); m.put("ita", "it");
        m.put("portuguese", "pt"); m.put("português", "pt"); m.put("portugues", "pt"); m.put("pt", "pt"); m.put("por", "pt");
        m.put("dutch", "nl"); m.put("nederlands", "nl"); m.put("nl", "nl");
        m.put("russian", "ru"); m.put("ru", "ru"); m.put("rus", "ru");
        m.put("arabic", "ar"); m.put("ar", "ar"); m.put("ara", "ar");
        m.put("chinese", "zh"); m.put("zh", "zh"); m.put("chi", "zh"); m.put("mandarin", "zh");
        m.put("japanese", "ja"); m.put("ja", "ja"); m.put("jpn", "ja");
        m.put("korean", "ko"); m.put("ko", "ko"); m.put("kor", "ko");
        m.put("polish", "pl"); m.put("polski", "pl"); m.put("pl", "pl");
        m.put("turkish", "tr"); m.put("türkçe", "tr"); m.put("tr", "tr");
        m.put("czech", "cs"); m.put("cs", "cs"); m.put("ces", "cs");
        m.put("danish", "da"); m.put("dansk", "da"); m.put("da", "da");
        m.put("finnish", "fi"); m.put("suomi", "fi"); m.put("fi", "fi");
        m.put("swedish", "sv"); m.put("svenska", "sv"); m.put("sv", "sv");
        m.put("norwegian", "no"); m.put("norsk", "no"); m.put("no", "no");
        m.put("greek", "el"); m.put("el", "el");
        m.put("hebrew", "he"); m.put("he", "he");
        m.put("hindi", "hi"); m.put("hi", "hi");
        m.put("hungarian", "hu"); m.put("hu", "hu");
        m.put("romanian", "ro"); m.put("ro", "ro");
        m.put("croatian", "hr"); m.put("hrvatski", "hr"); m.put("hr", "hr");
        m.put("serbian", "sr"); m.put("srpski", "sr"); m.put("sr", "sr");
        m.put("bosnian", "bs"); m.put("bs", "bs");
        m.put("slovenian", "sl"); m.put("sl", "sl");
        m.put("bulgarian", "bg"); m.put("bg", "bg");
        m.put("ukrainian", "uk"); m.put("uk", "uk");
        m.put("vietnamese", "vi"); m.put("vi", "vi");
        m.put("thai", "th"); m.put("th", "th");
        m.put("indonesian", "id"); m.put("id", "id");
        m.put("malay", "ms"); m.put("ms", "ms");
        m.put("persian", "fa"); m.put("farsi", "fa"); m.put("fa", "fa");
        m.put("bengali", "bn"); m.put("bn", "bn");
        return Collections.unmodifiableMap(m);
    }

    private SubtitleEngine() {}

    /** Fetches and deduplicates available subtitle tracks from configured APIs. */
    static List<Track> fetchTracks(String mediaType, String mediaId, int season, int episode,
                                   String preferredLang) {
        String[] templates = ("tv".equalsIgnoreCase(mediaType) && SiteConfig.SUBTITLE_API_TV_URLS.length > 0)
                ? SiteConfig.SUBTITLE_API_TV_URLS
                : SiteConfig.SUBTITLE_API_MOVIE_URLS;
        if (mediaId == null || mediaId.isEmpty() || templates.length == 0) {
            return Collections.emptyList();
        }

        List<Track> raw = new ArrayList<>();
        for (String tmpl : templates) {
            if (tmpl == null || tmpl.isEmpty()) continue;
            String url = tmpl
                    .replace("{id}", mediaId)
                    .replace("{season}", String.valueOf(season))
                    .replace("{episode}", String.valueOf(episode));
            try {
                String body = httpGet(url);
                if (body == null || body.trim().isEmpty()) continue;
                List<Track> parsed = parseTrackJson(body, url);
                if (!parsed.isEmpty()) {
                    raw.addAll(parsed);
                    // If primary curated endpoint already returned a healthy list (>= 8 tracks), stop here.
                    if (raw.size() >= 8) break;
                }
            } catch (Exception ignored) {
            }
        }
        return normalizeAndSort(raw, preferredLang);
    }

    /** Downloads and parses a WebVTT or SRT subtitle file into sorted Cue entries. */
    static List<Cue> fetchCues(String vttUrl) throws Exception {
        String body = httpGet(encodeUrl(vttUrl));
        if (body == null || body.trim().isEmpty()) return Collections.emptyList();
        return parseVttOrSrt(body);
    }

    /** Binary-search lookup of active caption text at playback timeSec. */
    static String activeTextAt(List<Cue> cues, double timeSec) {
        if (cues == null || cues.isEmpty() || timeSec < 0.0) return "";
        // Find first cue whose startSec > timeSec
        int lo = 0;
        int hi = cues.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startSec <= timeSec) lo = mid + 1;
            else hi = mid;
        }
        // Scan backwards slightly from lo-1 to catch overlapping cues
        int startScan = Math.max(0, lo - 8);
        StringBuilder sb = null;
        for (int i = startScan; i < lo; i++) {
            Cue c = cues.get(i);
            if (c.startSec <= timeSec && timeSec <= c.endSec) {
                if (sb == null) sb = new StringBuilder(c.text);
                else sb.append('\n').append(c.text);
            }
        }
        return sb == null ? "" : sb.toString();
    }

    /** Picks best matching track for a preferred language code/name (excluding forced if full track exists). */
    static Track findBestMatch(List<Track> tracks, String preferredLang) {
        if (tracks == null || tracks.isEmpty() || preferredLang == null) return null;
        String pref = preferredLang.trim().toLowerCase(Locale.ROOT);
        if (pref.isEmpty() || "off".equals(pref)) return null;
        String prefCode = normalizeLangCode(pref);

        Track fallbackForced = null;
        for (Track t : tracks) {
            if (matchesLanguage(t, pref, prefCode)) {
                if (!t.forced) return t;
                if (fallbackForced == null) fallbackForced = t;
            }
        }
        return fallbackForced;
    }

    static boolean matchesLanguage(Track track, String prefLower, String prefCode) {
        if (track == null) return false;
        if (!prefCode.isEmpty() && prefCode.equalsIgnoreCase(track.langCode)) return true;
        String dn = track.displayName.toLowerCase(Locale.ROOT);
        if (dn.equals(prefLower)) return true;
        if (dn.startsWith(prefLower + " ") || dn.startsWith(prefLower + "(")
                || dn.startsWith(prefLower + "-")) {
            return true;
        }
        return false;
    }

    static String normalizeLangCode(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return "";
        String direct = LANG_NAME_TO_CODE.get(clean);
        if (direct != null) return direct;
        // Extract first alphabetic word (prevents "eng" matching inside "Bengali")
        String[] tokens = clean.split("[^a-z\\u00c0-\\u024f]+");
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            String mapped = LANG_NAME_TO_CODE.get(tok);
            if (mapped != null) return mapped;
            break;
        }
        return "";
    }

    private static List<Track> parseTrackJson(String body, String sourceUrl) throws Exception {
        List<Track> out = new ArrayList<>();
        String trimmed = body.trim();
        JSONArray arr;
        if (trimmed.startsWith("[")) {
            arr = new JSONArray(trimmed);
        } else {
            JSONObject root = new JSONObject(trimmed);
            arr = root.optJSONArray("subtitles");
            if (arr == null) arr = root.optJSONArray("tracks");
            if (arr == null) arr = root.optJSONArray("data");
        }
        if (arr == null) return out;

        String origin = extractOrigin(sourceUrl);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String fileUrl = firstNonEmpty(o, "url", "file", "src");
            if (fileUrl.isEmpty()) continue;
            if (fileUrl.startsWith("//")) fileUrl = "https:" + fileUrl;
            else if (fileUrl.startsWith("/")) fileUrl = origin + fileUrl;
            if (!fileUrl.regionMatches(true, 0, "http", 0, 4)) continue;

            String rawLabel = firstNonEmpty(o, "label", "lang", "language", "name");
            if (rawLabel.isEmpty()) rawLabel = "Subtitle " + (i + 1);

            String cleanDisplay = cleanLabel(rawLabel);
            String langCode = normalizeLangCode(rawLabel);
            if (langCode.isEmpty()) langCode = normalizeLangCode(cleanDisplay);
            boolean forced = rawLabel.toLowerCase(Locale.ROOT).contains("forced")
                    || rawLabel.toLowerCase(Locale.ROOT).contains("signs");

            out.add(new Track(
                    encodeUrl(fileUrl),
                    cleanDisplay,
                    langCode,
                    encodeUrl(fileUrl),
                    forced,
                    false,
                    -1));
        }
        return out;
    }

    private static List<Track> normalizeAndSort(List<Track> raw, String preferredLang) {
        if (raw.isEmpty()) return Collections.emptyList();

        // Deduplicate by URL first
        LinkedHashMap<String, Track> byUrl = new LinkedHashMap<>();
        for (Track t : raw) {
            if (!byUrl.containsKey(t.url)) byUrl.put(t.url, t);
        }

        // Cap duplicates per language key so TV D-pad lists stay concise
        Map<String, Integer> langCounts = new HashMap<>();
        List<Track> capped = new ArrayList<>();
        for (Track t : byUrl.values()) {
            String baseName = baseLanguageName(t.displayName);
            String groupKey = !t.langCode.isEmpty() ? t.langCode : baseName.toLowerCase(Locale.ROOT);
            int count = langCounts.containsKey(groupKey) ? langCounts.get(groupKey) : 0;
            if (count >= MAX_VARIANTS_PER_LANG) continue;
            count++;
            langCounts.put(groupKey, count);

            String finalLabel = t.displayName;
            if (count > 1 && finalLabel.equalsIgnoreCase(baseName)) {
                finalLabel = baseName + " " + count;
            }
            capped.add(new Track(t.id, finalLabel, t.langCode, t.url, t.forced, false, -1));
        }

        final String pref = preferredLang == null ? "" : preferredLang.trim().toLowerCase(Locale.ROOT);
        final String prefCode = "off".equals(pref) ? "" : normalizeLangCode(pref);

        Collections.sort(capped, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                int pa = priority(a, pref, prefCode);
                int pb = priority(b, pref, prefCode);
                if (pa != pb) return Integer.compare(pa, pb);
                if (a.forced != b.forced) return a.forced ? 1 : -1;
                return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        return capped;
    }

    private static int priority(Track t, String pref, String prefCode) {
        if (!pref.isEmpty() && !"off".equals(pref) && matchesLanguage(t, pref, prefCode)) {
            return t.forced ? 1 : 0;
        }
        if ("en".equals(t.langCode)) {
            return t.forced ? 3 : 2;
        }
        return 10;
    }

    private static String cleanLabel(String raw) {
        String s = raw.trim();
        // Convert "English - English (CC)" -> "English (CC)" or "French - Français" -> "French"
        int dash = s.indexOf(" - ");
        if (dash > 0) {
            String left = s.substring(0, dash).trim();
            String right = s.substring(dash + 3).trim();
            if (right.toLowerCase(Locale.ROOT).startsWith(left.toLowerCase(Locale.ROOT))) {
                s = right;
            } else if (!left.isEmpty()) {
                s = left;
            }
        }
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0))) {
            s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    private static String baseLanguageName(String displayName) {
        int paren = displayName.indexOf('(');
        int bracket = displayName.indexOf('[');
        int cut = displayName.length();
        if (paren > 0 && paren < cut) cut = paren;
        if (bracket > 0 && bracket < cut) cut = bracket;
        return displayName.substring(0, cut).trim();
    }

    private static List<Cue> parseVttOrSrt(String raw) {
        List<Cue> cues = new ArrayList<>();
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        String[] lines = normalized.split("\n");

        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("NOTE")
                    || line.startsWith("STYLE") || line.startsWith("REGION")) {
                i++;
                continue;
            }

            String cueId = "";
            Matcher m = TIME_LINE.matcher(line);
            if (!m.find()) {
                cueId = line;
                i++;
                if (i >= lines.length) break;
                line = lines[i].trim();
                m = TIME_LINE.matcher(line);
                if (!m.find()) {
                    i++;
                    continue;
                }
            }

            double startSec = parseTimestamp(m.group(1));
            double endSec = parseTimestamp(m.group(2));
            i++;

            StringBuilder textBuf = new StringBuilder();
            while (i < lines.length) {
                String textLine = lines[i];
                if (textLine.trim().isEmpty()) {
                    i++;
                    break;
                }
                if (textBuf.length() > 0) textBuf.append('\n');
                textBuf.append(textLine.trim());
                i++;
            }

            if ("ad-1".equalsIgnoreCase(cueId)) continue;
            String cleanText = cleanCueText(textBuf.toString());
            if (cleanText.isEmpty()) continue;
            if (SPAM_CUE.matcher(cleanText).find()) continue;
            if (endSec > startSec) {
                cues.add(new Cue(startSec, endSec, cleanText));
            }
        }

        Collections.sort(cues, new Comparator<Cue>() {
            @Override
            public int compare(Cue a, Cue b) {
                return Double.compare(a.startSec, b.startSec);
            }
        });
        return cues;
    }

    private static double parseTimestamp(String ts) {
        String clean = ts.trim().replace(',', '.');
        String[] parts = clean.split(":");
        try {
            if (parts.length == 3) {
                double h = Double.parseDouble(parts[0]);
                double m = Double.parseDouble(parts[1]);
                double s = Double.parseDouble(parts[2]);
                return h * 3600.0 + m * 60.0 + s;
            } else if (parts.length == 2) {
                double m = Double.parseDouble(parts[0]);
                double s = Double.parseDouble(parts[1]);
                return m * 60.0 + s;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0.0;
    }

    private static String cleanCueText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String noTags = HTML_TAG.matcher(raw).replaceAll("");
        String decoded = Html.fromHtml(noTags.replace("\n", "<br/>"), Html.FROM_HTML_MODE_LEGACY).toString();
        String[] rawLines = decoded.split("\n");
        StringBuilder out = new StringBuilder();
        for (String rl : rawLines) {
            String t = rl.replace('\u00a0', ' ').trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                String v = o.optString(k, "").trim();
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
            }
        }
        return "";
    }

    private static String extractOrigin(String rawUrl) {
        try {
            URL u = new URL(rawUrl);
            int port = u.getPort();
            return u.getProtocol() + ":" + "//" + u.getHost() + (port > 0 ? (":" + port) : "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String encodeUrl(String url) {
        return url.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D");
    }

    private static String httpGet(String rawUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", SiteConfig.USER_AGENT);
        conn.setRequestProperty("Accept", "application/json,text/vtt,text/plain,*/*");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return "";
            String enc = conn.getContentEncoding();
            InputStream rawIn = conn.getInputStream();
            InputStream in = "gzip".equalsIgnoreCase(enc) ? new GZIPInputStream(rawIn) : rawIn;
            try (InputStream stream = in;
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int count;
                while ((count = stream.read(buf)) != -1) out.write(buf, 0, count);
                return new String(out.toByteArray(), Charset.forName("UTF-8"));
            }
        } finally {
            conn.disconnect();
        }
    }
}
