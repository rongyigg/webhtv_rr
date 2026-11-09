package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.DefaultTrackNameProvider;
import androidx.media3.ui.TrackNameProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.databinding.DialogTrackPanelBinding;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.ui.adapter.TrackPanelAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放页「轨道」面板：以逆向 ref_docs/dialog_track.xml 为唯一布局来源，
 * 左列视频轨道、右列音频轨道，数据直接来自 {@link PlayerManager#getCurrentTracks()}。
 * 不使用共享的 TrackDialog（它已被移动端字幕/AI 功能占用），避免影响手机 flavor。
 */
public final class TrackPanelDialog extends BaseBottomSheetDialog implements TrackPanelAdapter.OnClickListener {

    private final TrackPanelAdapter videoAdapter = new TrackPanelAdapter(this);
    private final TrackPanelAdapter audioAdapter = new TrackPanelAdapter(this);
    private final TrackNameProvider provider = new DefaultTrackNameProvider(App.get().getResources());
    private DialogTrackPanelBinding binding;
    private PlayerManager player;

    public static TrackPanelDialog create() {
        return new TrackPanelDialog();
    }

    public TrackPanelDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof TrackPanelDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    /** 底板完全透明：直接铺在视频画面上，不再绘制深色圆角外壳。 */
    @Override
    protected boolean transparent() {
        return true;
    }

    /** 去掉弹窗遮罩（FLAG_DIM_BEHIND / dimAmount），打开面板时画面保持可见。 */
    @Override
    protected boolean stableOverlay() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogTrackPanelBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.videoRecycler.setItemAnimator(null);
        binding.audioRecycler.setItemAnimator(null);
        binding.videoRecycler.setAdapter(videoAdapter);
        binding.audioRecycler.setAdapter(audioAdapter);
        refresh();
    }

    @Override
    public void onItemClick(Track item) {
        if (player == null || item == null) return;
        Track track = item.key(player.getKey()).save();
        if (track.getType() == C.TRACK_TYPE_AUDIO || track.getType() == C.TRACK_TYPE_VIDEO) selectAvTrack(track);
        else player.setTrack(Collections.singletonList(track));
        dismiss();
    }

    /**
     * 音视频轨严格按 {@link Tracks.Group#getType()} 分流：只有 group 类型是
     * {@link C#TRACK_TYPE_AUDIO} 时才写音频 override，视频同理，两条参数绝不混用，
     * 避免把音频 override 塞给视频渲染器造成切轨无声。命中下标直接由 group 给出，
     * 不依赖 {@link Track#isSelected()}，避免空 indices 把整条音轨关掉。
     */
    private void selectAvTrack(Track track) {
        Player exo = player.isExo() ? player.getPlayer() : null;
        if (exo == null) {
            track.setSelected(true);
            player.setTrack(Collections.singletonList(track));
            return;
        }
        Tracks.Group target = null;
        int index = -1;
        for (Tracks.Group group : exo.getCurrentTracks().getGroups()) {
            int type = group.getType();
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO) continue;
            if (type != track.getType()) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                boolean match = track.getPlayerId() != null && track.getPlayerId().equals(format.id);
                if (!match && track.getPlayerId() == null) match = TextUtils.equals(track.getFormat(), PlayerHelper.describeFormat(format));
                if (!match) continue;
                target = group;
                index = i;
                break;
            }
            if (target != null) break;
        }
        if (target == null || index < 0) {
            track.setSelected(true);
            player.setTrack(Collections.singletonList(track));
            return;
        }
        int type = target.getType();
        if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO) return;
        TrackSelectionParameters.Builder builder = exo.getTrackSelectionParameters().buildUpon();
        if (builder instanceof DefaultTrackSelector.Parameters.Builder exoBuilder) {
            exoBuilder.setExceedRendererCapabilitiesIfNecessary(true);
            exoBuilder.setExceedVideoConstraintsIfNecessary(true);
            exoBuilder.setExceedAudioConstraintsIfNecessary(true);
        }
        builder.setTrackTypeDisabled(type, false);
        builder.setOverrideForType(new TrackSelectionOverride(target.getMediaTrackGroup(), index));
        exo.setTrackSelectionParameters(builder.build());
    }

    private void refresh() {
        List<Track> videos = new ArrayList<>();
        List<Track> audios = new ArrayList<>();
        if (player != null) {
            Tracks tracks = player.getCurrentTracks();
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() == C.TRACK_TYPE_VIDEO) addGroup(videos, group);
                else if (group.getType() == C.TRACK_TYPE_AUDIO) addGroup(audios, group);
            }
        }
        videoAdapter.submit(videos);
        audioAdapter.submit(audios);
        binding.videoRecycler.setVisibility(videos.isEmpty() ? View.GONE : View.VISIBLE);
        binding.audioRecycler.setVisibility(audios.isEmpty() ? View.GONE : View.VISIBLE);
        focusInitial();
    }

    private void addGroup(List<Track> items, Tracks.Group group) {
        boolean marked = false;
        for (int i = 0; i < group.length; i++) {
            Format format = group.getTrackFormat(i);
            Track item = new Track(group.getType(), provider.getTrackName(format), PlayerHelper.describeFormat(format)).playerId(format.id);
            // 自适应视频会同时选中同组多条轨道，这里只把第一条标红，避免整列都变成选中态。
            boolean selected = group.isTrackSelected(i) && !marked;
            marked = marked || selected;
            item.setSelected(selected);
            items.add(item);
        }
    }

    /** 打开面板后把焦点放到左列当前选中项（左列为空时退回右列第一项）。 */
    private void focusInitial() {
        boolean useVideo = !videoAdapter.isEmpty();
        TrackPanelAdapter adapter = useVideo ? videoAdapter : audioAdapter;
        RecyclerView recycler = useVideo ? binding.videoRecycler : binding.audioRecycler;
        if (adapter.isEmpty()) return;
        int selected = adapter.getSelectedPosition();
        int target = selected == RecyclerView.NO_POSITION ? 0 : selected;
        recycler.post(() -> {
            if (binding == null || !isAdded()) return;
            recycler.scrollToPosition(target);
            recycler.post(() -> {
                if (binding == null || !isAdded()) return;
                RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
            });
        });
    }
}
