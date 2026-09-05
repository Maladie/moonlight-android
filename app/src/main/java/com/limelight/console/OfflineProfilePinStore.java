package com.limelight.console;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.limelight.gateway.GatewayConnection;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * Device-local preliminary PIN check. It never persists the PIN or a host PIN verifier.
 * The Android Keystore key is the verifier secret; the preference record is useless without it.
 */
final class OfflineProfilePinStore {
    private static final String PREFS = "offline_profile_pin_store";
    private static final String RECORD_PREFIX = "record.v1.";
    private static final String KEY_ALIAS = "moonwaker_offline_profile_pin_v1";
    private static final int SALT_BYTES = 16;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_FAILURES = 30;
    private static final long BASE_COOLDOWN_MS = 1_000L;
    private static final long MAX_COOLDOWN_MS = 5L * 60L * 1_000L;
    private static final Object STORE_LOCK = new Object();
    private static final Object KEY_LOCK = new Object();

    private final SharedPreferences preferences;
    private final SecureRandom random = new SecureRandom();
    private final boolean injectedKey;
    private final SecretKey testKey;
    private final LongSupplier clock;
    private volatile SecretKey cachedKey;

    enum Status { MATCH, INVALID, MISSING, COOLDOWN }

    static final class Result {
        final Status status;
        final int retryAfterSeconds;

        Result(Status status, int retryAfterSeconds) {
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private static Result match() {
            return new Result(Status.MATCH, 0);
        }

        private static Result invalid(int retryAfterSeconds) {
            return new Result(Status.INVALID, retryAfterSeconds);
        }

        private static Result missing() {
            return new Result(Status.MISSING, 0);
        }

        private static Result cooldown(int retryAfterSeconds) {
            return new Result(Status.COOLDOWN, retryAfterSeconds);
        }
    }

