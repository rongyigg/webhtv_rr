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
 * 播放页底部选集面板的选集网格。
 * 卡片布局 = 逆向数据 ref_docs/adapter_episode.xml（16sp 跑马灯文字 + 20x25dp Lottie 均衡器），
 * 标题沿用主界面选集按钮的规则（{@link EpisodeAdapter#getNativeTitle(Episode)}），
 * 当前集由 {@link Episode#isSelected()} 决定：只有正在播放的这一集 {@code VISIBLE} 并播放
 * {@code @raw/equalizer}，其余一律 {@code GONE} 且停止动画。
 */
public class OsdEpisodeAdapter extends RecyclerView.Adapter<OsdEpisodeAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Episode> items = new ArrayList<>();
    private boolean compact;

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

    public boolean isCompact() {
        return compact;
    }

    /** 原始 / 紧凑：只改字号和竖向留白，不新增第二套布局。 */
    public void setCompact(boolean compact) {
        if (this.compact == compact) return;
        this.compact = compact;
        notifyDataSetChanged();
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
        boolean selected = item.isSelected();
        holder.binding.text.setText(EpisodeAdapter.getNativeTitle(item));
        // 跑马灯只在获得焦点时滚动；TextView 自身不可聚焦，由 itemView 的焦点态驱动。
        holder.binding.text.setSelected(holder.itemView.hasFocus());
        applyDensity(holder);
        holder.itemView.setActivated(selected);
        holder.binding.text.setActivated(selected);
        if (selected) {
            holder.binding.lottie.setVisibility(View.VISIBLE);
            holder.binding.lottie.playAnimation();
        } else {
            holder.binding.lottie.cancelAnimation();
            holder.binding.lottie.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(view -> listener.onItemClick(item));
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.binding.lottie.cancelAnimation();
        holder.binding.lottie.setVisibility(View.GONE);
    }

    /** 原始 = 16sp / 上下 8dp；紧凑 = 14sp / 上下 4dp，横向留白始终 12dp。 */
    private void applyDensity(@NonNull ViewHolder holder) {
        holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 14f : 16f);
        int horizontal = ResUtil.dp2px(12);
        int vertical = ResUtil.dp2px(compact ? 4 : 8);
        // 背景挂在 itemView 上，所以留白也必须写在 itemView 上。
        holder.itemView.setPadding(horizontal, vertical, horizontal, vertical);
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterOsdEpisodeBinding binding;

        ViewHolder(@NonNull AdapterOsdEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            // text 本身不可聚焦，跑马灯靠 itemView 的焦点态驱动。
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> binding.text.setSelected(hasFocus));
        }
    }
}
