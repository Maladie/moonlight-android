package com.limelight.console;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns cached-poster decoding, asynchronous backdrop work, and cancellation. */
final class ConsoleArtworkController {
    private final ContentResolver resolver;
    private final Context context;
    private final ImageView backdrop;
    private final ImageView hero;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArtworkRequestGate requestGate = new ArtworkRequestGate();
    private final HostGatewayClient gatewayClient = new HostGatewayClient();
    private final LruCache<String, Bitmap> playniteCache = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private boolean destroyed;

    ConsoleArtworkController(Context context, ImageView backdrop, ImageView hero) {
        this.context = context;
        resolver = context.getContentResolver();
        this.backdrop = backdrop;
        this.hero = hero;
    }

    Bitmap decodePoster(Uri uri, int maxDimension) {
        if (uri == null || destroyed) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > maxDimension ||
                    bounds.outHeight / sample > maxDimension) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            try (InputStream input = resolver.openInputStream(uri)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        }
        catch (Exception ignored) {
            return null;
        }
    }

    void loadPlaynitePoster(HostGatewayClient.Connection connection, String gameId,
                            ImageView target) {
        if (destroyed || connection == null || gameId == null) return;
        String key = gameId + ":cover";
        target.setTag(key);
        Bitmap cached = playniteCache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        executor.execute(() -> {
            Bitmap bitmap = downloadPlaynite(connection, gameId, "cover", 640);
            if (bitmap == null) return;
            mainHandler.post(() -> {
                if (!destroyed && key.equals(target.getTag())) target.setImageBitmap(bitmap);
            });
        });
    }

    void showPlaynite(HostGatewayClient.Connection connection, String gameId,
                      Drawable immediate) {
        int token = requestGate.next();
        if (immediate != null) {
            hero.setImageDrawable(immediate);
            hero.setAlpha(0.58f);
        }
        executor.execute(() -> {
            Bitmap source = downloadPlaynite(connection, gameId, "background", 1200);
            if (source == null) source = downloadPlaynite(connection, gameId, "cover", 1200);
            if (source == null) return;
            Bitmap blurred = blurForBackdrop(source);
            Bitmap finalSource = source;
            mainHandler.post(() -> applyArtwork(token, finalSource, blurred));
        });
    }

    private Bitmap downloadPlaynite(HostGatewayClient.Connection connection, String gameId,
                                    String kind, int maxDimension) {
        if (destroyed) return null;
        String key = gameId + ":" + kind;
        Bitmap cached = playniteCache.get(key);
        if (cached != null) return cached;
        try {
            byte[] bytes = gatewayClient.getPlayniteArtwork(connection, gameId, kind);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > maxDimension ||
                    bounds.outHeight / sample > maxDimension) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null) playniteCache.put(key, bitmap);
            return bitmap;
        } catch (Exception ignored) {
            return null;
        }
    }

    void show(Uri uri, Drawable immediate) {
        int token = requestGate.next();
        if (immediate != null) {
            hero.setImageDrawable(immediate);
            hero.setAlpha(0.58f);
        }
        executor.execute(() -> {
            Bitmap source = decodePoster(uri, 900);
            if (source == null) return;
            Bitmap blurred = blurForBackdrop(source);
            mainHandler.post(() -> applyArtwork(token, source, blurred));
        });
    }

    private void applyArtwork(int token, Bitmap source, Bitmap blurred) {
        if (destroyed || !requestGate.isCurrent(token)) return;
        Drawable current = backdrop.getDrawable();
        boolean reducedMotion = context.getSharedPreferences(
                "launch_history", Context.MODE_PRIVATE)
                .getBoolean("reduced_motion", false);
        if (current != null && !reducedMotion) {
            TransitionDrawable transition = new TransitionDrawable(new Drawable[]{
                    current, new BitmapDrawable(context.getResources(), blurred)});
            transition.setCrossFadeEnabled(true);
            backdrop.setImageDrawable(transition);
            transition.startTransition(420);
        } else {
            backdrop.setImageBitmap(blurred);
        }
        hero.setImageBitmap(source);
        hero.setAlpha(0.58f);
        backdrop.setAlpha(0.28f);
        mainHandler.postDelayed(() -> {
            if (!destroyed && requestGate.isCurrent(token)) {
                backdrop.setImageBitmap(blurred);
                backdrop.setAlpha(0.28f);
            }
        }, 500L);
    }

    private static Bitmap blurForBackdrop(Bitmap source) {
        int width = 180;
        int height = Math.max(180, Math.min(320,
                Math.round(source.getHeight() *
                        (width / (float) Math.max(1, source.getWidth())))));
        Bitmap small = Bitmap.createScaledBitmap(source, width, height, true);
        if (small == source) small = source.copy(Bitmap.Config.ARGB_8888, true);

        int[] input = new int[width * height];
        int[] output = new int[input.length];
        small.getPixels(input, 0, width, 0, 0, width, height);
        int radius = 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = 0, red = 0, green = 0, blue = 0, count = 0;
                for (int ky = Math.max(0, y - radius);
                     ky <= Math.min(height - 1, y + radius); ky++) {
                    int row = ky * width;
                    for (int kx = Math.max(0, x - radius);
                         kx <= Math.min(width - 1, x + radius); kx++) {
                        int color = input[row + kx];
                        alpha += Color.alpha(color);
                        red += Color.red(color);
                        green += Color.green(color);
                        blue += Color.blue(color);
                        count++;
                    }
                }
                output[y * width + x] = Color.argb(
                        alpha / count, red / count, green / count, blue / count);
            }
        }
        small.setPixels(output, 0, width, 0, 0, width, height);
        return small;
    }

    void clear() {
        requestGate.invalidate();
        hero.animate().cancel();
        backdrop.animate().cancel();
        hero.setImageDrawable(null);
        hero.setAlpha(0f);
        backdrop.setImageDrawable(null);
        backdrop.setAlpha(0f);
    }

    void destroy() {
        destroyed = true;
        requestGate.invalidate();
        executor.shutdownNow();
        playniteCache.evictAll();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
