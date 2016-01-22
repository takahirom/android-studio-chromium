// Copyright 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.content.browser.input;

import android.content.res.Configuration;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.UnderlineSpan;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;

import org.chromium.base.Log;
import org.chromium.base.VisibleForTesting;
import org.chromium.base.annotations.CalledByNative;
import org.chromium.base.annotations.JNINamespace;
import org.chromium.blink_public.web.WebInputEventModifier;
import org.chromium.blink_public.web.WebInputEventType;
import org.chromium.ui.base.ime.TextInputType;
import org.chromium.ui.picker.InputDialogContainer;

/**
 * Adapts and plumbs android IME service onto the chrome text input API.
 * ImeAdapter provides an interface in both ways native <-> java:
 * 1. InputConnectionAdapter notifies native code of text composition state and
 *    dispatch key events from java -> WebKit.
 * 2. Native ImeAdapter notifies java side to clear composition text.
 *
 * The basic flow is:
 * 1. When InputConnectionAdapter gets called with composition or result text:
 *    If we receive a composition text or a result text, then we just need to
 *    dispatch a synthetic key event with special keycode 229, and then dispatch
 *    the composition or result text.
 * 2. Intercept dispatchKeyEvent() method for key events not handled by IME, we
 *   need to dispatch them to webkit and check webkit's reply. Then inject a
 *   new key event for further processing if webkit didn't handle it.
 *
 * Note that the native peer object does not take any strong reference onto the
 * instance of this java object, hence it is up to the client of this class (e.g.
 * the ViewEmbedder implementor) to hold a strong reference to it for the required
 * lifetime of the object.
 */
@JNINamespace("content")
public class ImeAdapter {
    private static final String TAG = "cr_Ime";

    private static final int COMPOSITION_KEY_CODE = 229;

    /**
     * Interface for the delegate that needs to be notified of IME changes.
     */
    public interface ImeAdapterDelegate {
        /**
         * Called to notify the delegate about synthetic/real key events before sending to renderer.
         */
        void onImeEvent();

        /**
         * Called when the keyboard could not be shown due to the hardware keyboard being present.
         */
        void onKeyboardBoundsUnchanged();

        /**
         * @see BaseInputConnection#performContextMenuAction(int)
         */
        boolean performContextMenuAction(int id);

        /**
         * @return View that the keyboard should be attached to.
         */
        View getAttachedView();

        /**
         * @return Object that should be called for all keyboard show and hide requests.
         */
        ResultReceiver getNewShowKeyboardReceiver();
    }

    static char[] sSingleCharArray = new char[1];
    static KeyCharacterMap sKeyCharacterMap;

    private long mNativeImeAdapterAndroid;
    private InputMethodManagerWrapper mInputMethodManagerWrapper;
    private AdapterInputConnection mInputConnection;
    private final ImeAdapterDelegate mViewEmbedder;

    private int mTextInputType = TextInputType.NONE;
    private int mTextInputFlags;

    /**
     * @param wrapper InputMethodManagerWrapper that should receive all the call directed to
     *                InputMethodManager.
     * @param embedder The view that is used for callbacks from ImeAdapter.
     */
    public ImeAdapter(InputMethodManagerWrapper wrapper, ImeAdapterDelegate embedder) {
        mInputMethodManagerWrapper = wrapper;
        mViewEmbedder = embedder;
    }

    /**
     * Default factory for AdapterInputConnection classes.
     */
    public static class AdapterInputConnectionFactory {
        public AdapterInputConnection get(View view, ImeAdapter imeAdapter,
                Editable editable, EditorInfo outAttrs) {
            return new AdapterInputConnection(view, imeAdapter, editable, outAttrs);
        }
    }

    /**
     * Overrides the InputMethodManagerWrapper that ImeAdapter uses to make calls to
     * InputMethodManager.
     * @param immw InputMethodManagerWrapper that should be used to call InputMethodManager.
     */
    @VisibleForTesting
    public void setInputMethodManagerWrapperForTest(InputMethodManagerWrapper immw) {
        mInputMethodManagerWrapper = immw;
    }

    /**
     * Should be only used by AdapterInputConnection.
     * @return InputMethodManagerWrapper that should receive all the calls directed to
     *         InputMethodManager.
     */
    InputMethodManagerWrapper getInputMethodManagerWrapper() {
        return mInputMethodManagerWrapper;
    }

    /**
     * Set the current active InputConnection when a new InputConnection is constructed.
     * @param inputConnection The input connection that is currently used with IME.
     */
    void setInputConnection(AdapterInputConnection inputConnection) {
        mInputConnection = inputConnection;
    }

    /**
     * Should be used only by AdapterInputConnection.
     * @return The input type of currently focused element.
     */
    int getTextInputType() {
        return mTextInputType;
    }

