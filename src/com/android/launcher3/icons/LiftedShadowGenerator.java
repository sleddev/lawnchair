package com.android.launcher3.icons;

import static com.android.launcher3.icons.ShadowGenerator.BLUR_FACTOR;
import static com.android.launcher3.icons.ShadowGenerator.ENABLE_SHADOWS;
import static com.android.launcher3.icons.ShadowGenerator.KEY_SHADOW_DISTANCE;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Lawnchair: the stock icon shadow, a little stronger, so icons sit on the wallpaper instead of
 * looking printed onto it. The stock key shadow is barely visible.
 *
 * It lives in this package to override the package private {@link #addPathShadow}.
 */
public class LiftedShadowGenerator extends ShadowGenerator {

    private static final int AMBIENT_SHADOW_ALPHA = 30;
    private static final int KEY_SHADOW_ALPHA = 34;

    private final int mIconSize;
    private final Paint mBlurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mDrawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final BlurMaskFilter mBlurMaskFilter;

    public LiftedShadowGenerator(int iconSize) {
        super(iconSize);
        mIconSize = iconSize;
        mBlurMaskFilter = new BlurMaskFilter(iconSize * BLUR_FACTOR, BlurMaskFilter.Blur.NORMAL);
    }

    @Override
    public synchronized void drawShadow(Bitmap icon, Canvas out) {
        if (!ENABLE_SHADOWS) return;
        int[] offset = new int[2];
        mBlurPaint.setMaskFilter(mBlurMaskFilter);
        Bitmap shadow = icon.extractAlpha(mBlurPaint, offset);

        mDrawPaint.setAlpha(AMBIENT_SHADOW_ALPHA);
        out.drawBitmap(shadow, offset[0], offset[1], mDrawPaint);

        mDrawPaint.setAlpha(KEY_SHADOW_ALPHA);
        out.drawBitmap(shadow, offset[0], offset[1] + KEY_SHADOW_DISTANCE * mIconSize, mDrawPaint);
    }

    @Override
    void addPathShadow(Path path, Canvas out) {
        if (!ENABLE_SHADOWS) return;
        mDrawPaint.setMaskFilter(mBlurMaskFilter);

        mDrawPaint.setAlpha(AMBIENT_SHADOW_ALPHA);
        out.drawPath(path, mDrawPaint);

        int save = out.save();
        mDrawPaint.setAlpha(KEY_SHADOW_ALPHA);
        out.translate(0, KEY_SHADOW_DISTANCE * mIconSize);
        out.drawPath(path, mDrawPaint);
        out.restoreToCount(save);

        mDrawPaint.setMaskFilter(null);
    }
}
