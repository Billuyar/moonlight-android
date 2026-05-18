package com.limelight.ui;

import android.view.View;
import android.view.inputmethod.BaseInputConnection;

/**
 * Shared InputConnection used by the three views that can receive IME focus
 * during a stream (StreamContainer, StreamView, ExternalControllerView).
 *
 * Samsung Keyboard and similar IMEs hold characters in a composing region
 * until the user commits a word (space, punctuation, suggestion tap). We
 * intercept setComposingText() so characters reach the host as they're
 * typed, then handle the IME's commit sequence without double-emitting.
 *
 * The trickiest pattern is:
 *   1. setComposingText("ışık")     — composes the word; we type it
 *   2. finishComposingText()         — composition done; buffer is "frozen"
 *   3. commitText("ışık ")           — commit composed word + trailing space
 *
 * If finishComposingText() clears our state, step 3 re-emits the whole word.
 * Instead we keep the buffer in a frozen state and on the next event treat
 * a matching prefix as already typed — emitting only the new suffix.
 */
public class StreamingInputConnection extends BaseInputConnection {

    public interface Sink {
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
    }

    private final Sink sink;
    private final StringBuilder composing = new StringBuilder();
    private boolean frozen = false;

    public StreamingInputConnection(View targetView, Sink sink) {
        super(targetView, false);
        this.sink = sink;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        String next = text == null ? "" : text.toString();
        if (frozen) {
            consumeAfterFreeze(next);
            // The new text becomes the new composing region; reset state.
            composing.setLength(0);
            composing.append(next);
            frozen = false;
        } else {
            emitCompositionDelta(next);
        }
        return super.setComposingText(text, newCursorPosition);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        String next = text == null ? "" : text.toString();
        if (frozen) {
            consumeAfterFreeze(next);
        } else {
            emitCompositionDelta(next);
        }
        composing.setLength(0);
        frozen = false;
        return true;
    }

    @Override
    public boolean finishComposingText() {
        // Don't clear the buffer — the IME usually re-commits the same text
        // immediately after. Keep it around so we can detect that re-commit
        // and emit only the new suffix.
        frozen = true;
        return super.finishComposingText();
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (sink != null && sink.handleDeleteSurroundingText(beforeLength, afterLength)) {
            return true;
        }
        return super.deleteSurroundingText(beforeLength, afterLength);
    }

    // Composing region replaced (still active). Emit a diff against the
    // previous composing text so the host stays in sync.
    private void emitCompositionDelta(String next) {
        String prev = composing.toString();
        int common = 0;
        int max = Math.min(prev.length(), next.length());
        while (common < max && prev.charAt(common) == next.charAt(common)) {
            common++;
        }
        int toDelete = prev.length() - common;
        if (toDelete > 0 && sink != null) {
            sink.handleDeleteSurroundingText(toDelete, 0);
        }
        if (next.length() > common && sink != null) {
            sink.handleCommitText(next.substring(common));
        }
        composing.setLength(0);
        composing.append(next);
    }

    // Called when an IME event lands after finishComposingText(). The text
    // most often starts with the just-finalized composition (Samsung's
    // "commit composed word + space" pattern); emit only the suffix. If it
    // doesn't share a prefix, treat the previous composition as committed
    // (don't backspace it) and emit the new text as-is.
    private void consumeAfterFreeze(String next) {
        String prev = composing.toString();
        if (!prev.isEmpty() && next.startsWith(prev)) {
            String suffix = next.substring(prev.length());
            if (!suffix.isEmpty() && sink != null) {
                sink.handleCommitText(suffix);
            }
        } else if (!next.isEmpty() && sink != null) {
            sink.handleCommitText(next);
        }
    }
}
