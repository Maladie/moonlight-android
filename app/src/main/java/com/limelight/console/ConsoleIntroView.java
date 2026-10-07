package com.limelight.console;

import android.animation.TimeAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.diagnostics.MoonWakerDiagnostics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Entry intro (UX_MENU_PL.md sections 3.8 and 4.8; mockups 17-intro-1..4 and the intro of
 * make_flow_video.py): covers the first load of the home screen after a profile is chosen,
 * its PIN accepted or the profile gate skipped. The avatar moves to the centre and fades into
 * a soft orb of light, one wide band of light sweeps across, blurred motes rise, then the
 * crescent mark and "Witaj, &lt;profil&gt;" hold until home is ready; home then enters from a
 * 1.04 zoom with its visible covers rising 24 px one after the other.
 *
 * <p>Every layer is a prepared bitmap or a once-drawn view moved, scaled and faded on the GPU.
 * The band, mark and mote bitmaps are built once per process off the main thread and kept;
 * only the profile row's snapshot is made per entry. All geometry is the mockup's, in px at
 * 1080p, and every colour the mockup's fixed violet palette, whatever the profile.
 */
final class ConsoleIntroView extends FrameLayout {
    /** What the intro starts over; only the profile row is snapshotted, never the PIN screen. */
    enum Entry { PROFILE, PIN, AUTOMATIC }

    /** Mockup "gate_backdrop(c1=(130,80,240), c2=(70,100,230))" of the intro frames. */
    static final int BLOOM_PRIMARY = 0xFF8250F0;
    static final int BLOOM_SECONDARY = 0xFF4664E6;
    /** Mockup VIOLET (150,96,255): world, glow and orb; the orb's middle ring (220,200,255). */
    static final int VIOLET = 0xFF9660FF;
    static final int ORB_MIDDLE = 0xFFDCC8FF;
    /** Mockup "Bokeh" colours (60 % the first) and "crescent_mark" / halo colours. */
    static final int MOTE_LIGHT = 0xFFFFECFF;
    static final int MOTE_VIOLET = 0xFFBEA0FF;
    static final int MARK_BOTTOM = 0xFFC4A8FF;
    static final int MARK_HALO = 0xFFC8AAFF;
    static final int BAND_COLOR = 0xFFFFF8FF;
    static final int TEXT = 0xFFF5F6F9;
    static final float CENTRE_X = 960f;
    static final float ORB_Y = 500f;
    static final float MARK_Y = 470f;
    static final float MARK_PX = 132f;
    static final float HALO_MARGIN_PX = 60f;
    static final float HALO_BLUR_PX = 18f;
    static final float GREETING_BASELINE = 604f;
    static final float GREETING_PX = 32f;
    static final float AVATAR_PX = 220f;
    /** The band: 195 px half-width, 0.18 tilt, 22 % at its core, swept x -450..2400. */
    static final float BAND_HALF_WIDTH = 195f;
    static final float BAND_TILT = .18f;
    static final float BAND_ALPHA = .22f;
    static final float BAND_SPAN = 820f;
    static final int BAND_BITMAP_WIDTH = 512;
    static final int BAND_BITMAP_HEIGHT = 540;
    /**
     * make_flow_video.py "Bokeh(n=34, seed=5)" as numpy draws it, in its 1280x720 px:
     * {radius, light, alpha 0..255, left, top, rise px/s} per mote. Each is a disc on a sprite
     * padded 4 px, Gaussian-blurred by 1.2 px with its colour fading to black at the edge.
     */
    static final float[] BOKEH = {
            14.27f, 0, 76, 425.8f, 185.9f, 28.7f, 8.72f, 1, 43, 1139.2f, 473.1f, 24.6f,
            9.09f, 0, 102, 984.2f, 348.4f, 31.8f, 12.47f, 1, 78, 411.5f, 582.2f, 19.8f,
            12.51f, 0, 55, 1035.4f, 578.7f, 18.5f, 12.90f, 1, 75, 576.7f, 257.6f, 27.1f,
            14.29f, 1, 50, 838.5f, 375.3f, 40.4f, 6.30f, 1, 95, 647.1f, 403.1f, 24.6f,
            3.20f, 0, 46, 984.9f, 336.6f, 44.6f, 8.59f, 0, 78, 380.1f, 515.9f, 36.9f,
            12.58f, 1, 55, 780.9f, 211.4f, 37.4f, 11.90f, 1, 95, 334.0f, 347.4f, 40.3f,
            8.33f, 0, 82, 1081.0f, 636.0f, 38.3f, 14.32f, 1, 89, 987.6f, 352.6f, 33.5f,
            9.71f, 0, 62, 542.1f, 160.4f, 29.8f, 11.84f, 0, 104, 467.3f, 634.7f, 23.3f,
            14.53f, 1, 68, 213.5f, 571.9f, 41.2f, 4.96f, 1, 58, 631.8f, 425.5f, 21.0f,
            15.10f, 1, 71, 197.3f, 161.3f, 23.5f, 7.78f, 0, 102, 620.5f, 378.3f, 36.7f,
            15.02f, 1, 95, 539.0f, 445.1f, 38.6f, 10.02f, 0, 88, 145.7f, 178.8f, 22.2f,
            5.99f, 1, 43, 357.2f, 448.5f, 42.8f, 7.89f, 1, 69, 820.3f, 536.9f, 44.3f,
            8.26f, 0, 63, 964.0f, 270.7f, 42.4f, 10.07f, 0, 77, 456.1f, 397.3f, 19.4f,
            3.60f, 1, 73, 973.1f, 169.7f, 33.6f, 9.87f, 1, 91, 664.7f, 430.3f, 31.8f,
            11.61f, 1, 78, 812.4f, 251.7f, 45.7f, 13.45f, 0, 60, 583.5f, 285.5f, 19.3f,
            3.23f, 1, 100, 303.7f, 491.0f, 25.0f, 3.92f, 1, 87, 641.7f, 423.5f, 30.7f,
            6.84f, 1, 104, 341.5f, 511.7f, 25.0f, 5.71f, 1, 46, 1075.4f, 335.3f, 22.9f};
    /** The flow video's 720p px in this view's 1080p px. */
    static final float VIDEO_PX = 1.5f;
    static final float MOTE_PAD_PX = 4f;
    static final float MOTE_BLUR_PX = 1.2f;
    static final float HOME_ZOOM = 1.04f;
    static final float COVER_RISE_PX = 24f;
    static final long COVER_STAGGER_MS = 30L;
    static final long EXIT_FADE_MS = 450L;
    static final long EXIT_ZOOM_MS = 600L;
    /** After the intro ends unready, readiness is still watched this long for the record. */
    static final long READY_WATCH_MS = 30_000L;

