package com.limelight.console;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import java.util.Arrays;

import org.junit.Test;

public class EmbeddedTvKeyboardModelTest {
    @Test public void alphabetHasFourRowsAndTheWideSpaceWinsDownwardGeometry() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();

        assertEquals(4, rowCount(keyboard));
        keyboard.select("text.v");
        assertTrue(keyboard.move(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals("space", keyboard.selectedId());
    }

    @Test public void horizontalNavigationStaysOnTheLetterRowAndDownUsesNearestBottomKey() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();
        keyboard.select("text.b");
        assertTrue(keyboard.move(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals("text.n", keyboard.selectedId());
        assertTrue(keyboard.move(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals("text.b", keyboard.selectedId());

        keyboard.select("text.z");
        assertTrue(keyboard.move(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals("symbols", keyboard.selectedId());
        keyboard.select("text.c");
        assertTrue(keyboard.move(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals("microphone", keyboard.selectedId());
        keyboard.select("text.m");
        assertTrue(keyboard.move(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals("send", keyboard.selectedId());
        keyboard.select("text.!");
        assertTrue(keyboard.move(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals("send", keyboard.selectedId());
    }

    @Test public void alphabetKeepsBasicPunctuationOnThePrimaryPageWithoutOverlappingKeys() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();
        assertEquals(",", find(keyboard, "text.,").text);
        assertEquals(".", find(keyboard, "text..").text);
        assertEquals("?", find(keyboard, "text.?").text);
        assertEquals("!", find(keyboard, "text.!").text);

        java.util.List<EmbeddedTvKeyboardModel.Key> keys = keyboard.keys();
        for (EmbeddedTvKeyboardModel.Key key : keys) {
            EmbeddedTvKeyboardModel.Bounds bounds = EmbeddedTvKeyboardModel.bounds(key, 1920, 336);
            assertTrue(bounds.left >= 0 && bounds.right <= 1920);
            assertTrue(bounds.top >= 0 && bounds.bottom <= 336);
            for (EmbeddedTvKeyboardModel.Key other : keys) {
                if (key == other || key.y != other.y) continue;
                EmbeddedTvKeyboardModel.Bounds otherBounds = EmbeddedTvKeyboardModel.bounds(other, 1920, 336);
                assertTrue(bounds.right <= otherBounds.left || otherBounds.right <= bounds.left);
            }
        }
        for (String id : new String[]{"text.,", "text..", "text.?", "text.!"}) {
            EmbeddedTvKeyboardModel.Bounds bounds = EmbeddedTvKeyboardModel.bounds(find(keyboard, id), 1920, 336);
            assertTrue(bounds.right - bounds.left >= 96);
            assertTrue(bounds.bottom - bounds.top >= 72);
        }
    }

    @Test public void shiftCyclesOneShotAndCapsWithoutLosingTheSelectedKey() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();
        keyboard.select("shift");

        keyboard.activateSelected();
        assertEquals(EmbeddedTvKeyboardModel.Shift.ONE_SHOT, keyboard.shift());
        keyboard.select("text.a");
        assertEquals("A", keyboard.activateSelected().text);
        assertEquals(EmbeddedTvKeyboardModel.Shift.LOWER, keyboard.shift());

        keyboard.select("shift");
        keyboard.activateSelected();
        keyboard.activateSelected();
        assertEquals(EmbeddedTvKeyboardModel.Shift.CAPS, keyboard.shift());
        keyboard.select("text.a");
        assertEquals("A", keyboard.activateSelected().text);
        assertEquals(EmbeddedTvKeyboardModel.Shift.CAPS, keyboard.shift());
    }

    @Test public void polishPageButtonIsRemovedButSymbolPageRemainsReachable() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();
        assertFalse(hasKey(keyboard, "polish"));

        keyboard.select("symbols");
        keyboard.activateSelected();
        assertEquals(EmbeddedTvKeyboardModel.Page.SYMBOLS, keyboard.page());
        keyboard.select("text.@");
        assertEquals("@", keyboard.activateSelected().text);
    }

    @Test public void insertionAndDeletionKeepWholeUnicodeCodePointsAtTheCursor() {
        EmbeddedTvKeyboardModel.Edit inserted = EmbeddedTvKeyboardModel.insert("ab", 1, 1, "Ą");
        assertEquals("aĄb", inserted.text);
        assertEquals(2, inserted.selection);

        String emoji = "a\uD83D\uDE00b";
        EmbeddedTvKeyboardModel.Edit deleted = EmbeddedTvKeyboardModel.backspace(emoji, 3, 3);
        assertEquals("ab", deleted.text);
        assertEquals(1, deleted.selection);
    }

    @Test public void cursorShortcutsMoveByWholeUnicodeCodePoints() {
        String value = "a\uD83D\uDE00b";
        assertEquals(1, EmbeddedTvKeyboardModel.moveCursorByCodePoints(value, 3, -1));
        assertEquals(3, EmbeddedTvKeyboardModel.moveCursorByCodePoints(value, 1, 1));
        assertEquals(0, EmbeddedTvKeyboardModel.moveCursorByCodePoints(value, 0, -1));
        assertEquals(value.length(), EmbeddedTvKeyboardModel.moveCursorByCodePoints(value, value.length(), 1));
    }

    @Test public void accentVariantsCoverPolishLettersAndRespectShiftModes() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();
        assertEquals(Arrays.asList("ź", "ż", "ž"), EmbeddedTvKeyboardModel.accentVariants("z",
                EmbeddedTvKeyboardModel.Shift.LOWER, "pl-PL"));
        assertEquals(Arrays.asList("Ź", "Ż", "Ž"), EmbeddedTvKeyboardModel.accentVariants("z",
                EmbeddedTvKeyboardModel.Shift.CAPS, "PL_pl"));
        keyboard.cycleShift();

        keyboard.select("text.a");
        EmbeddedTvKeyboardModel.Activation accented = keyboard.activateAccent("Ą");
        assertEquals(EmbeddedTvKeyboardModel.Action.TEXT, accented.action);
        assertEquals("Ą", accented.text);
        assertEquals(EmbeddedTvKeyboardModel.Shift.LOWER, keyboard.shift());
    }

    @Test public void localePriorityUsesPrimaryLanguageCaseInsensitivelyAndKeepsFallbacks() {
        assertEquals("ą", EmbeddedTvKeyboardModel.accentVariants("a", EmbeddedTvKeyboardModel.Shift.LOWER,
                "PL-pl").get(0));
        assertEquals("ß", EmbeddedTvKeyboardModel.accentVariants("s", EmbeddedTvKeyboardModel.Shift.LOWER,
                "de-DE").get(0));
        assertEquals("œ", EmbeddedTvKeyboardModel.accentVariants("o", EmbeddedTvKeyboardModel.Shift.LOWER,
                "fr_CA").get(1));
        assertEquals("ı", EmbeddedTvKeyboardModel.accentVariants("i", EmbeddedTvKeyboardModel.Shift.LOWER,
                "tr-TR").get(0));
        assertEquals("ă", EmbeddedTvKeyboardModel.accentVariants("a", EmbeddedTvKeyboardModel.Shift.LOWER,
                "ro-RO").get(0));
        assertEquals("å", EmbeddedTvKeyboardModel.accentVariants("a", EmbeddedTvKeyboardModel.Shift.LOWER,
                "sv-SE").get(0));
        assertTrue(EmbeddedTvKeyboardModel.accentVariants("a", EmbeddedTvKeyboardModel.Shift.LOWER,
                "en-US").contains("æ"));
    }

    @Test public void curatedVariantsAreDeduplicatedAndUseSingleCodePoints() {
        for (String base : Arrays.asList("a", "c", "d", "e", "g", "i", "l", "n", "o", "r",
                "s", "t", "u", "y", "z")) {
            java.util.List<String> values = EmbeddedTvKeyboardModel.accentVariants(base,
                    EmbeddedTvKeyboardModel.Shift.LOWER, "en-US");
            assertFalse(values.contains(base));
            assertEquals(values.size(), new java.util.HashSet<>(values).size());
            for (String value : values) assertEquals(1, value.codePointCount(0, value.length()));
        }
        assertTrue(EmbeddedTvKeyboardModel.accentVariants("a", EmbeddedTvKeyboardModel.Shift.LOWER,
                "en").containsAll(Arrays.asList("æ", "å", "ą", "ă")));
        assertTrue(EmbeddedTvKeyboardModel.accentVariants("o", EmbeddedTvKeyboardModel.Shift.LOWER,
                "en").containsAll(Arrays.asList("œ", "ø", "ő")));
        assertTrue(EmbeddedTvKeyboardModel.accentVariants("s", EmbeddedTvKeyboardModel.Shift.LOWER,
                "en").containsAll(Arrays.asList("ß", "ș")));
        assertTrue(EmbeddedTvKeyboardModel.accentVariants("t", EmbeddedTvKeyboardModel.Shift.LOWER,
                "en").containsAll(Arrays.asList("þ", "ț")));
        assertTrue(EmbeddedTvKeyboardModel.accentVariants("d", EmbeddedTvKeyboardModel.Shift.LOWER,
                "en").contains("ð"));
        assertTrue(EmbeddedTvKeyboardModel.accentVariants("i", EmbeddedTvKeyboardModel.Shift.LOWER,
                "en").contains("ı"));
    }

    @Test public void uppercaseIsOneCodePointAndHandlesGermanAndTurkishSpecialCases() {
        assertEquals("ẞ", EmbeddedTvKeyboardModel.uppercaseCodePoint("ß", "de-DE"));
        assertEquals("I", EmbeddedTvKeyboardModel.uppercaseCodePoint("ı", "tr-TR"));
        assertEquals("İ", EmbeddedTvKeyboardModel.uppercaseCodePoint("i", "tr-TR"));
        assertEquals("I", EmbeddedTvKeyboardModel.uppercaseCodePoint("i", "en-US"));
        assertEquals("Ź", EmbeddedTvKeyboardModel.uppercaseCodePoint("ź", "pl-PL"));
    }

    @Test public void accentPopupGeometryStaysInsideKeyboardAndFallsBelowTheTopRow() {
        EmbeddedTvKeyboardModel.PopupBounds topRow = EmbeddedTvKeyboardModel.accentPopupBounds(
                new EmbeddedTvKeyboardModel.Bounds(0, 0, 96, 84), 180, 42, 960, 336);
        assertEquals(0, topRow.left);
        assertEquals(84, topRow.top);
        assertTrue(topRow.right <= 960);
        assertTrue(topRow.bottom <= 336);

        EmbeddedTvKeyboardModel.PopupBounds rightEdge = EmbeddedTvKeyboardModel.accentPopupBounds(
                new EmbeddedTvKeyboardModel.Bounds(900, 168, 960, 252), 180, 42, 960, 336);
        assertEquals(780, rightEdge.left);
        assertTrue(rightEdge.top >= 0);
        assertTrue(rightEdge.bottom <= 336);
    }

    @Test public void accentSelectionClampsAndDirectionalRepeatHasOneInitialStart() {
        assertEquals(0, EmbeddedTvKeyboardModel.moveAccentSelection(0, -1, 2));
        assertEquals(1, EmbeddedTvKeyboardModel.moveAccentSelection(0, 1, 2));
        assertEquals(1, EmbeddedTvKeyboardModel.moveAccentSelection(1, 1, 2));
        assertTrue(EmbeddedTvKeyboardView.shouldStartDirectionalRepeat(KeyEvent.KEYCODE_UNKNOWN,
                KeyEvent.KEYCODE_DPAD_LEFT));
        assertFalse(EmbeddedTvKeyboardView.shouldStartDirectionalRepeat(KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(400L, EmbeddedTvKeyboardView.DIRECTION_REPEAT_INITIAL_DELAY_MS);
        assertEquals(180L, EmbeddedTvKeyboardView.DIRECTION_REPEAT_INTERVAL_MS);
    }

    @Test public void editLimitRejectsAWholeInsertionInsteadOfTruncatingACharacter() {
        StringBuilder full = new StringBuilder();
        for (int index = 0; index < EmbeddedTvKeyboardModel.MAX_DRAFT_LENGTH; index++) full.append('x');

        EmbeddedTvKeyboardModel.Edit result = EmbeddedTvKeyboardModel.insert(full.toString(),
                EmbeddedTvKeyboardModel.MAX_DRAFT_LENGTH, EmbeddedTvKeyboardModel.MAX_DRAFT_LENGTH, "Ą");
        assertEquals(full.toString(), result.text);
        assertEquals(EmbeddedTvKeyboardModel.MAX_DRAFT_LENGTH, result.selection);
    }

    @Test public void resetRestoresTheSafeAlphabetInitialSelection() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();
        keyboard.select("symbols");
        keyboard.activateSelected();
        keyboard.reset();

        assertEquals(EmbeddedTvKeyboardModel.Page.ALPHA, keyboard.page());
        assertEquals(EmbeddedTvKeyboardModel.Shift.LOWER, keyboard.shift());
        assertEquals("text.q", keyboard.selectedId());
        assertFalse(keyboard.move(KeyEvent.KEYCODE_DPAD_LEFT));
    }

    @Test public void sharedBoundsKeepWideSpaceAndSendInsideOneFullBottomRow() {
        EmbeddedTvKeyboardModel keyboard = new EmbeddedTvKeyboardModel();
        EmbeddedTvKeyboardModel.Key space = find(keyboard, "space");
        EmbeddedTvKeyboardModel.Key send = find(keyboard, "send");

        EmbeddedTvKeyboardModel.Bounds spaceBounds = EmbeddedTvKeyboardModel.bounds(space, 1000, 400);
        EmbeddedTvKeyboardModel.Bounds sendBounds = EmbeddedTvKeyboardModel.bounds(send, 1000, 400);
        assertEquals(400, spaceBounds.left);
        assertEquals(700, spaceBounds.right);
        assertEquals(700, sendBounds.left);
        assertEquals(1000, sendBounds.right);
        assertTrue(spaceBounds.contains(550, 350));
        assertTrue(sendBounds.contains(850, 350));
    }

    private static int rowCount(EmbeddedTvKeyboardModel keyboard) {
        int max = -1;
        for (EmbeddedTvKeyboardModel.Key key : keyboard.keys()) max = Math.max(max, (int) key.y);
        return max + 1;
    }

    private static EmbeddedTvKeyboardModel.Key find(EmbeddedTvKeyboardModel keyboard, String id) {
        for (EmbeddedTvKeyboardModel.Key key : keyboard.keys()) if (id.equals(key.id)) return key;
        throw new AssertionError("missing key " + id);
    }

    private static boolean hasKey(EmbeddedTvKeyboardModel keyboard, String id) {
        for (EmbeddedTvKeyboardModel.Key key : keyboard.keys()) if (id.equals(key.id)) return true;
        return false;
    }
}
