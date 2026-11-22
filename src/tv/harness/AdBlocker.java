package tv.harness;

import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight EasyList domain filter with media-safe URL checks and
 * config-driven anti-popup/anti-redirect rules. First-party hosts and extra
 * rules come from {@link SiteConfig}; this class is site-agnostic.
 */
final class AdBlocker {
    private static final String[] FIRST_PARTY = SiteConfig.FIRST_PARTY_HOSTS;
    private static final String[] ALLOWED_SUBRESOURCES = lowercaseArray(SiteConfig.ALLOWED_SUBRESOURCE_HOSTS);
    private static final String[] BLOCKED_TLDS = lowercaseArray(SiteConfig.BLOCKED_TLDS);
    private static final String[] BLOCKED_SUBSTRINGS = lowercaseArray(SiteConfig.BLOCKED_URL_SUBSTRINGS);
    private static final Set<String> DOMAINS = loadDomains();

    private AdBlocker() {}

    static boolean isFirstPartyHost(String rawUrl) {
        String host = extractHost(rawUrl);
        if (host == null) return false;
        return matchesFirstParty(host);
    }

    static boolean blocks(String rawUrl) {
        if (rawUrl == null) return false;

        // Non-HTTP URLs (e.g. data:, javascript:, about:, blob:) are never ad-blocked
        boolean isHttp = rawUrl.regionMatches(true, 0, "http" + "://", 0, 7);
        boolean isHttps = !isHttp && rawUrl.regionMatches(true, 0, "https" + "://", 0, 8);
        if (!isHttp && !isHttps) {
            return false;
        }

        // Unconditional substring blocks run before FIRST_PARTY_HOSTS so
        // anti-devtool redirect scripts or push-ad service workers on allowed
        // hosts are still neutralized.
        if (BLOCKED_SUBSTRINGS.length > 0) {
            String lowerUrl = rawUrl.toLowerCase(Locale.ROOT);
            for (String token : BLOCKED_SUBSTRINGS) {
                if (!token.isEmpty() && lowerUrl.contains(token)) return true;
            }
        }

        int schemeLen = isHttp ? 7 : 8;
        int len = rawUrl.length();
        int slash = -1;
        int qmark = -1;
        for (int i = schemeLen; i < len; i++) {
            char c = rawUrl.charAt(i);
            if (c == '/') {
                slash = i;
                break;
            } else if (c == '?' || c == '#') {
                qmark = i;
                break;
            }
        }

        int hostEnd = (slash != -1) ? slash : ((qmark != -1) ? qmark : len);
        int atIdx = rawUrl.lastIndexOf('@', hostEnd - 1);
        int hostStart = (atIdx >= schemeLen) ? atIdx + 1 : schemeLen;
        int colon = rawUrl.indexOf(':', hostStart);
        if (colon != -1 && colon < hostEnd) {
            hostEnd = colon;
        }
        if (hostStart >= hostEnd) {
            return blocksFallback(rawUrl);
        }

        String host = rawUrl.substring(hostStart, hostEnd).toLowerCase(Locale.ROOT);
        if (matchesFirstParty(host)) return false;
        if (matchesAllowedSubresource(host)) return false;

        String path = "";
        if (slash != -1) {
            int pathEnd = len;
            for (int i = slash; i < len; i++) {
                char c = rawUrl.charAt(i);
                if (c == '?' || c == '#') {
                    pathEnd = i;
                    break;
                }
            }
            path = rawUrl.substring(slash, pathEnd).toLowerCase(Locale.ROOT);
        }

        if (isMediaPath(path)) return false;
        if (hasBlockedTld(host)) return true;
        if (hasDomainRule(host)) return true;
        return hasBlockedPath(path);
    }

    private static boolean blocksFallback(String rawUrl) {
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
        if (matchesFirstParty(host)) return false;
        if (matchesAllowedSubresource(host)) return false;
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (isMediaPath(path)) return false;
        if (hasBlockedTld(host)) return true;
        if (hasDomainRule(host)) return true;
        return hasBlockedPath(path);
    }

    private static String extractHost(String rawUrl) {
        if (rawUrl == null) return null;
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean matchesFirstParty(String host) {
        for (String allowed : FIRST_PARTY) {
            if (allowed == null || allowed.isEmpty()) continue;
            String a = allowed.toLowerCase(Locale.ROOT);
            if (host.equals(a) || host.endsWith("." + a)) return true;
        }
        return false;
    }

    private static boolean matchesAllowedSubresource(String host) {
        for (String allowed : ALLOWED_SUBRESOURCES) {
            if (allowed == null || allowed.isEmpty()) continue;
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    private static boolean hasBlockedTld(String host) {
        for (String tld : BLOCKED_TLDS) {
            if (!tld.isEmpty() && host.endsWith("." + tld)) return true;
        }
        return false;
    }

    private static boolean hasBlockedPath(String path) {
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

    private static String[] lowercaseArray(String[] input) {
        if (input == null || input.length == 0) return new String[0];
        String[] out = new String[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = input[i] == null ? "" : input[i].trim().toLowerCase(Locale.ROOT);
        }
        return out;
    }

    private static Set<String> loadDomains() {
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
            // Targeted rules below still block common pop-up ad hosts if the list is absent.
        }
        Collections.addAll(out, "doubleclick.net", "googlesyndication.com",
                "googleadservices.com", "adservice.google.com", "popads.net",
                "popcash.net", "exoclick.com", "trafficjunky.net", "propellerads.com",
                "juicyads.com", "clickadu.com", "adsterra.com");
        if (SiteConfig.EXTRA_BLOCKED_DOMAINS != null) {
            for (String d : SiteConfig.EXTRA_BLOCKED_DOMAINS) {
                if (d != null) {
                    String clean = d.trim().toLowerCase(Locale.ROOT);
                    if (!clean.isEmpty()) out.add(clean);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
