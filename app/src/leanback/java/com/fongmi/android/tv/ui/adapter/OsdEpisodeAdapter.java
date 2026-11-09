package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterOsdEpisodeBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页底部选集面板的双列选集网格。
 * 标题沿用主界面选集按钮的规则（{@link EpisodeAdapter#getNativeTitle(Episode)}），
 * 当前集由 {@link Episode#isSelected()} 决定，点击直接切集。
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
        holder.binding.playing.setVisibility(item.isSelected() ? View.VISIBLE : View.GONE);
        holder.itemView.setSelected(item.isSelected());
        holder.itemView.setOnClickListener(view -> listener.onItemClick(item));
    }

    public interface OnClickListener {

        void onItemClick(Episode item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterOsdEpisodeBinding binding;

        ViewHolder(@NonNull AdapterOsdEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