    /**
     * Should be used only by AdapterInputConnection.
     * @return The input flags of the currently focused element.
     */
    int getTextInputFlags() {
        return mTextInputFlags;
    }

    private static int getModifiers(int metaState) {
        int modifiers = 0;
        if ((metaState & KeyEvent.META_SHIFT_ON) != 0) {
            modifiers |= WebInputEventModifier.ShiftKey;
        }
        if ((metaState & KeyEvent.META_ALT_ON) != 0) {
            modifiers |= WebInputEventModifier.AltKey;
        }
        if ((metaState & KeyEvent.META_CTRL_ON) != 0) {
            modifiers |= WebInputEventModifier.ControlKey;
        }
        if ((metaState & KeyEvent.META_CAPS_LOCK_ON) != 0) {
            modifiers |= WebInputEventModifier.CapsLockOn;
        }
        if ((metaState & KeyEvent.META_NUM_LOCK_ON) != 0) {
            modifiers |= WebInputEventModifier.NumLockOn;
        }
        return modifiers;
    }

    /**
     * Shows or hides the keyboard based on passed parameters.
     * @param textInputType Text input type for the currently focused field in renderer.
     * @param textInputFlags Text input flags.
     * @param showIfNeeded Whether the keyboard should be shown if it is currently hidden.
     */
    public void updateKeyboardVisibility(int textInputType,
            int textInputFlags, boolean showIfNeeded) {
        Log.d(TAG, "updateKeyboardVisibility: type [%d->%d], flags [%d], show [%b], ",
                mTextInputType, textInputType, textInputFlags, showIfNeeded);
        // If current input type is none and showIfNeeded is false, IME should not be shown
        // and input type should remain as none.
        if (mTextInputType == TextInputType.NONE && !showIfNeeded) {
            return;
        }

        mTextInputFlags = textInputFlags;
        if (mTextInputType != textInputType) {
            mTextInputType = textInputType;
            // No need to restart if we are going to hide anyways.
            if (textInputType != TextInputType.NONE) {
                mInputMethodManagerWrapper.restartInput(mViewEmbedder.getAttachedView());
            }
        }

        // There is no API for us to get notified of user's dismissal of keyboard.
        // Therefore, we should try to show keyboard even when text input type hasn't changed.
        if (textInputType != TextInputType.NONE) {
            if (showIfNeeded) showSoftKeyboard();
        } else {
            hideKeyboard();
        }
    }

    /**
     * Attaches the imeAdapter to its native counterpart. This is needed to start forwarding
     * keyboard events to WebKit.
     * @param nativeImeAdapter The pointer to the native ImeAdapter object.
     */
    public void attach(long nativeImeAdapter) {
        if (mNativeImeAdapterAndroid == nativeImeAdapter) return;
        if (mNativeImeAdapterAndroid != 0) {
            nativeResetImeAdapter(mNativeImeAdapterAndroid);
        }
        if (nativeImeAdapter != 0) {
            nativeAttachImeAdapter(nativeImeAdapter);
        }
        mNativeImeAdapterAndroid = nativeImeAdapter;
    }

    /**
     * Show soft keyboard only if it is the current keyboard configuration.
     */
    public void showSoftKeyboard() {
        Log.d(TAG, "showSoftKeyboard");
        mInputMethodManagerWrapper.showSoftInput(
                mViewEmbedder.getAttachedView(), 0, mViewEmbedder.getNewShowKeyboardReceiver());
        if (mViewEmbedder.getAttachedView().getResources().getConfiguration().keyboard
                != Configuration.KEYBOARD_NOKEYS) {
            mViewEmbedder.onKeyboardBoundsUnchanged();
        }
    }

    /**
     * Hide soft keyboard.
     */
    public void hideKeyboard() {
        Log.d(TAG, "hideKeyboard");
        View view = mViewEmbedder.getAttachedView();
        if (mInputMethodManagerWrapper.isActive(view)) {
            // NOTE: we should not set ResultReceiver here. Otherwise, IMM will own ContentViewCore
            // and ImeAdapter even after input method goes away and result gets received.
            mInputMethodManagerWrapper.hideSoftInputFromWindow(view.getWindowToken(), 0, null);
        }
        if (mTextInputType == TextInputType.NONE && mInputConnection != null) {
            mInputConnection.restartInput();
        }
    }

    /**
     * Call this when keyboard configuration has changed.
     */
    public void onKeyboardConfigurationChanged() {
        Log.d(TAG, "onKeyboardConfigurationChanged: mTextInputType [%d]", mTextInputType);
        if (mTextInputType != TextInputType.NONE) {
            mInputMethodManagerWrapper.restartInput(mViewEmbedder.getAttachedView());
            // By default, we show soft keyboard on keyboard changes. This is useful
            // when the user switches from hardware keyboard to software keyboard.
            // TODO(changwan): check if we can skip this for hardware keyboard configurations.
            showSoftKeyboard();
        }
    }

