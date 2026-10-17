package com.limelight.console;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Recycling grid of the expanded Playnite library. It owns the card window, D-pad stepping,
 * focus restore and the artwork prefetch range; ConsoleActivity supplies and binds the cards.
 */
final class ExpandedLibraryGrid extends RecyclerView {
    private static final int FULL_BIND = PlayniteLibraryDiff.TEXT
            | PlayniteLibraryDiff.ARTWORK | PlayniteLibraryDiff.LAUNCH;
    private static final long NAVIGATION_INTERVAL_MS = 70L;
    private static final int POOL_ROWS = 3;
    private static final int CELL_WIDTH_DP = 92;
    private static final int CELL_HEIGHT_DP = 123;
    private static final int CELL_MARGIN_DP = 4;
    private static final int SIDE_PADDING_DP = 5;
    static final int CELL_PITCH_DP = CELL_WIDTH_DP + CELL_MARGIN_DP * 2;

    interface Callbacks {
        /** Creates an unbound card; bindCard follows. */
        View createCard();

        void bindCard(View card, PlayniteDashboardItem item, int payload);

        /** Drops the card's bitmaps; the bitmap LRU stays their only owner. */
        void releaseCard(View card);

        /** PlayniteLibraryDiff payload for rebinding {@code next}, or -1 when the card is current. */
        int changePayload(PlayniteDashboardItem previous, PlayniteDashboardItem next);

        /** Brackets a synchronous layout or scroll, during which several cards may bind. */
        void bindBatch(boolean active);

        void onItemFocused(int position, int count);

        /** Focus leaves the grid upward or to the left. */
        void onLeave();

        void prefetchArtwork(List<PlayniteDashboardItem> items);

        boolean reducedMotion();
    }

    private final int columns;
    private final Callbacks callbacks;
    private final GridLayoutManager grid;
    private final CardAdapter adapter = new CardAdapter();
    private final float density;
    private List<PlayniteDashboardItem> items = Collections.emptyList();
    private int pendingFocus = NO_POSITION;
    private int lastFocused = NO_POSITION;
    private long lastNavigationAt;
    private int scrollDirection = 1;
    private int prefetchStart = -1;
    private int prefetchEnd = -1;
    private boolean poolWarmStarted;
    private boolean artworkReleased;
    private boolean updatingCardsInPlace;
    private boolean cardLayoutAbsorbed;

