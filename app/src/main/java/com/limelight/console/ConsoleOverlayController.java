package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Wake-style controller overlay shell rendered above the persistent stream surface. */
final class ConsoleOverlayController {
    private final Context context;
    private final ConsoleTheme theme;
    private final float density;
    private TextView integrationStatus;

    ConsoleOverlayController(Context context, ConsoleTheme theme) {
        this.context = context;
        this.theme = theme;
        density = context.getResources().getDisplayMetrics().density;
    }

    View build(Runnable returnToGame, Runnable openHome, Runnable openIntegrations) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xB0090D18);
        root.setVisibility(View.GONE);

        LinearLayout controls = panel();
        controls.addView(label("STREAM CONTROLS", 22, Color.WHITE, true), wrap());
        controls.addView(label("MoonWaker keeps the current transport connected",
                13, 0xFF9CA6C5, false), top(dp(8)));
        TextView game = action("▶  RETURN TO GAME");
        game.setId(View.generateViewId());
        game.setContentDescription("Return to game");
        game.setOnClickListener(view -> returnToGame.run());
        controls.addView(game, top(dp(22)));
        TextView home = action("⌂  CONSOLE HOME");
        home.setId(View.generateViewId());
        home.setContentDescription("Open console Home");
        home.setOnClickListener(view -> openHome.run());
        controls.addView(home, top(dp(10)));
        root.addView(controls, anchored(dp(480), dp(330), Gravity.LEFT | Gravity.BOTTOM));

        LinearLayout discord = panel();
        discord.addView(label("DISCORD & HOST SERVICES", 18, Color.WHITE, true), wrap());
        integrationStatus = label("Select a host to inspect its integration profile",
                13, 0xFF9CA6C5, false);
        discord.addView(integrationStatus, top(dp(8)));
        TextView integrations = action("OPEN HOST INTEGRATIONS  ›");
        integrations.setId(View.generateViewId());
        integrations.setContentDescription("Open Discord and host integrations");
        integrations.setOnClickListener(view -> openIntegrations.run());
        discord.addView(integrations, top(dp(22)));
        root.addView(discord, anchored(dp(480), dp(250), Gravity.RIGHT | Gravity.TOP));

        game.setNextFocusDownId(home.getId());
        game.setNextFocusRightId(integrations.getId());
        home.setNextFocusUpId(game.getId());
        home.setNextFocusRightId(integrations.getId());
        integrations.setNextFocusLeftId(game.getId());
        integrations.setNextFocusDownId(home.getId());

        root.setTag(game);
        return root;
    }

    void render(HostIntegrationSummary summary) {
        if (integrationStatus == null) return;
        if (summary == null) {
            integrationStatus.setText("Select a host to inspect its integration profile");
            integrationStatus.setTextColor(0xFF9CA6C5);
            return;
        }
        integrationStatus.setText(summary.profileLabel() + "\n" + summary.servicesLabel());
        integrationStatus.setTextColor(summary.gatewayPaired ? 0xFF69F0AE : 0xFFFFB74D);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(24), dp(28), dp(24));
        panel.setBackground(theme.cardBackground());
        return panel;
    }

    private TextView action(String text) {
        TextView view = label(text, 15, Color.WHITE, true);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(18), dp(10), dp(18), dp(10));
        view.setMinWidth(dp(360));
        view.setMinHeight(dp(58));
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(theme.cardBackground());
        view.setOnFocusChangeListener(theme::onCardFocus);
        return view;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.DEFAULT, bold ? 1 : 0);
        return view;
    }

    private FrameLayout.LayoutParams anchored(int width, int height, int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        params.setMargins(dp(42), dp(42), dp(42), dp(42));
        return params;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = margin;
        return params;
    }

    private int dp(int value) { return Math.round(value * density); }
}
