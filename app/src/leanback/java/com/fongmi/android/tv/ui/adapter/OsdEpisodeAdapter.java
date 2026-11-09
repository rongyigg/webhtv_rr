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
 * 卡片尺寸 = 逆向 ref_docs/adapter_episode.xml：16sp 单行文字 + 20x25dp Lottie。
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
        // 文字只在获得焦点时跑马灯，卡片自身用 itemView 的焦点态背景。
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

    /** 固定 16sp / 上下 8dp / 左右 12dp 内边距，卡片高度由内容决定。 */
    private void applyDensity(@NonNull ViewHolder holder) {
        holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        int horizontal = ResUtil.dp2px(12);
        int vertical = ResUtil.dp2px(8);
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
            // text 不自己接焦点，避免覆盖卡片 itemView 的焦点态背景。
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> binding.text.setSelected(hasFocus));
        }
    }
}
