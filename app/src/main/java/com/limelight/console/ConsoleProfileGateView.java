package com.limelight.console;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.BuildConfig;
import com.limelight.R;
import com.limelight.ui.ControllerGlyphs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-screen profile chooser shown between host selection and the host home. */
final class ConsoleProfileGateView extends FrameLayout {
    interface Listener {
        void onProfileSelected(String profileId);
        void onAutomaticProfileToggled(String profileId);
    }

    private final List<View> profileTiles = new ArrayList<>();
    private TextView clock;
    private LinearLayout legend;
    private boolean playStationButtons;
    private boolean controllerPresent;
    private boolean reducedMotion;
    private HostGatewayClient.IntegrationProfile focusedProfile;

    ConsoleProfileGateView(Context context) {
        super(context);
        setVisibility(GONE);
        setClipChildren(false);
        setClipToPadding(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        // Keep the gate visually part of the same world as host selection.
        setBackground(new ConsoleActivity.HostSelectionBackdropDrawable());
    }

    void setClockText(CharSequence value) {
        if (clock != null) clock.setText(value == null ? "" : value);
    }

    void setControllerState(boolean playStationButtons, boolean controllerPresent) {
        if (this.playStationButtons == playStationButtons
                && this.controllerPresent == controllerPresent) return;
        this.playStationButtons = playStationButtons;
        this.controllerPresent = controllerPresent;
        updateLegend();
    }

    void setReducedMotion(boolean reducedMotion) {
        this.reducedMotion = reducedMotion;
    }

    void show(String hostName, List<HostGatewayClient.IntegrationProfile> profiles,
              String automaticProfileId, Listener listener) {
        removeAllViews();
        profileTiles.clear();
        focusedProfile = null;

        clock = label("", 11, 0xFFDDE3EB, false);
        clock.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(
                dp(150), dp(40), Gravity.TOP | Gravity.END);
        clockParams.topMargin = dp(28);
        clockParams.rightMargin = dp(38);
        addView(clock, clockParams);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(34), dp(14), dp(34), dp(8));
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420), Gravity.CENTER);
        contentParams.leftMargin = dp(34);
        contentParams.rightMargin = dp(34);
        addView(content, contentParams);

        TextView eyebrow = label(safeName(hostName).toUpperCase(Locale.ROOT),
                11, 0xFF8EA7C5, true);
        eyebrow.setGravity(Gravity.CENTER);
        content.addView(eyebrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        TextView title = label(getContext().getString(R.string.console_profile_gate_title),
                25, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        titleParams.topMargin = dp(2);
        content.addView(title, titleParams);

        TextView subtitle = label(
                getContext().getString(R.string.console_profile_gate_subtitle),
                14, 0xFFB8C0CD, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        subtitleParams.bottomMargin = dp(8);
        content.addView(subtitle, subtitleParams);

        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(24), dp(4), dp(24), dp(4));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(224));
        content.addView(scroll, rowParams);

        View first = null;
        if (profiles != null) {
            for (HostGatewayClient.IntegrationProfile profile : profiles) {
                if (profile == null) continue;
                String profileId = safeId(profile.id);
                LinearLayout tile = profileTile(profile,
                        profileId.equals(safeId(automaticProfileId)), scroll, listener,
                        profiles);
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                        dp(176), dp(204));
                tileParams.leftMargin = dp(8);
                tileParams.rightMargin = dp(8);
                row.addView(tile, tileParams);
                profileTiles.add(tile);
                if (first == null) first = tile;
            }
        }
        wireTileFocus();

        TextView footerHint = label(getContext().getString(R.string.console_profile_gate_hint),
                10, 0xFF9EADBD, false);
        footerHint.setGravity(Gravity.CENTER);
        footerHint.setMaxLines(1);
        footerHint.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(footerHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        legend = new LinearLayout(getContext());
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(dp(9), dp(5), dp(9), dp(5));
        legend.setBackground(legendBackground());
        FrameLayout.LayoutParams legendParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        legendParams.rightMargin = dp(38);
        legendParams.bottomMargin = dp(20);
        addView(legend, legendParams);

        TextView version = label("v" + BuildConfig.VERSION_NAME, 8, 0x809DA8B6, false);
        FrameLayout.LayoutParams versionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        versionParams.leftMargin = dp(20);
        versionParams.bottomMargin = dp(14);
        addView(version, versionParams);

        setVisibility(VISIBLE);
        updateLegend();
        if (first != null) first.post(first::requestFocus);
    }

    private LinearLayout profileTile(HostGatewayClient.IntegrationProfile profile,
                                     boolean automatic, HorizontalScrollView scroll,
                                     Listener listener,
                                     List<HostGatewayClient.IntegrationProfile> profiles) {
        String profileId = safeId(profile.id);
        String name = safeName(profile.name);
        LinearLayout tile = new LinearLayout(getContext());
        tile.setId(View.generateViewId());
        tile.setTag("profile.select:" + profileId);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(5), dp(6), dp(5), dp(4));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setSoundEffectsEnabled(false);
        tile.setClipChildren(false);
        tile.setClipToPadding(false);

        FrameLayout avatar = new FrameLayout(getContext());
        ImageView artwork = new ImageView(getContext());
        artwork.setImageDrawable(new ConsoleActivity.HostAvatarDrawable(profileId));
        if (profile.isChild() && ConsoleChildProfileEditor.isKnownAvatar(profile.avatarId)
                && !"default".equals(profile.avatarId)) {
            artwork.setColorFilter(ConsoleChildProfileEditor.avatarColor(profile.avatarId),
                    android.graphics.PorterDuff.Mode.SRC_ATOP);
        }
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setAlpha(.92f);
        avatar.addView(artwork, new FrameLayout.LayoutParams(
                dp(92), dp(92), Gravity.CENTER));
        TextView initials = label(initials(name), 16, Color.WHITE, true);
        initials.setGravity(Gravity.CENTER);
        initials.setShadowLayer(dp(3), 0f, dp(1), 0xE0000000);
        avatar.addView(initials, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        styleAvatar(avatar, false);
        tile.addView(avatar, new LinearLayout.LayoutParams(dp(100), dp(100)));

        TextView nameView = label(name.toUpperCase(Locale.ROOT), 14, Color.WHITE, true);
        nameView.setGravity(Gravity.CENTER);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        nameParams.topMargin = dp(3);
        tile.addView(nameView, nameParams);

        LinearLayout badges = new LinearLayout(getContext());
        badges.setOrientation(LinearLayout.VERTICAL);
        badges.setGravity(Gravity.CENTER_HORIZONTAL);
        if (profile.isChild()) {
            String parentName = parentName(profile, profiles);
            addBadge(badges, parentName.isEmpty()
                            ? getContext().getString(R.string.console_profile_gate_child)
                            : getContext().getString(
                            R.string.console_profile_gate_child_of, parentName),
                    0xFFB9A5FF, 0x403E2D6C);
            if (profile.remainingDailySeconds > 0L) {
                addBadge(badges, getContext().getString(
                                R.string.console_profile_gate_remaining,
                                remainingMinutes(profile.remainingDailySeconds)),
                        0xFF73D7FF, 0x40305F76);
            }
        }
        if ("active".equals(profile.sessionState)) {
            addBadge(badges, getContext().getString(R.string.console_profile_gate_active),
                    0xFF69F0AE, 0x40206C49);
        }
        if (profile.pinRequired) {
            addBadge(badges, getContext().getString(R.string.console_profile_gate_pin),
                    0xFFFFC857, 0x405E4A18);
        }
        if (automatic) {
            addBadge(badges, getContext().getString(R.string.console_profile_gate_automatic),
                    0xFF73D7FF, 0x40305F76);
        }
        tile.addView(badges, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        String status = profileStatus(profile, automatic, profiles);
        tile.setContentDescription(getContext().getString(
                R.string.console_profile_gate_tile_description, name, status));
        styleTile(tile, false);
        tile.setOnClickListener(view -> listener.onProfileSelected(profileId));
        tile.setOnFocusChangeListener((view, focused) -> {
            styleTile(tile, focused);
            styleAvatar(avatar, focused);
            focusedProfile = focused ? profile : focusedProfile == profile ? null : focusedProfile;
            updateLegend();
            animateScale(tile, focused ? 1.07f : 1f);
            if (focused) scrollToFocused(scroll, tile);
        });
        tile.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_BUTTON_START)) {
                // Consume Options on protected profiles so no lower layer can act on it.
                if (!profile.pinRequired && !profile.isChild()) {
                    listener.onAutomaticProfileToggled(profileId);
                }
                return true;
            }
            return false;
        });
        return tile;
    }

    private void wireTileFocus() {
        for (int index = 0; index < profileTiles.size(); index++) {
            View tile = profileTiles.get(index);
            View left = profileTiles.get(Math.max(0, index - 1));
            View right = profileTiles.get(Math.min(profileTiles.size() - 1, index + 1));
            tile.setNextFocusLeftId(left.getId());
            tile.setNextFocusRightId(right.getId());
            tile.setNextFocusUpId(tile.getId());
            tile.setNextFocusDownId(tile.getId());
        }
    }

    private void scrollToFocused(HorizontalScrollView scroll, View tile) {
        tile.post(() -> scroll.smoothScrollTo(
                Math.max(0, tile.getLeft() - dp(92)), 0));
    }

    private void animateScale(View view, float scale) {
        view.animate().cancel();
        if (reducedMotion || !view.isLaidOut()) {
            view.setScaleX(scale);
            view.setScaleY(scale);
        } else {
            view.animate().scaleX(scale).scaleY(scale)
                    .setDuration(getResources().getInteger(R.integer.console_motion_focus_ms))
                    .start();
        }
    }

    private void addBadge(LinearLayout parent, String text, int foreground, int backgroundColor) {
        TextView badge = label(text, 10, foreground, true);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setPadding(dp(8), 0, dp(8), 0);
        badge.setBackground(badgeBackground(backgroundColor, foreground));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(20));
        params.bottomMargin = dp(2);
        parent.addView(badge, params);
    }

    private void styleTile(View tile, boolean focused) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                focused ? new int[]{0xE0263D55, 0xD5192838}
                        : new int[]{0x5C182230, 0x35101721});
        background.setCornerRadius(dp(16));
        background.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFF2F6FA : 0x425E7189);
        tile.setBackground(background);
        tile.setElevation(dp(focused ? 8 : 1));
    }

    private void styleAvatar(View avatar, boolean focused) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(focused ? 0x341E3146 : 0x24101820);
        ring.setStroke(dp(focused ? 2 : 1),
                focused ? 0xFFF2F6FA : 0x507C8792);
        avatar.setBackground(ring);
        avatar.setElevation(dp(focused ? 7 : 1));
    }

    private GradientDrawable badgeBackground(int color, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(10));
        background.setColor(color);
        background.setStroke(dp(1), (stroke & 0x00FFFFFF) | 0xA0000000);
        return background;
    }

    private GradientDrawable legendBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xE61A242B, 0xF20C1116});
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), 0x4D6E8291);
        return background;
    }

    private void updateLegend() {
        if (legend == null) return;
        legend.removeAllViews();
        addLegendItem(controllerPresent
                        ? ControllerGlyphs.text(playStationButtons, ControllerGlyphs.Button.CONFIRM)
                        : "OK",
                getContext().getString(R.string.console_select_hint), controllerPresent);
        addLegendItem(controllerPresent
                        ? ControllerGlyphs.text(playStationButtons, ControllerGlyphs.Button.CANCEL)
                        : "↩",
                getContext().getString(R.string.console_profile_gate_back), controllerPresent);
        if (focusedProfile != null && !focusedProfile.pinRequired
                && !focusedProfile.isChild()) {
            addLegendItem(controllerPresent
                            ? ControllerGlyphs.text(playStationButtons, ControllerGlyphs.Button.MENU)
                            : "≡",
                    getContext().getString(R.string.console_profile_gate_options), controllerPresent);
        }
    }

    private void addLegendItem(String glyph, String label, boolean controllerGlyph) {
        if (legend.getChildCount() > 0) {
            View separator = new View(getContext());
            separator.setBackgroundColor(0x336E8291);
            LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(
                    dp(1), dp(18));
            separatorParams.setMargins(dp(9), 0, dp(9), 0);
            legend.addView(separator, separatorParams);
        }
        TextView button = label(glyph, controllerGlyph ? 20 : 12, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        if (controllerGlyph) {
            button.setTypeface(ControllerGlyphs.typeface(getContext()));
        } else {
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xFF26343E);
            background.setStroke(dp(1), 0xBFE8F1F5);
            button.setBackground(background);
        }
        legend.addView(button, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView description = label(label, 11, 0xFFD7E4EA, true);
        description.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        descriptionParams.leftMargin = dp(4);
        legend.addView(description, descriptionParams);
    }

    private String profileStatus(HostGatewayClient.IntegrationProfile profile,
                                 boolean automatic,
                                 List<HostGatewayClient.IntegrationProfile> profiles) {
        StringBuilder status = new StringBuilder();
        if (profile.isChild()) {
            String parentName = parentName(profile, profiles);
            appendStatus(status, parentName.isEmpty()
                    ? getContext().getString(R.string.console_profile_gate_child)
                    : getContext().getString(R.string.console_profile_gate_child_of, parentName));
            if (profile.remainingDailySeconds > 0L) appendStatus(status,
                    getContext().getString(R.string.console_profile_gate_remaining,
                            remainingMinutes(profile.remainingDailySeconds)));
        }
        if ("active".equals(profile.sessionState)) {
            status.append(getContext().getString(R.string.console_profile_gate_active));
        }
        if (profile.pinRequired) appendStatus(status,
                getContext().getString(R.string.console_profile_gate_pin));
        if (automatic) appendStatus(status,
                getContext().getString(R.string.console_profile_gate_automatic));
        return status.toString();
    }

    private void appendStatus(StringBuilder status, String value) {
        if (status.length() > 0) status.append(" · ");
        status.append(value);
    }

    private String parentName(HostGatewayClient.IntegrationProfile profile,
                              List<HostGatewayClient.IntegrationProfile> profiles) {
        if (profile == null || profile.parentProfileId == null
                || profile.parentProfileId.isEmpty() || profiles == null) return "";
        for (HostGatewayClient.IntegrationProfile candidate : profiles) {
            if (candidate != null && profile.parentProfileId.equals(candidate.id)) {
                String value = candidate.name == null ? "" : candidate.name.trim();
                return value.isEmpty() ? "" : value;
            }
        }
        return "";
    }

    private int remainingMinutes(long seconds) {
        return (int) Math.max(1L, (seconds + 59L) / 60L);
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
        if (name.isEmpty()) return "MW";
        String[] words = name.trim().split("\\s+");
        if (words.length > 1) {
            return (words[0].substring(0, 1)
                    + words[words.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
        }
        return words[0].substring(0, Math.min(2, words[0].length()))
                .toUpperCase(Locale.ROOT);
    }

    private String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "MoonWaker" : name.trim();
    }

    private String safeId(String id) {
        return id == null ? "" : id;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
