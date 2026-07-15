package com.limelight.console;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.List;

/** Owns modal composition, focus entry/restore, dim dismissal, and input routing. */
final class ConsoleModalController {
    private final Context context;
    private final FrameLayout layer;
    private final InputRouter inputRouter;
    private final ConsoleTheme theme;
    private final float density;
    private View returnFocus;
    private String integrationHostUuid;
    private TextView gatewayStatus;
    private TextView profileStatus;
    private TextView servicesStatus;
    private TextView chooseProfile;
    private TextView discordIntegration;
    private TextView vibepolloIntegration;
    private TextView virtualHereIntegration;
    private Runnable onDismiss;
    private LinearLayout wakePanel;

    ConsoleModalController(Context context, FrameLayout layer,
                           InputRouter inputRouter, ConsoleTheme theme) {
        this.context = context;
        this.layer = layer;
        this.inputRouter = inputRouter;
        this.theme = theme;
        density = context.getResources().getDisplayMetrics().density;
    }

    boolean dismissIfVisible() {
        if (layer.getVisibility() != View.VISIBLE) return false;
        hide();
        return true;
    }

    void showExitConfirmation(View focusToRestore, boolean streamActive,
                              Runnable exitAction) {
        begin(focusToRestore);
        integrationHostUuid = null;
        TextView cancel = wakeAction("CANCEL");
        cancel.setOnClickListener(view -> hide());
        TextView exit = discordAction("EXIT MOONWAKER", 0xFFDA373C);
        exit.setOnClickListener(view -> { hide(); exitAction.run(); });
        showWakePanel("MOONWAKER", "Close MoonWaker?",
                streamActive ? "The active host session will be stopped." :
                        "The active host session will not be stopped.",
                () -> { }, wakeActionRow(cancel, exit));
    }

    void showSessionDetails(View focusToRestore, ConsoleSessionSummary summary,
                            Runnable returnToGame, Runnable disconnectTransport,
                            Runnable quitHostApplication) {
        begin(focusToRestore);
        integrationHostUuid = null;
        TextView resume = wakeAction("\u25B6  RETURN TO GAME");
        resume.setOnClickListener(view -> { hide(); returnToGame.run(); });
        TextView disconnect = wakeAction("DISCONNECT THIS TV");
        disconnect.setOnClickListener(view -> showSessionCommandConfirmation(
                view, "Disconnect this TV?",
                "The stream will end, but the application will remain running on the host.",
                "DISCONNECT", disconnectTransport));
        TextView quit = wakeAction("END APP ON HOST");
        quit.setOnClickListener(view -> showSessionCommandConfirmation(
                view, "End app on host?",
                "The running application will be closed on the host and this stream will end.",
                "END APP", quitHostApplication));
        showWakePanel("ACTIVE SESSION", summary.title, summary.details,
                null, resume, disconnect, quit);
    }

    void showOptions(View focusToRestore, boolean uiSounds, boolean reducedMotion,
                     Consumer<Boolean> setSounds, Consumer<Boolean> setMotion,
                     Runnable hostIntegrations, Runnable moonlightSettings) {
        begin(focusToRestore);
        integrationHostUuid = null;
        TextView sounds = wakeAction("UI SOUNDS  \u00B7  " + (uiSounds ? "ON" : "OFF"));
        boolean[] soundsEnabled = {uiSounds};
        sounds.setOnClickListener(view -> {
            soundsEnabled[0] = !soundsEnabled[0];
            setSounds.accept(soundsEnabled[0]);
            sounds.setText("UI SOUNDS  \u00B7  " + (soundsEnabled[0] ? "ON" : "OFF"));
            theme.prepareInteractiveView(sounds);
            sounds.requestFocus();
        });
        TextView motion = wakeAction("REDUCED MOTION  \u00B7  " +
                (reducedMotion ? "ON" : "OFF"));
        boolean[] motionReduced = {reducedMotion};
        motion.setOnClickListener(view -> {
            motionReduced[0] = !motionReduced[0];
            setMotion.accept(motionReduced[0]);
            motion.setText("REDUCED MOTION  \u00B7  " + (motionReduced[0] ? "ON" : "OFF"));
            motion.requestFocus();
        });
        TextView integrations = wakeAction("HOST INTEGRATIONS  \u203A");
        integrations.setOnClickListener(view -> hostIntegrations.run());
        TextView moonlight = wakeAction("MOONLIGHT SETTINGS  \u203A");
        moonlight.setOnClickListener(view -> moonlightSettings.run());
        showWakePanel("MOONWAKER", "Options",
                "Tune the console interface or open Moonlight's streaming preferences.",
                null, sounds, motion, integrations, moonlight);
    }

