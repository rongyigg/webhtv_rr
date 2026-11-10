package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
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
 * 卡片尺寸对齐逆向 App：高度固定 38dp（adapter_osd_episode.xml 的 layout_height），
 * 16sp 单行文字（对齐 ref_docs/adapter_episode.xml），左右 12dp 内边距，卡片间距 8dp。
 * 标题复用选集按钮的构造 {@link EpisodeAdapter#getNativeTitle(Episode)}。
 *
 * 交互约定（与分页标签严格区分）：
 * - 焦点在卡片间移动只改变焦点与选中红框，**不切集、不播放**；
 * - 只有按下确定键触发 {@link OnClickListener#onItemClick(Episode)} 才真正切集；
 * - 跑马灯只在获得焦点的卡片上开，失焦立刻停止。
 *
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
        holder.binding.text.setText(EpisodeAdapter.getNativeTitle(item));
        applyDensity(holder);
        applySelected(holder, item.isSelected());
        // rebind 后按当前焦点状态恢复跑马灯，避免卡片被回收重建后滚动停住。
        applyMarquee(holder, holder.itemView.hasFocus());
        holder.itemView.setOnClickListener(view -> listener.onItemClick(item));
        // 焦点移动只负责跑马灯：绝不在这里切集，切集只能通过上面的点击/确定键。
        holder.itemView.setOnFocusChangeListener((view, hasFocus) -> applyMarquee(holder, hasFocus));
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.binding.lottie.cancelAnimation();
        holder.binding.lottie.setVisibility(View.GONE);
    }

    /**
     * 动态跑马灯：只有 hasFocus == true 才切 MARQUEE + setSelected(true)，
     * 失焦立刻回到 END + setSelected(false)，保证同一时刻只有一张卡片在滚。
     * 卡片自身状态用 itemView 的 activated/focused 背景，这里的 selected 只作用于文字。
     */
    private void applyMarquee(@NonNull ViewHolder holder, boolean focused) {
        holder.binding.text.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        holder.binding.text.setSelected(focused);
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

    /**
     * 固定 16sp / 左右 12dp 内边距；卡片高度由 adapter_osd_episode.xml 固定为 38dp，
     * 上下内边距给 0，让文字在 38dp 长条里垂直居中（文字 View 自身 layout_gravity=center）。
     */
    private void applyDensity(@NonNull ViewHolder holder) {
        holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        int horizontal = ResUtil.dp2px(12);
        holder.itemView.setPadding(horizontal, 0, horizontal, 0);
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
        }
    }
}
