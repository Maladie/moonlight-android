package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller friendly child allowlist editor for one game.
 *
 * The caller supplies the already-authorized child set. This panel never loads
 * profiles or writes a registry; it returns the complete selected ID list.
 */
final class ConsoleChildGameSharingPanel extends LinearLayout {
    interface SaveCallback {
        void save(Draft draft, Completion completion);
    }

    interface Completion {
        void onSaved(int revision);
        void onFailed(CharSequence message, boolean conflict);
    }

    static final class ChildOption {
        final String id;
        final String name;
        final boolean granted;

        ChildOption(String id, String name, boolean granted) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.granted = granted;
        }
    }

    static final class Draft {
        final List<String> selectedChildIds;
        final int expectedRevision;

        Draft(List<String> selectedChildIds, int expectedRevision) {
            ArrayList<String> ids = new ArrayList<>();
            if (selectedChildIds != null) {
                for (String id : selectedChildIds) {
                    if (id != null && id.length() > 0 && !ids.contains(id)) ids.add(id);
                }
            }
            this.selectedChildIds = Collections.unmodifiableList(ids);
            this.expectedRevision = expectedRevision;
        }
    }

    private static final class OptionRow {
        final ChildOption option;
        final LinearLayout row;
        final CheckBox check;
        boolean originalGranted;

        OptionRow(ChildOption option, LinearLayout row, CheckBox check) {
            this.option = option;
            this.row = row;
            this.check = check;
            this.originalGranted = option.granted;
        }
    }

    private final List<OptionRow> optionRows = new ArrayList<>();
    private static final long AUTOSAVE_DELAY_MS = 5000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autosave = this::autosaveDue;
    private TextView statusView;
    private TextView cancelButton;
    private SaveCallback saveCallback;
    private Runnable cancelCallback;
    private Draft inFlightDraft;
    private int expectedRevision;
    private long lastChangeUptime;
    private String requestSignature = "";
    private String requestId = "";
    private boolean saveInFlight;
    private boolean dismissRequested;
    private boolean retryAvailable;
    private Runnable refreshCallback;

    ConsoleChildGameSharingPanel(Context context) {
        super(context);
        setVisibility(GONE);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void show(String gameName, List<ChildOption> ownChildren, int revision,
              SaveCallback save, Runnable cancel) {
        handler.removeCallbacks(autosave);
        expectedRevision = revision;
        saveCallback = save;
        cancelCallback = cancel;
        inFlightDraft = null;
        requestSignature = "";
        requestId = "";
        lastChangeUptime = 0L;
        saveInFlight = false;
        dismissRequested = false;
        retryAvailable = false;
        refreshCallback = null;
        optionRows.clear();
        removeAllViews();

        Map<String, ChildOption> unique = new LinkedHashMap<>();
        if (ownChildren != null) {
            for (ChildOption option : ownChildren) {
                if (option == null || option.id.length() == 0 || unique.containsKey(option.id)) continue;
                unique.put(option.id, option);
            }
        }
        if (unique.isEmpty()) {
            addView(label(getContext().getString(R.string.console_child_sharing_empty),
                    13, 0xFF9EADBD, false), params(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, dp(14)));
        } else {
            for (ChildOption option : unique.values()) addOption(option);
        }

        statusView = label("", 13, 0xFFB7D7C0, false);
        statusView.setVisibility(GONE);
        statusView.setPadding(dp(4), dp(4), dp(4), dp(2));
        addView(statusView, params(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(8), dp(2), dp(8), dp(2)));

        LinearLayout actions = new LinearLayout(getContext());
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        cancelButton = actionButton(R.string.console_close);
        cancelButton.setOnClickListener(view -> {
            if (cancelCallback != null) cancelCallback.run();
        });
        actions.addView(cancelButton, params(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), dp(8), dp(8), 0, 0));
        addView(actions, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), 0, 0, 0, 0));

        setVisibility(VISIBLE);
        wireFocusOrder();
        View first = optionRows.isEmpty() ? cancelButton : optionRows.get(0).row;
        first.post(first::requestFocus);
    }

    Draft currentDraft() {
        return readDraft();
    }

    /** Queue a dirty snapshot before the activity invalidates this panel context. */
    void prepareForDismiss() {
        dismissRequested = true;
        handler.removeCallbacks(autosave);
        if (!saveInFlight && hasChanges()) startSave();
    }

    boolean isDismissRequested() {
        return dismissRequested;
    }

    /** Resume the panel after its existing view hierarchy was restored from panel history. */
    void resumeAfterRestore() {
        dismissRequested = false;
    }

    void setRefreshCallback(Runnable refresh) {
        refreshCallback = refresh;
    }

    String requestIdFor(String signature) {
        String value = signature == null ? "" : signature;
        if (!value.equals(requestSignature) || requestId.length() == 0) {
            requestSignature = value;
            requestId = UUID.randomUUID().toString();
        }
        return requestId;
    }

    /** Drop callbacks for a host/profile that is no longer selected. */
    void discardPendingSave() {
        handler.removeCallbacks(autosave);
        inFlightDraft = null;
        saveInFlight = false;
    }

    private void addOption(ChildOption option) {
        LinearLayout row = new LinearLayout(getContext());
        row.setId(View.generateViewId());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(5), dp(12), dp(5));
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(false);
        row.setBackground(optionBackground(false));

        CheckBox check = new CheckBox(getContext());
        check.setId(View.generateViewId());
        check.setChecked(option.granted);
        check.setFocusable(false);
        check.setClickable(false);
        check.setSoundEffectsEnabled(false);
        check.setTextColor(Color.WHITE);
        row.addView(check, params(dp(42), dp(48), 0, 0, dp(8), 0));

        TextView name = label(displayName(option),
                15, Color.WHITE, true);
        name.setSingleLine(true);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, params(0, dp(48), 0, 0, 0, 0, 1f));
        updateOptionDescription(row, option, check.isChecked());
        row.setOnClickListener(view -> {
            check.setChecked(!check.isChecked());
            updateOptionDescription(row, option, check.isChecked());
            lastChangeUptime = SystemClock.uptimeMillis();
            scheduleAutosave();
            wireFocusOrder();
        });
        row.setOnFocusChangeListener((view, focused) -> row.setBackground(optionBackground(focused)));
        addView(row, params(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), 0, 0, 0, dp(7)));
        optionRows.add(new OptionRow(option, row, check));
    }

    private void updateOptionDescription(View row, ChildOption option, boolean checked) {
        String name = displayName(option);
        row.setContentDescription(getContext().getString(
                R.string.console_child_sharing_option_state, name,
                getContext().getString(checked
                        ? R.string.console_child_sharing_selected
                        : R.string.console_child_sharing_not_selected)));
    }

    private String displayName(ChildOption option) {
        String name = option.name.trim();
        return name.length() == 0
                ? getContext().getString(R.string.console_child_profile_title)
                : name;
    }

    private void scheduleAutosave() {
        handler.removeCallbacks(autosave);
        if (!hasChanges()) {
            setStatus(0, false);
            return;
        }
        setStatus(R.string.console_child_sharing_pending, true);
        if (dismissRequested) {
            if (!saveInFlight) startSave();
            return;
        }
        long elapsed = lastChangeUptime == 0L
                ? AUTOSAVE_DELAY_MS
                : SystemClock.uptimeMillis() - lastChangeUptime;
        handler.postDelayed(autosave, Math.max(0L, AUTOSAVE_DELAY_MS - elapsed));
    }

    private void autosaveDue() {
        if (!hasChanges()) return;
        if (saveInFlight) {
            return;
        }
        startSave();
    }

    private void startSave() {
        if (saveInFlight || !hasChanges()) return;
        if (saveCallback == null) {
            finishFailed(null, false);
            return;
        }
        saveInFlight = true;
        retryAvailable = false;
        inFlightDraft = readDraft();
        setStatus(R.string.console_child_sharing_saving, true);
        SaveCallback callback = saveCallback;
        try {
            callback.save(inFlightDraft, new Completion() {
                @Override
                public void onSaved(int revision) {
                    finishSaved(revision);
                }

                @Override
                public void onFailed(CharSequence message, boolean conflict) {
                    finishFailed(message, conflict);
                }
            });
        } catch (RuntimeException error) {
            finishFailed(null, false);
        }
    }

    private Draft readDraft() {
        ArrayList<String> selected = new ArrayList<>();
        for (OptionRow row : optionRows) {
            if (row.check.isChecked()) selected.add(row.option.id);
        }
        return new Draft(selected, expectedRevision);
    }

    private void finishSaved(int revision) {
        runOnUiThread(() -> {
            if (!saveInFlight) return;
            Draft completed = inFlightDraft;
            inFlightDraft = null;
            saveInFlight = false;
            expectedRevision = Math.max(0, revision);
            if (completed != null) {
                for (OptionRow option : optionRows) {
                    option.originalGranted = completed.selectedChildIds.contains(option.option.id);
                }
            }
            if (hasChanges()) {
                if (dismissRequested) startSave();
                else scheduleAutosave();
            } else {
                setStatus(R.string.console_child_sharing_saved, true);
            }
        });
    }

    private void finishFailed(CharSequence message, boolean conflict) {
        runOnUiThread(() -> {
            if (!saveInFlight) return;
            inFlightDraft = null;
            saveInFlight = false;
            CharSequence shown = message;
            if (shown == null || shown.length() == 0) {
                shown = getContext().getString(conflict
                        ? R.string.console_child_sharing_save_conflict
                        : R.string.console_child_sharing_save_failed);
            }
            setStatus(shown, false);
            retryAvailable = !dismissRequested && (!conflict || refreshCallback != null);
            if (statusView != null) {
                statusView.setClickable(retryAvailable);
                statusView.setFocusable(retryAvailable);
                if (retryAvailable) {
                    statusView.setOnClickListener(view -> {
                    retryAvailable = false;
                    if (conflict) {
                        if (refreshCallback != null) refreshCallback.run();
                    } else if (hasChanges()) startSave();
                    });
                } else {
                    statusView.setOnClickListener(null);
                }
            }
            wireFocusOrder();
            if (dismissRequested && !isShown()) {
                ConsoleUiFeedback.makeText(getContext(), shown, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void runOnUiThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else post(action);
    }

    private void setStatus(int textResource, boolean visible) {
        setStatus(textResource == 0 ? "" : getContext().getString(textResource), visible);
    }

    private void setStatus(CharSequence text, boolean success) {
        if (statusView == null) return;
        statusView.setText(text);
        statusView.setTextColor(success ? 0xFFB7D7C0 : 0xFFFFB4A8);
        statusView.setVisibility(text == null || text.length() == 0 ? GONE : VISIBLE);
        statusView.setClickable(false);
        statusView.setFocusable(false);
        statusView.setOnClickListener(null);
    }

    private boolean hasChanges() {
        for (OptionRow option : optionRows) {
            if (option.check.isChecked() != option.originalGranted) return true;
        }
        return false;
    }

    private void wireFocusOrder() {
        ArrayList<View> order = new ArrayList<>();
        for (OptionRow option : optionRows) appendFocusable(order, option.row);
        appendFocusable(order, statusView);
        appendFocusable(order, cancelButton);
        if (order.isEmpty()) return;
        for (int index = 0; index < order.size(); index++) {
            View current = order.get(index);
            current.setNextFocusDownId(order.get(Math.min(index + 1, order.size() - 1)).getId());
            current.setNextFocusUpId(order.get(Math.max(0, index - 1)).getId());
        }
    }

    private void appendFocusable(List<View> order, View view) {
        if (view != null && view.isEnabled() && view.isFocusable()) order.add(view);
    }

    private TextView actionButton(int textResource) {
        TextView button = label(getContext().getString(textResource), 14, 0xFFF0E9FF, true);
        button.setId(View.generateViewId());
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        button.setSoundEffectsEnabled(false);
        button.setMinWidth(dp(112));
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(actionBackground(false));
        button.setOnFocusChangeListener((view, focused) ->
                button.setBackground(actionBackground(focused)));
        return button;
    }

    private TextView label(CharSequence value, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable optionBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{focused ? 0xFF32444F : 0x76202D38,
                        focused ? 0xFF172129 : 0x44101721});
        background.setCornerRadius(dp(9));
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFDDF5FF : 0x425E7189);
        return background;
    }

    private GradientDrawable actionBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{focused ? 0xFF32444F : 0xC0232C33,
                        focused ? 0xFF172129 : 0xD0141A1F});
        background.setCornerRadius(dp(12));
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xFFDDF5FF : 0x55788A96);
        return background;
    }

    private LinearLayout.LayoutParams params(int width, int height,
                                             int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height);
        result.setMargins(left, top, right, bottom);
        return result;
    }

    private LinearLayout.LayoutParams params(int width, int height,
                                             int left, int top, int right, int bottom,
                                             float weight) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height, weight);
        result.setMargins(left, top, right, bottom);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
