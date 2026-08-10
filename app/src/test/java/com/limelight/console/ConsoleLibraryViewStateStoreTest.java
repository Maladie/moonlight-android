package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ConsoleLibraryViewStateStoreTest {
    @Test
    public void keepsNavigationStateSeparateForEachHost() {
        MemoryBackend backend = new MemoryBackend();
        ConsoleLibraryViewStateStore store = new ConsoleLibraryViewStateStore(backend);
        store.saveCarouselGame("one", "carousel-one");
        store.saveExpandedGame("one", "grid-one");
        store.saveSearch("one", "hollow");
        store.saveExpandedMode("one", true);

        ConsoleLibraryViewStateStore.State one = store.load("one", "legacy");
        ConsoleLibraryViewStateStore.State two = store.load("two", "legacy-two");
        assertEquals("carousel-one", one.carouselGameId);
        assertEquals("grid-one", one.expandedGameId);
        assertEquals("hollow", one.searchQuery);
        assertTrue(one.expandedMode);
        assertEquals("legacy-two", two.carouselGameId);
        assertFalse(two.expandedMode);
    }

    @Test
    public void clearOnlyRemovesRequestedHost() {
        MemoryBackend backend = new MemoryBackend();
        ConsoleLibraryViewStateStore store = new ConsoleLibraryViewStateStore(backend);
        store.saveCarouselGame("one", "first");
        store.saveCarouselGame("two", "second");
        store.clear("one");

        assertEquals("fallback", store.load("one", "fallback").carouselGameId);
        assertEquals("second", store.load("two", "fallback").carouselGameId);
    }

    private static final class MemoryBackend implements ConsoleLibraryViewStateStore.Backend {
        private final Map<String, Object> values = new HashMap<>();

        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }

        @Override public void remove(String... keys) {
            for (String key : keys) values.remove(key);
        }
    }
}