    private static Bitmap bandCache;
    private static Bitmap markCache;
    private static Bitmap[] moteCache;

    private final ConsoleEntryShell shell;
    private final ConsoleProfileSelectView profiles;
    private final ConsolePinEntryView pinEntry;
    private final View home;
    private final ViewGroup carousel;
    private final BooleanSupplier reducedMotion;
    private final BooleanSupplier homeData;
    private final Set<String> loadingPosters;
    private final Predicate<Object> artworkShown;
    private final TimeAnimator clock = new TimeAnimator();
    private final List<Runnable> untilShown = new ArrayList<>();
    private boolean drawn;
    private boolean shown;
    private final Set<Integer> swallowed = new HashSet<>();
    private final List<View> covers = new ArrayList<>();
    private final List<Float> coverAlpha = new ArrayList<>();
    private String lastHostId;
    private String hostId;
    private String profileId;
    private boolean running;
    private boolean reduced;
    private long minimumMs;
    private float speed;
    private long confirmedAt;
    private float nowMs;
    private long keyAtMs;
    private long exitAtMs;
    private boolean readyRecorded;
    private float s;
    private Bitmap shot;
    private View snapshot;
    private View content;
    private View backdrop;
    private View glow;
    private View avatar;
    private RectF avatarFrom;
    private View orb;
    private View band;
    private Motes motes;
    private View mark;
    private TextView greeting;