    OfflineProfilePinStore(Context context) {
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), null, false,
                System::currentTimeMillis);
    }

    // Test-only constructor. A null key models a lost/unavailable Android Keystore entry.
    OfflineProfilePinStore(SharedPreferences preferences, SecretKey testKey) {
        this(preferences, testKey, System::currentTimeMillis);
    }

    OfflineProfilePinStore(SharedPreferences preferences, SecretKey testKey,
                           LongSupplier clock) {
        this(preferences, testKey, true, clock);
    }

    private OfflineProfilePinStore(SharedPreferences preferences, SecretKey testKey,
                                   boolean injectedKey, LongSupplier clock) {
        if (preferences == null) throw new IllegalArgumentException("Missing preferences");
        if (clock == null) throw new IllegalArgumentException("Missing clock");
        this.preferences = preferences;
        this.testKey = testKey;
        this.injectedKey = injectedKey;
        this.clock = clock;
    }

    Result check(String hostId, GatewayConnection connection, String pin) {
        Domain domain = domain(hostId, connection);
        SecretKey key = domain == null ? null : key(false);
        if (domain == null || key == null) return Result.missing();

        synchronized (STORE_LOCK) {
            Record record = read(domain.recordKey);
            if (record == null) return Result.missing();

            long now = clock.getAsLong();
            if (record.cooldownUntil > now) {
                return Result.cooldown(retrySeconds(record.cooldownUntil - now));
            }

            boolean matches = false;
            if (validPin(pin)) {
                byte[] expected = verifier(key, domain, record.salt, pin);
                if (expected == null) return Result.missing();
                matches = MessageDigest.isEqual(expected, record.verifier);
            }
            if (matches) {
                if (record.failures != 0 || record.cooldownUntil != 0) {
                    write(domain.recordKey, new Record(record.salt, record.verifier, 0, 0L));
                }
                return Result.match();
            }

            int failures = Math.min(MAX_FAILURES, record.failures + 1);
            long delay = cooldownMillis(failures);
            long cooldownUntil = safeAdd(now, delay);
            if (!write(domain.recordKey,
                    new Record(record.salt, record.verifier, failures, cooldownUntil))) {
                // A failed durable write must never claim that local throttling is active.
                return Result.missing();
            }
            return Result.invalid(retrySeconds(delay));
        }
    }

    void rememberVerified(String hostId, GatewayConnection connection, String pin) {
        Domain domain = domain(hostId, connection);
        SecretKey key = domain == null ? null : key(true);
        if (domain == null || key == null || !validPin(pin)) return;

        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] verifier = verifier(key, domain, salt, pin);
        if (verifier == null) return;
        synchronized (STORE_LOCK) {
            removeProfileRecordsLocked(domain.hostProfilePrefix);
            write(domain.recordKey, new Record(salt, verifier, 0, 0L));
        }
    }

    void forget(String hostId, String profileId) {
        String prefix = hostProfilePrefix(hostId, profileId);
        if (prefix == null) return;
        synchronized (STORE_LOCK) {
            removeProfileRecordsLocked(prefix);
        }
    }

    void retainProfiles(String hostId, Set<String> profileIds) {
        String hostPrefix = hostPrefix(hostId);
        if (hostPrefix == null) return;
        Set<String> allowed = new HashSet<>();
        if (profileIds != null) {
            for (String profileId : profileIds) {
                String normalized = normalizeProfile(profileId);
                if (normalized != null) allowed.add(profileHash(normalized));
            }
        }
        synchronized (STORE_LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : preferences.getAll().keySet()) {
                if (!key.startsWith(hostPrefix)) continue;
                String suffix = key.substring(hostPrefix.length());
                int separator = suffix.indexOf('.');
                if (separator <= 0 || !allowed.contains(suffix.substring(0, separator))) {
                    editor.remove(key);
                }
            }
            editor.commit();
        }
    }

    void removeHost(String hostId) {
        String prefix = hostPrefix(hostId);
        if (prefix == null) return;
        synchronized (STORE_LOCK) {
            removeProfileRecordsLocked(prefix);
        }
    }

    static boolean supportsOfflineValidation(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.M;
    }

    private Domain domain(String hostId, GatewayConnection connection) {
        if (hostId == null || hostId.isEmpty() || connection == null) return null;
        String profileId = normalizeProfile(connection.profileId());
        if (profileId == null || !profileId.equals(connection.profileId())
                || connection.token().isEmpty() || connection.certificateSha256().isEmpty()) {
            return null;
        }
        String tokenDigest = hex(sha256(connection.token().getBytes(StandardCharsets.UTF_8)));
        String canonical = "v1|host=" + hostId.length() + ":" + hostId
                + "|profile=" + profileId.length() + ":" + profileId
                + "|cert=" + connection.certificateSha256()
                + "|token_sha256=" + tokenDigest;
        String hostProfilePrefix = hostProfilePrefix(hostId, profileId);
        if (hostProfilePrefix == null) return null;
        String recordKey = hostProfilePrefix
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        return new Domain(hostProfilePrefix, recordKey, canonical);
    }

    private SecretKey key(boolean create) {
        if (injectedKey) return testKey;
        if (!supportsOfflineValidation(Build.VERSION.SDK_INT)) return null;
        SecretKey available = cachedKey;
        if (available != null) return available;
        synchronized (KEY_LOCK) {
            if (cachedKey != null) return cachedKey;
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (!store.containsAlias(KEY_ALIAS)) {
                    if (!create) return null;
                    KeyGenerator generator = KeyGenerator.getInstance(
                            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
                    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                            KeyProperties.PURPOSE_SIGN)
                            .setKeySize(256)
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build());
                    generator.generateKey();
                    synchronized (STORE_LOCK) {
                        removeAllRecordsLocked();
                    }
                }
                KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
                if (!(entry instanceof KeyStore.SecretKeyEntry)) return null;
                cachedKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                return cachedKey;
            } catch (Exception unavailable) {
                return null;
            }
        }
    }

    private byte[] verifier(SecretKey key, Domain domain, byte[] salt, String pin) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(domain.canonical.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(salt);
            mac.update((byte) 0);
            mac.update(pin.getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal();
        } catch (Exception unavailable) {
            return null;
        }
    }

    private Record read(String key) {
        String raw;
        try {
            raw = preferences.getString(key, null);
        } catch (RuntimeException invalidValue) {
            return null;
        }
        if (raw == null) return null;
        String[] fields = raw.split(":", -1);
        if (fields.length != 5 || !"1".equals(fields[0])) return null;
        byte[] salt = parseHex(fields[1], SALT_BYTES);
        byte[] verifier = parseHex(fields[2], DIGEST_BYTES);
        if (salt == null || verifier == null) return null;
        try {
            int failures = Integer.parseInt(fields[3]);
            long cooldownUntil = Long.parseLong(fields[4]);
            if (failures < 0 || failures > MAX_FAILURES || cooldownUntil < 0) return null;
            return new Record(salt, verifier, failures, cooldownUntil);
        } catch (NumberFormatException invalidRecord) {
            return null;
        }
    }

    private boolean write(String key, Record record) {
        try {
            return preferences.edit().putString(key, record.serialize()).commit();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void removeProfileRecordsLocked(String prefix) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.commit();
    }

    private void removeAllRecordsLocked() {
        removeProfileRecordsLocked(RECORD_PREFIX);
    }

    private static String hostPrefix(String hostId) {
        if (hostId == null || hostId.isEmpty()) return null;
        return RECORD_PREFIX + hex(sha256(("host|" + hostId).getBytes(StandardCharsets.UTF_8))) + ".";
    }

    private static String hostProfilePrefix(String hostId, String profileId) {
        String hostPrefix = hostPrefix(hostId);
        String normalized = normalizeProfile(profileId);
        return hostPrefix == null || normalized == null ? null
                : hostPrefix + profileHash(normalized) + ".";
    }

    private static String profileHash(String profileId) {
        return hex(sha256(("profile|" + profileId).getBytes(StandardCharsets.UTF_8)));
    }

    private static String normalizeProfile(String profileId) {
        try {
            return GatewayConnection.normalizeProfileId(profileId);
        } catch (IllegalArgumentException invalidProfile) {
            return null;
        }
    }

    private static boolean validPin(String pin) {
        return pin != null && pin.matches("[0-9]{4}");
    }

    private static long cooldownMillis(int failures) {
        long multiplier = 1L << Math.min(30, Math.max(0, failures - 1));
        return Math.min(MAX_COOLDOWN_MS, BASE_COOLDOWN_MS * multiplier);
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static int retrySeconds(long millis) {
        long seconds = millis / 1_000L + (millis % 1_000L == 0 ? 0 : 1);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, seconds));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] value) {
        char[] result = new char[value.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            result[i * 2] = digits[(value[i] >>> 4) & 0x0f];
            result[i * 2 + 1] = digits[value[i] & 0x0f];
        }
        return new String(result);
    }

    private static byte[] parseHex(String value, int expectedBytes) {
        if (value == null || value.length() != expectedBytes * 2) return null;
        byte[] result = new byte[expectedBytes];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static final class Domain {
        final String hostProfilePrefix;
        final String recordKey;
        final String canonical;

        Domain(String hostProfilePrefix, String recordKey, String canonical) {
            this.hostProfilePrefix = hostProfilePrefix;
            this.recordKey = recordKey;
            this.canonical = canonical;
        }
    }

    private static final class Record {
        final byte[] salt;
        final byte[] verifier;
        final int failures;
        final long cooldownUntil;

        Record(byte[] salt, byte[] verifier, int failures, long cooldownUntil) {
            this.salt = salt;
            this.verifier = verifier;
            this.failures = failures;
            this.cooldownUntil = cooldownUntil;
        }

        String serialize() {
            return "1:" + hex(salt) + ":" + hex(verifier) + ":"
                    + failures + ":" + cooldownUntil;
        }
    }
}
