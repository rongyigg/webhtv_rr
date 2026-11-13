package com.fongmi.android.tv.ui.dialog;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.media3.common.C;

import com.fongmi.android.tv.databinding.DialogOffsetBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.google.android.material.slider.Slider;

import java.util.Locale;
import java.util.function.LongConsumer;

/**
 * 偏移面板的绑定逻辑，逐行对齐逆向 `m52.b0()`：
 * <ul>
 *   <li>音频 / 字幕两个 Slider 都用同一套 setup：夹到区间 → 按 stepSize 对齐 → 回写当前值；</li>
 *   <li>字幕 Slider 是 ±300s、1s 一档（step 1000），音频是 ±10s、0.1s 一档（step 100），
 *       所以初值必须先对齐步进，否则 {@code Slider.setValue} 会抛
 *       "Value must be equal to valueFrom plus a multiple of stepSize"；</li>
 *   <li>拖动的回调直接把对齐后的毫秒值交给 setter（逆向 `longConsumer.accept(Math.round(f))`），
 *       不再额外按 100ms 取整；</li>
 *   <li>标签格式 `%+.1fs`（逆向 `z8.O()`），Reset 把 Slider 归零 + 标签写 `+0.0s` + 偏移归零。</li>
 * </ul>
 */
final class OffsetPanel {

    private final DialogOffsetBinding binding;
    private final PlayerManager player;
    private final int type;

    OffsetPanel(DialogOffsetBinding binding, PlayerManager player, int type) {
        this.binding = binding;
        this.player = player;
        this.type = type;
    }

    void bind() {
        setupOffset(binding.audioSlider, binding.audioValue, player.getAudioOffsetMs(), player::setAudioOffsetMs);
        setupOffset(binding.textSlider, binding.textValue, player.getTextOffsetMs(), player::setTextOffsetMs);
        binding.reset.setOnClickListener(this::onReset);
        getSection().setVisibility(View.VISIBLE);
        getSlider().requestFocus();
    }

    private ViewGroup getSection() {
        return type == C.TRACK_TYPE_AUDIO ? binding.audioSection : binding.textSection;
    }

    private Slider getSlider() {
        return type == C.TRACK_TYPE_AUDIO ? binding.audioSlider : binding.textSlider;
    }

    private TextView getLabel() {
        return type == C.TRACK_TYPE_AUDIO ? binding.audioValue : binding.textValue;
    }

    private void onReset(View view) {
        getSlider().setValue(0);
        getLabel().setText(format(0));
        if (type == C.TRACK_TYPE_TEXT) player.setTextOffsetMs(0);
        if (type == C.TRACK_TYPE_AUDIO) player.setAudioOffsetMs(0);
    }

    private void setupOffset(Slider slider, TextView label, long valueMs, LongConsumer setter) {
        float value = snap(slider, valueMs);
        slider.clearOnChangeListeners();
        slider.setLabelFormatter(this::format);
        slider.setValue(value);
        label.setText(format(value));
        slider.addOnChangeListener((source, current, fromUser) -> {
            if (!fromUser) return;
            float snapped = snap(source, current);
            setter.accept(Math.round(snapped));
            label.setText(format(snapped));
        });
    }

    /**
     * 逆向 `z8.s0(Slider, float)`：先夹到 [valueFrom, valueTo]，再按 stepSize
     * 对齐到 valueFrom 的整数倍（stepSize 未设置时只做夹取）。
     */
    private static float snap(Slider slider, float value) {
        float from = slider.getValueFrom();
        float to = slider.getValueTo();
        float step = slider.getStepSize();
        float clamped = Math.min(to, Math.max(value, from));
        if (step <= 0) return clamped;
        float snapped = Math.round((clamped - from) / step) * step + from;
        return Math.min(to, Math.max(snapped, from));
    }

    private String format(float value) {
        return String.format(Locale.getDefault(), "%+.1fs", value / 1000f);
    }
}
