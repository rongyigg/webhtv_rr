package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.databinding.AdapterTrackPanelBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨道面板单列适配器（视频列 / 音频列各一个实例）。
 * 普通项透明背景 + 白字；有焦点是深灰长条 + 白字；已选中是同一深灰长条 + 柔和红字，
 * 全部交给 {@code selector_track_panel_item} / {@code selector_track_panel_text}。
 */
public class TrackPanelAdapter extends RecyclerView.Adapter<TrackPanelAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Track> mItems = new ArrayList<>();

    public TrackPanelAdapter(OnClickListener listener) {
        this.listener = listener;
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
        holder.binding.text.setText(item.getName());
        holder.binding.text.setSelected(item.isSelected());
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