    /**
     * Call this when view's focus has changed.
     * @param gainFocus True if we're gaining focus.
     */
    public void onViewFocusChanged(boolean gainFocus) {
        Log.d(TAG, "onViewFocusChanged: gainFocus [%b]", gainFocus);
        if (!gainFocus) hideKeyboard();
    }

    /**
     * @return Whether input can be shown on the current focus (e.g. on an input box
     *         or on a contenteditable).
     */
    public boolean canCreateInputConnection() {
        return mTextInputType != TextInputType.NONE;
    }

    @VisibleForTesting
    public void setInputTypeForTest(int textInputType) {
        mTextInputType = textInputType;
    }

    private static boolean isTextInputType(int type) {
        return type != TextInputType.NONE && !InputDialogContainer.isDialogInputType(type);
    }

    public boolean hasTextInputType() {
        return isTextInputType(mTextInputType);
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        Log.d(TAG, "dispatchKeyEvent: action [%d], keycode [%d]", event.getAction(),
                event.getKeyCode());
        if (mInputConnection != null) {
            return mInputConnection.sendKeyEvent(event);
        }
        return sendKeyEvent(event);
    }

    /**
     * @see BaseInputConnection#performContextMenuAction(int)
     */
    boolean performContextMenuAction(int id) {
        Log.d(TAG, "performContextMenuAction: id [%d]", id);
        return mViewEmbedder.performContextMenuAction(id);
    }

    boolean performEditorAction(int actionCode) {
        if (mNativeImeAdapterAndroid == 0) return false;
        if (actionCode == EditorInfo.IME_ACTION_NEXT) {
            sendSyntheticKeyPress(KeyEvent.KEYCODE_TAB,
                    KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                    | KeyEvent.FLAG_EDITOR_ACTION);
        } else {
            sendSyntheticKeyPress(KeyEvent.KEYCODE_ENTER,
                    KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE
                    | KeyEvent.FLAG_EDITOR_ACTION);
        }
        return true;
    }

