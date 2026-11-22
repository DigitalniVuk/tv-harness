package tv.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact player screen with native, focusable remote controls, popup/redirect
 * blocking, optional direct-embed server failover, HTML5 video watchdog, and
 * overlay episode picker.
 */
public final class WatchActivity extends Activity {
    private static final String TAG = "TvHarness";
    private static final String HOME_URL = SiteConfig.BASE_URL + SiteConfig.HOME_PATH;
    private static final int BG = Color.rgb(10, 12, 18);
    private static final int ACCENT = Color.rgb(126, 91, 242);
    private static final int FOCUS = Color.rgb(83, 184, 255);
    private static final int GLASS_BG = Color.argb(215, 14, 18, 27);
    private static final int GLASS_BTN = Color.argb(220, 30, 35, 48);
    private static final int AUTO_HIDE_DELAY_MS = 4500;
    private static final int WATCHDOG_INTERVAL_MS = 1500;

    private static class EpisodeInfo {
        final String num;
        final String name;

        EpisodeInfo(String num, String name) {
            this.num = num;
            this.name = name;
        }
    }

    private FrameLayout rootOverlay;
    private LinearLayout toolbar;
    private LinearLayout topBar;
    private LinearLayout bottomContainer;
    private LinearLayout controls;
    private LinearLayout episodeSheet;
    private LinearLayout episodeListContainer;
    private HorizontalScrollView episodeScrollView;
    private TextView episodeSheetTitle;
    private TextView title;
    private TextView serverButton;
    private TextView pauseButton;
    private TextView soundButton;
    private TextView autoplayButton;
    private TextView timeLabel;
    private ProgressBar seekProgress;
    private ProgressBar progress;
    private LinearLayout loadingOverlay;
    private TextView loadingText;
    private WebView web;
    private FrameLayout playerArea;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private boolean autoplayOn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideControlsRunnable = this::hideOsd;
    private final Runnable watchdogRunnable = this::pollVideoState;
    private final ExecutorService bgWorkers = Executors.newSingleThreadExecutor();

    private boolean osdVisible = true;
    private boolean episodeSheetVisible = false;
    private final List<EpisodeInfo> loadedEpisodes = new ArrayList<>();
    private String currentEpisodeNum = "";

    // Playback metadata & direct-embed state
    private String itemTitle = "";
    private String mediaId = "";
    private String mediaType = "movie";
    private int currentSeason = 1;
    private int currentEpisode = 1;
    private boolean directEmbedMode = false;
    private int currentServerIndex = 0;
    private int loadGeneration = 0;
    private boolean pageCommitted = false;
    private String expectedEmbedHost = "";
    private long serverLoadStartedMs = 0L;
    private boolean playbackStarted = false;
    private boolean userPaused = false;
    private boolean userMuted = false;
    private boolean videoCurrentlyPaused = false;
    private boolean videoCurrentlyMuted = false;
    private int playClickAttempts = 0;
    private int blockedReqCount = 0;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        parseIntentMeta();
        buildUi();
        configureWebView();

        if (state == null) {
            if (directEmbedMode) {
                loadDirectEmbedServer(0);
            } else {
                serverLoadStartedMs = SystemClock.elapsedRealtime();
                web.loadUrl(startUrl());
            }
        } else {
            web.restoreState(state);
        }

