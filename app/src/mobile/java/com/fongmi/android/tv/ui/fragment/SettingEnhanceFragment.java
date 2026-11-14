package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.ComicSourceConfig;
import com.fongmi.android.tv.bean.NovelSourceConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.databinding.FragmentSettingEnhanceBinding;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.ui.activity.FileActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.ComicSourceDialog;
import com.fongmi.android.tv.ui.dialog.NovelSourceDialog;
import com.fongmi.android.tv.ui.dialog.DebugLogDialog;
import com.fongmi.android.tv.ui.dialog.OneKeySyncDialog;
import com.fongmi.android.tv.ui.dialog.ShellProxyDialog;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;

public class SettingEnhanceFragment extends BaseFragment {

    private static final String URL_GITHUB = "https://github.com/Silent1566/webhtv";
    private static final String URL_CNB = "https://cnb.cool/fish2018/ext";

    private FragmentSettingEnhanceBinding mBinding;

    public static SettingEnhanceFragment newInstance() {
        return new SettingEnhanceFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_enable : R.string.setting_disable);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingEnhanceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        reorderItems();
        setText();
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.githubRepo) {
                openRepo(URL_GITHUB);
                return true;
            }
            if (item.getItemId() == R.id.cnbRepo) {
                openRepo(URL_CNB);
                return true;
            }
            return false;
        });
        mBinding.localReader.setOnClickListener(this::setLocalReader);
        mBinding.novelSource.setOnClickListener(this::setNovelSource);
        mBinding.comicSource.setOnClickListener(this::setComicSource);
        mBinding.debugLog.setOnClickListener(this::setDebugLog);
        mBinding.shellProxy.setOnClickListener(view -> ShellProxyDialog.show(this, this::setText));
        mBinding.shellProxy.setOnLongClickListener(v -> false);
        mBinding.shellProxyConfig.setVisibility(View.GONE);
        mBinding.oneKeySync.setOnClickListener(v -> OneKeySyncDialog.create().show(requireActivity()));
    }

    private void reorderItems() {
        ViewGroup parent = (ViewGroup) mBinding.oneKeySync.getParent();
        View[] order = {
                mBinding.oneKeySync,
                mBinding.shellProxy,
                mBinding.shellProxyConfig,
                mBinding.localReader,
                mBinding.novelSource,
                mBinding.comicSource,
                mBinding.debugLog        };
        for (View view : order) parent.removeView(view);
        for (View view : order) parent.addView(view);
    }

    private void setText() {
        if (!canSetText()) return;
        safeSet("novelSource", mBinding.novelSourceText, () -> getSwitch(!NovelSourceConfig.get().getDisplayRules().isEmpty()));
        safeSet("comicSource", mBinding.comicSourceText, () -> getSwitch(!ComicSourceConfig.get().getDisplayRules().isEmpty()));
        safeSet("debugLog", mBinding.debugLogText, () -> getSwitch(Setting.isDebugLog()));
        setShellProxyText();
    }

    private boolean canSetText() {
        return mBinding != null && isAdded() && getContext() != null;
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

    private void setLocalReader(View view) {
        Intent intent = new Intent(requireContext(), FileActivity.class);
        intent.putExtra("read_mode", true);
        startActivity(intent);
    }

    private void setNovelSource(View view) {
        NovelSourceDialog.create(requireActivity()).onDismiss(this::setText).show();
    }

    private void setComicSource(View view) {
        ComicSourceDialog.create(requireActivity()).onDismiss(this::setText).show();
    }

    private void openRepo(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Notify.show(R.string.manage_page_no_browser);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) setText();
    }

    @Override
    public void onResume() {
        super.onResume();
        setText();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }
}
