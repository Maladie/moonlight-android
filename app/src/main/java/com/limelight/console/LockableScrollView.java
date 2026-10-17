package com.limelight.console;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * Scroll host that can stay visually fixed while one of its descendants owns scrolling, and can
 * pin a footer (the panel legend) inside the bottom padding where the content never scrolls.
 */
final class LockableScrollView extends ScrollView {
    private boolean scrollLocked;
    private View footer;
    private int footerBottom;

    LockableScrollView(Context context) { super(context); }
    LockableScrollView(Context context, AttributeSet attributes) { super(context, attributes); }

    /** {@code footer} is drawn {@code bottomMargin} above the bottom edge; null removes it. */
    void setFooter(View footer, int bottomMargin) {
        if (this.footer == footer && footerBottom == bottomMargin) return;
        this.footer = footer;
        footerBottom = bottomMargin;
        requestLayout();
        invalidate();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (footer == null) return;
        footer.measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        footer.layout(0, 0, footer.getMeasuredWidth(), footer.getMeasuredHeight());
    }

    @Override public void draw(Canvas canvas) {
        super.draw(canvas);
        if (footer == null) return;
        // The canvas follows the scroll offset; undo it so the footer stays put.
        canvas.save();
        canvas.translate(getScrollX(),
                getScrollY() + getHeight() - footerBottom - footer.getHeight());
        footer.draw(canvas);
        canvas.restore();
    }

    void setScrollLocked(boolean value) {
        scrollLocked = value;
        if (value) {
            // ScrollView exposes no public abortAnimation API. A zero-velocity fling stops its
            // internal scroller before the fixed Community shell is reset to the top.
            super.fling(0);
            super.scrollTo(0, 0);
        }
    }

    static boolean blocksOuterScroll(boolean locked) { return locked; }

    @Override public void scrollTo(int x, int y) {
        if (!scrollLocked) super.scrollTo(x, y);
    }

    @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle,
                                                            boolean immediate) {
        return scrollLocked || super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override public boolean arrowScroll(int direction) {
        return !scrollLocked && super.arrowScroll(direction);
    }

    @Override public boolean pageScroll(int direction) {
        return !scrollLocked && super.pageScroll(direction);
    }

    @Override public boolean fullScroll(int direction) {
        return !scrollLocked && super.fullScroll(direction);
    }

    @Override public void fling(int velocityY) {
        if (!scrollLocked) super.fling(velocityY);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return !scrollLocked && super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        return !scrollLocked && super.onTouchEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return !scrollLocked && super.onGenericMotionEvent(event);
    }
}
