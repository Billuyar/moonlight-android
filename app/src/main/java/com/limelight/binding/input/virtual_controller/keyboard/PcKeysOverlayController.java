package com.limelight.binding.input.virtual_controller.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Compact PC-keys floating panel. Sits at the top of the stream so it doesn't
 * collide with the Android soft keyboard (which appears at the bottom).
 *
 * Modifier keys (Ctrl/Alt/Shift/Win) latch on a single tap: the first tap
 * sends ACTION_DOWN and keeps the key held; the second tap sends ACTION_UP and
 * releases it. This is the NoMachine / Acronis pattern, distinct from the
 * axixi keyboard's long-press-to-latch.
 */
public class PcKeysOverlayController {

    private static final Set<Integer> MODIFIER_KEY_CODES = new HashSet<>();
    static {
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_ALT_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_ALT_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_CTRL_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_CTRL_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_SHIFT_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_SHIFT_RIGHT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_META_LEFT);
        MODIFIER_KEY_CODES.add(KeyEvent.KEYCODE_META_RIGHT);
    }

    private final Context context;
    private final PreferenceConfiguration prefConfig;
    private final FrameLayout frameLayout;
    private final LinearLayout overlayView;
    private final int heightDp;

    public boolean shown = false;

    // Bitset of currently-latched modifier KeyEvent codes.
    private final BitSet latchedModifiers = new BitSet();

    // Where to dock the panel: top (false, default) or bottom (true). When docked
    // at bottom and the IME is up, the panel sits above the keyboard.
    private boolean dockAtBottom = false;
    private int imeBottomInset = 0;

    public interface VisibilityListener { void onVisibilityChanged(); }
    private VisibilityListener visibilityListener;
    public void setVisibilityListener(VisibilityListener l) { this.visibilityListener = l; }
    private void notifyVisibilityChanged() {
        if (visibilityListener != null) visibilityListener.onVisibilityChanged();
    }

    public PcKeysOverlayController(FrameLayout layout, Context context, PreferenceConfiguration prefConfig) {
        this(layout, context, prefConfig, R.layout.layout_pc_keys_overlay, 120);
    }

    public PcKeysOverlayController(FrameLayout layout, Context context,
                                   PreferenceConfiguration prefConfig,
                                   int layoutResId, int heightDp) {
        this.frameLayout = layout;
        this.context = context;
        this.prefConfig = prefConfig;
        this.heightDp = heightDp;
        this.overlayView = (LinearLayout) LayoutInflater.from(context)
                .inflate(layoutResId, null);
        bindKeys();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindKeys() {
        View.OnTouchListener touchListener = (View v, MotionEvent event) -> {
            String tag = (String) v.getTag();
            if (TextUtils.equals("hide", tag)) {
                if (event.getAction() == MotionEvent.ACTION_UP
                        || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    hide();
                }
                return true;
            }

            int keyCode = Integer.parseInt(tag);
            boolean isModifier = MODIFIER_KEY_CODES.contains(keyCode);

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    if (isModifier) {
                        // Tap-to-latch: toggle on/off, send a single ACTION_DOWN
                        // when latching, single ACTION_UP when unlatching.
                        boolean wasLatched = latchedModifiers.get(keyCode);
                        if (wasLatched) {
                            latchedModifiers.clear(keyCode);
                            dispatch(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                            v.setBackgroundResource(R.drawable.bg_ax_keyboard_button);
                        } else {
                            latchedModifiers.set(keyCode);
                            dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                            v.setBackgroundResource(R.drawable.bg_ax_keyboard_button_confirm);
                        }
                        haptic(v, true);
                    } else {
                        dispatch(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                        v.setBackgroundResource(R.drawable.bg_ax_keyboard_button_confirm);
                        haptic(v, true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!isModifier) {
                        dispatch(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                        v.setBackgroundResource(R.drawable.bg_ax_keyboard_button);
                        haptic(v, false);
                    }
                    return true;
                }
            }
            return false;
        };

        for (int i = 0; i < overlayView.getChildCount(); i++) {
            View row = overlayView.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout linearRow = (LinearLayout) row;
            for (int j = 0; j < linearRow.getChildCount(); j++) {
                View child = linearRow.getChildAt(j);
                if (child.getTag() != null) {
                    child.setOnTouchListener(touchListener);
                }
            }
        }
    }

    private void haptic(View v, boolean down) {
        if (!prefConfig.enableKeyboardVibrate) return;
        v.performHapticFeedback(down
                ? HapticFeedbackConstants.VIRTUAL_KEY
                : HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
    }

    private void dispatch(KeyEvent event) {
        if (Game.instance == null || !Game.instance.connected) return;
        event.setSource(0);
        Game.instance.onKey(null, event.getKeyCode(), event);
    }

    public boolean isVisible() {
        return overlayView.getVisibility() == View.VISIBLE;
    }

    public void hide() {
        overlayView.setVisibility(View.GONE);
        // Release any latched modifiers when the panel is hidden so the host
        // doesn't end up stuck with Ctrl/Alt/Shift held forever.
        for (int kc = latchedModifiers.nextSetBit(0); kc >= 0; kc = latchedModifiers.nextSetBit(kc + 1)) {
            dispatch(new KeyEvent(KeyEvent.ACTION_UP, kc));
        }
        latchedModifiers.clear();
        // Reset visual state of any latched modifier buttons
        for (int i = 0; i < overlayView.getChildCount(); i++) {
            View row = overlayView.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout linearRow = (LinearLayout) row;
            for (int j = 0; j < linearRow.getChildCount(); j++) {
                View child = linearRow.getChildAt(j);
                child.setBackgroundResource(R.drawable.bg_ax_keyboard_button);
            }
        }
        shown = false;
        notifyVisibilityChanged();
    }

    public void show() {
        overlayView.setVisibility(View.VISIBLE);
        shown = true;
        notifyVisibilityChanged();
    }

    public void toggleVisibility() {
        if (isVisible()) hide(); // hide() fires the visibility listener
        else show();             // show() fires the visibility listener
    }

    /**
     * Add the panel to the parent FrameLayout. Width is full-screen; height is
     * the value the constructor was called with. Position is controlled by
     * dockAtBottom; when docked at bottom and an IME is up, the panel sits
     * above the keyboard via a bottom margin.
     */
    public void refreshLayout() {
        if (overlayView.getParent() != null) {
            frameLayout.removeView(overlayView);
        }
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        int width = screen.widthPixels;
        int height = dip2px(heightDp);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        if (dockAtBottom) {
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            params.bottomMargin = imeBottomInset;
        } else {
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        }
        overlayView.setAlpha(prefConfig.oscKeyboardOpacity / 100f);
        frameLayout.addView(overlayView, params);
    }

    /** Change the dock position. Re-positions the panel if it's currently shown. */
    public void setDockAtBottom(boolean dock) {
        if (this.dockAtBottom == dock) return;
        this.dockAtBottom = dock;
        if (overlayView.getParent() != null) {
            refreshLayout();
        }
    }

    /** Height of the panel in pixels (matches the value used by refreshLayout). */
    public int getHeightPx() {
        return dip2px(heightDp);
    }

    /** Push the IME bottom inset so the panel can sit above the keyboard when docked at bottom. */
    public void setImeBottomInset(int px) {
        if (this.imeBottomInset == px) return;
        this.imeBottomInset = px;
        if (dockAtBottom && overlayView.getParent() != null) {
            refreshLayout();
        }
    }

    private int dip2px(float dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}
