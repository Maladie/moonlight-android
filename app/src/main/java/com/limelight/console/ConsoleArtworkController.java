package com.limelight.console;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns cached-poster decoding, asynchronous backdrop work, and cancellation. */
final class ConsoleArtworkController {
    private final ContentResolver resolver;
    private final ImageView backdrop;
    private final ImageView hero;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArtworkRequestGate requestGate = new ArtworkRequestGate();
    private boolean destroyed;

    ConsoleArtworkController(Context context, ImageView backdrop, ImageView hero) {
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

    void show(Uri uri, Drawable immediate) {
        int token = requestGate.next();
        if (immediate != null) {
            hero.setImageDrawable(immediate);
            hero.setAlpha(0.58f);
        }
        executor.execute(() -> {
            Bitmap source = decodePoster(uri, 900);
            if (source == null) return;
            Bitmap small = Bitmap.createScaledBitmap(source, 48, 72, true);
            Bitmap blurred = Bitmap.createScaledBitmap(small, 960, 1080, true);
            mainHandler.post(() -> {
                if (destroyed || !requestGate.isCurrent(token)) return;
                backdrop.setImageBitmap(blurred);
                backdrop.animate().alpha(0.46f).setDuration(220).start();
            });
        });
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
        mainHandler.removeCallbacksAndMessages(null);
    }
}
