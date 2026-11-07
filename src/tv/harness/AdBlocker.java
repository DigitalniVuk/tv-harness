package tv.harness;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight EasyList domain filter with a few media-safe URL checks.
 * First-party hosts come from {@link SiteConfig}; this class is
 * site-agnostic and must not gain any site names.
 */
final class AdBlocker {
    private static String[] FIRST_PARTY = SiteConfig.FIRST_PARTY_HOSTS;
    private static final Set<String> DOMAINS = loadDomains();

    private AdBlocker() {}

    static boolean blocks(String rawUrl) {
        Uri uri;
        try {
            uri = Uri.parse(rawUrl);
        } catch (RuntimeException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String allowed : FIRST_PARTY) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return false;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (isMediaPath(path)) return false;
        if (hasDomainRule(host)) return true;
        return path.contains("/adservice/") || path.contains("/adserver/")
                || path.contains("/popunder/") || path.contains("/clickunder/")
                || path.contains("/advertisement/") || path.contains("/banner-ad/");
    }

    private static boolean isMediaPath(String path) {
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".m4v")
                || path.endsWith(".mpd") || path.endsWith(".m4s") || path.endsWith(".ts")
                || path.endsWith(".vtt") || path.endsWith(".srt") || path.endsWith(".ass")
                || path.endsWith(".aac") || path.endsWith(".m4a");
    }

    private static boolean hasDomainRule(String host) {
        String candidate = host;
        while (candidate.length() > 0) {
            if (DOMAINS.contains(candidate)) return true;
            int dot = candidate.indexOf('.');
            if (dot < 0) return false;
            candidate = candidate.substring(dot + 1);
        }
        return false;
    }

    private static Set<String> loadDomains() {
        // Performance optimization: Pre-allocate initial capacity for ~47,500 entries in
        // assets/blocking-domains.txt. With default load factor of 0.75, capacity 65536
        // holds up to 49152 entries without table resizing and rehashing (saving ~12 resize cycles).
        // Larger 32KB buffer reduces I/O read calls.
        Set<String> out = new HashSet<>(65536);
        try (InputStream stream = App.context().getAssets().open("blocking-domains.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"), 32768)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                line = line.trim().toLowerCase(Locale.ROOT);
                if (!line.isEmpty() && line.charAt(0) != '#') out.add(line);
            }
        } catch (Exception ignored) {
            // The targeted rules below still block common pop-up ad paths if the list is absent.
        }
        Collections.addAll(out, "doubleclick.net", "googlesyndication.com",
                "googleadservices.com", "adservice.google.com", "popads.net",
                "popcash.net", "exoclick.com", "trafficjunky.net", "propellerads.com",
                "juicyads.com", "clickadu.com", "adsterra.com");
        return Collections.unmodifiableSet(out);
    }
}
