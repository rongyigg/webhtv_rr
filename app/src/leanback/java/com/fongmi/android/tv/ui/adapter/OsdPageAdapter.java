package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterOsdPageBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页底部选集面板的分页标签（1-6 / 7-12 ...）。
 * 焦点滑过标签即回调 {@link OnClickListener#onItemFocus(int)}：面板直接翻页，不用再按确定键。
 */
public class OsdPageAdapter extends RecyclerView.Adapter<OsdPageAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<String> items = new ArrayList<>();
    private int selectedPosition = RecyclerView.NO_POSITION;

    public OsdPageAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void addAll(List<String> pages) {
        items.clear();
        if (pages != null) items.addAll(pages);
        if (selectedPosition >= items.size()) selectedPosition = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        if (selectedPosition == position) return;
        int previous = selectedPosition;
        selectedPosition = position;
        if (previous >= 0 && previous < items.size()) notifyItemChanged(previous);
        if (position >= 0 && position < items.size()) notifyItemChanged(position);
    }

    public String get(int position) {
        return items.get(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterOsdPageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(items.get(position));
        holder.binding.text.setSelected(position == selectedPosition);
        holder.binding.text.setOnClickListener(view -> listener.onItemClick(position));
        // 焦点即选中：滑到哪一页就翻到哪一页，点击/确定键的语义保持不变。
        holder.binding.text.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) listener.onItemFocus(position);
        });
    }

    public interface OnClickListener {

        void onItemClick(int position);

        /** 分页标签获得焦点：立即切换下方集数网格。 */
        default void onItemFocus(int position) {
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterOsdPageBinding binding;

        ViewHolder(@NonNull AdapterOsdPageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
