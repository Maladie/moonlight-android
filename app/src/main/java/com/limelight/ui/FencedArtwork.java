package com.limelight.ui;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A cover's artwork: the cached one shows at once (no flash on a handoff); any other decodes
 * off the UI thread and shows only while its request is still the cover's latest and the cover
 * is not stopped. A late decode of an older request never paints, and nothing waits for a
 * decode.
 */
public final class FencedArtwork<T> {
    /** The last decoded artwork, shared by every cover so the next window shows it at once. */
    public static final class Cache<T> {
        private String path = "";
        private T value;

        synchronized T get(String requested) {
            return value != null && path.equals(requested) ? value : null;
        }

        synchronized void put(String decoded, T artwork) {
            path = decoded;
            value = artwork;
        }
    }

    private final Cache<T> cache;
    private final Function<String, T> decoder;
    private final Executor decodeExecutor;
    private final Executor uiExecutor;
    private final Consumer<T> show;
    private String path = "";
    /** The path whose artwork is on screen; empty when none is. */
    private String shown = "";
    /** The decode the latest request awaits; null when it awaits none. */
    private volatile Object decoding;
    private boolean stopped;

    /** {@code show} runs on the UI thread; null clears the artwork. */
    public FencedArtwork(Cache<T> cache, Function<String, T> decoder, Executor decodeExecutor,
                         Executor uiExecutor, Consumer<T> show) {
        this.cache = cache;
        this.decoder = decoder;
        this.decodeExecutor = decodeExecutor;
        this.uiExecutor = uiExecutor;
        this.show = show;
    }

    /**
     * UI thread. An empty path clears; the path already shown or pending (whichever transition
     * asks again, e.g. a switch's cover and then its new transition) neither clears nor decodes
     * again, even when another cover's decode has since replaced the shared cache.
     */
    public void request(String artworkPath) {
        if (stopped) return;
        String requested = artworkPath == null ? "" : artworkPath.trim();
        if (!requested.isEmpty() && requested.equals(path)
                && (decoding != null || requested.equals(shown))) {
            return;
        }
        path = requested;
        decoding = null;
        T cached = requested.isEmpty() ? null : cache.get(requested);
        if (requested.isEmpty() || cached != null) {
            display(requested, cached);
            return;
        }
        Object token = new Object();
        decoding = token;
        display("", null);
        decodeExecutor.execute(() -> {
            T decoded = decoder.apply(requested);
            // Cached here, not in the UI post: a cover stopped before that post ran (its window
            // paused for the next one) still hands the next window its artwork at once.
            if (decoded != null && decoding == token) cache.put(requested, decoded);
            uiExecutor.execute(() -> {
                if (stopped || decoding != token) return;
                decoding = null;
                // An unusable file shows the plain background, as no artwork does.
                display(decoded == null ? "" : requested, decoded);
            });
        });
    }

    /**
     * UI thread. The artwork is still being decided (a launch fetching it): the plain ground,
     * never the standard background, until the next {@link #request} names it.
     */
    public void hold() {
        if (stopped) return;
        path = "";
        decoding = new Object();
        display("", null);
    }

    /** The latest requested path; empty for none or while held. */
    public String requested() {
        return path;
    }

    public void stop() {
        stopped = true;
    }

    /** The latest request still awaits its decode: nothing, not even the plain background. */
    public boolean pending() {
        return decoding != null;
    }

    private void display(String artworkPath, T artwork) {
        shown = artwork == null ? "" : artworkPath;
        show.accept(artwork);
    }
}
