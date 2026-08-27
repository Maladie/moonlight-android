package com.limelight.console;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.view.ViewGroup;

import java.util.List;

/** Small TV-friendly presentation helpers shared by the two Discord panels. */
final class DiscordPanelViews {
    private DiscordPanelViews() { }

    static LinearLayout twoColumnGrid(Context context, List<View> tiles, int gapPx) {
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int index = 0; index < tiles.size(); index += 2) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            View left = tiles.get(index);
            row.addView(left, tileParams(index + 1 < tiles.size() ? gapPx / 2 : 0, 0));
            if (index + 1 < tiles.size()) {
                View right = tiles.get(index + 1);
                row.addView(right, tileParams(0, gapPx / 2));
            } else {
                android.widget.Space spacer = new android.widget.Space(context);
                row.addView(spacer, tileParams(0, gapPx / 2));
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (index + 2 < tiles.size()) rowParams.bottomMargin = gapPx;
            grid.addView(row, rowParams);
        }
        bindGridNavigation(tiles);
        return grid;
    }

    static TextView tile(TextView tile, String tag) {
        tile.setId(View.generateViewId());
        if (tag != null) tile.setTag(tag);
        tile.setMinHeight((int) (54 * tile.getResources().getDisplayMetrics().density + .5f));
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setMaxLines(3);
        tile.setEllipsize(TextUtils.TruncateAt.END);
        tile.setContentDescription(tile.getText());
        tile.setActivated(true);
        styleTile(tile, false);
        return tile;
    }

    static <T extends View> T infoCard(T card) {
        int padding = (int) (12 * card.getResources().getDisplayMetrics().density + .5f);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0x641C2730, 0x4612171D});
        background.setCornerRadius((int) (12 * card.getResources().getDisplayMetrics().density + .5f));
        card.setBackground(background);
        card.setMinimumHeight((int) (52 * card.getResources().getDisplayMetrics().density + .5f));
        card.setPadding(padding, padding / 2, padding, padding / 2);
        return card;
    }

    static boolean isTile(View view) {
        return view.isActivated();
    }

    static void styleTile(View tile, boolean focused) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, focused
                ? new int[]{0xD8253948, 0xD416252F}
                : new int[]{0x1A1A242C, 0x1012161B});
        background.setCornerRadius((int) (10 * tile.getResources().getDisplayMetrics().density + .5f));
        if (focused) {
            background.setStroke((int) (tile.getResources().getDisplayMetrics().density + .5f),
                    0xFF8DDCFF);
        }
        tile.setBackground(background);
        tile.setElevation((int) ((focused ? 4 : 0) * tile.getResources().getDisplayMetrics().density + .5f));
    }

    static void styleActivity(View card, boolean focused) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, focused
                ? new int[]{0xC84E607E, 0xD01B2535}
                : new int[]{0x6641516D, 0xCC151925});
        background.setCornerRadius((int) (16 * card.getResources().getDisplayMetrics().density + .5f));
        background.setStroke((int) ((focused ? 2 : 1) * card.getResources().getDisplayMetrics().density + .5f),
                focused ? 0xFFF7F8FF : 0x1FFFFFFF);
        card.setBackground(background);
        card.setElevation((int) ((focused ? 6 : 0) * card.getResources().getDisplayMetrics().density + .5f));
    }

    static LinearLayout destination(Context context, String tag, String title, String subtitle,
                                    String avatarUrl, boolean active) {
        LinearLayout row = new LinearLayout(context);
        row.setId(View.generateViewId());
        row.setTag(tag);
        row.setActivated(true);
        row.setFocusable(true);
        row.setClickable(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dp(context, 10);
        row.setPadding(padding, padding / 2, padding, padding / 2);
        row.setMinimumHeight(dp(context, 66));
        row.setContentDescription(subtitle.isEmpty() ? title : title + ". " + subtitle);
        row.setOnFocusChangeListener((view, focused) -> {
            styleTile(row, focused);
            if (focused) row.post(() -> row.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight())));
        });
        styleTile(row, false);

        android.widget.FrameLayout avatar = DiscordCommunityPresentation.avatar(context, title,
                avatarUrl, 44);
        if (active) {
            View dot = new View(context);
            GradientDrawable dotBackground = new GradientDrawable();
            dotBackground.setShape(GradientDrawable.OVAL);
            dotBackground.setColor(0xFF61E594);
            dot.setBackground(dotBackground);
            android.widget.FrameLayout.LayoutParams dotParams = new android.widget.FrameLayout.LayoutParams(
                    dp(context, 10), dp(context, 10), Gravity.RIGHT | Gravity.BOTTOM);
            dotParams.rightMargin = dp(context, 1);
            dotParams.bottomMargin = dp(context, 1);
            avatar.addView(dot, dotParams);
        }
        row.addView(avatar, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextSize(15);
        heading.setTextColor(Color.WHITE);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(heading);
        if (!subtitle.isEmpty()) {
            TextView meta = new TextView(context);
            meta.setText(subtitle);
            meta.setTextSize(13);
            meta.setTextColor(0xFF9FA7B8);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(meta);
        }
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.leftMargin = dp(context, 11);
        row.addView(copy, copyParams);
        return row;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int rowCount(int itemCount) {
        return Math.max(0, (itemCount + 1) / 2);
    }

    static boolean offlineVisible(boolean expanded) {
        return expanded;
    }

    static int previousRowIndex(int index) {
        return index >= 2 ? index - 2 : -1;
    }

    static int nextRowIndex(int index, int itemCount) {
        return index + 2 < itemCount ? index + 2 : -1;
    }

    private static LinearLayout.LayoutParams tileParams(int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = leftMargin;
        params.rightMargin = rightMargin;
        return params;
    }

    private static void bindGridNavigation(List<View> tiles) {
        for (int index = 0; index < tiles.size(); index++) {
            View tile = tiles.get(index);
            int previous = previousRowIndex(index);
            int next = nextRowIndex(index, tiles.size());
            if (previous >= 0) tile.setNextFocusUpId(tiles.get(previous).getId());
            if (next >= 0) tile.setNextFocusDownId(tiles.get(next).getId());
            if ((index & 1) == 0 && index + 1 < tiles.size()) {
                tile.setNextFocusRightId(tiles.get(index + 1).getId());
            } else if ((index & 1) == 1) {
                tile.setNextFocusLeftId(tiles.get(index - 1).getId());
            }
        }
    }
}
