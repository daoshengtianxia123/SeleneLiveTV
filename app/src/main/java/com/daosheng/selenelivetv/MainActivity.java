package com.daosheng.selenelivetv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import dev.jdtech.mpv.MPVLib;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    public static final String DEFAULT_SUB_URL =
            "https://gitee.com/daoshengtianxia/selene-iptv/raw/main/selene-sub.txt";
    private static final String OLD_DEFAULT_SUB_URL =
            "https://raw.githubusercontent.com/daoshengtianxia123/selene-iptv/main/selene-sub.txt";
    public static final String PREFS = "selene_live_prefs";
    public static final String KEY_SUB_URL = "subscription_url";
    private static final String KEY_CACHE = "playlist_cache";
    private static final String KEY_SUB_CACHE = "subscription_cache";
    private static final String KEY_CACHE_TIME = "playlist_cache_time";
    private static final String CACHE_FILE_NAME = "playlist_cache.m3u";
    private static final String SUB_CACHE_FILE_NAME = "selene-sub-cache.txt";
    private static final String KEY_LAST = "last_channel";
    private static final long CACHE_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final int REQ_SETTINGS = 1001;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Channel> channels = new ArrayList<>();
    private SurfaceView playerView;
    private MPVLib player;
    private volatile boolean mpvInitialized = false;
    private volatile boolean surfaceReady = false;
    private volatile boolean destroyed = false;
    private final AtomicInteger playToken = new AtomicInteger(0);
    private PlaybackController playbackController;
    private boolean pendingPlay = false;
    private int playbackRetryCount = 0;
    // 阶段1/2属于直播源/网络建立阶段，单独允许一次“干净重连”，不要误切软件解码。
    private int streamReconnectCount = 0;
    private int playbackGeneration = 0;
    private ListView list;
    private TextView overlay;
    private TextView status;

    // 直播调试状态层：用于定位网络、解复用、解码、缓存或画面输出卡点。
    private TextView debugView;
    private volatile String debugStage = "等待播放器";
    private volatile boolean debugFileLoaded = false;
    private volatile boolean debugVideoReady = false;
    private volatile boolean debugAudioReady = false;
    private volatile boolean debugPausedForCache = false;
    private volatile boolean debugCoreIdle = true;
    private volatile boolean debugEof = false;
    private volatile double debugBufferPercent = -1;
    private volatile double debugCacheSeconds = -1;
    private volatile double debugTimePos = -1;
    private volatile double debugLastTimePos = -1;
    private volatile long debugLastProgressAt = 0L;
    private volatile long debugHealthySince = 0L;
    private volatile String debugVideoCodec = "-";
    private volatile String debugVideoFormat = "-";
    private volatile String debugAudioCodec = "-";
    private volatile long debugWidth = 0;
    private volatile long debugHeight = 0;
    private volatile String debugLastEvent = "-";

    // 自动恢复状态：
    // 本地缓存先播；异常时只重连当前频道/重建播放器/刷新订阅。
    // 不再自动切软件解码，避免恢复动作反而把播放器卡住。
    private volatile boolean playbackHealthy = false;
    private volatile boolean softwareFallbackUsed = false;
    private volatile boolean forceSoftwareDecode = false;
    private volatile boolean playbackRefreshInProgress = false;
    private volatile boolean playbackRefreshTried = false;
    private volatile boolean playerCoreRestartTried = false;
    private volatile boolean playerCoreRestarting = false;
    private volatile long lastPlaybackTriggeredRefreshAt = 0L;
    private volatile long channelAttemptStartedAt = 0L;
    private volatile long lastManualSwitchAt = 0L;
    private volatile String playlistSource = "未加载";

    // 网络更新先作为“候选列表”试播，只有真正稳定播放后才覆盖已验证本地缓存。
    private volatile boolean pendingCachePromotion = false;
    private volatile String pendingSubscriptionText = null;
    private volatile String pendingPlaylistText = null;

    private int current = 0;
    private boolean listVisible = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();

        playbackController = new PlaybackController(new PlaybackController.Listener() {
            @Override public void onControllerStage(String stage) {
                ui.post(() -> {
                    if (destroyed) return;
                    debugStage = stage;
                    updateDebugPanel();
                });
            }

            @Override public void onControllerFailure(String message) {
                ui.post(() -> {
                    if (destroyed) return;
                    debugStage = "播放控制命令失败";
                    statusTextSafe("播放器控制失败：" + message);
                    updateDebugPanel();
                });
            }

            @Override public void onControllerBlocked(long blockedMs, int generation) {
                ui.post(() -> {
                    if (destroyed || playbackController == null) return;
                    if (!playbackController.isCurrent(generation)) return;
                    if (playerCoreRestarting) return;

                    debugStage = "播放控制线程疑似卡住";
                    statusTextSafe("播放器响应超时，正在重建当前播放会话…");
                    updateDebugPanel();
                    restartPlayerCoreForCurrentChannel("播放控制命令卡住 " + (blockedMs / 1000) + " 秒");
                });
            }
        });

        loadInitial();
    }

    public static String getSubscriptionUrl(Context c) {
        String value = c.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SUB_URL, DEFAULT_SUB_URL);
        if (value == null || value.trim().isEmpty() || OLD_DEFAULT_SUB_URL.equals(value.trim())) {
            return DEFAULT_SUB_URL;
        }
        return value.trim();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new SurfaceView(this);
        playerView.setFocusable(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playerView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                surfaceReady = holder.getSurface() != null && holder.getSurface().isValid();
                if (!surfaceReady || destroyed) return;
                initMpvIfNeeded(holder);
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceReady = holder.getSurface() != null && holder.getSurface().isValid();
                if (!surfaceReady || destroyed) return;
                if (!mpvInitialized) {
                    initMpvIfNeeded(holder);
                } else if (player != null) {
                    try { player.attachSurface(holder.getSurface()); } catch (Throwable e) {
                        statusTextSafe("MPV Surface 绑定失败：" + e.getClass().getSimpleName());
                    }
                }
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                surfaceReady = false;
                if (player != null) {
                    try { player.detachSurface(); } catch (Throwable ignored) {}
                }
            }
        });

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(22);
        status.setPadding(24, 14, 24, 14);
        status.setBackgroundColor(0x99000000);
        status.setText("正在加载直播订阅…");
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(status, statusLp);

        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(22);
        overlay.setPadding(22, 14, 22, 14);
        overlay.setBackgroundColor(0x99000000);
        overlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        overlayLp.leftMargin = 30;
        overlayLp.topMargin = 30;
        root.addView(overlay, overlayLp);

        debugView = new TextView(this);
        debugView.setTextColor(0xFFFFFFFF);
        debugView.setTextSize(14);
        debugView.setPadding(16, 12, 16, 12);
        debugView.setBackgroundColor(0xB0000000);
        debugView.setText("调试：等待播放器");
        FrameLayout.LayoutParams debugLp = new FrameLayout.LayoutParams(dp(430), -2, Gravity.TOP | Gravity.RIGHT);
        debugLp.rightMargin = dp(18);
        debugLp.topMargin = dp(18);
        root.addView(debugView, debugLp);

        list = new ListView(this);
        list.setBackgroundColor(0xE6111111);
        list.setDividerHeight(1);
        list.setVisibility(View.GONE);
        list.setFocusable(true);
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(dp(420), -1, Gravity.LEFT);
        root.addView(list, listLp);

        list.setOnItemClickListener((parent, view, position, id) -> {
            current = position;
            playCurrent();
            hideList();
        });

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(root);
    }

    private synchronized void initMpvIfNeeded(SurfaceHolder holder) {
        if (mpvInitialized || destroyed) {
            if (mpvInitialized && player != null && holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                try { player.attachSurface(holder.getSurface()); } catch (Throwable ignored) {}
            }
            return;
        }
        try {
            player = MPVLib.create(this);
            player.setOptionString("vo", "gpu");
            player.setOptionString("gpu-context", "android");
            player.setOptionString("gpu-api", "opengl");
            player.setOptionString("hwdec", "auto-safe");
            player.setOptionString("hwdec-codecs", "all");
            player.setOptionString("ao", "audiotrack");
            // 老款 Android 9 电视/盒子对部分 MP2/AAC 输出格式兼容较差，
            // 固定为常见的 48kHz / 16-bit / 双声道，减少爆音、破音。
            player.setOptionString("audio-samplerate", "48000");
            player.setOptionString("audio-format", "s16");
            player.setOptionString("audio-channels", "stereo");
            player.setOptionString("cache", "yes");
            // 国内 IPTV 直播优先快速起播，避免大缓存把阶段2拖很久。
            player.setOptionString("cache-secs", "3");
            player.setOptionString("demuxer-cache-time", "3");
            player.setOptionString("demuxer-readahead-secs", "3");
            player.setOptionString("demuxer-max-bytes", "32MiB");
            player.setOptionString("demuxer-max-back-bytes", "4MiB");
            player.setOptionString("cache-pause-initial", "no");
            player.setOptionString("network-timeout", "10");
            player.setOptionString("user-agent", "AptvPlayer/1.4.10");
            player.setOptionString("tls-verify", "no");
            player.setOptionString("keep-open", "no");
            player.setOptionString("force-window", "yes");
            player.setOptionString("video-sync", "audio");
            player.init();
            if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                player.attachSurface(holder.getSurface());
            }
            mpvInitialized = true;
            if (playbackController != null) playbackController.setPlayer(player);
            player.addObserver(new MPVLib.EventObserver() {
                @Override public void event(int eventId) {
                    ui.post(() -> handleMpvEvent(eventId));
                }

                @Override public void eventProperty(String name) {
                    ui.post(() -> updateDebugPanel());
                }

                @Override public void eventProperty(String name, boolean value) {
                    ui.post(() -> {
                        if ("paused-for-cache".equals(name)) debugPausedForCache = value;
                        else if ("core-idle".equals(name)) debugCoreIdle = value;
                        else if ("eof-reached".equals(name)) debugEof = value;
                        updateDebugPanel();
                    });
                }

                @Override public void eventProperty(String name, long value) {
                    ui.post(() -> {
                        if ("width".equals(name)) debugWidth = value;
                        else if ("height".equals(name)) debugHeight = value;
                        updateDebugPanel();
                    });
                }

                @Override public void eventProperty(String name, double value) {
                    ui.post(() -> {
                        if ("cache-buffering-state".equals(name)) debugBufferPercent = value;
                        else if ("demuxer-cache-duration".equals(name)) debugCacheSeconds = value;
                        else if ("time-pos".equals(name)) {
                            debugTimePos = value;
                            if (debugLastTimePos < 0 || Math.abs(value - debugLastTimePos) >= 0.20) {
                                debugLastTimePos = value;
                                debugLastProgressAt = System.currentTimeMillis();
                                if (value > 0.25) {
                                    if (!playbackHealthy) debugHealthySince = System.currentTimeMillis();
                                    playbackHealthy = true;
                                    debugStage = "4/4 正在连续播放";
                                    status.setVisibility(View.GONE);
                                }
                            }
                        }
                        updateDebugPanel();
                    });
                }

                @Override public void eventProperty(String name, String value) {
                    ui.post(() -> {
                        if ("video-codec".equals(name)) debugVideoCodec = value == null ? "-" : value;
                        else if ("video-format".equals(name)) debugVideoFormat = value == null ? "-" : value;
                        else if ("audio-codec-name".equals(name)) debugAudioCodec = value == null ? "-" : value;
                        updateDebugPanel();
                    });
                }
            });

            player.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            player.observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            player.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            player.observeProperty("cache-buffering-state", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            player.observeProperty("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            player.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            player.observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_STRING);
            player.observeProperty("video-format", MPVLib.MpvFormat.MPV_FORMAT_STRING);
            player.observeProperty("audio-codec-name", MPVLib.MpvFormat.MPV_FORMAT_STRING);
            player.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64);
            player.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64);

            ui.removeCallbacks(debugWatchdog);
            ui.post(debugWatchdog);
            if (pendingPlay) {
                pendingPlay = false;
                queuePlayCurrent();
            }
        } catch (Throwable e) {
            mpvInitialized = false;
            player = null;
            statusTextSafe("MPV 初始化失败：" + e.getClass().getSimpleName() +
                    (e.getMessage() == null ? "" : "\n" + e.getMessage()));
        }
    }

    private void handleMpvEvent(int eventId) {
        debugLastEvent = mpvEventName(eventId);
        switch (eventId) {
            case 6: // START_FILE
                debugStage = "1/4 正在打开直播流";
                debugFileLoaded = false;
                debugVideoReady = false;
                debugAudioReady = false;
                debugEof = false;
                debugLastProgressAt = System.currentTimeMillis();
                break;
            case 8: // FILE_LOADED
                debugStage = "2/4 已打开，正在识别音视频";
                debugFileLoaded = true;
                break;
            case 17: // VIDEO_RECONFIG
                debugStage = "3/4 视频解码器已建立";
                debugVideoReady = true;
                break;
            case 18: // AUDIO_RECONFIG
                debugAudioReady = true;
                break;
            case 21: // PLAYBACK_RESTART
                debugStage = "4/4 正在连续播放";
                if (!playbackHealthy) debugHealthySince = System.currentTimeMillis();
                playbackHealthy = true;
                playbackRetryCount = 0;
                debugLastProgressAt = System.currentTimeMillis();
                status.setVisibility(View.GONE);
                break;
            case 7: // END_FILE
                debugStage = "直播流已结束/断开";
                if (!playbackHealthy) {
                    ui.postDelayed(() -> handlePlaybackStall("直播流已结束/断开"), 300);
                }
                break;
            case 24: // QUEUE_OVERFLOW
                debugStage = "MPV事件队列溢出";
                break;
            default:
                break;
        }
        updateDebugPanel();
    }

    private String mpvEventName(int id) {
        switch (id) {
            case 1: return "SHUTDOWN";
            case 5: return "COMMAND_REPLY";
            case 6: return "START_FILE";
            case 7: return "END_FILE";
            case 8: return "FILE_LOADED";
            case 17: return "VIDEO_RECONFIG";
            case 18: return "AUDIO_RECONFIG";
            case 20: return "SEEK";
            case 21: return "PLAYBACK_RESTART";
            case 22: return "PROPERTY_CHANGE";
            case 24: return "QUEUE_OVERFLOW";
            default: return "EVENT_" + id;
        }
    }

    private String currentHost() {
        if (channels.isEmpty() || current < 0 || current >= channels.size()) return "-";
        try {
            return new URL(channels.get(current).url).getHost();
        } catch (Exception e) {
            return "非HTTP流";
        }
    }

    private String detectPlaybackProblem() {
        long now = System.currentTimeMillis();

        if (!surfaceReady) return "卡点：Surface 未就绪";
        if (!mpvInitialized || player == null) return "卡点：MPV 未初始化";
        if (!debugFileLoaded) return "卡点：直播地址尚未打开";
        if (debugPausedForCache) return "卡点：网络缓冲中";
        if (!debugVideoReady && debugFileLoaded) return "卡点：已打开流，等待视频解码";
        if (debugEof) return "卡点：直播源已断开/结束";
        if (debugCoreIdle && debugFileLoaded) return "卡点：MPV 当前空闲";
        if (debugLastProgressAt > 0 && now - debugLastProgressAt > 3500) {
            return "卡点：播放时间已停止 " + ((now - debugLastProgressAt) / 1000) +
                    " 秒（自动硬解）";
        }
        return "状态：自动硬解" +
                (playbackHealthy ? "，数据持续播放" : "，正在建立播放");
    }

    private void updateDebugPanel() {
        if (debugView == null) return;
        String channelName = channels.isEmpty() || current < 0 || current >= channels.size()
                ? "-" : channels.get(current).name;
        String resolution = (debugWidth > 0 && debugHeight > 0)
                ? debugWidth + "x" + debugHeight : "-";
        String buffer = debugBufferPercent >= 0
                ? String.format(Locale.US, "%.0f%%", debugBufferPercent) : "-";
        String cache = debugCacheSeconds >= 0
                ? String.format(Locale.US, "%.1fs", debugCacheSeconds) : "-";
        String pos = debugTimePos >= 0
                ? String.format(Locale.US, "%.1fs", debugTimePos) : "-";

        debugView.setText(
                "【直播调试】\n" +
                "频道：" + channelName + "\n" +
                "阶段：" + debugStage + "\n" +
                "订阅：" + playlistSource + "\n" +
                "事件：" + debugLastEvent + "\n" +
                "直播源：" + currentHost() + "\n" +
                "缓存：" + buffer + "  已缓存：" + cache + "\n" +
                "视频：" + debugVideoCodec + " / " + debugVideoFormat + " / " + resolution + "\n" +
                "音频：" + debugAudioCodec + "\n" +
                "时间：" + pos + "  cache=" + debugPausedForCache + "\n" +
                "恢复：" + (playerCoreRestarting ? "正在重建播放器" :
                        (playbackRefreshInProgress ? "正在更新订阅" :
                                (streamReconnectCount > 0 ? "已重连当前频道" : "未触发"))) + "\n" +
                detectPlaybackProblem()
        );
    }

    private final Runnable debugWatchdog = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            updateDebugPanel();
            checkPlaybackRecovery();

            // 真正持续播放满2秒且进度持续增长，候选网络列表才晋升为正式缓存。
            long now = System.currentTimeMillis();
            if (pendingCachePromotion &&
                    playbackHealthy &&
                    !debugPausedForCache &&
                    debugHealthySince > 0 &&
                    now - debugHealthySince >= 2000 &&
                    debugLastProgressAt > 0 &&
                    now - debugLastProgressAt <= 1500) {
                promotePendingCache();
            }

            // 真正持续播放满2秒，且最近仍有播放进度，就自动隐藏调试窗口。
            if (debugView != null &&
                    debugView.getVisibility() == View.VISIBLE &&
                    playbackHealthy &&
                    !debugPausedForCache &&
                    debugHealthySince > 0 &&
                    now - debugHealthySince >= 2000 &&
                    debugLastProgressAt > 0 &&
                    now - debugLastProgressAt <= 1500) {
                debugView.setVisibility(View.GONE);
            }

            ui.postDelayed(this, 1000);
        }
    };

    private void checkPlaybackRecovery() {
        if (destroyed || channels.isEmpty() || player == null || !mpvInitialized || !surfaceReady) return;
        long now = System.currentTimeMillis();

        if (playbackHealthy) {
            long noProgressMs = debugLastProgressAt > 0 ? now - debugLastProgressAt : 0;

            if (debugPausedForCache && noProgressMs >= 10000) {
                handlePlaybackStall("播放中连续缓冲超过10秒");
                return;
            }

            if (!debugPausedForCache && noProgressMs >= 8000) {
                handlePlaybackStall("播放时间超过8秒没有增长");
            }
            return;
        }

        long elapsed = now - channelAttemptStartedAt;
        if (channelAttemptStartedAt <= 0) return;

        // 连 START_FILE 都没有回来，说明不是普通网络首包慢，
        // 更像 MPV core/上一解码器状态没有正确退出。只重建播放器，不换频道。
        if ("-".equals(debugLastEvent) && elapsed >= 5000 &&
                !playerCoreRestartTried && !playerCoreRestarting) {
            restartPlayerCoreForCurrentChannel("loadfile 5秒没有收到 START_FILE");
            return;
        }

        // 阶段1：还没有 FILE_LOADED，本质是连接直播服务器/打开URL问题。
        if (!debugFileLoaded && elapsed >= 8000) {
            handlePlaybackStall("阶段1/4：直播地址8秒仍未打开");
            return;
        }

        // 阶段2：已经打开容器，但视频解码器还没有真正建立。
        // 国内运营商 TS/HLS 某些源会偶发关键帧/首包慢，给到12秒，再做“网络流重连”。
        if (debugFileLoaded && !debugVideoReady && elapsed >= 12000) {
            handlePlaybackStall("阶段2/4：直播流已打开但12秒仍未建立视频");
            return;
        }

        // 阶段3：视频解码器已经建立但时间轴不推进，仍只按当前频道异常处理，不自动切软件解码。
        if (debugVideoReady && debugTimePos <= 0.05 && elapsed >= 8000) {
            handlePlaybackStall("阶段3/4：视频解码已建立但8秒仍未开始播放");
            return;
        }

        if (debugPausedForCache && elapsed >= 12000) {
            handlePlaybackStall("网络缓冲超过12秒");
        }
    }

    private synchronized void restartPlayerCoreForCurrentChannel(String reason) {
        if (destroyed || channels.isEmpty() || playerCoreRestarting || playbackController == null) return;

        final int expectedGeneration = playbackGeneration;
        if (!playbackController.isCurrent(expectedGeneration)) return;

        playerCoreRestartTried = true;
        playerCoreRestarting = true;
        if (debugView != null) debugView.setVisibility(View.VISIBLE);
        debugStage = "自动恢复：正在重建播放器会话";
        status.setText(reason + "\n正在重建播放器并继续当前频道…");
        status.setVisibility(View.VISIBLE);
        updateDebugPanel();

        final MPVLib old = player;
        if (old != null) playbackController.clearPlayer(old);

        new Thread(() -> {
            try {
                // 旧 session 只做尽力清理；即使某个 native stop 卡住，也不能阻塞新的 PlaybackController。
                if (old != null) {
                    try { old.detachSurface(); } catch (Throwable ignored) {}
                    try { old.destroy(); } catch (Throwable ignored) {}
                }

                if (!playbackController.isCurrent(expectedGeneration) || destroyed) {
                    ui.post(() -> playerCoreRestarting = false);
                    return;
                }

                player = null;
                mpvInitialized = false;
                try { Thread.sleep(180); } catch (InterruptedException ignored) {}

                ui.post(() -> {
                    if (destroyed || playbackController == null ||
                            !playbackController.isCurrent(expectedGeneration)) {
                        playerCoreRestarting = false;
                        return;
                    }

                    SurfaceHolder holder = playerView.getHolder();
                    if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
                        playerCoreRestarting = false;
                        pendingPlay = true;
                        debugStage = "等待 Surface 后重建播放器";
                        updateDebugPanel();
                        return;
                    }

                    resetAttemptTelemetry(false);
                    debugStage = "播放器会话已重建";
                    initMpvIfNeeded(holder);
                    playerCoreRestarting = false;

                    if (player != null && mpvInitialized &&
                            playbackController.isCurrent(expectedGeneration) &&
                            current >= 0 && current < channels.size()) {
                        playbackController.reloadAfterPlayerRebuild(
                                channels.get(current), expectedGeneration);
                    }
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    playerCoreRestarting = false;
                    debugStage = "播放器会话重建失败";
                    statusTextSafe("播放器重建失败：" + e.getClass().getSimpleName());
                    updateDebugPanel();
                });
            }
        }, "mpv-session-rebuild").start();
    }

    private synchronized void handlePlaybackStall(String reason) {
        if (destroyed || channels.isEmpty()) return;

        if (playbackHealthy) {
            playbackHealthy = false;
            debugHealthySince = 0L;
        }
        if (debugView != null) debugView.setVisibility(View.VISIBLE);

        // 不自动切软件解码。任何阶段卡住都只允许对“当前频道”做一次干净重连。
        if (streamReconnectCount < 1) {
            streamReconnectCount++;
            forceSoftwareDecode = false;
            softwareFallbackUsed = false;
            debugStage = "自动恢复：重连当前频道 1/1";
            status.setText(reason + "\n正在重新连接当前频道…");
            status.setVisibility(View.VISIBLE);
            updateDebugPanel();
            resetAttemptTelemetry(false);
            queueCleanReconnectCurrent();
            return;
        }

        // 当前频道重连仍失败，再后台刷新订阅，尝试同名频道的新URL。
        if (!playbackRefreshTried && !playbackRefreshInProgress) {
            long now = System.currentTimeMillis();
            if (now - lastPlaybackTriggeredRefreshAt >= 60000L) {
                playbackRefreshTried = true;
                lastPlaybackTriggeredRefreshAt = now;
                refreshSubscriptionAfterPlaybackFailure(reason);
                return;
            }
            playbackRefreshTried = true;
        }

        // 仍失败只提示，不换台、不切软解，等待用户按上下键。
        if (playbackRefreshTried && !playbackRefreshInProgress) {
            showCurrentChannelUnavailable(reason);
        }
    }

    private void queueCleanReconnectCurrent() {
        if (channels.isEmpty() || destroyed || playbackController == null) return;
        if (current < 0 || current >= channels.size()) return;
        playbackController.reconnectCurrent(channels.get(current), playbackGeneration);
    }

    private void refreshSubscriptionAfterPlaybackFailure(String reason) {
        if (playbackRefreshInProgress || destroyed || channels.isEmpty()) return;
        playbackRefreshInProgress = true;
        if (debugView != null) debugView.setVisibility(View.VISIBLE);

        final Channel oldChannel = (current >= 0 && current < channels.size()) ? channels.get(current) : null;
        final int oldIndex = current;
        debugStage = "自动恢复：缓存播放失败，后台更新订阅";
        status.setText(reason + "\n缓存频道播放失败，正在后台更新订阅…");
        status.setVisibility(View.VISIBLE);
        updateDebugPanel();

        final String sub = getSubscriptionUrl(this);
        new Thread(() -> {
            try {
                ResolvedPlaylist resolved = resolveSubscription(sub);
                if (resolved.channels.isEmpty()) throw new IllegalStateException("新订阅没有有效频道");

                ui.post(() -> {
                    if (destroyed) return;
                    int newIndex = findSameChannelIndex(resolved.channels, oldChannel, oldIndex);
                    stagePendingCache(resolved);
                    setChannels(resolved.channels);
                    current = newIndex;
                    playlistSource = "Gitee候选列表（等待播放验证）";
                    playbackRefreshInProgress = false;
                    playbackRefreshTried = true;

                    // 新地址重新从自动/硬解开始，不沿用前一次软件解码状态。
                    forceSoftwareDecode = false;
                    softwareFallbackUsed = false;
                    streamReconnectCount = 0;
                    playbackHealthy = false;
                    debugStage = "订阅更新成功，正在用新地址重播";
                    status.setText("订阅已更新，正在重新播放：" +
                            (channels.isEmpty() ? "" : channels.get(current).name));
                    status.setVisibility(View.VISIBLE);
                    playCurrentAfterRefresh();
                });
            } catch (Exception e) {
                final String err = friendlyError(e);
                ui.post(() -> {
                    playbackRefreshInProgress = false;
                    debugStage = "自动更新订阅失败";
                    showCurrentChannelUnavailable("后台更新订阅失败：" + err);
                });
            }
        }, "playback-recovery-subscription").start();
    }

    private void playCurrentAfterRefresh() {
        if (channels.isEmpty()) return;
        resetAttemptTelemetry(false);
        // 这次已经完成过一次订阅刷新，若新地址仍失败则不要死循环刷新。
        playbackRefreshTried = true;
        playbackRefreshInProgress = false;
        softwareFallbackUsed = false;
        forceSoftwareDecode = false;
        queuePlayCurrent();

        // 新订阅地址仍无法播放时，只提示，不自动切台。
        final int generation = playbackGeneration;
        ui.postDelayed(() -> {
            if (!destroyed && generation == playbackGeneration && !playbackHealthy) {
                showCurrentChannelUnavailable("当前频道恢复后仍未正常播放");
            }
        }, 12000);
    }

    private void showCurrentChannelUnavailable(String reason) {
        if (destroyed) return;
        // 候选网络列表没有通过播放验证，绝不覆盖原来的好缓存。
        if (pendingCachePromotion) clearPendingCache();
        debugStage = "当前频道暂时不可用，等待用户手动换台";
        if (debugView != null) debugView.setVisibility(View.VISIBLE);

        String name = channels.isEmpty() || current < 0 || current >= channels.size()
                ? "当前频道" : channels.get(current).name;

        status.setText(
                name + " 暂时无法流畅播放\n" +
                reason + "\n" +
                "可能是网络较差或直播源暂时异常\n" +
                "不会自动切台/切软件解码\n" +
                "请按 ↑ / ↓ 手动切换频道"
        );
        status.setVisibility(View.VISIBLE);
        updateDebugPanel();
    }

    private void resetAttemptTelemetry(boolean resetRecoveryFlags) {
        playbackHealthy = false;
        debugFileLoaded = false;
        debugVideoReady = false;
        debugAudioReady = false;
        debugPausedForCache = false;
        debugCoreIdle = false;
        debugEof = false;
        debugBufferPercent = -1;
        debugCacheSeconds = -1;
        debugTimePos = -1;
        debugLastTimePos = -1;
        debugLastProgressAt = System.currentTimeMillis();
        debugHealthySince = 0L;
        debugVideoCodec = "-";
        debugVideoFormat = "-";
        debugAudioCodec = "-";
        debugWidth = 0;
        debugHeight = 0;
        debugLastEvent = "-";
        channelAttemptStartedAt = System.currentTimeMillis();

        if (resetRecoveryFlags) {
            softwareFallbackUsed = false;
            playerCoreRestartTried = false;
            playerCoreRestarting = false;
            forceSoftwareDecode = false;
            playbackRefreshTried = false;
            playbackRefreshInProgress = false;
        }
        updateDebugPanel();
    }

    private void stagePendingCache(ResolvedPlaylist resolved) {
        if (resolved == null || resolved.channels == null || resolved.channels.isEmpty()) return;
        pendingSubscriptionText = resolved.subscriptionText;
        pendingPlaylistText = resolved.playlistText;
        pendingCachePromotion = true;
    }

    private void clearPendingCache() {
        pendingCachePromotion = false;
        pendingSubscriptionText = null;
        pendingPlaylistText = null;
    }

    private void promotePendingCache() {
        if (!pendingCachePromotion) return;
        final String subText = pendingSubscriptionText;
        final String listText = pendingPlaylistText;
        if (listText == null || PlaylistParser.parse(listText).isEmpty()) {
            clearPendingCache();
            return;
        }

        writeSubscriptionCache(subText == null ? "" : subText);
        writePlaylistCacheToDisk(listText);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_SUB_CACHE, subText == null ? "" : subText)
                .putString(KEY_CACHE, listText)
                .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                .commit();

        clearPendingCache();
        playlistSource = "已验证可播放缓存";
        updateDebugPanel();
    }

    private void statusTextSafe(String text) {
        if (status != null) { status.setText(text); status.setVisibility(View.VISIBLE); }
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }

    private void loadInitial() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        // 第一层：应用内部文件缓存。只要之前成功更新过一次，就优先从这里启动。
        String cache = readPlaylistCacheFromDisk();
        List<Channel> cached = PlaylistParser.parse(cache);
        if (!cached.isEmpty()) {
            playlistSource = "内部文件缓存";
            startFromLocalPlaylist(p, cached, false);
            return;
        }

        // 第二层：SharedPreferences 备份。
        cache = p.getString(KEY_CACHE, "");
        cached = PlaylistParser.parse(cache);
        if (!cached.isEmpty()) {
            playlistSource = "Preferences备份";
            // 补写回内部文件，后续启动更稳。
            writePlaylistCacheToDisk(cache);
            startFromLocalPlaylist(p, cached, false);
            return;
        }

        // 第三层：APK 自带最近一次可用直播列表。
        // 第一次安装、GitHub无法访问、没开VPN时也能先显示频道并尝试直连播放。
        String bundled = readBundledPlaylist();
        List<Channel> bundledChannels = PlaylistParser.parse(bundled);
        if (!bundledChannels.isEmpty()) {
            playlistSource = "APK内置备用列表";
            writePlaylistCacheToDisk(bundled);
            p.edit().putString(KEY_CACHE, bundled).commit();
            startFromLocalPlaylist(p, bundledChannels, true);
            return;
        }

        // 三层本地数据都没有时才阻塞式联网获取。
        playlistSource = "首次网络加载";
        status.setText("本地没有任何频道列表，正在首次加载直播订阅…");
        status.setVisibility(View.VISIBLE);
        refreshSubscription(false, false);
    }

    private void startFromLocalPlaylist(SharedPreferences p, List<Channel> localChannels, boolean fromBundled) {
        setChannels(localChannels);
        current = Math.min(p.getInt(KEY_LAST, 0), channels.size() - 1);
        status.setText(fromBundled ? "正在使用APK内置频道列表" : "正在使用本地频道缓存");
        status.setVisibility(View.VISIBLE);

        // 本地列表先播，订阅网络绝不阻塞开机。
        playCurrent();

        long cacheTime = p.getLong(KEY_CACHE_TIME, 0L);
        boolean stale = cacheTime <= 0L || System.currentTimeMillis() - cacheTime >= CACHE_REFRESH_INTERVAL_MS;

        // 内置列表属于兜底，稍后静默尝试更新；普通缓存只有过期后才更新。
        if (fromBundled || stale) {
            ui.postDelayed(() -> refreshSubscription(false, true), 15000);
        }
    }

    private String readBundledPlaylist() {
        try (InputStream in = getAssets().open("fallback_live.m3u");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String readPlaylistCacheFromDisk() {
        File f = new File(getFilesDir(), CACHE_FILE_NAME);
        if (!f.exists() || f.length() <= 0) return "";
        try (FileInputStream in = new FileInputStream(f);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            String text = sb.toString();
            // 只有能解析出频道的缓存才认为有效。
            if (!PlaylistParser.parse(text).isEmpty()) return text;
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean writePlaylistCacheToDisk(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        File dir = getFilesDir();
        File tmp = new File(dir, CACHE_FILE_NAME + ".tmp");
        File dst = new File(dir, CACHE_FILE_NAME);
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            return false;
        }

        if (!PlaylistParser.parse(readTextFile(tmp)).isEmpty()) {
            if (dst.exists() && !dst.delete()) {
                tmp.delete();
                return false;
            }
            return tmp.renameTo(dst);
        }
        tmp.delete();
        return false;
    }

    private String readTextFile(File f) {
        try (FileInputStream in = new FileInputStream(f);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean writeSubscriptionCache(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        File dst = new File(getFilesDir(), SUB_CACHE_FILE_NAME);
        try (FileOutputStream out = new FileOutputStream(dst, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int findSameChannelIndex(List<Channel> items, Channel oldChannel, int fallback) {
        if (items == null || items.isEmpty()) return 0;
        if (oldChannel != null) {
            for (int i = 0; i < items.size(); i++) {
                Channel c = items.get(i);
                if (c.url != null && c.url.equals(oldChannel.url)) return i;
            }
            for (int i = 0; i < items.size(); i++) {
                Channel c = items.get(i);
                if (c.name != null && c.name.equals(oldChannel.name)) return i;
            }
        }
        return Math.max(0, Math.min(fallback, items.size() - 1));
    }

    private void refreshSubscription(boolean forceSettingsOnFail, boolean silentBackground) {
        final String sub = getSubscriptionUrl(this);
        final boolean hasUsableCache = !channels.isEmpty();
        final Channel oldChannel = hasUsableCache && current >= 0 && current < channels.size()
                ? channels.get(current) : null;
        final int oldIndex = current;

        if (!silentBackground || !hasUsableCache) {
            status.setText("正在加载直播订阅…");
            status.setVisibility(View.VISIBLE);
        }

        new Thread(() -> {
            try {
                ResolvedPlaylist resolved = resolveSubscription(sub);
                if (resolved.channels.isEmpty()) {
                    throw new IllegalStateException("直播列表中没有有效频道");
                }

                ui.post(() -> {
                    // 已有能用的本地缓存时，后台更新只保存为候选，不立刻覆盖“好缓存”。
                    // 这样即使 Gitee 新列表里某些源在本地网络不可用，下次开机仍使用已验证列表。
                    if (hasUsableCache) {
                        stagePendingCache(resolved);
                        playlistSource = "已验证本地缓存（后台更新待验证）";
                        if (!silentBackground) {
                            Toast.makeText(this, "订阅已更新，待当前网络验证后再替换缓存",
                                    Toast.LENGTH_SHORT).show();
                        }
                        updateDebugPanel();
                        return;
                    }

                    // 完全没有可用列表时才直接使用网络列表，并等真正播放成功后再晋升缓存。
                    stagePendingCache(resolved);
                    int newIndex = findSameChannelIndex(resolved.channels, oldChannel, oldIndex);
                    setChannels(resolved.channels);
                    current = newIndex;
                    playlistSource = "网络候选列表（等待播放验证）";

                    status.setText("订阅加载成功，共 " + channels.size() + " 个频道");
                    status.setVisibility(View.VISIBLE);
                    playCurrent();
                });
            } catch (Exception e) {
                final String reason = friendlyError(e);
                ui.post(() -> {
                    if (channels.isEmpty()) {
                        status.setText("订阅加载失败\n" + reason + "\n正在继续尝试本地/内置频道");
                        status.setVisibility(View.VISIBLE);
                    } else if (!silentBackground) {
                        Toast.makeText(this, "订阅更新失败：" + reason + "，继续使用本地缓存", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "subscription-loader").start();
    }

    private ResolvedPlaylist resolveSubscription(String subscriptionUrl) throws Exception {
        String first = downloadWithFallback(subscriptionUrl);

        List<Channel> direct = PlaylistParser.parse(first);
        if (!direct.isEmpty()) {
            return new ResolvedPlaylist(first, first, direct);
        }

        List<String> liveUrls;
        try {
            liveUrls = SeleneSubscriptionParser.extractLiveUrls(first);
        } catch (Exception decodeError) {
            throw new IllegalStateException("既不是有效 M3U，也无法按 Selene Base58 订阅解析：" + decodeError.getMessage(), decodeError);
        }

        StringBuilder errors = new StringBuilder();
        for (String liveUrl : liveUrls) {
            try {
                String playlist = downloadWithFallback(liveUrl);
                List<Channel> parsed = PlaylistParser.parse(playlist);
                if (!parsed.isEmpty()) {
                    return new ResolvedPlaylist(first, playlist, parsed);
                }
                if (errors.length() > 0) errors.append("；");
                errors.append("列表为空: ").append(liveUrl);
            } catch (Exception e) {
                if (errors.length() > 0) errors.append("；");
                errors.append(e.getMessage());
            }
        }
        throw new IllegalStateException("Selene订阅已解码，但直播列表加载失败：" + errors);
    }

    private static final class ResolvedPlaylist {
        final String subscriptionText;
        final String playlistText;
        final List<Channel> channels;

        ResolvedPlaylist(String subscriptionText, String playlistText, List<Channel> channels) {
            this.subscriptionText = subscriptionText;
            this.playlistText = playlistText;
            this.channels = channels;
        }
    }

    private String friendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
        if (e instanceof java.net.SocketTimeoutException) return "网络连接超时";
        if (e instanceof java.net.UnknownHostException) return "无法解析服务器地址，请检查网络/DNS";
        if (e instanceof javax.net.ssl.SSLException) return "HTTPS/SSL 连接失败";
        return msg;
    }

    private String downloadWithFallback(String address) throws Exception {
        List<String> candidates = buildFallbackUrls(address);
        Exception last = null;
        StringBuilder tried = new StringBuilder();

        for (String candidate : candidates) {
            try {
                return download(candidate);
            } catch (Exception e) {
                last = e;
                if (tried.length() > 0) tried.append("；");
                tried.append(shortHost(candidate)).append(": ").append(simpleNetworkError(e));
            }
        }

        if (last instanceof java.net.UnknownHostException) {
            throw new java.net.UnknownHostException("所有订阅入口均无法解析：" + tried);
        }
        throw new IOException("所有订阅入口均加载失败：" + tried, last);
    }

    private List<String> buildFallbackUrls(String address) {
        List<String> urls = new ArrayList<>();
        try {
            URL u = new URL(address);
            String host = u.getHost();
            String path = u.getPath();

            // 主线路：Gitee。若 Gitee 暂时失败，则回退到 jsDelivr / GitHub Raw。
            if ("gitee.com".equalsIgnoreCase(host) &&
                    path.startsWith("/daoshengtianxia/selene-iptv/raw/main/")) {
                String filePath = path.substring("/daoshengtianxia/selene-iptv/raw/main/".length());
                addUnique(urls, address);
                addUnique(urls, "https://cdn.jsdelivr.net/gh/daoshengtianxia123/selene-iptv@main/" + filePath);
                addUnique(urls, "https://raw.githubusercontent.com/daoshengtianxia123/selene-iptv/main/" + filePath);
                addUnique(urls, "https://github.com/daoshengtianxia123/selene-iptv/raw/refs/heads/main/" + filePath);
                return urls;
            }

            // 兼容旧配置：用户若仍保存 GitHub Raw 地址，也优先尝试 Gitee。
            if ("raw.githubusercontent.com".equalsIgnoreCase(host)) {
                String[] parts = path.split("/", 5);
                if (parts.length >= 5) {
                    String owner = parts[1];
                    String repo = parts[2];
                    String branch = parts[3];
                    String filePath = parts[4];

                    if ("daoshengtianxia123".equalsIgnoreCase(owner) &&
                            "selene-iptv".equalsIgnoreCase(repo) &&
                            "main".equalsIgnoreCase(branch)) {
                        addUnique(urls, "https://gitee.com/daoshengtianxia/selene-iptv/raw/main/" + filePath);
                    }
                    addUnique(urls, "https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + filePath);
                    addUnique(urls, address);
                    addUnique(urls, "https://github.com/" + owner + "/" + repo + "/raw/refs/heads/" + branch + "/" + filePath);
                    return urls;
                }
            }
        } catch (Exception ignored) {
        }

        addUnique(urls, address);
        return urls;
    }

    private void addUnique(List<String> urls, String value) {
        if (value != null && !value.trim().isEmpty() && !urls.contains(value)) urls.add(value);
    }

    private String shortHost(String address) {
        try { return new URL(address).getHost(); }
        catch (Exception e) { return address; }
    }

    private String simpleNetworkError(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "DNS失败";
        if (e instanceof java.net.SocketTimeoutException) return "超时";
        if (e instanceof javax.net.ssl.SSLException) return "SSL失败";
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private String download(String address) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "SeleneLiveTV/1.4 AndroidTV");
            c.setRequestProperty("Accept", "*/*");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + "：" + address);
            }
            try (InputStream in = c.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void setChannels(List<Channel> items) {
        channels.clear();
        channels.addAll(items);
        list.setAdapter(new ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_1, channels));
    }

    private void playCurrent() {
        if (channels.isEmpty()) return;
        if (current < 0) current = channels.size() - 1;
        if (current >= channels.size()) current = 0;
        Channel ch = channels.get(current);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LAST, current).apply();
        overlay.setText((current + 1) + "  " + ch.name + (ch.group.isEmpty() ? "" : "\n" + ch.group));
        overlay.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, 2500);
        status.setText("正在播放：" + ch.name);
        status.setVisibility(View.VISIBLE);
        debugStage = "准备切换频道";
        if (debugView != null) debugView.setVisibility(View.VISIBLE);
        resetAttemptTelemetry(true);

        playbackRetryCount = 0;
        streamReconnectCount = 0;

        if (!surfaceReady || !mpvInitialized || player == null) {
            pendingPlay = true;
            status.setText("正在准备播放器：" + ch.name);
            status.setVisibility(View.VISIBLE);
            return;
        }
        queueManualChannelSwitch();
    }

    private void queueManualChannelSwitch() {
        if (channels.isEmpty() || destroyed || playbackController == null) return;
        if (current < 0 || current >= channels.size()) return;
        playbackGeneration = playbackController.switchChannel(channels.get(current));
        pendingPlay = false;
    }

    private void queuePlayCurrent() {
        if (channels.isEmpty() || destroyed || playbackController == null) return;
        if (current < 0 || current >= channels.size()) return;
        playbackGeneration = playbackController.switchChannel(channels.get(current));
        pendingPlay = false;
    }

    private void schedulePlaybackRetry(String reason) {
        if (channels.isEmpty()) return;
        final int generation = playbackGeneration;
        if (playbackRetryCount >= 2) {
            status.setText(reason + "\n当前频道播放失败，按 ↑ / ↓ 切换频道");
            status.setVisibility(View.VISIBLE);
            return;
        }
        playbackRetryCount++;
        status.setText(reason + "\n正在自动重试 " + playbackRetryCount + "/2…");
        status.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> {
            if (generation != playbackGeneration || channels.isEmpty() || player == null) return;
            queueCleanReconnectCurrent();
        }, 1500);
    }

    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);

    private void previousChannel() {
        if (channels.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastManualSwitchAt < 180) return;
        lastManualSwitchAt = now;
        current--;
        if (current < 0) current = channels.size() - 1;
        playCurrent();
    }

    private void nextChannel() {
        if (channels.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastManualSwitchAt < 180) return;
        lastManualSwitchAt = now;
        current++;
        if (current >= channels.size()) current = 0;
        playCurrent();
    }

    private void showList() {
        if (channels.isEmpty()) return;
        listVisible = true;
        list.setVisibility(View.VISIBLE);
        list.setSelection(current);
        list.requestFocus();
    }

    private void hideList() {
        listVisible = false;
        list.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS) {
            status.setText("正在重新加载订阅…");
            status.setVisibility(View.VISIBLE);
            refreshSubscription(true, false);
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        playToken.incrementAndGet();
        ui.removeCallbacksAndMessages(null);
        if (playbackController != null) {
            playbackController.shutdown();
            playbackController = null;
        }
        final MPVLib oldPlayer = player;
        player = null;
        mpvInitialized = false;
        surfaceReady = false;
        if (oldPlayer != null) {
            try { oldPlayer.detachSurface(); } catch (Throwable ignored) {}
            try { oldPlayer.command(new String[]{"stop"}); } catch (Throwable ignored) {}
            try { oldPlayer.destroy(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();

        // 返回键优先关闭频道列表；若频道列表没开，则优先隐藏直播调试窗口。
        if (code == KeyEvent.KEYCODE_BACK) {
            if (listVisible) {
                hideList();
                return true;
            }
            if (debugView != null && debugView.getVisibility() == View.VISIBLE) {
                debugView.setVisibility(View.GONE);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (listVisible) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                hideList();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                previousChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                nextChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showList();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                openSettings();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
