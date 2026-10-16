package com.limelight.console;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
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
 * Which of the parent's children may play one game: a {@link ConsolePanel} switch per child,
 * saved 5 s after the last change or when the panel closes.
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
        boolean granted;
        boolean originalGranted;

        OptionRow(ChildOption option, LinearLayout row) {
            this.option = option;
            this.row = row;
            this.granted = option.granted;
            this.originalGranted = option.granted;
        }
    }

    private final List<OptionRow> optionRows = new ArrayList<>();
    private static final long AUTOSAVE_DELAY_MS = 5000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autosave = this::autosaveDue;
    private final ConsolePanel panel;
    private TextView statusView;
    /** Shown after a failed save: retries it, or refreshes after a conflict. */
    private TextView retryRow;
    private SaveCallback saveCallback;
    private Draft inFlightDraft;
    private int expectedRevision;
    private long lastChangeUptime;
    private String requestSignature = "";
    private String requestId = "";
    private boolean saveInFlight;
    private boolean dismissRequested;
    private Runnable refreshCallback;

    ConsoleChildGameSharingPanel(Context context, ConsolePanel panel) {
        super(context);
        this.panel = panel;
        setVisibility(GONE);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void show(List<ChildOption> ownChildren, int revision, SaveCallback save) {
        handler.removeCallbacks(autosave);
        expectedRevision = revision;
        saveCallback = save;
        inFlightDraft = null;
        requestSignature = "";
        requestId = "";
        lastChangeUptime = 0L;
        saveInFlight = false;
        dismissRequested = false;
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
            addView(panel.note(getContext().getString(R.string.console_child_sharing_empty)));
        } else {
            for (ChildOption option : unique.values()) addOption(option);
        }
        statusView = panel.note("");
        statusView.setVisibility(GONE);
        addView(statusView);
        retryRow = panel.action("");
        retryRow.setVisibility(GONE);
        addView(retryRow);

        setVisibility(VISIBLE);
        wireFocusOrder();
        if (!optionRows.isEmpty()) {
            View first = optionRows.get(0).row;
            first.post(first::requestFocus);
        }
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
        String name = option.name.trim().isEmpty()
                ? getContext().getString(R.string.console_child_profile_title) : option.name.trim();
        LinearLayout row = panel.toggle(name, option.granted);
        OptionRow entry = new OptionRow(option, row);
        row.setOnClickListener(view -> {
            entry.granted = !entry.granted;
            panel.setChecked(row, entry.granted);
            lastChangeUptime = SystemClock.uptimeMillis();
            scheduleAutosave();
        });
        addView(row);
        optionRows.add(entry);
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
        retryRow.setVisibility(GONE);
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
            if (row.granted) selected.add(row.option.id);
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
            boolean retry = !dismissRequested && (!conflict || refreshCallback != null);
            retryRow.setText(conflict ? R.string.console_child_management_refresh
                    : R.string.console_child_profile_save_retry);
            retryRow.setVisibility(retry ? VISIBLE : GONE);
            retryRow.setOnClickListener(view -> {
                retryRow.setVisibility(GONE);
                wireFocusOrder();
                if (conflict) {
                    if (refreshCallback != null) refreshCallback.run();
                } else if (hasChanges()) startSave();
            });
            wireFocusOrder();
            if (retry) retryRow.requestFocus();
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
    }

    private boolean hasChanges() {
        for (OptionRow option : optionRows) {
            if (option.granted != option.originalGranted) return true;
        }
        return false;
    }

    private void wireFocusOrder() {
        ArrayList<View> order = new ArrayList<>();
        for (OptionRow option : optionRows) order.add(option.row);
        if (retryRow.getVisibility() == VISIBLE) order.add(retryRow);
        for (int index = 0; index < order.size(); index++) {
            View current = order.get(index);
            current.setNextFocusDownId(order.get(Math.min(index + 1, order.size() - 1)).getId());
            current.setNextFocusUpId(order.get(Math.max(0, index - 1)).getId());
        }
    }
}
