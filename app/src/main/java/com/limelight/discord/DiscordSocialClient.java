package com.limelight.discord;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.limelight.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Process-wide owner for the Discord Social SDK. UI reads immutable snapshots only. */
public final class DiscordSocialClient {
    private static final String DEVICE_AUTHORIZE_URL =
            "https://discord.com/api/v10/oauth2/device/authorize";
    private static final String TOKEN_URL = "https://discord.com/api/v10/oauth2/token";
    // Communication includes presence. Existing presence-only grants remain valid for the
    // social snapshot, but require an explicit device-flow upgrade before DMs are enabled.
    private static final String SOCIAL_SCOPE = "openid sdk.social_layer";
    private static final long TOKEN_SKEW_MS = 60_000L;
    private static final OkHttpClient HTTP = new OkHttpClient();
    private static final AtomicInteger flowGeneration = new AtomicInteger();
    private static final Object stateLock = new Object();
    private static final Object messageLock = new Object();
    private static final int MAX_JAVA_MESSAGE_EVENTS = 256;
    private static final Snapshot EMPTY = new Snapshot(0, "Not connected", false, false,
            "", "", "", Collections.emptyList());

    private static HandlerThread sdkThread;
    private static Handler sdkHandler;
    private static boolean librariesLoaded;
    private static boolean clientStarted;
    private static boolean restoreInProgress;
    private static boolean refreshInProgress;
    private static long nextRefreshAttemptWallMs;
    private static volatile boolean flowActive;
    private static volatile Snapshot snapshot = EMPTY;
    private static TokenBundle session;
    private static Context appContext;
    private static Dialog deviceDialog;
    private static WeakReference<Activity> dialogActivity = new WeakReference<>(null);
    private static final Deque<MessageEvent> pendingMessageEvents = new ArrayDeque<>();

    private DiscordSocialClient() {}

    public static boolean isAvailable() {
        return BuildConfig.DISCORD_SOCIAL_SDK_AVAILABLE;
    }

    /** Starts SDK ownership and attempts session restore without displaying a QR code. */
    public static void attach(Activity activity) throws Exception {
        ensureRuntime(activity);
        restoreIfNeeded();
    }

    /** Restores an existing session first; only displays QR when a new authorization is required. */
    public static void authorize(Activity activity) throws Exception {
        ensureRuntime(activity);
        restoreOrAuthorize(new WeakReference<>(activity));
    }

    /** Explicitly upgrades a presence-only grant. This is never invoked automatically. */
    public static void authorizeForDirectMessages(Activity activity) throws Exception {
        ensureRuntime(activity);
        new Thread(() -> {
            TokenBundle restored;
            synchronized (stateLock) {
                if (flowActive || restoreInProgress) return;
                restored = readStoredBundleLocked();
                session = restored;
            }
            if (restored != null && hasDirectMessageScope(restored.scopes)) {
                connectOrRefresh(restored);
                return;
            }
            startDeviceFlow(new WeakReference<>(activity));
        }, "DiscordDirectMessageAuthorize").start();
    }

    public static void cancelAuthorization() {
        flowGeneration.incrementAndGet();
        boolean wasActive = flowActive;
        flowActive = false;
        dismissDeviceDialog();
        if (wasActive) setStatus("Authorization canceled");
    }

    /** Compatibility with the existing caller. Cancel never unlinks the account. */
    public static void cancel() {
        cancelAuthorization();
    }

    /** Ends the local session immediately. Remote revoke is best effort and never blocks unlink. */
    public static void unlink() {
        cancelAuthorization();
        final TokenBundle previous;
        synchronized (stateLock) {
            previous = session;
            session = null;
            nextRefreshAttemptWallMs = 0L;
            clearStoredBundleLocked();
        }
        replaceSnapshot("Not connected", false, true, "", "", Collections.emptyList());
        postNative(() -> nativeDisconnect());
        if (previous != null && (!previous.accessToken.isEmpty() || !previous.refreshToken.isEmpty())) {
            postNative(() -> nativeRevokeToken(BuildConfig.DISCORD_SOCIAL_APPLICATION_ID,
                    previous.refreshToken.isEmpty() ? previous.accessToken : previous.refreshToken));
        }
    }

    /** Returns the last immutable state. It never enters JNI or does I/O. */
    public static Snapshot getSnapshot() {
        return snapshot;
    }

    /** Compatibility with the existing caller. */
    public static Snapshot poll() {
        return getSnapshot();
    }

    public static boolean canRetry(String status) {
        return status.startsWith("Authorization canceled")
                || status.startsWith("Authorization denied")
                || status.startsWith("Discord code expired")
                || status.startsWith("Unable to ")
                || status.startsWith("Discord connection needs retry");
    }