    void showControllerActions(View focusToRestore, int player,
                               ConsoleControllerRepository.Controller controller,
                               Runnable identify, Runnable disconnect, Runnable unpair) {
        boolean canIdentify = ControllerActions.canIdentify(controller.deviceId);
        boolean canDisconnect = ControllerActions.canDisconnect();
        String[] actions = {
                canIdentify ? "Identify controller" : "Identify controller \u00B7 unavailable",
                canDisconnect ? "Power off controller" : "Power off controller \u00B7 unavailable",
                "Unpair controller"
        };
        new AlertDialog.Builder(context)
                .setTitle("P" + player + " \u00B7 " + compactControllerName(controller.name))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        if (canIdentify) identify.run();
                        else showUnavailableControllerFeature(
                                "Sony does not expose LED or vibration controls for this controller to apps.");
                    } else if (which == 1) {
                        if (canDisconnect) confirmPowerOff(disconnect);
                        else showUnavailableControllerFeature(
                                "This Android TV version does not let apps power off Bluetooth controllers.");
                    } else {
                        confirmUnpair(unpair);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String compactControllerName(String name) {
        if (name == null) return "Controller";
        return name.toLowerCase(java.util.Locale.ROOT).contains("dualsense") ?
                "DualSense" : name;
    }

    private void showUnavailableControllerFeature(String message) {
        new AlertDialog.Builder(context)
                .setTitle("Feature unavailable")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmPowerOff(Runnable action) {
        new AlertDialog.Builder(context)
                .setTitle("Power off controller?")
                .setMessage("The controller will disconnect from the TV. Press the PS button to connect it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Power off", (dialog, which) -> action.run())
                .show();
    }

    private void confirmUnpair(Runnable action) {
        new AlertDialog.Builder(context)
                .setTitle("Unpair controller?")
                .setMessage("The Bluetooth pairing will be removed. To use this controller again, pair it with the TV once more.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unpair", (dialog, which) -> action.run())
                .show();
    }

    private void showSessionCommandConfirmation(View focusToRestore, String title,
                                                String message, String actionLabel,
                                                Runnable action) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(actionLabel, (dialog, which) -> action.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showHostWakeTimeout(View focusToRestore, String hostName, Runnable retryAction) {
        begin(focusToRestore);
        integrationHostUuid = null;
        LinearLayout panel = panel();
        panel.addView(label("HOST DID NOT BECOME READY", 24, Color.WHITE, true), wrap());
        panel.addView(label(hostName, 16, 0xFFFFB74D, true), top(dp(10)));
        panel.addView(label("No compatible streaming service answered within 90 seconds.",
                14, 0xFFBDC4D8, false), top(dp(16)));
        TextView retry = card("RETRY", dp(320), dp(58));
        retry.setOnClickListener(view -> {
            hide();
            retryAction.run();
        });
        panel.addView(retry, top(dp(24)));
        TextView home = card("STAY ON HOME", dp(320), dp(58));
        home.setOnClickListener(view -> hide());
        panel.addView(home, top(dp(10)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(620), dp(420), Gravity.CENTER));
        showAndFocus(retry);
    }

    void showConnectionRecovery(View focusToRestore, String reason,
                                Runnable retryAction, Runnable disconnectAction) {
        begin(focusToRestore);
        integrationHostUuid = null;
        LinearLayout panel = panel();
        panel.addView(label("CONNECTION FAILED", 24, Color.WHITE, true), wrap());
        panel.addView(label(reason, 14, 0xFFFFB74D, false), top(dp(14)));
        TextView retry = card("RETRY", dp(320), dp(58));
        retry.setOnClickListener(view -> {
            hide();
            retryAction.run();
        });
        panel.addView(retry, top(dp(24)));
        TextView home = card("STAY ON HOME", dp(320), dp(58));
        home.setOnClickListener(view -> hide());
        panel.addView(home, top(dp(10)));
        TextView disconnect = card("DISCONNECT", dp(320), dp(58));
        disconnect.setOnClickListener(view -> {
            hide();
            disconnectAction.run();
        });
        panel.addView(disconnect, top(dp(10)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(620), dp(500), Gravity.CENTER));
        showAndFocus(retry);
    }

    void showHostIntegrations(View focusToRestore, String hostUuid, String hostName,
                              HostIntegrationSummary summary,
                              Runnable pairGateway, Runnable useDefaultProfile,
                              Runnable openDiscord, Runnable openVibepollo,
                              Runnable openVirtualHere,
                              Runnable dismissAction) {
        begin(focusToRestore);
        integrationHostUuid = hostUuid;
        onDismiss = dismissAction;
        LinearLayout panel = panel();
        panel.setPadding(dp(42), dp(48), dp(42), dp(38));
        panel.setBackgroundColor(0xFF111522);
        panel.addView(label("HOST INTEGRATIONS", 26, Color.WHITE, true), wrap());
        panel.addView(label(hostName, 16, 0xFFB99CFF, true), top(dp(8)));
        gatewayStatus = label(summary.gatewayLabel(), 15,
                summary.gatewayPaired ? 0xFF69F0AE : 0xFFFFB74D, true);
        profileStatus = label(summary.profileLabel(), 14, 0xFFE1E5F2, true);
        servicesStatus = label(summary.servicesLabel(), 14, 0xFF9CA6C5, false);
        panel.addView(gatewayStatus, top(dp(28)));
        panel.addView(profileStatus, top(dp(14)));
        panel.addView(servicesStatus, top(dp(20)));

        TextView pair = card("PAIR HOST GATEWAY", dp(340), dp(56));
        pair.setVisibility(summary.gatewayPaired ? View.GONE : View.VISIBLE);
        pair.setOnClickListener(view -> pairGateway.run());
        panel.addView(pair, top(dp(26)));

        chooseProfile = card("CHOOSE PROFILE", dp(340), dp(56));
        chooseProfile.setVisibility(View.GONE);
        panel.addView(chooseProfile, top(summary.gatewayPaired ? dp(26) : dp(12)));

        TextView useDefault = card("USE DEFAULT PROFILE", dp(340), dp(56));
        boolean canUseDefault = summary.gatewayPaired &&
                !GatewayConnection.DEFAULT_PROFILE_ID.equals(summary.profileId);
        useDefault.setVisibility(canUseDefault ? View.VISIBLE : View.GONE);
        useDefault.setOnClickListener(view -> useDefaultProfile.run());
        panel.addView(useDefault, top(dp(12)));

        discordIntegration = card("OPEN DISCORD  ›", dp(340), dp(56));
        discordIntegration.setVisibility(View.GONE);
        discordIntegration.setOnClickListener(view -> openDiscord.run());
        panel.addView(discordIntegration, top(dp(12)));

        vibepolloIntegration = card("VIBEPOLLO FIX  ›", dp(340), dp(56));
        vibepolloIntegration.setVisibility(View.GONE);
        vibepolloIntegration.setOnClickListener(view -> openVibepollo.run());
        panel.addView(vibepolloIntegration, top(dp(12)));

        virtualHereIntegration = card("VIRTUALHERE USB  ›", dp(340), dp(56));
        virtualHereIntegration.setVisibility(View.GONE);
        virtualHereIntegration.setOnClickListener(view -> openVirtualHere.run());
        panel.addView(virtualHereIntegration, top(dp(12)));

        TextView close = card("CLOSE", dp(340), dp(56));
        close.setOnClickListener(view -> hide());
        panel.addView(close, top(dp(12)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(720), matchHeight(), Gravity.RIGHT));
        showAndFocus(!summary.gatewayPaired ? pair : canUseDefault ? useDefault : close);
    }

    boolean updateHostIntegrations(String hostUuid, HostIntegrationSummary summary,
                                   IntegrationProfileCatalog catalog,
                                   Runnable chooseProfileAction) {
        if (layer.getVisibility() != View.VISIBLE || integrationHostUuid == null ||
                !integrationHostUuid.equals(hostUuid) || summary == null) return false;
        gatewayStatus.setText(summary.gatewayLabel());
        gatewayStatus.setTextColor(summary.gatewayPaired ? 0xFF69F0AE : 0xFFFFB74D);
        profileStatus.setText(summary.profileLabel());
        servicesStatus.setText(summary.servicesLabel());
        boolean canChoose = catalog != null && catalog.profiles.size() > 1;
        chooseProfile.setVisibility(canChoose ? View.VISIBLE : View.GONE);
        chooseProfile.setOnClickListener(canChoose ? view -> chooseProfileAction.run() : null);
        return true;
    }

    boolean updateHostIntegrationCapabilities(
            String hostUuid, HostGatewayClient.Capabilities capabilities) {
        if (layer.getVisibility() != View.VISIBLE || integrationHostUuid == null ||
                !integrationHostUuid.equals(hostUuid) || capabilities == null ||
                discordIntegration == null) return false;
        discordIntegration.setVisibility(capabilities.discord ? View.VISIBLE : View.GONE);
        vibepolloIntegration.setVisibility(capabilities.vibepolloFix ?
                View.VISIBLE : View.GONE);
        virtualHereIntegration.setVisibility(capabilities.virtualHere ?
                View.VISIBLE : View.GONE);
        return true;
    }

    void showProfileChooser(View focusToRestore, String hostUuid, String hostName,
                            IntegrationProfileCatalog catalog, String selectedProfileId,
                            Consumer<String> selectProfile, Runnable backAction,
                            Runnable dismissAction) {
        begin(focusToRestore);
        integrationHostUuid = hostUuid;
        onDismiss = dismissAction;
        LinearLayout panel = panel();
        panel.setPadding(dp(42), dp(38), dp(42), dp(34));
        panel.setBackgroundColor(0xFF111522);
        panel.addView(label("CHOOSE INTEGRATION PROFILE", 24, Color.WHITE, true), wrap());
        panel.addView(label(hostName, 15, 0xFFB99CFF, true), top(dp(8)));

        ScrollView profileScroll = new ScrollView(context);
        profileScroll.setVerticalScrollBarEnabled(false);
        LinearLayout profileList = new LinearLayout(context);
        profileList.setOrientation(LinearLayout.VERTICAL);
        profileList.setClipChildren(false);
        profileScroll.addView(profileList, new ScrollView.LayoutParams(
                matchWidth(), wrapSize()));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                matchWidth(), 0, 1f);
        scrollParams.topMargin = dp(18);
        panel.addView(profileScroll, scrollParams);

        View initialFocus = null;
        for (int index = 0; index < catalog.profiles.size(); index++) {
            IntegrationProfileStatus profile = catalog.profiles.get(index);
            boolean active = profile.id.equals(selectedProfileId);
            TextView profileAction = card(profile.name + (active ? "  ·  ACTIVE" : ""),
                    dp(420), dp(54));
            profileAction.setOnClickListener(view -> selectProfile.accept(profile.id));
            profileList.addView(profileAction, top(index == 0 ? 0 : dp(8)));
            if (initialFocus == null || active) initialFocus = profileAction;
        }
        TextView back = card("BACK", dp(420), dp(54));
        back.setOnClickListener(view -> backAction.run());
        panel.addView(back, top(dp(14)));
        TextView close = card("CLOSE", dp(420), dp(54));
        close.setOnClickListener(view -> hide());
        panel.addView(close, top(dp(8)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(720), matchHeight(), Gravity.RIGHT));
        showAndFocus(initialFocus != null ? initialFocus : back);
    }

    void hide() {
        layer.removeAllViews();
        layer.setVisibility(View.GONE);
        inputRouter.routeTo(InputRouter.Region.HOME);
        View restore = returnFocus;
        returnFocus = null;
        integrationHostUuid = null;
        gatewayStatus = null;
        profileStatus = null;
        servicesStatus = null;
        chooseProfile = null;
        discordIntegration = null;
        vibepolloIntegration = null;
        virtualHereIntegration = null;
        wakePanel = null;
        Runnable dismissAction = onDismiss;
        onDismiss = null;
        if (dismissAction != null) dismissAction.run();
        if (restore != null && restore.isShown()) restore.requestFocus();
    }

    private void begin(View focusToRestore) {
        boolean replacingVisiblePanel = layer.getVisibility() == View.VISIBLE;
        layer.removeAllViews();
        gatewayStatus = null;
        profileStatus = null;
        servicesStatus = null;
        chooseProfile = null;
        discordIntegration = null;
        vibepolloIntegration = null;
        virtualHereIntegration = null;
        wakePanel = null;
        onDismiss = null;
        if (!replacingVisiblePanel || returnFocus == null) returnFocus = focusToRestore;
        View dim = new View(context);
        dim.setBackgroundColor(0xA005060A);
        dim.setClickable(true);
        dim.setOnClickListener(view -> hide());
        layer.addView(dim, new FrameLayout.LayoutParams(matchWidth(), matchHeight()));
    }

    private void showAndFocus(View initialFocus) {
        layer.setVisibility(View.VISIBLE);
        inputRouter.routeTo(InputRouter.Region.MODAL);
        initialFocus.requestFocus();
    }

    void showWakePanel(View focusToRestore, String eyebrow, String title, String details,
                       Runnable backAction, View... actions) {
        begin(focusToRestore);
        integrationHostUuid = null;
        showWakePanel(eyebrow, title, details, backAction, actions);
    }

    private void showWakePanel(String eyebrow, String title, String details,
                               Runnable backAction, View... actions) {
        onDismiss = backAction;
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = new LinearLayout(context);
        wakePanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(34), dp(26), dp(34), dp(20));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xF51A1D2A, 0xF0221B38, 0xFA090B12});
        background.setCornerRadii(new float[]{dp(24), dp(24), 0, 0, 0, 0,
                dp(24), dp(24)});
        background.setStroke(dp(1), 0x707B6AA9);
        scroll.setBackground(background);
        scroll.setElevation(dp(18));
        scroll.addView(panel, new ScrollView.LayoutParams(matchWidth(), wrapSize()));

