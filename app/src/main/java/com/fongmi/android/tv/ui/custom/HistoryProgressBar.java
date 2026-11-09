package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;

/**
 * 历史页观看进度条，逐行照抄 {@code ref_docs/HistoryProgressBar.java}。
 *
 * <p>原版：
 * <ul>
 *   <li>轨道：{@code RectF(0, 0, width, height)} + 圆角 = 高度 / 2，画笔颜色 {@code -1}（纯白）；</li>
 *   <li>进度：宽度 = {@code min(width, max(progress * width, height))}（非零时至少保留一个圆点），
 *       圆角同轨道，{@code LinearGradient(0, 0, w, h, dl3.a(), dl3.l())}。</li>
 * </ul>
 *
 * <p>{@code dl3.a()} / {@code dl3.l()} 在反向资料里只有调用点、没有色值定义，
 * 这里与原 Compose 历史页的占位保持一致：两端都用工程既有 {@code @color/hero_accent}（#FFFF0000）。
 * 只影响进度条渐变色，不影响几何 / 进度计算。
 */
public class HistoryProgressBar extends View {

    private final Paint trackPaint;
    private final Paint progressPaint;
    private final RectF rect = new RectF();
    private final int progressStartColor;
    private final int progressEndColor;

    private float progress;
    private float lastBarWidth = -1f;
    private int lastStartColor;

    public HistoryProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // 原版 paint.setColor(-1) = 纯白轨道。
        trackPaint.setColor(0xFFFFFFFF);
        progressStartColor = ContextCompat.getColor(context, R.color.hero_accent);
        progressEndColor = ContextCompat.getColor(context, R.color.hero_accent);
        setFocusable(false);
        setClickable(false);
    }

    public HistoryProgressBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HistoryProgressBar(Context context) {
        this(context, null);
    }

    /** 0..1；与原版字段 {@code j} 完全一致，由调用方 clamp。 */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float h = height;
        float radius = h / 2f;
        float w = width;
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        if (progress <= 0f) return;

        float barWidth = Math.min(w, Math.max(progress * w, h));
        if (barWidth != lastBarWidth || progressStartColor != lastStartColor || progressPaint.getShader() == null) {
            lastBarWidth = barWidth;
            lastStartColor = progressStartColor;
            progressPaint.setShader(new LinearGradient(
                    0f, 0f, barWidth, h,
                    progressStartColor, progressEndColor,
                    Shader.TileMode.CLAMP));
        }
        rect.set(0f, 0f, barWidth, h);
        canvas.drawRoundRect(rect, radius, radius, progressPaint);
    }
}