    public static boolean canRetry(Snapshot snapshot) {
        return snapshot.authorizationRequired || canRetry(snapshot.status);
    }

    /** Capability is determined only from the stored OAuth scope, never inferred from Ready. */
    public static boolean canUseDirectMessages() {
        synchronized (stateLock) {
            return session != null && hasDirectMessageScope(session.scopes);
        }
    }

    static boolean hasDirectMessageScope(String scopes) {
        if (scopes == null) return false;
        for (String scope : scopes.trim().split("\\s+")) {
            if ("sdk.social_layer".equals(scope)) return true;
        }
        return false;
    }

    /** Conversation identity is the other participant, not MessageHandle.RecipientId blindly. */
    static long directMessagePeer(long currentUserId, long authorId, long recipientId) {
        return authorId == currentUserId ? recipientId : authorId;
    }

    /** Requests at most 30 recent messages. The native request is owned by the SDK HandlerThread. */
    public static void requestUserMessages(long recipientId, long requestId) {
        if (recipientId <= 0 || requestId < 0 || !canUseDirectMessages()) return;
        postNative(() -> nativeRequestUserMessages(recipientId, requestId, 30));
    }

    /** Sends only following an explicit UI action; callers own retry policy and drafts. */
    public static void sendUserMessage(long recipientId, long requestId, String content) {
        if (recipientId <= 0 || requestId < 0 || content == null || content.isEmpty()
                || content.length() > 2000 || !canUseDirectMessages()) return;
        postNative(() -> nativeSendUserMessage(recipientId, requestId, content));
    }

    /** Opens non-text message content only after the user explicitly selects its CTA. */
    public static void openMessageInDiscord(long messageId) {
        if (messageId <= 0 || !canUseDirectMessages()) return;
        postNative(() -> nativeOpenMessageInDiscord(messageId));
    }

    public static void setShowingChat(boolean showing) {
        postNative(() -> nativeSetShowingChat(showing));
    }

    /** Returns and clears already-parsed native events; UI never enters JNI directly. */
    public static List<MessageEvent> drainMessageEvents() {
        synchronized (messageLock) {
            if (pendingMessageEvents.isEmpty()) return Collections.emptyList();
            List<MessageEvent> result = new ArrayList<>(pendingMessageEvents);
            pendingMessageEvents.clear();
            return Collections.unmodifiableList(result);
        }
    }

    private static void ensureRuntime(Activity activity) throws Exception {
        if (!isAvailable()) throw new IllegalStateException("Discord Social SDK is not installed");
        appContext = activity.getApplicationContext();
        Class<?> init = Class.forName("com.discord.socialsdk.DiscordSocialSdkInit");
        Method setActivity = init.getMethod("setEngineActivity", Activity.class);
        setActivity.invoke(null, activity);
        synchronized (stateLock) {
            if (!librariesLoaded) {
                System.loadLibrary("discord_partner_sdk");
                System.loadLibrary("discord-social-jni");
                librariesLoaded = true;
            }
            if (sdkThread == null) {
                sdkThread = new HandlerThread("DiscordSocialSdk");
                sdkThread.start();
                sdkHandler = new Handler(sdkThread.getLooper());
            }
            if (!clientStarted) {
                clientStarted = true;
                sdkHandler.post(() -> {
                    nativeStart(BuildConfig.DISCORD_SOCIAL_APPLICATION_ID);
                    pumpCallbacks();
                });
            }
        }
    }

    private static void restoreIfNeeded() {
        TokenBundle existing;
        synchronized (stateLock) {
            if (restoreInProgress || appContext == null) return;
            existing = session;
            if (existing != null) {
                // A new Activity may attach after an SDK disconnect; the process owner keeps the token.
                connectOrRefresh(existing);
                return;
            }
            restoreInProgress = true;
        }
        new Thread(() -> {
            TokenBundle restored;
            synchronized (stateLock) {
                restored = readStoredBundleLocked();
                session = restored;
                restoreInProgress = false;
            }
            if (restored == null) {
                replaceSnapshot("Discord authorization required", false, true,
                        "", "", Collections.emptyList());
            } else {
                connectOrRefresh(restored);
            }
        }, "DiscordSessionRestore").start();
    }

    private static void restoreOrAuthorize(WeakReference<Activity> activity) {
        synchronized (stateLock) {
            if (flowActive || restoreInProgress) return;
            if (session != null) {
                connectOrRefresh(session);
                return;
            }
            restoreInProgress = true;
        }
        new Thread(() -> {
            TokenBundle restored;
            synchronized (stateLock) {
                restored = readStoredBundleLocked();
                session = restored;
                restoreInProgress = false;
            }
            if (restored != null) {
                connectOrRefresh(restored);
                return;
            }
            startDeviceFlow(activity);
        }, "DiscordSessionRestore").start();
    }

