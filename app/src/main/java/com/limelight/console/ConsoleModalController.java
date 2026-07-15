package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;

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
    private Runnable onDismiss;

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

    void showExitConfirmation(View focusToRestore, Runnable exitAction) {
        begin(focusToRestore);
        integrationHostUuid = null;
        LinearLayout panel = panel();
        panel.addView(label("EXIT MOONWAKER?", 24, Color.WHITE, true), wrap());
        panel.addView(label("An active host application will not be stopped.",
                15, 0xFFBDC4D8, false), top(dp(12)));
        TextView cancel = card("CANCEL", dp(260), dp(58));
        cancel.setOnClickListener(view -> hide());
        TextView exit = card("EXIT APP", dp(260), dp(58));
        exit.setOnClickListener(view -> exitAction.run());
        panel.addView(cancel, top(dp(22)));
        panel.addView(exit, top(dp(10)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(560), dp(360), Gravity.CENTER));
        showAndFocus(cancel);
    }

    void showSessionDetails(View focusToRestore, ConsoleSessionSummary summary,
                            Runnable returnToGame, Runnable disconnectTransport,
                            Runnable quitHostApplication) {
        begin(focusToRestore);
        integrationHostUuid = null;
        LinearLayout panel = panel();
        panel.setPadding(dp(42), dp(44), dp(42), dp(38));
        panel.setBackgroundColor(0xFF111522);
        panel.addView(label("ACTIVE SESSION", 26, Color.WHITE, true), wrap());
        panel.addView(label(summary.label, 16, 0xFF69F0AE, true), top(dp(18)));
        panel.addView(label("The stream transport remains connected while Console Home is open.",
                14, 0xFF9CA6C5, false), top(dp(14)));
        TextView resume = card("▶  RETURN TO GAME", dp(360), dp(58));
        resume.setOnClickListener(view -> {
            hide();
            returnToGame.run();
        });
        panel.addView(resume, top(dp(28)));
        TextView close = card("CLOSE", dp(360), dp(58));
        close.setOnClickListener(view -> hide());
        panel.addView(close, top(dp(10)));
        TextView disconnect = card("DISCONNECT STREAM", dp(360), dp(58));
        disconnect.setOnClickListener(view -> showSessionCommandConfirmation(
                view,
                "DISCONNECT STREAM?",
                "Streaming will stop. The host application will keep running.",
                "DISCONNECT",
                disconnectTransport));
        panel.addView(disconnect, top(dp(10)));
        TextView quit = card("QUIT HOST APPLICATION", dp(360), dp(58));
        quit.setOnClickListener(view -> showSessionCommandConfirmation(
                view,
                "QUIT HOST APPLICATION?",
                "The host application will be closed and streaming will disconnect.",
                "QUIT APPLICATION",
                quitHostApplication));
        panel.addView(quit, top(dp(10)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(720), matchHeight(), Gravity.RIGHT));
        showAndFocus(resume);
    }

    void showOptions(View focusToRestore, boolean uiSounds, boolean reducedMotion,
                     Runnable toggleSounds, Runnable toggleMotion,
                     Runnable hostIntegrations, Runnable moonlightSettings) {
        begin(focusToRestore);
        integrationHostUuid = null;
        LinearLayout panel = panel();
        panel.setPadding(dp(42), dp(48), dp(42), dp(38));
        panel.setBackgroundColor(0xFF111522);
        panel.addView(label("MOONWAKER GAME APP", 14, 0xFF9CA6C5, true), wrap());
        panel.addView(label("Options", 28, Color.WHITE, true), top(dp(8)));
        panel.addView(label(
                "Tune the console interface or open Moonlight's streaming preferences.",
                14, 0xFFBDC4D8, false), top(dp(14)));

        TextView sounds = card("UI SOUNDS  ·  " + (uiSounds ? "ON" : "OFF"),
                dp(420), dp(58));
        sounds.setOnClickListener(view -> toggleSounds.run());
        panel.addView(sounds, top(dp(28)));

        TextView motion = card("REDUCED MOTION  ·  " + (reducedMotion ? "ON" : "OFF"),
                dp(420), dp(58));
        motion.setOnClickListener(view -> toggleMotion.run());
        panel.addView(motion, top(dp(10)));

        TextView integrations = card("HOST INTEGRATIONS  ›", dp(420), dp(58));
        integrations.setOnClickListener(view -> hostIntegrations.run());
        panel.addView(integrations, top(dp(10)));

        TextView moonlight = card("MOONLIGHT SETTINGS  ›", dp(420), dp(58));
        moonlight.setOnClickListener(view -> moonlightSettings.run());
        panel.addView(moonlight, top(dp(10)));

        TextView close = card("CLOSE", dp(420), dp(58));
        close.setOnClickListener(view -> hide());
        panel.addView(close, top(dp(10)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(720), matchHeight(), Gravity.RIGHT));
        showAndFocus(sounds);
    }

    void showControllerActions(View focusToRestore,
                               ConsoleControllerRepository.Controller controller,
                               Runnable identify, Runnable disconnect, Runnable unpair) {
        begin(focusToRestore);
        integrationHostUuid = null;
        LinearLayout panel = panel();
        panel.setPadding(dp(42), dp(48), dp(42), dp(38));
        panel.setBackgroundColor(0xFF111522);
        panel.addView(label("CONTROLLER", 14, 0xFF9CA6C5, true), wrap());
        panel.addView(label(controller.name, 28, Color.WHITE, true), top(dp(8)));
        panel.addView(label(controller.batteryLabel(), 14, 0xFFBDC4D8, false), top(dp(12)));

        TextView identifyAction = card("IDENTIFY", dp(420), dp(58));
        identifyAction.setOnClickListener(view -> identify.run());
        panel.addView(identifyAction, top(dp(28)));
        TextView disconnectAction = card("POWER OFF / DISCONNECT", dp(420), dp(58));
        disconnectAction.setOnClickListener(view -> disconnect.run());
        panel.addView(disconnectAction, top(dp(10)));
        TextView unpairAction = card("UNPAIR CONTROLLER", dp(420), dp(58));
        unpairAction.setOnClickListener(view -> unpair.run());
        panel.addView(unpairAction, top(dp(10)));
        TextView close = card("CLOSE", dp(420), dp(58));
        close.setOnClickListener(view -> hide());
        panel.addView(close, top(dp(10)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(720), matchHeight(), Gravity.RIGHT));
        showAndFocus(identifyAction);
    }

    private void showSessionCommandConfirmation(View focusToRestore, String title,
                                                String message, String actionLabel,
                                                Runnable action) {
        begin(focusToRestore);
        integrationHostUuid = null;
        LinearLayout panel = panel();
        panel.addView(label(title, 24, Color.WHITE, true), wrap());
        panel.addView(label(message, 14, 0xFFBDC4D8, false), top(dp(14)));
        TextView cancel = card("CANCEL", dp(340), dp(58));
        cancel.setOnClickListener(view -> hide());
        panel.addView(cancel, top(dp(24)));
        TextView confirm = card(actionLabel, dp(340), dp(58));
        confirm.setOnClickListener(view -> {
            hide();
            action.run();
        });
        panel.addView(confirm, top(dp(10)));
        layer.addView(panel, new FrameLayout.LayoutParams(dp(660), dp(420), Gravity.CENTER));
        showAndFocus(cancel);
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
