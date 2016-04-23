/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ripple;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;

import com.hippo.hotspot.Hotspotable;

import java.util.Arrays;

// android-6.0.1_r31
/**
 * Drawable that shows a ripple effect in response to state changes. The
 * anchoring position of the ripple for a given state may be specified by
 * calling {@link #setHotspot(float, float)} with the corresponding state
 * attribute identifier.
 * <p>
 * A touch feedback drawable may contain multiple child layers, including a
 * special mask layer that is not drawn to the screen. A single layer may be
 * set as the mask from XML by specifying its {@code android:id} value as
 * {@link android.R.id#mask}. At run time, a single layer may be set as the
 * mask using {@code setId(..., android.R.id.mask)} or an existing mask layer
 * may be replaced using {@code setDrawableByLayerId(android.R.id.mask, ...)}.
 * <pre>
 * <code>&lt!-- A red ripple masked against an opaque rectangle. --/>
 * &ltripple android:color="#ffff0000">
 *   &ltitem android:id="@android:id/mask"
 *         android:drawable="@android:color/white" />
 * &lt/ripple></code>
 * </pre>
 * <p>
 * If a mask layer is set, the ripple effect will be masked against that layer
 * before it is drawn over the composite of the remaining child layers.
 * <p>
 * If no mask layer is set, the ripple effect is masked against the composite
 * of the child layers.
 * <pre>
 * <code>&lt!-- A green ripple drawn atop a black rectangle. --/>
 * &ltripple android:color="#ff00ff00">
 *   &ltitem android:drawable="@android:color/black" />
 * &lt/ripple>
 *
 * &lt!-- A blue ripple drawn atop a drawable resource. --/>
 * &ltripple android:color="#ff0000ff">
 *   &ltitem android:drawable="@drawable/my_drawable" />
 * &lt/ripple></code>
 * </pre>
 * <p>
 * If no child layers or mask is specified and the ripple is set as a View
 * background, the ripple will be drawn atop the first available parent
 * background within the View's hierarchy. In this case, the drawing region
 * may extend outside of the Drawable bounds.
 * <pre>
 * <code>&lt!-- An unbounded red ripple. --/>
 * &ltripple android:color="#ffff0000" /></code>
 * </pre>
 */
class RippleDrawable extends Drawable implements Hotspotable {
    /**
     * Radius value that specifies the ripple radius should be computed based
     * on the size of the ripple's container.
     */
    public static final int RADIUS_AUTO = -1;

    /** The maximum number of ripples supported. */
    private static final int MAX_RIPPLES = 10;

    private final Rect mTempRect = new Rect();

    /** Current ripple effect bounds, used to constrain ripple effects. */
    private final Rect mHotspotBounds = new Rect();

    /** Current drawing bounds, used to compute dirty region. */
    private final Rect mDrawingBounds = new Rect();

    /** Current dirty bounds, union of current and previous drawing bounds. */
    private final Rect mDirtyBounds = new Rect();

    /** The current background. May be actively animating or pending entry. */
    private RippleBackground mBackground;

    /** Whether we expect to draw a background when visible. */
    private boolean mBackgroundActive;

    /** The current ripple. May be actively animating or pending entry. */
    private RippleForeground mRipple;

    /** Whether we expect to draw a ripple when visible. */
    private boolean mRippleActive;

    // Hotspot coordinates that are awaiting activation.
    private float mPendingX;
    private float mPendingY;
    private boolean mHasPending;

    /**
     * Lazily-created array of actively animating ripples. Inactive ripples are
     * pruned during draw(). The locations of these will not change.
     */
    private RippleForeground[] mExitingRipples;
    private int mExitingRipplesCount = 0;

    /** Paint used to control appearance of ripples. */
    private Paint mRipplePaint;

    /** Target density of the display into which ripples are drawn. */
    private float mDensity = 1.0f;

    /** Whether bounds are being overridden. */
    private boolean mOverrideBounds;

    private int mMaxRadius = RADIUS_AUTO;
    private ColorStateList mColor;
    private final Drawable mContent;

    RippleDrawable(Context context, ColorStateList color, Drawable content) {
        mDensity = context.getResources().getDisplayMetrics().density;
        mColor = color;
        mContent = content;
    }

    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();

        if (mRipple != null) {
            mRipple.end();
        }

        if (mBackground != null) {
            mBackground.end();
        }

