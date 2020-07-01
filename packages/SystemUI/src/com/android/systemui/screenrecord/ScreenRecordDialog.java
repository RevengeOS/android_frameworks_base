/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_SCREENSHOT;
import static android.provider.Settings.System.SCREENRECORD_AUDIO_SOURCE;
import static android.provider.Settings.System.SCREENRECORD_SHOW_TAPS;
import static android.provider.Settings.System.SCREENRECORD_LOW_QUALITY;
import static android.provider.Settings.System.SCREENRECORD_CAPTURE_PROTECTED;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Activity to select screen recording options
 */
public class ScreenRecordDialog extends Activity {
    private static final String TAG = "ScreenRecord";

    private static final int REQUEST_CODE_PERMISSIONS = 201;
    private static final int REQUEST_CODE_PERMISSIONS_AUDIO = 202;

    private static final int REQUEST_CODE_VIDEO = 301;
    private static final int REQUEST_CODE_VIDEO_TAPS = 302;
    private static final int REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED = 303;
    private static final int REQUEST_CODE_VIDEO_CAPTURE_PROTECTED = 304;
    private static final int REQUEST_CODE_VIDEO_LOW = 305;
    private static final int REQUEST_CODE_VIDEO_CAPTURE_PROTECTED_LOW = 306;
    private static final int REQUEST_CODE_VIDEO_TAPS_LOW = 307;
    private static final int REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED_LOW = 308;

    private static final int REQUEST_CODE_VIDEO_AUDIO = 401;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS = 402;
    private static final int REQUEST_CODE_VIDEO_AUDIO_CAPTURE_PROTECTED = 403;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED = 404;
    private static final int REQUEST_CODE_VIDEO_AUDIO_LOW = 405;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS_LOW = 406;
    private static final int REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED_LOW = 407;
    private static final int REQUEST_CODE_VIDEO_AUDIO_CAPTURE_PROTECTED_LOW = 408;

