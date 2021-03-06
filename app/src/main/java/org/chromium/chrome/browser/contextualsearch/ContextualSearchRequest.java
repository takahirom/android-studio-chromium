// Copyright 2015 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.contextualsearch;

import android.net.Uri;
import android.text.TextUtils;

import org.chromium.base.VisibleForTesting;
import org.chromium.chrome.browser.search_engines.TemplateUrlService;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

import javax.annotation.Nullable;


/**
 * Bundles a Search Request URL with a low-priority version of the URL, helps manage the
 * fall-back when the low-priority version fails, and tracks which one is in use.
 */
class ContextualSearchRequest {
    private final boolean mWasPrefetch;

    private Uri mLowPriorityUri;
    private Uri mNormalPriorityUri;

    private boolean mIsLowPriority;
    private boolean mHasFailedLowPriorityLoad;
    private boolean mIsTranslationForced;

    private static final String GWS_LOW_PRIORITY_SEARCH_PATH = "s";
    private static final String GWS_SEARCH_NO_SUGGESTIONS_PARAM = "sns";
    private static final String GWS_SEARCH_NO_SUGGESTIONS_PARAM_VALUE = "1";
    private static final String GWS_QUERY_PARAM = "q";
    private static final String CTXS_PARAM_PATTERN = "(ctxs=[^&]+)";
    private static final String CTXR_PARAM = "ctxr";
    @VisibleForTesting static final String TLITE_SOURCE_LANGUAGE_PARAM = "tlitesl";
    private static final String TLITE_TARGET_LANGUAGE_PARAM = "tlitetl";
    private static final String TLITE_QUERY_PARAM = "tlitetxt";

    /**
     * Creates a search request for the given search term without any alternate term and
     * for normal-priority loading capability only.
     * @param searchTerm The resolved search term.
     */
    ContextualSearchRequest(String searchTerm) {
        this(searchTerm, null, false);
    }

    /**
     * Creates a search request for the given search term with the given alternate term and
     * low-priority loading capability.
     * @param searchTerm The resolved search term.
     * @param alternateTerm The alternate search term.
     * @param isLowPriorityEnabled Whether the request can be made at a low priority.
     */
    ContextualSearchRequest(String searchTerm, @Nullable String alternateTerm,
            boolean isLowPriorityEnabled) {
        mWasPrefetch = isLowPriorityEnabled;
        mNormalPriorityUri = getUriTemplate(searchTerm, alternateTerm, false);
        if (isLowPriorityEnabled) {
            // TODO(donnd): Call TemplateURL once we have an API for 3rd-party providers.
            Uri baseLowPriorityUri = getUriTemplate(searchTerm, alternateTerm, true);
            mLowPriorityUri = makeLowPriorityUri(baseLowPriorityUri);
            mIsLowPriority = true;
        } else {
            mIsLowPriority = false;
            mLowPriorityUri = null;
        }
    }

    /**
     * Sets an indicator that the normal-priority URL should be used for this search request.
     */
    void setNormalPriority() {
        mIsLowPriority = false;
    }

    /**
     * @return whether the low priority URL is being used.
     */
    boolean isUsingLowPriority() {
        return mIsLowPriority;
    }

    /**
     * @return whether this request started as a prefetch request.
     */
    boolean wasPrefetch() {
        return mWasPrefetch;
    }

    /**
     * Sets that this search request has failed.
     */
    void setHasFailed() {
        mHasFailedLowPriorityLoad = true;
    }

    /**
     * @return whether the load has failed for this search request or not.
     */
    boolean getHasFailed() {
        return mHasFailedLowPriorityLoad;
    }

    /**
     * Gets the search URL for this request.
     * @return either the low-priority or normal-priority URL for this search request.
     */
    String getSearchUrl() {
        if (mIsLowPriority && mLowPriorityUri != null) {
            return mLowPriorityUri.toString();
        } else {
            return mNormalPriorityUri.toString();
        }
    }

    /**
     * Returns whether the given URL is the current Contextual Search URL.
     * @param url The given URL.
     * @return Whether it is the current Contextual Search URL.
     */
    boolean isContextualSearchUrl(String url) {
        return url.equals(getSearchUrl());
    }

