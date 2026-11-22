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
 * metadata, overview, an auto-focused Play button, and optional TV season /
 * episode pickers without running website JavaScript.
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
    private int selectedSeason = 1;
    private int selectedEpisode = 1;

    private ImageView backdropView;
    private ImageView posterView;
    private TextView playButton;
    private LinearLayout tvSection;
    private LinearLayout seasonRow;
    private LinearLayout episodeRow;
    private TextView tvStatus;
    private final List<TextView> seasonButtons = new ArrayList<>();

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

        buildUi();
        loadImage(posterUrl, posterView, 1);
        loadImage(backdropUrl != null ? backdropUrl : posterUrl, backdropView, 2);

        if ("tv".equalsIgnoreCase(mediaType) && !mediaId.isEmpty()
                && SiteConfig.TV_DETAIL_PATH_TEMPLATE != null
                && !SiteConfig.TV_DETAIL_PATH_TEMPLATE.isEmpty()) {
            loadSeasons();
        }
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

        // Top hero row: Poster + Info + Primary CTA
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

        if (!overview.isEmpty()) {
            TextView descView = text(overview, 15, Color.rgb(218, 224, 236), false);
            descView.setMaxLines(5);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            descView.setLineSpacing(0, 1.32f);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descLp.topMargin = dp(14);
            info.addView(descView, descLp);
        }

        // Action row
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(24);

        String playLabel = "tv".equalsIgnoreCase(mediaType) ? "▶   PLAY  S1 E1" : "▶   PLAY NOW";
        playButton = actionButton(playLabel, true, () -> launchPlayer(selectedSeason, selectedEpisode));
        playButton.setContentDescription("Play " + itemTitle);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(230), dp(54));
        playLp.rightMargin = dp(14);
        actions.addView(playButton, playLp);

        TextView backButton = actionButton("←   LIBRARY", false, this::finish);
        backButton.setContentDescription("Back to library");
        actions.addView(backButton, new LinearLayout.LayoutParams(dp(168), dp(54)));

        info.addView(actions, actionsLp);
        hero.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        container.addView(hero, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Optional TV Seasons & Episodes section
        tvSection = new LinearLayout(this);
        tvSection.setOrientation(LinearLayout.VERTICAL);
        tvSection.setVisibility(View.GONE);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(26);

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
        playButton.post(() -> playButton.requestFocus());
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
                    seasonRow.removeAllViews();
                    seasonButtons.clear();
                    for (int s : seasons) {
                        final int seasonNum = s;
                        TextView btn = actionButton("Season " + seasonNum, false, () -> selectSeason(seasonNum));
                        btn.setTag(seasonNum);
                        btn.setTextSize(13);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(116), dp(42));
                        lp.rightMargin = dp(10);
                        seasonRow.addView(btn, lp);
                        seasonButtons.add(btn);
                    }
                    selectSeason(seasons.get(0));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing()) tvSection.setVisibility(View.GONE);
                });
            }
        });
    }

    private void selectSeason(int seasonNum) {
        selectedSeason = seasonNum;
        selectedEpisode = 1;
        updatePlayLabel();
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
                    tvStatus.setText("SEASON " + seasonNum + "  ·  " + episodes.size() + " EPISODES");
                    episodeRow.removeAllViews();
                    for (CatalogScraper.TvEpisode ep : episodes) {
                        episodeRow.addView(episodeCard(ep), episodeCardParams());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isFinishing()) tvStatus.setText("SEASON " + seasonNum);
                });
            }
        });
    }

    private LinearLayout.LayoutParams episodeCardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(236), dp(86));
        lp.rightMargin = dp(12);
        return lp;
    }

    private View episodeCard(CatalogScraper.TvEpisode ep) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setFocusable(true);
        card.setBackground(pillBackground(CARD, ACCENT));

        String header = "E" + ep.episode + (ep.runtimeMinutes > 0 ? "  ·  " + ep.runtimeMinutes + "m" : "");
        TextView top = text(header, 12, FOCUS, true);
        card.addView(top);

        TextView name = text(ep.name, 14, WHITE, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        card.addView(name, lp);

        card.setOnClickListener(v -> {
            selectedSeason = ep.season;
            selectedEpisode = ep.episode;
            updatePlayLabel();
            launchPlayer(ep.season, ep.episode);
        });
        return card;
    }

    private void updatePlayLabel() {
        if ("tv".equalsIgnoreCase(mediaType) && playButton != null) {
            playButton.setText("▶   PLAY  S" + selectedSeason + " E" + selectedEpisode);
        }
    }

    private void launchPlayer(int season, int episode) {
        Intent intent = new Intent(this, WatchActivity.class);
        intent.putExtra("url", watchUrl);
        intent.putExtra("title", itemTitle);
        intent.putExtra("mediaId", mediaId);
        intent.putExtra("mediaType", mediaType);
        intent.putExtra("season", season);
        intent.putExtra("episode", episode);
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
        TextView btn = text(label, 15, WHITE, true);
        btn.setGravity(Gravity.CENTER);
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