    ExpandedLibraryGrid(Context context, int columns, Callbacks callbacks) {
        super(context);
        this.columns = Math.max(1, columns);
        this.callbacks = callbacks;
        density = context.getResources().getDisplayMetrics().density;
        int rowHeight = px(CELL_HEIGHT_DP + CELL_MARGIN_DP * 2);
        grid = new GridLayoutManager(context, this.columns) {
            @Override
            protected void calculateExtraLayoutSpace(@NonNull State state,
                                                     @NonNull int[] extraLayoutSpace) {
                // Keep one bound row beyond each edge so a D-pad step always has a target.
                extraLayoutSpace[0] = rowHeight;
                extraLayoutSpace[1] = rowHeight;
            }

            @Override
            public void onLayoutChildren(Recycler recycler, State state) {
                ExpandedLibraryGrid.this.callbacks.bindBatch(true);
                try {
                    super.onLayoutChildren(recycler, state);
                } finally {
                    ExpandedLibraryGrid.this.callbacks.bindBatch(false);
                }
            }

            @Override
            public int scrollVerticallyBy(int dy, Recycler recycler, State state) {
                ExpandedLibraryGrid.this.callbacks.bindBatch(true);
                try {
                    return super.scrollVerticallyBy(dy, recycler, state);
                } finally {
                    ExpandedLibraryGrid.this.callbacks.bindBatch(false);
                }
            }

            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent,
                    @NonNull View child, @NonNull Rect rect, boolean immediate,
                    boolean focusedChildVisible) {
                return super.requestChildRectangleOnScreen(parent, child, rect,
                        immediate || ExpandedLibraryGrid.this.callbacks.reducedMotion(),
                        focusedChildVisible);
            }

            @Override
            public void onLayoutCompleted(State state) {
                super.onLayoutCompleted(state);
                requestPrefetch();
                if (pendingFocus != NO_POSITION) {
                    ExpandedLibraryGrid.this.post(ExpandedLibraryGrid.this::applyPendingFocus);
                }
                if (!poolWarmStarted && getChildCount() > 0) {
                    poolWarmStarted = true;
                    ExpandedLibraryGrid.this.postOnAnimation(ExpandedLibraryGrid.this::warmPool);
                }
            }
        };
        // No idle-time prefetch: it binds cards one at a time outside a bind batch, so each
        // pays for its own session snapshot. The extra layout row already binds one row ahead.
        grid.setItemPrefetchEnabled(false);
        setLayoutManager(grid);
        // Cards have a fixed size, so adapter changes never need to re-measure the grid.
        setHasFixedSize(true);
        setItemAnimator(null);
        // Two rows stay bound behind the scroll; the pool feeds the rows ahead.
        setItemViewCacheSize(this.columns * 2);
        getRecycledViewPool().setMaxRecycledViews(0, this.columns * POOL_ROWS);
        setClipChildren(false);
        setClipToPadding(false);
        setVerticalScrollBarEnabled(false);
        setPadding(px(SIDE_PADDING_DP), px(14), px(SIDE_PADDING_DP), px(14));
        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy != 0) scrollDirection = Integer.signum(dy);
                requestPrefetch();
            }
        });
    }

    /** Width that fits exactly {@code columns} fixed-size cells plus the side padding. */
    int preferredWidth() {
        return px(columns * CELL_PITCH_DP + SIDE_PADDING_DP * 2);
    }

    void setItems(List<PlayniteDashboardItem> next) {
        List<PlayniteDashboardItem> previous = items;
        int focused = focusedPosition();
        items = Collections.unmodifiableList(new ArrayList<>(next));
        prefetchStart = -1;
        prefetchEnd = -1;
        if (getAdapter() == null) {
            setAdapter(adapter);
            return;
        }
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }

            @Override public int getNewListSize() { return items.size(); }

            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).stableId()
                        .equals(items.get(newPosition).stableId());
            }

            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return callbacks.changePayload(previous.get(oldPosition),
                        items.get(newPosition)) < 0;
            }

            @Override public Object getChangePayload(int oldPosition, int newPosition) {
                return Math.max(0, callbacks.changePayload(previous.get(oldPosition),
                        items.get(newPosition)));
            }
        }, false).dispatchUpdatesTo(adapter);
        if (focused != NO_POSITION && focusedPosition() == NO_POSITION) {
            // The diff took the focused card away: the card now at its position takes over,
            // before the layout drops the old one and the window hands focus elsewhere.
            ViewHolder heir = findViewHolderForAdapterPosition(
                    Math.min(focused, items.size() - 1));
            if (heir != null) heir.itemView.requestFocus();
            else if (items.isEmpty()) callbacks.onLeave();
        }
        if (artworkReleased) {
            artworkReleased = false;
            rebindAll(PlayniteLibraryDiff.ARTWORK);
        }
    }

    /** Rebinds every card, for example after the session presentation changed. */
    void rebindAll(int payload) {
        adapter.notifyItemRangeChanged(0, items.size(), payload);
    }

    /** Scrolls so {@code position} is shown with the row above it as context. */
    void showPosition(int position) {
        if (items.isEmpty()) return;
        int row = Math.max(0, Math.min(items.size() - 1, position)) / columns;
        grid.scrollToPositionWithOffset(Math.max(0, row - 1) * columns, 0);
    }

    /**
     * Focuses {@code position}, scrolling it in and waiting for its layout when needed. The
     * position is remembered for {@link #restoreFocus} even while a panel blocks focus; an empty
     * grid is left instead.
     */
    void focusPosition(int position) {
        if (items.isEmpty()) {
            callbacks.onLeave();
            return;
        }
        int target = Math.max(0, Math.min(items.size() - 1, position));
        lastFocused = target;
        ViewHolder holder = findViewHolderForAdapterPosition(target);
        if (holder != null && holder.itemView.isShown() && holder.itemView.requestFocus()) return;
        if (hasFocus()) {
            // The jump recycles the focused card. Hold focus on the grid itself meanwhile, or
            // the window would hand it to its first focusable view, the filter bar.
            setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
            requestFocus();
        }
        pendingFocus = target;
        showPosition(target);
    }

    /**
     * Returns focus to the position focused last, for example after a side panel closes. A card
     * removed meanwhile yields to the one now at its position; an empty grid is left.
     */
    void restoreFocus() {
        focusPosition(lastFocused);
    }

    int focusedPosition() {
        View focused = getFocusedChild();
        return focused == null ? NO_POSITION : getChildAdapterPosition(focused);
    }

    boolean handleKey(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                && keyCode != KeyEvent.KEYCODE_DPAD_LEFT
                && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        int index = focusedPosition();
        if (index == NO_POSITION) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastNavigationAt < NAVIGATION_INTERVAL_MS) return true;
        lastNavigationAt = now;
        int target = Rows.navigationTarget(index, columns, items.size(), keyCode);
        if (target == Rows.LEAVE) {
            pendingFocus = NO_POSITION;
            callbacks.onLeave();
        } else if (target != index) {
            focusPosition(target);
        }
        return true;
    }

    /**
     * Leaving the grid: keeps the adapter and holders for the next entry and drops only the
     * bitmaps they show. The next setItems rebinds the artwork.
     */
    void release() {
        pendingFocus = NO_POSITION;
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        prefetchStart = -1;
        prefetchEnd = -1;
        for (int index = 0; index < getChildCount(); index++) {
            callbacks.releaseCard(getChildAt(index));
        }
        // Shrinking the view cache recycles its holders, so onViewRecycled releases them too.
        setItemViewCacheSize(0);
        setItemViewCacheSize(columns * 2);
        artworkReleased = true;
    }

    /**
     * Runs an update that changes only the inside of fixed-size cards (artwork arriving after
     * the bind) and lays those cards out in place instead of the whole grid.
     */
    void updateCardsInPlace(Runnable update) {
        if (updatingCardsInPlace || isComputingLayout() || isLayoutRequested()) {
            update.run();
            return;
        }
        updatingCardsInPlace = true;
        try {
            update.run();
        } finally {
            updatingCardsInPlace = false;
        }
        if (!cardLayoutAbsorbed) return;
        cardLayoutAbsorbed = false;
        for (int index = 0; index < getChildCount(); index++) {
            View card = getChildAt(index);
            if (!card.isLayoutRequested()) continue;
            if (card.getWidth() == 0 || card.getHeight() == 0) {
                super.requestLayout();
                return;
            }
            card.measure(MeasureSpec.makeMeasureSpec(card.getWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(card.getHeight(), MeasureSpec.EXACTLY));
            card.layout(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
        }
    }

    @Override
    public void requestLayout() {
        if (updatingCardsInPlace) {
            cardLayoutAbsorbed = true;
            return;
        }
        super.requestLayout();
    }

    /**
     * Cuts the grid just above its first row (UX_MENU_PL.md 3.5): with a row aligned to the top
     * padding, the row before it ends one cell margin above the padding and stays hidden, while
     * the first row's focus scale and ring still fit under the cut. Clip bounds, not a canvas
     * clip: elevated cards ignore the latter.
     */
    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        setClipBounds(new Rect(0, topCut(), width, height));
    }

    /** Distance from the grid's top edge to where its cards become visible. */
    int topCut() {
        return getPaddingTop() - px(CELL_MARGIN_DP - 1);
    }

    /** Distance from the grid's side edges to its first and last cover. */
    int coverInset() {
        return px(SIDE_PADDING_DP + CELL_MARGIN_DP);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        int position = getChildAdapterPosition(child);
        if (position == NO_POSITION) return;
        pendingFocus = NO_POSITION;
        lastFocused = position;
        callbacks.onItemFocused(position, items.size());
    }

    /** Focus entering from outside lands on the first fully visible card. */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!hasFocus()) {
            int first = grid.findFirstCompletelyVisibleItemPosition();
            View entry = first == NO_POSITION ? null : grid.findViewByPosition(first);
            if (entry != null && entry.isFocusable()) {
                views.add(entry);
                return;
            }
        }
        super.addFocusables(views, direction, focusableMode);
    }

    /** One attempt after the layout that brought the target in; focus never jumps later. */
    private void applyPendingFocus() {
        if (pendingFocus == NO_POSITION) return;
        ViewHolder holder = findViewHolderForAdapterPosition(pendingFocus);
        pendingFocus = NO_POSITION;
        boolean holdingFocus = isFocused();
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        if (holder != null && holder.itemView.isShown()) holder.itemView.requestFocus();
        else if (holdingFocus) requestFocus();
    }

    /** Fills the pool (3 rows) one holder per frame, so rows scrolled in only bind. */
    private void warmPool() {
        if (items.isEmpty() || getRecycledViewPool().getRecycledViewCount(0)
                >= columns * POOL_ROWS) return;
        getRecycledViewPool().putRecycledView(adapter.createViewHolder(this, 0));
        postOnAnimation(this::warmPool);
    }

    private void requestPrefetch() {
        int[] range = Rows.prefetchRange(grid.findFirstVisibleItemPosition(),
                grid.findLastVisibleItemPosition(), columns, items.size(), scrollDirection);
        if (range[0] == prefetchStart && range[1] == prefetchEnd) return;
        prefetchStart = range[0];
        prefetchEnd = range[1];
        if (range[1] > range[0]) {
            callbacks.prefetchArtwork(new ArrayList<>(items.subList(range[0], range[1])));
        }
    }

    private int px(int dp) {
        return Math.round(dp * density);
    }

    private final class CardAdapter extends Adapter<ViewHolder> {
        @Override public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View card = callbacks.createCard();
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    px(CELL_WIDTH_DP), px(CELL_HEIGHT_DP));
            int margin = px(CELL_MARGIN_DP);
            params.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(params);
            return new ViewHolder(card) { };
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            bind(holder, position, FULL_BIND);
        }

        @Override public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                               @NonNull List<Object> payloads) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position);
                return;
            }
            int payload = 0;
            for (Object value : payloads) payload |= (Integer) value;
            bind(holder, position, payload);
        }

        private void bind(ViewHolder holder, int position, int payload) {
            callbacks.bindCard(holder.itemView, items.get(position), payload);
        }

        @Override public void onViewRecycled(@NonNull ViewHolder holder) {
            callbacks.releaseCard(holder.itemView);
        }
    }

    /** Pure row arithmetic, kept free of Android types for unit tests. */
    static final class Rows {
        static final int LEAVE = -1;
        static final int PREFETCH_ROWS = 2;

        private Rows() { }

        /** Target index of a D-pad step, the same index to stay, or LEAVE. */
        static int navigationTarget(int index, int columns, int count, int keyCode) {
            int column = index % columns;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return column == 0 ? LEAVE : index - 1;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return column + 1 < columns && index + 1 < count ? index + 1 : index;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return index < columns ? LEAVE : index - columns;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (index + columns < count) return index + columns;
                    return index / columns == (count - 1) / columns ? index : count - 1;
                default:
                    return index;
            }
        }

        /**
         * Item range [start, end) whose artwork should be warm: the visible rows plus
         * PREFETCH_ROWS ahead in the scroll direction (down unless scrolling up).
         */
        static int[] prefetchRange(int firstVisible, int lastVisible, int columns, int count,
                                   int direction) {
            if (count <= 0 || firstVisible < 0 || lastVisible < firstVisible) {
                return new int[]{0, 0};
            }
            int firstRow = firstVisible / columns;
            int lastRow = Math.min(lastVisible, count - 1) / columns;
            if (direction < 0) firstRow = Math.max(0, firstRow - PREFETCH_ROWS);
            else lastRow += PREFETCH_ROWS;
            return new int[]{firstRow * columns, Math.min(count, (lastRow + 1) * columns)};
        }
    }
}
