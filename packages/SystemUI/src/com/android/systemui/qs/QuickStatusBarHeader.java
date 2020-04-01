/*
 * Design made by Naomi Calabretta
 * Copyright (C) 2020. All rights reserved
 * Please refer to the DESIGN_LICENSE file for details.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.util.StatsLog;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;

import androidx.annotation.VisibleForTesting;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.omni.CurrentWeatherView;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.privacy.PrivacyItemControllerKt;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;
    private QSCarrierGroup mCarrierGroup;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;

    private View mSystemIconsView;

    private LinearLayout mClockDateContainer, mStatusIconsContainer;

    private Clock mClockView;
    private DateView mDateView;
    private CurrentWeatherView mWeatherView;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;
    private boolean mPermissionsHubEnabled;

    private final DeviceConfig.OnPropertyChangedListener mPropertyListener =
            new DeviceConfig.OnPropertyChangedListener() {
                @Override
                public void onPropertyChanged(String namespace, String name, String value) {
                    if (DeviceConfig.NAMESPACE_PRIVACY.equals(namespace)
                            && SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED.equals(
                            name)) {
                        mPermissionsHubEnabled = Boolean.valueOf(value);
                        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
                        iconContainer.setIgnoredSlots(getIgnoredIconSlots());
                    }
                }
            };

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter) {
        super(context, attrs);
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);

        mClockDateContainer = findViewById(R.id.clock_date_container);
        mStatusIconsContainer = findViewById(R.id.status_icons_container);
        // Make mStatusIconsContainer the same height of mClockDateContainer
        mClockDateContainer.getViewTreeObserver().addOnGlobalLayoutListener(new OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                mClockDateContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                        mStatusIconsContainer.getLayoutParams();
                lp.height = mClockDateContainer.getHeight();
                mStatusIconsContainer.setLayoutParams(lp);
            }
        });

        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
        iconContainer.addIgnoredSlots(getIgnoredIconSlots());
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer);
        updateResources();

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mDateView = findViewById(R.id.date);
        mDateView.setOnClickListener(this);
        mWeatherView = findViewById(R.id.weather_container);
        mWeatherView.enableUpdates();
        mSpace = findViewById(R.id.space);
        mCarrierGroup = findViewById(R.id.carrier_group);

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        // QS will always show the estimate, and BatteryMeterView handles the case where
        // it's unavailable or charging
        mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
        mBatteryRemainingIcon.setOnClickListener(this);

        updateExtendedStatusBarTint(mContext);

        mPermissionsHubEnabled = PrivacyItemControllerKt.isPermissionsHubEnabled();
        // Change the ignored slots when DeviceConfig flag changes
        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                mContext.getMainExecutor(), mPropertyListener);
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_camera));
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_microphone));
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_mobile));
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_vpn));
        if (mPermissionsHubEnabled) {
            ignored.add(mContext.getResources().getString(
                    com.android.internal.R.string.status_bar_location));
        }

        return ignored;
    }

    public void updateExtendedStatusBarTint(Context context) {
        @ColorInt int textColor = Utils.getColorAttrDefaultColor(context,
                R.attr.wallpaperTextColor);
        if (mIconManager != null) {
            mIconManager.setTint(textColor);
        }

        mCarrierGroup.setTint(textColor);
        mClockView.setTextColor(textColor);
        mDateView.setTextColor(textColor);
        mWeatherView.setTint(textColor, 0);
        mBatteryRemainingIcon.updateColors(textColor, textColor, textColor);
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
        final int mTopMargin = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.qs_status_bar_top_padding);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mQsDisabled) {
            lp.height = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.qs_status_bar_height) + mTopMargin;
        } else {
            lp.height = Math.max(getMinimumHeight(),
                    resources.getDimensionPixelSize(
                            com.android.internal.R.dimen.custom_quick_qs_total_height) + mTopMargin);
        }

        setLayoutParams(lp);
        setPadding(0, mTopMargin, 0, 0);
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;
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
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mStatusBarIconController.removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        mCarrierGroup.setListening(listening);
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mDateView) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),0);
        } else if (v == mBatteryRemainingIcon) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(new Intent(
                    Intent.ACTION_POWER_USAGE_SUMMARY),0);
        }
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            // Prevents these views from getting set a margin.
            // The Icon views all have the same padding set in XML to be aligned.
            if (v == mSystemIconsView || v == mHeaderQsPanel) {
                continue;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = sideMargins;
            lp.rightMargin = sideMargins;
        }
    }
}