    private static void connectOrRefresh(TokenBundle bundle) {
        if (bundle.accessUsable(System.currentTimeMillis())) {
            applyToken(bundle, "Connecting");
            return;
        }
        if (bundle.refreshToken.isEmpty()) {
            synchronized (stateLock) {
                if (session == bundle) session = null;
            }
            replaceSnapshot("Discord authorization required", false, true,
                    "", "", Collections.emptyList());
            return;
        }
        requestRefreshIfDue(bundle, System.currentTimeMillis());
    }

    private static void requestRefreshIfDue(TokenBundle bundle, long nowWallMs) {
        synchronized (stateLock) {
            if (refreshInProgress || !refreshDue(bundle, nowWallMs, nextRefreshAttemptWallMs)) return;
            refreshInProgress = true;
        }
        setStatus("Refreshing Discord session");
        new Thread(() -> refresh(bundle), "DiscordTokenRefresh").start();
    }

    private static void refresh(TokenBundle previous) {
        try {
            JSONObject response = post(TOKEN_URL, new FormBody.Builder()
                    .add("client_id", Long.toString(BuildConfig.DISCORD_SOCIAL_APPLICATION_ID))
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", previous.refreshToken)
                    .build());
            String error = response.optString("error");
            if (!error.isEmpty()) {
                if ("invalid_grant".equals(error)) clearInvalidSession(previous);
                else transientRefreshFailure(previous);
                return;
            }
            TokenBundle next = TokenBundle.fromResponse(response, previous, System.currentTimeMillis());
            if (next == null) {
                transientRefreshFailure(previous);
                return;
            }
            synchronized (stateLock) {
                if (session != previous) return;
                session = next;
                nextRefreshAttemptWallMs = 0L;
                persistBundleLocked(next);
            }
            applyToken(next, "Connecting");
        } catch (Exception ignored) {
            transientRefreshFailure(previous);
        } finally {
            synchronized (stateLock) {
                refreshInProgress = false;
            }
        }
    }

    private static void transientRefreshFailure(TokenBundle expected) {
        synchronized (stateLock) {
            if (session != expected) return;
            nextRefreshAttemptWallMs = System.currentTimeMillis() + 30_000L;
        }
        setStatus("Discord connection needs retry");
    }

    private static void startDeviceFlow(WeakReference<Activity> activity) {
        final int generation = flowGeneration.incrementAndGet();
        flowActive = true;
        new Thread(() -> runDeviceFlow(activity, generation), "DiscordDeviceFlow").start();
    }

