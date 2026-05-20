package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

public class AbsoluteTouchContext implements TouchContext {
    private int lastTouchDownX = 0;
    private int lastTouchDownY = 0;
    private long lastTouchDownTime = 0;
    private int lastTouchUpX = 0;
    private int lastTouchUpY = 0;
    private long lastTouchUpTime = 0;
    private int lastTouchLocationX = 0;
    private int lastTouchLocationY = 0;
    private boolean cancelled;
    private boolean confirmedLongPress;
    private boolean confirmedTap;
    
    private final byte buttonPrimary;
    private final byte buttonSecondary;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            // This timer should have already expired, but cancel it just in case
            cancelTapDownTimer();

            // Switch from a left click to a right click after a long press.
            // Critical ordering: the left-button release MUST land before
            // the right-button press, otherwise the host sees both buttons
            // pressed simultaneously and the lingering left-up event after
            // the right-up dismisses the context menu. Sending the two
            // messages back-to-back lets Sunshine coalesce them; a small
            // delay forces them onto separate input frames.
            confirmedLongPress = true;
            if (confirmedTap) {
                conn.sendMouseButtonUp(buttonPrimary);
                handler.postDelayed(
                        () -> conn.sendMouseButtonDown(buttonSecondary), 60);
            } else {
                conn.sendMouseButtonDown(buttonSecondary);
            }
        }
    };

    private final Runnable tapDownRunnable = new Runnable() {
        @Override
        public void run() {
            // Start our tap
            tapConfirmed();
        }
    };

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final Handler handler;

    private final Runnable leftButtonUpRunnable = new Runnable() {
        @Override
        public void run() {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    };

    private static final int SCROLL_SPEED_FACTOR = 3;

    // Long-press to right-click. Bumped from Moonlight's defaults of
    // (650, 30) because on a 1080-wide phone, 30 px of allowed jitter
    // is ~3% of the screen and a steady hand easily exceeds it during
    // a half-second hold, cancelling the timer before it fires.
    private static final int LONG_PRESS_TIME_THRESHOLD = 500;
    private static final int LONG_PRESS_DISTANCE_THRESHOLD = 80;

    private static final int DOUBLE_TAP_TIME_THRESHOLD = 250;
    private static final int DOUBLE_TAP_DISTANCE_THRESHOLD = 60;

    private static final int TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100;
    private static final int TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20;

    // Two-phase copy gesture: (1) a drag with the left button held down
    // selects text on the host; on touch-release we ARM a "pending copy"
    // flag instead of copying immediately, because mid-select copies
    // sometimes captured stale PRIMARY content. (2) The NEXT quick tap
    // (lifted within the tap-down deadzone, no movement) fires the
    // configured handler — and we suppress the left click that the tap
    // would normally produce, so the click doesn't deselect the text
    // before the host reads PRIMARY. The flag auto-expires so a tap
    // long after a select doesn't mysteriously copy.
    private static final int DRAG_SELECT_DISTANCE_THRESHOLD = 60;
    private static final int PENDING_COPY_EXPIRY_MS = 4000;

    private boolean pendingCopyTap;

    private final Runnable clearPendingCopyRunnable = new Runnable() {
        @Override
        public void run() {
            pendingCopyTap = false;
        }
    };

    private Runnable copyTapHandler;

    public void setCopyTapHandler(Runnable handler) {
        this.copyTapHandler = handler;
    }

    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view, boolean swapped)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.targetView = view;
        this.handler = new Handler(Looper.getMainLooper());

        if (swapped) {
            buttonPrimary = MouseButtonPacket.BUTTON_RIGHT;
            buttonSecondary = MouseButtonPacket.BUTTON_LEFT;
        }
        else {
            buttonPrimary = MouseButtonPacket.BUTTON_LEFT;
            buttonSecondary = MouseButtonPacket.BUTTON_RIGHT;
        }
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger)
    {
        if (!isNewFinger) {
            // We don't handle finger transitions for absolute mode
            return true;
        }

        lastTouchLocationX = lastTouchDownX = eventX;
        lastTouchLocationY = lastTouchDownY = eventY;
        lastTouchDownTime = eventTime;
        cancelled = confirmedTap = confirmedLongPress = false;

        if (actionIndex == 0) {
            // Start the timers
            startTapDownTimer();
            startLongPressTimer();
        }

        return true;
    }

    private boolean distanceExceeds(int deltaX, int deltaY, double limit) {
        return Math.sqrt(Math.pow(deltaX, 2) + Math.pow(deltaY, 2)) > limit;
    }

    private void updatePosition(int eventX, int eventY) {
        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), targetView.getWidth());
        eventY = Math.min(Math.max(eventY, 0), targetView.getHeight());

        conn.sendMousePosition((short)eventX, (short)eventY, (short)targetView.getWidth(), (short)targetView.getHeight());
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return;
        }

        if (actionIndex == 0) {
            // Cancel the timers
            cancelLongPressTimer();
            cancelTapDownTimer();

            // Raise the mouse buttons that we currently have down
            if (confirmedLongPress) {
                conn.sendMouseButtonUp(buttonSecondary);
                // Long-press = right-click, not a copy tap.
                pendingCopyTap = false;
                handler.removeCallbacks(clearPendingCopyRunnable);
            }
            else if (confirmedTap) {
                // Make sure the cursor is at the finger-up position
                // BEFORE we release the button, so the drag-select
                // extends to where the user actually lifted. Without
                // this, the selection ends at the last touchMove
                // position (which may lag behind the lift point) and
                // a few characters at the end get dropped.
                updatePosition(eventX, eventY);
                conn.sendMouseButtonUp(buttonPrimary);
                // If the finger moved enough to be a drag-select, arm
                // pendingCopyTap so the NEXT quick tap fires the copy
                // handler (replacing its click). Mid-select copying was
                // unreliable, so we wait for an explicit tap.
                if (distanceExceeds(eventX - lastTouchDownX,
                                    eventY - lastTouchDownY,
                                    DRAG_SELECT_DISTANCE_THRESHOLD)) {
                    pendingCopyTap = true;
                    handler.removeCallbacks(clearPendingCopyRunnable);
                    handler.postDelayed(clearPendingCopyRunnable, PENDING_COPY_EXPIRY_MS);
                }
            }
            else {
                // Quick tap (lifted within deadzone). If a copy is
                // pending from a recent drag-select, fire that instead
                // of the click — sending a click would deselect the
                // text before the host reads PRIMARY.
                if (pendingCopyTap) {
                    pendingCopyTap = false;
                    handler.removeCallbacks(clearPendingCopyRunnable);
                    if (copyTapHandler != null) copyTapHandler.run();
                } else {
                    tapConfirmed();
                    // Release the left mouse button in 100ms to allow for apps that use polling
                    // to detect mouse button presses.
                    handler.removeCallbacks(leftButtonUpRunnable);
                    handler.postDelayed(leftButtonUpRunnable, 100);
                }
            }
        }

        lastTouchLocationX = lastTouchUpX = eventX;
        lastTouchLocationY = lastTouchUpY = eventY;
        lastTouchUpTime = eventTime;
    }

    private void startLongPressTimer() {
        cancelLongPressTimer();
        handler.postDelayed(longPressRunnable, LONG_PRESS_TIME_THRESHOLD);
    }

    private void cancelLongPressTimer() {
        handler.removeCallbacks(longPressRunnable);
    }

    private void startTapDownTimer() {
        cancelTapDownTimer();
        handler.postDelayed(tapDownRunnable, TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD);
    }

    private void cancelTapDownTimer() {
        handler.removeCallbacks(tapDownRunnable);
    }

    private void tapConfirmed() {
        if (confirmedTap || confirmedLongPress) {
            return;
        }

        confirmedTap = true;
        cancelTapDownTimer();

        // Left button down at original position
        if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
                distanceExceeds(lastTouchDownX - lastTouchUpX, lastTouchDownY - lastTouchUpY, DOUBLE_TAP_DISTANCE_THRESHOLD)) {
            // Don't reposition for finger down events within the deadzone. This makes double-clicking easier.
            updatePosition(lastTouchDownX, lastTouchDownY);
        }
        conn.sendMouseButtonDown(buttonPrimary);
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        if (actionIndex == 0) {
            if (distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, LONG_PRESS_DISTANCE_THRESHOLD)) {
                // Moved too far since touch down. Cancel the long press timer.
                cancelLongPressTimer();
            }

            // Once the long-press has fired, the right button is held
            // and the host has opened a context menu. Don't move the
            // cursor on subsequent jitter — drift between right-down and
            // right-up lets Mutter interpret the release as a click
            // outside the menu, dismissing it before the user can pick
            // an item.
            if (confirmedLongPress) {
                return true;
            }

            // Ignore motion within the deadzone period after touch down
            if (confirmedTap || distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD)) {
                tapConfirmed();
                updatePosition(eventX, eventY);
            }
        }
        else if (actionIndex == 1) {
            conn.sendMouseHighResScroll((short)((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR));
        }

        lastTouchLocationX = eventX;
        lastTouchLocationY = eventY;

        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        // Cancel the timers
        cancelLongPressTimer();
        cancelTapDownTimer();

        // Raise the mouse buttons
        if (confirmedLongPress) {
            conn.sendMouseButtonUp(buttonSecondary);
        }
        else if (confirmedTap) {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        if (actionIndex == 0 && pointerCount > 1) {
            cancelTouch();
        }
    }
}
