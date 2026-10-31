package com.fongmi.android.tv.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.effect.ColorLut;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.exo.ass.ExoAssSession;
import com.fongmi.android.tv.player.engine.IjkPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerCacheState;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.SystemPlayerEngine;
import com.fongmi.android.tv.player.audio.PlaybackMediaClock;
import com.fongmi.android.tv.player.audio.PlaybackMediaSessionController;
import com.fongmi.android.tv.player.audio.PlaybackMediaSignalHub;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.exo.ExoBufferingStallWatchdog;
import com.fongmi.android.tv.player.exo.ExoDecoderResourceRecoveryLimiter;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardBufferPolicy;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardController;
import com.fongmi.android.tv.player.exo.ExoNetworkGuardEligibility;
import com.fongmi.android.tv.player.exo.ExoRtspLiveLagController;
import com.fongmi.android.tv.player.exo.ExoRtspLiveLagPolicy;
import com.fongmi.android.tv.player.exo.ForwardBufferTrend;
import com.fongmi.android.tv.player.exo.PlaybackAnalyticsListener;
import com.fongmi.android.tv.player.ijk.IjkBufferController;
import com.fongmi.android.tv.player.ijk.IjkBufferPolicy;
import com.fongmi.android.tv.player.ijk.IjkDecodePressureController;
import com.fongmi.android.tv.player.ijk.IjkDecodePressurePolicy;
import com.fongmi.android.tv.player.ijk.IjkFirstFrameWatchdog;
import com.fongmi.android.tv.player.ijk.IjkRealtimeRecoveryController;
import com.fongmi.android.tv.player.ijk.IjkRealtimeRecoveryPolicy;
import com.fongmi.android.tv.player.ijk.IjkRuntimeProfileController;
import com.fongmi.android.tv.player.ijk.IjkRuntimeProfilePolicy;
import com.fongmi.android.tv.player.ijk.IjkRuntimeProfiles;
import com.fongmi.android.tv.player.danmaku.DanmakuUrlPolicy;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuBatcher;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuBuffer;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuMessage;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuMetrics;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuParser;
import com.fongmi.android.tv.player.danmaku.LiveDanmakuWebSocketSession;
import com.fongmi.android.tv.player.lut.DynamicLutEffect;
import com.fongmi.android.tv.player.lut.LutEffectFactory;
import com.fongmi.android.tv.player.lut.LutEligibility;
import com.fongmi.android.tv.player.lut.LutPreset;
import com.fongmi.android.tv.player.lut.LutSetting;
import com.fongmi.android.tv.player.lut.LutStore;
import com.fongmi.android.tv.player.lut.MpvLutShader;
import com.fongmi.android.tv.player.lut.MpvLutShaderFactory;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.setting.ExoPerformanceSetting;
import com.fongmi.android.tv.setting.IjkPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackExperimentSetting;
import com.fongmi.android.tv.setting.PlaybackLightweightAssessmentSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceCatalog;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PlaybackProfileAbSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.subtitle.RealtimeSubtitleController;
import com.fongmi.android.tv.utils.LocalProxyDebug;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;