        panel.addView(label(eyebrow, 12, 0xFFAFA4C9, true), wrap());
        TextView titleView = label(title, 29, Color.WHITE, true);
        panel.addView(titleView, top(dp(10)));
        if (details != null && !details.isEmpty()) {
            TextView detailView = label(details, 14, 0xFFC1C5D6, false);
            detailView.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    matchWidth(), wrapSize());
            params.topMargin = dp(10);
            params.bottomMargin = dp(16);
            panel.addView(detailView, params);
        }
        View initial = null;
        for (View action : actions) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    matchWidth(), wrapSize());
            params.bottomMargin = dp(6);
            panel.addView(action, params);
            if (initial == null) initial = firstFocusable(action);
        }
        panel.addView(label(backAction != null ? "BACK  \u00B7  PREVIOUS" : "BACK  \u00B7  CLOSE",
                11, 0x8FFFFFFF, true), top(dp(12)));
        rebuildWakeFocusNavigation();
        layer.addView(scroll, new FrameLayout.LayoutParams(
                dp(510), matchHeight(), Gravity.END));
        showAndFocus(initial != null ? initial : titleView);
    }

    private View firstFocusable(View view) {
        if (view.isFocusable() && view.getVisibility() == View.VISIBLE) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View candidate = firstFocusable(group.getChildAt(index));
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    TextView wakeAction(String value) {
        TextView action = label(value, 14, 0xFFF0E9FF, true);
        action.setFocusable(true);
        action.setClickable(true);
        theme.prepareInteractiveView(action);
        action.setMinHeight(dp(44));
        action.setPadding(dp(16), dp(7), dp(16), dp(7));
        action.setOnFocusChangeListener((view, focused) -> styleWakeAction(action, focused));
        styleWakeAction(action, false);
        return action;
    }

    TextView wakeStatus(String value) {
        TextView status = label(value, 14, 0xFFC1C5D6, false);
        status.setLineSpacing(dp(3), 1f);
        status.setFocusable(false);
        status.setPadding(dp(4), dp(6), dp(4), dp(14));
        return status;
    }

    TextView wakeSection(String value) {
        TextView section = label(value, 11, 0xFFB5BAC1, true);
        section.setLetterSpacing(0.08f);
        section.setPadding(dp(4), dp(13), dp(4), dp(5));
        section.setFocusable(false);
        return section;
    }

    TextView discordAction(String value, int color) {
        TextView action = label(value, 14, Color.WHITE, true);
        action.setFocusable(true);
        action.setClickable(true);
        theme.prepareInteractiveView(action);
        action.setMinHeight(dp(48));
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setPadding(dp(16), dp(8), dp(16), dp(8));
        action.setOnFocusChangeListener((view, focused) ->
                styleDiscordAction(action, color, focused));
        styleDiscordAction(action, color, false);
        return action;
    }

    LinearLayout wakeActionRow(View... actions) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int index = 0; index < actions.length; index++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, wrapSize(), 1f);
            if (index > 0) params.leftMargin = dp(8);
            row.addView(actions[index], params);
        }
        return row;
    }

    LinearLayout wakeWeightedActionRow(View main, View secondary) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(main, new LinearLayout.LayoutParams(0, dp(44), 2f));
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
                0, wrapSize(), 1f);
        secondaryParams.leftMargin = dp(8);
        row.addView(secondary, secondaryParams);
        return row;
    }

    SeekBar wakeVolumeSlider(int progress) {
        SeekBar slider = new SeekBar(context);
        slider.setFocusable(true);
        slider.setMax(200);
        slider.setKeyProgressIncrement(10);
        slider.setProgress(Math.max(0, Math.min(200,
                Math.round(progress / 10f) * 10)));
        slider.setPadding(dp(14), dp(8), dp(14), dp(8));
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF8D7AD1));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFE9E3FF));
        return slider;
    }

    void rebuildWakeFocusNavigation() {
        if (wakePanel == null) return;
        List<List<View>> rows = new java.util.ArrayList<>();
        for (int index = 0; index < wakePanel.getChildCount(); index++) {
            View child = wakePanel.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            List<View> row = new java.util.ArrayList<>();
            collectFocusable(child, row);
            if (!row.isEmpty()) rows.add(row);
        }
        for (List<View> row : rows) {
            for (View action : row) {
                if (action.getId() == View.NO_ID) action.setId(View.generateViewId());
            }
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<View> row = rows.get(rowIndex);
            for (int column = 0; column < row.size(); column++) {
                View action = row.get(column);
                List<View> previousRow = rows.get(Math.max(0, rowIndex - 1));
                List<View> nextRow = rows.get(Math.min(rows.size() - 1, rowIndex + 1));
                action.setNextFocusUpId(previousRow.get(
                        Math.min(column, previousRow.size() - 1)).getId());
                action.setNextFocusDownId(nextRow.get(
                        Math.min(column, nextRow.size() - 1)).getId());
                action.setNextFocusLeftId(row.get(Math.max(0, column - 1)).getId());
                action.setNextFocusRightId(row.get(
                        Math.min(row.size() - 1, column + 1)).getId());
            }
        }
    }

    private static void collectFocusable(View view, List<View> result) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.isFocusable()) result.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            collectFocusable(group.getChildAt(index), result);
        }
    }

    private void styleWakeAction(View action, boolean focused) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{focused ? 0xC76C59A9 : 0x32584A76,
                        focused ? 0xB04C3C79 : 0x65372E5D});
        background.setCornerRadius(dp(10));
        background.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFE9E3FF : 0x387C89B2);
        action.setBackground(background);
    }

    private void styleDiscordAction(TextView action, int color, boolean focused) {
        int resting = blend(color, 0xFF111214, 0.30f);
        int top = focused ? color : resting;
        int bottom = blend(top, 0xFF111214, 0.20f);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(top, 0xF2), withAlpha(bottom, 0xF2)});
        background.setCornerRadius(dp(9));
        background.setStroke(dp(focused ? 2 : 1),
                focused ? Color.WHITE : 0x384F545C);
        action.setBackground(background);
        action.setTextColor(focused ? Color.WHITE : 0xFFE3E5E8);
        action.setElevation(dp(focused ? 8 : 2));
    }

    private static int blend(int from, int to, float amount) {
        float value = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(36), dp(30), dp(36), dp(30));
        panel.setBackground(theme.cardBackground());
        return panel;
    }

    private TextView card(String value, int width, int height) {
        TextView view = label(value, 15, Color.WHITE, true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        view.setFocusable(true);
        view.setClickable(true);
        theme.prepareInteractiveView(view);
        view.setMinWidth(width);
        view.setMinHeight(height);
        view.setBackground(theme.cardBackground());
        view.setOnFocusChangeListener(theme::onCardFocus);
        return view;
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setTypeface(android.graphics.Typeface.DEFAULT, bold ? 1 : 0);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(wrapSize(), wrapSize());
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = margin;
        return params;
    }

    private int dp(int value) { return Math.round(value * density); }
    private static int matchWidth() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int matchHeight() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrapSize() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
}
