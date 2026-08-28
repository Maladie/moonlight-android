package com.limelight.console;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable TV keyboard pages plus the small, process-local selection state. */
final class EmbeddedTvKeyboardModel {
    static final int MAX_DRAFT_LENGTH = 2000;
    static final float GRID_COLUMNS = 10f;
    static final float GRID_ROWS = 4f;
    private static final Map<String, String> CURATED_ACCENTS = curatedAccents();

    enum Page { ALPHA, POLISH, SYMBOLS }
    enum Shift { LOWER, ONE_SHOT, CAPS }
    enum Type { TEXT, SHIFT, BACKSPACE, SPACE, ALPHA, POLISH, SYMBOLS, MICROPHONE, SEND }
    enum Action { NONE, TEXT, BACKSPACE, SPACE, MICROPHONE, SEND }

    static final class Key {
        final String id;
        final String label;
        final String text;
        final Type type;
        final float x;
        final float y;
        final float width;

        Key(String id, String label, String text, Type type, float x, float y, float width) {
            this.id = id;
            this.label = label;
            this.text = text;
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
        }

        float centerX() { return x + width / 2f; }
        float centerY() { return y + .5f; }
    }

    static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    static final class PopupBounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        PopupBounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    static final class Activation {
        final Action action;
        final String text;

        Activation(Action action, String text) {
            this.action = action;
            this.text = text == null ? "" : text;
        }
    }

    static final class Edit {
        final String text;
        final int selection;

        Edit(String text, int selection) {
            this.text = text;
            this.selection = selection;
        }
    }

    private Page page = Page.ALPHA;
    private Shift shift = Shift.LOWER;
    private String selectedId = "text.q";

    Page page() { return page; }
    Shift shift() { return shift; }
    String selectedId() { return selectedId; }

    Key selectedKey() { return selected(); }

    List<Key> keys() { return keysFor(page, shift); }

    void reset() {
        page = Page.ALPHA;
        shift = Shift.LOWER;
        selectedId = "text.q";
    }

