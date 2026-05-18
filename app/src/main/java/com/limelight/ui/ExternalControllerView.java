package com.limelight.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

public class ExternalControllerView extends FrameLayout {
    private InputCallbacks inputCallbacks;

    // When enabled, we expose an InputConnection so that soft keyboards can send
    // commitText() events (e.g. swipe typing). Default disabled.
    private boolean commitTextEnabled = false;

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.inputCallbacks = callbacks;
    }

    public void setCommitTextEnabled(boolean enabled) {
        this.commitTextEnabled = enabled;
        // Request focus so that IME targets this view when enabled
        if (enabled) {
            setFocusableInTouchMode(true);
            requestFocus();
        }
    }

    public ExternalControllerView(@NonNull Context context) {
        super(context);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        // This callbacks allows us to override dumb IME behavior like when
        // Samsung's default keyboard consumes Shift+Space.
        if (inputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks.handleKeyDown(event)) {
                    return true;
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (inputCallbacks.handleKeyUp(event)) {
                    return true;
                }
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return commitTextEnabled || super.onCheckIsTextEditor();
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs);
        }

        // NO_SUGGESTIONS asks IMEs (notably Samsung Keyboard) to skip predictive
        // composition. NO_PERSONALIZED_LEARNING tells them not to remember typed
        // text for autocomplete.
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;

        return new StreamingInputConnection(this, new StreamingInputConnection.Sink() {
            @Override
            public boolean handleCommitText(CharSequence text) {
                return inputCallbacks != null && inputCallbacks.handleCommitText(text);
            }
            @Override
            public boolean handleDeleteSurroundingText(int beforeLength, int afterLength) {
                return inputCallbacks != null && inputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength);
            }
        });
    }

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
    }
}
