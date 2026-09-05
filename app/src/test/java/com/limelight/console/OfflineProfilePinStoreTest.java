package com.limelight.console;

import android.content.SharedPreferences;

import com.limelight.gateway.GatewayConnection;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfflineProfilePinStoreTest {
    private static final String PIN = "1357";
    private static final String CERTIFICATE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test public void missingRecordRequiresOnlineVerification() {
        MemoryPreferences memory = new MemoryPreferences();
        OfflineProfilePinStore store = store(memory, key((byte) 1));

        assertEquals(OfflineProfilePinStore.Status.MISSING,
                store.check("host", connection("profile", "token"), PIN).status);
    }

    @Test public void verifiedPinSurvivesNewStoreAndNeverPersistsThePin() {
        MemoryPreferences memory = new MemoryPreferences();
        SecretKey key = key((byte) 2);
        GatewayConnection connection = connection("profile", "token");
        store(memory, key).rememberVerified("host", connection, PIN);

        OfflineProfilePinStore.Result check = store(memory, key)
                .check("host", connection, PIN);
        assertEquals(OfflineProfilePinStore.Status.MATCH, check.status);
        assertFalse(memory.values.containsValue(PIN));
        assertTrue(memory.values.size() > 0);
    }

    @Test public void wrongPinUsesPersistentExponentialCooldown() {
        MemoryPreferences memory = new MemoryPreferences();
        SecretKey key = key((byte) 3);
        FakeClock clock = new FakeClock();
        GatewayConnection connection = connection("profile", "token");
        OfflineProfilePinStore first = store(memory, key, clock);
        first.rememberVerified("host", connection, PIN);

        OfflineProfilePinStore.Result invalid = first.check("host", connection, "2468");
        assertEquals(OfflineProfilePinStore.Status.INVALID, invalid.status);
        assertEquals(1, invalid.retryAfterSeconds);

        OfflineProfilePinStore.Result cooldown = store(memory, key, clock)
                .check("host", connection, "2468");
        assertEquals(OfflineProfilePinStore.Status.COOLDOWN, cooldown.status);
        assertTrue(cooldown.retryAfterSeconds >= 1);

        clock.now += 1_000L;
        OfflineProfilePinStore.Result secondInvalid = first.check(
                "host", connection, "2468");
        assertEquals(OfflineProfilePinStore.Status.INVALID, secondInvalid.status);
        assertEquals(2, secondInvalid.retryAfterSeconds);

        clock.now += 1_000L;
        OfflineProfilePinStore.Result persistedCooldown = store(memory, key, clock)
                .check("host", connection, "2468");
        assertEquals(OfflineProfilePinStore.Status.COOLDOWN, persistedCooldown.status);
        assertEquals(1, persistedCooldown.retryAfterSeconds);
    }

    @Test public void bindingChangesRequireOnlineVerification() {
        MemoryPreferences memory = new MemoryPreferences();
        SecretKey key = key((byte) 4);
        OfflineProfilePinStore store = store(memory, key);
        store.rememberVerified("host", connection("profile", "token"), PIN);

        assertEquals(OfflineProfilePinStore.Status.MISSING,
                store.check("other-host", connection("profile", "token"), PIN).status);
        assertEquals(OfflineProfilePinStore.Status.MISSING,
                store.check("host", connection("other-profile", "token"), PIN).status);
        assertEquals(OfflineProfilePinStore.Status.MISSING,
                store.check("host", connection("profile", "other-token"), PIN).status);
        assertEquals(OfflineProfilePinStore.Status.MISSING,
                store.check("host", connectionWithCertificate("profile", "token",
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), PIN)
                        .status);
        assertEquals(OfflineProfilePinStore.Status.MATCH,
                store.check("host", connectionWithEndpoint("https://192.0.2.99:8785",
                        "profile", "token", CERTIFICATE), PIN).status);
    }

    @Test public void forgetAndRetainProfilesRemoveOldBindings() {
        MemoryPreferences memory = new MemoryPreferences();
        SecretKey key = key((byte) 5);
        OfflineProfilePinStore store = store(memory, key);
        GatewayConnection first = connection("first", "token");
        GatewayConnection second = connection("second", "token");
        store.rememberVerified("host", first, PIN);
        store.rememberVerified("host", second, PIN);

        store.retainProfiles("host", Collections.singleton("first"));
        assertEquals(OfflineProfilePinStore.Status.MATCH,
                store.check("host", first, PIN).status);
        assertEquals(OfflineProfilePinStore.Status.MISSING,
                store.check("host", second, PIN).status);

        store.forget("host", "first");
        assertEquals(OfflineProfilePinStore.Status.MISSING,
                store.check("host", first, PIN).status);
    }

    @Test public void unavailableKeyAndOlderAndroidAreOnlineOnly() {
        MemoryPreferences memory = new MemoryPreferences();
        SecretKey availableKey = key((byte) 6);
        GatewayConnection connection = connection("profile", "token");
        store(memory, availableKey).rememberVerified("host", connection, PIN);

        OfflineProfilePinStore unavailable = store(memory, null);
        unavailable.rememberVerified("host", connection, PIN);
        assertEquals(OfflineProfilePinStore.Status.MISSING,
                unavailable.check("host", connection, PIN).status);
        assertFalse(OfflineProfilePinStore.supportsOfflineValidation(22));
        assertTrue(OfflineProfilePinStore.supportsOfflineValidation(23));
    }

    private static OfflineProfilePinStore store(MemoryPreferences memory, SecretKey key) {
        return new OfflineProfilePinStore(memory.preferences, key);
    }

    private static OfflineProfilePinStore store(MemoryPreferences memory, SecretKey key,
                                                LongSupplier clock) {
        return new OfflineProfilePinStore(memory.preferences, key, clock);
    }

    private static SecretKey key(byte value) {
        byte[] material = new byte[32];
        java.util.Arrays.fill(material, value);
        return new SecretKeySpec(material, "HmacSHA256");
    }

    private static GatewayConnection connection(String profileId, String token) {
        return connectionWithEndpoint("https://192.0.2.10:8785", profileId, token, CERTIFICATE);
    }

    private static GatewayConnection connectionWithEndpoint(String endpoint, String profileId,
                                                            String token, String certificate) {
        return new GatewayConnection(endpoint, token, certificate, profileId);
    }

    private static GatewayConnection connectionWithCertificate(String profileId, String token,
                                                               String certificate) {
        return connectionWithEndpoint("https://192.0.2.10:8785", profileId, token, certificate);
    }

    private static final class FakeClock implements LongSupplier {
        long now;

        @Override public long getAsLong() {
            return now;
        }
    }

    private static final class MemoryPreferences {
        final Map<String, Object> values = new HashMap<>();
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAll":
                            return new HashMap<>(values);
                        case "getString":
                            Object value = values.get(args[0]);
                            return value == null ? args[1] : (String) value;
                        case "contains":
                            return values.containsKey(args[0]);
                        case "edit":
                            return editor(values);
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });

        private static SharedPreferences.Editor editor(Map<String, Object> values) {
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putString":
                                values.put((String) args[0], args[1]);
                                return proxy;
                            case "remove":
                                values.remove(args[0]);
                                return proxy;
                            case "clear":
                                values.clear();
                                return proxy;
                            case "apply":
                                return null;
                            case "commit":
                                return true;
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == long.class) return 0L;
            if (type == int.class) return 0;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return (char) 0;
            return null;
        }
    }
}