    boolean move(int keyCode) {
        Key selected = selected();
        if (selected == null || !isDirectional(keyCode)) return false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && selected.type == Type.TEXT && selected.y == 2f) {
            selectedId = "space";
            return true;
        }
        boolean horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean hasSameRowCandidate = false;
        if (horizontal) {
            for (Key candidate : keys()) {
                if (candidate != selected && isInDirection(selected, candidate, keyCode)
                        && candidate.y == selected.y) {
                    hasSameRowCandidate = true;
                    break;
                }
            }
        }
        Key best = null;
        float bestScore = Float.MAX_VALUE;
        for (Key candidate : keys()) {
            if (candidate == selected || !isInDirection(selected, candidate, keyCode)) continue;
            if (hasSameRowCandidate && candidate.y != selected.y) continue;
            float primary = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    ? Math.abs(candidate.centerX() - selected.centerX())
                    : Math.abs(candidate.centerY() - selected.centerY());
            float perpendicular = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    ? Math.abs(candidate.centerY() - selected.centerY())
                    : Math.abs(candidate.centerX() - selected.centerX());
            // Prefer a key in the requested half-plane that is closest to the movement axis.
            float normalizedPerpendicular = perpendicular / Math.max(1f, candidate.width);
            float score = primary * primary + normalizedPerpendicular * normalizedPerpendicular * 1.75f;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null) return false;
        selectedId = best.id;
        return true;
    }

    void cycleShift() {
        shift = shift == Shift.LOWER ? Shift.ONE_SHOT
                : shift == Shift.ONE_SHOT ? Shift.CAPS : Shift.LOWER;
    }

    List<String> accentVariantsForSelected() {
        Key key = selected();
        return key == null || key.type != Type.TEXT ? Collections.emptyList()
                : accentVariants(key.text, shift);
    }

    Activation activateAccent(String accent) {
        if (accent == null || !accentVariantsForSelected().contains(accent)) {
            return new Activation(Action.NONE, "");
        }
        if (shift == Shift.ONE_SHOT) shift = Shift.LOWER;
        return new Activation(Action.TEXT, accent);
    }

    void select(String id) {
        for (Key key : keys()) {
            if (key.id.equals(id)) {
                selectedId = id;
                return;
            }
        }
    }

    Activation activateSelected() {
        Key key = selected();
        if (key == null) return new Activation(Action.NONE, "");
        switch (key.type) {
            case TEXT:
                String text = displayText(key);
                if (shift == Shift.ONE_SHOT) shift = Shift.LOWER;
                return new Activation(Action.TEXT, text);
            case SHIFT:
                cycleShift();
                return new Activation(Action.NONE, "");
            case BACKSPACE:
                return new Activation(Action.BACKSPACE, "");
            case SPACE:
                return new Activation(Action.SPACE, " ");
            case ALPHA:
                setPage(Page.ALPHA);
                return new Activation(Action.NONE, "");
            case POLISH:
                setPage(Page.POLISH);
                return new Activation(Action.NONE, "");
            case SYMBOLS:
                setPage(Page.SYMBOLS);
                return new Activation(Action.NONE, "");
            case MICROPHONE:
                return new Activation(Action.MICROPHONE, "");
            case SEND:
                return new Activation(Action.SEND, "");
            default:
                return new Activation(Action.NONE, "");
        }
    }

    String displayLabel(Key key) {
        if (key.type == Type.SHIFT) return shift == Shift.CAPS ? "⇪" : "⇧";
        return key.type == Type.TEXT ? displayText(key) : key.label;
    }

    static Edit insert(String source, int selectionStart, int selectionEnd, String insertion) {
        String value = source == null ? "" : source;
        String addition = insertion == null ? "" : insertion;
        int start = startBoundary(value, bounded(selectionStart, value.length()));
        int end = endBoundary(value, bounded(selectionEnd, value.length()));
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        String result = value.substring(0, start) + addition + value.substring(end);
        if (result.length() > MAX_DRAFT_LENGTH) return new Edit(value, end);
        return new Edit(result, start + addition.length());
    }

    static Edit backspace(String source, int selectionStart, int selectionEnd) {
        String value = source == null ? "" : source;
        int start = startBoundary(value, bounded(selectionStart, value.length()));
        int end = endBoundary(value, bounded(selectionEnd, value.length()));
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        if (start != end) return new Edit(value.substring(0, start) + value.substring(end), start);
        if (start == 0) return new Edit(value, 0);
        int codePointStart = value.offsetByCodePoints(start, -1);
        return new Edit(value.substring(0, codePointStart) + value.substring(start), codePointStart);
    }

    static int moveCursorByCodePoints(String source, int selection, int direction) {
        String value = source == null ? "" : source;
        int cursor = startBoundary(value, bounded(selection, value.length()));
        if (direction < 0 && cursor > 0) return value.offsetByCodePoints(cursor, -1);
        if (direction > 0 && cursor < value.length()) return value.offsetByCodePoints(cursor, 1);
        return cursor;
    }

    static Bounds bounds(Key key, int width, int height) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        return new Bounds(Math.round(key.x / GRID_COLUMNS * safeWidth), Math.round(key.y / GRID_ROWS * safeHeight),
                Math.round((key.x + key.width) / GRID_COLUMNS * safeWidth),
                Math.round((key.y + 1f) / GRID_ROWS * safeHeight));
    }

    static PopupBounds accentPopupBounds(Bounds keyBounds, int popupWidth, int popupHeight,
                                         int availableWidth, int availableHeight) {
        int width = Math.max(1, Math.min(Math.max(1, popupWidth), Math.max(1, availableWidth)));
        int height = Math.max(1, Math.min(Math.max(1, popupHeight), Math.max(1, availableHeight)));
        int left = Math.max(0, Math.min(Math.max(0, availableWidth - width),
                keyBounds.left + (keyBounds.right - keyBounds.left - width) / 2));
        int above = keyBounds.top - height;
        int top = above >= 0 ? above : Math.min(Math.max(0, availableHeight - height), keyBounds.bottom);
        return new PopupBounds(left, top, left + width, top + height);
    }

    static List<String> accentVariants(String base, Shift shift) {
        return accentVariants(base, shift, Locale.getDefault().toLanguageTag());
    }

    /** Curated, locale-prioritized variants; every returned entry is exactly one code point. */
    static List<String> accentVariants(String base, Shift shift, String languageTag) {
        String letter = base == null ? "" : base.toLowerCase(Locale.ROOT);
        String canonical = CURATED_ACCENTS.get(letter);
        if (canonical == null) return Collections.emptyList();
        String language = primaryLanguage(languageTag);
        Set<Integer> ordered = new LinkedHashSet<>();
        addCodePoints(ordered, localePriority(language, letter));
        addCodePoints(ordered, canonical);
        ArrayList<String> result = new ArrayList<>();
        for (int codePoint : ordered) {
            result.add(shift == Shift.LOWER ? new String(Character.toChars(codePoint))
                    : uppercaseCodePoint(codePoint, language));
        }
        return Collections.unmodifiableList(result);
    }

    static String primaryLanguage(String languageTag) {
        String tag = languageTag == null ? "" : languageTag.trim();
        int separator = tag.indexOf('-');
        if (separator < 0) separator = tag.indexOf('_');
        return (separator < 0 ? tag : tag.substring(0, separator)).toLowerCase(Locale.ROOT);
    }

    static String uppercaseCodePoint(String value, String languageTag) {
        if (value == null || value.codePointCount(0, value.length()) != 1) return value == null ? "" : value;
        return uppercaseCodePoint(value.codePointAt(0), primaryLanguage(languageTag));
    }

    private static String uppercaseCodePoint(int codePoint, String language) {
        if (codePoint == 0x00DF) return "ẞ"; // ß must remain one code point, not SS.
        if (codePoint == 0x0131) return "I"; // dotless i
        if (codePoint == 'i' && "tr".equals(language)) return "İ";
        return new String(Character.toChars(Character.toUpperCase(codePoint)));
    }

    private static Map<String, String> curatedAccents() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("a", "áàâäãåāăąæ");
        values.put("c", "çćč");
        values.put("d", "ďð");
        values.put("e", "éèêëēĕėęě");
        values.put("g", "ĝğġ");
        values.put("i", "íìîïīĭįı");
        values.put("l", "łľĺ");
        values.put("n", "ñńň");
        values.put("o", "óòôöõøōŏőœ");
        values.put("r", "ŕř");
        values.put("s", "śšşșß");
        values.put("t", "ťţțþ");
        values.put("u", "úùûüūŭůűų");
        values.put("y", "ýÿ");
        values.put("z", "źżž");
        return Collections.unmodifiableMap(values);
    }

    private static void addCodePoints(Set<Integer> destination, String values) {
        if (values == null) return;
        for (int index = 0; index < values.length();) {
            int codePoint = values.codePointAt(index);
            destination.add(codePoint);
            index += Character.charCount(codePoint);
        }
    }

    private static String localePriority(String language, String base) {
        switch (language) {
            case "pl": return priority(base, "a:ą,c:ć,e:ę,l:ł,n:ń,o:ó,s:ś,z:źż");
            case "de": return priority(base, "a:ä,o:ö,u:ü,s:ß");
            case "fr": return priority(base, "a:àâä,c:ç,e:éèêë,i:îï,o:ôœ,u:ùûü,y:ÿ");
            case "es": return priority(base, "a:á,e:é,i:í,n:ñ,o:ó,u:ú");
            case "pt": return priority(base, "a:áâãà,c:ç,e:éê,i:í,o:óôõ,u:ú");
            case "it": return priority(base, "a:à,e:èé,i:ìí,o:òó,u:ùú");
            case "cs": return priority(base, "a:á,c:č,d:ď,e:ěé,i:í,n:ň,o:ó,r:ř,s:š,t:ť,u:ůú,y:ý,z:ž");
            case "sk": return priority(base, "a:áä,c:č,d:ď,e:é,i:í,l:ľ,n:ň,o:óô,r:ŕ,s:š,t:ť,u:ú,y:ý,z:ž");
            case "hu": return priority(base, "a:á,e:é,i:í,o:óöő,u:úüű");
            case "ro": return priority(base, "a:ăâ,i:î,s:ș,t:ț");
            case "da":
            case "no": return priority(base, "a:åæ,o:ø");
            case "sv": return priority(base, "a:åä,o:ö");
            case "is": return priority(base, "a:áæ,d:ð,e:é,i:í,o:óö,t:þ,u:ú,y:ý");
            case "nl": return priority(base, "a:áàä,e:éèë,i:ï,o:óö,u:ü");
            case "tr": return priority(base, "c:ç,g:ğ,i:ı,o:ö,s:ş,u:ü");
            default: return "";
        }
    }

    private static String priority(String base, String entries) {
        String marker = base + ":";
        for (String entry : entries.split(",")) {
            if (entry.startsWith(marker)) return entry.substring(marker.length());
        }
        return "";
    }

    static int moveAccentSelection(int selected, int direction, int count) {
        if (count <= 0) return -1;
        if (direction < 0) return Math.max(0, selected - 1);
        if (direction > 0) return Math.min(count - 1, selected + 1);
        return Math.max(0, Math.min(count - 1, selected));
    }

    private void setPage(Page next) {
        page = next;
        shift = Shift.LOWER;
        List<Key> keys = keys();
        selectedId = keys.isEmpty() ? "" : keys.get(0).id;
    }

    private Key selected() {
        for (Key key : keys()) if (key.id.equals(selectedId)) return key;
        List<Key> keys = keys();
        if (keys.isEmpty()) return null;
        selectedId = keys.get(0).id;
        return keys.get(0);
    }

    private String displayText(Key key) {
        return shift == Shift.LOWER ? key.text : uppercaseCodePoint(key.text,
                Locale.getDefault().toLanguageTag());
    }

    private static boolean isDirectional(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    private static boolean isInDirection(Key from, Key to, int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return to.centerX() < from.centerX();
            case KeyEvent.KEYCODE_DPAD_RIGHT: return to.centerX() > from.centerX();
            case KeyEvent.KEYCODE_DPAD_UP: return to.centerY() < from.centerY();
            case KeyEvent.KEYCODE_DPAD_DOWN: return to.centerY() > from.centerY();
            default: return false;
        }
    }

    private static int bounded(int value, int length) { return Math.max(0, Math.min(value, length)); }

    private static int startBoundary(String value, int index) {
        return isSurrogateBoundary(value, index) ? index - 1 : index;
    }

    private static int endBoundary(String value, int index) {
        return isSurrogateBoundary(value, index) ? index + 1 : index;
    }

    private static boolean isSurrogateBoundary(String value, int index) {
        return index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index));
    }

    private static List<Key> keysFor(Page page, Shift shift) {
        ArrayList<Key> keys = new ArrayList<>();
        if (page == Page.ALPHA) {
            row(keys, "qwertyuiop", 0, 0f, .9f);
            keys.add(key("text..", ".", ".", Type.TEXT, 9f, 0, 1f));
            row(keys, "asdfghjkl", 1, 0f, .9f);
            keys.add(key("text.,", ",", ",", Type.TEXT, 8.1f, 1, .95f));
            keys.add(key("text.?", "?", "?", Type.TEXT, 9.05f, 1, .95f));
            keys.add(key("shift", "⇧", "", Type.SHIFT, 0, 2, 1.25f));
            row(keys, "zxcvbnm", 2, 1.25f, .9f);
            keys.add(key("text.!", "!", "!", Type.TEXT, 7.55f, 2, .95f));
            keys.add(key("backspace", "⌫", "", Type.BACKSPACE, 8.5f, 2, 1.5f));
        } else if (page == Page.POLISH) {
            row(keys, "ąćęłńóśźż", 0, .5f);
            row(keys, "qwertyuiop", 1, 0f);
            keys.add(key("shift", "⇧", "", Type.SHIFT, 0, 2, 1.5f));
            row(keys, "zxcvbnm", 2, 1.5f);
            keys.add(key("backspace", "⌫", "", Type.BACKSPACE, 8.5f, 2, 1.5f));
        } else {
            row(keys, "1234567890", 0, 0f);
            row(keys, "@#$%&-+()/", 1, 0f);
            keys.add(key("text._", "_", "_", Type.TEXT, 0, 2, 1f));
            row(keys, "=*\"':;!?", 2, 1f);
            keys.add(key("backspace", "⌫", "", Type.BACKSPACE, 9f, 2, 1f));
        }
        keys.add(key(page == Page.SYMBOLS ? "alpha" : "symbols", page == Page.SYMBOLS ? "ABC" : "123",
                "", page == Page.SYMBOLS ? Type.ALPHA : Type.SYMBOLS, 0, 3, 1.5f));
        keys.add(key("polish", "ĄĘ", "", Type.POLISH, 1.5f, 3, 1.5f));
        keys.add(key("microphone", "MIC", "", Type.MICROPHONE, 3, 3, 1f));
        keys.add(key("space", "SPACE", " ", Type.SPACE, 4, 3, 3f));
        keys.add(key("send", "SEND", "", Type.SEND, 7, 3, 3f));
        return Collections.unmodifiableList(keys);
    }

    private static void row(List<Key> keys, String characters, int row, float x) {
        row(keys, characters, row, x, 1f);
    }

    private static void row(List<Key> keys, String characters, int row, float x, float width) {
        for (int index = 0; index < characters.length();) {
            int codePoint = characters.codePointAt(index);
            String value = new String(Character.toChars(codePoint));
            keys.add(key("text." + value, value, value, Type.TEXT, x, row, width));
            x += width;
            index += Character.charCount(codePoint);
        }
    }

    private static Key key(String id, String label, String text, Type type, float x, float y, float width) {
        return new Key(id, label, text, type, x, y, width);
    }
}
