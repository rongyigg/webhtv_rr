package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.AdapterOsdFlagBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页底部选集面板的线路列表。
 * 只负责展示和回调，选中状态由 {@link Flag#isSelected()} 决定，和主界面线路列表共用同一份数据。
 */
public class OsdFlagAdapter extends RecyclerView.Adapter<OsdFlagAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Flag> items = new ArrayList<>();

    public OsdFlagAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void addAll(List<Flag> flags) {
        items.clear();
        if (flags != null) items.addAll(flags);
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
    }

    public Flag get(int position) {
        return items.get(position);
    }

    public void notifySelectionChanged() {
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterOsdFlagBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Flag item = items.get(position);
        holder.binding.text.setText(item.getShow());
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setOnClickListener(view -> listener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(Flag item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterOsdFlagBinding binding;

        ViewHolder(@NonNull AdapterOsdFlagBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
