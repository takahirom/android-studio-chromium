// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.infobar;

import android.content.Context;

import org.chromium.chrome.R;

/**
 * Options panel shown in the after translate infobar.
 */
public class TranslateAlwaysPanel implements TranslateSubPanel {

    private final TranslateOptions mOptions;
    private final SubPanelListener mListener;

    TranslateAlwaysPanel(SubPanelListener listener, TranslateOptions options) {
        mOptions = options;
        mListener = listener;
    }

    @Override
    public void createContent(Context context, InfoBarLayout layout) {
        layout.setMessage(context.getString(
                R.string.translate_infobar_translation_done, mOptions.targetLanguage()));

        if (!mOptions.triggeredFromMenu()) {
            layout.setCustomContent(createAlwaysToggle(context, mOptions));
        }

        layout.setButtons(context.getString(R.string.translate_button_done),
                context.getString(R.string.translate_show_original));
    }

    @Override
    public void onButtonClicked(boolean primary) {
        if (primary) {
            mListener.onPanelClosed(ActionType.NONE);
        } else {
            mListener.onPanelClosed(ActionType.TRANSLATE_SHOW_ORIGINAL);
        }
    }

    /**
     * Creates a toggle that shows the current status of the "Always translate <language>" option.
     */
    static InfoBarControlLayout createAlwaysToggle(Context context, TranslateOptions options) {
        InfoBarControlLayout controlLayout = new InfoBarControlLayout(context);
        controlLayout.addSwitch(0,
                context.getString(R.string.translate_always_text, options.sourceLanguage()),
                R.id.translate_infobar_always_toggle,
                options.alwaysTranslateLanguageState());
        return controlLayout;
    }
}
