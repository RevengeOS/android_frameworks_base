/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.StatsLog;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.dimen;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyDialogBuilder;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyItemControllerKt;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.statusbar.phone.MultiUserSwitch;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;
import com.android.systemui.tuner.TunerService;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

public class QSFooterImpl extends FrameLayout implements QSFooter,
        OnClickListener, OnUserInfoChangedListener {

    private static final String TAG = "QSFooterImpl";

    private final ActivityStarter mActivityStarter;
    private final UserInfoController mUserInfoController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private SettingsButton mSettingsButton;
    protected View mSettingsContainer;
    private PageIndicator mPageIndicator;

    private boolean mQsDisabled;
    private QSPanel mQsPanel;

    private boolean mExpanded;

    private boolean mListening;

    protected MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;

    protected TouchAnimator mFooterAnimator;
    private float mExpansionAmount;

    protected View mEdit;
    private TouchAnimator mSettingsCogAnimator;

    private View mActionsContainer;

    private OnClickListener mExpandClickListener;

    private OngoingPrivacyChip mPrivacyChip;
    private PrivacyItemController mPrivacyItemController;
    private boolean mPermissionsHubEnabled;
    private boolean mPrivacyChipLogged = false;

    private final DeviceConfig.OnPropertyChangedListener mPropertyListener =
            new DeviceConfig.OnPropertyChangedListener() {
                @Override
                public void onPropertyChanged(String namespace, String name, String value) {
                    if (DeviceConfig.NAMESPACE_PRIVACY.equals(namespace)
                            && SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED.equals(
                            name)) {
                        mPermissionsHubEnabled = Boolean.valueOf(value);
                    }
                }
            };

    @Inject
    public QSFooterImpl(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            ActivityStarter activityStarter, UserInfoController userInfoController,
            DeviceProvisionedController deviceProvisionedController,
            PrivacyItemController privacyItemController) {
        super(context, attrs);
        mActivityStarter = activityStarter;
        mUserInfoController = userInfoController;
        mDeviceProvisionedController = deviceProvisionedController;
        mPrivacyItemController = privacyItemController;
    }

    @VisibleForTesting
    public QSFooterImpl(Context context, AttributeSet attrs) {
        this(context, attrs,
                Dependency.get(ActivityStarter.class),
                Dependency.get(UserInfoController.class),
                Dependency.get(DeviceProvisionedController.class),
                Dependency.get(PrivacyItemController.class));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEdit = findViewById(android.R.id.edit);
        mEdit.setOnClickListener(view ->
                mActivityStarter.postQSRunnableDismissingKeyguard(() ->
                        mQsPanel.showEdit(view)));

        mPageIndicator = findViewById(R.id.footer_page_indicator);

        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsContainer = findViewById(R.id.settings_button_container);
        mSettingsButton.setOnClickListener(this);

        mMultiUserSwitch = findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = mMultiUserSwitch.findViewById(R.id.multi_user_avatar);

        mActionsContainer = findViewById(R.id.qs_footer_actions_container);

        mPermissionsHubEnabled = PrivacyItemControllerKt.isPermissionsHubEnabled();
        // Change the ignored slots when DeviceConfig flag changes
        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                mContext.getMainExecutor(), mPropertyListener);

        mPrivacyChip = findViewById(R.id.privacy_chip);
        mPrivacyChip.setOnClickListener(this);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);

        updateResources();

        addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> updateAnimator(right - left));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateEverything();
    }

    private void updateAnimator(int width) {
        int numTiles = QuickQSPanel.getNumQuickTiles(mContext);
        int size = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size)
                - mContext.getResources().getDimensionPixelSize(dimen.qs_quick_tile_padding);
        int remaining = (width - numTiles * size) / (numTiles - 1);
        int defSpace = mContext.getResources().getDimensionPixelOffset(R.dimen.default_gear_space);

        mSettingsCogAnimator = new Builder()
                .addFloat(mSettingsContainer, "translationX",
                        isLayoutRtl() ? (remaining - defSpace) : -(remaining - defSpace), 0)
                .addFloat(mSettingsButton, "rotation", -120, 0)
                .build();

        setExpansion(mExpansionAmount);
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
        updateFooterAnimator();
    }

    private void updateFooterAnimator() {
        mFooterAnimator = createFooterAnimator();
    }

    @Nullable
    private TouchAnimator createFooterAnimator() {
        return new TouchAnimator.Builder()
                .addFloat(mActionsContainer, "alpha", 0, 1)
                .addFloat(mPageIndicator, "alpha", 0, 1)
                .addFloat(mPrivacyChip, "alpha", 0, 1)
                .setStartDelay(0.15f)
                .build();
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        setExpansion(mExpansionAmount);
    }

    @Override
    public void setExpandClickListener(OnClickListener onClickListener) {
        mExpandClickListener = onClickListener;
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    @Override
    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mSettingsCogAnimator != null) mSettingsCogAnimator.setPosition(headerExpansionFraction);

        if (mFooterAnimator != null) {
            mFooterAnimator.setPosition(headerExpansionFraction);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        updateListeners();
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            if (mExpandClickListener != null) {
                mExpandClickListener.onClick(null);
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateEverything();
    }

    public void updateEverything() {
        post(() -> {
            updateVisibilities();
            updateClickabilities();
            setClickable(false);
        });
    }

    private void updateClickabilities() {
        mMultiUserSwitch.setClickable(mMultiUserSwitch.getVisibility() == View.VISIBLE);
        mEdit.setClickable(mEdit.getVisibility() == View.VISIBLE);
        mSettingsButton.setClickable(mSettingsButton.getVisibility() == View.VISIBLE);
    }

    private void updateVisibilities() {
        mSettingsContainer.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mSettingsContainer.findViewById(R.id.tuner_icon).setVisibility(View.INVISIBLE);
        final boolean isDemo = UserManager.isDeviceInDemoMode(mContext);
        mMultiUserSwitch.setVisibility(showUserSwitcher() ? View.VISIBLE : View.INVISIBLE);
        mEdit.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
        mSettingsButton.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
    }

    private boolean showUserSwitcher() {
        return mExpanded && mMultiUserSwitch.isMultiUserEnabled();
    }

    private void updateListeners() {
        if (mListening) {
            mUserInfoController.addCallback(this);
            mPrivacyItemController.addCallback(mPICCallback);
        } else {
            mUserInfoController.removeCallback(this);
            mPrivacyChipLogged = false;
        }
    }

    @Override
    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        if (mQsPanel != null) {
            mMultiUserSwitch.setQsPanel(qsPanel);
            mQsPanel.setFooterPageIndicator(mPageIndicator);
        }
    }

    @Override
    public void onClick(View v) {
        // Don't do anything until view are unhidden
        if (!mExpanded) {
            return;
        }

        if (v == mSettingsButton) {
            if (!mDeviceProvisionedController.isCurrentUserSetup()) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                });
                return;
            }
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            if (mSettingsButton.isTunerClick()) {
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                    if (TunerService.isTunerEnabled(mContext)) {
                        TunerService.showResetRequest(mContext, () -> {
                            // Relaunch settings so that the tuner disappears.
                            startSettingsActivity();
                        });
                    } else {
                        Toast.makeText(getContext(), R.string.tuner_toast,
                                Toast.LENGTH_LONG).show();
                        TunerService.setTunerEnabled(mContext, true);
                    }
                    startSettingsActivity();

                });
            } else {
                startSettingsActivity();
            }
        } else if (v == mPrivacyChip) {
            // Makes sure that the builder is grabbed as soon as the chip is pressed
            PrivacyDialogBuilder builder = mPrivacyChip.getBuilder();
            if (builder.getAppsAndTypes().size() == 0) return;
            Handler mUiHandler = new Handler(Looper.getMainLooper());
            StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                    StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_CLICKED);
            mUiHandler.post(() -> {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
            });
        }
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        if (picture != null &&
                UserManager.get(mContext).isGuestUser(KeyguardUpdateMonitor.getCurrentUser()) &&
                !(picture instanceof UserIconDrawable)) {
            picture = picture.getConstantState().newDrawable(mContext.getResources()).mutate();
            picture.setColorFilter(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorForeground),
                    Mode.SRC_IN);
        }
        mMultiUserAvatar.setImageDrawable(picture);
    }

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void privacyChanged(List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }
    };

    private void setChipVisibility(boolean chipVisible) {
        final boolean chipVisibilityDisabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PRIVACY_CHIP_VIEW, 1) == 1;
        if (chipVisible && mPermissionsHubEnabled && !chipVisibilityDisabled) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                        StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_VIEWED);
            }
        } else {
            mPrivacyChip.setVisibility(View.GONE);
        }
    }
}