    @VisibleForTesting
    protected void sendSyntheticKeyPress(int keyCode, int flags) {
        long eventTime = SystemClock.uptimeMillis();
        sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags));
        sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                flags));
    }

    boolean sendCompositionToNative(CharSequence text, int newCursorPosition, boolean isCommit) {
        if (mNativeImeAdapterAndroid == 0) return false;
        mViewEmbedder.onImeEvent();

        long timestampMs = SystemClock.uptimeMillis();
        nativeSendSyntheticKeyEvent(mNativeImeAdapterAndroid, WebInputEventType.RawKeyDown,
                timestampMs, COMPOSITION_KEY_CODE, 0, 0);

        if (isCommit) {
            nativeCommitText(mNativeImeAdapterAndroid, text.toString());
        } else {
            nativeSetComposingText(
                    mNativeImeAdapterAndroid, text, text.toString(), newCursorPosition);
        }

        nativeSendSyntheticKeyEvent(mNativeImeAdapterAndroid, WebInputEventType.KeyUp,
                timestampMs, COMPOSITION_KEY_CODE, 0, 0);
        return true;
    }

    @VisibleForTesting
    protected void finishComposingText() {
        if (mNativeImeAdapterAndroid == 0) return;
        nativeFinishComposingText(mNativeImeAdapterAndroid);
    }

    boolean sendKeyEvent(KeyEvent event) {
        if (mNativeImeAdapterAndroid == 0) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            // action == KeyEvent.ACTION_MULTIPLE
            // TODO(bulach): confirm the actual behavior. Apparently:
            // If event.getKeyCode() == KEYCODE_UNKNOWN, we can send a
            // composition key down (229) followed by a commit text with the
            // string from event.getUnicodeChars().
            // Otherwise, we'd need to send an event with a
            // WebInputEvent::IsAutoRepeat modifier. We also need to verify when
            // we receive ACTION_MULTIPLE: we may receive it after an ACTION_DOWN,
            // and if that's the case, we'll need to review when to send the Char
            // event.
            return false;
        }
        mViewEmbedder.onImeEvent();
        return nativeSendKeyEvent(mNativeImeAdapterAndroid, event, event.getAction(),
                getModifiers(event.getMetaState()), event.getEventTime(), event.getKeyCode(),
                             event.getScanCode(), /*isSystemKey=*/false, event.getUnicodeChar());
    }

    /**
     * Send a request to the native counterpart to delete a given range of characters.
     * @param beforeLength Number of characters to extend the selection by before the existing
     *                     selection.
     * @param afterLength Number of characters to extend the selection by after the existing
     *                    selection.
     * @return Whether the native counterpart of ImeAdapter received the call.
     */
    boolean deleteSurroundingText(int beforeLength, int afterLength) {
        mViewEmbedder.onImeEvent();
        if (mNativeImeAdapterAndroid == 0) return false;
        nativeSendSyntheticKeyEvent(mNativeImeAdapterAndroid, WebInputEventType.RawKeyDown,
                SystemClock.uptimeMillis(), COMPOSITION_KEY_CODE, 0, 0);
        nativeDeleteSurroundingText(mNativeImeAdapterAndroid, beforeLength, afterLength);
        nativeSendSyntheticKeyEvent(mNativeImeAdapterAndroid,
                WebInputEventType.KeyUp, SystemClock.uptimeMillis(), COMPOSITION_KEY_CODE, 0, 0);
        return true;
    }

    /**
     * Send a request to the native counterpart to set the selection to given range.
     * @param start Selection start index.
     * @param end Selection end index.
     * @return Whether the native counterpart of ImeAdapter received the call.
     */
    boolean setEditableSelectionOffsets(int start, int end) {
        if (mNativeImeAdapterAndroid == 0) return false;
        nativeSetEditableSelectionOffsets(mNativeImeAdapterAndroid, start, end);
        return true;
    }

    /**
     * Send a request to the native counterpart to set composing region to given indices.
     * @param start The start of the composition.
     * @param end The end of the composition.
     * @return Whether the native counterpart of ImeAdapter received the call.
     */
    boolean setComposingRegion(CharSequence text, int start, int end) {
        if (mNativeImeAdapterAndroid == 0) return false;
        nativeSetComposingRegion(mNativeImeAdapterAndroid, start, end);
        return true;
    }

    @CalledByNative
    private void focusedNodeChanged(boolean isEditable) {
        Log.d(TAG, "focusedNodeChanged: isEditable [%b]", isEditable);
        if (mTextInputType != TextInputType.NONE && mInputConnection != null && isEditable) {
            mInputConnection.restartInput();
        }
    }

    @CalledByNative
    private void populateUnderlinesFromSpans(CharSequence text, long underlines) {
        Log.d(TAG, "populateUnderlinesFromSpans: text [%s], underlines [%d]", text, underlines);
        if (!(text instanceof SpannableString)) return;

        SpannableString spannableString = ((SpannableString) text);
        CharacterStyle spans[] =
                spannableString.getSpans(0, text.length(), CharacterStyle.class);
        for (CharacterStyle span : spans) {
            if (span instanceof BackgroundColorSpan) {
                nativeAppendBackgroundColorSpan(underlines, spannableString.getSpanStart(span),
                        spannableString.getSpanEnd(span),
                        ((BackgroundColorSpan) span).getBackgroundColor());
            } else if (span instanceof UnderlineSpan) {
                nativeAppendUnderlineSpan(underlines, spannableString.getSpanStart(span),
                        spannableString.getSpanEnd(span));
            }
        }
    }

    @CalledByNative
    private void cancelComposition() {
        Log.d(TAG, "cancelComposition");
        if (mInputConnection != null) mInputConnection.restartInput();
    }

    @CalledByNative
    void detach() {
        Log.d(TAG, "detach");
        mNativeImeAdapterAndroid = 0;
    }

    private native boolean nativeSendSyntheticKeyEvent(long nativeImeAdapterAndroid,
            int eventType, long timestampMs, int keyCode, int modifiers, int unicodeChar);

    private native boolean nativeSendKeyEvent(long nativeImeAdapterAndroid, KeyEvent event,
            int action, int modifiers, long timestampMs, int keyCode, int scanCode,
            boolean isSystemKey, int unicodeChar);

    private static native void nativeAppendUnderlineSpan(long underlinePtr, int start, int end);

    private static native void nativeAppendBackgroundColorSpan(long underlinePtr, int start,
            int end, int backgroundColor);

    private native void nativeSetComposingText(long nativeImeAdapterAndroid, CharSequence text,
            String textStr, int newCursorPosition);

    private native void nativeCommitText(long nativeImeAdapterAndroid, String textStr);

    private native void nativeFinishComposingText(long nativeImeAdapterAndroid);

    private native void nativeAttachImeAdapter(long nativeImeAdapterAndroid);

    private native void nativeSetEditableSelectionOffsets(long nativeImeAdapterAndroid,
            int start, int end);

    private native void nativeSetComposingRegion(long nativeImeAdapterAndroid, int start, int end);

    private native void nativeDeleteSurroundingText(long nativeImeAdapterAndroid,
            int before, int after);

    private native void nativeResetImeAdapter(long nativeImeAdapterAndroid);
}
