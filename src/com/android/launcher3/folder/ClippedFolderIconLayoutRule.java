package com.android.launcher3.folder;

/**
 * Lawnchair: lays out the folder preview as a 3x3 grid that sits fully inside the folder
 * background and fills up in reading order, instead of AOSP's circle-based layout where the items
 * overflow the background.
 */
public class ClippedFolderIconLayoutRule {

    public static final int PREVIEW_GRID_SIZE = 3;
    public static final int MAX_NUM_ITEMS_IN_PREVIEW = PREVIEW_GRID_SIZE * PREVIEW_GRID_SIZE;

    // Padding between the background edge and the grid, as a fraction of the available space.
    // Large enough that the corner items stay clear of the background's rounded corners.
    private static final float GRID_PADDING = 0.11f;
    // Fraction of a grid cell taken up by its icon; the rest is the gap between icons.
    private static final float ICON_CELL_FACTOR = 0.92f;

    private static final float MAX_RADIUS_DILATION = 0.25f;
    // The max amount of overlap the preview items can go outside of the background bounds.
    public static final float ICON_OVERLAP_FACTOR = 1 + (MAX_RADIUS_DILATION / 2f);

    public static final int EXIT_INDEX = -2;
    public static final int ENTER_INDEX = -3;

    private float[] mTmpPoint = new float[2];

    private float mAvailableSpace;
    private float mIconSize;
    private boolean mIsRtl;
    private float mBaselineIconScale;
    private int mNumFolderColumns;

    /**
     * initialize the layout rule
     */
    public void init(int availableSpace, float intrinsicIconSize, boolean rtl,
            int numFolderColumns) {
        mAvailableSpace = availableSpace;
        mIconSize = intrinsicIconSize;
        mIsRtl = rtl;
        mBaselineIconScale = availableSpace / intrinsicIconSize;
        mNumFolderColumns = numFolderColumns;
    }

    /**
     * Computes positions for icons in Preview.
     *
     * @param index       index of icon in folder
     * @param curNumItems current number of preview items
     * @param params      params to update for icon
     */
    public PreviewItemDrawingParams computePreviewItemDrawingParams(int index, int curNumItems,
            PreviewItemDrawingParams params) {
        float totalScale = scaleForItem(curNumItems, 0);
        float transX;
        float transY;

        if (index == EXIT_INDEX) {
            // 0 1 2 * <-- Exit position (row 0, col 3)
            // 3 4 5
            // 6 7 8
            getGridPosition(0, PREVIEW_GRID_SIZE, mTmpPoint);
        } else if (index == ENTER_INDEX) {
            // 0 1 2
            // 3 4 5 * <-- Enter position (row 1, col 3)
            // 6 7 8
            getGridPosition(1, PREVIEW_GRID_SIZE, mTmpPoint);
        } else if (index >= MAX_NUM_ITEMS_IN_PREVIEW) {
            // Items beyond those displayed in the preview are animated to the center
            mTmpPoint[0] = mTmpPoint[1] = mAvailableSpace / 2 - (mIconSize * totalScale) / 2;
        } else {
            getPosition(index, mTmpPoint);
        }

        transX = mTmpPoint[0];
        transY = mTmpPoint[1];

        if (params == null) {
            params = new PreviewItemDrawingParams(transX, transY, totalScale);
        } else {
            params.update(transX, transY, totalScale);
        }
        return params;
    }

    /**
     * Computes positions for icons in folder as part of spring animation. Here both preview icons
     * and the rest of the content icons are animated along the same grid.
     *
     * @param index          index of icon in folder
     * @param numItemsInPage current number of items in page
     * @param params         params to update for icon
     */
    public PreviewItemDrawingParams computeSpringAnimationItemParams(int index, int numItemsInPage,
            int page, PreviewItemDrawingParams params) {
        float totalScale = scaleForItem(numItemsInPage, page);
        float transX;
        float transY;

        if (numItemsInPage <= MAX_NUM_ITEMS_IN_PREVIEW) {
            getPosition(index, mTmpPoint);
        } else {
            getGridPosition(index / mNumFolderColumns, index % mNumFolderColumns, mTmpPoint);
        }

        transX = mTmpPoint[0];
        transY = mTmpPoint[1];

        if (params == null) {
            params = new PreviewItemDrawingParams(transX, transY, totalScale);
        } else {
            params.update(transX, transY, totalScale);
        }
        return params;
    }

    /** Size of a grid cell, as a fraction of the available space. */
    private static float getCellSize() {
        return (1 - 2 * GRID_PADDING) / PREVIEW_GRID_SIZE;
    }

    /**
     * Returns the top left of the icon at {@param row}, {@param col} of the grid. Columns beyond
     * the grid continue at the same spacing.
     *
     * Positions in the 3x3 grid: 0 1 2  // 0 is row 0, col 0
     *                            3 4 5
     *                            6 7 8  // 8 is row 2, col 2
     */
    private void getGridPosition(int row, int col, float[] result) {
        float cellSize = getCellSize() * mAvailableSpace;
        float iconSize = cellSize * ICON_CELL_FACTOR;
        float first = GRID_PADDING * mAvailableSpace + (cellSize - iconSize) / 2;

        if (mIsRtl) {
            col = PREVIEW_GRID_SIZE - 1 - col;
        }
        result[0] = first + col * cellSize;
        result[1] = first + row * cellSize;
    }

    /** Items fill the grid in reading order, starting at the top left. */
    private void getPosition(int index, float[] result) {
        getGridPosition(index / PREVIEW_GRID_SIZE, index % PREVIEW_GRID_SIZE, result);
    }

    /**
     * Calculate Scale for Preview Icons based on current page and number of items in page.
     * @param numItems number of items in page
     * @param page current page of Folder
     * @return scale for icons in Folder
     */
    public float scaleForItem(int numItems, int page) {
        return getCellSize() * ICON_CELL_FACTOR * mBaselineIconScale;
    }

    public float getIconSize() {
        return mIconSize;
    }
}
