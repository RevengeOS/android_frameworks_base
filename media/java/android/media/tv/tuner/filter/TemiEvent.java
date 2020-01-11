/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.filter;

import android.annotation.NonNull;
import android.media.tv.tuner.Tuner.Filter;

/**
 * Filter event sent from {@link Filter} objects for Timed External Media Information (TEMI) data.
 *
 * @hide
 */
public class TemiEvent extends FilterEvent {
    private final long mPts;
    private final byte mDescrTag;
    private final byte[] mDescrData;

    // This constructor is used by JNI code only
    private TemiEvent(long pts, byte descrTag, byte[] descrData) {
        mPts = pts;
        mDescrTag = descrTag;
        mDescrData = descrData;
    }


    /**
     * Gets PTS (Presentation Time Stamp) for audio or video frame.
     */
    public long getPts() {
        return mPts;
    }

    /**
     * Gets TEMI descriptor tag.
     */
    public byte getDescriptorTag() {
        return mDescrTag;
    }

    /**
     * Gets TEMI descriptor.
     */
    @NonNull
    public byte[] getDescriptorData() {
        return mDescrData;
    }
}