    private boolean mUseAudio;
    private int mAudioSource;
    private boolean mShowTaps;
    private boolean mLowQuality;
    private boolean mCaptureProtected;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_record_dialog);

        Window window = getWindow();
        assert window != null;

        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.BOTTOM);

        final Switch tapsSwitch = findViewById(R.id.switch_taps);
        final Switch qualitySwitch = findViewById(R.id.switch_low_quality);
        final Switch captureProtected = findViewById(R.id.switch_capture_protected);

        final Spinner audioOptions = findViewById(R.id.audio_options);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.screen_record_audio_choices, R.layout.screenrecord_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioOptions.setAdapter(adapter);
        mAudioSource = Settings.System.getIntForUser(getContentResolver(),
                SCREENRECORD_AUDIO_SOURCE, 0, UserHandle.USER_CURRENT);
        audioOptions.setSelection(mAudioSource);
        audioOptions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mAudioSource = position;
                mUseAudio = position > 0;
                Settings.System.putIntForUser(getContentResolver(),
                        SCREENRECORD_AUDIO_SOURCE, position, UserHandle.USER_CURRENT);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        initialCheckSwitch(tapsSwitch, SCREENRECORD_SHOW_TAPS);
        initialCheckSwitch(qualitySwitch, SCREENRECORD_LOW_QUALITY);
        initialCheckSwitch(captureProtected, SCREENRECORD_CAPTURE_PROTECTED);

        setSwitchListener(tapsSwitch, SCREENRECORD_SHOW_TAPS);
        setSwitchListener(qualitySwitch, SCREENRECORD_LOW_QUALITY);
        setSwitchListener(captureProtected, SCREENRECORD_CAPTURE_PROTECTED);

        final Button recordButton = findViewById(R.id.record_button);
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mShowTaps = tapsSwitch.isChecked();
                mLowQuality = qualitySwitch.isChecked();
                mCaptureProtected = captureProtected.isChecked();
                Log.d(TAG, "Record button clicked: audio " + mAudioSource + ", taps " + mShowTaps + ", quality " + mLowQuality + ", capture protected " + mCaptureProtected);

                if (mUseAudio && ScreenRecordDialog.this.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Requesting permission for audio");
                    ScreenRecordDialog.this.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                            REQUEST_CODE_PERMISSIONS_AUDIO);
                } else {
                    ScreenRecordDialog.this.requestScreenCapture();
                }
            }
        });
        final Button cancelButton = findViewById(R.id.cancel_button);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ScreenRecordDialog.this.finish();
            }
        });

        try {
            ActivityManagerWrapper.getInstance().closeSystemWindows(
                    SYSTEM_DIALOG_REASON_SCREENSHOT).get(
                    3000/*timeout*/, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {}
    }

    private void initialCheckSwitch(Switch sw, String setting) {
        sw.setChecked(
                Settings.System.getIntForUser(this.getContentResolver(),
                        setting, 0, UserHandle.USER_CURRENT) == 1);
    }

    private void setSwitchListener(Switch sw, String setting) {
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Settings.System.putIntForUser(this.getContentResolver(),
                    setting, isChecked ? 1 : 0, UserHandle.USER_CURRENT);
        });
    }

    private void requestScreenCapture() {
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(
                Context.MEDIA_PROJECTION_SERVICE);
        assert mediaProjectionManager != null;
        Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();

        if (mLowQuality) {
            if (mUseAudio) {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mCaptureProtected ? REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED_LOW : REQUEST_CODE_VIDEO_AUDIO_TAPS_LOW)
                        : (mCaptureProtected ? REQUEST_CODE_VIDEO_AUDIO_CAPTURE_PROTECTED_LOW : REQUEST_CODE_VIDEO_AUDIO_LOW));
            } else {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mCaptureProtected ? REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED_LOW : REQUEST_CODE_VIDEO_TAPS_LOW)
                        : (mCaptureProtected ? REQUEST_CODE_VIDEO_CAPTURE_PROTECTED_LOW : REQUEST_CODE_VIDEO_LOW));
            }
        } else {
            if (mUseAudio) {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mCaptureProtected ? REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED : REQUEST_CODE_VIDEO_AUDIO_TAPS)
                        : (mCaptureProtected ? REQUEST_CODE_VIDEO_AUDIO_CAPTURE_PROTECTED : REQUEST_CODE_VIDEO_AUDIO));
            } else {
                startActivityForResult(permissionIntent,
                        mShowTaps ? (mCaptureProtected ? REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED : REQUEST_CODE_VIDEO_TAPS)
                        : (mCaptureProtected ? REQUEST_CODE_VIDEO_CAPTURE_PROTECTED : REQUEST_CODE_VIDEO));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mShowTaps = requestCode == REQUEST_CODE_VIDEO_TAPS
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS
                || requestCode == REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED
                || requestCode == REQUEST_CODE_VIDEO_TAPS_LOW
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_LOW
                || requestCode == REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED_LOW
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED_LOW;
        mCaptureProtected = requestCode == REQUEST_CODE_VIDEO_AUDIO_CAPTURE_PROTECTED
                || requestCode == REQUEST_CODE_VIDEO_CAPTURE_PROTECTED
                || requestCode == REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_CAPTURE_PROTECTED_LOW
                || requestCode == REQUEST_CODE_VIDEO_CAPTURE_PROTECTED_LOW
                || requestCode == REQUEST_CODE_VIDEO_TAPS_CAPTURE_PROTECTED_LOW
                || requestCode == REQUEST_CODE_VIDEO_AUDIO_TAPS_CAPTURE_PROTECTED_LOW;
        mLowQuality = ((requestCode > 304 && requestCode < 309)
                || (requestCode > 404 && requestCode < 409));
        switch (requestCode) {
            case REQUEST_CODE_PERMISSIONS:
                int permission = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                if (permission != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                            getResources().getString(R.string.screenrecord_permission_error),
                            Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    requestScreenCapture();
                }
                break;
            case REQUEST_CODE_PERMISSIONS_AUDIO:
                int videoPermission = checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE);
                int audioPermission = checkSelfPermission(Manifest.permission.RECORD_AUDIO);
                if (videoPermission != PackageManager.PERMISSION_GRANTED
                        || audioPermission != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                            getResources().getString(R.string.screenrecord_permission_error),
                            Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    requestScreenCapture();
                }
                break;
            default:
                if (resultCode == RESULT_OK) {
                    mUseAudio = requestCode > 400 && requestCode < 409;
                    if (mUseAudio == false) {
                        mAudioSource = 0;
                    }
                    startForegroundService(
                            RecordingService.getStartIntent(this, resultCode, data, mAudioSource,
                                    mShowTaps, mLowQuality, mCaptureProtected));
                } else {
                    Toast.makeText(this,
                            getResources().getString(R.string.screenrecord_permission_error),
                            Toast.LENGTH_SHORT).show();
                }
                finish();
        }
    }
}
