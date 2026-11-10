package com.fongmi.android.tv.ui.adapter;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterOsdEpisodeBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页底部选集网格适配器。
 * 卡片尺寸对齐逆向 App 截图：14sp 单行文字 + 上下 6dp / 左右 12dp 内边距 ≈ 30dp 高，
 * 5dp 圆角改 3dp、卡片间距 8dp（逆向取样：卡片 61px≈30.5dp、行距 16px=8dp）。
 * 标题复用选集按钮的构造 {@link EpisodeAdapter#getNativeTitle(Episode)}。
 * 只有当前正在播放的 {@link Episode#isSelected()} 才把 {@code @raw/equalizer} 置为 VISIBLE 并播放，
 * 其余一律 GONE 且停止动画。
 */
public class OsdEpisodeAdapter extends RecyclerView.Adapter<OsdEpisodeAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Episode> items = new ArrayList<>();

    public OsdEpisodeAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void addAll(List<Episode> episodes) {
        items.clear();
        if (episodes != null) items.addAll(episodes);
        notifyDataSetChanged();
    }

    public int indexOf(Episode item) {
        return items.indexOf(item);
    }

    public int getSelectedPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
    }

    /**
     * 面板内「焦点即时切集」后只刷新受影响的两张卡片。
     * 这里必须避开 notifyDataSetChanged：它会重建当前所有子项，遥控器正在移动的焦点
     * 会被打回第一项，表现为「按一下方向键又跳回开头」。
     */
    public void notifySelectionChanged(int oldPosition, int newPosition) {
        if (oldPosition == newPosition) return;
        if (oldPosition >= 0 && oldPosition < items.size()) notifyItemChanged(oldPosition);
        if (newPosition >= 0 && newPosition < items.size()) notifyItemChanged(newPosition);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterOsdEpisodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode item = items.get(position);
        holder.binding.text.setText(EpisodeAdapter.getNativeTitle(item));
        // 文字只在获得焦点时跑马灯，卡片自身用 itemView 的焦点态背景。
        holder.binding.text.setSelected(holder.itemView.hasFocus());
        applyDensity(holder);
        applySelected(holder, item.isSelected());
        holder.itemView.setOnClickListener(view -> listener.onItemClick(item));
        // 逆向 App 的行为：焦点一落到某一集就立刻切过去，不用再按确定键。
        // 这里只回调、不重建列表，焦点才能继续沿方向键移动。
        holder.itemView.setOnFocusChangeListener((view, hasFocus) -> {
            holder.binding.text.setSelected(hasFocus);
            if (hasFocus && !item.isSelected()) listener.onFocus(item);
        });
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.binding.lottie.cancelAnimation();
        holder.binding.lottie.setVisibility(View.GONE);
    }

    private void applySelected(@NonNull ViewHolder holder, boolean selected) {
        holder.itemView.setActivated(selected);
        holder.binding.text.setActivated(selected);
        if (selected) {
            holder.binding.lottie.setVisibility(View.VISIBLE);
            holder.binding.lottie.playAnimation();
        } else {
            holder.binding.lottie.cancelAnimation();
            holder.binding.lottie.setVisibility(View.GONE);
        }
    }

    /** 固定 14sp / 上下 6dp / 左右 12dp 内边距，卡片高度由内容决定（逆向实测 30.5dp）。 */
    private void applyDensity(@NonNull ViewHolder holder) {
        holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        int horizontal = ResUtil.dp2px(12);
        int vertical = ResUtil.dp2px(6);
        holder.itemView.setPadding(horizontal, vertical, horizontal, vertical);
    }

    public interface OnClickListener {

        void onItemClick(Episode item);

        /** 焦点落到某一集：立即切集，不需要再按确定键。 */
        default void onFocus(Episode item) {
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterOsdEpisodeBinding binding;

        ViewHolder(@NonNull AdapterOsdEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            // text 不自己接焦点，避免覆盖卡片 itemView 的焦点态背景。
        }
    }
}