    /**
     * Returns the formatted Search URL, replacing the ctxs param with the ctxr param, so that
     * the SearchBox will becomes visible, while preserving the Answers Mode.
     *
     * @return The formatted Search URL.
     */
    String getSearchUrlForPromotion() {
        String searchUrl = getSearchUrl();

        URL url;
        try {
            url = new URL(searchUrl.replaceAll(CTXS_PARAM_PATTERN, CTXR_PARAM));
        } catch (MalformedURLException e) {
            url = null;
        }

        return url != null ? url.toString() : null;
    }

    /**
     * Adds translation parameters.
     * @param sourceLanguage The language of the original search term.
     * @param targetLanguage The language the that the user prefers.
     */
    void forceTranslation(String sourceLanguage, String targetLanguage) {
        mIsTranslationForced = true;
        if (mLowPriorityUri != null) {
            mLowPriorityUri = makeTranslateUri(mLowPriorityUri, sourceLanguage, targetLanguage);
        }
        mNormalPriorityUri = makeTranslateUri(mNormalPriorityUri, sourceLanguage, targetLanguage);
    }

    /**
     * @return Whether translation was forced for this request.
     */
    @VisibleForTesting
    boolean isTranslationForced() {
        return mIsTranslationForced;
    }

    /**
     * Uses TemplateUrlService to generate the url for the given query
     * {@link String} for {@code query} with the contextual search version param set.
     * @param query The search term to use as the main query in the returned search url.
     * @param alternateTerm The alternate search term to use as an alternate suggestion.
     * @param shouldPrefetch Whether the returned url should include a prefetch parameter.
     * @return A {@link Uri} that contains the url of the default search engine with
     *         {@code query} and {@code alternateTerm} inserted as parameters and contextual
     *         search and prefetch parameters conditionally set.
     */
    private Uri getUriTemplate(String query, @Nullable String alternateTerm,
            boolean shouldPrefetch) {
        return Uri.parse(TemplateUrlService.getInstance().getUrlForContextualSearchQuery(
                query, alternateTerm, shouldPrefetch));
    }

    /**
     * @return a low-priority {@code Uri} from the given base {@code Uri}.
     */
    private Uri makeLowPriorityUri(Uri baseUri) {
        return baseUri.buildUpon()
                .path(GWS_LOW_PRIORITY_SEARCH_PATH)
                .appendQueryParameter(
                        GWS_SEARCH_NO_SUGGESTIONS_PARAM, GWS_SEARCH_NO_SUGGESTIONS_PARAM_VALUE)
                .build();
    }

    /**
     * Makes the given {@code Uri} into a similar Uri that triggers a Translate one-box.
     * @param baseUri The base Uri to build off of.
     * @param sourceLanguage The language of the original search term.
     * @param targetLanguage The language the that the user prefers.
     * @return A {@link Uri} that has additional parameters for Translate appropriately set.
     */
    private Uri makeTranslateUri(Uri baseUri, String sourceLanguage, String targetLanguage) {
        // TODO(donnd): update to work for non-English.  See also getTranslateQuery.
        if (!TextUtils.equals(targetLanguage, Locale.ENGLISH.getLanguage())) return baseUri;
        Uri resultUri = baseUri;
        if (!sourceLanguage.isEmpty() || !targetLanguage.isEmpty()) {
            // We must replace the q= param, and there seems to be no good way other than clearing
            // all the query params and adding them all back in, changing q=.
            Uri.Builder builder = baseUri.buildUpon().clearQuery();
            String query = null;
            for (String param : baseUri.getQueryParameterNames()) {
                String value = baseUri.getQueryParameter(param);
                if (TextUtils.equals(param, GWS_QUERY_PARAM)) {
                    query = value;
                    value = getTranslateQuery();
                }
                builder.appendQueryParameter(param, value);
            }
            if (!sourceLanguage.isEmpty()) {
                builder.appendQueryParameter(TLITE_SOURCE_LANGUAGE_PARAM, sourceLanguage);
            }
            if (!targetLanguage.isEmpty()) {
                builder.appendQueryParameter(TLITE_TARGET_LANGUAGE_PARAM, targetLanguage);
            }
            builder.appendQueryParameter(TLITE_QUERY_PARAM, query);
            resultUri = builder.build();
        }
        return resultUri;
    }

    /**
     * TODO(donnd): This translate API is evolving.  Update this code!
     * TODO(donnd): As of Oct 2015 this will only work on production GWS to translate into English.
     */
    private String getTranslateQuery() {
        return "Translate";
    }
}
