package com.limelight.utils;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

public class PanZoomHandler {
    static private final float MAX_SCALE = 10.0f;

    private final Game game;
    private final View streamView;
    private final PreferenceConfiguration prefConfig;
    private final boolean isTopMode;
    private final ScaleGestureDetector scaleGestureDetector;
    private final GestureDetector gestureDetector;
    private View parent;
    private float scaleFactor = 1.0f;
    private float childX, childY = 0;
    private float parentWidth, parentHeight = 0;
    private float childWidth, childHeight = 0;
    // When the soft keyboard is up, the bottom imeBottomInset pixels of the parent
    // are obscured. We treat the visible parent as (parentWidth, parentHeight - this)
    // so the user can scroll the stream up to reveal what was hidden behind the IME.
    private int imeBottomInset = 0;

    public PanZoomHandler(Context context, Game game, View streamView, View parent, PreferenceConfiguration prefConfig) {
        this.game = game;
        this.streamView = streamView;
        this.parent = parent;
        this.prefConfig = prefConfig;
        this.isTopMode = prefConfig.alignDisplayTopCenter;
        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());

        // Everything gets easier with 0,0 as the pivot point
        streamView.setPivotX(0);
        streamView.setPivotY(0);
    }

    public void handleTouchEvent(MotionEvent motionEvent) {
        scaleGestureDetector.onTouchEvent(motionEvent);
        gestureDetector.onTouchEvent(motionEvent);
    }

    // Pan-only variant: skips the pinch-zoom detector. Used by the IME-up
    // two-finger-scroll path so the user can slide the stream to reveal
    // text-cursor area without accidentally zooming.
    public void handlePanOnlyTouchEvent(MotionEvent motionEvent) {
        gestureDetector.onTouchEvent(motionEvent);
    }

    private void updateDimensions() {
        childHeight = streamView.getHeight() * scaleFactor;
        childWidth = streamView.getWidth() * scaleFactor;
        parentWidth = parent.getWidth();
        parentHeight = parent.getHeight();
    }

    private void constrainToBounds() {
        updateDimensions();

        if (parentWidth >= childWidth) {
            childX = (parentWidth - childWidth) / 2;
        } else {
            float boundaryX = parentWidth - childWidth;
            childX = Math.max(boundaryX, Math.min(childX, 0));
        }

        // While the IME covers the bottom of the parent, treat the visible area as
        // shorter so the user can pan the stream up to reveal what's hidden behind
        // the keyboard. At scale 1 with imeBottomInset > 0 this gives a vertical
        // scroll range of [parentHeight - imeBottomInset - childHeight, 0].
        float effectiveParentHeight = parentHeight - imeBottomInset;
        if (effectiveParentHeight >= childHeight) {
            if (isTopMode) {
                childY = 0;
            } else {
                childY = (effectiveParentHeight - childHeight) / 2;
            }
        } else {
            float boundaryY = effectiveParentHeight - childHeight;
            childY = Math.max(boundaryY, Math.min(childY, 0));
        }

        streamView.setX(childX);
        streamView.setY(childY);
    }

    public void handleSurfaceChange() {
        if (childWidth == 0 || parent == null) {
            // Retrieve parent, should handle both built-in display and external display
            parent = (View)streamView.getParent();
            return;
        }

        float prevChildWidth = childWidth;
        float prevChildHeight = childHeight;
        float prevParentWidth = parentWidth;
        float prevParentHeight = parentHeight;

        updateDimensions();

        float viewScaleX = childWidth / prevChildWidth;
        float viewScaleY = childHeight / prevChildHeight;

        float dPivotX1 = childX - prevParentWidth / 2;
        float dPivotY1 = childY - prevParentHeight / 2;

        float dPivotX2 = dPivotX1 * viewScaleX;
        float dPivotY2 = dPivotY1 * viewScaleY;

        childX = dPivotX2 + parentWidth / 2;
        childY = dPivotY2 + parentHeight / 2;

        streamView.setX(childX);
        streamView.setY(childY);

        constrainToBounds();
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScaleFactor = scaleFactor * detector.getScaleFactor();
            newScaleFactor = Math.max(1, Math.min(newScaleFactor, MAX_SCALE)); // Apply minimum scale

            // Calculate pivot point
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();

            float dPivotX = (childX - focusX) / scaleFactor * newScaleFactor;
            float dPivotY = (childY - focusY) / scaleFactor * newScaleFactor;

            childX = focusX + dPivotX;
            childY = focusY + dPivotY;

            scaleFactor = newScaleFactor;

            streamView.setScaleX(scaleFactor);
            streamView.setScaleY(scaleFactor);

            streamView.setX(childX);
            streamView.setY(childY);

            constrainToBounds();
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            game.updatePipAutoEnter();
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            childX = streamView.getX() - distanceX;
            childY = streamView.getY() - distanceY;

            streamView.setX(childX);
            streamView.setY(childY);

            constrainToBounds();
            return true;
        }
    }

    public void setInitialZoomAndPan(float scale, float offsetX, float offsetY) {
        this.scaleFactor = scale;
        // apply to view
        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);
        this.childX = offsetX;
        this.childY = offsetY;
        streamView.setX(childX);
        streamView.setY(childY);
    }

    /**
     * Reset zoom to 1.0 and re-center the child within the (current) parent. Used
     * when the parent's size changes (e.g. soft keyboard opens and resizes the
     * stream container) so the user starts from a sane "everything visible" state
     * before they pan/zoom from there.
     */
    public void resetToFit() {
        scaleFactor = 1.0f;
        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);
        childX = 0;
        childY = 0;
        streamView.setX(childX);
        streamView.setY(childY);
        constrainToBounds();
    }

    /**
     * Tell PanZoomHandler how much of the bottom of the parent is currently
     * obscured by the soft keyboard. This expands the legal childY range so the
     * user can pan the stream up to bring hidden content out from behind the IME.
     * Call with 0 when the keyboard closes.
     */
    public void setImeBottomInset(int px) {
        this.imeBottomInset = px;
        constrainToBounds();
    }

    public float getScaleFactor() { return scaleFactor; }
    public float getChildX() { return childX; }
    public float getChildY() { return childY; }

    /**
     * Auto-scroll the stream so an on-screen Y coordinate (the user's last
     * single-finger tap, used as a proxy for the focused text field) sits
     * above the IME with a small padding. Does nothing if the point is
     * already comfortably above the keyboard, so a re-open with the cursor
     * already visible doesn't disturb the user's pan.
     *
     * @param onScreenY tap Y in streamContainer / on-screen pixels
     * @param imeInset  current IME inset in pixels
     */
    public void scrollOnScreenPointAboveIme(float onScreenY, int imeInset) {
        if (imeInset <= 0) return;
        updateDimensions();
        float visibleBottom = parentHeight - imeInset;
        if (visibleBottom <= 0) return;
        float padding = Math.min(40f, visibleBottom * 0.1f);

        if (onScreenY <= visibleBottom - padding) return;  // already visible

        float delta = onScreenY - (visibleBottom - padding);
        childY -= delta;
        streamView.setY(childY);
        constrainToBounds();
    }
}