    /**
     * {@code homeData}: the home screen shows its local data; {@code loadingPosters}: artwork
     * tags whose poster is still being fetched or decoded; {@code artworkShown}: for a focused
     * poster's tag, no backdrop is pending and its art is at rest or its request ended without
     * art. The posters on screen are checked here.
     * The intro's bitmaps start building now, off the main thread.
     */
    ConsoleIntroView(Context context, ConsoleEntryShell shell, ConsoleProfileSelectView profiles,
                     ConsolePinEntryView pinEntry, View home, ViewGroup carousel,
                     BooleanSupplier reducedMotion, BooleanSupplier homeData,
                     Set<String> loadingPosters, Predicate<Object> artworkShown) {
        super(context);
        this.shell = shell;
        this.profiles = profiles;
        this.pinEntry = pinEntry;
        this.home = home;
        this.carousel = carousel;
        this.reducedMotion = reducedMotion;
        this.homeData = homeData;
        this.loadingPosters = loadingPosters;
        this.artworkShown = artworkShown;
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        clock.setTimeListener((animation, total, delta) ->
                frame(total / ConsoleIntroTiming.TIME_SCALE));
        float scale = scale();
        Thread prepare = new Thread(() -> {
            bandBitmap();
            markBitmap(scale);
            moteSprites();
            ConsoleEntryShell.bloomBitmap();
        }, "moonwaker-intro");
        prepare.setPriority(Thread.MIN_PRIORITY);
        prepare.start();
    }

    /**
     * Starts over the profile row, the PIN screen or (automatic entry) whatever is shown; call
     * once entry is confirmed and before the home screen replaces that screen. After a PIN or
     * an automatic entry the intro starts on the plain shell base: nothing of the PIN screen
     * is ever copied, as its FLAG_SECURE goes as soon as the shell hides.
     */
    void start(HostGatewayClient.IntegrationProfile profile, String hostId, Entry entry) {
        end();
        boolean hostChange = lastHostId != null && !lastHostId.equals(hostId);
        lastHostId = hostId;
        this.hostId = hostId;
        profileId = profile.id;
        reduced = reducedMotion.getAsBoolean();
        View from = entry == Entry.PIN ? pinEntry.avatar()
                : entry == Entry.PROFILE ? profiles.avatarOf(profile.id) : null;
        minimumMs = hostChange ? ConsoleIntroTiming.HOST_CHANGE_MS : ConsoleIntroTiming.MINIMUM_MS;
        speed = (float) ConsoleIntroTiming.MINIMUM_MS / minimumMs;
        confirmedAt = SystemClock.uptimeMillis();
        keyAtMs = -1L;
        exitAtMs = -1L;
        readyRecorded = false;
        drawn = shown = false;
        MoonWakerDiagnostics.record("INFO", "android.console", "console.entry.confirmed",
                "host_id", hostId, "profile_id", profileId,
                "kind", entry.name().toLowerCase(Locale.ROOT),
                "detail", reduced ? "reduced" : hostChange ? "host_change" : "full");
        s = scale();
        build(profile, from, entry == Entry.PROFILE && shell.getWidth() > 0);
        running = true;
        setAlpha(1f);
        setVisibility(VISIBLE);
        frame(0f);
        clock.start();
    }

    /**
     * Runs {@code action} once a starting intro has been on screen for a frame (e.g. to drop the
     * PIN screen's FLAG_SECURE only after the intro has replaced its last frame), else now.
     */
    static void shown(ConsoleIntroView intro, Runnable action) {
        if (intro == null || !intro.running || intro.shown) action.run();
        else intro.untilShown.add(action);
    }