    private static void runDeviceFlow(WeakReference<Activity> activityReference, int generation) {
        try {
            setStatus("Requesting Discord code");
            JSONObject device = post(DEVICE_AUTHORIZE_URL, new FormBody.Builder()
                    .add("client_id", Long.toString(BuildConfig.DISCORD_SOCIAL_APPLICATION_ID))
                    .add("scope", SOCIAL_SCOPE)
                    .build());
            String deviceCode = device.optString("device_code");
            String userCode = device.optString("user_code");
            String verificationUrl = device.optString("verification_uri_complete");
            int expiresIn = device.optInt("expires_in");
            int interval = Math.max(1, device.optInt("interval", 5));
            if (deviceCode.isEmpty() || userCode.isEmpty() || expiresIn <= 0 || !active(generation)) {
                finishFlow(generation, "Unable to read Discord response");
                return;
            }
            if (verificationUrl.isEmpty()) verificationUrl = "https://discord.com/activate";
            Activity activity = activityReference.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                finishFlow(generation, "Authorization canceled");
                return;
            }
            final String url = verificationUrl;
            activity.runOnUiThread(() -> showDeviceDialog(activityReference, generation, url, userCode));
            setStatus("Awaiting Discord approval");
            long deadline = SystemClock.elapsedRealtime() + expiresIn * 1000L;
            while (active(generation)) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    finishFlow(generation, "Discord code expired");
                    dismissDeviceDialog();
                    return;
                }
                Thread.sleep(Math.min(remaining, interval * 1000L));
                if (!active(generation)) return;
                JSONObject token = post(TOKEN_URL, new FormBody.Builder()
                        .add("client_id", Long.toString(BuildConfig.DISCORD_SOCIAL_APPLICATION_ID))
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                        .add("device_code", deviceCode)
                        .build());
                if (!token.optString("access_token").isEmpty()) {
                    TokenBundle next = TokenBundle.fromResponse(token, null, System.currentTimeMillis());
                    if (next == null) {
                        finishFlow(generation, "Unable to read Discord response");
                        return;
                    }
                    synchronized (stateLock) {
                        session = next;
                        nextRefreshAttemptWallMs = 0L;
                        persistBundleLocked(next); // commit completes before UpdateToken below.
                    }
                    flowActive = false;
                    dismissDeviceDialog();
                    applyToken(next, "Connecting");
                    return;
                }
                String error = token.optString("error");
                if ("authorization_pending".equals(error)) continue;
                if ("slow_down".equals(error)) {
                    interval += 5;
                    continue;
                }
                finishFlow(generation, pollFailure(error));
                dismissDeviceDialog();
                return;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            if (active(generation)) {
                finishFlow(generation, "Unable to contact Discord");
                dismissDeviceDialog();
            }
        }
    }

    private static JSONObject post(String url, RequestBody body) throws java.io.IOException, JSONException {
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (response.body() == null) throw new java.io.IOException("Empty response");
            return new JSONObject(response.body().string());
        }
    }

    static String pollFailure(String error) {
        if ("access_denied".equals(error)) return "Authorization denied";
        if ("expired_token".equals(error)) return "Discord code expired";
        return "Unable to contact Discord";
    }

    private static boolean active(int generation) {
        return flowGeneration.get() == generation && flowActive;
    }

    private static void finishFlow(int generation, String status) {
        if (active(generation)) {
            flowActive = false;
            setStatus(status);
        }
    }

    private static void applyToken(TokenBundle bundle, String status) {
        setStatus(status);
        postNative(() -> nativeUpdateToken(bundle.accessToken));
    }

    private static void setStatus(String status) {
        Snapshot current = snapshot;
        replaceSnapshot(status, current.connected, current.authorizationRequired,
                current.userId, current.displayName, current.friendDetails);
        postNative(() -> nativeSetStatus(status));
    }

    private static void postNative(Runnable work) {
        Handler handler;
        synchronized (stateLock) {
            handler = sdkHandler;
        }
        if (handler != null) handler.post(work);
    }

    private static void pumpCallbacks() {
        if (!clientStarted) return;
        String[] values = nativePumpAndSnapshot();
        enqueueMessageEvents(nativeDrainMessageEvents());
        Snapshot parsed = Snapshot.parse(values, snapshot.revision + 1);
        if (parsed == null) {
            Snapshot previous = snapshot;
            replaceSnapshot("Discord data is temporarily unavailable", previous.connected,
                    previous.authorizationRequired, previous.userId, previous.displayName,
                    previous.friendDetails);
        } else {
            synchronized (stateLock) {
                parsed = parsed.withAuthorizationRequired(authorizationRequiredForOwner(
                        session != null, flowActive, restoreInProgress));
            }
            if (!parsed.sameData(snapshot)) snapshot = parsed;
        }
        TokenBundle current;
        synchronized (stateLock) {
            current = session;
        }
        if (current != null) {
            requestRefreshIfDue(current, System.currentTimeMillis());
        }
        sdkHandler.postDelayed(DiscordSocialClient::pumpCallbacks, 250L);
    }

    private static void enqueueMessageEvents(String[] records) {
        if (records == null || records.length == 0) return;
        synchronized (messageLock) {
            for (String record : records) {
                MessageEvent event = MessageEvent.parse(record);
                if (event == null) event = MessageEvent.overflow();
                if (event.type == MessageEvent.Type.OVERFLOW
                        || pendingMessageEvents.size() >= MAX_JAVA_MESSAGE_EVENTS) {
                    // The only safe response to a dropped/invalid event is a bounded reload by
                    // the active conversation owner; do not present a partially current model.
                    pendingMessageEvents.clear();
                    pendingMessageEvents.addLast(MessageEvent.overflow());
                    continue;
                }
                pendingMessageEvents.addLast(event);
            }
        }
    }

    static boolean authorizationRequiredForOwner(boolean hasSession, boolean flowActive,
                                                 boolean restoreInProgress) {
        return !hasSession && !flowActive && !restoreInProgress;
    }

    static boolean refreshDue(TokenBundle bundle, long nowWallMs, long nextAttemptWallMs) {
        return bundle != null && !bundle.refreshToken.isEmpty()
                && nowWallMs >= bundle.expiresAtWallMs - TOKEN_SKEW_MS
                && nowWallMs >= nextAttemptWallMs;
    }

    private static void clearInvalidSession(TokenBundle expected) {
        synchronized (stateLock) {
            if (session != expected) return;
            session = null;
            nextRefreshAttemptWallMs = 0L;
            clearStoredBundleLocked();
        }
        replaceSnapshot("Discord authorization required", false, true,
                "", "", Collections.emptyList());
        postNative(() -> nativeDisconnect());
    }

    private static void replaceSnapshot(String status, boolean connected, boolean authorizationRequired,
                                        String userId, String displayName, List<Friend> friends) {
        Snapshot previous = snapshot;
        Snapshot next = new Snapshot(previous.revision + 1, status, connected, authorizationRequired,
                userId, displayName, userId.equals(previous.userId) ? previous.avatarUrl : "", friends);
        if (!next.sameData(previous)) snapshot = next;
    }

    private static void showDeviceDialog(WeakReference<Activity> activityReference, int generation,
                                         String verificationUrl, String userCode) {
        Activity activity = activityReference.get();
        if (!active(generation) || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (active(generation)) finishFlow(generation, "Authorization canceled");
            return;
        }
        int padding = dp(activity, 28);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(padding, padding, padding, dp(activity, 22));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B252D, 0xFF0B1015});
        background.setCornerRadius(dp(activity, 22));
        background.setStroke(dp(activity, 1), 0x99768B99);
        card.setBackground(background);
        card.addView(label(activity, "Połącz Discord", 25, Color.WHITE, true), match());
        TextView help = label(activity, "Zeskanuj kod QR telefonem i potwierdź w Discordzie.",
                15, 0xFFC4CED5, false);
        LinearLayout.LayoutParams helpParams = match();
        helpParams.topMargin = dp(activity, 8);
        card.addView(help, helpParams);
        ImageView qr = new ImageView(activity);
        qr.setImageBitmap(qrCode(verificationUrl, dp(activity, 240)));
        qr.setContentDescription("Kod QR Discord");
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(activity, 240), dp(activity, 240));
        qrParams.topMargin = dp(activity, 18);
        card.addView(qr, qrParams);
        TextView code = label(activity, userCode, 28, Color.WHITE, true);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams codeParams = match();
        codeParams.topMargin = dp(activity, 12);
        card.addView(code, codeParams);
        TextView fallback = label(activity, "Lub wejdź na discord.com/activate", 14, 0xFFC4CED5, false);
        fallback.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fallbackParams = match();
        fallbackParams.topMargin = dp(activity, 6);
        card.addView(fallback, fallbackParams);
        TextView cancel = label(activity, "Anuluj", 15, Color.WHITE, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setFocusable(true);
        cancel.setClickable(true);
        cancel.setPadding(dp(activity, 24), 0, dp(activity, 24), 0);
        cancel.setBackground(buttonBackground(activity, false));
        cancel.setOnFocusChangeListener((view, focused) -> cancel.setBackground(buttonBackground(activity, focused)));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(activity, 180), dp(activity, 50));
        cancelParams.topMargin = dp(activity, 20);
        card.addView(cancel, cancelParams);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(ignored -> {
            boolean ownsDialog;
            synchronized (stateLock) {
                ownsDialog = deviceDialog == dialog;
                if (ownsDialog) {
                    deviceDialog = null;
                    dialogActivity = new WeakReference<>(null);
                }
            }
            if (ownsDialog) cancelAuthorization();
        });
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = dp(activity, 680);
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.gravity = Gravity.CENTER;
            attributes.dimAmount = .72f;
            window.setAttributes(attributes);
        }
        synchronized (stateLock) {
            deviceDialog = dialog;
            dialogActivity = activityReference;
        }
        dialog.show();
        if (window != null) window.setLayout(dp(activity, 680), WindowManager.LayoutParams.WRAP_CONTENT);
        cancel.requestFocus();
    }

    private static void dismissDeviceDialog() {
        final Dialog dialog;
        final Activity activity;
        synchronized (stateLock) {
            dialog = deviceDialog;
            activity = dialogActivity.get();
            deviceDialog = null;
            dialogActivity = new WeakReference<>(null);
        }
        if (dialog == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            dialog.dismiss();
        });
    }

    private static Bitmap qrCode(String value, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
            return bitmap;
        } catch (WriterException e) {
            throw new IllegalStateException("Unable to create QR code", e);
        }
    }

    private static TextView label(Activity activity, String text, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static GradientDrawable buttonBackground(Activity activity, boolean focused) {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{focused ? 0xFF32444F : 0xC0232C33, focused ? 0xFF172129 : 0xD0141A1F});
        background.setCornerRadius(dp(activity, 12));
        background.setStroke(dp(activity, focused ? 2 : 1), focused ? 0xFFDDF5FF : 0x55788A96);
        return background;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static TokenBundle readStoredBundleLocked() {
        if (appContext == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            String encoded = prefs().getString("bundle", null);
            if (encoded == null || !encoded.startsWith("v1:")) return null;
            byte[] value = Base64.decode(encoded.substring(3), Base64.NO_WRAP);
            if (value.length <= 12) throw new IllegalStateException("Invalid bundle");
            byte[] iv = new byte[12];
            byte[] ciphertext = new byte[value.length - iv.length];
            System.arraycopy(value, 0, iv, 0, iv.length);
            System.arraycopy(value, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            TokenBundle bundle = TokenBundle.fromJson(
                    new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)));
            if (bundle == null) clearStoredBundleLocked();
            return bundle;
        } catch (Exception ignored) {
            clearStoredBundleLocked();
            return null;
        }
    }

    private static void persistBundleLocked(TokenBundle bundle) {
        if (appContext == null || bundle.refreshToken.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // ponytail: API 21-22 remains session-only; add a wrapped-key store only if those TVs matter.
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(bundle.toJson().toString().getBytes(StandardCharsets.UTF_8));
            byte[] value = new byte[cipher.getIV().length + encrypted.length];
            System.arraycopy(cipher.getIV(), 0, value, 0, cipher.getIV().length);
            System.arraycopy(encrypted, 0, value, cipher.getIV().length, encrypted.length);
            if (!prefs().edit().putString("bundle", "v1:" + Base64.encodeToString(value, Base64.NO_WRAP)).commit()) {
                throw new IllegalStateException("Unable to store Discord session");
            }
        } catch (Exception ignored) {
            clearStoredBundleLocked();
            // Do not connect a token that the user expects to survive a process restart.
            session = new TokenBundle(bundle.accessToken, "", bundle.expiresAtWallMs, bundle.scopes);
        }
    }

    private static void clearStoredBundleLocked() {
        if (appContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) prefs().edit().remove("bundle").commit();
    }

    private static SharedPreferences prefs() {
        return appContext.getSharedPreferences("discord_social_session", Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias("moonwaker_discord_social_v1")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("moonwaker_discord_social_v1",
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry("moonwaker_discord_social_v1", null)).getSecretKey();
    }

    /** Immutable, process-local representation of the separate v1 DM event protocol. */
    public static final class MessageEvent {
        public enum Type {
            HISTORY_BEGIN, HISTORY_MESSAGE, HISTORY_RESULT, CREATED, UPDATED, DELETED,
            SEND_RESULT, OPEN_MESSAGE_RESULT, OVERFLOW
        }

        public final Type type;
        public final long recipientId;
        public final long requestId;
        public final long messageId;
        public final long authorId;
        public final String content;
        public final long sentTimestampMs;
        public final long editedTimestampMs;
        public final String additionalContentType;
        public final String additionalContentTitle;
        public final int additionalContentCount;
        public final boolean disclosure;
        public final boolean successful;
        public final boolean retryable;
        public final float retryAfterSeconds;
        public final String errorType;

        private MessageEvent(Type type, long recipientId, long requestId, long messageId,
                             long authorId, String content, long sentTimestampMs,
                             long editedTimestampMs, String additionalContentType,
                             String additionalContentTitle, int additionalContentCount, boolean disclosure, boolean successful,
                             boolean retryable, float retryAfterSeconds, String errorType) {
            this.type = type;
            this.recipientId = recipientId;
            this.requestId = requestId;
            this.messageId = messageId;
            this.authorId = authorId;
            this.content = content;
            this.sentTimestampMs = sentTimestampMs;
            this.editedTimestampMs = editedTimestampMs;
            this.additionalContentType = additionalContentType;
            this.additionalContentTitle = additionalContentTitle;
            this.additionalContentCount = additionalContentCount;
            this.disclosure = disclosure;
            this.successful = successful;
            this.retryable = retryable;
            this.retryAfterSeconds = retryAfterSeconds;
            this.errorType = errorType;
        }

        static MessageEvent overflow() {
            return new MessageEvent(Type.OVERFLOW, 0, 0, 0, 0, "", 0, 0, "", "", 0,
                    false, false, false, 0, "");
        }

        public static MessageEvent parse(String record) {
            try {
                List<String> fields = decodeRecord(record);
                if (fields.size() < 2 || !"1".equals(fields.get(0))) return null;
                Type type = Type.valueOf(fields.get(1));
                if (type == Type.OVERFLOW) return fields.size() == 2 ? overflow() : null;
                if (type == Type.HISTORY_BEGIN && fields.size() == 4) {
                    return new MessageEvent(type, positiveLong(fields.get(2)), nonNegativeLong(fields.get(3)),
                            0, 0, "", 0, 0, "", "", 0, false, false, false, 0, "");
                }
                if ((type == Type.HISTORY_MESSAGE || type == Type.CREATED || type == Type.UPDATED)
                        && (fields.size() == 12 || fields.size() == 13)) {
                    boolean hasTitle = fields.size() == 13;
                    return new MessageEvent(type, positiveLong(fields.get(2)), nonNegativeLong(fields.get(3)),
                            positiveLong(fields.get(4)), nonNegativeLong(fields.get(5)), fields.get(6),
                            nonNegativeLong(fields.get(7)), nonNegativeLong(fields.get(8)), fields.get(9),
                            hasTitle ? fields.get(10) : "", nonNegativeInt(fields.get(hasTitle ? 11 : 10)),
                            flag(fields.get(hasTitle ? 12 : 11)), false, false, 0, "");
                }
                if (type == Type.DELETED && fields.size() == 4) {
                    return new MessageEvent(type, 0, 0, positiveLong(fields.get(2)),
                            nonNegativeLong(fields.get(3)), "", 0, 0, "", "", 0,
                            false, false, false, 0, "");
                }
                if ((type == Type.HISTORY_RESULT || type == Type.SEND_RESULT) && fields.size() == 8) {
                    return new MessageEvent(type, positiveLong(fields.get(2)), nonNegativeLong(fields.get(3)),
                            0, 0, "", 0, 0, "", "", 0, false, flag(fields.get(4)), flag(fields.get(5)),
                            nonNegativeFloat(fields.get(6)), fields.get(7));
                }
                if (type == Type.OPEN_MESSAGE_RESULT && fields.size() == 5) {
                    return new MessageEvent(type, 0, 0, positiveLong(fields.get(2)), 0,
                            "", 0, 0, "", "", 0, false, flag(fields.get(3)), false, 0, fields.get(4));
                }
                return null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private static List<String> decodeRecord(String record) {
            if (record == null) throw new IllegalArgumentException();
            byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
            List<String> result = new ArrayList<>();
            for (int position = 0; position < bytes.length;) {
                int colon = position;
                while (colon < bytes.length && bytes[colon] != ':') {
                    if (bytes[colon] < '0' || bytes[colon] > '9') throw new IllegalArgumentException();
                    colon++;
                }
                if (colon == position || colon == bytes.length) throw new IllegalArgumentException();
                long length = Long.parseLong(new String(bytes, position, colon - position,
                        StandardCharsets.US_ASCII));
                int start = colon + 1;
                if (length < 0 || length > bytes.length - start) throw new IllegalArgumentException();
                result.add(DiscordSocialMessageCodec.decodeUtf8(new String(bytes, start, (int) length,
                        StandardCharsets.US_ASCII)));
                position = start + (int) length;
            }
            return result;
        }

        private static long positiveLong(String value) {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new IllegalArgumentException();
            return parsed;
        }
        private static long nonNegativeLong(String value) {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new IllegalArgumentException();
            return parsed;
        }
        private static int nonNegativeInt(String value) {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new IllegalArgumentException();
            return parsed;
        }
        private static float nonNegativeFloat(String value) {
            float parsed = Float.parseFloat(value);
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0) throw new IllegalArgumentException();
            return parsed;
        }
        private static boolean flag(String value) {
            if ("1".equals(value)) return true;
            if ("0".equals(value)) return false;
            throw new IllegalArgumentException();
        }
    }

    // Keeps protocol tests independent from JNI while exercising the exact length-prefix grammar.
    public static String encodeMessageEventForTest(String... fields) {
        StringBuilder record = new StringBuilder();
        for (String field : fields) {
            String encoded = DiscordSocialMessageCodec.encodeUtf8(field);
            record.append(encoded.length()).append(':').append(encoded);
        }
        return record.toString();
    }

    static final class TokenBundle {
        final String accessToken;
        final String refreshToken;
        final long expiresAtWallMs;
        final String scopes;

        TokenBundle(String accessToken, String refreshToken, long expiresAtWallMs, String scopes) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtWallMs = expiresAtWallMs;
            this.scopes = scopes;
        }

        boolean accessUsable(long nowWallMs) {
            return !accessToken.isEmpty() && expiresAtWallMs > nowWallMs + TOKEN_SKEW_MS;
        }

        static TokenBundle fromResponse(JSONObject response, TokenBundle previous, long nowWallMs) {
            String access = response.optString("access_token");
            long expiresIn = response.optLong("expires_in", 0L);
            if (access.isEmpty() || expiresIn <= 0L) return null;
            String refresh = response.optString("refresh_token");
            if (refresh.isEmpty() && previous != null) refresh = previous.refreshToken;
            String scopes = response.optString("scope", previous == null ? SOCIAL_SCOPE : previous.scopes);
            return new TokenBundle(access, refresh, nowWallMs + expiresIn * 1000L, scopes);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("v", 1).put("a", accessToken).put("r", refreshToken)
                    .put("e", expiresAtWallMs).put("s", scopes);
        }

        static TokenBundle fromJson(JSONObject json) {
            if (json.optInt("v") != 1) return null;
            String access = json.optString("a");
            String refresh = json.optString("r");
            long expires = json.optLong("e");
            String scopes = json.optString("s");
            return access.isEmpty() || refresh.isEmpty() || expires <= 0 || scopes.isEmpty()
                    ? null : new TokenBundle(access, refresh, expires, scopes);
        }
    }

    public static final class Friend {
        public enum Group { PLAYING, ONLINE, OFFLINE }
        public final String userId;
        public final String displayName;
        public final Group group;
        public final String activityName;

        public final String avatarUrl;

        Friend(String userId, String displayName, Group group, String activityName,
               String avatarUrl) {
            this.userId = userId;
            this.displayName = displayName;
            this.group = group;
            this.activityName = activityName;
            this.avatarUrl = avatarUrl;
        }

        @Override public String toString() { return displayName; }

        @Override public boolean equals(Object value) {
            if (!(value instanceof Friend)) return false;
            Friend other = (Friend) value;
            return userId.equals(other.userId) && displayName.equals(other.displayName)
                    && group == other.group && activityName.equals(other.activityName)
                    && avatarUrl.equals(other.avatarUrl);
        }

        @Override public int hashCode() {
            return ((((userId.hashCode() * 31) + displayName.hashCode()) * 31 + group.hashCode()) * 31
                    + activityName.hashCode()) * 31 + avatarUrl.hashCode();
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final String status;
        public final boolean connected;
        public final boolean authorizationRequired;
        public final String userId;
        public final String displayName;
        public final String avatarUrl;
        public final List<Friend> friendDetails;
        /** Compatibility list for the current panel. New UI should use friendDetails. */
        public final List<String> friends;

        Snapshot(long revision, String status, boolean connected, boolean authorizationRequired,
                 String userId, String displayName, String avatarUrl, List<Friend> friendDetails) {
            this.revision = revision;
            this.status = status;
            this.connected = connected;
            this.authorizationRequired = authorizationRequired;
            this.userId = userId;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
            this.friendDetails = Collections.unmodifiableList(new ArrayList<>(friendDetails));
            List<String> names = new ArrayList<>();
            for (Friend friend : friendDetails) names.add(friend.displayName);
            this.friends = Collections.unmodifiableList(names);
        }

        static Snapshot parse(String[] values, long revision) {
            try {
                if (values == null || values.length < 7 || !"2".equals(values[0])) return null;
                int count = Integer.parseInt(values[6]);
                if (count < 0 || values.length != 7 + count * 5) return null;
                boolean connected = "1".equals(values[2]);
                if (!connected && !"0".equals(values[2])) return null;
                List<Friend> friends = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int base = 7 + i * 5;
                    friends.add(new Friend(values[base], values[base + 1],
                            Friend.Group.valueOf(values[base + 2]), values[base + 3],
                            values[base + 4]));
                }
                boolean required = "Discord authorization required".equals(values[1]);
                return new Snapshot(revision, values[1], connected, required,
                        values[3], values[4], values[5], friends);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        Snapshot withAuthorizationRequired(boolean required) {
            return new Snapshot(revision, status, connected, required, userId, displayName,
                    avatarUrl, friendDetails);
        }

        boolean sameData(Snapshot other) {
            return status.equals(other.status) && connected == other.connected
                    && authorizationRequired == other.authorizationRequired && userId.equals(other.userId)
                    && displayName.equals(other.displayName) && avatarUrl.equals(other.avatarUrl)
                    && friendDetails.equals(other.friendDetails);
        }
    }

    private static native void nativeStart(long applicationId);
    private static native void nativeSetStatus(String status);
    private static native void nativeUpdateToken(String accessToken);
    private static native void nativeDisconnect();
    private static native void nativeRevokeToken(long applicationId, String token);
    private static native String[] nativePumpAndSnapshot();
    private static native void nativeRequestUserMessages(long recipientId, long requestId, int limit);
    private static native void nativeSendUserMessage(long recipientId, long requestId, String content);
    private static native void nativeOpenMessageInDiscord(long messageId);
    private static native void nativeSetShowingChat(boolean showing);
    private static native String[] nativeDrainMessageEvents();
}
