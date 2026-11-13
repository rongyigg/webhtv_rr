package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public abstract class BaseBottomSheetDialog extends BottomSheetDialogFragment {

    protected abstract ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container);

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            if (!isAdded() || getContext() == null) return;
            setBehavior(dialog);
        });
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        if (stableOverlay()) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.setDimAmount(0f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            WindowCompat.setDecorFitsSystemWindows(window, true);
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            if (Util.isFullscreen(getActivity())) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return getBinding(inflater, container).getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView();
        initEvent();
    }

    protected void initView() {
    }

    protected void initEvent() {
    }

    protected boolean transparent() {
        return false;
    }

    protected boolean stableOverlay() {
        return false;
    }

    /**
     * 是否铺满整个屏幕宽度。
     *
     * Material3 的 {@link BottomSheetBehavior} 默认把面板宽度限制在
     * `material_bottom_sheet_max_width`（640dp）并水平居中，电视上会变成一块
     * 悬在屏幕中间的窄卡片。原版播放页轨道面板（`ref_docs/dialog_track.xml`）是
     * `match_parent` 全宽，需要它的子类把这条限制去掉。
     */
    protected boolean fullWidth() {
        return false;
    }

    protected void setBehavior(BottomSheetDialog dialog) {
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        sheet.setClickable(true);
        sheet.setFocusable(true);
        if (transparent()) {
            int color = ResUtil.getColor(R.color.transparent);
            sheet.setBackgroundColor(color);
            if (sheet.getParent() instanceof View) ((View) sheet.getParent()).setBackgroundColor(color);
            Window window = dialog.getWindow();
            if (window != null) window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
        // 默认 maxWidth = 640dp（Material3），只有显式要求全宽的弹窗才解除。
        if (fullWidth()) behavior.setMaxWidth(Integer.MAX_VALUE);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
    }
}