    /** Whether the intro still covers home, before its exit. */
    boolean holding() {
        return running && exitAtMs < 0L;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!running || drawn) return;
        drawn = true;
        // This frame is recorded now and presented by the next vsync; one more to spare.
        afterTwoFrames(this::presented);
    }

    private void afterTwoFrames(Runnable action) {
        postOnAnimation(() -> postOnAnimation(action));
    }

    private void presented() {
        shown = true;
        List<Runnable> actions = new ArrayList<>(untilShown);
        untilShown.clear();
        for (Runnable action : actions) action.run();
    }

    /**
     * Takes every key while the intro is on screen; afterwards the repeats and release of a
     * key held through it, until that key is pressed anew.
     */
    boolean handleKey(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (!running) {
            if (!swallowed.contains(code)) return false;
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                swallowed.remove(code);
                return false;
            }
            if (action == KeyEvent.ACTION_UP) swallowed.remove(code);
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            swallowed.add(code);
            if (keyAtMs < ConsoleIntroTiming.SKIP_AFTER_MS) keyAtMs = (long) nowMs;
        } else if (action == KeyEvent.ACTION_UP) {
            swallowed.remove(code);
        }
        return true;
    }

    private float scale() {
        return getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    private void build(HostGatewayClient.IntegrationProfile profile, View from,
                       boolean snapshotShell) {
        if (snapshotShell) {
            // Mockup: the intro blends in from the profile row, which home hides at once.
            shot = Bitmap.createBitmap(shell.getWidth() / 2, shell.getHeight() / 2,
                    Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(shot);
            canvas.scale(.5f, .5f);
            shell.draw(canvas);
            snapshot = image(shot);
        } else {
            snapshot = new View(getContext());
            snapshot.setBackground(base());
        }
        addView(snapshot, match());

        FrameLayout layers = new FrameLayout(getContext());
        content = layers;
        addView(layers, match());
        FrameLayout world = new FrameLayout(getContext());
        backdrop = world;
        Spots bloom = new Spots(getContext(),
                new float[]{600f, 380f, 900f, 70f, 1450f, 760f, 820f, 58f},
                new int[]{BLOOM_PRIMARY, BLOOM_SECONDARY});
        bloom.setBackground(base());
        world.addView(bloom, match());
        world.addView(new ConsoleEntryShell.Atmosphere(getContext()), match());
        world.addView(new Spots(getContext(), new float[]{CENTRE_X, ORB_Y, 760f, 80f},
                new int[]{VIOLET}), match());
        layers.addView(world, match());
        glow = add(layers, new Spots(getContext(), new float[]{CENTRE_X, ORB_Y, 420f, 110f},
                new int[]{VIOLET}));
        if (!reduced) {
            avatar = avatar(layers, profile);
            avatarFrom = from == null ? null : rectIn(from);
            orb = add(layers, new Spots(getContext(), new float[]{
                    CENTRE_X, ORB_Y, 300f, 120f, CENTRE_X, ORB_Y, 135f, 160f,
                    CENTRE_X, ORB_Y, 56f, 255f},
                    new int[]{VIOLET, ORB_MIDDLE, Color.WHITE}));
            band = image(bandBitmap());
            layers.addView(band, new LayoutParams(Math.round(BAND_SPAN * s),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            motes = new Motes(getContext(), s);
            layers.addView(motes, match());
        }
        Bitmap crescent = markBitmap(s);
        mark = new ImageView(getContext());
        ((ImageView) mark).setImageBitmap(crescent);
        layers.addView(mark, new LayoutParams(crescent.getWidth(), crescent.getHeight()));
        mark.setTranslationX(CENTRE_X * s - crescent.getWidth() / 2f);
        mark.setTranslationY(MARK_Y * s - crescent.getHeight() / 2f);
        greeting = new TextView(getContext());
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, GREETING_PX * s);
        greeting.setTextColor(TEXT);
        greeting.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        greeting.setIncludeFontPadding(false);
        greeting.setText(getContext().getString(R.string.console_intro_greeting,
                ConsoleProfileSelectView.displayName(getContext(), profile)));
        layers.addView(greeting, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        greeting.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            view.setTranslationX(CENTRE_X * s - (r - l) / 2f);
            view.setTranslationY(GREETING_BASELINE * s - view.getBaseline());
        });
        // A blending layer is composited off screen once per frame instead of per child.
        (reduced ? content : backdrop).setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private static GradientDrawable base() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ConsoleEntryShell.BASE_TOP, ConsoleEntryShell.BASE_BOTTOM});
    }

    /** One frame at {@code ms} on the intro's clock. */
    private void frame(float ms) {
        if (!running) return;
        nowMs = ms;
        boolean ready = ready();
        if (exitAtMs < 0L && ConsoleIntroTiming.leave((long) ms, minimumMs, ready, keyAtMs)) {
            beginExit((long) ms);
        }
        if (reduced) {
            content.setAlpha(ConsoleIntroTiming.clamp(ms / ConsoleIntroTiming.REDUCED_FADE_MS));
        } else {
            phase(ms * speed);
        }
        if (exitAtMs >= 0L) exit(ms - exitAtMs);
    }

    /** make_flow_video.py, step 5, at {@code t} ms into the intro. */
    private void phase(float t) {
        float k = ConsoleIntroTiming.easeInOut(t / 300f);
        backdrop.setAlpha(k);
        if (k >= 1f && backdrop.getLayerType() != LAYER_TYPE_NONE) {
            backdrop.setLayerType(LAYER_TYPE_NONE, null);
        }
        glow.setAlpha(ConsoleIntroTiming.easeOut(t / 400f));
        // The avatar: from where it was to a 220 px disc on the orb in 300 ms, then gone.
        float size = AVATAR_PX * s;
        float fromX = avatarFrom == null ? CENTRE_X * s : avatarFrom.centerX();
        float fromY = avatarFrom == null ? ORB_Y * s : avatarFrom.centerY();
        float fromScale = avatarFrom == null ? 1f : avatarFrom.width() / size;
        float scale = fromScale + (1f - fromScale) * k;
        avatar.setScaleX(scale);
        avatar.setScaleY(scale);
        avatar.setTranslationX(fromX + (CENTRE_X * s - fromX) * k - size / 2f);
        avatar.setTranslationY(fromY + (ORB_Y * s - fromY) * k - size / 2f);
        avatar.setAlpha(1f - ConsoleIntroTiming.easeInOut((t - 300f) / 350f));
        orb.setAlpha(ConsoleIntroTiming.easeOut((t - 300f) / 300f)
                * (1f - ConsoleIntroTiming.easeInOut((t - 750f) / 400f)));
        boolean sweeping = t >= 300f && t <= 950f;
        band.setVisibility(sweeping ? VISIBLE : INVISIBLE);
        float centre = -450f + 2850f * ConsoleIntroTiming.easeInOut((t - 300f) / 650f);
        band.setTranslationX((centre - BAND_SPAN / 2f) * s);
        motes.setAlpha(ConsoleIntroTiming.easeOut((t - 300f) / 300f) * .9f);
        // Still motes while leaving: each redraw would repaint the whole fading layer.
        if (exitAtMs < 0L) motes.setTime(t - 300f);
        float shown = ConsoleIntroTiming.easeOut((t - 750f) / 350f);
        mark.setAlpha(shown);
        greeting.setAlpha(shown);
    }

    private void beginExit(long ms) {
        exitAtMs = ms;
        removeView(snapshot);
        snapshot = null;
        backdrop.setLayerType(LAYER_TYPE_NONE, null);
        content.setLayerType(LAYER_TYPE_NONE, null);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        focusCarousel();
        if (reduced) return;
        home.setPivotX(home.getWidth() / 2f);
        home.setPivotY(home.getHeight() / 2f);
        android.graphics.Rect visible = new android.graphics.Rect();
        for (int index = 0; index < carousel.getChildCount(); index++) {
            View cover = carousel.getChildAt(index);
            if (cover.getVisibility() != VISIBLE || !cover.getLocalVisibleRect(visible)) continue;
            covers.add(cover);
            coverAlpha.add(cover.getAlpha());
        }
    }

    /** Mockup exit: 450 ms cross-fade, zoom 1.04 to 1 in 600 ms, covers rise 30 ms apart. */
    private void exit(float e) {
        if (reduced) {
            setAlpha(1f - ConsoleIntroTiming.clamp(e / ConsoleIntroTiming.REDUCED_FADE_MS));
            if (e >= ConsoleIntroTiming.REDUCED_FADE_MS) end();
            return;
        }
        setAlpha(1f - ConsoleIntroTiming.easeInOut(e / EXIT_FADE_MS));
        float zoom = HOME_ZOOM - (HOME_ZOOM - 1f) * ConsoleIntroTiming.easeOut(e / EXIT_ZOOM_MS);
        home.setScaleX(zoom);
        home.setScaleY(zoom);
        for (int index = 0; index < covers.size(); index++) {
            float k = ConsoleIntroTiming.easeOut((e - COVER_STAGGER_MS * index) / EXIT_FADE_MS);
            covers.get(index).setTranslationY(COVER_RISE_PX * s * (1f - k));
            covers.get(index).setAlpha(coverAlpha.get(index) * k);
        }
        if (e >= Math.max(EXIT_ZOOM_MS,
                EXIT_FADE_MS + COVER_STAGGER_MS * Math.max(0, covers.size() - 1))) end();
    }

    /** Stops at once, leaving home at rest; the per-entry snapshot is released. */
    void end() {
        clock.end();
        if (running) {
            home.setScaleX(1f);
            home.setScaleY(1f);
            for (int index = 0; index < covers.size(); index++) {
                covers.get(index).setTranslationY(0f);
                covers.get(index).setAlpha(coverAlpha.get(index));
            }
            if (!readyRecorded) watchReadiness();
        }
        running = false;
        // Ended before it was seen: what waited on it still waits for frames of home.
        if (!untilShown.isEmpty()) afterTwoFrames(this::presented);
        covers.clear();
        coverAlpha.clear();
        removeAllViews();
        setLayerType(LAYER_TYPE_NONE, null);
        setVisibility(GONE);
        snapshot = content = backdrop = glow = avatar = orb = band = mark = null;
        motes = null;
        greeting = null;
        if (shot != null) shot.recycle();
        shot = null;
    }

    /**
     * Mockup: home enters on its first carousel card with that game's art behind. Keys are held
     * back while the intro runs, so focus elsewhere (e.g. the host chip after the entry screen
     * hid) is not the user's.
     */
    private void focusCarousel() {
        if (!running || !carousel.isShown() || carousel.hasFocus()) return;
        for (int index = 0; index < carousel.getChildCount(); index++) {
            View card = carousel.getChildAt(index);
            if (card.isFocusable() && card.getVisibility() == VISIBLE) {
                card.requestFocus();
                return;
            }
        }
    }

    /**
     * Home is ready once its local data and carousel are shown, no poster on screen is still
     * loading and, with the carousel focused while covered, the focused game's art is at rest
     * behind it, so the exit cross-fade reveals it; the first time, the moment is recorded.
     */
    private boolean ready() {
        if (readyRecorded) return true;
        // Until home is shown its carousel cannot take focus, so no game's art is asked for.
        if (!homeData.getAsBoolean() || !carousel.isShown() || !postersSettled()) return false;
        focusCarousel();
        // A backdrop is asked for only once its card shows a poster: without one there is none.
        View focused = carousel.getFocusedChild();
        ImageView poster = focused == null ? null : poster(focused);
        if (poster != null && poster.getDrawable() instanceof BitmapDrawable
                && !artworkShown.test(poster.getTag(R.id.playnite_artwork_key))) {
            return false;
        }
        readyRecorded = true;
        MoonWakerDiagnostics.record("INFO", "android.console", "console.home.ready",
                "host_id", hostId, "profile_id", profileId,
                "duration_ms", SystemClock.uptimeMillis() - confirmedAt,
                "state", running && exitAtMs < 0L ? "intro_waiting" : "after_intro");
        return true;
    }

    private void watchReadiness() {
        if (ready() || SystemClock.uptimeMillis() - confirmedAt > READY_WATCH_MS) return;
        String expected = profileId;
        postDelayed(() -> {
            if (!running && expected.equals(profileId)) watchReadiness();
        }, 100L);
    }

    /** Every poster on screen is decoded, or has no load running (no artwork, or it failed). */
    private boolean postersSettled() {
        android.graphics.Rect visible = new android.graphics.Rect();
        for (int index = 0; index < carousel.getChildCount(); index++) {
            View cover = carousel.getChildAt(index);
            if (cover.getVisibility() != VISIBLE || !cover.getLocalVisibleRect(visible)) continue;
            ImageView poster = poster(cover);
            if (poster != null && !(poster.getDrawable() instanceof BitmapDrawable)
                    && loadingPosters.contains(poster.getTag(R.id.playnite_artwork_key))) {
                return false;
            }
        }
        return true;
    }

    private static ImageView poster(View view) {
        if (view instanceof ImageView && view.getTag(R.id.playnite_artwork_key) != null) {
            return (ImageView) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            ImageView found = poster(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private View avatar(FrameLayout layers, HostGatewayClient.IntegrationProfile profile) {
        FrameLayout frame = new FrameLayout(getContext());
        int size = Math.round(AVATAR_PX * s);
        ConsoleProfileSelectView.addAvatar(frame, profile,
                size / getResources().getDisplayMetrics().density);
        layers.addView(frame, new LayoutParams(size, size));
        frame.setPivotX(size / 2f);
        frame.setPivotY(size / 2f);
        return frame;
    }

    /** The avatar disc of {@code view} (inside its padding), in this view's coordinates. */
    private RectF rectIn(View view) {
        float[] points = {view.getPaddingLeft(), view.getPaddingTop(),
                view.getWidth() - view.getPaddingRight(),
                view.getHeight() - view.getPaddingBottom()};
        toRoot(view, points);
        float[] origin = {0f, 0f, 0f, 0f};
        toRoot(this, origin);
        return new RectF(points[0] - origin[0], points[1] - origin[1],
                points[2] - origin[0], points[3] - origin[1]);
    }

    private static void toRoot(View view, float[] points) {
        while (true) {
            view.getMatrix().mapPoints(points);
            for (int index = 0; index < points.length; index += 2) {
                points[index] += view.getLeft();
                points[index + 1] += view.getTop();
            }
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return;
            view = (View) parent;
            for (int index = 0; index < points.length; index += 2) {
                points[index] -= view.getScrollX();
                points[index + 1] -= view.getScrollY();
            }
        }
    }

    private View add(FrameLayout layers, View view) {
        layers.addView(view, match());
        return view;
    }

    private ImageView image(Bitmap bitmap) {
        ImageView view = new ImageView(getContext());
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setImageBitmap(bitmap);
        return view;
    }

    /**
     * Mockup "band": a soft tilted band, exp(-2.5 d^2) across its width, brightest a little
     * above the middle; drawn once at half resolution and moved across the screen.
     */
    private static synchronized Bitmap bandBitmap() {
        if (bandCache != null) return bandCache;
        int[] pixels = new int[BAND_BITMAP_WIDTH * BAND_BITMAP_HEIGHT];
        for (int y = 0; y < BAND_BITMAP_HEIGHT; y++) {
            float py = (y + .5f) / BAND_BITMAP_HEIGHT * 1080f;
            float vertical = .4f + .6f * (float) Math.exp(-Math.pow((py - 486f) / 540f, 2));
            for (int x = 0; x < BAND_BITMAP_WIDTH; x++) {
                float px = (x + .5f) / BAND_BITMAP_WIDTH * BAND_SPAN - BAND_SPAN / 2f;
                float d = (px - (py - 540f) * BAND_TILT) / BAND_HALF_WIDTH;
                float a = (float) Math.exp(-2.5f * d * d) * BAND_ALPHA * vertical;
                pixels[y * BAND_BITMAP_WIDTH + x] = Math.round(255 * a) << 24
                        | BAND_COLOR & 0xFFFFFF;
            }
        }
        bandCache = Bitmap.createBitmap(pixels, BAND_BITMAP_WIDTH, BAND_BITMAP_HEIGHT,
                Bitmap.Config.ARGB_8888);
        return bandCache;
    }

    /**
     * One sprite per mote, as the flow video's: alpha a x c and colour x c, c the blurred
     * disc's coverage (a Gaussian edge, logistic approximation), in 1080p px.
     */
    private static synchronized Bitmap[] moteSprites() {
        if (moteCache != null) return moteCache;
        Bitmap[] sprites = new Bitmap[BOKEH.length / 6];
        float sigma = MOTE_BLUR_PX * VIDEO_PX;
        for (int mote = 0; mote < sprites.length; mote++) {
            float radius = BOKEH[mote * 6] * VIDEO_PX;
            int size = Math.round(2f * (radius + MOTE_PAD_PX * VIDEO_PX));
            float centre = size / 2f;
            float alpha = BOKEH[mote * 6 + 2];
            int color = BOKEH[mote * 6 + 1] != 0f ? MOTE_LIGHT : MOTE_VIOLET;
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float d = (float) Math.hypot(x + .5f - centre, y + .5f - centre);
                    float c = 1f / (1f + (float) Math.exp(1.702f * (d - radius) / sigma));
                    pixels[y * size + x] = Math.round(alpha * c) << 24
                            | Math.round(Color.red(color) * c) << 16
                            | Math.round(Color.green(color) * c) << 8
                            | Math.round(Color.blue(color) * c);
                }
            }
            sprites[mote] = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        }
        moteCache = sprites;
        return sprites;
    }

    /**
     * Mockup "crescent_mark" on its blurred halo: a disc less an offset disc, white at the top
     * to violet at the bottom, over the same shape tinted and blurred by 18 px.
     */
    private static synchronized Bitmap markBitmap(float s) {
        float markSize = MARK_PX * s;
        float margin = HALO_MARGIN_PX * s;
        int size = Math.round(markSize + 2 * margin);
        if (markCache != null && markCache.getWidth() == size) return markCache;
        Path crescent = new Path();
        crescent.addOval(new RectF(margin, margin, margin + markSize, margin + markSize),
                Path.Direction.CW);
        Path bite = new Path();
        bite.addOval(new RectF(margin + .28f * markSize, margin - .12f * markSize,
                margin + 1.18f * markSize, margin + .78f * markSize), Path.Direction.CW);
        crescent.op(bite, Path.Op.DIFFERENCE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawPath(crescent, paint);
        int[] pixels = new int[size * size];
        out.getPixels(pixels, 0, size, 0, 0, size, size);
        for (int index = 0; index < pixels.length; index++) pixels[index] >>>= 24;
        blur(pixels, size, size, Math.max(1, Math.round(HALO_BLUR_PX * s)));
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = pixels[index] << 24 | MARK_HALO & 0xFFFFFF;
        }
        out.setPixels(pixels, 0, size, 0, 0, size, size);
        paint.setShader(new LinearGradient(0f, margin, 0f, margin + markSize,
                Color.WHITE, MARK_BOTTOM, Shader.TileMode.CLAMP));
        new Canvas(out).drawPath(crescent, paint);
        markCache = out;
        return out;
    }

    /** Three box passes approximate a Gaussian of standard deviation {@code radius}. */
    private static void blur(int[] values, int width, int height, int radius) {
        int[] scratch = new int[values.length];
        for (int pass = 0; pass < 3; pass++) {
            box(values, scratch, width, height, radius, 1, width);
            box(scratch, values, height, width, radius, width, 1);
        }
    }

    /** A box along lines of {@code length} values {@code step} apart, {@code stride} apart. */
    private static void box(int[] from, int[] to, int length, int lines, int radius, int step,
                            int stride) {
        int span = 2 * radius + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * stride;
            int sum = 0;
            for (int index = -radius; index <= radius; index++) {
                if (index >= 0 && index < length) sum += from[base + index * step];
            }
            for (int index = 0; index < length; index++) {
                to[base + index * step] = sum / span;
                int out = index - radius;
                int in = index + radius + 1;
                if (out >= 0) sum -= from[base + out * step];
                if (in < length) sum += from[base + in * step];
            }
        }
    }

    private static LayoutParams match() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * Mockup "radial" spots, drawn once: {cx, cy, radius, alpha 0..255} per spot at 1080p,
     * each the shell's cached falloff bitmap in its colour.
     */
    private static final class Spots extends View {
        private final float[] spots;
        private final Paint[] paints;
        private final RectF rect = new RectF();

        Spots(Context context, float[] spots, int[] colors) {
            super(context);
            this.spots = spots;
            paints = new Paint[colors.length];
            for (int index = 0; index < colors.length; index++) {
                paints[index] = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                paints[index].setColorFilter(new PorterDuffColorFilter(colors[index],
                        PorterDuff.Mode.SRC_IN));
                paints[index].setAlpha(Math.round(spots[index * 4 + 3]));
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float scale = getWidth() / 1920f;
            for (int index = 0; index < paints.length; index++) {
                float cx = spots[index * 4] * scale;
                float cy = spots[index * 4 + 1] * scale;
                float radius = spots[index * 4 + 2] * scale;
                rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawBitmap(ConsoleEntryShell.bloomBitmap(), null, rect, paints[index]);
            }
        }
    }

    /** The flow video's motes, each its cached sprite placed and risen as the video does. */
    private static final class Motes extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final float scale;
        private final Bitmap[] sprites = moteSprites();
        private final RectF rect = new RectF();
        private float time;

        Motes(Context context, float scale) {
            super(context);
            this.scale = scale;
        }

        void setTime(float ms) {
            time = Math.max(0f, ms);
            if (getAlpha() > 0f) invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int mote = 0; mote < sprites.length; mote++) {
                float r = BOKEH[mote * 6];
                float half = sprites[mote].getWidth() / 2f;
                float x = (BOKEH[mote * 6 + 3] + MOTE_PAD_PX + r) * VIDEO_PX;
                float y = (BOKEH[mote * 6 + 4] + MOTE_PAD_PX + r
                        - BOKEH[mote * 6 + 5] * time / 1000f) * VIDEO_PX;
                rect.set((x - half) * scale, (y - half) * scale, (x + half) * scale,
                        (y + half) * scale);
                canvas.drawBitmap(sprites[mote], null, rect, paint);
            }
        }
    }
}