        if (directEmbedMode && "tv".equalsIgnoreCase(mediaType)) {
            fetchNativeTvEpisodes();
        }
        startWatchdog();
        scheduleHideOsd();
    }

    private void parseIntentMeta() {
        Intent i = getIntent();
        String url = i.getStringExtra("url");
        itemTitle = i.getStringExtra("title");
        if (itemTitle == null) itemTitle = "";
        mediaId = i.getStringExtra("mediaId");
        if (mediaId == null) mediaId = "";
        mediaType = i.getStringExtra("mediaType");
        if (mediaType == null || mediaType.isEmpty()) {
            if (url != null && !SiteConfig.DIRECT_EMBED_TV_MARKER.isEmpty()
                    && url.contains(SiteConfig.DIRECT_EMBED_TV_MARKER)) {
                mediaType = "tv";
            } else {
                mediaType = "movie";
            }
        }
        currentSeason = Math.max(1, i.getIntExtra("season", 1));
        currentEpisode = Math.max(1, i.getIntExtra("episode", 1));
        currentEpisodeNum = String.valueOf(currentEpisode);

        if (mediaId.isEmpty() && url != null && !SiteConfig.DIRECT_EMBED_ID_REGEX.isEmpty()) {
            Matcher m = Pattern.compile(SiteConfig.DIRECT_EMBED_ID_REGEX).matcher(url);
            if (m.find() && m.groupCount() >= 1) {
                mediaId = m.group(1);
            }
        }

        String[] templates = currentEmbedTemplates();
        directEmbedMode = !mediaId.isEmpty() && templates.length > 0;
    }

    private String[] currentEmbedTemplates() {
        if ("tv".equalsIgnoreCase(mediaType) && SiteConfig.DIRECT_EMBED_TV_URLS.length > 0) {
            return SiteConfig.DIRECT_EMBED_TV_URLS;
        }
        return SiteConfig.DIRECT_EMBED_MOVIE_URLS;
    }

    private String serverLabel(int idx) {
        if (SiteConfig.DIRECT_EMBED_LABELS != null && idx < SiteConfig.DIRECT_EMBED_LABELS.length
                && !SiteConfig.DIRECT_EMBED_LABELS[idx].isEmpty()) {
            return SiteConfig.DIRECT_EMBED_LABELS[idx];
        }
        return "Server " + (idx + 1);
    }

    private void loadDirectEmbedServer(int index) {
        String[] templates = currentEmbedTemplates();
        if (templates.length == 0) return;
        currentServerIndex = Math.max(0, Math.min(index, templates.length - 1));
        loadGeneration++;
        pageCommitted = false;
        playbackStarted = false;
        userPaused = false;
        playClickAttempts = 0;
        serverLoadStartedMs = SystemClock.elapsedRealtime();

        String embedUrl = templates[currentServerIndex]
                .replace("{id}", mediaId)
                .replace("{season}", String.valueOf(currentSeason))
                .replace("{episode}", String.valueOf(currentEpisode));
        Uri parsed = Uri.parse(embedUrl);
        expectedEmbedHost = parsed.getHost() == null ? "" : parsed.getHost();

        String label = serverLabel(currentServerIndex);
        Log.i(TAG, "LOAD_EMBED [" + label + "] gen=" + loadGeneration + " " + embedUrl);
        updateHeaderTitle();
        if (timeLabel != null) timeLabel.setText("00:00 / --:--");
        if (seekProgress != null) seekProgress.setProgress(0);
        showLoadingOverlay("Loading " + label + "…");

        Map<String, String> headers = new HashMap<>();
        String ref = (SiteConfig.DIRECT_EMBED_REFERER != null && !SiteConfig.DIRECT_EMBED_REFERER.isEmpty())
                ? SiteConfig.DIRECT_EMBED_REFERER
                : (SiteConfig.BASE_URL + "/");
        headers.put("Referer", ref);
        web.stopLoading();
        web.loadUrl(embedUrl, headers);
        startWatchdog();
    }

    private void switchToNextServer(boolean automatic) {
        String[] templates = currentEmbedTemplates();
        if (templates.length <= 1) return;
        int next = (currentServerIndex + 1) % templates.length;
        if (automatic && next == 0) {
            // Exhausted all servers automatically — stop cycling and inform user
            Log.w(TAG, "FAILOVER_EXHAUSTED all " + templates.length + " servers stalled");
            showLoadingOverlay("Stream unavailable on all servers — press RELOAD or BACK");
            Toast.makeText(this, "No working server found. Press RELOAD or BACK.", Toast.LENGTH_LONG).show();
            return;
        }
        String msg = automatic
                ? (serverLabel(currentServerIndex) + " stalled — trying " + serverLabel(next) + "…")
                : ("Switching to " + serverLabel(next) + "…");
        Log.i(TAG, "SERVER_SWITCH: " + msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        loadDirectEmbedServer(next);
    }

    private void updateHeaderTitle() {
        if (title == null) return;
        String base = !itemTitle.isEmpty() ? itemTitle : (SiteConfig.DISPLAY_NAME + " PLAYER");
        if (directEmbedMode) {
            String epTag = "tv".equalsIgnoreCase(mediaType)
                    ? ("  ·  S" + currentSeason + " E" + currentEpisode) : "";
            String srv = "  ·  " + serverLabel(currentServerIndex)
                    + " (" + (currentServerIndex + 1) + "/" + currentEmbedTemplates().length + ")";
            title.setText(base + epTag + srv);
            if (serverButton != null) {
                serverButton.setText("SERVER " + (currentServerIndex + 1) + "/" + currentEmbedTemplates().length);
            }
        } else {
            title.setText(base);
        }
    }

    private void buildUi() {
        rootOverlay = new FrameLayout(this);
        rootOverlay.setBackgroundColor(BG);
        rootOverlay.setFocusable(directEmbedMode);
        rootOverlay.setFocusableInTouchMode(directEmbedMode);
        disableDefaultFocusHighlight(rootOverlay);

        playerArea = new FrameLayout(this);
        playerArea.setBackgroundColor(BG);
        disableDefaultFocusHighlight(playerArea);
        rootOverlay.addView(playerArea, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setId(View.generateViewId());
        web.setFocusable(!directEmbedMode);
        web.setFocusableInTouchMode(!directEmbedMode);
        web.setBackgroundColor(BG);
        disableDefaultFocusHighlight(web);
        playerArea.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Centered loading indicator shown until video frames advance
        loadingOverlay = new LinearLayout(this);
        loadingOverlay.setOrientation(LinearLayout.HORIZONTAL);
        loadingOverlay.setGravity(Gravity.CENTER);
        loadingOverlay.setPadding(dp(22), dp(14), dp(22), dp(14));
        GradientDrawable loadBg = new GradientDrawable();
        loadBg.setColor(Color.argb(215, 14, 18, 28));
        loadBg.setCornerRadius(dp(14));
        loadBg.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        loadingOverlay.setBackground(loadBg);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(FOCUS));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        spLp.rightMargin = dp(12);
        loadingOverlay.addView(spinner, spLp);

        loadingText = new TextView(this);
        loadingText.setText("Loading stream…");
        loadingText.setTextColor(Color.WHITE);
        loadingText.setTextSize(15);
        loadingText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        loadingOverlay.addView(loadingText);
        loadingOverlay.setVisibility(View.GONE);

        rootOverlay.addView(loadingOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Top toolbar
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setBackground(topScrimDrawable());

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(24), dp(10), dp(24), dp(8));

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (directEmbedMode && currentEmbedTemplates().length > 1) {
            serverButton = action("SERVER", "Switch stream server", () -> switchToNextServer(false));
            topBar.addView(serverButton, headerButtonParams(dp(116)));
        }
        if (hasEpisodePicker()) {
            topBar.addView(action("EPISODES", "Episodes", this::toggleEpisodeSheet), headerButtonParams(dp(106)));
        }
        topBar.addView(action("RELOAD", "Reload player", this::reloadCurrent), headerButtonParams(dp(96)));
        topBar.addView(action("BACK", "Return", this::finish), headerButtonParams(dp(92)));
        toolbar.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        toolbar.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        rootOverlay.addView(toolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Episode sheet overlay
        episodeSheet = new LinearLayout(this);
        episodeSheet.setOrientation(LinearLayout.VERTICAL);
        episodeSheet.setBackground(sheetBackgroundDrawable());
        episodeSheet.setPadding(dp(20), dp(16), dp(20), dp(16));
        episodeSheet.setVisibility(View.GONE);

        LinearLayout sheetHeader = new LinearLayout(this);
        sheetHeader.setGravity(Gravity.CENTER_VERTICAL);
        episodeSheetTitle = new TextView(this);
        episodeSheetTitle.setText("SELECT EPISODE");
        episodeSheetTitle.setTextColor(Color.WHITE);
        episodeSheetTitle.setTextSize(16);
        episodeSheetTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sheetHeader.addView(episodeSheetTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sheetHeader.addView(action("CLOSE ✕", "Close episodes", this::toggleEpisodeSheet), headerButtonParams(dp(106)));
        episodeSheet.addView(sheetHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        episodeScrollView = new HorizontalScrollView(this);
        episodeScrollView.setHorizontalScrollBarEnabled(false);
        episodeScrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        episodeListContainer = new LinearLayout(this);
        episodeListContainer.setOrientation(LinearLayout.HORIZONTAL);
        episodeListContainer.setPadding(0, dp(12), 0, dp(4));
        episodeScrollView.addView(episodeListContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        episodeSheet.addView(episodeScrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sheetParams.bottomMargin = dp(116);
        sheetParams.leftMargin = dp(24);
        sheetParams.rightMargin = dp(24);
        rootOverlay.addView(episodeSheet, sheetParams);

        // Bottom OSD: progress/time bar + transport controls
        bottomContainer = new LinearLayout(this);
        bottomContainer.setOrientation(LinearLayout.VERTICAL);
        bottomContainer.setBackground(bottomScrimDrawable());
        bottomContainer.setPadding(dp(20), dp(10), dp(20), dp(12));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.setPadding(dp(6), 0, dp(6), dp(8));

        timeLabel = new TextView(this);
        timeLabel.setText("00:00 / --:--");
        timeLabel.setTextColor(Color.rgb(220, 226, 238));
        timeLabel.setTextSize(13);
        timeLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.rightMargin = dp(14);
        timeRow.addView(timeLabel, timeLp);

        seekProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        seekProgress.setMax(1000);
        seekProgress.setProgress(0);
        seekProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(FOCUS));
        seekProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.argb(90, 255, 255, 255)));
        timeRow.addView(seekProgress, new LinearLayout.LayoutParams(0, dp(5), 1));
        bottomContainer.addView(timeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        pauseButton = square("Ⅱ\nPAUSE", "Pause or play", this::togglePause);
        controls.addView(pauseButton, controlParams());

        if (directEmbedMode) {
            controls.addView(square("⏪\n-15s", "Rewind 15 seconds", () -> seekBy(-15)), controlParams());
            controls.addView(square("⏩\n+15s", "Forward 15 seconds", () -> seekBy(15)), controlParams());
            if ("tv".equalsIgnoreCase(mediaType)) {
                controls.addView(square("◀\nPREV EP", "Previous episode", () -> stepTvEpisode(-1)), controlParams());
                controls.addView(square("▶\nNEXT EP", "Next episode", () -> stepTvEpisode(1)), controlParams());
            }
            soundButton = square("🔊\nSOUND ON", "Toggle sound", this::toggleSound);
            controls.addView(soundButton, controlParams());
        } else {
            controls.addView(square("◀\nPREV", "Previous episode", () -> pageClick(SiteConfig.PREV_EPISODE_SELECTOR)), controlParams());
            controls.addView(square("▶\nNEXT", "Next episode", () -> pageClick(SiteConfig.NEXT_EPISODE_SELECTOR)), controlParams());
            if (!SiteConfig.EPISODE_LIST_SELECTOR.isEmpty()) {
                controls.addView(square("≡\nEPISODES", "Episodes list", this::toggleEpisodeSheet), controlParams());
            }
            autoplayButton = square("AUTO PLAY\n…", "Toggle autoplay", this::toggleAutoplay);
            controls.addView(autoplayButton, controlParams());
        }

        bottomContainer.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        rootOverlay.addView(bottomContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        updateHeaderTitle();
        setContentView(rootOverlay);
        controls.getChildAt(0).requestFocus();
    }

    private boolean hasEpisodePicker() {
        if (directEmbedMode && "tv".equalsIgnoreCase(mediaType)
                && SiteConfig.TV_SEASON_PATH_TEMPLATE != null
                && !SiteConfig.TV_SEASON_PATH_TEMPLATE.isEmpty()) {
            return true;
        }
        return SiteConfig.EPISODE_LIST_SELECTOR != null && !SiteConfig.EPISODE_LIST_SELECTOR.isEmpty();
    }

    private void reloadCurrent() {
        if (directEmbedMode) {
            loadDirectEmbedServer(currentServerIndex);
        } else {
            playbackStarted = false;
            serverLoadStartedMs = SystemClock.elapsedRealtime();
            web.reload();
        }
    }

    private void showLoadingOverlay(String message) {
        if (loadingText != null) loadingText.setText(message);
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoadingOverlay() {
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
    }

    private GradientDrawable topScrimDrawable() {
        return new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(225, 10, 12, 18), Color.argb(150, 10, 12, 18), Color.TRANSPARENT});
    }

    private GradientDrawable bottomScrimDrawable() {
        return new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(240, 10, 12, 18), Color.argb(190, 10, 12, 18), Color.TRANSPARENT});
    }

    private GradientDrawable sheetBackgroundDrawable() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GLASS_BG);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        return bg;
    }

    private LinearLayout.LayoutParams headerButtonParams(int widthPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(widthPx, dp(38));
        p.leftMargin = dp(8);
        return p;
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(60), 1);
        p.leftMargin = dp(6);
        p.rightMargin = dp(6);
        return p;
    }

    private TextView square(String label, String description, Runnable run) {
        TextView button = action(label, description, run);
        button.setTextSize(13);
        button.setMaxLines(2);
        button.setId(View.generateViewId());
        return button;
    }

    private TextView action(String label, String description, Runnable run) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setContentDescription(description);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setOnClickListener(v -> {
            scheduleHideOsd();
            run.run();
            if (!isFinishing()) v.post(v::requestFocus);
        });
        button.setBackground(buttonBackground());
        return button;
    }

    private StateListDrawable buttonBackground() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(GLASS_BTN);
        normal.setCornerRadius(dp(10));
        normal.setStroke(dp(1), Color.argb(60, 255, 255, 255));

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ACCENT);
        focused.setCornerRadius(dp(10));
        focused.setStroke(dp(3), FOCUS);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private void scheduleHideOsd() {
        handler.removeCallbacks(hideControlsRunnable);
        if (episodeSheetVisible) return;
        handler.postDelayed(hideControlsRunnable, AUTO_HIDE_DELAY_MS);
    }

    private static void disableDefaultFocusHighlight(View view) {
        if (view == null || Build.VERSION.SDK_INT < 26) return;
        try {
            View.class.getMethod("setDefaultFocusHighlightEnabled", boolean.class).invoke(view, false);
        } catch (Exception ignored) {
        }
    }

    private void showOsd() {
        osdVisible = true;
        toolbar.setVisibility(View.VISIBLE);
        bottomContainer.setVisibility(View.VISIBLE);
        if (!controls.hasFocus() && !toolbar.hasFocus() && !episodeSheetVisible
                && controls.getChildCount() > 0) {
            controls.getChildAt(0).requestFocus();
        }
        scheduleHideOsd();
    }

    private void hideOsd() {
        if (episodeSheetVisible) return;
        osdVisible = false;
        toolbar.setVisibility(View.GONE);
        bottomContainer.setVisibility(View.GONE);
        if (directEmbedMode) {
            if (rootOverlay != null && !rootOverlay.hasFocus()) {
                rootOverlay.requestFocus();
            }
        } else if (web != null && !web.hasFocus()) {
            web.requestFocus();
        }
    }

    private void toggleEpisodeSheet() {
        if (!hasEpisodePicker()) {
            Toast.makeText(this, "Episodes not available", Toast.LENGTH_SHORT).show();
            return;
        }
        episodeSheetVisible = !episodeSheetVisible;
        if (episodeSheetVisible) {
            showOsd();
            handler.removeCallbacks(hideControlsRunnable);
            episodeSheet.setVisibility(View.VISIBLE);
            if (loadedEpisodes.isEmpty()) {
                populateEpisodeUi();
            }
            if (directEmbedMode) {
                fetchNativeTvEpisodes();
            } else {
                fetchEpisodesFromPage();
            }
            if (episodeListContainer.getChildCount() > 0) {
                focusCurrentEpisodeOrFirst();
            }
        } else {
            episodeSheet.setVisibility(View.GONE);
            scheduleHideOsd();
            if (controls.getChildCount() > 0) {
                controls.getChildAt(0).requestFocus();
            }
        }
    }

    private void fetchNativeTvEpisodes() {
        if (mediaId.isEmpty()) return;
        bgWorkers.execute(() -> {
            try {
                List<CatalogScraper.TvEpisode> eps = CatalogScraper.fetchTvEpisodes(mediaId, currentSeason);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    loadedEpisodes.clear();
                    for (CatalogScraper.TvEpisode e : eps) {
                        loadedEpisodes.add(new EpisodeInfo(String.valueOf(e.episode), e.name));
                    }
                    if (episodeSheetVisible) populateEpisodeUi();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void stepTvEpisode(int delta) {
        int target = currentEpisode + delta;
        if (target < 1) {
            Toast.makeText(this, "Already at Episode 1", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!loadedEpisodes.isEmpty() && target > loadedEpisodes.size()) {
            Toast.makeText(this, "Last episode of Season " + currentSeason, Toast.LENGTH_SHORT).show();
            return;
        }
        currentEpisode = target;
        currentEpisodeNum = String.valueOf(currentEpisode);
        Toast.makeText(this, "Loading S" + currentSeason + " E" + currentEpisode + "…", Toast.LENGTH_SHORT).show();
        loadDirectEmbedServer(currentServerIndex);
    }

    private void focusCurrentEpisodeOrFirst() {
        View target = null;
        for (int i = 0; i < episodeListContainer.getChildCount(); i++) {
            View child = episodeListContainer.getChildAt(i);
            if (currentEpisodeNum != null && currentEpisodeNum.equals(child.getTag())) {
                target = child;
                break;
            }
        }
        if (target == null && episodeListContainer.getChildCount() > 0) {
            target = episodeListContainer.getChildAt(0);
        }
        if (target != null) {
            final View toFocus = target;
            toFocus.post(() -> {
                toFocus.requestFocus();
                int scrollTo = toFocus.getLeft() - dp(40);
                episodeScrollView.smoothScrollTo(Math.max(0, scrollTo), 0);
            });
        }
    }

    private void fetchEpisodesFromPage() {
        if (web == null || SiteConfig.EPISODE_LIST_SELECTOR.isEmpty()) return;
        String js = "(function(){"
                + "var eps=[];"
                + "var active=document.querySelector(" + jsQuote(SiteConfig.EPISODE_ACTIVE_SELECTOR) + ");"
                + "var cur=active?(active.getAttribute(" + jsQuote(SiteConfig.EPISODE_NUM_ATTR) + ")||'') :'';"
                + "var nodes=document.querySelectorAll(" + jsQuote(SiteConfig.EPISODE_LIST_SELECTOR) + ");"
                + "for(var i=0;i<nodes.length;i++){"
                + "  var n=nodes[i];"
                + "  var num=n.getAttribute(" + jsQuote(SiteConfig.EPISODE_NUM_ATTR) + ")||n.textContent.trim();"
                + "  var name=n.getAttribute('title')||n.textContent.trim();"
                + "  if(num) eps.push({num:num,name:name});"
                + "}"
                + "return JSON.stringify({current:cur,episodes:eps});"
                + "})()";
        web.evaluateJavascript(js, result -> {
            if (result == null || "null".equals(result)) return;
            try {
                String unquoted = new JSONObject("{data:" + result + "}").getString("data");
                JSONObject json = new JSONObject(unquoted);
                currentEpisodeNum = json.optString("current", "");
                JSONArray arr = json.getJSONArray("episodes");
                loadedEpisodes.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    loadedEpisodes.add(new EpisodeInfo(o.getString("num"), o.getString("name")));
                }
                populateEpisodeUi();
            } catch (Exception ignored) {
            }
        });
    }

    private void populateEpisodeUi() {
        episodeListContainer.removeAllViews();
        if (loadedEpisodes.isEmpty()) {
            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminate(true);
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(24), dp(24));
            sp.gravity = Gravity.CENTER_VERTICAL;
            sp.rightMargin = dp(10);
            sp.leftMargin = dp(4);
            episodeListContainer.addView(spinner, sp);

            TextView empty = new TextView(this);
            empty.setText("Loading episodes…");
            empty.setTextColor(Color.rgb(180, 185, 200));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            episodeListContainer.addView(empty);
            return;
        }

        episodeSheetTitle.setText("SEASON " + currentSeason + "  ·  " + loadedEpisodes.size() + " EPISODES");

        for (EpisodeInfo ep : loadedEpisodes) {
            final String num = ep.num;
            boolean isCurrent = num.equals(currentEpisodeNum);

            TextView btn = new TextView(this);
            btn.setText("EP " + num);
            btn.setTag(num);
            String desc = (ep.name != null && !ep.name.isEmpty()) ? ep.name : ("Episode " + num);
            btn.setContentDescription(desc + (isCurrent ? ", currently playing" : ""));
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(13);
            btn.setTypeface(Typeface.DEFAULT, isCurrent ? Typeface.BOLD : Typeface.NORMAL);
            btn.setGravity(Gravity.CENTER);
            btn.setFocusable(true);
            btn.setFocusableInTouchMode(true);

            GradientDrawable norm = new GradientDrawable();
            norm.setColor(isCurrent ? ACCENT : GLASS_BTN);
            norm.setCornerRadius(dp(8));
            norm.setStroke(dp(1), isCurrent ? FOCUS : Color.argb(60, 255, 255, 255));

            GradientDrawable foc = new GradientDrawable();
            foc.setColor(ACCENT);
            foc.setCornerRadius(dp(8));
            foc.setStroke(dp(3), FOCUS);

            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, foc);
            states.addState(new int[]{android.R.attr.state_pressed}, foc);
            states.addState(new int[]{}, norm);
            btn.setBackground(states);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(44));
            lp.rightMargin = dp(8);
            btn.setLayoutParams(lp);

            btn.setOnClickListener(v -> selectEpisode(num));
            episodeListContainer.addView(btn);
        }

        focusCurrentEpisodeOrFirst();
    }

    private void selectEpisode(String num) {
        currentEpisodeNum = num;
        toggleEpisodeSheet();
        if (directEmbedMode) {
            try {
                currentEpisode = Integer.parseInt(num);
            } catch (NumberFormatException ignored) {
            }
            Toast.makeText(this, "Loading S" + currentSeason + " E" + currentEpisode + "…", Toast.LENGTH_SHORT).show();
            loadDirectEmbedServer(currentServerIndex);
            return;
        }
        Toast.makeText(this, "Loading Episode " + num + "…", Toast.LENGTH_SHORT).show();
        String js = "(function(){"
                + "var sel=" + jsQuote(SiteConfig.EPISODE_LIST_SELECTOR) + ";"
                + "var el=null;"
                + "var nodes=document.querySelectorAll(sel);"
                + "for(var i=0;i<nodes.length;i++){"
                + "  if((nodes[i].getAttribute(" + jsQuote(SiteConfig.EPISODE_NUM_ATTR) + ")||nodes[i].textContent.trim())==="
                + jsQuote(num) + "){el=nodes[i];break;}"
                + "}"
                + "if(el){el.click();return true;}"
                + "return false;"
                + "})()";
        web.evaluateJavascript(js, res -> {
            if ("false".equals(res)) {
                Toast.makeText(this, "Episode " + num + " not clickable yet", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadsImagesAutomatically(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (SiteConfig.USER_AGENT != null && !SiteConfig.USER_AGENT.isEmpty()) {
            settings.setUserAgentString(SiteConfig.USER_AGENT);
        }
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!request.isForMainFrame()) {
                    return AdBlocker.blocks(url);
                }
                if (AdBlocker.blocks(url)) {
                    Log.i(TAG, "BLOCKED_REDIRECT_AD: " + url);
                    return true;
                }
                if (SiteConfig.STRICT_MAIN_FRAME_ALLOWLIST && !AdBlocker.isFirstPartyHost(url)) {
                    Log.i(TAG, "BLOCKED_REDIRECT_OFFHOST: " + url);
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return blockedResponse(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return blockedResponse(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                progress.setVisibility(View.VISIBLE);
                if (!directEmbedMode) {
                    pageCommitted = true;
                } else if (url != null && !"about:blank".equals(url)) {
                    String host = Uri.parse(url).getHost();
                    if (host != null && (expectedEmbedHost.isEmpty() || host.equalsIgnoreCase(expectedEmbedHost))) {
                        pageCommitted = true;
                    }
                }
                view.evaluateJavascript(earlyAntiPopupScript(), null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (!directEmbedMode) {
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost();
                    if (host != null && itemTitle.isEmpty()) title.setText(host + "  ·  PLAYER");
                }
                view.evaluateJavascript(playerPageScript(), null);
                if (!directEmbedMode) {
                    updateAutoplayState();
                    fetchEpisodesFromPage();
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(WatchActivity.this, "Secure connection failed", Toast.LENGTH_SHORT).show();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture,
                                          android.os.Message resultMsg) {
                Log.i(TAG, "BLOCKED_POPUP: onCreateWindow userGesture=" + userGesture);
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullscreenView = view;
                fullscreenCallback = callback;
                web.setVisibility(View.GONE);
                playerArea.addView(view, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                leaveFullscreen();
            }

            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                Log.i(TAG, "BLOCKED_JS_ALERT: " + message);
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                Log.i(TAG, "BLOCKED_JS_CONFIRM: " + message);
                result.cancel();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                Log.i(TAG, "BLOCKED_JS_PROMPT: " + message);
                result.cancel();
                return true;
            }
        });
    }

    private WebResourceResponse blockedResponse(String url) {
        if (!AdBlocker.blocks(url)) return null;
        blockedReqCount++;
        if (blockedReqCount <= 40 || blockedReqCount % 10 == 0) {
            Log.i(TAG, "BLOCKED_REQ (#" + blockedReqCount + "): " + url);
        }
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private void startWatchdog() {
        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    private void pollVideoState() {
        if (isFinishing() || web == null) return;
        final int gen = loadGeneration;
        if (directEmbedMode && !pageCommitted) {
            long elapsed = SystemClock.elapsedRealtime() - serverLoadStartedMs;
            if (SiteConfig.STALL_TIMEOUT_MS > 0 && elapsed > SiteConfig.STALL_TIMEOUT_MS
                    && currentEmbedTemplates().length > 1) {
                Log.w(TAG, "STALL_DETECTED server=" + serverLabel(currentServerIndex)
                        + " elapsed=" + elapsed + "ms uncommitted");
                switchToNextServer(true);
                return;
            }
            handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
            return;
        }

        String focusSel = SiteConfig.PLAYER_FOCUS_SELECTOR == null ? "" : SiteConfig.PLAYER_FOCUS_SELECTOR;
        String hideChromeSnippet = (directEmbedMode && SiteConfig.HIDE_DIRECT_EMBED_CHROME)
                ? ("if(v.readyState>=2){" + hideDirectEmbedChromeBody() + "}") : "";
        String js = "(function(){"
                + "var vids=document.querySelectorAll('video');"
                + "var v=null;"
                + "for(var i=0;i<vids.length;i++){if(vids[i].readyState>0||vids[i].currentSrc){v=vids[i];break;}}"
                + "if(!v&&vids.length>0)v=vids[0];"
                + "var host=location.hostname||'';"
                + "if(!v)return JSON.stringify({found:false,host:host});"
                + hideChromeSnippet
                + "if(" + (SiteConfig.AUTO_UNMUTE ? "true" : "false") + "&&!" + (userMuted ? "true" : "false") + "&&v.muted){"
                + "  try{v.muted=false;v.volume=1;}catch(e){}"
                + "}"
                + "if(!" + (userPaused ? "true" : "false") + "&&v.paused&&v.readyState>=2){"
                + "  try{var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}"
                + "}"
                + "return JSON.stringify({found:true,host:host,rs:v.readyState,ct:v.currentTime||0,dur:isFinite(v.duration)?v.duration:0,"
                + "paused:!!v.paused,muted:!!v.muted,vw:v.videoWidth||0,vh:v.videoHeight||0});"
                + "})()";

        web.evaluateJavascript(js, result -> {
            if (isFinishing() || web == null || gen != loadGeneration) return;
            boolean found = false;
            String host = "";
            int rs = 0;
            double ct = 0;
            double dur = 0;
            boolean paused = true;
            boolean muted = false;
            int vw = 0;
            int vh = 0;

            if (result != null && !"null".equals(result)) {
                try {
                    String unquoted = new JSONObject("{data:" + result + "}").getString("data");
                    JSONObject obj = new JSONObject(unquoted);
                    found = obj.optBoolean("found", false);
                    host = obj.optString("host", "");
                    rs = obj.optInt("rs", 0);
                    ct = obj.optDouble("ct", 0);
                    dur = obj.optDouble("dur", 0);
                    paused = obj.optBoolean("paused", true);
                    muted = obj.optBoolean("muted", false);
                    vw = obj.optInt("vw", 0);
                    vh = obj.optInt("vh", 0);
                } catch (Exception ignored) {
                }
            }

            if (directEmbedMode && !expectedEmbedHost.isEmpty() && !host.equalsIgnoreCase(expectedEmbedHost)) {
                handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
                return;
            }

            videoCurrentlyPaused = paused;
            videoCurrentlyMuted = muted;

            if (found) {
                if (pauseButton != null) {
                    pauseButton.setText(paused ? "▶\nPLAY" : "Ⅱ\nPAUSE");
                }
                if (soundButton != null) {
                    soundButton.setText(muted ? "🔇\nMUTED" : "🔊\nSOUND ON");
                }
                if (timeLabel != null) {
                    timeLabel.setText(formatTime(ct) + " / " + (dur > 0 ? formatTime(dur) : "--:--"));
                }
                if (seekProgress != null && dur > 0) {
                    seekProgress.setProgress((int) Math.min(1000, Math.max(0, (ct / dur) * 1000.0)));
                }
            }

            if (found && ct > 0.25) {
                if (!playbackStarted) {
                    playbackStarted = true;
                    hideLoadingOverlay();
                    Log.i(TAG, "PLAYBACK_STARTED server=" + serverLabel(currentServerIndex)
                            + " gen=" + gen
                            + " ct=" + String.format(Locale.US, "%.1f", ct)
                            + " dur=" + String.format(Locale.US, "%.0f", dur)
                            + " res=" + vw + "x" + vh + " muted=" + muted);
                    scheduleHideOsd();
                }
            } else if (!playbackStarted && directEmbedMode) {
                long elapsed = SystemClock.elapsedRealtime() - serverLoadStartedMs;
                if (elapsed > 3500 && playClickAttempts < 2 && !focusSel.isEmpty()) {
                    playClickAttempts++;
                    web.evaluateJavascript("(function(){var b=document.querySelector("
                            + jsQuote(focusSel) + ");if(b)b.click();})()", null);
                }
                if (SiteConfig.STALL_TIMEOUT_MS > 0 && elapsed > SiteConfig.STALL_TIMEOUT_MS
                        && currentEmbedTemplates().length > 1) {
                    Log.w(TAG, "STALL_DETECTED server=" + serverLabel(currentServerIndex)
                            + " elapsed=" + elapsed + "ms rs=" + rs + " ct=" + ct);
                    switchToNextServer(true);
                    return;
                }
            }

            handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
        });
    }

    private static String hideDirectEmbedChromeBody() {
        String capSel = SiteConfig.PLAYER_CAPTION_SELECTOR == null ? "" : SiteConfig.PLAYER_CAPTION_SELECTOR;
        return "try{"
                + "v.controls=false;"
                + "var capSel=" + jsQuote(capSel) + ";"
                + "function hideNonCap(el){"
                + "  if(!el||el.nodeType!==1)return;"
                + "  var t=el.tagName;"
                + "  if(t==='STYLE'||t==='SCRIPT'||t==='LINK'||t==='VIDEO')return;"
                + "  if(capSel&&el.matches&&el.matches(capSel))return;"
                + "  if(capSel&&el.querySelector&&el.querySelector(capSel)){"
                + "    for(var k=0;k<el.children.length;k++)hideNonCap(el.children[k]);"
                + "    return;"
                + "  }"
                + "  el.style.setProperty('display','none','important');"
                + "  el.style.setProperty('visibility','hidden','important');"
                + "  el.style.setProperty('opacity','0','important');"
                + "}"
                + "var cur=v;"
                + "while(cur&&cur!==document.body&&cur!==document.documentElement){"
                + "  var p=cur.parentElement;"
                + "  if(p){for(var j=0;j<p.children.length;j++){if(p.children[j]!==cur)hideNonCap(p.children[j]);}}"
                + "  cur=p;"
                + "}"
                + "}catch(e){}";
    }

    private static String formatTime(double seconds) {
        int total = (int) Math.max(0, Math.floor(seconds));
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private void seekBy(int deltaSeconds) {
        if (web == null) return;
        String js = "(function(){"
                + "var v=document.querySelector('video');"
                + "if(!v||!isFinite(v.duration))return false;"
                + "v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+(" + deltaSeconds + ")));"
                + "return true;})()";
        web.evaluateJavascript(js, null);
        scheduleHideOsd();
    }

    private void toggleSound() {
        if (web == null) return;
        userMuted = !videoCurrentlyMuted;
        String js = "(function(){"
                + "var v=document.querySelector('video');"
                + "if(!v)return false;"
                + "v.muted=" + (userMuted ? "true" : "false") + ";"
                + "if(!v.muted)v.volume=1;"
                + "return v.muted;})()";
        web.evaluateJavascript(js, res -> {
            videoCurrentlyMuted = "true".equals(res);
            if (soundButton != null) {
                soundButton.setText(videoCurrentlyMuted ? "🔇\nMUTED" : "🔊\nSOUND ON");
            }
        });
    }

    private void pageClick(String selector) {
        if (selector == null || selector.isEmpty()) {
            Toast.makeText(this, "That control is not available for this site", Toast.LENGTH_SHORT).show();
            return;
        }
        String js = "(function(){var e=document.querySelector(" + jsQuote(selector)
                + ");if(e){e.click();return true;}return false;})()";
        web.evaluateJavascript(js, value -> {
            if ("false".equals(value)) Toast.makeText(this, "Episode control is not available yet",
                    Toast.LENGTH_SHORT).show();
            else fetchEpisodesFromPage();
        });
    }

    private static String jsQuote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void toggleAutoplay() {
        if (SiteConfig.AUTOPLAY_SELECTOR == null || SiteConfig.AUTOPLAY_SELECTOR.isEmpty()) {
            Toast.makeText(this, "Autoplay control is not available for this site", Toast.LENGTH_SHORT).show();
            return;
        }
        web.evaluateJavascript("(function(){var e=document.querySelector("
                + jsQuote(SiteConfig.AUTOPLAY_SELECTOR) + ");"
                + "if(!e)return false;e.click();return e.classList.contains('active')||"
                + "e.classList.contains('on')||e.getAttribute('data-value')==='1';})()",
                value -> updateAutoplayState());
    }

    private void updateAutoplayState() {
        if (web == null || autoplayButton == null) return;
        web.evaluateJavascript("(function(){var e=document.querySelector("
                + jsQuote(SiteConfig.AUTOPLAY_SELECTOR) + ");"
                + "var v=localStorage.getItem('auto_play');if(v!==null)return v==='1'||v==='true';"
                + "return !!(e&&(e.classList.contains('active')||e.classList.contains('on')||"
                + "e.getAttribute('data-value')==='1'||e.getAttribute('aria-checked')==='true'));})()",
                value -> {
                    autoplayOn = "true".equals(value);
                    if (autoplayButton != null) {
                        autoplayButton.setText("AUTO PLAY\n" + (autoplayOn ? "ON ✓" : "OFF"));
                        autoplayButton.setContentDescription("Autoplay, currently " + (autoplayOn ? "enabled" : "disabled"));
                    }
                });
    }

    private void togglePause() {
        if (web == null) return;
        if (directEmbedMode) {
            String js = "(function(){"
                    + "var v=document.querySelector('video');"
                    + "if(v){if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});return 'playing';}"
                    + "else{v.pause();return 'paused';}}"
                    + "var sel=" + jsQuote(SiteConfig.PAUSE_TOGGLE_SELECTOR == null ? "" : SiteConfig.PAUSE_TOGGLE_SELECTOR) + ";"
                    + "if(sel){var e=document.querySelector(sel);if(e){e.click();return 'clicked';}}"
                    + "return 'none';})()";
            web.evaluateJavascript(js, res -> {
                if (res != null && res.contains("paused")) {
                    userPaused = true;
                    videoCurrentlyPaused = true;
                    if (pauseButton != null) pauseButton.setText("▶\nPLAY");
                } else if (res != null && res.contains("playing")) {
                    userPaused = false;
                    videoCurrentlyPaused = false;
                    if (pauseButton != null) pauseButton.setText("Ⅱ\nPAUSE");
                }
                if (SiteConfig.HIDE_DIRECT_EMBED_CHROME) {
                    String hideJs = "(function(){var v=document.querySelector('video');if(v){"
                            + hideDirectEmbedChromeBody() + "}})()";
                    handler.postDelayed(() -> { if (!isFinishing() && web != null) web.evaluateJavascript(hideJs, null); }, 80);
                    handler.postDelayed(() -> { if (!isFinishing() && web != null) web.evaluateJavascript(hideJs, null); }, 300);
                }
            });
            return;
        }
        String selector = SiteConfig.PAUSE_TOGGLE_SELECTOR;
        if (selector != null && !selector.isEmpty()) {
            web.evaluateJavascript("(function(){var e=document.querySelector("
                    + jsQuote(selector) + ");if(e){e.click();return true;}return false;})()", null);
            return;
        }
        web.evaluateJavascript("(function(){var v=document.querySelector('video');"
                + "if(v){if(v.paused)v.play();else v.pause();return true;}"
                + "var e=document.createEvent('Events');"
                + "e.initEvent('keydown',true,true);Object.defineProperty(e,'keyCode',{get:function(){return 32;}});"
                + "Object.defineProperty(e,'which',{get:function(){return 32;}});window.dispatchEvent(e);return true;})()",
                null);
    }

    private static String earlyAntiPopupScript() {
        return "(function(){"
                + "try{"
                + "  window.open=function(){return null;};"
                + "  window.alert=function(){};"
                + "  window.confirm=function(){return false;};"
                + "  window.prompt=function(){return null;};"
                + "  if(!window.__tvNoBlank){"
                + "    window.__tvNoBlank=1;"
                + "    document.addEventListener('click',function(ev){"
                + "      var a=ev.target&&ev.target.closest?ev.target.closest('a'):null;"
                + "      if(a&&a.target==='_blank'){ev.preventDefault();ev.stopPropagation();}"
                + "    },true);"
                + "  }"
                + "}catch(e){}"
                + "})();";
    }

    /** Builds the page-customisation script from SiteConfig player selectors. */
    private static String playerPageScript() {
        String hide = SiteConfig.PLAYER_HIDE_CSS == null ? "" : SiteConfig.PLAYER_HIDE_CSS;
        String keep = SiteConfig.PLAYER_KEEP_SELECTORS == null ? "" : SiteConfig.PLAYER_KEEP_SELECTORS;
        String element = SiteConfig.PLAYER_ELEMENT_SELECTOR == null ? "" : SiteConfig.PLAYER_ELEMENT_SELECTOR;
        String frame = SiteConfig.PLAYER_FRAME_SELECTOR == null ? "" : SiteConfig.PLAYER_FRAME_SELECTOR;
        String focus = SiteConfig.PLAYER_FOCUS_SELECTOR == null ? "" : SiteConfig.PLAYER_FOCUS_SELECTOR;
        StringBuilder css = new StringBuilder()
                .append("html,body{margin:0!important;padding:0!important;overflow:hidden!important;")
                .append("background:#0a0c12!important;width:100%!important;height:100%!important;}")
                .append("*:focus,*:focus-visible{outline:none!important;box-shadow:none!important;}");
        if (!hide.isEmpty()) {
            css.append(hide).append("{display:none!important;}");
        }
        if (!keep.isEmpty()) {
            css.append(keep).append("{display:block!important;position:static!important;")
                    .append("width:100%!important;max-width:none!important;")
                    .append("height:100%!important;min-height:0!important;margin:0!important;padding:0!important;")
                    .append("border:0!important;background:#0a0c12!important;overflow:hidden!important;}");
        }
        if (!element.isEmpty()) {
            css.append(element).append("{height:100%!important;width:100%!important;display:flex!important;")
                    .append("flex-direction:column!important;justify-content:center!important;align-items:center!important;}");
        }
        if (!frame.isEmpty()) {
            css.append(frame).append("{position:absolute!important;inset:0!important;")
                    .append("width:100%!important;height:100%!important;border:0!important;object-fit:contain!important;}");
        }
        return earlyAntiPopupScript() + "(function(){"
                + "var css=" + jsQuote(css.toString()) + ";"
                + "var s=document.getElementById('tv-player-style');if(!s){s=document.createElement('style');"
                + "s.id='tv-player-style';(document.head||document.documentElement).appendChild(s);}s.textContent=css;"
                + "if(!window.__tvFocusWatch){window.__tvFocusWatch=1;var n=0;var t=setInterval(function(){"
                + "var p=" + (focus.isEmpty() ? "null" : "document.querySelector(" + jsQuote(focus) + ")") + ";"
                + "if(p&&getComputedStyle(p).display!=='none'){"
                + "if(p.scrollIntoView)p.scrollIntoView({block:'center'});"
                + "p.setAttribute('tabindex','0');p.focus();clearInterval(t);}else{"
                + "p=document.querySelector(" + jsQuote(frame.isEmpty() ? "video, iframe" : frame) + ");"
                + "if(p){p.setAttribute('tabindex','0');p.focus();clearInterval(t);}}"
                + "if(++n>480)clearInterval(t);},500);}"
                + "})();";
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();

        // Handle BACK key
        if (key == KeyEvent.KEYCODE_BACK) {
            if (fullscreenView != null) {
                leaveFullscreen();
                return true;
            }
            if (episodeSheetVisible) {
                toggleEpisodeSheet();
                return true;
            }
            if (osdVisible && playbackStarted) {
                hideOsd();
                return true;
            }
            if (!directEmbedMode && web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            finish();
            return true;
        }

        // Media keys
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            togglePause();
            showOsd();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            seekBy(15);
            showOsd();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            seekBy(-15);
            showOsd();
            return true;
        }

        // D-Pad and navigation keys wake up / keep OSD visible
        boolean isDpadKey = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;

        if (isDpadKey) {
            if (!osdVisible) {
                showOsd();
                controls.getChildAt(0).requestFocus();
                return true;
            }
            scheduleHideOsd();
        }

        // If episode sheet is open, manage navigation in episode list
        if (episodeSheetVisible) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                controls.getChildAt(0).requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                focusFirstToolbarButton();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // Navigation between controls and top bar
        if (controls.hasFocus()) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                if (toolbar.getVisibility() == View.VISIBLE && focusFirstToolbarButton()) {
                    return true;
                }
                web.requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                View focused = getCurrentFocus();
                int index = controls.indexOfChild(focused);
                if (index >= 0) {
                    int delta = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    int next = Math.max(0, Math.min(controls.getChildCount() - 1, index + delta));
                    controls.getChildAt(next).requestFocus();
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                View focused = getCurrentFocus();
                if (focused != null && controls.indexOfChild(focused) >= 0) {
                    focused.performClick();
                    return true;
                }
            }
        } else if (toolbar.hasFocus()) {
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                controls.getChildAt(0).requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                List<View> buttons = focusableToolbarButtons();
                View focused = getCurrentFocus();
                int idx = buttons.indexOf(focused);
                if (idx >= 0) {
                    int delta = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    int next = Math.max(0, Math.min(buttons.size() - 1, idx + delta));
                    buttons.get(next).requestFocus();
                    return true;
                }
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                View focused = getCurrentFocus();
                if (focused != null && topBar.indexOfChild(focused) >= 0) {
                    focused.performClick();
                    return true;
                }
            }
        } else if (isDpadKey) {
            controls.getChildAt(0).requestFocus();
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private List<View> focusableToolbarButtons() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < topBar.getChildCount(); i++) {
            View c = topBar.getChildAt(i);
            if (c.isFocusable() && c.getVisibility() == View.VISIBLE) out.add(c);
        }
        return out;
    }

    private boolean focusFirstToolbarButton() {
        List<View> buttons = focusableToolbarButtons();
        if (!buttons.isEmpty()) {
            buttons.get(0).requestFocus();
            return true;
        }
        return false;
    }

    private String startUrl() {
        String requested = getIntent().getStringExtra("url");
        if (requested != null && requested.startsWith(SiteConfig.BASE_URL + "/")) {
            return requested;
        }
        return HOME_URL;
    }

    private void leaveFullscreen() {
        if (fullscreenView == null) return;
        playerArea.removeView(fullscreenView);
        fullscreenView = null;
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        if (callback != null) callback.onCustomViewHidden();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (web != null) web.saveState(out);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideControlsRunnable);
        handler.removeCallbacks(watchdogRunnable);
        bgWorkers.shutdownNow();
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.clearHistory();
            web.removeAllViews();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
