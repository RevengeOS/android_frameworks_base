/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.AlarmClock;
import android.support.annotation.VisibleForTesting;
import android.widget.FrameLayout;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.statusbar.policy.DateView;

import com.android.internal.util.weather.WeatherClient;

import java.util.Locale;
import java.util.Objects;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, WeatherClient.WeatherObserver {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    public static final int MAX_TOOLTIP_SHOWN_COUNT = 3;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;

    private View mSystemIconsView;
    private BatteryMeterView mBatteryMeterView;
    private Clock mClockView;
    private DateView mDateView;
    private ImageView mWeatherIcon;
    private TextView mWeatherTextView;

    private WeatherClient mWeatherClient;
    private WeatherClient.WeatherInfo mWeatherInfo;
    private WeatherSettingsObserver mWeatherSettingsObserver;
    private boolean useMetricUnit;

    protected ContentResolver mContentResolver;

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContentResolver = context.getContentResolver();
        mWeatherSettingsObserver = new WeatherSettingsObserver(new Handler());
        mWeatherSettingsObserver.observe();
        mWeatherSettingsObserver.updateWeatherUnit();
        mWeatherClient = new WeatherClient(context);
        mWeatherClient.addObserver(this, true /*withQuery*/);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer);

        updateResources();

        mBatteryMeterView = findViewById(R.id.battery);
        mBatteryMeterView.setIsQuickSbHeaderOrKeyguard(true);
        mBatteryMeterView.setOnClickListener(this);
        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mDateView = findViewById(R.id.date);
        mDateView.setOnClickListener(this);
        mWeatherIcon = findViewById(R.id.weather_icon);
        mWeatherTextView = findViewById(R.id.weather_text);

        updateExtendedStatusBarTint(mContext);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        Resources resources = mContext.getResources();

        // Update height for a few views, especially due to landscape mode restricting space.
        mSystemIconsView.getLayoutParams().height = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.extended_status_bar_height);
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mQsDisabled) {
            lp.height = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.extended_status_bar_height);
        } else {
            lp.height = Math.max(getMinimumHeight(),
                    resources.getDimensionPixelSize(
                            com.android.internal.R.dimen.revenge_quick_qs_total_height));
        }

        setLayoutParams(lp);
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        updateResources();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(StatusBarIconController.class).addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        Pair<Integer, Integer> padding = PhoneStatusBarView.cornerCutoutMargins(
                insets.getDisplayCutout(), getDisplay());
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start), 0,
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end), 0);
        } else {
            mSystemIconsView.setPadding(padding.first, 0, padding.second, 0);

        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        Dependency.get(StatusBarIconController.class).removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS),0);
        } else if (v == mBatteryMeterView) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    Intent.ACTION_POWER_USAGE_SUMMARY),0);
        } else if (v == mDateView) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),0);
        }
    }

    public void updateEverything() {
        post(() -> setClickable(false));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanel(mQsPanel);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    public static float getColorIntensity(@ColorInt int color) {
        return color == Color.WHITE ? 0 : 1;
    }

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v == mSystemIconsView || v == mHeaderQsPanel) {
                continue;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = sideMargins;
            lp.rightMargin = sideMargins;
        }
    }

    private boolean updateWeatherStatus() {
        if (mWeatherInfo == null || mWeatherInfo.getStatus() != WeatherClient.WEATHER_UPDATE_SUCCESS) {
            mWeatherIcon.setVisibility(View.GONE);
            mWeatherTextView.setVisibility(View.GONE);
            Log.w(TAG, "Weather is not available");
            return false;
        }
        mWeatherIcon.setImageDrawable(getContext().getDrawable(mWeatherInfo.getWeatherConditionImage()));
        String temperatureText = (mWeatherInfo.getTemperature(useMetricUnit)) + (useMetricUnit ? "°C" : "°F");
        mWeatherTextView.setText(temperatureText);
        if (mWeatherTextView.getVisibility() == View.GONE) {
            mWeatherIcon.setVisibility(View.VISIBLE);
            mWeatherTextView.setVisibility(View.VISIBLE);
        }
        return true;
    }

    @Override
    public void onWeatherUpdated(WeatherClient.WeatherInfo weatherInfo) {
        mWeatherInfo = weatherInfo;
        updateWeatherStatus();
    }

    public void updateExtendedStatusBarTint(Context context) {
        @ColorInt final int wallpaperTextColor = Utils.getColorAttr(context, R.attr.wallpaperTextColor);
        @ColorInt final int wallpaperTextColorSecondary = Utils.getColorAttr(context, R.attr.wallpaperTextColorSecondary);
        mIconManager.setTint(wallpaperTextColor);
        mBatteryMeterView.updateColors(wallpaperTextColor, wallpaperTextColorSecondary, wallpaperTextColor);
        mClockView.setTextColor(wallpaperTextColor);
        mDateView.setTextColor(wallpaperTextColor);
        mWeatherTextView.setTextColor(wallpaperTextColor);
    }

    private class WeatherSettingsObserver extends ContentObserver {
        WeatherSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.WEATHER_LOCKSCREEN_UNIT),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            if (uri.equals(Settings.System.getUriFor(Settings.System.WEATHER_LOCKSCREEN_UNIT))) {
                updateWeatherUnit();
                updateWeatherStatus();
            }
        }

        public void updateWeatherUnit() {
            useMetricUnit = Settings.System.getIntForUser(mContentResolver, Settings.System.WEATHER_LOCKSCREEN_UNIT, 0, UserHandle.USER_CURRENT) == 0;
        }
    }
}