import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerManager implements ParseCallback {

    public static final String RELOAD_LUT_WARMUP = "__webhtv_lut_warmup_reload__";
    static final int FALLBACK_NONE = 0;
    static final int FALLBACK_DECODE = 1;
    static final int FALLBACK_PLAYER = 2;
    private static final String NETWORK_GUARD_DEBUG = "EXO_NETWORK_GUARD";

    private static void logNetworkGuard(String message) {
        if (SpiderDebug.isEnabled()) Log.d(NETWORK_GUARD_DEBUG, message);
    }

    private static final long LOCAL_PROXY_READY_TIMEOUT_MS = 5000;
    private static final long LOCAL_PROXY_RETRY_DELAY_MS = 1000;
    private static final long HARD_DECODE_SWITCH_RETRY_DELAY_MS = 1200;
    private static final long EXO_TUNNELING_RETRY_DELAY_MS = 250;
    private static final long EXO_DECODER_RUNTIME_RETRY_DELAY_MS = 1200;
    private static final long EXO_DECODER_RESOURCE_RECOVERY_DELAY_MS = 500;
    private static final long EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS = 1200;
    private static final int LOCAL_PROXY_MAX_RETRY = 2;
    // 以内核常量为下标，长度随顺序表推导，避免新增内核时标记表长度漏改。
    private static final int PLAYER_COUNT = PlayerSetting.kernelIndexSize();
    private static final int LUT_WARMUP_RECOVERED_ERROR_REFRESH_THRESHOLD = 3;
    private static final long DANMAKU_FORCE_RELOAD_DEBOUNCE_MS = 10000;
    private static final long LIVE_DANMAKU_METRICS_INTERVAL_MS = 15000L;
    private static final long PLAYBACK_TELEMETRY_INTERVAL_MS = 5000L;
    private static final long DISK_RANGE_GAP_TOLERANCE_MS = 2000L;
    private static final long BUFFERING_STALL_POLL_INTERVAL_MS = 1000L;
    private static final long LUT_PREVIEW_FRAME_INTERVAL_MS = 16L;
    private static final float[] SPEED_PRESETS = new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 5f};
    private static final DecimalFormat SPEED_FORMAT = new DecimalFormat("0.##x");
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)(?:response code|http status|http error)\\D+(\\d{3})");

    private final Runnable runnable;
    private final Runnable bufferingStallRunnable;
    private final Runnable liveDanmakuMetricsRunnable;
    private final Runnable networkProtectionRunnable;
    private final Runnable playbackTelemetryRunnable;
    private final ExoBufferingStallWatchdog bufferingStallWatchdog =
            new ExoBufferingStallWatchdog();
    private final Callback callback;
    private final PlaybackMediaSignalHub mediaSignals = new PlaybackMediaSignalHub(8);
    private final PlaybackMediaClock mediaClock = new PlaybackMediaClock(500L);
    private final PlaybackMediaSessionController mediaSession =
            new PlaybackMediaSessionController(mediaSignals, mediaClock);
    private final DynamicLutEffect dynamicLutEffect;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private final BroadcastReceiver noisyReceiver;
    private final PlaybackBufferingTracker playbackBufferingTracker;
    private final PlaybackTrace playbackTrace;
    private final PlaybackAutoContextStore playbackAutoContextStore;
    private final PlaybackTelemetryCoordinator playbackTelemetryCoordinator;
    private final PlaybackProfileAbCoordinator playbackProfileAbCoordinator;
    private final PlaybackProfileAbCoordinator
            playbackLightweightAssessmentCoordinator;
    private final PlaybackMediaFactsCoordinator playbackMediaFactsCoordinator;
    private final ExoDecoderResourceRecoveryLimiter
            exoDecoderResourceRecoveryLimiter;
    private final ExoNetworkGuardController networkProtectionController;
    private final ExoRtspLiveLagController rtspLiveLagController;
    private final PlaybackMemoryCoordinator.Registration ijkBufferMemoryRegistration;
    private final PlaybackExperimentCoordinator playbackExperimentCoordinator;
    private final PlaybackExperimentCoordinator.Registration playbackExperimentRegistration;
    private final IjkBufferController ijkBufferController;
    private final IjkDecodePressureController ijkDecodePressureController;
    private final IjkRealtimeRecoveryController ijkRealtimeRecoveryController;
    private final IjkRuntimeProfileController ijkRuntimeProfileController;
    private final IjkFirstFrameWatchdog ijkFirstFrameWatchdog;
    private final ForwardBufferTrend networkProtectionTrend;
    private final LiveDanmakuBatcher liveDanmakuBatcher;
    private final LiveDanmakuBuffer liveDanmakuBuffer;
    private final LiveDanmakuMetrics liveDanmakuMetrics;
    private final SpeedToggleState speedToggleState;
    private final ExoSpeedRestoreState exoSpeedRestoreState;
    private DanmakuController danmakuController;
    private LiveDanmakuWebSocketSession liveDanmakuSession;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private TrackSelectionParameters realtimeSubtitleTrackSelection;
    private String currentDanmakuUrl;
    private String currentDanmakuKey;
    private String loadingDanmakuKey;
    private String lastLoggedRouteTraceId = PlaybackTrace.NONE;
    private IjkTimelinePublicationKey lastIjkTimelinePublicationKey;
    private IjkBufferController.Decision pendingIjkBufferDecision;
    private IjkDecodePressureController.Decision pendingIjkDecodePressureDecision;
    private IjkRealtimeRecoveryPolicy.Decision pendingIjkRealtimeRecoveryDecision;
    private ExoDecoderResourceRecovery pendingExoDecoderResourceRecovery;
    private PlaybackAutoContext.SessionToken playbackAutoSession = PlaybackAutoContext.SessionToken.none();
    private long playbackTrackSequence;
    private long danmakuLoadStartedAtMs;
    private volatile long liveDanmakuGeneration;
    private volatile boolean liveDanmakuPlaybackActive;
    private long pendingSwitchPositionMs = C.TIME_UNSET;
    private long pendingInitialStartPositionMs = C.TIME_UNSET;
    /**
     * 宿主从 History 里恢复出来的外挂字幕，等本次起播前注入。
     *
     * <p>不让宿主直接改 spec：spec 在 {@code parse()} 路径里是 PlayerManager 自己
     * 构造的，宿主拿不到。取用一次即清，避免换集时复用上一集的字幕。
     */
    private Sub pendingRestoreSub;
    private float pendingSwitchSpeed = 1f;
    private boolean danmakuLoadInProgress;
    private boolean danmakuForeground = true;
    private boolean pendingSwitchRepeat;
    private boolean pendingSwitchRestore;
    private boolean audioFocusHeld;
    private boolean noisyReceiverRegistered;
    private boolean resumeOnAudioFocusGain;
    private Object audioFocusRequest;

    private boolean initTrack;
    private boolean videoEffectsActive;
    private boolean videoEffectsDirty;
    private boolean parseHealthRecorded;
    private boolean lutAppliedForItem;
    private boolean lutApplyInProgress;
    private boolean lutPipelineReadyForItem;
    private boolean lutPipelinePrepareInProgress;
    private boolean pendingLutPreview;
    private boolean waitingLutBeforePlay;
    private boolean playWhenReady = true;
    private boolean lutWarmupRecoveryActive;
    private boolean lutWarmupRefreshRequested;
    private boolean lutWarmupReloadPreviewPending;
    private boolean hardDecodeSwitchRetryArmed;
    private boolean lutAllowed = true;
    private boolean manualPlayerSwitchPending;
    private boolean ijkBufferManagedReload;
    private boolean ijkRuntimeTemporaryFallback;
    private boolean ijkRuntimeManualOverride;
    private boolean pendingIjkRuntimeFallbackReparse;
    private boolean playbackForeground;
    private boolean exoDecoderResourceRecoveryInProgress;
    private int playerType;
    private int retry;
    private int localProxyRetry;
    private int prepareSeq;
    private int lutApplySeq;
    private long parseHealthStartedAt;
    private boolean[] playerFallbackTried;
    private int lutWarmupRecoveredErrors;
    private float userPlaybackSpeed = 1f;
    private float networkProtectionSpeed = 1f;
    private float networkProtectionSupportedSpeed = 1f;
    private long networkProtectionMediaBitrate;
    private ExoNetworkGuardController.State networkProtectionState = ExoNetworkGuardController.State.NORMAL;
    private ExoNetworkGuardController.ProtectionTier networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
    private String networkProtectionReason = "waiting";
    private PlaybackExperimentCoordinator.Token networkProtectionExperimentToken;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        PlaybackExperimentSetting.ensureInitialized();
        this.playbackExperimentCoordinator =
                PlaybackExperimentCoordinator.process();
        this.playbackExperimentRegistration =
                playbackExperimentCoordinator.addListener(update ->
                        App.post(() -> onPlaybackExperimentPolicyChanged(update)));
        this.runnable = this::onPlaybackTimeout;
        this.bufferingStallRunnable = this::checkBufferingStall;
        this.liveDanmakuMetricsRunnable = () -> logLiveDanmakuMetrics("periodic", true);
        this.networkProtectionRunnable = this::evaluateNetworkProtection;
        this.playbackTelemetryRunnable = this::publishPlaybackTelemetryTick;
        this.playbackBufferingTracker = new PlaybackBufferingTracker();
        this.playbackTrace = new PlaybackTrace();
        this.playbackAutoContextStore = PlaybackAutoContextStore.process();
        this.playbackTelemetryCoordinator = PlaybackTelemetryCoordinator.process();
        this.playbackProfileAbCoordinator = PlaybackProfileAbCoordinator.process();
        this.playbackLightweightAssessmentCoordinator =
                PlaybackLightweightAssessmentSetting.coordinator();
        this.playbackMediaFactsCoordinator = new PlaybackMediaFactsCoordinator(playbackAutoContextStore);
        this.exoDecoderResourceRecoveryLimiter =
                new ExoDecoderResourceRecoveryLimiter();
        this.networkProtectionController = new ExoNetworkGuardController();
        this.rtspLiveLagController = new ExoRtspLiveLagController();
        this.ijkBufferController = new IjkBufferController();
        this.ijkDecodePressureController =
                new IjkDecodePressureController();
        this.ijkRealtimeRecoveryController =
                new IjkRealtimeRecoveryController();
        this.ijkRuntimeProfileController =
                IjkRuntimeProfiles.process().newController();
        this.ijkFirstFrameWatchdog = new IjkFirstFrameWatchdog();
        this.ijkBufferMemoryRegistration = PlaybackMemoryCoordinator.process().addListener(update ->
                App.post(() -> onIjkBufferMemoryUpdate(update)));
        this.networkProtectionTrend = new ForwardBufferTrend();
        this.liveDanmakuBuffer = new LiveDanmakuBuffer();
        this.liveDanmakuMetrics = new LiveDanmakuMetrics();
        this.liveDanmakuBatcher = new LiveDanmakuBatcher(liveDanmakuBuffer, this::onLiveDanmakuBatch);
        this.speedToggleState = new SpeedToggleState();
        this.exoSpeedRestoreState = new ExoSpeedRestoreState();
        this.dynamicLutEffect = new DynamicLutEffect();
        this.audioFocusChangeListener = this::onNativeAudioFocusChanged;
        this.noisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) return;
                onNativeAudioBecomingNoisy();
            }
        };
        // 播放页可能在服务起来之前就定好了本次要用的内核（按剧集记住的选择），
        // 所以这里读会话内核；没有会话时它自然退回设置页的全局默认。
        this.playerType = PlayerSetting.getActivePlayer();
        PlayerSetting.putActivePlayer(this.playerType);
        this.playerFallbackTried = new boolean[PLAYER_COUNT];
        mediaSession.begin(0L);
        this.engine = buildEngine(playerType, PlayerEngine.HARD);
        this.player = engine.getPlayer();
    }

    public void release() {
        mediaSession.beforeRelease();
        PlayerSetting.clearActivePlayer();
        prepareSeq++;
        exoSpeedRestoreState.clear();
        lutApplySeq++;
        resetNetworkProtectionSession("release");
        clearExoDecoderResourceRecovery(true);
        player.removeListener(listener);
        App.removeCallbacks(runnable);
        cancelBufferingStallWatchdog();
        App.removeCallbacks(networkProtectionRunnable);
        App.removeCallbacks(playbackTelemetryRunnable);
        ijkBufferMemoryRegistration.close();
        playbackExperimentRegistration.close();
        stopNativeAudioSession();
        clearDanmaku("release");
        releaseLiveDanmakuSession();
        liveDanmakuBatcher.release();
        App.removeCallbacks(liveDanmakuMetricsRunnable);
        if (danmakuController != null) danmakuController.setListener(null);
        danmakuController = null;
        endPlaybackTelemetrySession("release");
        clearPlaybackAutoContext();
        ijkRuntimeTemporaryFallback = false;
        ijkRuntimeManualOverride = false;
        pendingIjkRuntimeFallbackReparse = false;
        if (engine == null) {
            mediaSession.close();
            return;
        }
        engine.release();
        engine = null;
        player = null;
        realtimeSubtitleTrackSelection = null;
        videoEffectsActive = false;
        videoEffectsDirty = false;
        lutAppliedForItem = false;
        lutApplyInProgress = false;
        lutPipelineReadyForItem = false;
        lutPipelinePrepareInProgress = false;
        mediaSession.close();
        pendingLutPreview = false;
        waitingLutBeforePlay = false;
        lutWarmupReloadPreviewPending = false;
        clearLutWarmupRecovery();
        playbackBufferingTracker.reset();
        playbackTrace.clear();
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
    }


    private boolean experimentAllowed(PlaybackExperimentPolicy.Action action) {
        return PlaybackExperimentSetting.isAllowed(action);
    }

    private void onPlaybackExperimentPolicyChanged(
            PlaybackExperimentCoordinator.Update update) {
        if (update == null) return;
        invalidatePlaybackProfileAssessments(
                PlaybackProfileAbCoordinator.InvalidationReason
                        .GENERATION_CHANGED);
        PlaybackExperimentPolicy.State policy =
                PlaybackExperimentSetting.getState();
        PlaybackTrace.log(
                "playback-experiment",
                playbackTrace.current(),
                "generation=%d change=%s strategy=%s frameAb=%s profileAb=%s action=invalidate-internal-experiments",
                update.generation(),
                update.change(),
                policy.strategyId(),
                policy.allows(PlaybackExperimentPolicy.Action
                        .EXO_FRAME_SCHEDULING_AB),
                policy.allows(PlaybackExperimentPolicy.Action
                        .SHARED_PROFILE_AB_VALIDATION));
    }

    private void resetLutRuntimeState(String reason, boolean clearEngineEffects) {
        lutApplySeq++;
        if (clearEngineEffects && engine != null && engine.supportsNativeLut()) {
            safeSetNativeLut(null, reason + "_reset");
        } else if (clearEngineEffects && engine != null && videoEffectsActive) {
            try {
                engine.setVideoEffects(Collections.emptyList());
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "clear effects before reset reason=%s", reason);
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "clear effects before reset failed reason=%s error=%s", reason, causeChain(e));
            }
        }
        dynamicLutEffect.clear();
        videoEffectsActive = false;
        videoEffectsDirty = false;
        lutAppliedForItem = false;
        lutApplyInProgress = false;
        lutPipelineReadyForItem = false;
        lutPipelinePrepareInProgress = false;
        pendingLutPreview = false;
        waitingLutBeforePlay = false;
        lutWarmupReloadPreviewPending = false;
        clearLutWarmupRecovery();
    }

    public Player getPlayer() {
        return player;
    }

    public ExoAssSession getAssSession() {
        return engine instanceof ExoPlayerEngine exo ? exo.getAssSession() : null;
    }

    public com.fongmi.android.tv.player.exo.subtitle.ExoSubtitleSession getSubtitleSession() {
        return engine instanceof ExoPlayerEngine exo ? exo.getSubtitleSession() : null;
    }

    public Tracks getCurrentTracks() {
        return engine.getCurrentTracks();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return engine.getCurrentMediaEditions();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public String getPlaybackTraceId() {
        return playbackTrace.current();
    }

    public PlaybackAutoContext getPlaybackAutoContext() {
        return playbackAutoContextStore.snapshot();
    }

    public void publishPlaybackRenderTarget(PlaybackAutoContext.RenderTarget renderTarget) {
        playbackMediaFactsCoordinator.publishRenderTarget(
                playbackAutoSession, renderTarget, SystemClock.elapsedRealtime());
    }

    public void publishPlaybackDisplayFacts(
            PlaybackAutoContext.DisplayMode currentMode,
            PlaybackAutoContext.DisplayMode requestedMode) {
        playbackMediaFactsCoordinator.publishDisplayFacts(
                playbackAutoSession, currentMode, requestedMode, SystemClock.elapsedRealtime());
    }

    public void publishPlaybackDecision(PlaybackTelemetry.DecisionEvent event) {
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession, event, SystemClock.elapsedRealtime());
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public boolean supportsPlaylistQueue() {
        return engine != null && engine.supportsPlaylistQueue();
    }

    public boolean appendPlaylistItem(PlaySpec nextSpec, String mediaId) {
        return engine != null && engine.appendPlaylistItem(nextSpec, mediaId);
    }

    public boolean removePlaylistItemsAfterCurrent() {
        return engine != null && engine.removePlaylistItemsAfterCurrent();
    }

    public boolean setPlaylistPreloadDurationMs(long durationMs) {
        return engine != null && engine.setPlaylistPreloadDurationMs(durationMs);
    }

    /** Commits a Media3 natural transition without replacing the active playlist/player. */
    public boolean commitPlaylistTransition(PlaySpec nextSpec) {
        if (engine == null || nextSpec == null) return false;
        if (spec != null) nextSpec.setPlaybackTraceId(spec.getPlaybackTraceId());
        if (!engine.commitPlaylistTransition(nextSpec)) return false;
        spec = nextSpec;
        bindPlaybackTrace();
        return true;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    public Sub getSelectedSubtitleSub() {
        return spec == null ? null : findSelectedSubtitleSub(spec.getSubs(), getCurrentTracks());
    }

    public List<Sub> getSubtitleSubs() {
        return spec == null || spec.getSubs() == null ? Collections.emptyList() : spec.getSubs();
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public Result getCurrentResult() {
        return snapshotCurrentResult(spec);
    }

    static Result snapshotCurrentResult(PlaySpec spec) {
        if (spec == null) return Result.empty();
        return Result.playbackSnapshot(
                spec.getParseResult(),
                spec.getUrl(),
                spec.getHeaders(),
                spec.getFormat(),
                spec.getDrm(),
                spec.getSubs());
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return userPlaybackSpeed;
    }

    public float getEffectiveSpeed() {
        return player == null ? userPlaybackSpeed : player.getPlaybackParameters().speed;
    }

    public String getNetworkProtectionText() {
        if (!isExo() || !ExoPerformanceSetting.isNetworkProtectionEnabled()) return "";
        if (Math.abs(userPlaybackSpeed - 1f) > 0.001f) return "手动倍速时停用";
        if (!isVod()) return "仅支持点播";
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        if (!eligibility.eligible()) return networkProtectionEligibilityText(eligibility.reason());
        return switch (networkProtectionState) {
            case NORMAL -> "正常";
            case WARNING -> "评估中";
            case PROTECT -> "降速中";
            case RECOVERY -> "恢复中";
            case UNSUSTAINABLE -> "网络不足";
        };
    }

    private String networkProtectionEligibilityText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "preserve-passthrough" -> "音频直通时停用";
            case "preserve-tunneling" -> "隧道模式时停用";
            case "speed-unsupported" -> "播放器不支持调速";
            case "user-speed" -> "手动倍速时停用";
            case "vod-only" -> "仅支持点播";
            default -> "未启用";
        };
    }

    public long getNetworkProtectionMediaBitrate() {
        return networkProtectionMediaBitrate;
    }

    /** Runtime metrics reported by the active native engine, or unknown when absent. */
    public PlayerEngine.RuntimeMetrics getRuntimeMetrics() {
        return engine == null ? PlayerEngine.RuntimeMetrics.unknown() : engine.getRuntimeMetrics();
    }

    public long getNetworkProtectionStableThroughput() {
        return networkProtectionMediaBitrate <= 0 ? 0 : Math.max(0, Math.round(networkProtectionMediaBitrate * networkProtectionSupportedSpeed));
    }

    public long getNetworkProtectionConsumption() {
        return networkProtectionMediaBitrate <= 0 ? 0 : Math.max(0, Math.round(networkProtectionMediaBitrate * getEffectiveSpeed()));
    }

    public float getNetworkProtectionSupportedSpeed() {
        return networkProtectionSupportedSpeed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return engine != null && engine.isLive();
    }

    public boolean isVod() {
        return engine != null && engine.isVod();
    }

    public boolean haveTrack(int type) {
        return engine.haveTrack(type);
    }

    public boolean haveTitle() {
        return engine.haveTitle();
    }

    public boolean haveDanmaku() {
        return getDanmakus() != null && getDanmakus().stream().anyMatch(Danmaku::isSelected);
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public PlaybackMediaSignalHub mediaSignals() {
        return mediaSignals;
    }

    public PlaybackMediaClock mediaClock() {
        return mediaClock;
    }

    public long getBufferedDuration() {
        return Math.max(0, getEffectiveBufferedPosition() - getPosition());
    }

    /** Raw Exo buffered duration, free of the disk-range folding used for the progress bar. */
    public long getNativeBufferedDuration() {
        return player == null ? 0 : Math.max(0, player.getBufferedPosition() - getPosition());
    }

    public String getStartupSummary() {
        return playbackTrace.startupSummary();
    }

    public String getSlowestStartupStage() {
        return playbackTrace.slowestStage();
    }

    public int getBufferedPercentage() {
        if (!isExo()) return player.getBufferedPercentage();
        long duration = player.getDuration();
        if (duration == 0) return 100;
        if (duration < 0) return 0;
        return Math.max(0, Math.min(100, androidx.media3.common.util.Util.percentInt(
                getEffectiveBufferedPosition(), duration)));
    }

    private long getEffectiveBufferedPosition() {
        long nativeBuffered = Math.max(0, player.getBufferedPosition());
        if (!isExo()) return nativeBuffered;
        String mediaKey = PlaybackDiskBufferStore.mediaKey(player.getCurrentMediaItem());
        return PlaybackDiskBufferStore.process().effectiveEnd(
                mediaKey, nativeBuffered, player.getDuration(), DISK_RANGE_GAP_TOLERANCE_MS);
    }

    public boolean isLoading() {
        return player.isLoading();
    }

    public String getSizeText() {
        int width = getVideoWidth();
        int height = getVideoHeight();
        if (width <= 0 || height <= 0) {
            Format format = getVideoFormat();
            if (format != null) {
                if (width <= 0) width = format.width;
                if (height <= 0) height = format.height;
            }
        }
        return width <= 0 || height <= 0 ? "" : width + " x " + height;
    }

    public String getVideoParamsText() {
        StringBuilder builder = new StringBuilder();
        append(builder, "分辨率", getSizeText());
        Format video = getSelectedFormat(C.TRACK_TYPE_VIDEO);
        Format audio = getSelectedFormat(C.TRACK_TYPE_AUDIO);
        if (video != null) {
            if (video.frameRate > 0) append(builder, "帧率", String.format(Locale.getDefault(), "%.2f fps", video.frameRate));
            append(builder, "视频编码", firstText(video.codecs, video.sampleMimeType, video.containerMimeType));
            append(builder, "视频码率", formatBitrate(video.averageBitrate > 0 ? video.averageBitrate : video.peakBitrate));
        }
        if (audio != null) {
            append(builder, "音频编码", firstText(audio.codecs, audio.sampleMimeType, audio.containerMimeType));
            append(builder, "采样率", audio.sampleRate > 0 ? audio.sampleRate + " Hz" : "");
            append(builder, "声道", audio.channelCount > 0 ? String.valueOf(audio.channelCount) : "");
            append(builder, "音频码率", formatBitrate(audio.averageBitrate > 0 ? audio.averageBitrate : audio.peakBitrate));
        }
        append(builder, "解码", getDecodeText());
        append(builder, "倍速", getSpeedText());
        append(builder, "时长", getDurationTime());
        return builder.toString();
    }

    public Format getVideoFormat() {
        return engine.getVideoFormat();
    }

    public PlayerCacheState getCacheState() {
        return engine == null ? PlayerCacheState.empty() : engine.getCacheState();
    }

    public String getRenderDiagnostics() {
        return engine == null ? "" : engine.getRenderDiagnostics();
    }

    public String getRuntimeDiagnostics() {
        return engine == null ? "" : engine.getRuntimeDiagnostics();
    }

    public String getGpuLoadDiagnostics() {
        return engine == null ? "" : engine.getGpuLoadDiagnostics();
    }

    public void setGpuLoadDiagnosticsEnabled(boolean enabled) {
        if (engine != null) engine.setGpuLoadDiagnosticsEnabled(enabled);
    }

    public PlayerEngine.VideoPlaybackDetails getVideoPlaybackDetails() {
        return engine == null
                ? PlayerEngine.VideoPlaybackDetails.empty()
                : engine.getVideoPlaybackDetails();
    }

    public AudioPlaybackDiagnostics.Snapshot getAudioPlaybackDiagnostics() {
        return engine == null
                ? AudioPlaybackDiagnostics.Snapshot.empty()
                : engine.getAudioPlaybackDiagnostics();
    }

    public long getDroppedFrames() {
        return engine == null ? 0 : engine.getDroppedFrames();
    }

    public int getRebufferCount() {
        return (int) Math.min(Integer.MAX_VALUE, playbackBufferingTracker.getRebufferCount());
    }

    public long getRebufferTotalMs() {
        return getRebufferTotalMs(SystemClock.elapsedRealtime());
    }

    private long getRebufferTotalMs(long nowMs) {
        return playbackBufferingTracker.getRebufferTotalMs(nowMs);
    }

    public boolean supportsSubtitleStyle() {
        return engine != null && engine.supportsSubtitleStyle();
    }

    public boolean supportsSecondarySubtitle() {
        return engine != null && engine.supportsSecondarySubtitle();
    }

    public boolean isSecondarySubtitleSelected(Format format) {
        return engine != null && engine.isSecondarySubtitleSelected(format);
    }

    public boolean isPrimarySubtitleSelected(Format format) {
        return engine != null && engine.isPrimarySubtitleSelected(format);
    }

    public String getAudioPassThroughText() {
        if (!PlayerSetting.isAudioPassThrough(playerType)) return "关";
        return "开";
    }

    public void setSubtitleStyle(float textSize, float position) {
        if (engine != null) engine.setSubtitleStyle(textSize, position);
    }

    public void setVideoAspect(float aspectRatio, boolean stretch) {
        if (engine != null) engine.setVideoAspect(aspectRatio, stretch);
    }

    public String getSpeedText() {
        return SPEED_FORMAT.format(getSpeed());
    }

    /**
     * Decode label that reflects the running decoder, not only the configured profile. In the
     * hard-decode profile Exo still installs the FFmpeg renderer as a fallback for codecs
     * MediaCodec refuses, so a session labelled 硬解 can be decoding in software; showing only
     * the configured value hides exactly the case a user needs when playback is slow.
     */
    public String getDecodeText() {
        return DecodeLabelPolicy.decodeLabel(
                engine.getDecodeText(), getSoftDecodeLabel(),
                engine.isHard(), getActualDecodeMode());
    }

    /** Index 0 of the same localized array every engine's label comes from. */
    private String getSoftDecodeLabel() {
        String[] labels = ResUtil.getStringArray(R.array.select_decode);
        return labels.length > PlayerEngine.SOFT ? labels[PlayerEngine.SOFT] : "";
    }

    /**
     * The load-shedding mode IJK actually applied, or null when not on IJK. Read from the
     * engine rather than re-derived from settings: IJK forces {@code TuneMode.OFF} in the
     * hard-decode profile even when a mode is configured, so re-deriving would claim shedding
     * is active while it is not.
     */
    @Nullable
    public IjkDecodePressurePolicy.TuneMode getAppliedIjkTuneMode() {
        return engine instanceof IjkPlayerEngine ijk
                ? ijk.getAppliedDecodeControlConfig().tuneMode() : null;
    }

    /** Configured profile only; callers deciding behavior must not see the label adjustment. */
    public boolean isHardDecode() {
        return engine.isHard();
    }

    /** True when the profile says hardware but a software decoder is actually running. */
    public boolean isHardProfileRunningSoftware() {
        return DecodeLabelPolicy.isHardwareProfileRunningSoftware(
                engine.isHard(), getActualDecodeMode());
    }

    /**
     * The decode mode already resolved by {@link PlaybackMediaFactsMapper}, which trusts each
     * engine's self-reported decoder kind before falling back to name parsing. Reading it here
     * keeps one source of truth and works for every kernel, not only Exo.
     */
    public PlaybackAutoContext.DecodeMode getActualDecodeMode() {
        PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> fact =
                playbackAutoContextStore.snapshot().media().decoder().videoDecodeMode();
        return fact.hasValue() ? fact.value() : PlaybackAutoContext.DecodeMode.UNKNOWN;
    }

    public String getPlayerText() {
        return getPlayerText(playerType);
    }

    public int getPlayerType() {
        return playerType;
    }

    public String getLutText() {
        return LutSetting.getButtonText();
    }

    public void setLutAllowed(boolean allowed) {
        if (lutAllowed == allowed) return;
        lutAllowed = allowed;
        if (!allowed) resetLutRuntimeState("lut_disallowed", true);
    }

    public String getLutUnavailableReason() {
        return LutEligibility.getUnavailableReason(engine, spec);
    }

    public boolean selectLut(@Nullable LutPreset preset, boolean preview) {
        if (preset != null) {
            String reason = getLutUnavailableReason();
            if (!TextUtils.isEmpty(reason)) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-ui", "reject preset=%s reason=%s", preset.getId(), reason);
                Notify.show(reason);
                return false;
            }
        }
        LutSetting.select(preset);
        callback.onPlayerRenderRequired();
        if (preset != null && preview) applyLutPreview(true);
        else applyLut(true);
        return true;
    }

    public boolean isIjk() {
        return playerType == PlayerSetting.IJK;
    }

    public boolean useNativeVideoOutput() {
        return PlayerSetting.useNativeVideoOutput(playerType);
    }

    private String getPlayerText(int type) {
        String[] items = ResUtil.getStringArray(R.array.select_player_kernel);
        return type >= 0 && type < items.length ? items[type] : items[PlayerSetting.EXO];
    }

    private Format getSelectedFormat(int type) {
        return getSelectedFormat(getCurrentTracks(), type);
    }

    static Format getSelectedFormat(Tracks tracks, int type) {
        if (tracks == null || tracks.isEmpty()) return null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i);
            }
        }
        return null;
    }

    static Sub findSelectedSubtitleSub(List<Sub> subs, Tracks tracks) {
        Sub selected = findSubtitleSub(subs, getSelectedFormat(tracks, C.TRACK_TYPE_TEXT));
        if (selected != null || hasTrack(tracks, C.TRACK_TYPE_TEXT)) return selected;
        return firstSubtitleSub(subs);
    }

    static Sub findSubtitleSub(List<Sub> subs, Format format) {
        if (subs == null || format == null) return null;
        Sub mimeLanguageMatch = null;
        for (Sub sub : subs) {
            if (sub == null) continue;
            if (!TextUtils.isEmpty(format.label) && TextUtils.equals(format.label, sub.getName()) && mimeMatches(sub, format)) return sub;
            if (TextUtils.isEmpty(format.label) && mimeMatches(sub, format) && languageMatches(sub, format)) {
                if (mimeLanguageMatch != null) return null;
                mimeLanguageMatch = sub;
            }
        }
        return mimeLanguageMatch;
    }

    private static boolean hasTrack(Tracks tracks, int type) {
        if (tracks == null || tracks.isEmpty()) return false;
        for (Tracks.Group group : tracks.getGroups()) if (group.getType() == type && group.length > 0) return true;
        return false;
    }

    private static Sub firstSubtitleSub(List<Sub> subs) {
        if (subs == null) return null;
        for (Sub sub : subs) if (sub != null && !TextUtils.isEmpty(sub.getUrl())) return sub;
        return null;
    }

    private static boolean mimeMatches(Sub sub, Format format) {
        return TextUtils.isEmpty(format.sampleMimeType) || TextUtils.isEmpty(sub.getFormat()) || TextUtils.equals(format.sampleMimeType, sub.getFormat());
    }

    private static boolean languageMatches(Sub sub, Format format) {
        return TextUtils.isEmpty(format.language) || TextUtils.isEmpty(sub.getLang()) || TextUtils.equals(format.language, sub.getLang());
    }

    private static void append(StringBuilder builder, String name, String value) {
        if (TextUtils.isEmpty(value)) return;
        builder.append(name).append(" : ").append(value).append("\n");
    }

    private static String firstText(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private static String formatBitrate(int bitrate) {
        if (bitrate <= 0) return "";
        return bitrate >= 1_000_000 ? String.format(Locale.getDefault(), "%.2f Mbps", bitrate / 1_000_000f) : bitrate / 1000 + " Kbps";
    }

    public boolean isMpv() {
        return playerType == PlayerSetting.MPV;
    }

    public boolean sendMpvCustomButton(String id, boolean longPress) {
        // The MPV kernel is gone, so no script messages can reach an engine anymore.
        return false;
    }

    public boolean isMpvSurfaceDirect() {
        return false;
    }

    /**
     * The MPV probe frame that this shutter used to hide can no longer exist,
     * so the shutter must never be held closed on its account.
     */
    public boolean shouldKeepVideoShutterClosed() {
        return false;
    }

    public boolean isExo() {
        return playerType == PlayerSetting.EXO;
    }

    public boolean requiresTextureRenderForLut() {
        return isExo()
                && LutSetting.isEnabled()
                && canWarmLutPipeline()
                && TextUtils.isEmpty(getLutUnavailableReason());
    }

    public boolean isNativePlayer() {
        return !isExo();
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (sub == null || spec == null) return;
        Track.delete(getKey(), C.TRACK_TYPE_TEXT);
        engine.resetTrack(C.TRACK_TYPE_TEXT);
        spec.setSub(sub);
        // 这里是本地文件、在线搜索、AI 翻译、局域网推送四条路径的唯一收口，
        // 一处上报就够，不需要在各 UI 入口分别接线。集地址由宿主从自己的
        // History 里取，PlayerManager 不掺和历史记录的事。
        callback.onSubtitleSelected(sub);
        restartCurrentItemWithState();
    }

    /**
     * 登记一条待恢复的外挂字幕，下一次起播时注入。
     *
     * <p>必须在 {@code start()} / {@code parse()} 之前调用。是否该恢复由宿主用
     * {@code SubtitleRestorePolicy} 判定，这里只负责搬运。
     */
    public void setPendingRestoreSub(Sub sub) {
        pendingRestoreSub = sub;
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        setMediaItem();
    }

    public void setTitle(MediaEdition edition) {
        if (edition == null) return;
        if (spec != null) spec.setUrl(spec.getUri().buildUpon().fragment("edition=" + edition.index).build().toString());
        if (engine.selectEdition(edition)) return;
        setMediaItem();
        seekTo(0);
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        engine.setMetadata(data);
    }

    public void setDanmakuController(DanmakuController controller) {
        if (danmakuController == controller) {
            configureDanmakuController(controller);
            return;
        }
        if (danmakuController != null) {
            danmakuController.setListener(null);
            danmakuController.clearItems();
        }
        danmakuController = controller;
        if (danmakuController == null) return;
        configureDanmakuController(danmakuController);
        restoreDanmakuDataSource();
    }

    private void configureDanmakuController(DanmakuController controller) {
        if (controller == null) return;
        controller.setOkHttpClient(OkHttp.player());
        controller.setConfig(DanmakuSetting.getConfig());
        controller.setEnabled(DanmakuSetting.isShow());
        controller.setListener(new DanmakuController.Listener() {
            @Override
            public void onLoadCompleted(Uri uri, int count) {
                logDanmakuLoad("completed", uri, count, null);
                finishDanmakuLoad(uri);
            }

            @Override
            public void onLoadError(Uri uri, IOException error) {
                logDanmakuLoad("error", uri, -1, error);
                finishDanmakuLoad(uri);
            }
        });
    }

    private void restoreDanmakuDataSource() {
        if (danmakuController == null || TextUtils.isEmpty(currentDanmakuUrl)) return;
        if (!DanmakuUrlPolicy.classify(currentDanmakuUrl).isStatic()) return;
        loadingDanmakuKey = currentDanmakuKey;
        danmakuLoadStartedAtMs = SystemClock.elapsedRealtime();
        danmakuLoadInProgress = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "restore controller %s key=%s", DanmakuUrlPolicy.logSummary(currentDanmakuUrl), summarizeUrl(currentDanmakuKey));
        danmakuController.setDataSource(Uri.parse(currentDanmakuUrl));
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        if (danmakuController != null) danmakuController.setConfig(config);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuController != null) danmakuController.setEnabled(enabled);
        if (!enabled) {
            stopLiveDanmakuSession("hidden");
        } else if (danmakuForeground && DanmakuUrlPolicy.classify(currentDanmakuUrl).isLive()) {
            connectLiveDanmakuSession(currentDanmakuUrl);
        }
    }

    public void setDanmakuForeground(boolean foreground) {
        if (danmakuForeground == foreground) return;
        danmakuForeground = foreground;
        if (!foreground) {
            stopLiveDanmakuSession("background");
            discardLiveDanmakuPending();
        } else if (DanmakuSetting.isShow() && DanmakuUrlPolicy.classify(currentDanmakuUrl).isLive()) {
            connectLiveDanmakuSession(currentDanmakuUrl);
        }
    }

    public void setPlaybackForeground(boolean foreground) {
        playbackForeground = foreground;
        if (!foreground || pendingExoDecoderResourceRecovery == null) return;
        ExoDecoderResourceRecovery recovery = pendingExoDecoderResourceRecovery;
        pendingExoDecoderResourceRecovery = null;
        scheduleExoDecoderResourceRecovery(recovery, "foreground");
    }

    public void sendDanmaku(String text) {
        if (danmakuController != null) danmakuController.sendNow(text);
    }

    public String setSpeed(float speed) {
        speedToggleState.clear();
        return applySpeed(speed);
    }

    private String applySpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        RealtimeSubtitleController realtime = RealtimeSubtitleController.get();
        if (Math.abs(speed - 1f) > 0.001f && (realtime.isEnabled() || realtime.isPreparing())) {
            realtime.disable();
            Notify.show(R.string.subtitle_realtime_speed_disabled);
        }
        if (Math.abs(speed - userPlaybackSpeed) >= 0.001f) {
            invalidatePlaybackProfileAssessments(
                    PlaybackProfileAbCoordinator.InvalidationReason.USER_SPEED);
        }
        userPlaybackSpeed = speed;
        resetNetworkProtectionSession("user-speed");
        if (Math.abs(speed - 1f) < 0.001f) scheduleNetworkProtection(0);
        return getSpeedText();
    }

    private void applyEffectiveSpeed(float speed, String reason) {
        if (player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            logNetworkGuard("apply skipped reason=" + reason + " player=" + (player != null)
                    + " command=" + (player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)));
            return;
        }
        float current = player.getPlaybackParameters().speed;
        if (Math.abs(speed - userPlaybackSpeed) >= 0.001f) {
            invalidatePlaybackProfileAssessments(
                    PlaybackProfileAbCoordinator.InvalidationReason
                            .SPEED_RESCUE_CONFOUND);
        }
        logNetworkGuard(String.format(java.util.Locale.US,
                "apply request reason=%s requested=%.3f current=%.3f user=%.3f state=%d playing=%s loading=%s",
                reason, speed, current, userPlaybackSpeed, player.getPlaybackState(), player.isPlaying(), player.isLoading()));
        if (Math.abs(current - speed) < 0.001f) return;
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        logNetworkGuard(String.format(java.util.Locale.US,
                "apply result reason=%s requested=%.3f actual=%.3f", reason, speed, player.getPlaybackParameters().speed));
        PlaybackTrace.log("exo-network-protection", playbackTrace.current(), "speed %.3f->%.3f reason=%s user=%.2f", current, speed, reason, userPlaybackSpeed);
    }

    private void resetNetworkProtectionSession(String reason) {
        App.removeCallbacks(networkProtectionRunnable);
        networkProtectionController.reset();
        networkProtectionTrend.reset();
        networkProtectionState = ExoNetworkGuardController.State.NORMAL;
        networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
        networkProtectionReason = reason;
        networkProtectionSpeed = 1f;
        networkProtectionSupportedSpeed = 1f;
        networkProtectionMediaBitrate = 0;
        networkProtectionExperimentToken = null;
        if (isExo() && exoSpeedRestoreState.deferSpeed(userPlaybackSpeed)) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "exo speed deferred target=%.2f reason=%s", userPlaybackSpeed, reason);
        } else {
            applyEffectiveSpeed(userPlaybackSpeed, reason);
        }
    }

    private ExoNetworkGuardEligibility.Decision getNetworkProtectionEligibility() {
        AudioPlaybackDiagnostics.OutputMode audioOutputMode = getAudioPlaybackDiagnostics()
                .outputMode();
        return ExoNetworkGuardEligibility.resolve(new ExoNetworkGuardEligibility.Request(
                ExoPerformanceSetting.isNetworkProtectionEnabled()
                        && experimentAllowed(
                        PlaybackExperimentPolicy.Action.EXO_NETWORK_SPEED),
                player != null && isExo(),
                isVod(),
                Math.abs(userPlaybackSpeed - 1f) < 0.001f,
                player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
                PlayerSetting.isTunnel(),
                audioOutputMode));
    }

    private void scheduleNetworkProtection(long delayMs) {
        App.removeCallbacks(networkProtectionRunnable);
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        AudioPlaybackDiagnostics.OutputMode audioOutputMode = getAudioPlaybackDiagnostics()
                .outputMode();
        logNetworkGuard("schedule delay=" + delayMs + " eligible=" + eligibility.eligible()
                + " reason=" + eligibility.reason() + " exo=" + isExo() + " vod=" + isVod()
                + " userSpeed=" + userPlaybackSpeed + " tunnel=" + PlayerSetting.isTunnel()
                + " configuredPassthrough=" + PlayerSetting.isAudioPassThrough(PlayerSetting.EXO)
                + " audioOutput=" + audioOutputMode
                + " state=" + (player == null ? -1 : player.getPlaybackState())
                + " playing=" + (player != null && player.isPlaying()));
        if (!eligibility.eligible()) {
            if (networkProtectionSpeed < 0.999f) resetNetworkProtectionSession(eligibility.reason());
            else {
                networkProtectionState = ExoNetworkGuardController.State.NORMAL;
                networkProtectionTier = ExoNetworkGuardController.ProtectionTier.NONE;
                networkProtectionReason = eligibility.reason();
            }
            return;
        }
        if (player.getPlaybackState() != Player.STATE_READY || !player.isPlaying()) return;
        networkProtectionExperimentToken = playbackExperimentCoordinator.capture(
                PlaybackExperimentPolicy.Action.EXO_NETWORK_SPEED);
        App.post(networkProtectionRunnable, delayMs);
    }

    private void evaluateNetworkProtection() {
        if (player == null) return;
        if (!playbackExperimentCoordinator.isCurrent(
                networkProtectionExperimentToken)
                || !experimentAllowed(
                PlaybackExperimentPolicy.Action.EXO_NETWORK_SPEED)) {
            resetNetworkProtectionSession("experiment-disabled");
            return;
        }
        ExoNetworkGuardEligibility.Decision eligibility = getNetworkProtectionEligibility();
        boolean eligible = eligibility.eligible();
        long nowMs = SystemClock.elapsedRealtime();
        boolean ready = player.getPlaybackState() == Player.STATE_READY;
        boolean playing = player.isPlaying();
        boolean loading = player.isLoading();
        long bufferedMs = Math.max(0, player.getTotalBufferedDuration());
        networkProtectionTrend.observe(
                nowMs,
                bufferedMs,
                eligible && ready && playing,
                loading);
        ForwardBufferTrend.Snapshot trend = networkProtectionTrend.snapshot();
        PlaybackAnalyticsListener.Snapshot analytics = PlaybackAnalyticsListener.getSnapshot();
        PlaybackAnalyticsListener.DisplayMediaBitrateEstimate media = PlaybackAnalyticsListener.getDisplayMediaBitrateEstimate(getVideoFormat());
        boolean networkEstimateKnown = isTrustedNetworkEstimate(analytics, media);
        float networkSupportedSpeed = networkEstimateKnown ? Math.min(2f, analytics.bandwidthEstimate() * 0.90f / media.bitrateBitsPerSecond()) : 1f;
        long safeBufferMs = getNetworkProtectionSafeBufferMs();
        float previousEffectiveSpeed = getEffectiveSpeed();
        ExoNetworkGuardController.State previousState = networkProtectionState;
        ExoNetworkGuardController.ProtectionTier previousTier = networkProtectionTier;
        ExoNetworkGuardController.Decision decision = networkProtectionController.evaluate(new ExoNetworkGuardController.Input(
                nowMs,
                eligible,
                ready,
                playing,
                loading,
                bufferedMs,
                trend.known(),
                trend.slopeMsPerSecond(),
                trend.fastSlopeMsPerSecond(),
                trend.slowSlopeMsPerSecond(),
                trend.windowMs(),
                analytics.rebufferCount(),
                previousEffectiveSpeed,
                ExoPerformanceSetting.getNetworkProtectionMinimumSpeed(),
                safeBufferMs,
                networkEstimateKnown,
                networkSupportedSpeed));
        logNetworkGuard(String.format(java.util.Locale.US,
                "evaluate eligible=%s ready=%s playing=%s loading=%s buffered=%d safe=%d trendKnown=%s slope=%d fast=%d slow=%d window=%d rebuffer=%d current=%.3f networkKnown=%s networkSupported=%.3f decision=%s tier=%s reason=%s changed=%s target=%.3f supported=%.3f raw=%.3f calculated=%.3f tte=%d ttr=%d requiredSlew=%.4f appliedSlew=%.4f feasible=%s",
                eligible, ready, playing, loading, bufferedMs, safeBufferMs, trend.known(), trend.slopeMsPerSecond(),
                trend.fastSlopeMsPerSecond(), trend.slowSlopeMsPerSecond(), trend.windowMs(), analytics.rebufferCount(),
                getEffectiveSpeed(), networkEstimateKnown, networkSupportedSpeed, decision.state(), decision.tier(), decision.reason(),
                decision.changed(), decision.targetSpeed(), decision.supportedSpeed(), decision.rawTargetSpeed(),
                decision.calculatedTargetSpeed(), decision.timeToEmptyMs(), decision.timeToReserveMs(),
                decision.requiredSlewPerSecond(), decision.appliedSlewPerSecond(), decision.rampFeasible()));
        networkProtectionState = decision.state();
        networkProtectionTier = decision.tier();
        networkProtectionReason = decision.reason();
        networkProtectionSpeed = decision.targetSpeed();
        networkProtectionSupportedSpeed = decision.supportedSpeed();
        networkProtectionMediaBitrate = media.bitrateBitsPerSecond();
        if (decision.changed()) applyEffectiveSpeed(networkProtectionSpeed, "guard-" + decision.reason());
        PlaybackTelemetry.DecisionOutcome telemetryOutcome = decision.changed()
                ? PlaybackTelemetry.DecisionOutcome.APPLIED
                : !eligible || !ready || !playing
                ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                : PlaybackTelemetry.DecisionOutcome.HELD;
        playbackTelemetryCoordinator.publishDecision(playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.NETWORK_PROTECTION,
                        telemetryOutcome,
                        previousState.name().toLowerCase(java.util.Locale.US),
                        decision.state().name().toLowerCase(java.util.Locale.US),
                        networkProtectionState.name().toLowerCase(java.util.Locale.US),
                        decision.reason(),
                        telemetryOutcome == PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                                ? eligibility.reason() : telemetryOutcome == PlaybackTelemetry.DecisionOutcome.HELD ? "no-change" : "none",
                        List.of(
                                PlaybackTelemetry.DecisionInput.bool("eligible", eligible, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("ready", ready, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("playing", playing, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.bool("loading", loading, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("buffered_ms", bufferedMs, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.number("safe_buffer_ms", safeBufferMs, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                                networkEstimateKnown ? PlaybackTelemetry.DecisionInput.number("bandwidth_bps", analytics.bandwidthEstimate(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("bandwidth_bps"),
                                media.bitrateBitsPerSecond() > 0 ? PlaybackTelemetry.DecisionInput.number("media_bitrate_bps", media.bitrateBitsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, telemetryConfidence(media.confidence())) : PlaybackTelemetry.DecisionInput.unknown("media_bitrate_bps"),
                                PlaybackTelemetry.DecisionInput.number("rebuffer_count", analytics.rebufferCount(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                trend.known() ? PlaybackTelemetry.DecisionInput.number("buffer_slope_msps", trend.slopeMsPerSecond(), PlaybackAutoContext.ValueSource.ESTIMATOR, PlaybackAutoContext.Confidence.MEDIUM) : PlaybackTelemetry.DecisionInput.unknown("buffer_slope_msps"),
                                PlaybackTelemetry.DecisionInput.decimal("current_speed", previousEffectiveSpeed, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH),
                                PlaybackTelemetry.DecisionInput.decimal("target_speed", decision.targetSpeed(), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH))),
                nowMs);
        if (decision.changed() || previousState != networkProtectionState || previousTier != networkProtectionTier) {
            PlaybackTrace.log("exo-network-protection", playbackTrace.current(), "state=%s tier=%s reason=%s speed=%.3f supported=%.3f rawTarget=%.3f target=%.3f floor=%.2f buffered=%d safe=%d tte=%d ttr=%d requiredSlew=%.4f appliedSlew=%.4f feasible=%s loading=%s slope=%d fast=%d slow=%d window=%d rebuffer=%d networkKnown=%s networkSupported=%.3f route=%s",
                    networkProtectionState, networkProtectionTier, networkProtectionReason, networkProtectionSpeed, decision.supportedSpeed(), decision.rawTargetSpeed(), decision.calculatedTargetSpeed(), ExoPerformanceSetting.getNetworkProtectionMinimumSpeed(),
                    player.getTotalBufferedDuration(), decision.safeBufferMs(), decision.timeToEmptyMs(), decision.timeToReserveMs(), decision.requiredSlewPerSecond(), decision.appliedSlewPerSecond(), decision.rampFeasible(),
                    player.isLoading(), trend.slopeMsPerSecond(), trend.fastSlopeMsPerSecond(), trend.slowSlopeMsPerSecond(), trend.windowMs(), analytics.rebufferCount(), networkEstimateKnown, networkSupportedSpeed, getEffectivePlaybackRoute().route());
        }
        if (eligible && player.getPlaybackState() == Player.STATE_READY && player.isPlaying()) scheduleNetworkProtection(getNetworkProtectionEvaluationDelayMs());
    }

    private long getNetworkProtectionEvaluationDelayMs() {
        return switch (networkProtectionState) {
            case WARNING, PROTECT, RECOVERY -> ExoNetworkGuardController.CONTROL_INTERVAL_MS;
            case NORMAL, UNSUSTAINABLE -> ExoNetworkGuardController.OBSERVE_INTERVAL_MS;
        };
    }

    private long getNetworkProtectionSafeBufferMs() {
        PlaybackRoute.Resolution route = getEffectivePlaybackRoute();
        return ExoNetworkGuardBufferPolicy.resolve(route.loopback(), ExoPerformanceSetting.getRebufferMs());
    }

    private boolean isTrustedNetworkEstimate(PlaybackAnalyticsListener.Snapshot analytics, PlaybackAnalyticsListener.DisplayMediaBitrateEstimate media) {
        if (analytics.bandwidthEstimate() <= 0 || media.bitrateBitsPerSecond() <= 0) return false;
        if ("unknown".equals(media.source()) || "low".equals(media.confidence()) || "unknown".equals(media.confidence())) return false;
        return getEffectivePlaybackRoute().route() == PlaybackRoute.DIRECT_REMOTE_HTTP;
    }

    public String addSpeed() {
        return setSpeed(nextPresetSpeed());
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        return toggleSpeed(1.0f, 1.0f);
    }

    public String toggleSpeed(float normalSpeed) {
        return toggleSpeed(normalSpeed, PlayerSetting.getDefaultSpeed());
    }

    private String toggleSpeed(float normalSpeed, float fallbackSpeed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        return applySpeed(speedToggleState.next(getSpeed(), normalSpeed, PlayerSetting.getSpeed(), fallbackSpeed));
    }

    static final class SpeedToggleState {

        private static final float EPSILON = 0.001f;
        private float restoreSpeed = Float.NaN;

        float next(float currentSpeed, float normalSpeed, float fastSpeed, float fallbackSpeed) {
            if (isClose(currentSpeed, fastSpeed)) {
                float target = isValid(restoreSpeed) ? restoreSpeed : restoreSpeed(normalSpeed, fastSpeed, fallbackSpeed);
                clear();
                return target;
            }
            restoreSpeed = restoreSpeed(normalSpeed, fastSpeed, fallbackSpeed);
            return fastSpeed;
        }

        void clear() {
            restoreSpeed = Float.NaN;
        }

        private static float restoreSpeed(float normalSpeed, float fastSpeed, float fallbackSpeed) {
            if (isValid(normalSpeed) && !isClose(normalSpeed, fastSpeed)) return normalSpeed;
            if (isValid(fallbackSpeed) && !isClose(fallbackSpeed, fastSpeed)) return fallbackSpeed;
            return 1.0f;
        }

        private static boolean isClose(float first, float second) {
            return Math.abs(first - second) < EPSILON;
        }

        private static boolean isValid(float speed) {
            return speed > 0 && !Float.isNaN(speed) && !Float.isInfinite(speed);
        }
    }

    static final class ExoSpeedRestoreState {

        private int generation = -1;
        private float targetSpeed = Float.NaN;

        float beginPrepare(int generation, float desiredSpeed) {
            this.generation = generation;
            this.targetSpeed = desiredSpeed;
            return 1.0f;
        }

        void cancelPrepare(int generation) {
            if (this.generation == generation) this.generation = -1;
        }

        boolean deferSpeed(float speed) {
            if (!hasDeferredSpeed()) return false;
            targetSpeed = speed;
            return true;
        }

        float effectiveSpeed(float actualSpeed) {
            return hasDeferredSpeed() ? targetSpeed : actualSpeed;
        }

        float takeReadySpeed(int generation) {
            if (!isPending() || this.generation != generation) return Float.NaN;
            float speed = targetSpeed;
            clear();
            return speed;
        }

        void clear() {
            generation = -1;
            targetSpeed = Float.NaN;
        }

        private boolean hasDeferredSpeed() {
            return !Float.isNaN(targetSpeed);
        }

        private boolean isPending() {
            return generation >= 0 && hasDeferredSpeed();
        }
    }

    private float nextPresetSpeed() {
        float speed = getSpeed();
        for (float preset : SPEED_PRESETS) if (speed < preset - 0.01f) return preset;
        return SPEED_PRESETS[0];
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) engine.setTrack(tracks);
    }

    public void setSecondarySubtitleTrack(Track track) {
        if (engine != null) engine.setSecondarySubtitleTrack(track);
    }

    public void play() {
        startNativeAudioSession(true);
        player.play();
    }

    public void pause() {
        invalidatePlaybackProfileAssessments(
                PlaybackProfileAbCoordinator.InvalidationReason.PAUSED);
        player.pause();
        stopNativeAudioSession();
    }

    public void stop() {
        stopNativeAudioSession();
        clearDanmaku("stop");
        engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        engine.cancelPendingPrepare();
        stopNativeAudioSession();
        clearDanmaku("clear_media_items");
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return engine.isRepeatOne();
    }

    public void setRepeatOne(boolean repeat) {
        engine.setRepeatOne(repeat);
    }

    public void seekTo(long time) {
        mediaSession.beforeSeek(Math.max(0L, time));
        long now = SystemClock.elapsedRealtime();
        invalidatePlaybackProfileAssessments(
                PlaybackProfileAbCoordinator.InvalidationReason.USER_SEEK);
        rtspLiveLagController.onUserSeek(playbackAutoSession, now);
        ijkRealtimeRecoveryController.onUserSeek(playbackAutoSession, now);
        ijkDecodePressureController.onUserSeek(playbackAutoSession, now);
        resetNetworkProtectionSession("user-seek");
        player.seekTo(time);
        cancelBufferingStallWatchdog();
        if (isExo()) armBufferingStallWatchdog();
    }

    public long getTextOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET)) return player.getTextOffsetMs();
        return 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        if (player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET)) return player.getAudioOffsetMs();
        return 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable);
        cancelBufferingStallWatchdog();
        boolean activePlayback = player != null
                && player.getPlaybackState() == Player.STATE_READY
                && player.getPlayWhenReady();
        if (activePlayback) scheduleNetworkProtection(0);
        else resetNetworkProtectionSession("reset");
        retry = 0;
        localProxyRetry = 0;
        resetPlayerFallback();
        hardDecodeSwitchRetryArmed = false;
        clearPendingSwitchRestore();
    }

    public void clear() {
        prepareSeq++;
        if (engine != null) engine.cancelPendingPrepare();
        lutApplySeq++;
        clearExoDecoderResourceRecovery(true);
        resetNetworkProtectionSession("clear");
        spec = null;
        // 待恢复字幕是绑定「下一次起播」的一次性登记。若那次起播被提前拦下
        // （DRM 不支持、阅读器路由、地址为空），登记会留在这里；不清掉的话
        // 会被后续某次无关起播（例如音频迷你播放器）取走并挂上。
        pendingRestoreSub = null;
        clearPendingSwitchRestore();
        clearDanmaku("clear");
        lutAppliedForItem = false;
        lutApplyInProgress = false;
        lutPipelineReadyForItem = false;
        lutPipelinePrepareInProgress = false;
        parseHealthRecorded = false;
        parseHealthStartedAt = 0;
        pendingLutPreview = false;
        waitingLutBeforePlay = false;
        clearLutWarmupRecovery();
        endPlaybackTelemetrySession("clear");
        playbackBufferingTracker.reset();
        clearPlaybackAutoContext();
        playbackTrace.clear();
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
    }

    public void resetTrack() {
        engine.resetTrack();
    }

public void resetTrack(int type) {
        engine.resetTrack(type);
    }

    public void disableSubtitleTrackForRealtime() {
        if (player == null || engine == null) return;
        if (isExo() && realtimeSubtitleTrackSelection == null) realtimeSubtitleTrackSelection = player.getTrackSelectionParameters();
        setTrack(Collections.singletonList(Track.disabled(C.TRACK_TYPE_TEXT, "")));
    }

    public void restoreSubtitleTrackAfterRealtime() {
        if (player == null || engine == null) {
            realtimeSubtitleTrackSelection = null;
            return;
        }
        if (isExo() && realtimeSubtitleTrackSelection != null) player.setTrackSelectionParameters(realtimeSubtitleTrackSelection);
        realtimeSubtitleTrackSelection = null;
    }

    public void rebuildAudioPipeline() {
        if (!isExo() || spec == null || spec.getUrl() == null || engine == null || player == null) return;
        PlaySpec target = spec;
        long position = isLive() ? C.TIME_UNSET : Math.max(0, getPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        mediaSignals.detachPipeline();
        mediaSession.reset(position == C.TIME_UNSET ? 0L : position,
                PlaybackMediaSignalHub.ResetReason.ENGINE_REBUILD);
        ++prepareSeq;
        App.removeCallbacks(runnable);
        rebuildPlayer();
        playWhenReady = wasPlayWhenReady;
        setDanmakus(target.getDanmakus());
        prepareLutPipeline();
        initTrack = false;
        waitingLutBeforePlay = false;
        applySubtitleStyle();
        startEngine(target, position, wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        callback.onPrepare();
    }

    public void restoreVideoTrack() {
        if (engine != null) engine.restoreVideoTrack();
    }

    public void toggleDecode() {
        beginIjkRuntimeManualOverride();
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        beginPlaybackTrace("switch-decode");
        engine.setDecode(next);
        if (engine instanceof ExoPlayerEngine exo) {
            exo.prepareFrameSchedulingForNextPlayback();
        }
        rebuildPlayer(resetVideoSurface);
        setMediaItem();
    }

    public void switchDecode(PlaySpec freshSpec, long position, float speed, boolean repeat) {
        if (engine == null || player == null || freshSpec == null) return;
        beginIjkRuntimeManualOverride();
        beginPlaybackTrace("switch-decode-fresh");
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        resetLutRuntimeState("switch_decode_fresh", true);
        stopNativeAudioSession();
        engine.release();
        spec = freshSpec;
        bindPlaybackTrace();
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        engine = buildEngine(playerType, next);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh decode=%d position=%d spec=%s", next, position, debugSpec());
        callback.onPlayerRebuild(player, resetVideoSurface);
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    public void switchDecode(Result result, String key, MediaMetadata metadata, boolean useParse, long position, float speed, boolean repeat) {
        if (engine == null || player == null || result == null || result.hasMsg() || result.getRealUrl().isEmpty()) return;
        beginIjkRuntimeManualOverride();
        beginPlaybackTrace("switch-decode-result");
        int next = engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean resetVideoSurface = playerType == PlayerSetting.EXO && next == PlayerEngine.HARD;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        resetLutRuntimeState("switch_decode_result", true);
        stopNativeAudioSession();
        stopParse();
        engine.release();
        hardDecodeSwitchRetryArmed = next == PlayerEngine.HARD;
        engine = buildEngine(playerType, next);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        callback.onPlayerRebuild(player, resetVideoSurface);
        if (result.needParse() || useParse) {
            pendingSwitchRestore = true;
            pendingSwitchPositionMs = position;
            pendingSwitchSpeed = speed;
            pendingSwitchRepeat = repeat;
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh parse decode=%d position=%d useParse=%s spec=%s", next, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            spec = PlaySpec.from(result, key, metadata);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch decode fresh result decode=%d position=%d spec=%s", next, position, debugSpec());
            setMediaItem(Constant.TIMEOUT_PLAY);
            if (position > 0) seekTo(position);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        }
    }

    public void togglePlayer() {
        switchPlayerManually(PlayerSetting.nextPlayer(playerType));
    }

    public void switchPlayer(int type) {
        switchPlayer(type, false);
    }

    public void switchPlayerManually(int type) {
        switchPlayer(type, true);
    }

    /**
     * 为即将开始的一次播放切到指定内核（通常来自历史记录里记住的选择）。
     * 与 switchPlayer 不同：这里不保留旧 spec 的进度、也不重新起播旧地址，
     * 因为调用方紧接着就会 start/parse 新地址；只换引擎并记下本次会话内核。
     */
    public void preparePlayer(int type) {
        int next = resolveAvailablePlayer(PlayerSetting.sanitizePlayer(type));
        PlayerSetting.putActivePlayer(next);
        if (engine == null || player == null || next == playerType) return;
        int decode = engine.getDecode();
        resetPlayerFallback();
        manualPlayerSwitchPending = false;
        beginPlaybackTrace("prepare-player");
        prepareSeq++;
        resetLutRuntimeState("prepare_player", true);
        stopNativeAudioSession();
        stopParse();
        engine.release();
        playerType = next;
        spec = null;
        clearPendingSwitchRestore();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "prepare player type=%d decode=%d", next, decode);
        engine = buildEngine(playerType, sanitizeDecode(decode));
        player = engine.getPlayer();
        callback.onPlayerRebuild(player, false);
    }

    public void switchPlayer(int type, PlaySpec freshSpec, long position, float speed, boolean repeat) {
        if (engine == null || player == null || freshSpec == null) return;
        beginIjkRuntimeManualOverride();
        beginPlaybackTrace("switch-player-fresh");
        type = PlayerSetting.sanitizePlayer(type);
        boolean resetVideoSurface = type != playerType;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        resetLutRuntimeState("switch_player_fresh", true);
        stopNativeAudioSession();
        engine.release();
        playerType = type;
        PlayerSetting.putActivePlayer(type);
        spec = freshSpec;
        bindPlaybackTrace();
        playWhenReady = wasPlayWhenReady;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh type=%d position=%d spec=%s", type, position, debugSpec());
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        callback.onPlayerRebuild(player, resetVideoSurface);
        setMediaItem(Constant.TIMEOUT_PLAY);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    public void switchPlayer(int type, Result result, String key, MediaMetadata metadata, boolean useParse, long position, float speed, boolean repeat) {
        if (engine == null || player == null || result == null || result.hasMsg() || result.getRealUrl().isEmpty()) return;
        beginIjkRuntimeManualOverride();
        beginPlaybackTrace("switch-player-result");
        type = PlayerSetting.sanitizePlayer(type);
        manualPlayerSwitchPending = true;
        boolean resetVideoSurface = type != playerType;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        int decode = engine.getDecode();
        prepareSeq++;
        resetLutRuntimeState("switch_player_result", true);
        stopNativeAudioSession();
        stopParse();
        engine.release();
        playerType = type;
        PlayerSetting.putActivePlayer(type);
        engine = buildEngine(playerType, decode);
        player = engine.getPlayer();
        playWhenReady = wasPlayWhenReady;
        callback.onPlayerRebuild(player, resetVideoSurface);
        if (result.needParse() || useParse) {
            pendingSwitchRestore = true;
            pendingSwitchPositionMs = position;
            pendingSwitchSpeed = speed;
            pendingSwitchRepeat = repeat;
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh parse type=%d position=%d useParse=%s spec=%s", type, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            spec = PlaySpec.from(result, key, metadata);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player fresh result type=%d position=%d spec=%s", type, position, debugSpec());
            setMediaItem(Constant.TIMEOUT_PLAY);
            if (position > 0) seekTo(position);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        }
    }

    private void switchPlayer(int type, boolean manual) {
        if (engine == null || player == null) return;
        type = PlayerSetting.sanitizePlayer(type);
        type = manual ? resolveManualPlayer(type) : resolveAvailablePlayer(type);
        if (type == playerType) return;
        resetPlayerFallback();
        manualPlayerSwitchPending = manual;
        if (manual) beginIjkRuntimeManualOverride();
        beginPlaybackTrace("switch-player");
        switchEngine(type, true, true, true);
    }

    /**
     * chosen 区分「用户为本次播放选定的内核」与「当前实际在跑的引擎」：
     * 手动切换要更新会话内核（供取播放地址、写历史使用），
     * 而播放失败后的自动回退只换引擎，不能改写用户的选择。
     */
    private void switchEngine(int type, boolean chosen, boolean preserveState, boolean notifyPrepare) {
        int decode = engine.getDecode();
        switchEngine(type, chosen, preserveState, notifyPrepare, decode);
    }

    private void switchEngine(int type, boolean chosen, boolean preserveState, boolean notifyPrepare, int decode) {
        long position = preserveState ? getPosition() : 0;
        float speed = preserveState ? getSpeed() : 1f;
        boolean repeat = preserveState && isRepeatOne();
        boolean wasPlayWhenReady = preserveState && player != null ? player.getPlayWhenReady() : playWhenReady;
        switchEngine(type, chosen, notifyPrepare, decode, position, speed, repeat, wasPlayWhenReady);
    }

    private void switchEngine(int type, boolean chosen, boolean notifyPrepare, int decode, long position, float speed, boolean repeat, boolean wasPlayWhenReady) {
        prepareSeq++;
        resetLutRuntimeState("switch_player", true);
        stopNativeAudioSession();
        engine.release();
        playerType = type;
        if (chosen) PlayerSetting.putActivePlayer(type);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player type=%d chosen=%s position=%d spec=%s", type, chosen, position, debugSpec());
        engine = buildEngine(playerType, sanitizeDecode(decode));
        player = engine.getPlayer();
        // A Surface used by a CPU renderer stays connected after decoder release.
        // A different player needs a new Surface before its GPU/codec can connect.
        callback.onPlayerRebuild(player, true);
        if (spec == null || spec.getUrl() == null) return;
        this.playWhenReady = wasPlayWhenReady;
        if (reparseForPlayerSwitch(position, speed, repeat)) return;
        if (notifyPrepare) setMediaItem(Constant.TIMEOUT_PLAY);
        else setMediaItemNow(Constant.TIMEOUT_PLAY, false);
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    private void rebuildPlayer() {
        rebuildPlayer(false);
    }

    private void rebuildPlayer(boolean resetVideoSurface) {
        stopNativeAudioSession();
        player = engine.rebuild(listener);
        restoreIjkStagedBufferConfig();
        videoEffectsActive = false;
        videoEffectsDirty = false;
        lutAppliedForItem = false;
        lutApplyInProgress = false;
        lutPipelineReadyForItem = false;
        lutPipelinePrepareInProgress = false;
        pendingLutPreview = false;
        waitingLutBeforePlay = false;
        callback.onPlayerRebuild(player, resetVideoSurface);
    }

    private void applyIjkAutoInitialControl() {
        if (!(engine instanceof IjkPlayerEngine ijk)
                || !playbackAutoSession.active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackAutoContext context = playbackAutoContextStore.snapshot();
        IjkBufferPolicy.Request request = buildIjkBufferRequest(
                context, now, false);
        IjkBufferPolicy.Decision policy = mergeIjkBufferDecision(
                IjkBufferPolicy.resolve(request));
        IjkBufferController.Decision decision =
                ijkBufferController.stageInitial(
                        playbackAutoSession, context.session(), policy);
        if (policy.managed()) {
            ijk.stageAutomaticInputBufferConfig(decision.targetConfig());
        }
        IjkDecodePressureController.Decision decodeDecision =
                ijkDecodePressureController.stageInitial(
                        playbackAutoSession,
                        context.session(),
                        hasAutomaticIjkDecodeOptions());
        if (hasAutomaticIjkDecodeOptions()) {
            ijk.stageAutomaticDecodeControlConfig(
                    mergeIjkDecodeConfig(decodeDecision.targetConfig()));
        }
        publishIjkBufferDecision(
                decision, request, IjkBufferController.Trigger.INITIAL,
                false, true, now);
        publishIjkDecodePressureDecision(
                decodeDecision,
                null,
                false,
                false,
                now);
    }

    private void restoreIjkStagedBufferConfig() {
        if (!(engine instanceof IjkPlayerEngine ijk)
                || !PlaybackPerformanceSetting.hasAutomaticOptions(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_BUFFER,
                PlaybackPerformanceCatalog.IJK_WATER,
                PlaybackPerformanceCatalog.IJK_PICTURE_QUEUE,
                PlaybackPerformanceCatalog.IJK_SOFT_TUNE)
                || !playbackAutoSession.active()) return;
        IjkBufferController.Snapshot snapshot = ijkBufferController.snapshot();
        if (!playbackAutoSession.equals(snapshot.session())) return;
        ijk.stageAutomaticInputBufferConfig(
                mergeIjkBufferConfig(snapshot.stagedConfig()));
        IjkDecodePressureController.Snapshot decode =
                ijkDecodePressureController.snapshot();
        if (playbackAutoSession.equals(decode.session())) {
            ijk.stageAutomaticDecodeControlConfig(
                    mergeIjkDecodeConfig(decode.stagedConfig()));
        }
    }

    private void onIjkBufferMemoryUpdate(
            PlaybackMemoryCoordinator.Update update) {
        if (update == null || !playbackAutoSession.active()
                || !playbackAutoSession.equals(update.session())) return;
        evaluateIjkBuffer(IjkBufferController.Trigger.MEMORY,
                SystemClock.elapsedRealtime());
    }

    private void evaluateIjkBuffer(
            IjkBufferController.Trigger trigger,
            long nowElapsedMs) {
        if (!(engine instanceof IjkPlayerEngine ijk)
                || !playbackAutoSession.active()) return;
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext context = playbackAutoContextStore.snapshot();
        IjkBufferPolicy.Request request = buildIjkBufferRequest(
                context, now, true);
        IjkBufferPolicy.Decision policy = mergeIjkBufferDecision(
                IjkBufferPolicy.resolve(request));
        IjkBufferController.Decision decision = ijkBufferController.evaluate(
                playbackAutoSession,
                context.session(),
                policy,
                ijk.getAppliedInputBufferConfig(),
                trigger,
                player != null && player.getPlaybackState()
                        == Player.STATE_BUFFERING,
                playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)
                        || playbackTrace.hasStage(
                        PlaybackTrace.Stage.AUDIO_PLAYABLE),
                request.rebufferUsable() ? request.rebufferCount() : 0,
                now);

        boolean applyStarted = false;
        boolean applySucceeded = decision.action()
                != IjkBufferController.Action.RELOAD;
        if (decision.requestsReload()) {
            boolean safetyReload = decision.reason()
                    == IjkBufferController.Reason.SAFETY_RELOAD;
            PlaybackExperimentPolicy.Action reloadAction = safetyReload
                    ? PlaybackExperimentPolicy.Action.IJK_BUFFER_SAFETY_RELOAD
                    : PlaybackExperimentPolicy.Action.IJK_BUFFER_RELOAD;
            if (!experimentAllowed(reloadAction)) {
                decision = ijkBufferController.deferExperimentalReload(
                        playbackAutoSession, decision);
                applySucceeded = false;
            } else {
                applyStarted = ijkBufferController.beginApply(
                        playbackAutoSession, decision);
                if (applyStarted) {
                    ijk.stageAutomaticInputBufferConfig(
                            mergeIjkBufferConfig(
                                    ijkBufferController.snapshot().stagedConfig()));
                    pendingIjkBufferDecision = decision;
                    boolean restartStarted = restartIjkBuffer(ijk, decision);
                    applySucceeded = restartStarted
                            && decision.targetConfig().equals(
                            ijk.getAppliedInputBufferConfig());
                    if (!applySucceeded) {
                        completeIjkBufferManagedReload(
                                false, "start-failed", now, false);
                        if (!restartStarted) ijkBufferManagedReload = false;
                    }
                }
            }
        }
        if (policy.managed()) {
            ijk.stageAutomaticInputBufferConfig(
                    mergeIjkBufferConfig(
                            ijkBufferController.snapshot().stagedConfig()));
        }
        publishIjkBufferDecision(
                decision, request, trigger, applyStarted,
                applySucceeded, now);
    }

    private IjkBufferPolicy.Request buildIjkBufferRequest(
            PlaybackAutoContext context,
            long nowElapsedMs,
            boolean allowEngineScene) {
        PlaybackAutoContext current = context == null
                ? PlaybackAutoContext.empty() : context;
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                current.resource().protocol();
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> streamFact =
                current.resource().streamKind();
        PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> manifestFact =
                current.resource().manifest();
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact =
                current.device().memoryPressure();
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshotFact =
                current.device().memorySnapshot();
        PlaybackAutoContext.Fact<Long> bitrateFact =
                current.runtime().mediaBitrateBitsPerSecond();
        PlaybackAutoContext.Fact<Integer> rebufferFact =
                current.runtime().rebufferCount();
        PlaybackAutoContext.Fact<Long> liveLagFact =
                current.runtime().liveLagMs();
        boolean protocolUsable = protocolFact.isUsable(now);
        PlaybackAutoContext.Protocol protocol = protocolUsable
                ? protocolFact.value() : PlaybackAutoContext.Protocol.UNKNOWN;
        boolean streamUsable = streamFact.isUsable(now);
        PlaybackAutoContext.StreamKind stream = streamUsable
                ? streamFact.value() : PlaybackAutoContext.StreamKind.UNKNOWN;
        int configuredScene = PlaybackPerformanceSetting.isOverridden(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_SCENE)
                ? IjkPerformanceSetting.getScene()
                : IjkPerformanceSetting.SCENE_AUTO;
        if (configuredScene != IjkPerformanceSetting.SCENE_AUTO) {
            stream = switch (configuredScene) {
                case IjkPerformanceSetting.SCENE_VOD ->
                        PlaybackAutoContext.StreamKind.VOD;
                case IjkPerformanceSetting.SCENE_LIVE_LOW_LATENCY ->
                        PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
                default -> PlaybackAutoContext.StreamKind.LIVE;
            };
            streamUsable = true;
        }
        boolean segmented = protocol == PlaybackAutoContext.Protocol.HLS
                || protocol == PlaybackAutoContext.Protocol.DASH;
        if (!streamUsable && allowEngineScene && !segmented
                && engine instanceof IjkPlayerEngine ijk) {
            if (ijk.isVod()) {
                stream = PlaybackAutoContext.StreamKind.VOD;
                streamUsable = true;
            } else if (ijk.isLive()) {
                stream = PlaybackAutoContext.StreamKind.LIVE;
                streamUsable = true;
            }
        }
        boolean snapshotUsable = snapshotFact.isUsable(now)
                && snapshotFact.value().hasEvidence();
        return new IjkBufferPolicy.Request(
                PlaybackPerformanceSetting.hasAutomaticOptions(
                        PlayerSetting.IJK,
                        PlaybackPerformanceCatalog.IJK_BUFFER,
                        PlaybackPerformanceCatalog.IJK_WATER),
                isIjk(),
                protocolUsable,
                protocol,
                streamUsable,
                stream,
                manifestFact.isUsable(now),
                manifestFact.value(),
                pressureFact.isUsable(now),
                pressureFact.value(),
                snapshotUsable,
                snapshotFact.value(),
                bitrateFact.isUsable(now) && bitrateFact.value() > 0,
                bitrateFact.isUsable(now) ? bitrateFact.value() : 0,
                rebufferFact.isUsable(now),
                rebufferFact.isUsable(now) ? rebufferFact.value() : 0,
                liveLagFact.isUsable(now) && liveLagFact.value() >= 0,
                liveLagFact.isUsable(now) ? liveLagFact.value() : -1);
    }

    private IjkBufferPolicy.Config mergeIjkBufferConfig(
            IjkBufferPolicy.Config automatic) {
        IjkBufferPolicy.Config safe = automatic == null
                ? IjkBufferPolicy.safeInitialConfig() : automatic;
        int bufferMb = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_BUFFER)
                ? safe.bufferMb() : IjkPerformanceSetting.getBufferMb();
        if (PlaybackPerformanceSetting.isAuto(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_WATER)) {
            return new IjkBufferPolicy.Config(
                    bufferMb,
                    safe.firstWaterMs(),
                    safe.nextWaterMs(),
                    safe.lastWaterMs());
        }
        return new IjkBufferPolicy.Config(
                bufferMb,
                IjkPerformanceSetting.getFirstWaterMs(),
                IjkPerformanceSetting.getNextWaterMs(),
                IjkPerformanceSetting.getLastWaterMs());
    }

    private IjkBufferPolicy.Decision mergeIjkBufferDecision(
            IjkBufferPolicy.Decision decision) {
        IjkBufferPolicy.Decision safe = decision == null
                ? IjkBufferPolicy.resolve(null) : decision;
        return new IjkBufferPolicy.Decision(
                safe.managed(),
                mergeIjkBufferConfig(safe.target()),
                safe.reason(),
                safe.memoryCeilingMb(),
                safe.liveLagHigh(),
                safe.targetOffsetMs(),
                safe.mediaDemandBytes());
    }

    private void evaluateIjkRealtimeRecovery(long nowElapsedMs) {
        if (!(engine instanceof IjkPlayerEngine ijk)
                || !playbackAutoSession.active()) return;
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext context = playbackAutoContextStore.snapshot();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                context.resource().protocol();
        boolean protocolUsable = protocolFact.isUsable(now);
        PlaybackAutoContext.Protocol protocol = protocolUsable
                ? protocolFact.value() : PlaybackAutoContext.Protocol.UNKNOWN;
        boolean automatic = PlaybackPerformanceSetting.hasAutomaticOptions(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_BUFFER,
                PlaybackPerformanceCatalog.IJK_WATER) && experimentAllowed(
                PlaybackExperimentPolicy.Action.IJK_REALTIME_REBUILD);
        boolean realtime = protocol == PlaybackAutoContext.Protocol.RTSP
                || protocol == PlaybackAutoContext.Protocol.RTMP;
        if (!automatic || !protocolUsable || !realtime) {
            ijkRealtimeRecoveryController.onPositionDiscontinuity(
                    playbackAutoSession);
            return;
        }
        IjkBufferController.Snapshot reloadState =
                ijkBufferController.snapshot();
        IjkRealtimeRecoveryController.Input input =
                new IjkRealtimeRecoveryController.Input(
                        playbackAutoSession,
                        automatic,
                        isIjk(),
                        protocolUsable,
                        protocol,
                        isIjkPlaybackActive(),
                        playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)
                                || playbackTrace.hasStage(
                                PlaybackTrace.Stage.AUDIO_PLAYABLE),
                        Math.abs(getSpeed() - 1f) < 0.01f,
                        false,
                        reloadState.applyInProgress(),
                        ijk.getRealtimeQueueSnapshot(),
                        ijk.getAppliedInputBufferConfig(),
                        now);
        IjkRealtimeRecoveryPolicy.Decision decision =
                ijkRealtimeRecoveryController.evaluate(input);
        IjkRealtimeRecoveryController.Snapshot stateAtDecision =
                ijkRealtimeRecoveryController.snapshot();
        IjkBufferController.Decision reloadGate = null;
        boolean actionStarted = false;
        boolean restartStarted = false;
        if (decision.requestsRecovery()) {
            reloadGate = ijkBufferController.requestRealtimeRecovery(
                    playbackAutoSession,
                    context.session(),
                    ijk.getAppliedInputBufferConfig(),
                    now);
            if (reloadGate.requestsReload()) {
                boolean reloadReserved = ijkBufferController.beginApply(
                        playbackAutoSession, reloadGate);
                boolean recoveryReserved = reloadReserved
                        && ijkRealtimeRecoveryController.beginAction(
                        playbackAutoSession, decision, now);
                actionStarted = reloadReserved && recoveryReserved;
                if (actionStarted) {
                    pendingIjkBufferDecision = reloadGate;
                    pendingIjkRealtimeRecoveryDecision = decision;
                    ijk.stageAutomaticInputBufferConfig(
                            mergeIjkBufferConfig(
                                    ijkBufferController.snapshot().stagedConfig()));
                    restartStarted = restartIjkRealtimeRecovery(
                            ijk, reloadGate, decision);
                    if (!restartStarted) {
                        completeIjkBufferManagedReload(
                                false, "start-failed", now, false);
                    }
                } else if (reloadReserved) {
                    ijkBufferController.completeApply(
                            playbackAutoSession, reloadGate, false, now);
                }
            }
        }
        publishIjkRealtimeRecoveryDecision(
                decision,
                protocol,
                reloadGate,
                stateAtDecision,
                actionStarted,
                restartStarted,
                now);
    }

    private void evaluateIjkDecodePressure(long nowElapsedMs) {
        if (!(engine instanceof IjkPlayerEngine ijk)
                || !playbackAutoSession.active()) return;
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext context = playbackAutoContextStore.snapshot();
        PlaybackAutoContext.DecoderFacts decoder = context.media().decoder();
        PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decodeFact =
                decoder.videoDecodeMode();
        PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermalFact =
                context.device().thermalState();
        PlaybackAutoContext.Fact<Float> frameRateFact =
                context.media().videoTrack().frameRate();
        boolean decoderUsable = decoder.trackSequence()
                == context.media().trackSequence()
                && decodeFact.isUsable(now);
        IjkDecodePressurePolicy.Input policyInput =
                new IjkDecodePressurePolicy.Input(
                        isIjkDecodePressureAutomatic()
                                && experimentAllowed(
                                PlaybackExperimentPolicy.Action.IJK_DECODE_REBUILD),
                        isIjk(),
                        isIjkPlaybackActive(),
                        playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)
                                || playbackTrace.hasStage(
                                PlaybackTrace.Stage.AUDIO_PLAYABLE),
                        Math.abs(getSpeed() - 1f) < 0.01f,
                        false,
                        false,
                        decoderUsable,
                        decoderUsable ? decodeFact.value()
                                : PlaybackAutoContext.DecodeMode.UNKNOWN,
                        thermalFact.isUsable(now),
                        thermalFact.isUsable(now) ? thermalFact.value()
                                : PlaybackAutoContext.ThermalState.UNKNOWN,
                        frameRateFact.isUsable(now)
                                && frameRateFact.value() > 0,
                        frameRateFact.isUsable(now)
                                ? frameRateFact.value() : -1f,
                        ijk.getDecodePressureSnapshot());
        IjkBufferController.Snapshot reloadState =
                ijkBufferController.snapshot();
        IjkDecodePressureController.Decision decision =
                ijkDecodePressureController.evaluate(
                        new IjkDecodePressureController.Input(
                                playbackAutoSession,
                                context.session(),
                                policyInput,
                                automaticIjkDecodeView(
                                        ijk.getAppliedDecodeControlConfig()),
                                reloadState.applyInProgress(),
                                now));

        IjkBufferController.Decision reloadGate = null;
        boolean actionStarted = false;
        boolean restartStarted = false;
        if (decision.requestsReload()) {
            reloadGate = ijkBufferController.requestDecodePressureReload(
                    playbackAutoSession,
                    context.session(),
                    ijk.getAppliedInputBufferConfig(),
                    now);
            if (reloadGate.requestsReload()) {
                boolean reloadReserved = ijkBufferController.beginApply(
                        playbackAutoSession, reloadGate);
                boolean decodeReserved = reloadReserved
                        && ijkDecodePressureController.beginAction(
                        playbackAutoSession, decision);
                actionStarted = reloadReserved && decodeReserved;
                if (actionStarted) {
                    pendingIjkBufferDecision = reloadGate;
                    pendingIjkDecodePressureDecision = decision;
                    ijk.stageAutomaticInputBufferConfig(
                            mergeIjkBufferConfig(
                                    ijkBufferController.snapshot().stagedConfig()));
                    ijk.stageAutomaticDecodeControlConfig(
                            mergeIjkDecodeConfig(
                                    ijkDecodePressureController.snapshot()
                                            .stagedConfig()));
                    restartStarted = restartIjkDecodePressure(
                            ijk, reloadGate, decision);
                    boolean applied = restartStarted
                            && mergeIjkDecodeConfig(
                            decision.targetConfig()).equals(
                            ijk.getAppliedDecodeControlConfig());
                    if (!applied) {
                        completeIjkBufferManagedReload(
                                false, "start-failed", now, false);
                        if (!restartStarted) ijkBufferManagedReload = false;
                    }
                } else if (reloadReserved) {
                    ijkBufferController.completeApply(
                            playbackAutoSession, reloadGate, false, now);
                }
            }
        }
        if (hasAutomaticIjkDecodeOptions()) {
            ijk.stageAutomaticDecodeControlConfig(
                    mergeIjkDecodeConfig(
                            ijkDecodePressureController.snapshot().stagedConfig()));
        }
        publishIjkDecodePressureDecision(
                decision,
                reloadGate,
                actionStarted,
                restartStarted,
                now);
    }

    private boolean hasAutomaticIjkDecodeOptions() {
        return PlaybackPerformanceSetting.hasAutomaticOptions(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_PICTURE_QUEUE,
                PlaybackPerformanceCatalog.IJK_SOFT_TUNE);
    }

    private boolean isIjkDecodePressureAutomatic() {
        return PlaybackPerformanceSetting.isAuto(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_SOFT_TUNE);
    }

    private IjkDecodePressurePolicy.Config automaticIjkDecodeView(
            IjkDecodePressurePolicy.Config applied) {
        IjkDecodePressurePolicy.Config safe = applied == null
                ? IjkDecodePressurePolicy.automaticInitialConfig()
                : applied;
        return new IjkDecodePressurePolicy.Config(
                IjkDecodePressurePolicy.AUTOMATIC_PICTURE_QUEUE,
                safe.tuneMode());
    }

    private IjkDecodePressurePolicy.Config mergeIjkDecodeConfig(
            IjkDecodePressurePolicy.Config automatic) {
        IjkDecodePressurePolicy.Config safe = automatic == null
                ? IjkDecodePressurePolicy.automaticInitialConfig()
                : automatic;
        int pictureQueue = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_PICTURE_QUEUE)
                ? safe.pictureQueue() : IjkPerformanceSetting.getPictureQueue();
        IjkDecodePressurePolicy.TuneMode tune = PlaybackPerformanceSetting.isAuto(
                PlayerSetting.IJK,
                PlaybackPerformanceCatalog.IJK_SOFT_TUNE)
                ? safe.tuneMode() : switch (IjkPerformanceSetting.getSoftTuneMode()) {
            case IjkPerformanceSetting.SOFT_TUNE_AGGRESSIVE ->
                    IjkDecodePressurePolicy.TuneMode.AGGRESSIVE;
            case IjkPerformanceSetting.SOFT_TUNE_MILD ->
                    IjkDecodePressurePolicy.TuneMode.MILD;
            default -> IjkDecodePressurePolicy.TuneMode.OFF;
        };
        return new IjkDecodePressurePolicy.Config(pictureQueue, tune);
    }

    private boolean isIjkPlaybackActive() {
        if (player == null || !player.getPlayWhenReady()
                || player.getPlaybackState() != Player.STATE_READY) {
            return false;
        }
        try {
            return player.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean restartIjkBuffer(
            IjkPlayerEngine ijk,
            IjkBufferController.Decision decision) {
        if (spec == null || TextUtils.isEmpty(spec.getUrl())
                || player == null) return false;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        long position = ijk.isVod()
                ? Math.max(0, player.getCurrentPosition()) : C.TIME_UNSET;
        try {
            prepareSeq++;
            App.removeCallbacks(runnable);
            initTrack = false;
            playWhenReady = wasPlayWhenReady;
            ijkBufferManagedReload = true;
            PlaybackTrace.log("ijk-buffer", playbackTrace.current(),
                    "action=reload old=%s target=%s resume=%d play=%s reason=%s",
                    decision.appliedConfig().label(),
                    decision.targetConfig().label(),
                    position == C.TIME_UNSET ? 0 : position,
                    wasPlayWhenReady,
                    decision.reason().label());
            restartEngine(spec, position, wasPlayWhenReady);
        } catch (Throwable error) {
            PlaybackTrace.log("ijk-buffer", playbackTrace.current(),
                    "action=reload result=failed errorType=%s",
                    error.getClass().getSimpleName());
            return false;
        }
        try {
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        } catch (Throwable error) {
            PlaybackTrace.log("ijk-buffer", playbackTrace.current(),
                    "action=restore-state result=partial errorType=%s",
                    error.getClass().getSimpleName());
        }
        App.post(runnable, Constant.TIMEOUT_PLAY);
        return true;
    }

    private boolean restartIjkRealtimeRecovery(
            IjkPlayerEngine ijk,
            IjkBufferController.Decision reloadGate,
            IjkRealtimeRecoveryPolicy.Decision recovery) {
        if (spec == null || TextUtils.isEmpty(spec.getUrl())
                || player == null) return false;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        try {
            prepareSeq++;
            App.removeCallbacks(runnable);
            initTrack = false;
            playWhenReady = wasPlayWhenReady;
            ijkBufferManagedReload = true;
            PlaybackTrace.log("ijk-realtime", playbackTrace.current(),
                    "action=rebuild-session trigger=%s bufferedMs=%d bytes=%d packets=%d play=%s reloadReason=%s",
                    recovery.trigger().label(),
                    recovery.queue().playableDurationMs(),
                    recovery.queue().totalBytes(),
                    recovery.queue().totalPackets(),
                    wasPlayWhenReady,
                    reloadGate.reason().label());
            restartEngine(spec, C.TIME_UNSET, wasPlayWhenReady);
        } catch (Throwable error) {
            PlaybackTrace.log("ijk-realtime", playbackTrace.current(),
                    "action=rebuild-session result=failed errorType=%s",
                    error.getClass().getSimpleName());
            return false;
        }
        try {
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        } catch (Throwable error) {
            PlaybackTrace.log("ijk-realtime", playbackTrace.current(),
                    "action=restore-state result=partial errorType=%s",
                    error.getClass().getSimpleName());
        }
        App.post(runnable, Constant.TIMEOUT_PLAY);
        return true;
    }

    private boolean restartIjkDecodePressure(
            IjkPlayerEngine ijk,
            IjkBufferController.Decision reloadGate,
            IjkDecodePressureController.Decision decision) {
        if (spec == null || TextUtils.isEmpty(spec.getUrl())
                || player == null) return false;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        long position = ijk.isVod()
                ? Math.max(0, player.getCurrentPosition()) : C.TIME_UNSET;
        IjkDecodePressurePolicy.Metrics metrics =
                decision.assessment().metrics();
        try {
            prepareSeq++;
            App.removeCallbacks(runnable);
            initTrack = false;
            playWhenReady = wasPlayWhenReady;
            ijkBufferManagedReload = true;
            PlaybackTrace.log("ijk-decode", playbackTrace.current(),
                    "action=reload old=%s target=%s pressure=%s thermalReason=%s targetFps=%d decodeFps=%d outputFps=%d play=%s reloadReason=%s",
                    decision.appliedConfig().label(),
                    decision.targetConfig().label(),
                    decision.assessment().pressure().label(),
                    decision.assessment().reason().label(),
                    Math.round(metrics.targetFps() * 1_000f),
                    Math.round(metrics.decodeFps() * 1_000f),
                    Math.round(metrics.outputFps() * 1_000f),
                    wasPlayWhenReady,
                    reloadGate.reason().label());
            restartEngine(spec, position, wasPlayWhenReady);
        } catch (Throwable error) {
            PlaybackTrace.log("ijk-decode", playbackTrace.current(),
                    "action=reload result=failed errorType=%s",
                    error.getClass().getSimpleName());
            return false;
        }
        try {
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
        } catch (Throwable error) {
            PlaybackTrace.log("ijk-decode", playbackTrace.current(),
                    "action=restore-state result=partial errorType=%s",
                    error.getClass().getSimpleName());
        }
        App.post(runnable, Constant.TIMEOUT_PLAY);
        return true;
    }

    private void completeIjkBufferManagedReload(
            boolean succeeded,
            String completionReason,
            long nowElapsedMs,
            boolean publishCompletion) {
        IjkBufferController.Decision pending = pendingIjkBufferDecision;
        IjkDecodePressureController.Decision decode =
                pendingIjkDecodePressureDecision;
        IjkRealtimeRecoveryPolicy.Decision recovery =
                pendingIjkRealtimeRecoveryDecision;
        pendingIjkBufferDecision = null;
        pendingIjkDecodePressureDecision = null;
        pendingIjkRealtimeRecoveryDecision = null;
        if (pending != null || decode != null || recovery != null) {
            long now = Math.max(0, nowElapsedMs);
            if (pending != null) {
                ijkBufferController.completeApply(
                        playbackAutoSession, pending, succeeded, now);
            }
            if (recovery != null) {
                ijkRealtimeRecoveryController.completeAction(
                        playbackAutoSession, succeeded);
            }
            if (decode != null) {
                ijkDecodePressureController.completeAction(
                        playbackAutoSession, succeeded);
            }
            if (engine instanceof IjkPlayerEngine ijk
                    && PlaybackPerformanceSetting.hasAutomaticOptions(
                    PlayerSetting.IJK,
                    PlaybackPerformanceCatalog.IJK_BUFFER,
                    PlaybackPerformanceCatalog.IJK_WATER,
                    PlaybackPerformanceCatalog.IJK_PICTURE_QUEUE,
                    PlaybackPerformanceCatalog.IJK_SOFT_TUNE)
                    && playbackAutoSession.active()) {
                ijk.stageAutomaticInputBufferConfig(
                        mergeIjkBufferConfig(
                                ijkBufferController.snapshot().stagedConfig()));
                ijk.stageAutomaticDecodeControlConfig(
                        mergeIjkDecodeConfig(
                                ijkDecodePressureController.snapshot().stagedConfig()));
            }
            String domain = recovery != null ? "ijk-realtime"
                    : decode != null ? "ijk-decode" : "ijk-buffer";
            String target = decode != null
                    ? decode.targetConfig().label()
                    : pending == null ? "unknown"
                    : pending.targetConfig().label();
            PlaybackTrace.log(domain, playbackTrace.current(),
                    "action=reload-complete result=%s reason=%s target=%s",
                    succeeded ? "ready" : "failed",
                    PlaybackTelemetry.safeLabel(completionReason),
                    target);
            if (publishCompletion) {
                if (recovery != null) {
                    publishIjkRealtimeRecoveryCompletion(
                            recovery, succeeded, completionReason, now);
                } else if (decode != null) {
                    publishIjkDecodePressureCompletion(
                            decode, succeeded, completionReason, now);
                } else if (pending != null) {
                    publishIjkBufferCompletion(
                            pending, succeeded, completionReason, now);
                }
            }
        }
        ijkBufferManagedReload = false;
    }

    private void publishIjkDecodePressureCompletion(
            IjkDecodePressureController.Decision decision,
            boolean succeeded,
            String completionReason,
            long nowElapsedMs) {
        IjkDecodePressureController.Snapshot decode =
                ijkDecodePressureController.snapshot();
        IjkBufferController.Snapshot reload =
                ijkBufferController.snapshot();
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "action_attempts", decode.actionAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "successful_actions", decode.successfulActions(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "failed_actions", decode.failedActions(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "shared_reload_attempts", reload.reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_DECODE_PRESSURE,
                        succeeded ? PlaybackTelemetry.DecisionOutcome.APPLIED
                                : PlaybackTelemetry.DecisionOutcome.FAILED,
                        decision.appliedConfig().label(),
                        decision.targetConfig().label(),
                        succeeded ? decision.targetConfig().label()
                                : decision.appliedConfig().label(),
                        succeeded ? "reload-ready" : "reload-failed",
                        succeeded ? "none"
                                : PlaybackTelemetry.safeLabel(
                                completionReason),
                        inputs),
                nowElapsedMs);
    }

    private void publishIjkBufferCompletion(
            IjkBufferController.Decision decision,
            boolean succeeded,
            String completionReason,
            long nowElapsedMs) {
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        IjkBufferController.Snapshot snapshot = ijkBufferController.snapshot();
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "reload_attempts", snapshot.reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "successful_reloads", snapshot.successfulReloads(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_BUFFER,
                        succeeded ? PlaybackTelemetry.DecisionOutcome.APPLIED
                                : PlaybackTelemetry.DecisionOutcome.FAILED,
                        decision.appliedConfig().label(),
                        decision.targetConfig().label(),
                        succeeded ? decision.targetConfig().label()
                                : decision.appliedConfig().label(),
                        succeeded ? "reload-ready" : "reload-failed",
                        succeeded ? "none"
                                : PlaybackTelemetry.safeLabel(completionReason),
                        inputs),
                nowElapsedMs);
    }

    private void publishIjkBufferDecision(
            IjkBufferController.Decision decision,
            IjkBufferPolicy.Request request,
            IjkBufferController.Trigger trigger,
            boolean applyStarted,
            boolean applySucceeded,
            long nowElapsedMs) {
        if (decision == null || request == null) return;
        PlaybackTelemetry.DecisionOutcome outcome = !decision.policy().managed()
                ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                : decision.action() == IjkBufferController.Action.RELOAD
                ? applyStarted && applySucceeded
                ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                : PlaybackTelemetry.DecisionOutcome.FAILED
                : decision.action() == IjkBufferController.Action.STAGE
                ? PlaybackTelemetry.DecisionOutcome.SELECTED
                : PlaybackTelemetry.DecisionOutcome.HELD;
        String result = applyStarted && applySucceeded
                || decision.action() == IjkBufferController.Action.STAGE
                ? decision.targetConfig().label()
                : decision.appliedConfig().label();
        PlaybackAutoContext.Fact<Long> liveLagFact =
                playbackAutoContextStore.snapshot().runtime().liveLagMs();
        boolean liveLagFactUsable = liveLagFact.isUsable(nowElapsedMs);
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "trigger", trigger.name().toLowerCase(java.util.Locale.US),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "memory_ceiling_mb", decision.policy().memoryCeilingMb(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "target_first_ms", decision.targetConfig().firstWaterMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "target_next_ms", decision.targetConfig().nextWaterMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "target_last_ms", decision.targetConfig().lastWaterMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(request.mediaBitrateUsable()
                ? PlaybackTelemetry.DecisionInput.number(
                "media_bps", request.mediaBitrateBitsPerSecond(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM)
                : PlaybackTelemetry.DecisionInput.unknown("media_bps"));
        inputs.add(request.liveLagUsable()
                ? PlaybackTelemetry.DecisionInput.number(
                "live_lag_ms", request.liveLagMs(),
                liveLagFactUsable ? liveLagFact.source()
                        : PlaybackAutoContext.ValueSource.UNKNOWN,
                liveLagFactUsable ? liveLagFact.confidence()
                        : PlaybackAutoContext.Confidence.UNKNOWN)
                : PlaybackTelemetry.DecisionInput.unknown("live_lag_ms"));
        inputs.add(request.rebufferUsable()
                ? PlaybackTelemetry.DecisionInput.number(
                "rebuffer_count", request.rebufferCount(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH)
                : PlaybackTelemetry.DecisionInput.unknown("rebuffer_count"));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "reload_attempts",
                ijkBufferController.snapshot().reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_BUFFER,
                        outcome,
                        decision.appliedConfig().label(),
                        decision.targetConfig().label(),
                        result,
                        decision.reason().label(),
                        decision.policy().reason().label(),
                        inputs),
                nowElapsedMs);
    }

    private void publishIjkDecodePressureDecision(
            IjkDecodePressureController.Decision decision,
            IjkBufferController.Decision reloadGate,
            boolean actionStarted,
            boolean restartStarted,
            long nowElapsedMs) {
        if (decision == null) return;
        boolean suppressed = switch (decision.reason()) {
            case STALE_SESSION,
                 STALE_SAMPLE,
                 NOT_MANAGED,
                 INELIGIBLE,
                 ACTION_PENDING -> true;
            default -> false;
        };
        PlaybackTelemetry.DecisionOutcome outcome;
        if (decision.action() == IjkDecodePressureController.Action.STAGE) {
            outcome = PlaybackTelemetry.DecisionOutcome.SELECTED;
        } else if (!decision.requestsReload()) {
            outcome = suppressed
                    ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                    : PlaybackTelemetry.DecisionOutcome.HELD;
        } else if (reloadGate != null && !reloadGate.requestsReload()) {
            outcome = PlaybackTelemetry.DecisionOutcome.HELD;
        } else {
            outcome = actionStarted && restartStarted
                    ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                    : PlaybackTelemetry.DecisionOutcome.FAILED;
        }
        String suppression = reloadGate != null
                && !reloadGate.requestsReload()
                ? reloadGate.reason().label()
                : suppressed ? decision.assessment().reason().label()
                : "none";
        String result = decision.action()
                == IjkDecodePressureController.Action.STAGE
                || actionStarted && restartStarted
                ? decision.targetConfig().label()
                : decision.appliedConfig().label();
        IjkDecodePressureController.Snapshot state =
                ijkDecodePressureController.snapshot();
        IjkBufferController.Snapshot reload =
                ijkBufferController.snapshot();
        IjkDecodePressurePolicy.Metrics metrics =
                decision.assessment().metrics();
        PlaybackAutoContext context = playbackAutoContextStore.snapshot();
        long now = Math.max(0, nowElapsedMs);
        PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decoder =
                context.media().decoder().videoDecodeMode();
        PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermal =
                context.device().thermalState();
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "pressure", decision.assessment().pressure().label(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(decoder.isUsable(now)
                ? PlaybackTelemetry.DecisionInput.text(
                "actual_decode", decoder.value().label(),
                decoder.source(), decoder.confidence())
                : PlaybackTelemetry.DecisionInput.unknown("actual_decode"));
        inputs.add(thermal.isUsable(now)
                ? PlaybackTelemetry.DecisionInput.text(
                "thermal", thermal.value().label(),
                thermal.source(), thermal.confidence())
                : PlaybackTelemetry.DecisionInput.unknown("thermal"));
        inputs.add(metrics.targetFps() > 0
                ? PlaybackTelemetry.DecisionInput.number(
                "target_fps_milli",
                Math.round(metrics.targetFps() * 1_000f),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.MEDIUM)
                : PlaybackTelemetry.DecisionInput.unknown(
                "target_fps_milli"));
        inputs.add(metrics.fpsUsable()
                ? PlaybackTelemetry.DecisionInput.number(
                "decode_fps_milli",
                Math.round(metrics.decodeFps() * 1_000f),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM)
                : PlaybackTelemetry.DecisionInput.unknown(
                "decode_fps_milli"));
        inputs.add(metrics.fpsUsable()
                ? PlaybackTelemetry.DecisionInput.number(
                "output_fps_milli",
                Math.round(metrics.outputFps() * 1_000f),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM)
                : PlaybackTelemetry.DecisionInput.unknown(
                "output_fps_milli"));
        inputs.add(metrics.outputRatioPermille() >= 0
                ? PlaybackTelemetry.DecisionInput.number(
                "output_ratio_permille",
                metrics.outputRatioPermille(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM)
                : PlaybackTelemetry.DecisionInput.unknown(
                "output_ratio_permille"));
        inputs.add(metrics.outputToDecodePermille() >= 0
                ? PlaybackTelemetry.DecisionInput.number(
                "output_decode_permille",
                metrics.outputToDecodePermille(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM)
                : PlaybackTelemetry.DecisionInput.unknown(
                "output_decode_permille"));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "risk_samples", state.consecutiveRiskSamples(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "recovery_samples", state.consecutiveRecoverySamples(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "reload_attempts", reload.reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cooldown_ms", reloadGate == null
                        ? 0 : reloadGate.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_DECODE_PRESSURE,
                        outcome,
                        decision.appliedConfig().label(),
                        decision.targetConfig().label(),
                        result,
                        decision.reason().label(),
                        suppression,
                        inputs),
                now);
        if (decision.action() != IjkDecodePressureController.Action.HOLD
                || state.consecutiveRiskSamples() > 0
                || state.consecutiveRecoverySamples() > 0
                || reloadGate != null) {
            PlaybackTrace.log("ijk-decode", playbackTrace.current(),
                    "action=%s reason=%s pressure=%s targetFps=%d decodeFps=%d outputFps=%d outputRatio=%d outputDecodeRatio=%d riskSamples=%d recoverySamples=%d reloadGate=%s result=%s",
                    decision.action().label(),
                    decision.reason().label(),
                    decision.assessment().pressure().label(),
                    Math.round(metrics.targetFps() * 1_000f),
                    Math.round(metrics.decodeFps() * 1_000f),
                    Math.round(metrics.outputFps() * 1_000f),
                    metrics.outputRatioPermille(),
                    metrics.outputToDecodePermille(),
                    state.consecutiveRiskSamples(),
                    state.consecutiveRecoverySamples(),
                    reloadGate == null ? "none"
                            : reloadGate.reason().label(),
                    outcome.label());
        }
    }

    private void publishIjkRealtimeRecoveryDecision(
            IjkRealtimeRecoveryPolicy.Decision decision,
            PlaybackAutoContext.Protocol protocol,
            IjkBufferController.Decision reloadGate,
            IjkRealtimeRecoveryController.Snapshot stateAtDecision,
            boolean actionStarted,
            boolean restartStarted,
            long nowElapsedMs) {
        if (decision == null) return;
        boolean suppressed = switch (decision.reason()) {
            case NOT_AUTOMATIC_IJK,
                 NOT_REALTIME_PROTOCOL,
                 INACTIVE,
                 STARTUP,
                 NON_UNIT_SPEED,
                 USER_SEEK,
                 ACTION_PENDING,
                 EVIDENCE_UNKNOWN,
                 STALE_SESSION,
                 STALE_SAMPLE -> true;
            default -> false;
        };
        PlaybackTelemetry.DecisionOutcome outcome;
        if (!decision.requestsRecovery()) {
            outcome = suppressed
                    ? PlaybackTelemetry.DecisionOutcome.SUPPRESSED
                    : PlaybackTelemetry.DecisionOutcome.HELD;
        } else if (reloadGate != null && !reloadGate.requestsReload()) {
            outcome = PlaybackTelemetry.DecisionOutcome.HELD;
        } else {
            outcome = actionStarted && restartStarted
                    ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                    : PlaybackTelemetry.DecisionOutcome.FAILED;
        }
        String suppression = reloadGate != null
                && !reloadGate.requestsReload()
                ? reloadGate.reason().label()
                : suppressed ? decision.reason().label() : "none";
        IjkRealtimeRecoveryPolicy.QueueSnapshot queue = decision.queue();
        IjkBufferController.Snapshot reloadState =
                ijkBufferController.snapshot();
        IjkRealtimeRecoveryController.Snapshot recoveryState =
                stateAtDecision == null
                        ? ijkRealtimeRecoveryController.snapshot()
                        : stateAtDecision;
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "protocol", protocol == null
                        ? PlaybackAutoContext.Protocol.UNKNOWN.label()
                        : protocol.label(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "trigger", decision.trigger().label(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(queue.durationUsable()
                ? PlaybackTelemetry.DecisionInput.number(
                "buffered_ms", queue.playableDurationMs(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM)
                : PlaybackTelemetry.DecisionInput.unknown("buffered_ms"));
        inputs.add(decision.durationGrowthMsPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown(
                "duration_growth_msps")
                : PlaybackTelemetry.DecisionInput.number(
                "duration_growth_msps",
                decision.durationGrowthMsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cached_bytes", queue.totalBytes(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(decision.bytesGrowthPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown("bytes_growth_ps")
                : PlaybackTelemetry.DecisionInput.number(
                "bytes_growth_ps", decision.bytesGrowthPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cached_packets", queue.totalPackets(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(decision.packetsGrowthPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown(
                "packets_growth_ps")
                : PlaybackTelemetry.DecisionInput.number(
                "packets_growth_ps", decision.packetsGrowthPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "occupancy_permille",
                queue.occupancyPermille(
                        decision.thresholds().maxBufferBytes()),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "risk_samples", recoveryState.consecutiveRiskSamples(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "reload_attempts", reloadState.reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cooldown_ms", reloadGate == null
                        ? 0 : reloadGate.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_REALTIME_RECOVERY,
                        outcome,
                        "monitoring",
                        decision.action().label(),
                        actionStarted && restartStarted
                                ? "rebuild-pending" : "hold",
                        decision.reason().label(),
                        suppression,
                        inputs),
                nowElapsedMs);
        if (decision.trigger() != IjkRealtimeRecoveryPolicy.Trigger.NONE
                || reloadGate != null) {
            PlaybackTrace.log("ijk-realtime", playbackTrace.current(),
                    "action=%s reason=%s trigger=%s bufferedMs=%d bytes=%d packets=%d durationGrowth=%d byteGrowth=%d packetGrowth=%d samples=%d reloadGate=%s result=%s",
                    decision.action().label(),
                    decision.reason().label(),
                    decision.trigger().label(),
                    queue.playableDurationMs(),
                    queue.totalBytes(),
                    queue.totalPackets(),
                    decision.durationGrowthMsPerSecond(),
                    decision.bytesGrowthPerSecond(),
                    decision.packetsGrowthPerSecond(),
                    recoveryState.consecutiveRiskSamples(),
                    reloadGate == null ? "none"
                            : reloadGate.reason().label(),
                    outcome.label());
        }
    }

    private void publishIjkRealtimeRecoveryCompletion(
            IjkRealtimeRecoveryPolicy.Decision decision,
            boolean succeeded,
            String completionReason,
            long nowElapsedMs) {
        IjkRealtimeRecoveryController.Snapshot recovery =
                ijkRealtimeRecoveryController.snapshot();
        IjkBufferController.Snapshot reload =
                ijkBufferController.snapshot();
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "recovery_attempts", recovery.recoveryAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "successful_recoveries", recovery.successfulRecoveries(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "failed_recoveries", recovery.failedRecoveries(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "shared_reload_attempts", reload.reloadAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_REALTIME_RECOVERY,
                        succeeded ? PlaybackTelemetry.DecisionOutcome.APPLIED
                                : PlaybackTelemetry.DecisionOutcome.FAILED,
                        "rebuild-pending",
                        "ready",
                        succeeded ? "ready" : "failed",
                        succeeded ? "rebuild-ready" : "rebuild-failed",
                        succeeded ? "none"
                                : PlaybackTelemetry.safeLabel(
                                completionReason),
                        inputs),
                nowElapsedMs);
        PlaybackTrace.log("ijk-realtime", playbackTrace.current(),
                "action=rebuild-complete result=%s trigger=%s attempts=%d successes=%d failures=%d reason=%s",
                succeeded ? "ready" : "failed",
                decision.trigger().label(),
                recovery.recoveryAttempts(),
                recovery.successfulRecoveries(),
                recovery.failedRecoveries(),
                PlaybackTelemetry.safeLabel(completionReason));
    }

    private void activateIjkRuntimeProfileIfEligible(long nowElapsedMs) {
        if (ijkRuntimeManualOverride
                || !playbackAutoSession.active()
                || playerType != PlayerSetting.IJK
                || !PlaybackPerformanceSetting.isAuto(PlayerSetting.IJK)
                || PlayerSetting.getActivePlayer() != PlayerSetting.IJK) return;
        long now = Math.max(0, nowElapsedMs);
        IjkRuntimeProfileController.Facts facts = currentIjkRuntimeFacts(now);
        IjkRuntimeProfileController.RuntimeSample sample =
                currentIjkRuntimeSample(null, now);
        IjkRuntimeProfilePolicy.Path path = engine != null && engine.isHard()
                ? IjkRuntimeProfilePolicy.Path.IJK_HARD
                : IjkRuntimeProfilePolicy.Path.IJK_SOFT;
        if (!ijkRuntimeProfileController.activate(
                playbackAutoSession, path, facts, sample, now)) return;
        PlaybackTrace.log(
                "ijk-runtime-profile",
                playbackTrace.current(),
                "action=activate path=%s profile=%s",
                path.label(),
                ijkRuntimeProfileController.snapshot().profileId());
    }

    private IjkRuntimeProfileController.Facts currentIjkRuntimeFacts(
            long nowElapsedMs) {
        boolean automatic = PlayerSetting.getActivePlayer() == PlayerSetting.IJK
                && PlaybackPerformanceSetting.isAuto(PlayerSetting.IJK)
                && !ijkRuntimeManualOverride;
        return IjkRuntimeProfileController.Facts.fromContext(
                playbackAutoContextStore.snapshot(),
                automatic,
                Math.max(0, nowElapsedMs));
    }

    private IjkRuntimeProfileController.RuntimeSample currentIjkRuntimeSample(
            PlaybackTelemetry.RuntimeObservation observation,
            long nowElapsedMs) {
        boolean active = false;
        if (player != null) {
            try {
                active = player.getPlaybackState() == Player.STATE_READY
                        && player.getPlayWhenReady()
                        && player.isPlaying();
            } catch (Throwable ignored) {
            }
        }
        boolean decodeFpsUsable = false;
        float decodeFps = 0f;
        boolean outputFpsUsable = false;
        float outputFps = 0f;
        boolean dropRateUsable = false;
        int dropRatePermille = 0;
        if (engine instanceof IjkPlayerEngine ijk) {
            IjkDecodePressurePolicy.DecodeSnapshot decode =
                    ijk.getDecodePressureSnapshot();
            decodeFpsUsable = decode.available()
                    && decode.decodeFps() > 0;
            decodeFps = decodeFpsUsable ? decode.decodeFps() : 0f;
            outputFpsUsable = decode.available()
                    && decode.outputFps() > 0;
            outputFps = outputFpsUsable ? decode.outputFps() : 0f;
            IjkPlayerEngine.DropRateSnapshot drop =
                    ijk.getDropRateSnapshot();
            dropRateUsable = drop.available();
            dropRatePermille = drop.permille();
        } else if (observation != null
                && observation.renderedFrameRate().known()
                && observation.renderedFrameRate().value() > 0) {
            outputFpsUsable = true;
            outputFps = observation.renderedFrameRate().value();
        }
        int rebufferCount = observation != null
                && observation.rebufferCount().known()
                ? Math.max(0, observation.rebufferCount().value())
                : getRebufferCount();
        boolean droppedFramesUsable = observation != null
                && observation.droppedFrames().known();
        long droppedFrames = droppedFramesUsable
                ? Math.max(0, observation.droppedFrames().value()) : 0;
        long nativeHeapBytes = -1;
        long pssBytes = -1;
        PlaybackAutoContext context = playbackAutoContextStore.snapshot();
        long now = Math.max(0, nowElapsedMs);
        if (playbackAutoSession.equals(context.session())) {
            PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot>
                    memory = context.device().memorySnapshot();
            if (memory.isUsable(now)
                    && memory.value().nativeHeapAllocatedBytes() != null) {
                nativeHeapBytes = memory.value().nativeHeapAllocatedBytes();
            }
            PlaybackAutoContext.Fact<Long> pss =
                    context.device().diagnosticPssBytes();
            if (pss.isUsable(now) && pss.value() >= 0) {
                pssBytes = pss.value();
            }
        }
        return new IjkRuntimeProfileController.RuntimeSample(
                active,
                rebufferCount,
                decodeFpsUsable,
                decodeFps,
                outputFpsUsable,
                outputFps,
                dropRateUsable,
                dropRatePermille,
                droppedFramesUsable,
                droppedFrames,
                nativeHeapBytes,
                pssBytes);
    }

    private void onIjkRuntimeFirstFrame(long nowElapsedMs) {
        if (!playbackAutoSession.active()) return;
        long now = Math.max(0, nowElapsedMs);
        IjkRuntimeProfileController.Observation observation =
                ijkRuntimeProfileController.onFirstFrame(
                        playbackAutoSession,
                        currentIjkRuntimeFacts(now),
                        currentIjkRuntimeSample(null, now),
                        now,
                        System.currentTimeMillis());
        publishIjkRuntimeObservation(observation, now);
    }

    private boolean evaluateIjkRuntimeProfile(
            PlaybackTelemetry.RuntimeObservation runtime,
            long nowElapsedMs) {
        if (!playbackAutoSession.active()) return false;
        long now = Math.max(0, nowElapsedMs);
        IjkRuntimeProfileController.Facts facts = currentIjkRuntimeFacts(now);
        IjkRuntimeProfileController.RuntimeSample sample =
                currentIjkRuntimeSample(runtime, now);
        IjkRuntimeProfileController.Observation observation =
                ijkRuntimeProfileController.observe(
                        playbackAutoSession,
                        facts,
                        sample,
                        now,
                        System.currentTimeMillis());
        publishIjkRuntimeObservation(observation, now);
        if (experimentAllowed(
                PlaybackExperimentPolicy.Action.IJK_RUNTIME_KERNEL_FALLBACK)) {
            IjkRuntimeProfileController.Decision decision =
                    ijkRuntimeProfileController.handleFirstFrameTimeout(
                            playbackAutoSession,
                            facts,
                            sample,
                            now,
                            System.currentTimeMillis());
            if (decision != null) {
                publishIjkRuntimeFailureDecision(decision, now);
                if (decision.requestsSwitch()) {
                    boolean switched = switchIjkRuntimeFallback(decision);
                    if (!switched) {
                        ijkRuntimeProfileController.onSwitchStartFailed(
                                playbackAutoSession,
                                System.currentTimeMillis());
                        publishIjkRuntimeSwitchStartFailure(
                                "switch-start-failed");
                    }
                    if (switched) return true;
                }
            }
        }
        return evaluateIjkFirstFrameWatchdog(facts, sample, now);
    }


    private boolean evaluateIjkFirstFrameWatchdog(
            IjkRuntimeProfileController.Facts facts,
            IjkRuntimeProfileController.RuntimeSample sample,
            long nowElapsedMs) {
        IjkFirstFrameWatchdog.Decision decision =
                ijkFirstFrameWatchdog.evaluate(
                        playbackAutoSession,
                        new IjkFirstFrameWatchdog.RuntimeSample(
                                sample.active(),
                                hasVideoTrackForFirstFrame(facts),
                                sample.outputFrameRateUsable()),
                        nowElapsedMs);
        if (!decision.timedOut()) return false;
        int fallbackMode = PlayerSetting.getFailureFallback();
        int decode = engine == null ? PlayerEngine.HARD : engine.getDecode();
        int fallbackAction = nextFallbackAction(fallbackMode, decode);
        PlaybackTrace.log(
                "ijk-first-frame-watchdog",
                playbackTrace.current(),
                "action=timeout activeMs=%d decode=%d fallbackMode=%d fallbackAction=%d",
                decision.activeDurationMs(),
                decode,
                fallbackMode,
                fallbackAction);
        App.removeCallbacks(runnable);
        completeIjkBufferManagedReload(
                false, "first-frame-timeout", nowElapsedMs, true);
        ijkRealtimeRecoveryController.onPlaybackError(playbackAutoSession);
        ijkDecodePressureController.onPlaybackError(playbackAutoSession);
        PlaybackException error = new PlaybackException(
                ResUtil.getString(R.string.error_play_stage_output),
                null,
                PlaybackException.ERROR_CODE_DECODING_FAILED);
        if (fallbackPlayback(error)) {
            PlaybackTrace.log(
                    "ijk-first-frame-watchdog",
                    playbackTrace.current(),
                    "action=fallback result=started fallbackAction=%d",
                    fallbackAction);
            return true;
        }
        PlaybackTrace.log(
                "ijk-first-frame-watchdog",
                playbackTrace.current(),
                "action=fallback result=unavailable fallbackAction=%d",
                fallbackAction);
        finishPlaybackProfileAbSession(
                "first-frame-timeout", nowElapsedMs);
        callback.onError(ResUtil.getString(R.string.error_play_stage_output));
        return true;
    }

    // 首帧看门狗的视频轨证据：轨道列表在 prepared 后即可得，不依赖
    // onVideoSizeChanged，因此黑屏且从未回调尺寸的场景也能被判定。
    private boolean hasVideoTrackForFirstFrame(
            IjkRuntimeProfileController.Facts facts) {
        if (engine != null) {
            try {
                Tracks tracks = engine.getCurrentTracks();
                if (tracks != null && tracks.containsType(C.TRACK_TYPE_VIDEO)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return facts.hasVideoTrackEvidence();
    }

    private void finishIjkRuntimeProfileSession(
            PlaybackTelemetry.RuntimeObservation runtime,
            long nowElapsedMs) {
        if (!playbackAutoSession.active()) return;
        long now = Math.max(0, nowElapsedMs);
        ijkRuntimeProfileController.finishSession(
                playbackAutoSession,
                currentIjkRuntimeFacts(now),
                currentIjkRuntimeSample(runtime, now),
                System.currentTimeMillis());
    }

    private boolean retryIjkRuntimeProfileFallback(
            PlaybackException error,
            PlaybackErrorClassifier.Failure failure,
            PlayerEngine.ErrorAction engineAction) {
        if (engineAction == PlayerEngine.ErrorAction.RECOVERED
                || error == null
                || failure == null
                || !experimentAllowed(
                PlaybackExperimentPolicy.Action.IJK_RUNTIME_KERNEL_FALLBACK)
                || !playbackAutoSession.active()) return false;
        long now = SystemClock.elapsedRealtime();
        IjkPlayerEngine.ErrorSnapshot ijkError =
                engine instanceof IjkPlayerEngine ijk
                        ? ijk.getLastErrorSnapshot()
                        : IjkPlayerEngine.ErrorSnapshot.none();
        IjkRuntimeProfileController.Decision decision =
                ijkRuntimeProfileController.handleFailure(
                        playbackAutoSession,
                        currentIjkRuntimeFacts(now),
                        currentIjkRuntimeSample(
                                collectPlaybackTelemetry(
                                        PlaybackAutoContext.PlaybackPhase.ERROR,
                                        now),
                                now),
                        new IjkRuntimeProfileController.FailureEvent(
                                failure.stage(),
                                error.errorCode,
                                ijkError.what(),
                                ijkError.extra(),
                                ijkError.prepared()),
                        now,
                        System.currentTimeMillis());
        publishIjkRuntimeFailureDecision(decision, now);
        if (!decision.requestsSwitch()) return false;
        boolean switched = switchIjkRuntimeFallback(decision);
        if (!switched) {
            ijkRuntimeProfileController.onSwitchStartFailed(
                    playbackAutoSession, System.currentTimeMillis());
            publishIjkRuntimeSwitchStartFailure("switch-start-failed");
        }
        return switched;
    }

    private boolean switchIjkRuntimeFallback(
            IjkRuntimeProfileController.Decision decision) {
        if (decision == null
                || !decision.requestsSwitch()
                || engine == null
                || player == null
                || spec == null
                || TextUtils.isEmpty(spec.getUrl())) return false;
        IjkRuntimeProfilePolicy.Path targetPath = decision.targetPath();
        int targetPlayer = playerTypeForIjkRuntimePath(targetPath);
        int targetDecode = targetPath == IjkRuntimeProfilePolicy.Path.IJK_SOFT
                ? PlayerEngine.SOFT : PlayerEngine.HARD;
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        long now = SystemClock.elapsedRealtime();
        long position = ijkRuntimeVodResumePosition(now);
        PlayerEngine replacement;
        try {
            replacement = buildEngine(targetPlayer, targetDecode);
        } catch (Throwable error) {
            PlaybackTrace.log(
                    "ijk-runtime-profile",
                    playbackTrace.current(),
                    "action=build-fallback target=%s result=failed errorType=%s",
                    targetPath.label(),
                    error.getClass().getSimpleName());
            return false;
        }
        Player replacementPlayer = replacement.getPlayer();
        try {
            prepareSeq++;
            App.removeCallbacks(runnable);
            App.removeCallbacks(networkProtectionRunnable);
            resetNetworkProtectionSession("ijk-runtime-fallback");
            resetLutRuntimeState("ijk_runtime_fallback", true);
            stopNativeAudioSession();
            stopParse();
            engine.release();
            engine = replacement;
            player = replacementPlayer;
            playerType = targetPlayer;
            playWhenReady = wasPlayWhenReady;
            hardDecodeSwitchRetryArmed = false;
            initTrack = false;
            ijkRuntimeTemporaryFallback = true;
            pendingIjkRuntimeFallbackReparse = false;
            callback.onPlayerRebuild(player, false);
            PlaybackTrace.log(
                    "ijk-runtime-profile",
                    playbackTrace.current(),
                    "action=switch from=%s target=%s count=%d resume=%d play=%s",
                    decision.fromPath().label(),
                    targetPath.label(),
                    decision.fallbackCount(),
                    position == C.TIME_UNSET ? 0 : position,
                    wasPlayWhenReady);
            pendingIjkRuntimeFallbackReparse = true;
            if (reparseForPlayerSwitch(position, speed, repeat)) {
                return true;
            }
            pendingIjkRuntimeFallbackReparse = false;
            setMediaItem(Constant.TIMEOUT_PLAY);
            if (position > 0) seekTo(position);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            return true;
        } catch (Throwable error) {
            PlaybackTrace.log(
                    "ijk-runtime-profile",
                    playbackTrace.current(),
                    "action=switch target=%s result=failed errorType=%s",
                    targetPath.label(),
                    error.getClass().getSimpleName());
            try {
                if (engine != replacement) replacement.release();
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    private long ijkRuntimeVodResumePosition(long nowElapsedMs) {
        PlaybackAutoContext context = playbackAutoContextStore.snapshot();
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> stream =
                context.resource().streamKind();
        if (!playbackAutoSession.equals(context.session())
                || !stream.isUsable(Math.max(0, nowElapsedMs))
                || stream.value() != PlaybackAutoContext.StreamKind.VOD) {
            return C.TIME_UNSET;
        }
        return Math.max(0, getPosition());
    }

    private static int playerTypeForIjkRuntimePath(
            IjkRuntimeProfilePolicy.Path path) {
        // MPV 内核已停用：IJK 运行期回退策略仍会给出 MPV 这一档位，这里直接落到 EXO，
        // 否则会出现 playerType=MPV 与引擎类型不一致（buildEngine 已把 MPV 建成 Exo 引擎）。
        if (path == IjkRuntimeProfilePolicy.Path.MPV) {
            return PlayerSetting.EXO;
        }
        if (path == IjkRuntimeProfilePolicy.Path.EXO) {
            return PlayerSetting.EXO;
        }
        return PlayerSetting.IJK;
    }

    private void prepareIjkRuntimeForUserPlayback() {
        ijkRuntimeManualOverride = false;
        pendingIjkRuntimeFallbackReparse = false;
        if (!ijkRuntimeTemporaryFallback) return;
        if (PlayerSetting.getActivePlayer() != PlayerSetting.IJK
                || !PlaybackPerformanceSetting.isAuto(PlayerSetting.IJK)) {
            ijkRuntimeTemporaryFallback = false;
            return;
        }
        if (engine instanceof IjkPlayerEngine && engine.isHard()) {
            playerType = PlayerSetting.IJK;
            ijkRuntimeTemporaryFallback = false;
            return;
        }
        PlayerEngine replacement;
        try {
            replacement = buildEngine(PlayerSetting.IJK, PlayerEngine.HARD);
        } catch (Throwable error) {
            PlaybackTrace.log(
                    "ijk-runtime-profile",
                    playbackTrace.current(),
                    "action=restore-default result=failed errorType=%s",
                    error.getClass().getSimpleName());
            return;
        }
        try {
            prepareSeq++;
            stopNativeAudioSession();
            if (engine != null) engine.release();
            engine = replacement;
            player = replacement.getPlayer();
            playerType = PlayerSetting.IJK;
            callback.onPlayerRebuild(player, false);
            ijkRuntimeTemporaryFallback = false;
            PlaybackTrace.log(
                    "ijk-runtime-profile",
                    playbackTrace.current(),
                    "action=restore-default target=ijk-hard result=applied");
        } catch (Throwable error) {
            PlaybackTrace.log(
                    "ijk-runtime-profile",
                    playbackTrace.current(),
                    "action=restore-default result=failed errorType=%s",
                    error.getClass().getSimpleName());
        }
    }

    private void beginIjkRuntimeManualOverride() {
        ijkRuntimeManualOverride = true;
        ijkRuntimeTemporaryFallback = false;
        pendingIjkRuntimeFallbackReparse = false;
        ijkRuntimeProfileController.cancel(playbackAutoSession);
    }

    private void publishIjkRuntimeObservation(
            IjkRuntimeProfileController.Observation observation,
            long nowElapsedMs) {
        if (observation == null || !observation.material()) return;
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "profile", observation.profileId(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "rebuffer_count", observation.rebufferCount(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        addNumberInput(inputs, "drop_rate_permille",
                observation.dropRatePermille(),
                PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                PlaybackAutoContext.Confidence.MEDIUM);
        addNumberInput(inputs, "rendered_ratio_permille",
                observation.renderedRatioPermille(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM);
        addNumberInput(inputs, "native_growth_bytes",
                observation.nativeHeapGrowthBytes(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.MEDIUM);
        addNumberInput(inputs, "pss_growth_bytes",
                observation.pssGrowthBytes(),
                PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.LOW);
        PlaybackTelemetry.DecisionOutcome outcome =
                observation.fallbackSucceeded()
                        ? PlaybackTelemetry.DecisionOutcome.APPLIED
                        : observation.action()
                        == IjkRuntimeProfileController.ObservationAction.STABLE
                        ? PlaybackTelemetry.DecisionOutcome.SELECTED
                        : PlaybackTelemetry.DecisionOutcome.OBSERVED;
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_RUNTIME_PROFILE,
                        outcome,
                        observation.path().label(),
                        observation.action().label(),
                        observation.path().label(),
                        observation.reason().label(),
                        "none",
                        inputs),
                nowElapsedMs);
        PlaybackTrace.log(
                "ijk-runtime-profile",
                playbackTrace.current(),
                "action=%s reason=%s key=%s path=%s stable=%s fallbackSuccess=%s rebuffers=%d dropPermille=%d renderedPermille=%d nativeGrowth=%d pssGrowth=%d",
                observation.action().label(),
                observation.reason().label(),
                observation.profileId(),
                observation.path().label(),
                observation.health().stable(),
                observation.fallbackSucceeded(),
                observation.rebufferCount(),
                observation.dropRatePermille(),
                observation.renderedRatioPermille(),
                observation.nativeHeapGrowthBytes(),
                observation.pssGrowthBytes());
    }

    private void publishIjkRuntimeFailureDecision(
            IjkRuntimeProfileController.Decision decision,
            long nowElapsedMs) {
        if (decision == null
                || decision.reason()
                == IjkRuntimeProfileController.Reason.NOT_MANAGED) return;
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "profile", decision.profileId(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "failure_kind", decision.assessment().kind().label(),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "fallback_count", decision.fallbackCount(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "failure_persisted", decision.failurePersisted(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "fallback_failure_recorded",
                decision.fallbackFailureRecorded(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_RUNTIME_PROFILE,
                        decision.requestsSwitch()
                                ? PlaybackTelemetry.DecisionOutcome.REQUESTED
                                : PlaybackTelemetry.DecisionOutcome.HELD,
                        decision.fromPath().label(),
                        decision.targetPath() == null
                                ? "hold" : decision.targetPath().label(),
                        decision.requestsSwitch()
                                ? "switch-pending" : decision.fromPath().label(),
                        decision.reason().label(),
                        decision.requestsSwitch()
                                ? "none" : decision.reason().label(),
                        inputs),
                nowElapsedMs);
        PlaybackTrace.log(
                "ijk-runtime-profile",
                playbackTrace.current(),
                "action=%s reason=%s key=%s from=%s target=%s kind=%s persist=%s fallbackCount=%d",
                decision.action().label(),
                decision.reason().label(),
                decision.profileId(),
                decision.fromPath().label(),
                decision.targetPath() == null
                        ? "none" : decision.targetPath().label(),
                decision.assessment().kind().label(),
                decision.failurePersisted(),
                decision.fallbackCount());
    }

    private void publishIjkRuntimeSwitchStartFailure(String reason) {
        long now = SystemClock.elapsedRealtime();
        IjkRuntimeProfileController.Snapshot snapshot =
                ijkRuntimeProfileController.snapshot();
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.IJK_RUNTIME_PROFILE,
                        PlaybackTelemetry.DecisionOutcome.FAILED,
                        snapshot.currentPath().label(),
                        snapshot.currentPath().label(),
                        "failed",
                        IjkRuntimeProfileController.Reason
                                .SWITCH_START_FAILED.label(),
                        PlaybackTelemetry.safeLabel(reason),
                        List.of(
                                PlaybackTelemetry.DecisionInput.number(
                                        "fallback_count",
                                        snapshot.fallbackCount(),
                                        PlaybackAutoContext.ValueSource
                                                .PLAYER_MANAGER,
                                        PlaybackAutoContext.Confidence.HIGH))),
                now);
    }

    private static void addNumberInput(
            List<PlaybackTelemetry.DecisionInput> inputs,
            String name,
            long value,
            PlaybackAutoContext.ValueSource source,
            PlaybackAutoContext.Confidence confidence) {
        if (value < 0 || source == PlaybackAutoContext.ValueSource.UNKNOWN
                || confidence == PlaybackAutoContext.Confidence.UNKNOWN) {
            inputs.add(PlaybackTelemetry.DecisionInput.unknown(name));
        } else {
            inputs.add(PlaybackTelemetry.DecisionInput.number(name, value, source, confidence));
        }
    }

    private static long optionBits(String option) {
        if (TextUtils.isEmpty(option)) return 0;
        try {
            return Math.max(0, Long.parseLong(option.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }


    public void applyPerformanceSettings() {
        // Only the EXO kernel supports live performance reconfiguration now that MPV is gone.
        if (!isExo()) return;
        resetNetworkProtectionSession("performance-settings-changed");
        scheduleNetworkProtection(0);
        if (engine instanceof ExoPlayerEngine exo
                && exo.requiresDv7Hdr10FallbackRebuild()) {
            rebuildAndRestartExo("dv7-fallback-setting-changed");
        }
    }

    private boolean rebuildAndRestartExo(String reason) {
        if (!isExo() || spec == null || TextUtils.isEmpty(spec.getUrl())) {
            return false;
        }
        long position = Math.max(0, player.getCurrentPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        prepareSeq++;
        App.removeCallbacks(runnable);
        videoSize = null;
        initTrack = false;
        rebuildPlayer();
        playWhenReady = wasPlayWhenReady;
        applySubtitleStyle();
        playbackTrace.mark(PlaybackTrace.Stage.PREPARE,
                "player=" + playerType + " decode=" + engine.getDecode()
                        + " exo=" + reason);
        startEngine(spec, position, wasPlayWhenReady);
        startNativeAudioSession(wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        return true;
    }

    /**
     * 把宿主放在 spec 上的待恢复字幕挂进字幕列表。
     *
     * <p>发生在 {@code setMediaItem} 之前，所以走的是正常起播路径——不会触发
     * {@link #setSub(Sub)} 里的重启分支，用户也就看不到画面闪一下。三个内核
     * 统一从 {@code PlaySpec.subs} 取字幕，因此这里不区分内核。
     */
    private void restorePendingSubtitle() {
        Sub sub = pendingRestoreSub;
        pendingRestoreSub = null;
        if (spec == null || sub == null) return;
        // setSub() 内部会先 remove 再插首位，源站自带同一个 url 时天然幂等。
        spec.setSub(sub);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("subtitle-restore", "inject persisted subtitle name=%s key=%s", sub.getName(), getKey());
    }

    private boolean hasRequestedSubtitle(List<Track> tracks) {
        return findRequestedSubtitle(tracks) != null;
    }

    private Track findRequestedSubtitle(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return null;
        for (Track track : tracks) {
            if (track.getType() == C.TRACK_TYPE_TEXT
                    && track.isSelected() && !track.isDisabled()) return track;
        }
        return null;
    }

    private void restoreTrackSelection(List<Track> tracks) {
        if (tracks != null && !tracks.isEmpty()) engine.setTrack(tracks);
    }

    private PlayerEngine buildEngine(int type, int decode) {
        // MPV 内核已停用：这里是最后一道归一。任何来源的 MPV（旧配置、旧历史、内部自动路由）
        // 都先压回 EXO 再建引擎，避免出现 playerType=MPV 而引擎实际是 ExoPlayerEngine 的错位状态。
        type = PlayerSetting.sanitizePlayer(type);
        if (type != PlayerSetting.EXO) {
            exoSpeedRestoreState.clear();
            mediaSignals.detachPipeline();
        }
        return switch (type) {
            case PlayerSetting.IJK -> new IjkPlayerEngine(decode, listener);
            case PlayerSetting.SYSTEM -> new SystemPlayerEngine(decode, listener);
            default -> new ExoPlayerEngine(decode, listener, mediaSignals, mediaClock);
        };
    }

    public void browse(PlaySpec spec) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, true);
    }

    public void start(PlaySpec spec, long timeout, boolean playWhenReady) {
        start(spec, timeout, playWhenReady, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, boolean playWhenReady, long positionMs) {
        mediaSession.begin(0L);
        endPlaybackTelemetrySession("replace-start");
        prepareIjkRuntimeForUserPlayback();
        clearPendingSwitchRestore();
        clearDanmaku("start");
        this.spec = spec;
        pendingInitialStartPositionMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        beginPlaybackTrace("start", false);
        this.playWhenReady = playWhenReady;
        manualPlayerSwitchPending = false;
        localProxyRetry = 0;
        resetPlayerFallback();
        hardDecodeSwitchRetryArmed = false;
        setMediaItem(timeout);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, true);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, boolean playWhenReady) {
        parse(key, result, useParse, metadata, playWhenReady, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata,
                      boolean playWhenReady, long positionMs) {
        mediaSession.begin(0L);
        endPlaybackTelemetrySession("replace-parse");
        prepareIjkRuntimeForUserPlayback();
        stopParse();
        clearPendingSwitchRestore();
        clearDanmaku("parse");
        spec = PlaySpec.fromParse(result, key, metadata, useParse);
        pendingInitialStartPositionMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        beginPlaybackTrace("parse", false);
        this.playWhenReady = playWhenReady;
        manualPlayerSwitchPending = false;
        localProxyRetry = 0;
        parseHealthStartedAt = System.currentTimeMillis();
        parseHealthRecorded = false;
        resetPlayerFallback();
        hardDecodeSwitchRetryArmed = false;
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    private boolean reparseForPlayerSwitch(long position, float speed, boolean repeat) {
        if (spec == null || !spec.canReparse() || !spec.isParseSource()) return false;
        Result result = spec.getParseResult();
        boolean useParse = spec.isParseUseParse();
        MediaMetadata metadata = spec.getMetadata();
        String key = spec.getKey();
        pendingSwitchRestore = true;
        pendingSwitchPositionMs = position;
        pendingSwitchSpeed = speed;
        pendingSwitchRepeat = repeat;
        stopParse();
        if (spec.isParseSource()) {
            spec = PlaySpec.fromParse(result, key, metadata, useParse);
            bindPlaybackTrace();
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player reparse type=%d position=%d useParse=%s spec=%s", playerType, position, useParse, debugSpec());
            parseJob = ParseJob.create(this).start(result, useParse);
        } else {
            refreshDirectForPlayerSwitch(result, key, metadata);
        }
        return true;
    }

    private void refreshDirectForPlayerSwitch(Result result, String key, MediaMetadata metadata) {
        int requestSeq = prepareSeq;
        int requestPlayerType = playerType;
        PlaySpec requestSpec = spec;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player refresh direct type=%d keyLen=%d flag=%s url=%s", requestPlayerType, safeLength(key), result.getFlag(), summarizeUrl(result.getUrl().v()));
        Task.execute(() -> {
            try {
                Result refreshed = SiteApi.playerContent(key, result.getFlag(), result.getUrl().v(), requestPlayerType);
                App.post(() -> {
                    if (!isCurrentDirectSwitchRefresh(pendingSwitchRestore, requestSeq, prepareSeq, requestPlayerType, playerType, requestSpec, spec)) return;
                    startRefreshedSwitchResult(refreshed, key, metadata);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    if (!isCurrentDirectSwitchRefresh(pendingSwitchRestore, requestSeq, prepareSeq, requestPlayerType, playerType, requestSpec, spec)) return;
                    clearPendingSwitchRestore();
                    callback.onError(e.getMessage());
                });
            }
        });
    }

    private void startRefreshedSwitchResult(Result result, String key, MediaMetadata metadata) {
        if (result == null || result.hasMsg() || result.getRealUrl().isEmpty()) {
            clearPendingSwitchRestore();
            callback.onError(result == null ? ResUtil.getString(R.string.error_play_url) : result.hasMsg() ? result.getMsg() : ResUtil.getString(R.string.error_play_url));
            return;
        }
        if (result.needParse()) {
            spec = PlaySpec.fromParse(result, key, metadata, false);
            bindPlaybackTrace();
            parseJob = ParseJob.create(this).start(result, false);
            return;
        }
        spec = PlaySpec.from(result, key, metadata);
        bindPlaybackTrace();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "switch player refreshed direct spec=%s", debugSpec());
        setMediaItem(Constant.TIMEOUT_PLAY);
        restoreAfterSwitchReparse();
    }

    private void restoreAfterSwitchReparse() {
        if (!pendingSwitchRestore) return;
        long position = pendingSwitchPositionMs;
        float speed = pendingSwitchSpeed;
        boolean repeat = pendingSwitchRepeat;
        clearPendingSwitchRestore();
        if (position > 0) seekTo(position);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
    }

    private void clearPendingSwitchRestore() {
        pendingSwitchRestore = false;
        pendingIjkRuntimeFallbackReparse = false;
        pendingSwitchPositionMs = C.TIME_UNSET;
        pendingSwitchSpeed = 1f;
        pendingSwitchRepeat = false;
    }

    public void setMediaItem() {
        playWhenReady = player == null || player.getPlayWhenReady();
        setMediaItem(Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(long timeout) {
        if (spec == null || spec.getUrl() == null) return;
        if (!ensurePlayerAvailableForPlayback()) return;
        int seq = ++prepareSeq;
        if (LocalProxyDebug.shouldAwaitReady(spec.getUrl())) {
            awaitLocalProxyAndSetMediaItem(seq, timeout);
            return;
        }
        setMediaItemNow(timeout, true);
    }

    public void reloadCurrentMediaItem() {
        restartCurrentItemWithState();
    }

    private void restartCurrentItemWithState() {
        if (spec == null || spec.getUrl() == null || engine == null || player == null) return;
        if (player.getCurrentMediaItem() == null || player.getPlaybackState() == Player.STATE_IDLE) {
            setMediaItem();
            return;
        }
        if (!ensurePlayerAvailableForPlayback()) return;
        long position = Math.max(0, player.getCurrentPosition());
        boolean playWhenReady = player.getPlayWhenReady();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "restart media item position=%d play=%s spec=%s", position, playWhenReady, debugSpec());
        App.removeCallbacks(runnable);
        currentDanmakuUrl = null;
        setDanmakus(spec.getDanmakus());
        initTrack = false;
        waitingLutBeforePlay = false;
        restartEngine(spec, position, playWhenReady);
        App.post(runnable, Constant.TIMEOUT_PLAY);
    }

    private void awaitLocalProxyAndSetMediaItem(int seq, long timeout) {
        PlaySpec target = spec;
        String url = target.getUrl();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await start seq=%d timeout=%d spec=%s", seq, timeout, debugSpec());
        Task.execute(() -> {
            boolean ready = LocalProxyDebug.awaitReady(url, LOCAL_PROXY_READY_TIMEOUT_MS);
            App.post(() -> {
                if (seq != prepareSeq || spec != target || engine == null) {
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await skip seq=%d current=%d ready=%s", seq, prepareSeq, ready);
                    return;
                }
                if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy await done seq=%d ready=%s spec=%s", seq, ready, debugSpec());
                setMediaItemNow(timeout, true);
            });
        });
    }

    private void prepareExoSpeedForMediaItem(int generation) {
        if (!isExo() || player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            exoSpeedRestoreState.clear();
            return;
        }
        float actualSpeed = player.getPlaybackParameters().speed;
        float targetSpeed = getSpeed();
        // Avoid configuring MediaCodec with an accelerated operating rate on affected Android TV decoders.
        setActualPlaybackSpeed(exoSpeedRestoreState.beginPrepare(generation, targetSpeed));
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "exo speed prepare generation=%d target=%.2f previous=%.2f", generation, targetSpeed, actualSpeed);
    }

    private void restoreExoSpeedAfterPrepare(int generation) {
        if (!isExo() || player == null || !player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return;
        float speed = exoSpeedRestoreState.takeReadySpeed(generation);
        if (Float.isNaN(speed)) return;
        setActualPlaybackSpeed(speed);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "exo speed restored generation=%d target=%.2f", generation, speed);
    }

    private void cancelExoSpeedPrepare(int generation) {
        exoSpeedRestoreState.cancelPrepare(generation);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "exo speed prepare canceled generation=%d", generation);
    }

    private void setActualPlaybackSpeed(float speed) {
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
    }

    private void setMediaItemNow(long timeout, boolean notifyPrepare) {
        if (spec == null || spec.getUrl() == null || engine == null) return;
        prepareExoFrameSchedulingForNewPlayback();
        spec.setPlaybackTraceId(playbackTrace.ensure());
        spec.refreshPlaybackRoute();
        publishPlaybackAutoContext(false);
        activateIjkRuntimeProfileIfEligible(SystemClock.elapsedRealtime());
        applyIjkAutoInitialControl();
        logPlaybackRoute();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "setMediaItem timeout=%d notify=%s spec=%s", timeout, notifyPrepare, debugSpec());
        resetNetworkProtectionSession("new-media");
        App.removeCallbacks(runnable);
        setDanmakus(spec.getDanmakus());
        prepareLutPipeline();
        initTrack = false;
        waitingLutBeforePlay = false;
        applySubtitleStyle();
        playbackTrace.mark(PlaybackTrace.Stage.PREPARE, "player=" + playerType + " decode=" + engine.getDecode());
        long initialPositionMs = pendingInitialStartPositionMs;
        pendingInitialStartPositionMs = C.TIME_UNSET;
        startEngine(spec, initialPositionMs, playWhenReady);
        publishPlaybackTelemetry();
        schedulePlaybackTelemetry();
        startNativeAudioSession(playWhenReady);
        App.post(runnable, timeout);
        if (notifyPrepare) callback.onPrepare();
    }

    private void startEngine(PlaySpec source, boolean playWhenReady) {
        beginPlaybackAttempt();
        engine.start(source.checkUa(), playWhenReady);
    }

    private void startEngine(PlaySpec source, long position, boolean playWhenReady) {
        beginPlaybackAttempt();
        engine.start(source.checkUa(), position, playWhenReady);
    }

    private void restartEngine(PlaySpec source, long position, boolean playWhenReady) {
        beginPlaybackAttempt();
        engine.restart(source.checkUa(), position, playWhenReady);
    }

    private void beginPlaybackAttempt() {
        if (!playbackAutoSession.active()) return;
        long now = SystemClock.elapsedRealtime();
        // 自动档位只有在实验开关允许时才会走 handleFirstFrameTimeout；
        // 否则由独立看门狗接管，避免两条路径都不生效留下无保护缺口。
        boolean ijkRuntimeCoversFirstFrame =
                ijkRuntimeProfileController.snapshot().managed()
                        && experimentAllowed(PlaybackExperimentPolicy.Action
                        .IJK_RUNTIME_KERNEL_FALLBACK);
        ijkRuntimeProfileController.onPlaybackAttemptStarted(
                playbackAutoSession, currentIjkRuntimeSample(null, now));
        ijkFirstFrameWatchdog.beginAttempt(
                playbackAutoSession, isIjk() && !ijkRuntimeCoversFirstFrame);
    }

    private void prepareExoFrameSchedulingForNewPlayback() {
        if (!(engine instanceof ExoPlayerEngine exo)) return;
        if (!exo.prepareFrameSchedulingForNextPlayback()) return;
        rebuildPlayer(false);
    }

    private void applySubtitleStyle() {
        if (engine != null) engine.setSubtitleStyle(PlayerSetting.getSubtitleTextSize(), PlayerSetting.getSubtitlePosition());
    }

    private void startNativeAudioSession(boolean shouldPlay) {
        if (!shouldPlay || !isNativePlayer()) return;
        requestNativeAudioFocus();
        registerNoisyReceiver();
    }

    private void stopNativeAudioSession() {
        unregisterNoisyReceiver();
        abandonNativeAudioFocus();
        resumeOnAudioFocusGain = false;
    }

    private void requestNativeAudioFocus() {
        AudioManager manager = audioManager();
        if (manager == null || audioFocusHeld) return;
        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusApi26.request(manager, audioFocusChangeListener, raw -> diagnosticNativeFocus("request", raw, 0));
            result = audioFocusRequest == null ? AudioManager.AUDIOFOCUS_REQUEST_FAILED : AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            result = manager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            diagnosticNativeFocus("request", result, 0);
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!audioFocusHeld && SpiderDebug.isEnabled()) SpiderDebug.log("player", "native audio focus request denied type=%d", playerType);
    }

    private void abandonNativeAudioFocus() {
        if (!audioFocusHeld) return;
        AudioManager manager = audioManager();
        if (manager != null) {
            int result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? AudioFocusApi26.abandon(manager, audioFocusRequest) : manager.abandonAudioFocus(audioFocusChangeListener);
            diagnosticNativeFocus("abandon", result, 0);
        }
        audioFocusRequest = null;
        audioFocusHeld = false;
    }

    private void registerNoisyReceiver() {
        if (noisyReceiverRegistered) return;
        try {
            App.get().registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyReceiverRegistered = true;
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "register noisy receiver failed error=%s", causeChain(e));
        }
    }

    private void unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return;
        try {
            App.get().unregisterReceiver(noisyReceiver);
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "unregister noisy receiver failed error=%s", causeChain(e));
        }
        noisyReceiverRegistered = false;
    }

    private void onNativeAudioBecomingNoisy() {
        if (!isNativePlayer() || player == null) return;
        boolean wasPlaying = player.isPlaying();
        player.pause();
        stopNativeAudioSession();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "native audio noisy pause type=%d wasPlaying=%s", playerType, wasPlaying);
    }

    private void onNativeAudioFocusChanged(int focusChange) {
        if (!isNativePlayer() || player == null) return;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false;
                    startNativeAudioSession(true);
                    player.play();
                }
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnAudioFocusGain = player.isPlaying();
                player.pause();
            }
            case AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnAudioFocusGain = false;
                player.pause();
                stopNativeAudioSession();
            }
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "native audio focus changed type=%d change=%d resume=%s", playerType, focusChange, resumeOnAudioFocusGain);
        diagnosticNativeFocus("callback", 0, focusChange);
    }

    private void diagnosticNativeFocus(String action, int result, int change) {
        if (!com.github.catvod.crawler.DebugLogStore.isEnabled()) return;
        com.github.catvod.crawler.DebugLogStore.event(new com.github.catvod.crawler.diagnostics.DiagnosticEvent("audio.focus", getPlaybackTraceId(),
                "native-focus-controller", 0, 0).observed("focusOwner", "App/native-player").observed("focusAction", action)
                .observed("focusGain", AudioManager.AUDIOFOCUS_GAIN).observed("focusResult", result).observed("focusChange", change)
                .observed("usage", android.media.AudioAttributes.USAGE_MEDIA).observed("contentType", android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .observed("playWhenReady", player != null && player.getPlayWhenReady()).observed("active", audioFocusHeld)
                .observed("metricScope", "controller focus session; callback result is a focus change, not a new request"));
    }

    private AudioManager audioManager() {
        return (AudioManager) App.get().getSystemService(Context.AUDIO_SERVICE);
    }

    private static final class AudioFocusApi26 {

        private static Object request(AudioManager manager, AudioManager.OnAudioFocusChangeListener listener, java.util.function.IntConsumer observe) {
            android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            android.media.AudioFocusRequest request = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(listener)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .build();
            int result = manager.requestAudioFocus(request); observe.accept(result);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? request : null;
        }

        private static int abandon(AudioManager manager, Object request) {
            return request instanceof android.media.AudioFocusRequest ? manager.abandonAudioFocusRequest((android.media.AudioFocusRequest) request) : AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
    }


    private void prepareLutPipeline() {
        lutApplySeq++;
        lutAppliedForItem = false;
        lutApplyInProgress = false;
        lutPipelineReadyForItem = false;
        lutPipelinePrepareInProgress = false;
        pendingLutPreview = false;
        dynamicLutEffect.clear();
        clearLutWarmupRecovery();
        if (videoEffectsDirty) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "rebuild clean player before media item reason=prepare spec=%s", debugSpec());
            rebuildPlayer();
        }
        if (shouldPreinstallDynamicLutPipeline()
                && safeSetVideoEffects(dynamicLutEffect.effects(), "prepare_dynamic_passthrough")) {
            lutPipelineReadyForItem = true;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "preinstalled dynamic pipeline before playback spec=%s", debugSpec());
            return;
        }
        clearVideoEffects("prepare");
    }

    private boolean shouldPreinstallDynamicLutPipeline() {
        return LutSetting.isEnabled()
                && engine != null
                && !engine.supportsNativeLut()
                && canWarmLutPipeline()
                && TextUtils.isEmpty(getLutUnavailableReason());
    }

    private boolean shouldPrepareLutBeforePlay() {
        return false;
    }

    public void applyLut(boolean notify) {
        applyLut(notify, false);
    }

    public void applyLutPreview(boolean notify) {
        applyLut(notify, true);
    }

    private void applyLut(boolean notify, boolean preview) {
        if (engine == null) return;
        int seq = ++lutApplySeq;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "request seq=%d notify=%s preview=%s enabled=%s preset=%s state=%s videoFormat=%s tracksEmpty=%s active=%s dirty=%s applied=%s applying=%s pendingPreview=%s", seq, notify, preview, LutSetting.isEnabled(), LutSetting.getPresetId(), stateName(player.getPlaybackState()), engine.getVideoFormat(), engine.getCurrentTracks() == null || engine.getCurrentTracks().isEmpty(), videoEffectsActive, videoEffectsDirty, lutAppliedForItem, lutApplyInProgress, pendingLutPreview);
        if (!lutAllowed) {
            lutAppliedForItem = true;
            lutApplyInProgress = false;
            pendingLutPreview = false;
            lutWarmupReloadPreviewPending = false;
            setNeutralVideoEffects("disallowed");
            completeLutBeforePlay("disallowed");
            return;
        }
        if (!LutSetting.isEnabled()) {
            if (waitingLutBeforePlay && shouldWaitForVideoFormat()) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "wait video format before neutral start reason=off state=%s spec=%s", stateName(player.getPlaybackState()), debugSpec());
                return;
            }
            lutAppliedForItem = true;
            lutApplyInProgress = false;
            pendingLutPreview = false;
            lutWarmupReloadPreviewPending = false;
            setNeutralVideoEffects("off");
            completeLutBeforePlay("off");
            return;
        }
        LutPreset preset = LutStore.find(LutSetting.getPresetId());
        if (preset == null) {
            if (waitingLutBeforePlay && shouldWaitForVideoFormat()) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "wait video format before neutral start reason=missing state=%s spec=%s", stateName(player.getPlaybackState()), debugSpec());
                return;
            }
            lutAppliedForItem = true;
            lutApplyInProgress = false;
            pendingLutPreview = false;
            lutWarmupReloadPreviewPending = false;
            setNeutralVideoEffects("missing");
            completeLutBeforePlay("missing");
            if (notify) Notify.show(R.string.lut_missing);
            return;
        }
        String reason = getLutUnavailableReason();
        if (!TextUtils.isEmpty(reason)) {
            lutAppliedForItem = true;
            lutApplyInProgress = false;
            pendingLutPreview = false;
            lutWarmupReloadPreviewPending = false;
            setNeutralVideoEffects(reason);
            completeLutBeforePlay(reason);
            if (notify) Notify.show(reason);
            return;
        }
        if (shouldWaitForLutFormat()) {
            lutAppliedForItem = false;
            lutApplyInProgress = false;
            if (notify || preview) pendingLutPreview = preview;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "wait video format notify=%s preview=%s state=%s spec=%s", notify, preview, stateName(player.getPlaybackState()), debugSpec());
            return;
        }
        if (notify || preview) pendingLutPreview = preview;
        if (engine.supportsNativeLut()) {
            applyNativeLut(seq, preset, notify, preview);
            return;
        }
        if (!ensureLutPipelineReadyForCurrentItem("request")) {
            return;
        }
        lutAppliedForItem = false;
        lutApplyInProgress = true;
        pendingLutPreview = false;
        int strength = LutSetting.getStrength();
        int previewSeconds = LutSetting.getPreviewSeconds();
        Task.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                ColorLut colorLut = LutEffectFactory.createColorLut(preset, strength);
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "create dynamic preset=%s format=%s strength=%d preview=%s seconds=%d cost=%dms", preset.getId(), preset.getFormat(), strength, preview, previewSeconds, System.currentTimeMillis() - start);
                App.post(() -> applyLutColor(seq, colorLut, notify, preview, previewSeconds));
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "create failed preset=%s strength=%d error=%s", preset.getId(), strength, causeChain(e));
                App.post(() -> {
                    if (seq != lutApplySeq || engine == null) return;
                    lutApplyInProgress = false;
                    setNeutralVideoEffects("error");
                    completeLutBeforePlay("error");
                    if (notify) Notify.show(R.string.lut_apply_failed);
                });
            }
        });
    }

    private void applyNativeLut(int seq, LutPreset preset, boolean notify, boolean preview) {
        lutAppliedForItem = false;
        lutApplyInProgress = true;
        pendingLutPreview = false;
        int strength = LutSetting.getStrength();
        int previewSeconds = LutSetting.getPreviewSeconds();
        Task.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                MpvLutShader shader = MpvLutShaderFactory.create(preset, strength, preview);
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-mpv", "create shader preset=%s format=%s strength=%d preview=%s seconds=%d clock=monotonic cost=%dms", preset.getId(), preset.getFormat(), strength, preview, previewSeconds, System.currentTimeMillis() - start);
                App.post(() -> applyNativeLutShader(seq, shader, notify, preview));
            } catch (Throwable e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-mpv", "create shader failed preset=%s strength=%d error=%s", preset.getId(), strength, causeChain(e));
                App.post(() -> {
                    if (seq != lutApplySeq || engine == null) return;
                    lutApplyInProgress = false;
                    setNeutralVideoEffects("native_error");
                    completeLutBeforePlay("native_error");
                    if (notify) Notify.show(R.string.lut_apply_failed);
                });
            }
        });
    }

    private void applyNativeLutShader(int seq, MpvLutShader shader, boolean notify, boolean preview) {
        if (seq != lutApplySeq || engine == null) return;
        String reason = getLutUnavailableReason();
        if (!TextUtils.isEmpty(reason)) {
            lutApplyInProgress = false;
            setNeutralVideoEffects(reason);
            completeLutBeforePlay(reason);
            if (notify) Notify.show(reason);
            return;
        }
        if (safeSetNativeLut(shader, preview ? "preview_native" : "apply_native")) {
            lutAppliedForItem = true;
            pendingLutPreview = false;
            if (preview) scheduleNativeLutPreviewCommit(seq);
        } else {
            lutAppliedForItem = false;
        }
        lutApplyInProgress = false;
        completeLutBeforePlay(preview ? "preview_native" : "apply_native");
    }

    private void scheduleNativeLutPreviewCommit(int seq) {
        int holdMs = Math.max(1, LutSetting.getPreviewSeconds()) * 1000;
        long slideStartMs = SystemClock.elapsedRealtime() + holdMs;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-mpv", "preview scheduled seq=%d hold=%d slide=%d", seq, holdMs, MpvLutShaderFactory.PREVIEW_SLIDE_MS);
        App.post(() -> updateNativeLutPreview(seq, slideStartMs), holdMs);
    }

    private void updateNativeLutPreview(int seq, long slideStartMs) {
        if (seq != lutApplySeq || engine == null || !engine.supportsNativeLut()) return;
        if (!lutAllowed || !LutSetting.isEnabled()) return;
        long elapsedMs = Math.max(0, SystemClock.elapsedRealtime() - slideStartMs);
        float progress = Math.min(1f, elapsedMs / (float) MpvLutShaderFactory.PREVIEW_SLIDE_MS);
        engine.setNativeLutPreviewProgress(progress);
        if (progress < 1f) {
            App.post(() -> updateNativeLutPreview(seq, slideStartMs), LUT_PREVIEW_FRAME_INTERVAL_MS);
            return;
        }
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-mpv", "preview completed seq=%d elapsed=%d", seq, elapsedMs);
    }

    private void applyLutColor(int seq, ColorLut colorLut, boolean notify, boolean preview, int previewSeconds) {
        if (seq != lutApplySeq || engine == null) return;
        String reason = getLutUnavailableReason();
        if (!TextUtils.isEmpty(reason)) {
            dynamicLutEffect.clear();
            lutApplyInProgress = false;
            setNeutralVideoEffects(reason);
            completeLutBeforePlay(reason);
            if (notify) Notify.show(reason);
            return;
        }
        if (shouldWaitForLutFormat()) {
            lutAppliedForItem = false;
            lutApplyInProgress = false;
            if (notify || preview) pendingLutPreview = preview;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "wait video format before set effects preview=%s spec=%s", preview, debugSpec());
            return;
        }
        dynamicLutEffect.set(colorLut, preview, previewSeconds);
        boolean applied = videoEffectsActive;
        if (applied) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "updated dynamic pipeline in place reason=%s preview=%s", preview ? "preview_dynamic" : "apply_dynamic", preview);
        } else {
            applied = safeSetVideoEffects(dynamicLutEffect.effects(), preview ? "preview_dynamic" : "apply_dynamic");
        }
        if (applied) {
            lutAppliedForItem = true;
            pendingLutPreview = false;
        } else {
            lutAppliedForItem = false;
        }
        lutApplyInProgress = false;
        completeLutBeforePlay(preview ? "preview" : "apply");
    }

    private void applyLutForCurrentItem() {
        if (engine == null) return;
        if (!lutAllowed) {
            if (lutAppliedForItem && !videoEffectsActive && !waitingLutBeforePlay) return;
            lutAppliedForItem = true;
            lutApplyInProgress = false;
            pendingLutPreview = false;
            lutWarmupReloadPreviewPending = false;
            setNeutralVideoEffects("auto_disallowed");
            completeLutBeforePlay("auto_disallowed");
            return;
        }
        if (!LutSetting.isEnabled()) {
            if (lutAppliedForItem && !videoEffectsActive && !waitingLutBeforePlay) return;
            lutAppliedForItem = true;
            lutApplyInProgress = false;
            pendingLutPreview = false;
            lutWarmupReloadPreviewPending = false;
            setNeutralVideoEffects("auto_off");
            completeLutBeforePlay("auto_off");
            return;
        }
        String reason = getLutUnavailableReason();
        if (!TextUtils.isEmpty(reason)) {
            lutAppliedForItem = true;
            lutApplyInProgress = false;
            pendingLutPreview = false;
            lutWarmupReloadPreviewPending = false;
            setNeutralVideoEffects(reason);
            completeLutBeforePlay(reason);
            return;
        }
        if (shouldWaitForLutFormat()) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "wait video format before auto apply state=%s spec=%s", stateName(player.getPlaybackState()), debugSpec());
            return;
        }
        if (lutApplyInProgress) return;
        if (lutAppliedForItem) return;
        if (!ensureLutPipelineReadyForCurrentItem("auto")) return;
        boolean preview = pendingLutPreview || lutWarmupReloadPreviewPending;
        lutWarmupReloadPreviewPending = false;
        applyLut(false, preview);
    }

    private void completeLutBeforePlay(String reason) {
        if (!waitingLutBeforePlay || player == null) return;
        waitingLutBeforePlay = false;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "start playback after lut decision reason=%s active=%s dirty=%s", reason, videoEffectsActive, videoEffectsDirty);
        player.play();
    }

    private boolean ensureLutPipelineReadyForCurrentItem(String reason) {
        if (lutPipelineReadyForItem) return true;
        if (lutPipelinePrepareInProgress) return false;
        if (!canWarmLutPipeline()) return true;
        if (shouldWaitForVideoFormat()) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "wait video format before pipeline warmup reason=%s state=%s spec=%s", reason, stateName(player.getPlaybackState()), debugSpec());
            return false;
        }
        String unavailable = getLutUnavailableReason();
        if (!TextUtils.isEmpty(unavailable)) return true;
        return prepareLutPipelineForCurrentItem(reason);
    }

    private boolean prepareLutPipelineForCurrentItem(String reason) {
        if (spec == null || engine == null || player == null) return true;
        lutPipelinePrepareInProgress = true;
        lutAppliedForItem = false;
        lutApplyInProgress = false;
        dynamicLutEffect.clear();
        long position = Math.max(0, getPosition());
        boolean playWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        if (!safeSetVideoEffects(dynamicLutEffect.effects(), reason + "_prepare_dynamic_passthrough")) {
            lutPipelinePrepareInProgress = false;
            return true;
        }
        lutPipelineReadyForItem = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "prepare current item with effects reason=%s position=%d play=%s spec=%s", reason, position, playWhenReady, debugSpec());
        startLutWarmupRecovery();
        restartEngine(spec, position, playWhenReady);
        if (speed != 1f) setSpeed(speed);
        lutPipelinePrepareInProgress = false;
        return false;
    }

    private void startLutWarmupRecovery() {
        lutWarmupRecoveryActive = true;
        lutWarmupRefreshRequested = false;
        lutWarmupRecoveredErrors = 0;
    }

    private void clearLutWarmupRecovery() {
        lutWarmupRecoveryActive = false;
        lutWarmupRefreshRequested = false;
        lutWarmupRecoveredErrors = 0;
    }

    private boolean retryLutWarmupByRefresh(String reason) {
        if (!lutWarmupRecoveryActive || lutWarmupRefreshRequested || !LutSetting.isEnabled()) return false;
        lutWarmupRefreshRequested = true;
        lutWarmupRecoveryActive = false;
        lutWarmupReloadPreviewPending = true;
        App.removeCallbacks(runnable);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "request playback refresh after warmup failure reason=%s errors=%d spec=%s", reason, lutWarmupRecoveredErrors, debugSpec());
        callback.onReload(RELOAD_LUT_WARMUP);
        return true;
    }

    private boolean retryLutWarmupByRefresh(PlayerEngine.ErrorAction action, PlaybackException e) {
        if (!lutWarmupRecoveryActive || lutWarmupRefreshRequested || !LutSetting.isEnabled()) return false;
        if (action == PlayerEngine.ErrorAction.RECOVERED && ++lutWarmupRecoveredErrors < LUT_WARMUP_RECOVERED_ERROR_REFRESH_THRESHOLD) return false;
        if (action != PlayerEngine.ErrorAction.RECOVERED && action != PlayerEngine.ErrorAction.FATAL && action != PlayerEngine.ErrorAction.RELOAD) return false;
        return retryLutWarmupByRefresh("error_" + e.errorCode + "_" + action);
    }

    private boolean shouldWaitForLutFormat() {
        if (engine == null || !LutSetting.isEnabled()) return false;
        return shouldWaitForVideoFormat();
    }

    private boolean shouldWaitForVideoFormat() {
        if (engine == null) return false;
        Format currentFormat = engine.getVideoFormat();
        if (isUsableVideoFormat(currentFormat)) return false;
        Tracks tracks = engine.getCurrentTracks();
        if (tracks == null || tracks.isEmpty()) return true;
        boolean hasVideo = false;
        boolean hasUsableFormat = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            hasVideo = true;
            for (int i = 0; i < group.length; i++) {
                if (isUsableVideoFormat(group.getTrackFormat(i))) {
                    hasUsableFormat = true;
                    break;
                }
            }
            if (hasUsableFormat) break;
        }
        return hasVideo && !hasUsableFormat;
    }

    private boolean isUsableVideoFormat(Format format) {
        if (format == null) return false;
        return !TextUtils.isEmpty(format.sampleMimeType) || !TextUtils.isEmpty(format.codecs) || format.colorInfo != null || format.width > 0 || format.height > 0;
    }

    private void clearVideoEffects(String reason) {
        dynamicLutEffect.clear();
        if (engine != null && engine.supportsNativeLut()) {
            safeSetNativeLut(null, reason);
            return;
        }
        safeSetVideoEffects(Collections.emptyList(), reason);
    }

    private void setNeutralVideoEffects(String reason) {
        dynamicLutEffect.clear();
        if (engine != null && engine.supportsNativeLut()) {
            safeSetNativeLut(null, reason);
            return;
        }
        if (canKeepWarmNeutralEffects()) safeSetVideoEffects(dynamicLutEffect.effects(), reason + "_dynamic_passthrough");
        else clearVideoEffects(reason);
    }

    private boolean canKeepWarmNeutralEffects() {
        if (!LutSetting.isEnabled()) return false;
        if (!canWarmLutPipeline()) return false;
        if (shouldWaitForVideoFormat()) return false;
        return TextUtils.isEmpty(getLutUnavailableReason());
    }

    private boolean canWarmLutPipeline() {
        if (!lutAllowed) return false;
        if (engine == null || !engine.supportsVideoEffects()) return false;
        if (spec != null && spec.getDrm() != null) return false;
        if (PlayerSetting.isTunnel()) return false;
        if (engine.getDecode() == PlayerEngine.SOFT) return false;
        if (PlayerSetting.isVideoPrefer(playerType)) return false;
        return true;
    }

    private boolean safeSetVideoEffects(List<Effect> effects, String reason) {
        if (engine == null) return false;
        boolean empty = effects == null || effects.isEmpty();
        if (empty && !videoEffectsActive) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "skip clear effects reason=%s", reason);
            return false;
        }
        if (!empty && videoEffectsActive) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "reuse active effects reason=%s", reason);
            return true;
        }
        try {
            engine.setVideoEffects(empty ? Collections.emptyList() : effects);
            if (empty) {
                videoEffectsActive = false;
            } else {
                videoEffectsActive = true;
                videoEffectsDirty = true;
            }
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "set effects=%d reason=%s active=%s dirty=%s", empty ? 0 : effects.size(), reason, videoEffectsActive, videoEffectsDirty);
            return true;
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "set effects failed reason=%s error=%s", reason, causeChain(e));
            return false;
        }
    }

    private boolean safeSetNativeLut(MpvLutShader shader, String reason) {
        if (engine == null || !engine.supportsNativeLut()) return false;
        try {
            engine.setNativeLutShader(shader);
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-mpv", "set shader=%s reason=%s", shader == null ? "none" : shader.diagnostics(), reason);
            return true;
        } catch (Throwable e) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("lut-mpv", "set shader failed reason=%s error=%s", reason, causeChain(e));
            return false;
        }
    }

    private void setDanmakus(List<Danmaku> items) {
        setDanmaku(items == null || items.isEmpty() ? Danmaku.empty() : items.get(0));
    }

    public void setDanmaku(Danmaku item) {
        setDanmaku(item, false);
    }

    public void reloadDanmaku(Danmaku item) {
        setDanmaku(item, true);
    }

    private void setDanmaku(Danmaku item, boolean force) {
        if (item.isEmpty()) {
            if (spec != null) spec.setDanmaku(item);
            clearDanmaku("empty_source");
            return;
        }
        if (danmakuController == null) return;
        String url = DanmakuUrlPolicy.normalize(DanmakuSetting.getValidApiUrl(), item.getRealUrl());
        DanmakuUrlPolicy.SourceType sourceType = DanmakuUrlPolicy.classify(url);
        if (!sourceType.isSupported()) {
            if (spec != null) spec.setDanmaku(item);
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "reject %s", DanmakuUrlPolicy.logSummary(url));
            clearDanmaku("unsupported_source");
            return;
        }
        String key = normalizeDanmakuKey(url);
        if (!force && TextUtils.equals(currentDanmakuUrl, url)) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "skip same %s", DanmakuUrlPolicy.logSummary(url));
            return;
        }
        if (force && shouldSkipForcedDanmakuReload(key)) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "skip duplicate reload key=%s %s", summarizeUrl(key), DanmakuUrlPolicy.logSummary(url));
            return;
        }
        if (spec != null) spec.setDanmaku(item);
        if (force && currentDanmakuUrl != null) danmakuController.clearItems();
        currentDanmakuUrl = url;
        currentDanmakuKey = key;
        if (sourceType.isLive()) {
            danmakuController.clearItems();
            loadingDanmakuKey = null;
            danmakuLoadStartedAtMs = 0;
            danmakuLoadInProgress = false;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "select live source pending websocket connection %s", DanmakuUrlPolicy.logSummary(url));
            if (danmakuForeground && DanmakuSetting.isShow()) connectLiveDanmakuSession(url);
            return;
        }
        stopLiveDanmakuSession("static_source");
        loadingDanmakuKey = key;
        danmakuLoadStartedAtMs = SystemClock.elapsedRealtime();
        danmakuLoadInProgress = true;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "%s name=%s %s key=%s", force ? "reload" : "load", item.getName(), DanmakuUrlPolicy.logSummary(url), summarizeUrl(key));
        danmakuController.setDataSource(Uri.parse(url));
    }

    private boolean shouldSkipForcedDanmakuReload(String key) {
        if (TextUtils.isEmpty(key) || !TextUtils.equals(currentDanmakuKey, key) || danmakuLoadStartedAtMs <= 0) return false;
        if (danmakuLoadInProgress && (TextUtils.isEmpty(loadingDanmakuKey) || TextUtils.equals(loadingDanmakuKey, key))) return true;
        long elapsed = SystemClock.elapsedRealtime() - danmakuLoadStartedAtMs;
        return elapsed >= 0 && elapsed < DANMAKU_FORCE_RELOAD_DEBOUNCE_MS;
    }

    private void finishDanmakuLoad(Uri uri) {
        String key = normalizeDanmakuKey(uri == null ? "" : uri.toString());
        if (!TextUtils.isEmpty(loadingDanmakuKey) && !TextUtils.equals(loadingDanmakuKey, key)) return;
        danmakuLoadInProgress = false;
        loadingDanmakuKey = null;
    }

    private void clearDanmakuState() {
        currentDanmakuUrl = null;
        currentDanmakuKey = null;
        loadingDanmakuKey = null;
        danmakuLoadStartedAtMs = 0;
        danmakuLoadInProgress = false;
    }

    private void clearDanmaku(String reason) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "clear reason=%s current=%s", reason, DanmakuUrlPolicy.logSummary(currentDanmakuUrl));
        stopLiveDanmakuSession(reason);
        liveDanmakuBatcher.clear();
        liveDanmakuBuffer.clear();
        if (danmakuController != null) danmakuController.clearItems();
        clearDanmakuState();
    }

    private void connectLiveDanmakuSession(String url) {
        if (TextUtils.isEmpty(url)) return;
        if (liveDanmakuSession == null) {
            liveDanmakuSession = new LiveDanmakuWebSocketSession(App.get(), new LiveDanmakuWebSocketSession.Listener() {
                @Override
                public void onStateChanged(LiveDanmakuWebSocketSession.State state, long generation, String sourceUrl, int code, String detail) {
                    liveDanmakuGeneration = generation;
                    liveDanmakuMetrics.onState(state, code, SystemClock.elapsedRealtime());
                    if (state == LiveDanmakuWebSocketSession.State.CONNECTING) {
                        liveDanmakuBuffer.reset(generation);
                        liveDanmakuBatcher.reset(generation);
                    } else if (state == LiveDanmakuWebSocketSession.State.RETRY_WAIT || state == LiveDanmakuWebSocketSession.State.STOPPED || state == LiveDanmakuWebSocketSession.State.RELEASED) {
                        liveDanmakuBatcher.clear();
                        liveDanmakuBuffer.clear();
                    }
                    if (state != LiveDanmakuWebSocketSession.State.OPEN) {
                        App.post(() -> logLiveDanmakuMetrics("state_" + state, false));
                        clearLiveDanmakuRenderer(generation);
                    }
                    if (state == LiveDanmakuWebSocketSession.State.CONNECTING || state == LiveDanmakuWebSocketSession.State.OPEN || state == LiveDanmakuWebSocketSession.State.RETRY_WAIT) scheduleLiveDanmakuMetrics();
                    else App.removeCallbacks(liveDanmakuMetricsRunnable);
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku-ws", "state=%s generation=%d code=%d detail=%s %s", state, generation, code, detail, DanmakuUrlPolicy.logSummary(sourceUrl));
                }

                @Override
                public void onMessage(long generation, String text) {
                    if (generation != liveDanmakuGeneration) return;
                    liveDanmakuMetrics.onFrame();
                    long parseStartedNs = SystemClock.elapsedRealtimeNanos();
                    LiveDanmakuParser.Result result = LiveDanmakuParser.parse(text, generation, SystemClock.elapsedRealtime());
                    liveDanmakuMetrics.onParse(result, SystemClock.elapsedRealtimeNanos() - parseStartedNs);
                    if (!result.isAccepted()) return;
                    LiveDanmakuWebSocketSession session = liveDanmakuSession;
                    if (session != null) session.markMessageAccepted(generation);
                    if (result.kind() == LiveDanmakuParser.Kind.ONLINE) {
                        liveDanmakuBuffer.updateOnline(generation, result.online());
                    } else {
                        if (!liveDanmakuPlaybackActive) return;
                        LiveDanmakuMessage message = result.message();
                        LiveDanmakuBuffer.OfferResult offer = liveDanmakuBuffer.offer(message);
                        liveDanmakuMetrics.onOffer(offer);
                        if (offer != LiveDanmakuBuffer.OfferResult.STALE) liveDanmakuBatcher.requestDrain(generation);
                    }
                }
            });
        }
        liveDanmakuGeneration = liveDanmakuSession.connect(url);
    }

    private void stopLiveDanmakuSession(String reason) {
        if (liveDanmakuSession != null) liveDanmakuGeneration = liveDanmakuSession.stop(reason);
    }

    private void releaseLiveDanmakuSession() {
        if (liveDanmakuSession == null) return;
        liveDanmakuSession.release();
        liveDanmakuSession = null;
    }

    private void onLiveDanmakuBatch(long generation, List<LiveDanmakuMessage> messages) {
        long scheduledAtMs = SystemClock.elapsedRealtime();
        App.post(() -> {
            if (generation != liveDanmakuGeneration || messages.isEmpty() || danmakuController == null || !DanmakuSetting.isShow()) return;
            liveDanmakuMetrics.onBatch(messages.size(), SystemClock.elapsedRealtime() - scheduledAtMs);
            List<androidx.media3.ui.danmaku.Danmaku> batch = new ArrayList<>(messages.size());
            for (LiveDanmakuMessage message : messages) {
                int pool = message.type() == LiveDanmakuMessage.Type.SUPER_CHAT ? androidx.media3.ui.danmaku.Danmaku.POOL_SPECIAL : androidx.media3.ui.danmaku.Danmaku.POOL_NORMAL;
                long ttlMs = message.type() == LiveDanmakuMessage.Type.SUPER_CHAT ? LiveDanmakuBuffer.DEFAULT_PRIORITY_TTL_MS : LiveDanmakuBuffer.DEFAULT_NORMAL_TTL_MS;
                batch.add(new androidx.media3.ui.danmaku.Danmaku(message.text(), 0L, androidx.media3.ui.danmaku.Danmaku.TYPE_SCROLL, message.colorArgb(), 0f, pool, "", 0L)
                        .setLiveExpiryElapsedRealtimeMs(message.receivedAtMs() + ttlMs));
            }
            danmakuController.offerLiveBatch(batch);
        });
    }

    private void clearLiveDanmakuRenderer(long generation) {
        App.post(() -> {
            if (generation != liveDanmakuGeneration || danmakuController == null) return;
            danmakuController.clearLiveItems();
        });
    }

    private void discardLiveDanmakuPending() {
        liveDanmakuBuffer.discardPending();
        liveDanmakuBatcher.reset(liveDanmakuGeneration);
        clearLiveDanmakuRenderer(liveDanmakuGeneration);
    }

    private void scheduleLiveDanmakuMetrics() {
        if (!SpiderDebug.isEnabled()) return;
        App.post(liveDanmakuMetricsRunnable, LIVE_DANMAKU_METRICS_INTERVAL_MS);
    }

    private void logLiveDanmakuMetrics(String reason, boolean reschedule) {
        if (!SpiderDebug.isEnabled()) return;
        long nowMs = SystemClock.elapsedRealtime();
        LiveDanmakuMetrics.Snapshot metrics = liveDanmakuMetrics.snapshotAndReset(nowMs);
        LiveDanmakuBuffer.Snapshot buffer = liveDanmakuBuffer.snapshot();
        androidx.media3.ui.danmaku.DanmakuView.LiveStats render = danmakuController == null ? androidx.media3.ui.danmaku.DanmakuView.LiveStats.EMPTY : danmakuController.getLiveStats();
        SpiderDebug.log("danmaku-ws-metrics", "reason=%s state=%s stateMs=%d openMs=%d code=%d received=%d parsed=%d invalid=%d normal=%d super=%d online=%d queued=%d overflow=%d stale=%d batches=%d batchMessages=%d parseAvgUs=%d parseMaxUs=%d mainAvgMs=%d mainMaxMs=%d retries=%d appPending=%d/%d appExpired=%d appHigh=%d renderOffered=%d renderAdmitted=%d renderOverflow=%d renderExpired=%d trackWaits=%d renderPending=%d renderHigh=%d active=%d", reason, metrics.state(), metrics.stateDurationMs(), metrics.openDurationMs(), metrics.lastCode(), metrics.received(), metrics.parsed(), metrics.invalid(), metrics.normal(), metrics.superChat(), metrics.online(), metrics.queued(), metrics.overflow(), metrics.stale(), metrics.batches(), metrics.batchedMessages(), metrics.averageParseNanos() / 1000L, metrics.maxParseNanos() / 1000L, metrics.averageMainDelayMs(), metrics.maxMainDelayMs(), metrics.retryWaits(), buffer.normalPending(), buffer.priorityPending(), buffer.droppedExpired(), buffer.highWaterMark(), render.offered, render.admitted, render.droppedOverflow, render.droppedExpired, render.trackWaits, render.pending, render.highWaterMark, render.active);
        if (reschedule && liveDanmakuSession != null && liveDanmakuSession.state() != LiveDanmakuWebSocketSession.State.STOPPED && liveDanmakuSession.state() != LiveDanmakuWebSocketSession.State.RELEASED) scheduleLiveDanmakuMetrics();
    }

    private void logDanmakuLoad(String event, Uri uri, int count, IOException error) {
        if (!SpiderDebug.isEnabled()) return;
        long elapsed = danmakuLoadStartedAtMs <= 0 ? -1 : SystemClock.elapsedRealtime() - danmakuLoadStartedAtMs;
        if (error == null) {
            SpiderDebug.log("danmaku", "load %s count=%d elapsed=%dms %s", event, count, elapsed, DanmakuUrlPolicy.logSummary(uri == null ? "" : uri.toString()));
        } else {
            SpiderDebug.log("danmaku", "load %s elapsed=%dms %s error=%s", event, elapsed, DanmakuUrlPolicy.logSummary(uri == null ? "" : uri.toString()), error.getMessage());
        }
    }

    private static String normalizeDanmakuKey(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String value = url.trim();
        try {
            Uri uri = Uri.parse(value);
            String nested = getNestedDanmakuUrl(uri);
            return TextUtils.isEmpty(nested) ? value : normalizeDanmakuKey(nested);
        } catch (Throwable e) {
            return value;
        }
    }

    private static String getNestedDanmakuUrl(Uri uri) {
        if (uri == null) return "";
        String path = uri.getPath();
        if (TextUtils.isEmpty(path) || !path.endsWith("/danmaku")) return "";
        return uri.getQueryParameter("url");
    }

    public void addDanmaku(Danmaku item) {
        if (danmakuController == null || item.isEmpty()) return;
        if (spec != null) spec.addDanmaku(item);
        if (currentDanmakuUrl == null) setDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        playbackTrace.mark(PlaybackTrace.Stage.PARSE_COMPLETE, "headers=" + (headers == null ? 0 : headers.size()));
        PlaybackTrace.log("player", playbackTrace.current(), "parseSuccess from=%s url=%s headers=%s", from, summarizeUrl(url), headers == null ? 0 : headers.size());
        recordParseHealth(true, "");
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        setMediaItem(Constant.TIMEOUT_PLAY);
        restoreAfterSwitchReparse();
    }

    @Override
    public void onParseError() {
        recordParseHealth(false, ResUtil.getString(R.string.error_play_parse));
        if (pendingIjkRuntimeFallbackReparse) {
            ijkRuntimeProfileController.onSwitchStartFailed(
                    playbackAutoSession, System.currentTimeMillis());
            publishIjkRuntimeSwitchStartFailure("reparse-failed");
        }
        clearPendingSwitchRestore();
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    private void recordParseHealth(boolean success, String error) {
        if (parseHealthRecorded || spec == null) return;
        parseHealthRecorded = true;
        long cost = parseHealthStartedAt <= 0 ? 0 : System.currentTimeMillis() - parseHealthStartedAt;
        SiteHealthStore.recordParse(spec.getKey(), success, cost, error);
    }

    private String debugSpec() {
        if (spec == null) return "null";
        return "trace=" + playbackTrace.current() +
                ", keyLen=" + safeLength(spec.getKey()) +
                ", url=" + summarizeUrl(spec.getUrl()) +
                ", format=" + spec.getFormat() +
                ", headers=" + (spec.getHeaders() == null ? 0 : spec.getHeaders().size()) +
                ", subs=" + (spec.getSubs() == null ? 0 : spec.getSubs().size()) +
                ", danmakus=" + (spec.getDanmakus() == null ? 0 : spec.getDanmakus().size());
    }

    private void beginPlaybackTrace(String reason) {
        beginPlaybackTrace(reason, true);
    }

    private void beginPlaybackTrace(String reason, boolean finishCurrentSession) {
        if (finishCurrentSession) {
            endPlaybackTelemetrySession("replace-" + reason);
        }
        playbackBufferingTracker.reset();
        clearExoDecoderResourceRecovery(true);
        lastIjkTimelinePublicationKey = null;
        playbackTrace.begin();
        long now = SystemClock.elapsedRealtime();
        playbackAutoSession = playbackAutoContextStore.beginSession(playbackTrace.current(), now);
        rtspLiveLagController.beginSession(playbackAutoSession);
        ijkBufferController.beginSession(playbackAutoSession, now);
        ijkDecodePressureController.beginSession(playbackAutoSession);
        ijkRealtimeRecoveryController.beginSession(playbackAutoSession);
        ijkRuntimeProfileController.beginSession(playbackAutoSession);
        ijkFirstFrameWatchdog.beginSession(playbackAutoSession);
        ijkBufferManagedReload = false;
        pendingIjkBufferDecision = null;
        pendingIjkDecodePressureDecision = null;
        pendingIjkRealtimeRecoveryDecision = null;
        playbackTrackSequence = 1;
        playbackMediaFactsCoordinator.beginSession(playbackAutoSession);
        PlaybackMemoryMonitor.process().beginSession(playbackAutoSession);
        PlaybackSystemConditionMonitor.process().beginSession(playbackAutoSession);
        playbackTelemetryCoordinator.beginSession(playbackAutoSession, now);
        beginPlaybackProfileAbSession(now);
        lastLoggedRouteTraceId = PlaybackTrace.NONE;
        bindPlaybackTrace();
        playbackTrace.mark(PlaybackTrace.Stage.REQUEST, "reason=" + reason + " player=" + playerType + " decode=" + (engine == null ? -1 : engine.getDecode()));
        publishPlaybackTelemetry();
        schedulePlaybackTelemetry();
    }

    private void bindPlaybackTrace() {
        if (spec != null) spec.setPlaybackTraceId(playbackTrace.current());
    }

    private void beginPlaybackProfileAbSession(long nowElapsedMs) {
        PlaybackProfileAbPolicy.EnrollmentResolution enrollment =
                PlaybackProfileAbSetting.getEnrollmentResolution();
        PlaybackProfileAbPolicy.Arm arm = currentPlaybackProfileAbArm();
        playbackProfileAbCoordinator.beginSession(
                playbackAutoSession,
                new PlaybackProfileAbCoordinator.StartConfig(
                        enrollment.active()
                                && playbackProfileAbGateOpen()
                                && arm != null,
                        arm,
                        enrollment.enrollment().deviceDigest(),
                        playbackExperimentCoordinator.generation(),
                        Math.abs(userPlaybackSpeed - 1f) < 0.001f),
                nowElapsedMs);
        PlaybackProfileAbPolicy.EnrollmentResolution lightweightEnrollment =
                PlaybackLightweightAssessmentSetting
                        .getEnrollmentResolution();
        PlaybackProfileAbPolicy.Arm lightweightArm =
                currentPlaybackLightweightAssessmentArm();
        playbackLightweightAssessmentCoordinator.beginSession(
                playbackAutoSession,
                new PlaybackProfileAbCoordinator.StartConfig(
                        lightweightEnrollment.active()
                                && playbackProfileAbGateOpen()
                                && lightweightArm != null,
                        lightweightArm,
                        lightweightEnrollment.enrollment().deviceDigest(),
                        playbackExperimentCoordinator.generation(),
                        Math.abs(userPlaybackSpeed - 1f) < 0.001f),
                nowElapsedMs);
    }

    private void observePlaybackProfileAb(
            PlaybackTelemetry.RuntimeObservation observation,
            long nowElapsedMs) {
        if (!playbackAutoSession.active()) return;
        boolean playbackIntended = false;
        if (player != null) {
            try {
                playbackIntended = player.getPlayWhenReady()
                        && (player.isPlaying()
                        || player.getPlaybackState()
                        == Player.STATE_BUFFERING);
            } catch (Throwable ignored) {
            }
        }
        boolean frameSchedulingExperimentActive = false;
        if (isExo()) {
            try {
                var frameScheduling = PlaybackAnalyticsListener
                        .getFrameSchedulingExperimentSnapshot();
                frameSchedulingExperimentActive = frameScheduling.active()
                        && playbackTrace.current().equals(
                        frameScheduling.traceId());
            } catch (Throwable ignored) {
            }
        }
        playbackProfileAbCoordinator.observe(
                playbackAutoSession,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        PlaybackProfileAbSetting.isEnrolled()
                                && playbackProfileAbGateOpen(),
                        currentPlaybackProfileAbArm(),
                        playbackExperimentCoordinator.generation(),
                        playbackAutoContextStore.snapshot(),
                        observation,
                        playbackIntended,
                        frameSchedulingExperimentActive,
                        false),
                nowElapsedMs);
        playbackLightweightAssessmentCoordinator.observe(
                playbackAutoSession,
                new PlaybackProfileAbCoordinator.RuntimeInput(
                        PlaybackLightweightAssessmentSetting.isEnrolled()
                                && playbackProfileAbGateOpen(),
                        currentPlaybackLightweightAssessmentArm(),
                        playbackExperimentCoordinator.generation(),
                        playbackAutoContextStore.snapshot(),
                        observation,
                        playbackIntended,
                        frameSchedulingExperimentActive,
                        false),
                nowElapsedMs);
    }

    private void finishPlaybackProfileAbSession(
            String reason,
            long nowElapsedMs) {
        playbackProfileAbCoordinator.endSession(
                playbackAutoSession,
                new PlaybackProfileAbCoordinator.EndConfig(
                        PlaybackProfileAbSetting.isEnrolled()
                                && playbackProfileAbGateOpen(),
                        currentPlaybackProfileAbArm(),
                        playbackExperimentCoordinator.generation(),
                        reason),
                nowElapsedMs,
                System.currentTimeMillis());
        playbackLightweightAssessmentCoordinator.endSession(
                playbackAutoSession,
                new PlaybackProfileAbCoordinator.EndConfig(
                        PlaybackLightweightAssessmentSetting.isEnrolled()
                                && playbackProfileAbGateOpen(),
                        currentPlaybackLightweightAssessmentArm(),
                        playbackExperimentCoordinator.generation(),
                        reason),
                nowElapsedMs,
                System.currentTimeMillis());
    }

    private PlaybackProfileAbPolicy.Arm currentPlaybackProfileAbArm() {
        return PlaybackProfileAbPolicy.armForProfile(
                PlaybackPerformanceSetting.getProfile(playerType));
    }

    private PlaybackProfileAbPolicy.Arm
    currentPlaybackLightweightAssessmentArm() {
        return PlaybackLightweightAssessmentPolicy.armForProfile(
                PlaybackPerformanceSetting.getProfile(playerType));
    }

    private void invalidatePlaybackProfileAssessments(
            PlaybackProfileAbCoordinator.InvalidationReason reason) {
        playbackProfileAbCoordinator.invalidate(
                playbackAutoSession, reason);
        playbackLightweightAssessmentCoordinator.invalidate(
                playbackAutoSession, reason);
    }

    private boolean playbackProfileAbGateOpen() {
        return PlaybackProfileAbPolicy.gateAllows(
                PlaybackExperimentSetting.getState(),
                playbackAutoKernel(playerType));
    }

    private PlaybackResourceClassifier.Classification currentResourceClassification() {
        PlaybackResourceClassifier.Classification request = PlaybackResourceClassifier.classifyRequest(
                spec == null ? null : spec.getUrl(),
                spec == null ? null : spec.getFormat(),
                spec == null ? null : spec.getFormat());
        PlaybackResourceClassifier.Classification observed =
                engine == null ? null : engine.getResourceClassification();
        return PlaybackResourceClassifier.merge(request, observed);
    }

    private void publishPlaybackAutoContext(boolean acceptDecoder) {
        if (spec == null || engine == null || !playbackAutoSession.active()) return;
        PlaybackResourceClassifier.Classification classification = currentResourceClassification();
        PlaybackRoute.Resolution observedRoute = engine.getEffectivePlaybackRoute();
        if (observedRoute == null || observedRoute.route() == PlaybackRoute.OTHER) observedRoute = spec.getPlaybackRoute();
        long now = SystemClock.elapsedRealtime();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel = PlaybackAutoContext.Fact.forSession(
                playbackAutoKernel(playerType), PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH, now);
        PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decode = PlaybackAutoContext.Fact.forSession(
                engine.isHard() ? PlaybackAutoContext.DecodeMode.HARDWARE : PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST, PlaybackAutoContext.Confidence.HIGH, now);
        PlaybackAutoContext.PathFacts path = classification.toPathFacts(observedRoute, now);
        PlaybackAutoContext.ResourceFacts resource = classification.toResourceFacts(now);
        if (!playbackAutoContextStore.publishPlaybackFacts(playbackAutoSession, kernel, decode, resource, path, now)) return;
        try {
            playbackMediaFactsCoordinator.publishEngineFacts(
                    playbackAutoSession,
                    playbackTrackSequence,
                    engine.getPlaybackFactsSnapshot(),
                    acceptDecoder,
                    now);
        } catch (Throwable error) {
            PlaybackTrace.log("playback-auto-context", playbackTrace.current(),
                    "media facts unavailable type=%s action=keep-partial", error.getClass().getSimpleName());
        }
        PlaybackAutoContext snapshot = playbackAutoContextStore.snapshot();
        if (playbackAutoSession.equals(snapshot.session())) {
            PlaybackTrace.log("playback-auto-context", playbackTrace.current(), "%s", snapshot.logSummary());
            PlaybackTrace.log("playback-auto-resource", playbackTrace.current(), "%s", classification.logSummary());
        }
    }

    private void publishPlaybackTelemetryTick() {
        if (!playbackAutoSession.active()) return;
        publishPlaybackTelemetry();
        schedulePlaybackTelemetry();
    }

    private void publishPlaybackTelemetry() {
        publishPlaybackTelemetry(null);
    }

    private void publishPlaybackTelemetry(
            PlaybackAutoContext.PlaybackPhase phaseOverride) {
        if (!playbackAutoSession.active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackTelemetry.RuntimeObservation observation =
                collectPlaybackTelemetry(phaseOverride, now);
        playbackTelemetryCoordinator.publishRuntime(
                playbackAutoSession, observation, now);
        observePlaybackProfileAb(observation, now);
        if (evaluateIjkRuntimeProfile(observation, now)) return;
        evaluateExoRtspLiveLag(observation, now);
        if (phaseOverride != PlaybackAutoContext.PlaybackPhase.ERROR) {
            evaluateIjkBuffer(IjkBufferController.Trigger.RUNTIME, now);
            evaluateIjkRealtimeRecovery(now);
            evaluateIjkDecodePressure(now);
        }
    }

    private void schedulePlaybackTelemetry() {
        App.removeCallbacks(playbackTelemetryRunnable);
        if (!playbackAutoSession.active() || player == null || player.getPlaybackState() == Player.STATE_ENDED) return;
        App.post(playbackTelemetryRunnable, PLAYBACK_TELEMETRY_INTERVAL_MS);
    }

    private void endPlaybackTelemetrySession(String reason) {
        App.removeCallbacks(playbackTelemetryRunnable);
        if (!playbackAutoSession.active()) return;
        long now = SystemClock.elapsedRealtime();
        PlaybackTelemetry.RuntimeObservation observation =
                collectPlaybackTelemetry(null, now);
        observePlaybackProfileAb(observation, now);
        finishPlaybackProfileAbSession(reason, now);
        finishIjkRuntimeProfileSession(observation, now);
        playbackTelemetryCoordinator.endSession(
                playbackAutoSession, reason, observation, now);
        rtspLiveLagController.endSession(playbackAutoSession);
    }

    private PlaybackTelemetry.RuntimeObservation collectPlaybackTelemetry(
            PlaybackAutoContext.PlaybackPhase phaseOverride,
            long now) {
        PlaybackAutoContext.PlaybackPhase phaseValue = phaseOverride == null ? playbackPhaseSnapshot() : phaseOverride;
        PlaybackTelemetry.Metric<PlaybackAutoContext.PlaybackPhase> phase = phaseValue == PlaybackAutoContext.PlaybackPhase.UNKNOWN
                ? PlaybackTelemetry.Metric.unknown()
                : PlaybackTelemetry.Metric.of(phaseValue, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        PlaybackTelemetry.Metric<Boolean> loading = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> position = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> duration = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> buffered = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> bandwidth = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> mediaBitrate = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Float> renderedFrameRate = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> droppedFrames = PlaybackTelemetry.Metric.unknown();
        PlaybackTelemetry.Metric<Long> liveLag = PlaybackTelemetry.Metric.unknown();
        if (player != null) {
            try {
                loading = PlaybackTelemetry.Metric.of(player.isLoading(), PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getCurrentPosition();
                if (value >= 0) position = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getDuration();
                if (value >= 0 && value != C.TIME_UNSET) duration = PlaybackTelemetry.Metric.of(value,
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
            try {
                long value = player.getTotalBufferedDuration();
                if (value >= 0) buffered = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable ignored) {
            }
        }
        if (isExo() && playbackTrace.current().equals(PlaybackAnalyticsListener.getPlaybackTraceId())) {
            PlaybackAnalyticsListener.Snapshot analytics = PlaybackAnalyticsListener.getSnapshot();
            if (analytics.bandwidthEstimate() > 0) {
                bandwidth = PlaybackTelemetry.Metric.of(analytics.bandwidthEstimate(), PlaybackAutoContext.ValueSource.ESTIMATOR,
                        PlaybackAutoContext.Confidence.MEDIUM);
            }
            PlaybackAnalyticsListener.DisplayMediaBitrateEstimate media =
                    PlaybackAnalyticsListener.getDisplayMediaBitrateEstimate(getVideoFormat());
            if (media.bitrateBitsPerSecond() > 0) {
                PlaybackAutoContext.ValueSource source = "format".equals(media.source())
                        ? PlaybackAutoContext.ValueSource.PLAYER_CALLBACK : PlaybackAutoContext.ValueSource.ESTIMATOR;
                mediaBitrate = PlaybackTelemetry.Metric.of(media.bitrateBitsPerSecond(), source,
                        telemetryConfidence(media.confidence()));
            }
            PlaybackAnalyticsListener.DisplayFrameRateEstimate frameRate = PlaybackAnalyticsListener.getDisplayFrameRateEstimate();
            if (frameRate.frameRate() > 0 && frameRate.sampleCount() > 0) {
                renderedFrameRate = PlaybackTelemetry.Metric.of(frameRate.frameRate(), PlaybackAutoContext.ValueSource.ESTIMATOR,
                        frameRate.sampleCount() >= 12 ? PlaybackAutoContext.Confidence.HIGH : PlaybackAutoContext.Confidence.MEDIUM);
            }
            if (analytics.everReady() || analytics.videoFormat() != null) {
                droppedFrames = PlaybackTelemetry.Metric.of(Math.max(0, analytics.droppedFrames()),
                        PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH);
            }
            try {
                long value = player.getCurrentLiveOffset();
                if (player.isCurrentMediaItemLive() && value >= 0 && value != C.TIME_UNSET) {
                    liveLag = PlaybackTelemetry.Metric.of(value, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                            PlaybackAutoContext.Confidence.HIGH);
                }
            } catch (Throwable ignored) {
            }
        } else if (engine != null) {
            try {
                PlayerEngine.RuntimeMetrics metrics = engine.getRuntimeMetrics();
                if (metrics.bandwidthBitsPerSecond() != null) bandwidth = PlaybackTelemetry.Metric.of(
                        metrics.bandwidthBitsPerSecond(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.MEDIUM);
                if (metrics.mediaBitrateBitsPerSecond() != null) mediaBitrate = PlaybackTelemetry.Metric.of(
                        metrics.mediaBitrateBitsPerSecond(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.MEDIUM);
                if (metrics.renderedFrameRate() != null) renderedFrameRate = PlaybackTelemetry.Metric.of(
                        metrics.renderedFrameRate(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.MEDIUM);
                if (metrics.droppedFrames() != null) droppedFrames = PlaybackTelemetry.Metric.of(
                        metrics.droppedFrames(), PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.HIGH);
            } catch (Throwable error) {
                PlaybackTrace.log("playback-telemetry", playbackTrace.current(),
                        "native metrics unavailable type=%s action=keep-unknown", error.getClass().getSimpleName());
            }
        }
        if (player != null && isIjk()) {
            try {
                long value = player.getCurrentLiveOffset();
                if (player.isCurrentMediaItemLive()
                        && value >= 0 && value != C.TIME_UNSET) {
                    liveLag = PlaybackTelemetry.Metric.of(
                            value,
                            PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                            PlaybackAutoContext.Confidence.HIGH);
                }
            } catch (Throwable ignored) {
            }
            if (!liveLag.known() && engine instanceof IjkPlayerEngine ijk) {
                try {
                    Long value = ijk.getLiveLagLowerBoundMs();
                    if (value != null && value >= 0) {
                        liveLag = PlaybackTelemetry.Metric.of(
                                value,
                                PlaybackAutoContext.ValueSource.PROXY,
                                PlaybackAutoContext.Confidence.MEDIUM);
                    }
                } catch (Throwable error) {
                    PlaybackTrace.log("ijk-buffer", playbackTrace.current(),
                            "live-lag unavailable errorType=%s action=keep-unknown",
                            error.getClass().getSimpleName());
                }
            }
        }
        long firstFrameMs = playbackTrace.stageElapsedMs(PlaybackTrace.Stage.FIRST_FRAME);
        PlaybackTelemetry.Metric<Long> firstFrame = firstFrameMs < 0 ? PlaybackTelemetry.Metric.unknown()
                : PlaybackTelemetry.Metric.of(firstFrameMs, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH);
        return new PlaybackTelemetry.RuntimeObservation(
                phase,
                loading,
                position,
                duration,
                buffered,
                bandwidth,
                mediaBitrate,
                renderedFrameRate,
                droppedFrames,
                PlaybackTelemetry.Metric.of(getRebufferCount(),
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                PlaybackTelemetry.Metric.of(getRebufferTotalMs(now),
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH),
                firstFrame,
                liveLag);
    }


    private void evaluateExoRtspLiveLag(
            PlaybackTelemetry.RuntimeObservation observation,
            long nowMs) {
        if (!playbackAutoSession.active()) return;
        boolean automatic = PlaybackPerformanceSetting.isAuto(PlayerSetting.EXO)
                && experimentAllowed(
                PlaybackExperimentPolicy.Action.EXO_RTSP_RECOVERY);
        boolean exo = isExo() && engine instanceof ExoPlayerEngine;
        PlaybackResourceClassifier.Classification classification =
                currentResourceClassification();
        boolean rtsp = classification.protocol() == PlaybackAutoContext.Protocol.RTSP;
        boolean classifiedLive = classification.streamKind() == PlaybackAutoContext.StreamKind.LIVE
                || classification.streamKind() == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE;
        boolean mediaItemLive = false;
        boolean active = false;
        boolean loading = false;
        boolean seekAvailable = false;
        if (player != null) {
            try {
                mediaItemLive = player.isCurrentMediaItemLive();
            } catch (Throwable ignored) {
            }
            try {
                int state = player.getPlaybackState();
                active = player.getPlayWhenReady()
                        && (player.isPlaying()
                        || state == Player.STATE_READY
                        || state == Player.STATE_BUFFERING);
            } catch (Throwable ignored) {
            }
            try {
                loading = player.isLoading();
            } catch (Throwable ignored) {
            }
            try {
                seekAvailable = player.isCommandAvailable(
                        Player.COMMAND_SEEK_TO_DEFAULT_POSITION);
            } catch (Throwable ignored) {
            }
        }
        boolean live = classifiedLive && mediaItemLive;
        boolean startupComplete = playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME)
                || playbackTrace.hasStage(PlaybackTrace.Stage.AUDIO_PLAYABLE);
        long liveLagMs = metricLong(observation == null ? null : observation.liveLagMs());
        long bufferedMs = metricLong(observation == null ? null : observation.bufferedDurationMs());
        boolean liveEdgeReliable = isReliableDynamicLiveEdge(
                live, seekAvailable, liveLagMs);
        ExoRtspLiveLagPolicy.Decision decision = rtspLiveLagController.evaluate(
                new ExoRtspLiveLagController.Input(
                        playbackAutoSession,
                        automatic,
                        exo,
                        rtsp,
                        live,
                        active,
                        startupComplete,
                        false,
                        loading,
                        liveEdgeReliable,
                        seekAvailable,
                        liveLagMs,
                        bufferedMs,
                        nowMs));
        ExoRtspLiveLagController.Snapshot stateAtDecision =
                rtspLiveLagController.snapshot();

        // Do not fill the shared decision log with inapplicable HLS/DASH/native observations.
        if (!automatic || !exo || !rtsp) return;
        if (!decision.requestsRecovery()) {
            publishExoRtspLiveLagDecision(
                    decision,
                    PlaybackTelemetry.DecisionOutcome.HELD,
                    false,
                    active,
                    loading,
                    seekAvailable,
                    liveEdgeReliable,
                    stateAtDecision,
                    nowMs);
            return;
        }

        boolean started = rtspLiveLagController.beginAction(
                playbackAutoSession, decision.action(), nowMs);
        boolean succeeded = started && executeExoRtspLiveLagRecovery(decision.action());
        if (started) {
            rtspLiveLagController.completeAction(
                    playbackAutoSession, decision.action(), succeeded);
        }
        publishExoRtspLiveLagDecision(
                decision,
                succeeded ? PlaybackTelemetry.DecisionOutcome.APPLIED
                        : PlaybackTelemetry.DecisionOutcome.FAILED,
                succeeded,
                active,
                loading,
                seekAvailable,
                liveEdgeReliable,
                stateAtDecision,
                nowMs);
    }

    private boolean executeExoRtspLiveLagRecovery(
            ExoRtspLiveLagPolicy.Action action) {
        try {
            return switch (action) {
                case SEEK_LIVE_EDGE -> engine instanceof ExoPlayerEngine exo
                        && exo.recoverRtspLiveEdge();
                case REBUILD_SESSION -> restartExoRtspLiveSession();
                case HOLD -> false;
            };
        } catch (Throwable error) {
            PlaybackTrace.log("exo-rtsp-live", playbackTrace.current(),
                    "action=%s result=failed errorType=%s",
                    action.label(), error.getClass().getSimpleName());
            return false;
        }
    }

    private boolean restartExoRtspLiveSession() {
        if (!(engine instanceof ExoPlayerEngine)
                || player == null
                || spec == null
                || TextUtils.isEmpty(spec.getUrl())) {
            return false;
        }
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        prepareSeq++;
        App.removeCallbacks(runnable);
        App.removeCallbacks(networkProtectionRunnable);
        resetNetworkProtectionSession("rtsp-live-rebuild");
        setDanmakus(spec.getDanmakus());
        initTrack = false;
        waitingLutBeforePlay = false;
        playWhenReady = wasPlayWhenReady;
        applySubtitleStyle();
        PlaybackTrace.log("exo-rtsp-live", playbackTrace.current(),
                "action=rebuild-session play=%s speed=%.3f repeat=%s",
                wasPlayWhenReady, speed, repeat);
        restartEngine(spec, C.TIME_UNSET, wasPlayWhenReady);
        if (speed != 1f) setSpeed(speed);
        setRepeatOne(repeat);
        App.post(runnable, Constant.TIMEOUT_PLAY);
        callback.onPrepare();
        return true;
    }

    private boolean isReliableDynamicLiveEdge(
            boolean live,
            boolean seekAvailable,
            long liveLagMs) {
        if (!live || !seekAvailable || liveLagMs < 0 || player == null) return false;
        try {
            Timeline timeline = player.getCurrentTimeline();
            int index = player.getCurrentMediaItemIndex();
            if (timeline == null || timeline.isEmpty()
                    || index < 0 || index >= timeline.getWindowCount()) {
                return false;
            }
            Timeline.Window window = timeline.getWindow(index, new Timeline.Window());
            return window.isLive() && window.isDynamic;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void publishExoRtspLiveLagDecision(
            ExoRtspLiveLagPolicy.Decision decision,
            PlaybackTelemetry.DecisionOutcome outcome,
            boolean succeeded,
            boolean active,
            boolean loading,
            boolean seekAvailable,
            boolean liveEdgeReliable,
            ExoRtspLiveLagController.Snapshot stateAtDecision,
            long nowMs) {
        ExoRtspLiveLagController.Snapshot snapshot =
                rtspLiveLagController.snapshot();
        ExoRtspLiveLagController.Snapshot decisionState = stateAtDecision == null
                ? snapshot : stateAtDecision;
        List<PlaybackTelemetry.DecisionInput> inputs = new ArrayList<>();
        inputs.add(PlaybackTelemetry.DecisionInput.text(
                "trigger", decision.trigger().label(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.liveLagMs() < 0
                ? PlaybackTelemetry.DecisionInput.unknown("live_lag_ms")
                : PlaybackTelemetry.DecisionInput.number(
                "live_lag_ms", decision.liveLagMs(),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.lagGrowthMsPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown("lag_growth_msps")
                : PlaybackTelemetry.DecisionInput.number(
                "lag_growth_msps", decision.lagGrowthMsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(decision.bufferedMs() < 0
                ? PlaybackTelemetry.DecisionInput.unknown("buffered_ms")
                : PlaybackTelemetry.DecisionInput.number(
                "buffered_ms", decision.bufferedMs(),
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(decision.bufferGrowthMsPerSecond() == Long.MIN_VALUE
                ? PlaybackTelemetry.DecisionInput.unknown("buffer_growth_msps")
                : PlaybackTelemetry.DecisionInput.number(
                "buffer_growth_msps", decision.bufferGrowthMsPerSecond(),
                PlaybackAutoContext.ValueSource.ESTIMATOR,
                PlaybackAutoContext.Confidence.MEDIUM));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "active", active,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "loading", loading,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "edge_reliable", liveEdgeReliable,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.bool(
                "seek_available", seekAvailable,
                PlaybackAutoContext.ValueSource.PLAYER_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "risk_samples", decisionState.consecutiveRiskSamples(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "recoveries", snapshot.recoveryAttempts(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        inputs.add(PlaybackTelemetry.DecisionInput.number(
                "cooldown_ms", decision.cooldownRemainingMs(),
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                PlaybackAutoContext.Confidence.HIGH));
        playbackTelemetryCoordinator.publishDecision(
                playbackAutoSession,
                new PlaybackTelemetry.DecisionEvent(
                        PlaybackTelemetry.DecisionDomain.RTSP_LIVE_RECOVERY,
                        outcome,
                        decisionState.lastAction().label(),
                        decision.action().label(),
                        succeeded ? decision.action().label() : "hold",
                        decision.reason().label(),
                        decision.requestsRecovery() ? succeeded ? "none" : "action-failed"
                                : decision.reason().label(),
                        inputs),
                nowMs);
    }

    private static long metricLong(PlaybackTelemetry.Metric<Long> metric) {
        return metric == null || !metric.known() || metric.value() < 0
                ? -1 : metric.value();
    }

    private PlaybackAutoContext.PlaybackPhase playbackPhaseSnapshot() {
        if (player == null) return PlaybackAutoContext.PlaybackPhase.IDLE;
        return switch (player.getPlaybackState()) {
            case Player.STATE_BUFFERING -> PlaybackAutoContext.PlaybackPhase.BUFFERING;
            case Player.STATE_READY -> PlaybackAutoContext.PlaybackPhase.READY;
            case Player.STATE_ENDED -> PlaybackAutoContext.PlaybackPhase.ENDED;
            case Player.STATE_IDLE -> spec == null ? PlaybackAutoContext.PlaybackPhase.IDLE : PlaybackAutoContext.PlaybackPhase.PREPARING;
            default -> PlaybackAutoContext.PlaybackPhase.UNKNOWN;
        };
    }

    private static PlaybackAutoContext.Confidence telemetryConfidence(String value) {
        if (value == null) return PlaybackAutoContext.Confidence.UNKNOWN;
        return switch (value) {
            case "high" -> PlaybackAutoContext.Confidence.HIGH;
            case "medium" -> PlaybackAutoContext.Confidence.MEDIUM;
            case "low" -> PlaybackAutoContext.Confidence.LOW;
            default -> PlaybackAutoContext.Confidence.UNKNOWN;
        };
    }

    private void clearPlaybackAutoContext() {
        ijkBufferController.endSession(playbackAutoSession);
        ijkDecodePressureController.endSession(playbackAutoSession);
        ijkRealtimeRecoveryController.endSession(playbackAutoSession);
        ijkRuntimeProfileController.endSession(playbackAutoSession);
        ijkFirstFrameWatchdog.endSession(playbackAutoSession);
        ijkBufferManagedReload = false;
        pendingIjkBufferDecision = null;
        pendingIjkDecodePressureDecision = null;
        pendingIjkRealtimeRecoveryDecision = null;
        PlaybackSystemConditionMonitor.process().endSession(playbackAutoSession);
        PlaybackMemoryMonitor.process().endSession(playbackAutoSession);
        playbackMediaFactsCoordinator.endSession(playbackAutoSession);
        playbackAutoContextStore.clear(playbackAutoSession);
        playbackAutoSession = PlaybackAutoContext.SessionToken.none();
        playbackTrackSequence = 0;
    }

    private static PlaybackAutoContext.Kernel playbackAutoKernel(int playerType) {
        return switch (PlayerSetting.sanitizePlayer(playerType)) {
            case PlayerSetting.IJK -> PlaybackAutoContext.Kernel.IJK;
            case PlayerSetting.MPV -> PlaybackAutoContext.Kernel.MPV;
            default -> PlaybackAutoContext.Kernel.EXO;
        };
    }

    private void logPlaybackRoute() {
        if (spec == null) return;
        String traceId = playbackTrace.current();
        if (traceId.equals(lastLoggedRouteTraceId)) return;
        PlaybackRoute.Resolution resolution = spec.getPlaybackRoute();
        PlaybackTrace.log("playback-route", traceId, "%s", resolution.logSummary());
        DiagnosticControls.forTrace(traceId, "input.route", "resolved-playback-route", e -> e
                .observed("inputRoute", resolution.route().name()).observed("source", resolution.owner().name())
                .observed("reason", resolution.evidence().name()).observed("input", resolution.logSummary())
                .observed("metricScope", "resolved local route; external proxy upstream is not observed"));
        lastLoggedRouteTraceId = traceId;
    }

    private static String summarizeUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return "scheme=" + (TextUtils.isEmpty(scheme) ? "unknown" : scheme)
                + " len=" + url.length();
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String stateName(int state) {
        return switch (state) {
            case Player.STATE_IDLE -> "IDLE";
            case Player.STATE_BUFFERING -> "BUFFERING";
            case Player.STATE_READY -> "READY";
            case Player.STATE_ENDED -> "ENDED";
            default -> String.valueOf(state);
        };
    }

    private void markStartupCompletion(boolean ready, Tracks tracks) {
        if (tracks == null) return;
        boolean hasVideo = tracks.containsType(C.TRACK_TYPE_VIDEO);
        boolean hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO);
        PlaybackStartupPolicy.Completion completion = PlaybackStartupPolicy.resolve(ready, false, hasVideo, hasAudio);
        if (completion == PlaybackStartupPolicy.Completion.AUDIO_PLAYABLE) {
            playbackTrace.mark(PlaybackTrace.Stage.AUDIO_PLAYABLE, "source=ready player=" + playerType);
        }
    }


    private void recordBufferingState(int state) {
        if (player == null) return;
        if (isExo()
                && state == Player.STATE_BUFFERING
                && !playbackBufferingTracker.isBuffering()
                && PlaybackAnalyticsListener.isSeekRecoveryActive()) {
            PlaybackTrace.log("playback-buffer", playbackTrace.current(),
                    "event=excluded phase=seek outcome=user-action");
            return;
        }
        if (ijkBufferManagedReload
                && state == Player.STATE_BUFFERING
                && !playbackBufferingTracker.isBuffering()) {
            String domain = pendingIjkDecodePressureDecision != null
                    ? "ijk-decode"
                    : pendingIjkRealtimeRecoveryDecision == null
                    ? "ijk-buffer" : "ijk-realtime";
            PlaybackTrace.log(domain,
                    playbackTrace.current(),
                    "action=managed-reload-buffering result=excluded-from-rebuffer");
            return;
        }
        boolean startupComplete = playbackTrace.hasStage(PlaybackTrace.Stage.FIRST_FRAME) || playbackTrace.hasStage(PlaybackTrace.Stage.AUDIO_PLAYABLE);
        PlaybackBufferingTracker.Event event = playbackBufferingTracker.update(
                state == Player.STATE_BUFFERING,
                startupComplete,
                SystemClock.elapsedRealtime(),
                state,
                currentPositionSnapshot(),
                forwardBufferedSnapshot(),
                loadingSnapshot());
        if (event == null) return;
        PlaybackTrace.log("playback-buffer", playbackTrace.current(),
                "event=%s phase=%s outcome=%s duration=%dms count=%d total=%dms position=%d forward=%d state=%s loading=%s",
                event.type().label(), event.phase().label(), bufferingOutcome(event), event.durationMs(), event.rebufferCount(), event.rebufferTotalMs(),
                event.positionMs(), event.forwardBufferedMs(), stateName(event.playbackState()), event.loading());
    }

    private long currentPositionSnapshot() {
        try {
            return Math.max(0, player.getCurrentPosition());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private long forwardBufferedSnapshot() {
        try {
            return Math.max(0, player.getTotalBufferedDuration());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean loadingSnapshot() {
        try {
            return player.isLoading();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String bufferingOutcome(PlaybackBufferingTracker.Event event) {
        if (event.type() == PlaybackBufferingTracker.Type.START) return "-";
        return switch (event.playbackState()) {
            case Player.STATE_READY -> "ready";
            case Player.STATE_ENDED -> "ended";
            case Player.STATE_IDLE -> "idle";
            default -> "left-buffering";
        };
    }

    private static String trackSummary(Tracks tracks) {
        return "video=" + tracks.containsType(C.TRACK_TYPE_VIDEO) +
                " audio=" + tracks.containsType(C.TRACK_TYPE_AUDIO) +
                " text=" + tracks.containsType(C.TRACK_TYPE_TEXT) +
                " groups=" + tracks.getGroups().size();
    }

    private static String causeChain(Throwable error) {
        if (error == null) return "null";
        StringBuilder builder = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (builder.length() > 0) builder.append(" <- ");
            builder.append(current.getClass().getName());
            current = current.getCause();
        }
        return builder.toString();
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onTitlesChanged();

        default boolean onSourceHttpError(int statusCode, String msg) {
            return false;
        }

        void onError(String msg);

        void onReload(String msg);

        default void onPlayerRenderRequired() {
        }

        default void onPlayerOutputPending() {
        }

        default void onPlayerOutputReady() {
        }

        default void onExoFirstFrame() {
        }

        /**
         * 用户选中了一个外挂字幕。宿主负责写进自己的 History——PlayerManager
         * 不持有 History 引用，也不该持有。
         */
        default void onSubtitleSelected(Sub sub) {
        }

        void onPlayerRebuild(Player newPlayer, boolean resetVideoSurface);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            liveDanmakuPlaybackActive = isPlaying;
            if (!isPlaying) discardLiveDanmakuPending();
            if (isPlaying) scheduleNetworkProtection(0);
            else if (player.getPlaybackState() == Player.STATE_READY && !player.getPlayWhenReady()) {
                invalidatePlaybackProfileAssessments(
                        PlaybackProfileAbCoordinator.InvalidationReason.PAUSED);
                resetNetworkProtectionSession("paused");
            } else if (player.getPlaybackState() != Player.STATE_BUFFERING) {
                App.removeCallbacks(networkProtectionRunnable);
                networkProtectionController.disrupt(networkProtectionSpeed);
                networkProtectionTrend.reset();
                networkProtectionState = networkProtectionController.getState();
                networkProtectionTier = networkProtectionController.getTier();
            } else {
                // A rebuffer is exactly the evidence the guard needs after playback resumes.
                // Keep the current protection speed, controller history, and forward-buffer
                // trend instead of forcing another warm-up window on every stall.
                App.removeCallbacks(networkProtectionRunnable);
                networkProtectionReason = "buffering-hold";
            }
            publishPlaybackTelemetry();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state != Player.STATE_IDLE) App.removeCallbacks(runnable);
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "state=%s spec=%s", stateName(state), debugSpec());
            publishPlaybackAutoContext(state != Player.STATE_IDLE);
            if (state == Player.STATE_READY) {
                cancelBufferingStallWatchdog();
                manualPlayerSwitchPending = false;
                ijkRuntimeProfileController.onPrepared(
                        playbackAutoSession, SystemClock.elapsedRealtime());
                ijkFirstFrameWatchdog.onPrepared(
                        playbackAutoSession, SystemClock.elapsedRealtime());
                if (isIjk()) {
                    completeIjkBufferManagedReload(
                            true, "ready", SystemClock.elapsedRealtime(), true);
                }
                playbackTrace.mark(PlaybackTrace.Stage.READY, "player=" + playerType);
                markStartupCompletion(true, getCurrentTracks());
                hardDecodeSwitchRetryArmed = false;
                clearLutWarmupRecovery();
                applyLutForCurrentItem();
                scheduleNetworkProtection(0);
            } else if (state == Player.STATE_BUFFERING) {
                if (isExo()) armBufferingStallWatchdog();
                App.removeCallbacks(networkProtectionRunnable);
                // Do not reset/disrupt the network guard here. BUFFERING is transient and
                // clearing its trend makes repeated stalls permanently outrun the 10 s
                // confirmation window. READY schedules an immediate evaluation using the
                // retained pre-stall trend plus the newly recorded rebuffer count.
                networkProtectionReason = "buffering-hold";
            } else {
                cancelBufferingStallWatchdog();
                resetNetworkProtectionSession(state == Player.STATE_ENDED ? "ended" : "inactive");
            }
            recordBufferingState(state);
            publishPlaybackTelemetry();
            if (state == Player.STATE_ENDED) {
                App.removeCallbacks(playbackTelemetryRunnable);
                finishPlaybackProfileAbSession(
                        "ended", SystemClock.elapsedRealtime());
            } else {
                schedulePlaybackTelemetry();
            }
        }

        @Override
        public void onTimelineChanged(@NonNull Timeline timeline, int reason) {
            if (isExo()) scheduleNetworkProtection(0);
            if (!(engine instanceof IjkPlayerEngine)) return;
            int index = player == null ? C.INDEX_UNSET : player.getCurrentMediaItemIndex();
            if (timeline.isEmpty() || index < 0 || index >= timeline.getWindowCount()) return;
            Timeline.Window window = timeline.getWindow(index, new Timeline.Window());
            if (window.manifest == null) return;
            IjkTimelinePublicationKey key = new IjkTimelinePublicationKey(
                    window.manifest,
                    window.liveConfiguration,
                    window.isDynamic);
            if (key.equals(lastIjkTimelinePublicationKey)) return;
            lastIjkTimelinePublicationKey = key;
            publishPlaybackAutoContext(false);
            evaluateIjkBuffer(IjkBufferController.Trigger.MANIFEST,
                    SystemClock.elapsedRealtime());
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) {
                mediaSession.reset(Math.max(0L, newPosition.positionMs),
                        PlaybackMediaSignalHub.ResetReason.SOURCE_CHANGED);
            }
            rtspLiveLagController.onPositionDiscontinuity(playbackAutoSession);
            ijkRealtimeRecoveryController.onPositionDiscontinuity(
                    playbackAutoSession);
            ijkDecodePressureController.onPositionDiscontinuity(
                    playbackAutoSession);
            resetNetworkProtectionSession("discontinuity-" + reason);
            scheduleNetworkProtection(ExoNetworkGuardController.OBSERVE_INTERVAL_MS);
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
            publishPlaybackAutoContext(true);
            publishPlaybackTelemetry();
            applyLutForCurrentItem();
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (playbackTrackSequence < Long.MAX_VALUE) playbackTrackSequence++;
            publishPlaybackAutoContext(false);
            if (isExo()) scheduleNetworkProtection(0);
            if (!tracks.isEmpty() && !initTrack) {
                playbackTrace.mark(PlaybackTrace.Stage.TRACKS, trackSummary(tracks));
                restoreTrackSelection(Track.find(getKey()));
                callback.onTracksChanged();
                initTrack = true;
            }
            markStartupCompletion(player != null && player.getPlaybackState() == Player.STATE_READY, tracks);
            publishPlaybackTelemetry();
            applyLutForCurrentItem();
        }

        @Override
        public void onRenderedFirstFrame() {
            // A rendered video frame proves that the media and video decoder are
            // working even if a slow audio track keeps Exo in BUFFERING briefly.
            // Do not let the generic startup timer turn that valid playback into
            // a false connection-timeout error.
            if (isExo()) App.removeCallbacks(runnable);
            playbackTrace.mark(PlaybackTrace.Stage.FIRST_FRAME,
                    "source=media3 player=" + playerType);
            publishPlaybackAutoContext(true);
            onIjkRuntimeFirstFrame(SystemClock.elapsedRealtime());
            ijkFirstFrameWatchdog.onFirstFrame(playbackAutoSession);
            publishPlaybackTelemetry();
            if (isExo()) callback.onExoFirstFrame();
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onTitlesChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            App.removeCallbacks(runnable);
            App.removeCallbacks(networkProtectionRunnable);
            if (handleExoDecoderResourcesReclaimed(e)) return;
            completeIjkBufferManagedReload(
                    false, "playback-error",
                    SystemClock.elapsedRealtime(), true);
            rtspLiveLagController.onPlaybackError(playbackAutoSession);
            ijkRealtimeRecoveryController.onPlaybackError(
                    playbackAutoSession);
            ijkDecodePressureController.onPlaybackError(
                    playbackAutoSession);
            // Publish the failing runtime snapshot without letting the periodic
            // HLS timeout path start a rollback before this concrete error is
            // classified. A downgrade error gets exactly one rollback attempt;
            // an error from that rollback continues through the normal handler.
            publishPlaybackTelemetry(PlaybackAutoContext.PlaybackPhase.ERROR);
            PlaybackErrorClassifier.Failure failure = PlaybackErrorClassifier.classify(e, getEffectivePlaybackRoute());
            PlayerEngine.ErrorAction action = engine.handleError(e);
            int statusCode = httpStatus(e);
            String errorMessage = engine.getErrorMessage(e);
            PlaybackTrace.log("playback-error", playbackTrace.current(), "%s action=%s player=%d decode=%d", failure.logSummary(), action, playerType, engine.getDecode());
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "error code=%d http=%d errorType=%s message=%s action=%s retry=%d spec=%s causeTypes=%s", e.errorCode, statusCode, e.getClass().getSimpleName(), e.getMessage(), action, retry, debugSpec(), causeChain(e));
            LocalProxyDebug.dumpIfLocalFailure(spec == null ? null : spec.getUrl(), e);
            if (statusCode > 0 && callback.onSourceHttpError(statusCode, errorMessage)) return;
            if (retryLutFailure(e)) return;
            if (retryLutWarmupByRefresh(action, e)) return;
            boolean decoderRuntimeObserved = action == PlayerEngine.ErrorAction.DECODE
                    && engine instanceof ExoPlayerEngine exo
                    && exo.observeDecoderRuntimeFailure(e);
            if (action == PlayerEngine.ErrorAction.DECODE && retryExoTunnelingFailure(e)) return;
            if (decoderRuntimeObserved && retryExoDecoderRuntimeFailure(e)) return;
            if (action == PlayerEngine.ErrorAction.DECODE && retryHardDecodeSwitch(e)) return;
            if (action == PlayerEngine.ErrorAction.FATAL && retryLocalProxy(e)) return;
            if (shouldStopOnManualSwitchFailure(manualPlayerSwitchPending, action)) {
                finishPlaybackProfileAbSession(
                        "manual-switch-error", SystemClock.elapsedRealtime());
                callback.onError(errorMessage);
                return;
            }
            if (retryIjkRuntimeProfileFallback(e, failure, action)) return;
            if (action == PlayerEngine.ErrorAction.RELOAD) {
                finishPlaybackProfileAbSession(
                        "player-error", SystemClock.elapsedRealtime());
                callback.onReload(getPlaybackErrorMessage(failure));
                return;
            }
            if (action == PlayerEngine.ErrorAction.RECOVERED) {
                if (spec != null) setDanmakus(spec.getDanmakus());
                return;
            }
            if (fallbackPlayback(e)) return;
            finishPlaybackProfileAbSession(
                    "player-error", SystemClock.elapsedRealtime());
            callback.onError(getPlaybackErrorMessage(failure));
        }
    };

    private void armBufferingStallWatchdog() {
        if (!isExo() || player == null || spec == null) return;
        bufferingStallWatchdog.arm(
                SystemClock.elapsedRealtime(),
                Math.max(0, player.getCurrentPosition()),
                nativeBufferedPosition());
        App.post(bufferingStallRunnable, BUFFERING_STALL_POLL_INTERVAL_MS);
    }

    private void cancelBufferingStallWatchdog() {
        bufferingStallWatchdog.reset();
        App.removeCallbacks(bufferingStallRunnable);
    }

    private long nativeBufferedPosition() {
        return player == null ? 0 : Math.max(0, player.getBufferedPosition());
    }

    private void checkBufferingStall() {
        if (!isExo() || player == null || spec == null) {
            cancelBufferingStallWatchdog();
            return;
        }
        int state = player.getPlaybackState();
        if (state == Player.STATE_READY || state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
            cancelBufferingStallWatchdog();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!player.getPlayWhenReady()) {
            bufferingStallWatchdog.arm(now,
                    Math.max(0, player.getCurrentPosition()), nativeBufferedPosition());
            App.post(bufferingStallRunnable, BUFFERING_STALL_POLL_INTERVAL_MS);
            return;
        }
        long position = Math.max(0, player.getCurrentPosition());
        long buffered = nativeBufferedPosition();
        bufferingStallWatchdog.observe(now, position, buffered);
        if (bufferingStallWatchdog.shouldTimeout(now, position, buffered, player.isLoading())) {
            onBufferingStall(position, buffered);
            return;
        }
        App.post(bufferingStallRunnable, BUFFERING_STALL_POLL_INTERVAL_MS);
    }

    private void onBufferingStall(long positionMs, long bufferedPositionMs) {
        cancelBufferingStallWatchdog();
        PlaybackTrace.log("buffering-stall", playbackTrace.current(),
                "stalled position=%d buffered=%d player=%d", positionMs, bufferedPositionMs, playerType);
        PlaybackException e = new PlaybackException(
                ResUtil.getString(R.string.error_play_timeout), null, PlaybackException.ERROR_CODE_TIMEOUT);
        if (fallbackPlayback(e)) return;
        finishPlaybackProfileAbSession("buffering-stall", SystemClock.elapsedRealtime());
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
    }

    private void onPlaybackTimeout() {
        cancelBufferingStallWatchdog();
        completeIjkBufferManagedReload(
                false, "timeout", SystemClock.elapsedRealtime(), true);
        PlaybackException e = new PlaybackException(ResUtil.getString(R.string.error_play_timeout), null, PlaybackException.ERROR_CODE_TIMEOUT);
        if (retryLutWarmupByRefresh("timeout")) return;
        if (retryExoDv7FirstFrameTimeout()) return;
        if (manualPlayerSwitchPending) {
            finishPlaybackProfileAbSession(
                    "manual-switch-timeout", SystemClock.elapsedRealtime());
            callback.onError(ResUtil.getString(R.string.error_play_timeout));
            return;
        }
        if (fallbackPlayback(e)) return;
        finishPlaybackProfileAbSession(
                "timeout", SystemClock.elapsedRealtime());
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
    }

    private record IjkTimelinePublicationKey(
            Object manifest,
            MediaItem.LiveConfiguration liveConfiguration,
            boolean dynamic) {
    }

    private record ExoDecoderResourceRecovery(
            PlaySpec target,
            long positionMs,
            float speed,
            boolean repeat,
            boolean playWhenReady,
            long textOffsetMs,
            long audioOffsetMs) {
    }

    private PlaybackRoute.Resolution getEffectivePlaybackRoute() {
        PlaybackRoute.Resolution route = engine == null ? null : engine.getEffectivePlaybackRoute();
        if (route != null && route.route() != PlaybackRoute.OTHER) return route;
        return spec == null ? PlaybackRoute.resolve(null) : spec.getPlaybackRoute();
    }

    private String getPlaybackErrorMessage(PlaybackErrorClassifier.Failure failure) {
        return switch (failure.stage()) {
            case LOCAL_ENDPOINT -> switch (failure.route().owner()) {
                case APP_MAIN_SERVER, APP_HLS_PROXY -> ResUtil.getString(R.string.error_play_stage_app_local);
                default -> ResUtil.getString(R.string.error_play_stage_external_local);
            };
            case NETWORK_IO -> PlaybackRouteCapabilities.resolve(failure.route()).externalUpstreamOpaque()
                    ? ResUtil.getString(R.string.error_play_stage_external_supply)
                    : ResUtil.getString(R.string.error_play_stage_network);
            case MEDIA_PARSING -> ResUtil.getString(R.string.error_play_stage_media);
            case DECODER -> ResUtil.getString(R.string.error_play_stage_decoder);
            case OUTPUT -> isAudioOutputFailure(failure)
                    ? ResUtil.getString(R.string.error_play_stage_audio_output)
                    : isVideoOutputFailure(failure)
                    ? ResUtil.getString(R.string.error_play_stage_video_output)
                    : ResUtil.getString(R.string.error_play_stage_output);
            case DRM -> ResUtil.getString(R.string.error_play_stage_drm);
            case UNKNOWN -> ResUtil.getString(R.string.error_play_stage_unknown);
        };
    }

    private boolean isAudioOutputFailure(PlaybackErrorClassifier.Failure failure) {
        return hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED);
    }

    private boolean isVideoOutputFailure(PlaybackErrorClassifier.Failure failure) {
        return hasErrorCode(failure, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED)
                || hasErrorCode(failure, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
    }

    private boolean hasErrorCode(
            PlaybackErrorClassifier.Failure failure, int errorCode) {
        return PlaybackException.getErrorCodeName(errorCode).equals(failure.errorCode());
    }

    private boolean handleExoDecoderResourcesReclaimed(
            PlaybackException error) {
        if (error.errorCode
                != PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            return false;
        }
        if (pendingExoDecoderResourceRecovery != null
                || exoDecoderResourceRecoveryInProgress) {
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=suppress-duplicate pending=%s inProgress=%s",
                    pendingExoDecoderResourceRecovery != null,
                    exoDecoderResourceRecoveryInProgress);
            return true;
        }
        ExoDecoderResourceRecovery recovery =
                captureExoDecoderResourceRecovery();
        ExoDecoderResourceRecoveryLimiter.Action action =
                exoDecoderResourceRecoveryLimiter.request(
                        error.errorCode,
                        engine instanceof ExoPlayerEngine,
                        engine != null && player != null,
                        recovery != null,
                        playbackForeground);
        if (action
                == ExoDecoderResourceRecoveryLimiter.Action.DEFER_UNTIL_FOREGROUND) {
            hardDecodeSwitchRetryArmed = false;
            pendingExoDecoderResourceRecovery = recovery;
            App.removeCallbacks(playbackTelemetryRunnable);
            resetNetworkProtectionSession("exo-resource-reclaimed-deferred");
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=defer position=%d play=%s",
                    recovery.positionMs(),
                    recovery.playWhenReady());
            return true;
        }
        if (action
                != ExoDecoderResourceRecoveryLimiter.Action.RECOVER_NOW) {
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=pass-through reason=%s",
                    action);
            return false;
        }
        hardDecodeSwitchRetryArmed = false;
        scheduleExoDecoderResourceRecovery(recovery, "foreground-error");
        return true;
    }

    @Nullable
    private ExoDecoderResourceRecovery captureExoDecoderResourceRecovery() {
        if (!(engine instanceof ExoPlayerEngine)
                || player == null
                || spec == null
                || spec.getUrl() == null) {
            return null;
        }
        return new ExoDecoderResourceRecovery(
                spec,
                Math.max(0, getPosition()),
                getSpeed(),
                isRepeatOne(),
                player.getPlayWhenReady(),
                getTextOffsetMs(),
                getAudioOffsetMs());
    }

    private void scheduleExoDecoderResourceRecovery(
            ExoDecoderResourceRecovery recovery,
            String reason) {
        if (recovery == null) return;
        if (!playbackForeground) {
            pendingExoDecoderResourceRecovery = recovery;
            PlaybackTrace.log(
                    "exo-decoder-resource",
                    playbackTrace.current(),
                    "action=defer-before-rebuild reason=%s",
                    reason);
            return;
        }
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec != recovery.target()) {
            return;
        }
        exoDecoderResourceRecoveryInProgress = true;
        int seq = ++prepareSeq;
        App.removeCallbacks(runnable);
        App.removeCallbacks(networkProtectionRunnable);
        App.removeCallbacks(playbackTelemetryRunnable);
        resetNetworkProtectionSession("exo-resource-reclaimed");
        rebuildPlayer(true);
        this.playWhenReady = recovery.playWhenReady();
        initTrack = false;
        PlaybackTrace.log(
                "exo-decoder-resource",
                playbackTrace.current(),
                "action=rebuild delay=%d position=%d play=%s reason=%s",
                EXO_DECODER_RESOURCE_RECOVERY_DELAY_MS,
                recovery.positionMs(),
                recovery.playWhenReady(),
                reason);
        App.post(() -> {
            if (seq != prepareSeq
                    || spec != recovery.target()
                    || engine != exo
                    || player == null) {
                exoDecoderResourceRecoveryInProgress = false;
                return;
            }
            if (!playbackForeground) {
                pendingExoDecoderResourceRecovery = recovery;
                exoDecoderResourceRecoveryInProgress = false;
                PlaybackTrace.log(
                        "exo-decoder-resource",
                        playbackTrace.current(),
                        "action=defer-before-prepare");
                return;
            }
            try {
                setDanmakus(recovery.target().getDanmakus());
                waitingLutBeforePlay = false;
                applySubtitleStyle();
                startEngine(
                        recovery.target(),
                        recovery.positionMs(),
                        recovery.playWhenReady());
                setSpeed(recovery.speed());
                setRepeatOne(recovery.repeat());
                setTextOffsetMs(recovery.textOffsetMs());
                setAudioOffsetMs(recovery.audioOffsetMs());
                App.post(runnable, Constant.TIMEOUT_PLAY);
                callback.onPrepare();
                PlaybackTrace.log(
                        "exo-decoder-resource",
                        playbackTrace.current(),
                        "action=prepare position=%d play=%s",
                        recovery.positionMs(),
                        recovery.playWhenReady());
            } finally {
                exoDecoderResourceRecoveryInProgress = false;
            }
        }, EXO_DECODER_RESOURCE_RECOVERY_DELAY_MS);
    }

    private void clearExoDecoderResourceRecovery(boolean resetBudget) {
        pendingExoDecoderResourceRecovery = null;
        exoDecoderResourceRecoveryInProgress = false;
        if (resetBudget) exoDecoderResourceRecoveryLimiter.reset();
    }

    private boolean retryHardDecodeSwitch(PlaybackException e) {
        if (!hardDecodeSwitchRetryArmed || engine == null || player == null || spec == null || !engine.isHard()) return false;
        if (e.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false;
        hardDecodeSwitchRetryArmed = false;
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, getPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        resetLutRuntimeState("hard_decode_switch_retry", true);
        engine.release();
        engine = buildEngine(playerType, PlayerEngine.HARD);
        player = engine.getPlayer();
        restoreIjkStagedBufferConfig();
        callback.onPlayerRebuild(player, true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "hard decode switch retry delay=%d position=%d errorType=%s", HARD_DECODE_SWITCH_RETRY_DELAY_MS, position, e.getClass().getSimpleName());
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine == null || player == null || !engine.isHard()) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "hard decode switch retry start position=%d", position);
            setDanmakus(target.getDanmakus());
            initTrack = false;
            waitingLutBeforePlay = false;
            applySubtitleStyle();
            startEngine(target, position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
        }, HARD_DECODE_SWITCH_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoTunnelingFailure(PlaybackException e) {
        if (!(engine instanceof ExoPlayerEngine exo) || player == null || spec == null) return false;
        if (e.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED && e.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false;
        if (!exo.disableTunnelingForSession()) return false;
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, getPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        rebuildPlayer(true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-tunnel", "fallback scheduled delay=%d position=%d errorType=%s", EXO_TUNNELING_RETRY_DELAY_MS, position, e.getClass().getSimpleName());
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine != exo || player == null) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("exo-tunnel", "fallback start position=%d", position);
            setDanmakus(target.getDanmakus());
            waitingLutBeforePlay = false;
            applySubtitleStyle();
            startEngine(target, position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
        }, EXO_TUNNELING_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoDecoderRuntimeFailure(PlaybackException e) {
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec == null) {
            return false;
        }
        boolean dolbyVisionFallback =
                exo.isDolbyVisionP81RuntimeFailurePending();
        if (!dolbyVisionFallback
                && !experimentAllowed(
                PlaybackExperimentPolicy.Action.EXO_DECODER_RUNTIME_REBUILD)) {
            return false;
        }
        if (!exo.prepareDecoderRuntimeFallback()) {
            return false;
        }
        hardDecodeSwitchRetryArmed = false;
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, getPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        rebuildPlayer(true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log(
                    "exo-decoder-profile",
                    "action=retry-scheduled delay=%d position=%d errorType=%s",
                    EXO_DECODER_RUNTIME_RETRY_DELAY_MS,
                    position,
                    e.getClass().getSimpleName());
        }
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine != exo || player == null) return;
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(
                        "exo-decoder-profile",
                        "action=retry-start position=%d",
                        position);
            }
            setDanmakus(target.getDanmakus());
            waitingLutBeforePlay = false;
            applySubtitleStyle();
            startEngine(target, position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
        }, EXO_DECODER_RUNTIME_RETRY_DELAY_MS);
        return true;
    }

    private boolean retryExoDv7FirstFrameTimeout() {
        if (!(engine instanceof ExoPlayerEngine exo)
                || player == null
                || spec == null
                || !exo.prepareDv7Hdr10FallbackForFirstFrameTimeout()) {
            return false;
        }
        int seq = ++prepareSeq;
        PlaySpec target = spec;
        long position = Math.max(0, player.getCurrentPosition());
        float speed = getSpeed();
        boolean repeat = isRepeatOne();
        boolean wasPlayWhenReady = player.getPlayWhenReady();
        App.removeCallbacks(runnable);
        rebuildPlayer(true);
        this.playWhenReady = wasPlayWhenReady;
        initTrack = false;
        if (SpiderDebug.isEnabled()) {
            SpiderDebug.log(
                    "exo-dv",
                    "action=first-frame-timeout-fallback-scheduled delay=%d position=%d",
                    EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS,
                    position);
        }
        App.post(() -> {
            if (seq != prepareSeq || spec != target || engine != exo || player == null) return;
            setDanmakus(target.getDanmakus());
            waitingLutBeforePlay = false;
            applySubtitleStyle();
            startEngine(target, position, wasPlayWhenReady);
            if (speed != 1f) setSpeed(speed);
            setRepeatOne(repeat);
            App.post(runnable, Constant.TIMEOUT_PLAY);
            callback.onPrepare();
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(
                        "exo-dv",
                        "action=first-frame-timeout-fallback-start position=%d",
                        position);
            }
        }, EXO_DV7_FIRST_FRAME_FALLBACK_DELAY_MS);
        return true;
    }

    private boolean retryLutFailure(PlaybackException e) {
        if (!LutSetting.isEnabled()) return false;
        if (e.errorCode != PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED && e.errorCode != PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED) return false;
        App.removeCallbacks(runnable);
        LutSetting.select(null);
        lutAppliedForItem = true;
        lutApplyInProgress = false;
        pendingLutPreview = false;
        lutWarmupReloadPreviewPending = false;
        clearVideoEffects("lut_error_retry");
        Notify.show(R.string.lut_apply_failed);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "disable and retry after frame processing error code=%d spec=%s cause=%s", e.errorCode, debugSpec(), causeChain(e));
        if (spec != null) setMediaItem();
        return true;
    }

    private boolean retryLocalProxy(PlaybackException e) {
        if (spec == null || !LocalProxyDebug.isLocalProxyUrl(spec.getUrl())) return false;
        if (!LocalProxyDebug.isConnectionRefused(e)) return false;
        if (++localProxyRetry > LOCAL_PROXY_MAX_RETRY) return false;
        int attempt = localProxyRetry;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy retry schedule attempt=%d delay=%d spec=%s", attempt, LOCAL_PROXY_RETRY_DELAY_MS, debugSpec());
        App.removeCallbacks(runnable);
        App.post(() -> {
            if (spec == null || attempt != localProxyRetry) return;
            if (SpiderDebug.isEnabled()) SpiderDebug.log("player", "local proxy retry start attempt=%d spec=%s", attempt, debugSpec());
            setMediaItem();
        }, LOCAL_PROXY_RETRY_DELAY_MS);
        return true;
    }

    private boolean fallbackPlayback(PlaybackException e) {
        if (engine == null) return false;
        return switch (nextFallbackAction(PlayerSetting.getFailureFallback(), engine.getDecode())) {
            case FALLBACK_DECODE -> fallbackDecode(e);
            case FALLBACK_PLAYER -> fallbackPlayer(e);
            default -> false;
        };
    }

    private boolean fallbackDecode(PlaybackException e) {
        if (spec == null || spec.getUrl() == null || engine == null || !engine.isHard()) return false;
        SpiderDebug.log("player", "fallback decode player=%s from=hard to=soft spec=%s cause=%s", getPlayerText(playerType), debugSpec(), causeChain(e));
        App.removeCallbacks(runnable);
        localProxyRetry = 0;
        hardDecodeSwitchRetryArmed = false;
        switchEngine(playerType, false, true, true, PlayerEngine.SOFT);
        return true;
    }

    private boolean fallbackPlayer(PlaybackException e) {
        if (spec == null || spec.getUrl() == null || engine == null) return false;
        int next = nextFallbackPlayer();
        if (next == PlayerSetting.NONE) return false;
        String from = getPlayerText(playerType);
        String to = getPlayerText(next);
        SpiderDebug.log("player", "fallback player from=%s to=%s spec=%s cause=%s", from, to, debugSpec(), causeChain(e));
        App.removeCallbacks(runnable);
        localProxyRetry = 0;
        switchEngine(next, false, true, true, fallbackDecode(PlayerSetting.getFailureFallback(), playerType, next, engine.getDecode()));
        return true;
    }

    private boolean ensurePlayerAvailableForPlayback() {
        if (PlayerSetting.isPlayerAvailable(playerType)) return true;
        int from = playerType;
        int next = nextFallbackPlayer();
        if (next == PlayerSetting.NONE) {
            callback.onError(ResUtil.getString(R.string.error_play_ijk_unavailable));
            return false;
        }
        logUnavailablePlayer(from, next);
        switchEngine(next, false, false, true);
        return false;
    }

    /**
     * 按内核优先级顺序（EXO → IJK → MPV → 系统）挑下一个没试过的内核。
     * 当前内核先标记成已试，所以回退天然跳过自身：MPV 失败就走 EXO → IJK → 系统。
     */
    private int nextFallbackPlayer() {
        markPlayerFallbackTried(playerType);
        int next = PlayerSetting.firstUntriedPlayer(playerFallbackTried);
        while (next != PlayerSetting.NONE) {
            markPlayerFallbackTried(next);
            if (PlayerSetting.isPlayerAvailable(next)) return next;
            next = PlayerSetting.firstUntriedPlayer(playerFallbackTried);
        }
        return PlayerSetting.NONE;
    }

    static int nextFallbackAction(int mode, int decode) {
        int currentDecode = sanitizeDecode(decode);
        return switch (mode) {
            case PlayerSetting.FALLBACK_DECODE_ONLY -> currentDecode == PlayerEngine.HARD ? FALLBACK_DECODE : FALLBACK_NONE;
            case PlayerSetting.FALLBACK_PLAYER_ONLY -> FALLBACK_PLAYER;
            case PlayerSetting.FALLBACK_DISABLED -> FALLBACK_NONE;
            default -> currentDecode == PlayerEngine.HARD ? FALLBACK_DECODE : FALLBACK_PLAYER;
        };
    }

    static int fallbackDecode(int mode, int from, int to, int decode) {
        int currentDecode = sanitizeDecode(decode);
        if (from == to || mode == PlayerSetting.FALLBACK_PLAYER_ONLY) return currentDecode;
        return PlayerEngine.HARD;
    }

    private static int sanitizeDecode(int decode) {
        return decode == PlayerEngine.SOFT ? PlayerEngine.SOFT : PlayerEngine.HARD;
    }

    private int resolveAvailablePlayer(int type) {
        if (PlayerSetting.isPlayerAvailable(type)) return type;
        int next = PlayerSetting.nextPlayer(type);
        while (next != type) {
            if (PlayerSetting.isPlayerAvailable(next)) {
                logUnavailablePlayer(type, next);
                return next;
            }
            next = PlayerSetting.nextPlayer(next);
        }
        return playerType;
    }

    private int resolveManualPlayer(int type) {
        if (PlayerSetting.isPlayerAvailable(type)) return type;
        SpiderDebug.log("player", "manual player unavailable type=%s package=%s", getPlayerText(type), App.get().getPackageName());
        Notify.show(ResUtil.getString(R.string.error_play_ijk_unavailable));
        return playerType;
    }

    private void logUnavailablePlayer(int from, int to) {
        SpiderDebug.log("player", "player unavailable from=%s to=%s package=%s", getPlayerText(from), getPlayerText(to), App.get().getPackageName());
    }

    private void resetPlayerFallback() {
        playerFallbackTried = new boolean[PLAYER_COUNT];
    }

    private void markPlayerFallbackTried(int type) {
        if (type >= 0 && type < playerFallbackTried.length) playerFallbackTried[type] = true;
    }

    static boolean shouldStopOnManualSwitchFailure(boolean manualSwitchPending, PlayerEngine.ErrorAction action) {
        return manualSwitchPending && action != PlayerEngine.ErrorAction.RECOVERED;
    }

    static boolean isCurrentDirectSwitchRefresh(boolean pending, int requestSeq, int currentSeq, int requestPlayerType, int currentPlayerType, PlaySpec requestSpec, PlaySpec currentSpec) {
        return pending && requestSeq == currentSeq && requestPlayerType == currentPlayerType && requestSpec == currentSpec;
    }

    static int httpStatus(Throwable error) {
        int depth = 0;
        for (Throwable cause = error; cause != null && depth++ < 8; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException response) return response.responseCode;
            String message = cause.getMessage();
            if (TextUtils.isEmpty(message)) continue;
            Matcher matcher = HTTP_STATUS.matcher(message);
            if (!matcher.find()) continue;
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }
}
