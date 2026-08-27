package com.limelight.console;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Bounded presentation cache for public Discord CDN avatars. */
final class DiscordAvatarLoader {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_EDGE = 96;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build();
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private static final Map<String, List<WeakReference<ImageView>>> IN_FLIGHT = new HashMap<>();

    private DiscordAvatarLoader() { }

    static void load(String url, ImageView view) {
        view.setTag(url);
        if (!safeUrl(url)) return;
        Bitmap cached;
        synchronized (CACHE) { cached = CACHE.get(url); }
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        synchronized (IN_FLIGHT) {
            List<WeakReference<ImageView>> waiting = IN_FLIGHT.get(url);
            if (waiting != null) {
                waiting.add(new WeakReference<>(view));
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(new WeakReference<>(view));
            IN_FLIGHT.put(url, waiting);
        }
        EXECUTOR.execute(() -> {
            Bitmap bitmap = fetch(url);
            if (bitmap != null) synchronized (CACHE) { CACHE.put(url, bitmap); }
            List<WeakReference<ImageView>> waiting;
            synchronized (IN_FLIGHT) { waiting = IN_FLIGHT.remove(url); }
            if (bitmap != null) {
                List<WeakReference<ImageView>> targets = waiting;
                mainHandler().post(() -> {
                    for (WeakReference<ImageView> target : targets) {
                        ImageView image = target.get();
                        if (image != null && url.equals(image.getTag()) && image.isAttachedToWindow()) {
                            image.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        });
    }

    static boolean safeUrl(String value) {
        HttpUrl url = HttpUrl.parse(value == null ? "" : value);
        if (url == null || !"https".equals(url.scheme())) return false;
        String host = url.host();
        return "cdn.discordapp.com".equals(host) || "media.discordapp.net".equals(host);
    }

    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private static Bitmap fetch(String url) {
        try (Response response = HTTP.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null
                    || response.body().contentLength() > MAX_BYTES) return null;
            byte[] bytes = readBounded(response.body().byteStream());
            if (bytes == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample(bounds.outWidth, bounds.outHeight);
            decode.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readBounded(InputStream stream) throws Exception {
        byte[] buffer = new byte[8192];
        int total = 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int read; (read = stream.read(buffer)) != -1;) {
            total += read;
            if (total > MAX_BYTES) return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static int sample(int width, int height) {
        int sample = 1;
        while (width / sample > MAX_EDGE || height / sample > MAX_EDGE) sample *= 2;
        return sample;
    }
}
