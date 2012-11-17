/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.inputmethod.research;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.JsonWriter;
import android.util.Log;
import android.view.MotionEvent;
import android.view.inputmethod.CompletionInfo;

import com.android.inputmethod.keyboard.Key;
import com.android.inputmethod.latin.SuggestedWords;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;
import com.android.inputmethod.latin.Utils;
import com.android.inputmethod.latin.define.ProductionFlag;
import com.android.inputmethod.research.ResearchLogger.LogStatement;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A group of log statements related to each other.
 *
 * A LogUnit is collection of LogStatements, each of which is generated by at a particular point
 * in the code.  (There is no LogStatement class; the data is stored across the instance variables
 * here.)  A single LogUnit's statements can correspond to all the calls made while in the same
 * composing region, or all the calls between committing the last composing region, and the first
 * character of the next composing region.
 *
 * Individual statements in a log may be marked as potentially private.  If so, then they are only
 * published to a ResearchLog if the ResearchLogger determines that publishing the entire LogUnit
 * will not violate the user's privacy.  Checks for this may include whether other LogUnits have
 * been published recently, or whether the LogUnit contains numbers, etc.
 */
/* package */ class LogUnit {
    private static final String TAG = LogUnit.class.getSimpleName();
    private static final boolean DEBUG = false && ProductionFlag.IS_EXPERIMENTAL_DEBUG;
    private final ArrayList<LogStatement> mLogStatementList;
    private final ArrayList<Object[]> mValuesList;
    // Assume that mTimeList is sorted in increasing order.  Do not insert null values into
    // mTimeList.
    private final ArrayList<Long> mTimeList;
    // Word that this LogUnit generates.  Should be null if the LogUnit does not generate a genuine
    // word (i.e. separators alone do not count as a word).  Should never be empty.
    private String mWord;
    private boolean mMayContainDigit;
    private boolean mIsPartOfMegaword;
    private boolean mContainsCorrection;

    // mCorrectionType indicates whether the word was corrected at all, and if so, whether it was
    // to a different word or just a "typo" correction.  It is considered a "typo" if the final
    // word was listed in the suggestions available the first time the word was gestured or
    // tapped.
    private int mCorrectionType;
    public static final int CORRECTIONTYPE_NO_CORRECTION = 0;
    public static final int CORRECTIONTYPE_CORRECTION = 1;
    public static final int CORRECTIONTYPE_DIFFERENT_WORD = 2;
    public static final int CORRECTIONTYPE_TYPO = 3;

    private SuggestedWords mSuggestedWords;

    public LogUnit() {
        mLogStatementList = new ArrayList<LogStatement>();
        mValuesList = new ArrayList<Object[]>();
        mTimeList = new ArrayList<Long>();
        mIsPartOfMegaword = false;
        mCorrectionType = CORRECTIONTYPE_NO_CORRECTION;
        mSuggestedWords = null;
    }

    private LogUnit(final ArrayList<LogStatement> logStatementList,
            final ArrayList<Object[]> valuesList,
            final ArrayList<Long> timeList,
            final boolean isPartOfMegaword) {
        mLogStatementList = logStatementList;
        mValuesList = valuesList;
        mTimeList = timeList;
        mIsPartOfMegaword = isPartOfMegaword;
        mCorrectionType = CORRECTIONTYPE_NO_CORRECTION;
        mSuggestedWords = null;
    }

    private static final Object[] NULL_VALUES = new Object[0];
    /**
     * Adds a new log statement.  The time parameter in successive calls to this method must be
     * monotonically increasing, or splitByTime() will not work.
     */
    public void addLogStatement(final LogStatement logStatement, final long time,
            Object... values) {
        if (values == null) {
            values = NULL_VALUES;
        }
        mLogStatementList.add(logStatement);
        mValuesList.add(values);
        mTimeList.add(time);
    }

    /**
     * Publish the contents of this LogUnit to researchLog.
     */
    public synchronized void publishTo(final ResearchLog researchLog,
            final boolean canIncludePrivateData) {
        // Prepare debugging output if necessary
        final StringWriter debugStringWriter;
        final JsonWriter debugJsonWriter;
        if (DEBUG) {
            debugStringWriter = new StringWriter();
            debugJsonWriter = new JsonWriter(debugStringWriter);
            debugJsonWriter.setIndent("  ");
            try {
                debugJsonWriter.beginArray();
            } catch (IOException e) {
                Log.e(TAG, "Could not open array in JsonWriter", e);
            }
        } else {
            debugStringWriter = null;
            debugJsonWriter = null;
        }
        // Write out any logStatement that passes the privacy filter.
        final int size = mLogStatementList.size();
        if (size != 0) {
            // Note that jsonWriter is only set to a non-null value if the logUnit start text is
            // output and at least one logStatement is output.
            JsonWriter jsonWriter = null;
            for (int i = 0; i < size; i++) {
                final LogStatement logStatement = mLogStatementList.get(i);
                if (!canIncludePrivateData && logStatement.mIsPotentiallyPrivate) {
                    continue;
                }
                if (mIsPartOfMegaword && logStatement.mIsPotentiallyRevealing) {
                    continue;
                }
                // Only retrieve the jsonWriter if we need to.  If we don't get this far, then
                // researchLog.getValidJsonWriterLocked() will not ever be called, and the file
                // will not have been opened for writing.
                if (jsonWriter == null) {
                    jsonWriter = researchLog.getValidJsonWriterLocked();
                    outputLogUnitStart(jsonWriter, canIncludePrivateData);
                }
                outputLogStatementToLocked(jsonWriter, mLogStatementList.get(i), mValuesList.get(i),
                        mTimeList.get(i));
                if (DEBUG) {
                    outputLogStatementToLocked(debugJsonWriter, mLogStatementList.get(i),
                            mValuesList.get(i), mTimeList.get(i));
                }
            }
            if (jsonWriter != null) {
                // We must have called logUnitStart earlier, so emit a logUnitStop.
                outputLogUnitStop(jsonWriter);
            }
        }
        if (DEBUG) {
            try {
                debugJsonWriter.endArray();
                debugJsonWriter.flush();
            } catch (IOException e) {
                Log.e(TAG, "Could not close array in JsonWriter", e);
            }
            final String bigString = debugStringWriter.getBuffer().toString();
            final String[] lines = bigString.split("\n");
            for (String line : lines) {
                Log.d(TAG, line);
            }
        }
    }

    private static final String CURRENT_TIME_KEY = "_ct";
    private static final String UPTIME_KEY = "_ut";
    private static final String EVENT_TYPE_KEY = "_ty";
    private static final String WORD_KEY = "_wo";
    private static final String CORRECTION_TYPE_KEY = "_corType";
    private static final String LOG_UNIT_BEGIN_KEY = "logUnitStart";
    private static final String LOG_UNIT_END_KEY = "logUnitEnd";

    private void outputLogUnitStart(final JsonWriter jsonWriter,
            final boolean canIncludePrivateData) {
        try {
            jsonWriter.beginObject();
            jsonWriter.name(CURRENT_TIME_KEY).value(System.currentTimeMillis());
            if (canIncludePrivateData) {
                jsonWriter.name(WORD_KEY).value(getWord());
                jsonWriter.name(CORRECTION_TYPE_KEY).value(getCorrectionType());
            }
            jsonWriter.name(EVENT_TYPE_KEY).value(LOG_UNIT_BEGIN_KEY);
            jsonWriter.endObject();
        } catch (IOException e) {
            e.printStackTrace();
            Log.w(TAG, "Error in JsonWriter; cannot write LogUnitStart");
        }
    }

    private void outputLogUnitStop(final JsonWriter jsonWriter) {
        try {
            jsonWriter.beginObject();
            jsonWriter.name(CURRENT_TIME_KEY).value(System.currentTimeMillis());
            jsonWriter.name(EVENT_TYPE_KEY).value(LOG_UNIT_END_KEY);
            jsonWriter.endObject();
        } catch (IOException e) {
            e.printStackTrace();
            Log.w(TAG, "Error in JsonWriter; cannot write LogUnitStop");
        }
    }

    /**
     * Write the logStatement and its contents out through jsonWriter.
     *
     * Note that this method is not thread safe for the same jsonWriter.  Callers must ensure
     * thread safety.
     */
    private boolean outputLogStatementToLocked(final JsonWriter jsonWriter,
            final LogStatement logStatement, final Object[] values, final Long time) {
        if (DEBUG) {
            if (logStatement.mKeys.length != values.length) {
                Log.d(TAG, "Key and Value list sizes do not match. " + logStatement.mName);
            }
        }
        try {
            jsonWriter.beginObject();
            jsonWriter.name(CURRENT_TIME_KEY).value(System.currentTimeMillis());
            jsonWriter.name(UPTIME_KEY).value(time);
            jsonWriter.name(EVENT_TYPE_KEY).value(logStatement.mName);
            final String[] keys = logStatement.mKeys;
            final int length = values.length;
            for (int i = 0; i < length; i++) {
                jsonWriter.name(keys[i]);
                final Object value = values[i];
                if (value instanceof CharSequence) {
                    jsonWriter.value(value.toString());
                } else if (value instanceof Number) {
                    jsonWriter.value((Number) value);
                } else if (value instanceof Boolean) {
                    jsonWriter.value((Boolean) value);
                } else if (value instanceof CompletionInfo[]) {
                    JsonUtils.writeJson((CompletionInfo[]) value, jsonWriter);
                } else if (value instanceof SharedPreferences) {
                    JsonUtils.writeJson((SharedPreferences) value, jsonWriter);
                } else if (value instanceof Key[]) {
                    JsonUtils.writeJson((Key[]) value, jsonWriter);
                } else if (value instanceof SuggestedWords) {
                    JsonUtils.writeJson((SuggestedWords) value, jsonWriter);
                } else if (value instanceof MotionEvent) {
                    JsonUtils.writeJson((MotionEvent) value, jsonWriter);
                } else if (value == null) {
                    jsonWriter.nullValue();
                } else {
                    Log.w(TAG, "Unrecognized type to be logged: " +
                            (value == null ? "<null>" : value.getClass().getName()));
                    jsonWriter.nullValue();
                }
            }
            jsonWriter.endObject();
        } catch (IOException e) {
            e.printStackTrace();
            Log.w(TAG, "Error in JsonWriter; skipping LogStatement");
            return false;
        }
        return true;
    }

    /**
     * Mark the current logUnit as containing data to generate {@code word}.
     *
     * If {@code setWord()} was previously called for this LogUnit, then the method will try to
     * determine what kind of correction it is, and update its internal state of the correctionType
     * accordingly.
     *
     * @param word The word this LogUnit generates.  Caller should not pass null or the empty
     * string.
     */
    public void setWord(final String word) {
        if (hasWord()) {
            // The word was already set once, and it is now being changed.  See if the new word
            // is close to the old word.  If so, then the change is probably a typo correction.
            // If not, the user may have decided to enter a different word, so flag it.
            if (mSuggestedWords != null) {
                if (isInSuggestedWords(word, mSuggestedWords)) {
                    mCorrectionType = CORRECTIONTYPE_TYPO;
                } else {
                    mCorrectionType = CORRECTIONTYPE_DIFFERENT_WORD;
                }
            } else {
                // No suggested words, so it's not clear whether it's a typo or different word.
                // Mark it as a generic correction.
                mCorrectionType = CORRECTIONTYPE_CORRECTION;
            }
        }
        mWord = word;
    }

    public String getWord() {
        return mWord;
    }

    public boolean hasWord() {
        return mWord != null && !TextUtils.isEmpty(mWord.trim());
    }

    public void setMayContainDigit() {
        mMayContainDigit = true;
    }

    public boolean mayContainDigit() {
        return mMayContainDigit;
    }

    public void setContainsCorrection() {
        mContainsCorrection = true;
    }

    public boolean containsCorrection() {
        return mContainsCorrection;
    }

    public void setCorrectionType(final int correctionType) {
        mCorrectionType = correctionType;
    }

    public int getCorrectionType() {
        return mCorrectionType;
    }

    public boolean isEmpty() {
        return mLogStatementList.isEmpty();
    }

    /**
     * Split this logUnit, with all events before maxTime staying in the current logUnit, and all
     * events after maxTime going into a new LogUnit that is returned.
     */
    public LogUnit splitByTime(final long maxTime) {
        // Assume that mTimeList is in sorted order.
        final int length = mTimeList.size();
        // TODO: find time by binary search, e.g. using Collections#binarySearch()
        for (int index = 0; index < length; index++) {
            if (mTimeList.get(index) > maxTime) {
                final List<LogStatement> laterLogStatements =
                        mLogStatementList.subList(index, length);
                final List<Object[]> laterValues = mValuesList.subList(index, length);
                final List<Long> laterTimes = mTimeList.subList(index, length);

                // Create the LogUnit containing the later logStatements and associated data.
                final LogUnit newLogUnit = new LogUnit(
                        new ArrayList<LogStatement>(laterLogStatements),
                        new ArrayList<Object[]>(laterValues),
                        new ArrayList<Long>(laterTimes),
                        true /* isPartOfMegaword */);
                newLogUnit.mWord = null;
                newLogUnit.mMayContainDigit = mMayContainDigit;
                newLogUnit.mContainsCorrection = mContainsCorrection;

                // Purge the logStatements and associated data from this LogUnit.
                laterLogStatements.clear();
                laterValues.clear();
                laterTimes.clear();
                mIsPartOfMegaword = true;

                return newLogUnit;
            }
        }
        return new LogUnit();
    }

    public void append(final LogUnit logUnit) {
        mLogStatementList.addAll(logUnit.mLogStatementList);
        mValuesList.addAll(logUnit.mValuesList);
        mTimeList.addAll(logUnit.mTimeList);
        mWord = null;
        if (logUnit.mWord != null) {
            setWord(logUnit.mWord);
        }
        mMayContainDigit = mMayContainDigit || logUnit.mMayContainDigit;
        mContainsCorrection = mContainsCorrection || logUnit.mContainsCorrection;
        mIsPartOfMegaword = false;
    }

    public SuggestedWords getSuggestions() {
        return mSuggestedWords;
    }

    /**
     * Initialize the suggestions.
     *
     * Once set to a non-null value, the suggestions may not be changed again.  This is to keep
     * track of the list of words that are close to the user's initial effort to type the word.
     * Only words that are close to the initial effort are considered typo corrections.
     */
    public void initializeSuggestions(final SuggestedWords suggestedWords) {
        if (mSuggestedWords == null) {
            mSuggestedWords = suggestedWords;
        }
    }

    private static boolean isInSuggestedWords(final String queryWord,
            final SuggestedWords suggestedWords) {
        if (TextUtils.isEmpty(queryWord)) {
            return false;
        }
        final int size = suggestedWords.size();
        for (int i = 0; i < size; i++) {
            final SuggestedWordInfo wordInfo = suggestedWords.getInfo(i);
            if (queryWord.equals(wordInfo.mWord)) {
                return true;
            }
        }
        return false;
    }
}
