package com.fongmi.android.tv.ui.adapter;

import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.databinding.AdapterTrackPanelBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨道面板单列适配器（视频列 / 音频列各一个实例）。
 *
 * 状态矩阵（焦点与选中严格分开）：
 * - 普通：透明背景 + 白字；
 * - 焦点：整行统一渐变红长条（@drawable/bg_global_focus_solid_red）+ 纯白字
 *   （背景见 selector_track_panel_item）；
 * - 选中但未聚焦（当前生效轨道）：透明背景 + 横向红渐变文字
 *   （Paint 的 LinearGradient 着色器，@color/osd_accent → @color/osd_accent_light，绝不写死单色红）。
 */
public class TrackPanelAdapter extends RecyclerView.Adapter<TrackPanelAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Track> mItems = new ArrayList<>();
    private final int gradientStart;
    private final int gradientEnd;

    public TrackPanelAdapter(OnClickListener listener) {
        this.listener = listener;
        this.gradientStart = ContextCompat.getColor(App.get(), R.color.osd_accent);
        this.gradientEnd = ContextCompat.getColor(App.get(), R.color.osd_accent_light);
    }

    public interface OnClickListener {

        void onItemClick(Track item);
    }

    public void submit(List<Track> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
    }

    public boolean isEmpty() {
        return mItems.isEmpty();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterTrackPanelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Track item = mItems.get(position);
        TextView text = holder.binding.text;
        text.setText(item.getName());
        // 用 activated 而不是 selected 表达「当前生效」：TextView 的 marquee 在 selected
        // 状态下会自动滚动，会让未聚焦的当前轨道在后台一直跑马灯。
        text.setActivated(item.isSelected());
        applyTextStyle(text, item.isSelected(), text.hasFocus());
        // 焦点变化只改文字着色：选中态的红渐变在失焦后恢复，聚焦时回到纯白。
        text.setOnFocusChangeListener((view, hasFocus) -> applyTextStyle(text, item.isSelected(), hasFocus));
    }

    /**
     * 选中但未聚焦的轨道用横向红渐变文字；聚焦与普通项用纯白。
     * 渐变宽度按当前文本实测宽度计算，避免使用尚未完成布局的 View 宽度。
     */
    private void applyTextStyle(@NonNull TextView text, boolean selected, boolean focused) {
        text.setTextColor(Color.WHITE);
        text.getPaint().setShader(selected && !focused ? createGradient(text) : null);
        text.invalidate();
    }

    @NonNull
    private Shader createGradient(@NonNull TextView text) {
        String label = text.getText().toString();
        float width = Math.max(1f, text.getPaint().measureText(label));
        return new LinearGradient(0f, 0f, width, 0f, gradientStart, gradientEnd, Shader.TileMode.CLAMP);
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AdapterTrackPanelBinding binding;

        public ViewHolder(@NonNull AdapterTrackPanelBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int position = getLayoutPosition();
            if (position == RecyclerView.NO_POSITION) return;
            listener.onItemClick(mItems.get(position));
        }
    }
}
