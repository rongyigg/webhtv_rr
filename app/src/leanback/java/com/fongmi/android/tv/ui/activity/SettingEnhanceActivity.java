package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.databinding.ActivitySettingEnhanceBinding;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.DebugLogDialog;
import com.fongmi.android.tv.ui.dialog.OneKeySyncDialog;
import com.fongmi.android.tv.ui.dialog.ShellProxyDialog;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;

public class SettingEnhanceActivity extends BaseActivity {

    private static final String URL_GITHUB = "https://github.com/Silent1566/webhtv";
    private static final String URL_CNB = "https://cnb.cool/fish2018/ext";

    private ActivitySettingEnhanceBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingEnhanceActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_enable : R.string.setting_disable);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingEnhanceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        reorderItems();
        mBinding.oneKeySync.requestFocus();
        setText();
    }

    @Override
    protected void initEvent() {
        mBinding.githubRepo.setOnClickListener(view -> openRepo(URL_GITHUB));
        mBinding.cnbRepo.setOnClickListener(view -> openRepo(URL_CNB));
        mBinding.debugLog.setOnClickListener(this::setDebugLog);
        mBinding.shellProxy.setOnClickListener(view -> ShellProxyDialog.show(this, this::setText));
        mBinding.shellProxy.setOnLongClickListener(v -> false);
        mBinding.shellProxyConfig.setVisibility(View.GONE);
        mBinding.oneKeySync.setOnClickListener(v -> OneKeySyncDialog.create().show(this));
    }

    private void reorderItems() {
        ViewGroup parent = (ViewGroup) mBinding.oneKeySync.getParent();
        View[] order = {
                mBinding.oneKeySync,
                mBinding.shellProxy,
                mBinding.shellProxyConfig,
                mBinding.debugLog
        };
        for (View view : order) parent.removeView(view);
        for (View view : order) parent.addView(view);
    }

    private void setText() {
        if (!canSetText()) return;
        safeSet("debugLog", mBinding.debugLogText, () -> getSwitch(Setting.isDebugLog()));
        setShellProxyText();
    }

    private boolean canSetText() {
        return mBinding != null && !isFinishing() && !isDestroyed();
    }

    private void setShellProxyText() {
        safeRun("shellProxy", () -> {
            int count = ProxySetting.count();
            mBinding.shellProxyText.setText(getSwitch(Setting.isShellProxy()) + " · " + getString(R.string.setting_proxy_rule_count, count));
            mBinding.shellProxyConfigText.setText(getString(R.string.setting_proxy_rule_count, count));
        }, () -> {
            setError(mBinding.shellProxyText);
            setError(mBinding.shellProxyConfigText);
        });
    }

    private void safeSet(String name, TextView view, TextSupplier supplier) {
        safeRun(name, () -> view.setText(supplier.get()), () -> setError(view));
    }

    private void safeRun(String name, Runnable action, Runnable fallback) {
        try {
            action.run();
        } catch (Throwable e) {
            SpiderDebug.log("enhance", "summary failed item=%s error=%s", name, e.toString());
            if (fallback == null) return;
            try {
                fallback.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private void setError(TextView view) {
        if (view != null) view.setText(R.string.error_config_get);
    }

    private interface TextSupplier {
        CharSequence get();
    }

    private void setDebugLog(View view) {
        Setting.putDebugLog(!Setting.isDebugLog());
        mBinding.debugLogText.setText(getSwitch(Setting.isDebugLog()));
        if (!Setting.isDebugLog()) return;
        DebugLogDialog.show(this);
    }

    private void openRepo(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Notify.show(R.string.manage_page_no_browser);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setText();
    }
}
