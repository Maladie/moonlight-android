package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.util.List;
import java.util.Locale;

final class ConsoleProfileGateView extends FrameLayout {
    interface Listener {
        void onProfileSelected(String profileId);
        void onAutomaticProfileToggled(String profileId);
    }

    ConsoleProfileGateView(Context context) {
        super(context);
        setVisibility(GONE);
        setBackgroundColor(0xFF0B1019);
    }

    void show(String hostName, List<HostGatewayClient.IntegrationProfile> profiles,
              String automaticProfileId, Listener listener) {
        removeAllViews();

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(44), dp(34), dp(44), dp(24));
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView eyebrow = label(hostName == null ? "" : hostName.toUpperCase(Locale.ROOT),
                11, 0xFF8EA7C5, true);
        eyebrow.setGravity(Gravity.CENTER);
        content.addView(eyebrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        TextView title = label(getContext().getString(R.string.console_profile_gate_title),
                25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        titleParams.bottomMargin = dp(24);
        content.addView(title, titleParams);

        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View first = null;
        for (HostGatewayClient.IntegrationProfile profile : profiles) {
            LinearLayout tile = profileTile(profile,
                    profile.id.equals(automaticProfileId), scroll, listener);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(170), dp(190));
            tileParams.leftMargin = dp(12);
            tileParams.rightMargin = dp(12);
            row.addView(tile, tileParams);
            if (first == null) first = tile;
        }

        TextView legend = label(getContext().getString(R.string.console_profile_gate_legend),
                10, 0xFFD3DAE5, false);
        legend.setGravity(Gravity.CENTER);
        content.addView(legend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        setVisibility(VISIBLE);
        if (first != null) first.post(first::requestFocus);
    }

    private LinearLayout profileTile(HostGatewayClient.IntegrationProfile profile,
                                     boolean automatic, HorizontalScrollView scroll,
                                     Listener listener) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setId(View.generateViewId());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(12), dp(14), dp(12), dp(10));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setSoundEffectsEnabled(false);

        TextView avatar = label(initials(profile.name), 26, Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(circle(profile.id));
        tile.addView(avatar, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView name = label(profile.name.toUpperCase(Locale.ROOT), 13, Color.WHITE, true);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        nameParams.topMargin = dp(8);
        tile.addView(name, nameParams);

        String state = "active".equals(profile.sessionState)
                ? getContext().getString(R.string.console_profile_gate_active) : "";
        if (profile.pinRequired) {
            state = state.isEmpty()
                    ? getContext().getString(R.string.console_profile_gate_pin)
                    : state + " · " + getContext().getString(
                    R.string.console_profile_gate_pin);
        }
        if (automatic) {
            state = state.isEmpty()
                    ? getContext().getString(R.string.console_profile_gate_automatic)
                    : state + " · " + getContext().getString(
                    R.string.console_profile_gate_automatic);
        }
        TextView status = label(state, 9, automatic ? 0xFF73D7FF : 0xFFFFC857, false);
        status.setGravity(Gravity.CENTER);
        tile.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        styleTile(tile, false);
        tile.setOnClickListener(view -> listener.onProfileSelected(profile.id));
        tile.setOnFocusChangeListener((view, focused) -> {
            styleTile(tile, focused);
            tile.animate().scaleX(focused ? 1.06f : 1f)
                    .scaleY(focused ? 1.06f : 1f).setDuration(120).start();
            if (focused) scroll.smoothScrollTo(Math.max(0, tile.getLeft() - dp(80)), 0);
        });
        tile.setOnKeyListener((view, keyCode, event) -> {
            if (!profile.pinRequired && event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_BUTTON_START)) {
                listener.onAutomaticProfileToggled(profile.id);
                return true;
            }
            return false;
        });
        return tile;
    }

    private void styleTile(View tile, boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(18));
        background.setColor(focused ? 0xFF18263A : 0xB0141C28);
        background.setStroke(dp(focused ? 3 : 1),
                focused ? 0xFFF4F8FF : 0x405E7189);
        tile.setBackground(background);
        tile.setElevation(dp(focused ? 10 : 2));
    }

    private GradientDrawable circle(String seed) {
        int hash = seed == null ? 0 : seed.hashCode();
        int red = 70 + Math.abs(hash) % 55;
        int green = 85 + Math.abs(hash / 31) % 65;
        int blue = 125 + Math.abs(hash / 997) % 75;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(Color.rgb(red, green, blue));
        drawable.setStroke(dp(2), 0x90FFFFFF);
        return drawable;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private String initials(String name) {
        if (name == null || name.trim().isEmpty()) return "MW";
        String[] words = name.trim().split("\\s+");
        if (words.length > 1) {
            return (words[0].substring(0, 1)
                    + words[words.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        return words[0].substring(0, Math.min(2, words[0].length()))
                .toUpperCase(Locale.ROOT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