        cancelExitingRipples();
    }

    private void cancelExitingRipples() {
        final int count = mExitingRipplesCount;
        final RippleForeground[] ripples = mExitingRipples;
        for (int i = 0; i < count; i++) {
            ripples[i].end();
        }

        if (ripples != null) {
            Arrays.fill(ripples, 0, count, null);
        }
        mExitingRipplesCount = 0;

        // Always draw an additional "clean" frame after canceling animations.
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        // Not supported
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        // Not supported
    }

    @Override
    public int getOpacity() {
        // Worst-case scenario.
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final boolean changed = super.onStateChange(stateSet);

        boolean enabled = false;
        boolean pressed = false;
        boolean focused = false;

        for (int state : stateSet) {
            if (state == android.R.attr.state_enabled) {
                enabled = true;
            } else if (state == android.R.attr.state_focused) {
                focused = true;
            } else if (state == android.R.attr.state_pressed) {
                pressed = true;
            }
        }

        setRippleActive(enabled && pressed);
        setBackgroundActive(focused || (enabled && pressed), focused);

        return changed;
    }

    private void setRippleActive(boolean active) {
        if (mRippleActive != active) {
            mRippleActive = active;
            if (active) {
                tryRippleEnter();
            } else {
                tryRippleExit();
            }
        }
    }

    private void setBackgroundActive(boolean active, boolean focused) {
        if (mBackgroundActive != active) {
            mBackgroundActive = active;
            if (active) {
                tryBackgroundEnter(focused);
            } else {
                tryBackgroundExit();
            }
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        if (!mOverrideBounds) {
            mHotspotBounds.set(bounds);
            onHotspotBoundsChanged();
        }

        if (mBackground != null) {
            mBackground.onBoundsChange();
        }

        if (mRipple != null) {
            mRipple.onBoundsChange();
        }

        if (mContent != null) {
            mContent.setBounds(bounds);
        }

        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);

        if (!visible) {
            clearHotspots();
        } else if (changed) {
            // If we just became visible, ensure the background and ripple
            // visibilities are consistent with their internal states.
            if (mRippleActive) {
                tryRippleEnter();
            }

            if (mBackgroundActive) {
                tryBackgroundEnter(false);
            }

            // Skip animations, just show the correct final states.
            jumpToCurrentState();
        }

        return changed;
    }

    private boolean isBounded() {
        return mContent != null;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    /**
     * Sets the ripple color.
     *
     * @param color Ripple color as a color state list.
     */
    public void setColor(ColorStateList color) {
        mColor = color;
        invalidateSelf();
    }

    /**
     * Sets the radius in pixels of the fully expanded ripple.
     *
     * @param radius ripple radius in pixels, or {@link #RADIUS_AUTO} to
     *               compute the radius based on the container size
     */
    public void setRadius(int radius) {
        mMaxRadius = radius;
        invalidateSelf();
    }

    /**
     * @return the radius in pixels of the fully expanded ripple if an explicit
     *         radius has been set, or {@link #RADIUS_AUTO} if the radius is
     *         computed based on the container size
     */
    public int getRadius() {
        return mMaxRadius;
    }

    @Override
    public void setHotspot(float x, float y) {
        if (mRipple == null || mBackground == null) {
            mPendingX = x;
            mPendingY = y;
            mHasPending = true;
        }

        if (mRipple != null) {
            mRipple.move(x, y);
        }
    }

    /**
     * Creates an active hotspot at the specified location.
     */
    private void tryBackgroundEnter(boolean focused) {
        if (mBackground == null) {
            mBackground = new RippleBackground(this, mHotspotBounds);
        }

        mBackground.setup(mMaxRadius, mDensity);
        mBackground.enter(focused);
    }

    private void tryBackgroundExit() {
        if (mBackground != null) {
            // Don't null out the background, we need it to draw!
            mBackground.exit();
        }
    }

    /**
     * Attempts to start an enter animation for the active hotspot. Fails if
     * there are too many animating ripples.
     */
    private void tryRippleEnter() {
        if (mExitingRipplesCount >= MAX_RIPPLES) {
            // This should never happen unless the user is tapping like a maniac
            // or there is a bug that's preventing ripples from being removed.
            return;
        }

        if (mRipple == null) {
            final float x;
            final float y;
            if (mHasPending) {
                mHasPending = false;
                x = mPendingX;
                y = mPendingY;
            } else {
                x = mHotspotBounds.exactCenterX();
                y = mHotspotBounds.exactCenterY();
            }

            final boolean isBounded = isBounded();
            mRipple = new RippleForeground(this, mHotspotBounds, x, y, isBounded);
        }

        mRipple.setup(mMaxRadius, mDensity);
        mRipple.enter(false);
    }

    /**
     * Attempts to start an exit animation for the active hotspot. Fails if
     * there is no active hotspot.
     */
    private void tryRippleExit() {
        if (mRipple != null) {
            if (mExitingRipples == null) {
                mExitingRipples = new RippleForeground[MAX_RIPPLES];
            }
            mExitingRipples[mExitingRipplesCount++] = mRipple;
            mRipple.exit();
            mRipple = null;
        }
    }

    /**
     * Cancels and removes the active ripple, all exiting ripples, and the
     * background. Nothing will be drawn after this method is called.
     */
    private void clearHotspots() {
        if (mRipple != null) {
            mRipple.end();
            mRipple = null;
            mRippleActive = false;
        }

        if (mBackground != null) {
            mBackground.end();
            mBackground = null;
            mBackgroundActive = false;
        }

        cancelExitingRipples();
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        mOverrideBounds = true;
        mHotspotBounds.set(left, top, right, bottom);

        onHotspotBoundsChanged();
    }

    @Override
    public void getHotspotBounds(Rect outRect) {
        outRect.set(mHotspotBounds);
    }

    /**
     * Notifies all the animating ripples that the hotspot bounds have changed.
     */
    private void onHotspotBoundsChanged() {
        final int count = mExitingRipplesCount;
        final RippleForeground[] ripples = mExitingRipples;
        for (int i = 0; i < count; i++) {
            ripples[i].onHotspotBoundsChanged();
        }

        if (mRipple != null) {
            mRipple.onHotspotBoundsChanged();
        }

        if (mBackground != null) {
            mBackground.onHotspotBoundsChanged();
        }
    }

    /**
     * Optimized for drawing ripples with a mask layer and optional content.
     */
    @Override
    public void draw(@NonNull Canvas canvas) {
        pruneRipples();

        // Clip to the dirty bounds, which will be the drawable bounds if we
        // have a mask or content and the ripple bounds if we're projecting.
        final Rect bounds = getDirtyBounds();
        final int saveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
        canvas.clipRect(bounds);

        drawContent(canvas);
        drawBackgroundAndRipples(canvas);

        canvas.restoreToCount(saveCount);
    }

    private void pruneRipples() {
        int remaining = 0;

        // Move remaining entries into pruned spaces.
        final RippleForeground[] ripples = mExitingRipples;
        final int count = mExitingRipplesCount;
        for (int i = 0; i < count; i++) {
            if (!ripples[i].hasFinishedExit()) {
                ripples[remaining++] = ripples[i];
            }
        }

        // Null out the remaining entries.
        for (int i = remaining; i < count; i++) {
            ripples[i] = null;
        }

        mExitingRipplesCount = remaining;
    }

    private void drawContent(Canvas canvas) {
        if (mContent != null) {
            mContent.draw(canvas);
        }
    }

    private void drawBackgroundAndRipples(Canvas canvas) {
        final RippleForeground active = mRipple;
        final RippleBackground background = mBackground;
        final int count = mExitingRipplesCount;
        if (active == null && count <= 0 && (background == null || !background.isVisible())) {
            // Move along, nothing to draw here.
            return;
        }

        final float x = mHotspotBounds.exactCenterX();
        final float y = mHotspotBounds.exactCenterY();
        canvas.translate(x, y);

        // Grab the color for the current state and cut the alpha channel in
        // half so that the ripple and background together yield full alpha.
        final int color = mColor.getColorForState(getState(), Color.BLACK);
        final int halfAlpha = (Color.alpha(color) / 2) << 24;
        final Paint p = getRipplePaint();

        final int halfAlphaColor = (color & 0xFFFFFF) | halfAlpha;
        p.setColor(halfAlphaColor);
        p.setColorFilter(null);
        p.setShader(null);

        if (background != null && background.isVisible()) {
            background.draw(canvas, p);
        }

        if (count > 0) {
            final RippleForeground[] ripples = mExitingRipples;
            for (int i = 0; i < count; i++) {
                ripples[i].draw(canvas, p);
            }
        }

        if (active != null) {
            active.draw(canvas, p);
        }

        canvas.translate(-x, -y);
    }

    private Paint getRipplePaint() {
        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
            mRipplePaint.setStyle(Paint.Style.FILL);
        }
        return mRipplePaint;
    }

    @Override
    public Rect getDirtyBounds() {
        if (!isBounded()) {
            final Rect drawingBounds = mDrawingBounds;
            final Rect dirtyBounds = mDirtyBounds;
            dirtyBounds.set(drawingBounds);
            drawingBounds.setEmpty();

            final int cX = (int) mHotspotBounds.exactCenterX();
            final int cY = (int) mHotspotBounds.exactCenterY();
            final Rect rippleBounds = mTempRect;

            final RippleForeground[] activeRipples = mExitingRipples;
            final int N = mExitingRipplesCount;
            for (int i = 0; i < N; i++) {
                activeRipples[i].getBounds(rippleBounds);
                rippleBounds.offset(cX, cY);
                drawingBounds.union(rippleBounds);
            }

            final RippleBackground background = mBackground;
            if (background != null) {
                background.getBounds(rippleBounds);
                rippleBounds.offset(cX, cY);
                drawingBounds.union(rippleBounds);
            }

            dirtyBounds.union(drawingBounds);
            if (!dirtyBounds.intersect(getBounds())) {
                dirtyBounds.setEmpty();
            }
            return dirtyBounds;
        } else {
            return getBounds();
        }
    }
}
