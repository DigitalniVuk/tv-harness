package tv.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native TV detail screen shown between CatalogActivity and WatchActivity when
 * {@link SiteConfig#SHOW_DETAIL_SCREEN} is enabled. Displays poster, backdrop,
 * metadata, overview, resume/play/start-over actions from {@link WatchHistoryStore},
 * and optional TV season / episode pickers without running website JavaScript.
 */
public final class DetailActivity extends Activity {
    private static final int BG = Color.rgb(10, 12, 18);
    private static final int CARD = Color.rgb(26, 31, 43);
    private static final int ACCENT = Color.rgb(126, 91, 242);
    private static final int FOCUS = Color.rgb(83, 184, 255);
    private static final int WHITE = Color.rgb(244, 246, 250);
    private static final int MUTED = Color.rgb(180, 190, 208);

    private final ExecutorService workers = Executors.newFixedThreadPool(3);

    private String itemTitle;
    private String watchUrl;
    private String mediaId;
    private String mediaType;
    private String posterUrl;
    private String backdropUrl;
    private String overview;
    private String details;
    private String year;
    private String rating;
    private String historyKey;

    private int selectedSeason = 1;
    private int selectedEpisode = 1;
    private String selectedEpisodeTitle = "";
    private WatchHistoryStore.Entry savedHistory;
    private List<CatalogScraper.TvEpisode> currentSeasonEpisodes = new ArrayList<>();

    private ImageView backdropView;
    private ImageView posterView;
    private TextView progressSummaryView;
    private LinearLayout actionsRow;
    private TextView primaryButton;
    private LinearLayout tvSection;
    private LinearLayout seasonRow;
    private LinearLayout episodeRow;
    private TextView tvStatus;
    private final List<TextView> seasonButtons = new ArrayList<>();
    private boolean seasonsLoaded = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent i = getIntent();
        itemTitle = nonNull(i.getStringExtra("title"), SiteConfig.DISPLAY_NAME);
        watchUrl = nonNull(i.getStringExtra("url"), "");
        mediaId = nonNull(i.getStringExtra("mediaId"), "");
        mediaType = nonNull(i.getStringExtra("mediaType"), "movie");
        posterUrl = i.getStringExtra("posterUrl");
        backdropUrl = i.getStringExtra("backdropUrl");
        overview = nonNull(i.getStringExtra("overview"), "");
        details = nonNull(i.getStringExtra("details"), "");
        year = nonNull(i.getStringExtra("year"), "");
        rating = nonNull(i.getStringExtra("rating"), "");
        historyKey = WatchHistoryStore.canonicalKey(mediaType, mediaId, watchUrl);

        savedHistory = SiteConfig.ENABLE_WATCH_HISTORY ? WatchHistoryStore.get(historyKey) : null;
        if (savedHistory != null && isTv()) {
            selectedSeason = savedHistory.season;
            selectedEpisode = savedHistory.completed ? (savedHistory.episode + 1) : savedHistory.episode;
            selectedEpisodeTitle = savedHistory.completed ? "" : savedHistory.episodeTitle;
        }

        buildUi();
        loadImage(posterUrl, posterView, 1);
        loadImage(backdropUrl != null ? backdropUrl : posterUrl, backdropView, 2);

        if (isTv() && !mediaId.isEmpty()
                && SiteConfig.TV_DETAIL_PATH_TEMPLATE != null
                && !SiteConfig.TV_DETAIL_PATH_TEMPLATE.isEmpty()) {
            loadSeasons();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!SiteConfig.ENABLE_WATCH_HISTORY) return;
        WatchHistoryStore.Entry latest = WatchHistoryStore.get(historyKey);
        boolean changed = (savedHistory == null && latest != null)
                || (savedHistory != null && latest == null)
                || (savedHistory != null && latest != null
                && (savedHistory.updatedAt != latest.updatedAt
                || savedHistory.positionSec != latest.positionSec
                || savedHistory.season != latest.season
                || savedHistory.episode != latest.episode
                || savedHistory.completed != latest.completed));
        savedHistory = latest;
        if (changed) {
            if (savedHistory != null && isTv()) {
                selectedSeason = savedHistory.season;
                selectedEpisode = savedHistory.completed ? (savedHistory.episode + 1) : savedHistory.episode;
                selectedEpisodeTitle = savedHistory.completed ? "" : savedHistory.episodeTitle;
            }
            rebuildActions(true);
            if (seasonsLoaded && !currentSeasonEpisodes.isEmpty()) {
                renderEpisodeCards();
            }
        }
    }

    private boolean isTv() {
        return "tv".equalsIgnoreCase(mediaType);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        backdropView = new ImageView(this);
        backdropView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropView.setAlpha(0.32f);
        root.addView(backdropView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(170, 10, 12, 18), Color.argb(235, 10, 12, 18), Color.rgb(10, 12, 18)}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(44), dp(34), dp(44), dp(30));

        // Top hero row: Poster + Info + Actions
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.TOP);

        FrameLayout posterFrame = new FrameLayout(this);
        GradientDrawable posterBg = new GradientDrawable();
        posterBg.setColor(CARD);
        posterBg.setCornerRadius(dp(14));
        posterBg.setStroke(dp(1), Color.argb(70, 255, 255, 255));
        posterFrame.setBackground(posterBg);
        posterFrame.setClipToOutline(true);

        posterView = new ImageView(this);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterFrame.addView(posterView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams posterLp = new LinearLayout.LayoutParams(dp(200), dp(300));
        posterLp.rightMargin = dp(34);
        hero.addView(posterFrame, posterLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView brand = text(SiteConfig.DISPLAY_NAME.toUpperCase(), 13, FOCUS, true);
        info.addView(brand);

        TextView titleView = text(itemTitle, 30, WHITE, true);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(4);
        info.addView(titleView, titleLp);

        if (!details.isEmpty()) {
            TextView metaView = text(details, 15, MUTED, true);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaLp.topMargin = dp(8);
            info.addView(metaView, metaLp);
        }

        progressSummaryView = text("", 14, FOCUS, true);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progLp.topMargin = dp(6);
        progressSummaryView.setVisibility(View.GONE);
        info.addView(progressSummaryView, progLp);

        if (!overview.isEmpty()) {
            TextView descView = text(overview, 15, Color.rgb(218, 224, 236), false);
            descView.setMaxLines(4);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            descView.setLineSpacing(0, 1.32f);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descLp.topMargin = dp(12);
            info.addView(descView, descLp);
        }

        // Dynamic Action Row
        actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(20);
        info.addView(actionsRow, actionsLp);

        rebuildActions(true);

        hero.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        container.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Optional TV Seasons & Episodes section
        tvSection = new LinearLayout(this);
        tvSection.setOrientation(LinearLayout.VERTICAL);
        tvSection.setVisibility(View.GONE);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(24);

        tvStatus = text("SEASONS & EPISODES", 16, WHITE, true);
        tvSection.addView(tvStatus);

        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonRow = new LinearLayout(this);
        seasonRow.setOrientation(LinearLayout.HORIZONTAL);
        seasonRow.setPadding(0, dp(10), 0, dp(10));
        seasonScroll.addView(seasonRow);
        tvSection.addView(seasonScroll);

        HorizontalScrollView epScroll = new HorizontalScrollView(this);
        epScroll.setHorizontalScrollBarEnabled(false);
        episodeRow = new LinearLayout(this);
        episodeRow.setOrientation(LinearLayout.HORIZONTAL);
        episodeRow.setPadding(0, dp(6), 0, dp(10));
        epScroll.addView(episodeRow);
        tvSection.addView(epScroll);

        container.addView(tvSection, tvLp);
        scroll.addView(container);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void rebuildActions(boolean requestPrimaryFocus) {
        if (actionsRow == null) return;
        actionsRow.removeAllViews();

        boolean hasInProgress = savedHistory != null && savedHistory.isInProgress();
        boolean hasCompleted = savedHistory != null && savedHistory.completed;

        if (progressSummaryView != null) {
            if (hasInProgress) {
                String epPrefix = isTv() ? ("S" + savedHistory.season + " E" + savedHistory.episode + "  ·  ") : "";
                progressSummaryView.setText(epPrefix + "Watched " + WatchHistoryStore.formatTime(savedHistory.positionSec)
                        + " of " + WatchHistoryStore.formatTime(savedHistory.durationSec)
                        + "  ·  " + savedHistory.remainingLabel());
                progressSummaryView.setVisibility(View.VISIBLE);
            } else if (hasCompleted) {
                String epPrefix = isTv() ? ("S" + savedHistory.season + " E" + savedHistory.episode + "  ·  ") : "";
                progressSummaryView.setText(epPrefix + "✓ Watched");
                progressSummaryView.setVisibility(View.VISIBLE);
            } else {
                progressSummaryView.setVisibility(View.GONE);
            }
        }

        if (hasInProgress) {
            final int resumeSeason = isTv() ? savedHistory.season : 1;
            final int resumeEpisode = isTv() ? savedHistory.episode : 1;
            final double resumePos = savedHistory.positionSec;
            String label = isTv()
                    ? ("▶  RESUME S" + resumeSeason + " E" + resumeEpisode + " · " + WatchHistoryStore.formatTime(resumePos))
                    : ("▶  RESUME (" + WatchHistoryStore.formatTime(resumePos) + ")");
            primaryButton = actionButton(label, true, () -> launchPlayer(resumeSeason, resumeEpisode, resumePos, false));
            primaryButton.setContentDescription("Resume " + itemTitle);
            LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
            playLp.rightMargin = dp(12);
            actionsRow.addView(primaryButton, playLp);

            TextView startOverBtn = actionButton("↺  START OVER", false,
                    () -> launchPlayer(resumeSeason, resumeEpisode, 0.0, true));
            startOverBtn.setContentDescription("Start over from beginning");
            LinearLayout.LayoutParams soLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
            soLp.rightMargin = dp(12);
            actionsRow.addView(startOverBtn, soLp);
        } else if (hasCompleted && isTv()) {
            final int nextSeason = selectedSeason;
            final int nextEpisode = selectedEpisode;
            primaryButton = actionButton("▶  PLAY S" + nextSeason + " E" + nextEpisode, true,
                    () -> launchPlayer(nextSeason, nextEpisode, 0.0, true));
            LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
            playLp.rightMargin = dp(12);
            actionsRow.addView(primaryButton, playLp);

            final int prevS = savedHistory.season;
            final int prevE = savedHistory.episode;
            TextView rewatchBtn = actionButton("↺  REWATCH S" + prevS + " E" + prevE, false,
                    () -> launchPlayer(prevS, prevE, 0.0, true));
            LinearLayout.LayoutParams rwLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
            rwLp.rightMargin = dp(12);
            actionsRow.addView(rewatchBtn, rwLp);
        } else {
            String playLabel = isTv()
                    ? ("▶  PLAY S" + selectedSeason + " E" + selectedEpisode)
                    : (hasCompleted ? "▶  WATCH AGAIN" : "▶  PLAY NOW");
            primaryButton = actionButton(playLabel, true,
                    () -> launchPlayer(selectedSeason, selectedEpisode, 0.0, hasCompleted));
            primaryButton.setContentDescription("Play " + itemTitle);
            LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
            playLp.rightMargin = dp(12);
            actionsRow.addView(primaryButton, playLp);
        }

        TextView backButton = actionButton("←  LIBRARY", false, this::finish);
        backButton.setContentDescription("Back to library");
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        backLp.rightMargin = dp(12);
        actionsRow.addView(backButton, backLp);

        if (savedHistory != null && SiteConfig.ENABLE_WATCH_HISTORY) {
            TextView removeBtn = actionButton("✕  REMOVE", false, () -> {
                WatchHistoryStore.remove(historyKey);
                savedHistory = null;
                Toast.makeText(this, "Removed from watch history", Toast.LENGTH_SHORT).show();
                rebuildActions(true);
                if (seasonsLoaded && !currentSeasonEpisodes.isEmpty()) renderEpisodeCards();
            });
            removeBtn.setContentDescription("Remove from watch history");
            actionsRow.addView(removeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        }

        if (requestPrimaryFocus && primaryButton != null) {
            final View target = primaryButton;
            target.post(target::requestFocus);
        }
    }

    private void loadSeasons() {
        tvSection.setVisibility(View.VISIBLE);
        tvStatus.setText("Loading seasons…");
        workers.execute(() -> {
            try {
                List<Integer> seasons = CatalogScraper.fetchTvSeasons(mediaId);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (seasons.isEmpty()) {
                        tvSection.setVisibility(View.GONE);
                        return;
                    }
                    seasonsLoaded = true;
                    seasonRow.removeAllViews();
                    seasonButtons.clear();
                    int initialSeason = seasons.get(0);
                    for (int s : seasons) {
                        if (s == selectedSeason) initialSeason = s;
                        final int seasonNum = s;
                        TextView btn = actionButton("Season " + seasonNum, false, () -> selectSeason(seasonNum, true));
                        btn.setTag(seasonNum);
                        btn.setTextSize(13);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(116), dp(42));
                        lp.rightMargin = dp(10);
                        seasonRow.addView(btn, lp);
                        seasonButtons.add(btn);
                    }
                    selectSeason(initialSeason, false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing()) tvSection.setVisibility(View.GONE);
                });
            }
        });
    }

    private void selectSeason(int seasonNum, boolean userPicked) {
        selectedSeason = seasonNum;
        if (userPicked) {
            selectedEpisode = 1;
            selectedEpisodeTitle = "";
            rebuildActions(false);
        }
        for (TextView btn : seasonButtons) {
            btn.setSelected(Integer.valueOf(seasonNum).equals(btn.getTag()));
        }
        tvStatus.setText("SEASON " + seasonNum + "  ·  Loading episodes…");
        episodeRow.removeAllViews();
        workers.execute(() -> {
            try {
                List<CatalogScraper.TvEpisode> episodes = CatalogScraper.fetchTvEpisodes(mediaId, seasonNum);
                runOnUiThread(() -> {
                    if (isFinishing() || selectedSeason != seasonNum) return;
                    currentSeasonEpisodes = episodes;
                    if (!episodes.isEmpty() && selectedEpisode > episodes.size()) {
                        selectedEpisode = episodes.get(episodes.size() - 1).episode;
                        rebuildActions(false);
                    }
                    tvStatus.setText("SEASON " + seasonNum + "  ·  " + episodes.size() + " EPISODES");
                    renderEpisodeCards();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing()) tvStatus.setText("SEASON " + seasonNum);
                });
            }
        });
    }

    private void renderEpisodeCards() {
        episodeRow.removeAllViews();
        for (CatalogScraper.TvEpisode ep : currentSeasonEpisodes) {
            episodeRow.addView(episodeCard(ep), episodeCardParams());
        }
    }

    private LinearLayout.LayoutParams episodeCardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(242), dp(92));
        lp.rightMargin = dp(12);
        return lp;
    }

    private View episodeCard(CatalogScraper.TvEpisode ep) {
        boolean matchesHistory = savedHistory != null
                && savedHistory.season == ep.season
                && savedHistory.episode == ep.episode;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(8));
        card.setFocusable(true);
        card.setBackground(pillBackground(matchesHistory ? Color.rgb(36, 40, 62) : CARD, ACCENT));

        String statusTag = "";
        if (matchesHistory) {
            statusTag = savedHistory.completed
                    ? "  ·  ✓ Watched"
                    : ("  ·  " + WatchHistoryStore.formatTime(savedHistory.positionSec));
        }
        String header = "E" + ep.episode
                + (ep.runtimeMinutes > 0 ? "  ·  " + ep.runtimeMinutes + "m" : "")
                + statusTag;
        TextView top = text(header, 12, FOCUS, true);
        card.addView(top);

        TextView name = text(ep.name, 14, WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = dp(4);
        card.addView(name, lp);

        if (matchesHistory && savedHistory.progressFraction() > 0f) {
            View track = createProgressStrip(savedHistory.progressFraction());
            LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
            progLp.topMargin = dp(4);
            card.addView(track, progLp);
        }

        card.setOnClickListener(v -> {
            selectedSeason = ep.season;
            selectedEpisode = ep.episode;
            selectedEpisodeTitle = ep.name;
            double resumeSec = (matchesHistory && savedHistory.isInProgress()) ? savedHistory.positionSec : 0.0;
            launchPlayer(ep.season, ep.episode, resumeSec, resumeSec <= 0.0);
        });
        return card;
    }

    private View createProgressStrip(float fraction) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(95, 255, 255, 255));
        bg.setCornerRadius(dp(2));
        track.setBackground(bg);

        float clamped = Math.max(0.03f, Math.min(1.0f, fraction));
        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(FOCUS);
        fg.setCornerRadius(dp(2));
        fill.setBackground(fg);
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, clamped));
        if (clamped < 1.0f) {
            View rest = new View(this);
            track.addView(rest, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f - clamped));
        }
        return track;
    }

    private void launchPlayer(int season, int episode, double resumePosSec, boolean startOver) {
        Intent intent = new Intent(this, WatchActivity.class);
        intent.putExtra("url", watchUrl);
        intent.putExtra("title", itemTitle);
        intent.putExtra("mediaId", mediaId);
        intent.putExtra("mediaType", mediaType);
        intent.putExtra("posterUrl", posterUrl);
        intent.putExtra("backdropUrl", backdropUrl);
        intent.putExtra("overview", overview);
        intent.putExtra("details", details);
        intent.putExtra("year", year);
        intent.putExtra("rating", rating);
        intent.putExtra("season", season);
        intent.putExtra("episode", episode);
        intent.putExtra("episodeTitle", selectedEpisodeTitle);
        intent.putExtra("resumePosSec", resumePosSec);
        intent.putExtra("startOver", startOver);
        if (savedHistory != null && !startOver) {
            intent.putExtra("preferredServerIdx", savedHistory.serverIdx);
        }
        startActivity(intent);
    }

    private void loadImage(String url, ImageView target, int sampleSize) {
        if (url == null || url.isEmpty()) return;
        boolean allowed = false;
        for (String prefix : SiteConfig.ALLOWED_IMAGE_PREFIXES) {
            if (url.startsWith(prefix)) { allowed = true; break; }
        }
        if (!allowed) return;
        workers.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(12000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", SiteConfig.USER_AGENT);
                try (InputStream in = conn.getInputStream()) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sampleSize;
                    Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
                    if (bmp != null) {
                        runOnUiThread(() -> {
                            if (!isFinishing()) target.setImageBitmap(bmp);
                        });
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private TextView actionButton(String label, boolean primary, Runnable click) {
        TextView btn = text(label, 14, WHITE, true);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setPadding(dp(20), 0, dp(20), 0);
        btn.setMinWidth(dp(136));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setBackground(pillBackground(primary ? Color.rgb(92, 65, 198) : Color.rgb(41, 46, 62), ACCENT));
        btn.setOnClickListener(v -> click.run());
        return btn;
    }

    private StateListDrawable pillBackground(int normalColor, int focusedColor) {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setCornerRadius(dp(12));
        normal.setStroke(dp(1), Color.argb(65, 255, 255, 255));

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(focusedColor);
        focused.setCornerRadius(dp(12));
        focused.setStroke(dp(3), FOCUS);

        GradientDrawable selected = new GradientDrawable();
        selected.setColor(Color.rgb(55, 45, 85));
        selected.setCornerRadius(dp(12));
        selected.setStroke(dp(2), ACCENT);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_pressed}, focused);
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, normal);
        return states;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private static String nonNull(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        workers.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
