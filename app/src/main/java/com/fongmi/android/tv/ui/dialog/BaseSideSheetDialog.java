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
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.core.view.WindowCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.utils.Util;
import com.google.android.material.sidesheet.SideSheetDialog;

public abstract class BaseSideSheetDialog extends AppCompatDialogFragment {

    private boolean fullscreen;

    protected abstract ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container);

    protected abstract int getWidth();

    protected boolean edgeToEdgeOnFullscreen() {
        return false;
    }

    /**
     * 是否让播放页保持原亮度（不压暗视频）。
     *
     * 与 {@link BaseBottomSheetDialog#stableOverlay()} 同一套语义：置 true 后清掉窗口的
     * `FLAG_DIM_BEHIND` 并把 dim 置 0，侧边栏背后就是原样的视频，不再盖一层 Material 默认的
     * 32% 黑色遮罩。播放页的侧边栏（偏移设置）用得到，普通页面的侧边栏保持默认压暗。
     */
    protected boolean stableOverlay() {
        return false;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        SideSheetDialog dialog = new SideSheetDialog(requireContext());
        dialog.getBehavior().setDraggable(false);
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        if (stableOverlay()) {
            clearDim(window);
            // 只在 onCreateDialog 里清掉没用：SideSheet 的主题链是
            // ThemeOverlay.Material3.SideSheetDialog → … → Base.V7.ThemeOverlay.AppCompat.Dialog，
            // 那一层把 android:backgroundDimEnabled 设为 true，Dialog.show() 里
            // PhoneWindow.generateLayout() 会按主题重新补上 FLAG_DIM_BEHIND + dimAmount(0.32)，
            // 把这里清掉的遮罩又加回来（表现就是「改了还是黑」）。
            // OnShowListener 在 addView 之后触发，这时再清一次才真的生效。
            dialog.setOnShowListener(d -> {
                Window shown = dialog.getWindow();
                if (shown != null) clearDim(shown);
            });
        }
        fullscreen = Util.isFullscreen(getActivity());
        if (fullscreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            Util.hideSystemUI(window);
            if (edgeToEdgeOnFullscreen()) WindowCompat.setDecorFitsSystemWindows(window, false);
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

    @Override
    public void onStart() {
        super.onStart();
        applyWindow();
        FrameLayout sheet = getDialog().findViewById(com.google.android.material.R.id.m3_side_sheet);
        if (sheet == null) return;
        if (edgeToEdgeOnFullscreen() && fullscreen) {
            if (sheet.getParent() instanceof ViewGroup) ((ViewGroup) sheet.getParent()).setPadding(0, 0, 0, 0);
            sheet.setPadding(0, 0, 0, 0);
        }
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.width = getWidth();
        if (edgeToEdgeOnFullscreen() && fullscreen && params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(0, 0, 0, 0);
        }
        sheet.setLayoutParams(params);
    }

    private void applyWindow() {
        if (!fullscreen || getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Util.hideSystemUI(window);
        if (!edgeToEdgeOnFullscreen()) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }

    /**
     * 清掉窗口遮罩（压暗层）。
     *
     * 逆向原版 `ci.V()` 只做 `f().g = false`（不可拖拽）+ 全屏时 `addFlags(1024)`，
     * 从不给侧边栏加 dim，所以原版偏移面板背后就是原亮度视频。这里显式把
     * `FLAG_DIM_BEHIND` 和 `dimAmount` 一起归零，窗口已 attach 时 setAttributes 会立即
     * `updateViewLayout`，不需要重建 Dialog。
     */
    private void clearDim(Window window) {
        WindowManager.LayoutParams params = window.getAttributes();
        params.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        params.dimAmount = 0f;
        window.setAttributes(params);
    }
}
