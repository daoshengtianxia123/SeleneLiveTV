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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    public static final String DEFAULT_SUB_URL =
            "https://raw.githubusercontent.com/daoshengtianxia123/selene-iptv/main/selene-sub.txt";
    public static final String PREFS = "selene_live_prefs";
    public static final String KEY_SUB_URL = "subscription_url";
    private static final String KEY_CACHE = "playlist_cache";
    private static final String KEY_CACHE_TIME = "playlist_cache_time";
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
    private final ExecutorService playerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger playToken = new AtomicInteger(0);
    private boolean pendingPlay = false;
    private int playbackRetryCount = 0;
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
    private volatile String debugVideoCodec = "-";
    private volatile String debugVideoFormat = "-";
    private volatile String debugAudioCodec = "-";
    private volatile long debugWidth = 0;
    private volatile long debugHeight = 0;
    private volatile String debugLastEvent = "-";

    // 自动恢复状态：
    // 1) 本地缓存先播；2) 解码卡住先切软件解码；3) 仍失败则后台刷新订阅并替换缓存。
    private volatile boolean playbackHealthy = false;
    private volatile boolean softwareFallbackUsed = false;
    private volatile boolean forceSoftwareDecode = false;
    private volatile boolean playbackRefreshInProgress = false;
    private volatile boolean playbackRefreshTried = false;
    private volatile long channelAttemptStartedAt = 0L;

    private int current = 0;
    private boolean listVisible = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();
        loadInitial();
    }

    public static String getSubscriptionUrl(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_SUB_URL, DEFAULT_SUB_URL);
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
            player.setOptionString("cache", "yes");
            player.setOptionString("cache-secs", "8");
            player.setOptionString("demuxer-cache-time", "8");
            player.setOptionString("demuxer-readahead-secs", "8");
            player.setOptionString("demuxer-max-bytes", "64MiB");
            player.setOptionString("demuxer-max-back-bytes", "8MiB");
            player.setOptionString("network-timeout", "15");
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
                                    playbackHealthy = true;
                                    debugStage = forceSoftwareDecode
                                            ? "4/4 软件解码正在连续播放"
                                            : "4/4 正在连续播放";
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
            if (pendingPlay || !channels.isEmpty()) {
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
                playbackRetryCount = 0;
                break;
            case 17: // VIDEO_RECONFIG
                debugStage = "3/4 视频解码器已建立";
                debugVideoReady = true;
                break;
            case 18: // AUDIO_RECONFIG
                debugAudioReady = true;
                break;
            case 21: // PLAYBACK_RESTART
                debugStage = forceSoftwareDecode
                        ? "4/4 软件解码正在连续播放"
                        : "4/4 正在连续播放";
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
                    " 秒" + (forceSoftwareDecode ? "（软件解码）" : "（硬解/自动）");
        }
        return "状态：" + (forceSoftwareDecode ? "软件解码" : "自动解码") +
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
                "事件：" + debugLastEvent + "\n" +
                "服务器：" + currentHost() + "\n" +
                "缓存：" + buffer + "  已缓存：" + cache + "\n" +
                "视频：" + debugVideoCodec + " / " + debugVideoFormat + " / " + resolution + "\n" +
                "音频：" + debugAudioCodec + "\n" +
                "时间：" + pos + "  cache=" + debugPausedForCache + "\n" +
                "恢复：" + (playbackRefreshInProgress ? "正在更新订阅" :
                        (softwareFallbackUsed ? "已尝试软件解码" : "未触发")) + "\n" +
                detectPlaybackProblem()
        );
    }

    private final Runnable debugWatchdog = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            updateDebugPanel();
            checkPlaybackRecovery();
            ui.postDelayed(this, 1000);
        }
    };

    private void checkPlaybackRecovery() {
        if (destroyed || channels.isEmpty() || player == null || !mpvInitialized || !surfaceReady) return;
        if (playbackHealthy) {
            // 已经进入 PLAYBACK_RESTART 后，如果 time-pos 又长时间不增长，也视为卡死。
            if (debugLastProgressAt > 0 &&
                    System.currentTimeMillis() - debugLastProgressAt > 8000 &&
                    !debugPausedForCache) {
                handlePlaybackStall("播放时间超过8秒没有增长");
            }
            return;
        }

        long elapsed = System.currentTimeMillis() - channelAttemptStartedAt;
        if (channelAttemptStartedAt <= 0) return;

        // 已打开流、识别到视频参数，但始终无法真正开始播放：与你截图中的状态一致。
        if (debugFileLoaded && debugWidth > 0 && debugHeight > 0 &&
                debugTimePos <= 0.05 && elapsed >= 6000) {
            handlePlaybackStall("已打开H.264视频但6秒仍未开始播放");
            return;
        }

        // 连 FILE_LOADED 都没有：多数是旧地址、网络或服务器问题。
        if (!debugFileLoaded && elapsed >= 9000) {
            handlePlaybackStall("直播地址9秒仍未打开");
            return;
        }

        // 长时间一直缓冲也触发恢复。
        if (debugPausedForCache && elapsed >= 12000) {
            handlePlaybackStall("网络缓冲超过12秒");
        }
    }

    private synchronized void handlePlaybackStall(String reason) {
        if (destroyed || channels.isEmpty() || playbackHealthy) return;

        // 第一次卡住优先关闭硬解，用软件解码重试当前地址。
        if (!softwareFallbackUsed) {
            softwareFallbackUsed = true;
            forceSoftwareDecode = true;
            debugStage = "自动恢复：切换软件解码重试";
            status.setText(reason + "\n正在切换软件解码重试…");
            status.setVisibility(View.VISIBLE);
            updateDebugPanel();
            resetAttemptTelemetry(false);
            queuePlayCurrent();
            return;
        }

        // 软件解码仍然失败：认为当前缓存中的直播地址可能已经失效，后台刷新订阅。
        if (!playbackRefreshTried && !playbackRefreshInProgress) {
            playbackRefreshTried = true;
            refreshSubscriptionAfterPlaybackFailure(reason);
        }
    }

    private void refreshSubscriptionAfterPlaybackFailure(String reason) {
        if (playbackRefreshInProgress || destroyed || channels.isEmpty()) return;
        playbackRefreshInProgress = true;

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

                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_CACHE, resolved.playlistText)
                        .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                        .apply();

                ui.post(() -> {
                    if (destroyed) return;
                    int newIndex = findSameChannelIndex(resolved.channels, oldChannel, oldIndex);
                    setChannels(resolved.channels);
                    current = newIndex;
                    playbackRefreshInProgress = false;
                    playbackRefreshTried = true;

                    // 新地址重新从自动/硬解开始，不沿用前一次软件解码状态。
                    forceSoftwareDecode = false;
                    softwareFallbackUsed = false;
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
                    status.setText("播放失败，后台更新订阅也失败：\n" + err +
                            "\n仍保留本地缓存，可按 ↑ / ↓ 换台");
                    status.setVisibility(View.VISIBLE);
                    updateDebugPanel();
                });
            }
        }, "playback-recovery-subscription").start();
    }

    private void playCurrentAfterRefresh() {
        if (channels.isEmpty()) return;
        resetAttemptTelemetry(true);
        queuePlayCurrent();
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
        debugVideoCodec = "-";
        debugVideoFormat = "-";
        debugAudioCodec = "-";
        debugWidth = 0;
        debugHeight = 0;
        debugLastEvent = "-";
        channelAttemptStartedAt = System.currentTimeMillis();

        if (resetRecoveryFlags) {
            softwareFallbackUsed = false;
            forceSoftwareDecode = false;
            playbackRefreshTried = false;
            playbackRefreshInProgress = false;
        }
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
        String cache = p.getString(KEY_CACHE, "");
        List<Channel> cached = PlaylistParser.parse(cache);
        if (!cached.isEmpty()) {
            setChannels(cached);
            current = Math.min(p.getInt(KEY_LAST, 0), channels.size() - 1);
            status.setVisibility(View.GONE);
            playCurrent();

            long cacheTime = p.getLong(KEY_CACHE_TIME, 0L);
            if (System.currentTimeMillis() - cacheTime >= CACHE_REFRESH_INTERVAL_MS) {
                ui.postDelayed(() -> refreshSubscription(false, true), 3000);
            }
        } else {
            refreshSubscription(true, false);
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

                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_CACHE, resolved.playlistText)
                        .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                        .apply();

                ui.post(() -> {
                    Channel playingNow = !channels.isEmpty() && current >= 0 && current < channels.size()
                            ? channels.get(current) : oldChannel;
                    int currentNow = current;
                    int newIndex = findSameChannelIndex(resolved.channels, playingNow,
                            currentNow >= 0 ? currentNow : oldIndex);
                    setChannels(resolved.channels);
                    current = newIndex;

                    if (silentBackground && hasUsableCache) {
                        return;
                    }

                    status.setText("订阅加载成功，共 " + channels.size() + " 个频道");
                    status.setVisibility(View.VISIBLE);
                    ui.postDelayed(() -> {
                        if (!channels.isEmpty()) status.setVisibility(View.GONE);
                    }, 1200);
                    playCurrent();
                });
            } catch (Exception e) {
                final String reason = friendlyError(e);
                ui.post(() -> {
                    if (channels.isEmpty()) {
                        status.setText("订阅加载失败\n" + reason + "\n\n按菜单键进入订阅设置");
                        status.setVisibility(View.VISIBLE);
                        if (forceSettingsOnFail) ui.postDelayed(this::openSettings, 1200);
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
            return new ResolvedPlaylist(first, direct);
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
                    return new ResolvedPlaylist(playlist, parsed);
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
        final String playlistText;
        final List<Channel> channels;

        ResolvedPlaylist(String playlistText, List<Channel> channels) {
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
        addUnique(urls, address);
        try {
            URL u = new URL(address);
            if ("raw.githubusercontent.com".equalsIgnoreCase(u.getHost())) {
                String[] parts = u.getPath().split("/", 5);
                if (parts.length >= 5) {
                    String owner = parts[1];
                    String repo = parts[2];
                    String branch = parts[3];
                    String path = parts[4];
                    addUnique(urls, "https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + path);
                    addUnique(urls, "https://github.com/" + owner + "/" + repo + "/raw/refs/heads/" + branch + "/" + path);
                }
            }
        } catch (Exception ignored) {
        }
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
        resetAttemptTelemetry(true);

        playbackGeneration++;
        playbackRetryCount = 0;

        if (!surfaceReady || !mpvInitialized || player == null) {
            pendingPlay = true;
            status.setText("正在准备播放器：" + ch.name);
            status.setVisibility(View.VISIBLE);
            return;
        }
        queuePlayCurrent();
    }

    private void queuePlayCurrent() {
        if (channels.isEmpty() || destroyed) return;
        final int index = current;
        final Channel ch = channels.get(index);
        final int token = playToken.incrementAndGet();
        pendingPlay = false;

        playerExecutor.execute(() -> {
            if (destroyed || token != playToken.get() || !surfaceReady || player == null || !mpvInitialized) return;
            try {
                ui.post(() -> {
                    debugStage = forceSoftwareDecode
                            ? "已发送 loadfile（软件解码），等待 START_FILE"
                            : "已发送 loadfile（自动解码），等待 START_FILE";
                    updateDebugPanel();
                });
                try {
                    player.setPropertyString("hwdec", forceSoftwareDecode ? "no" : "auto-safe");
                } catch (Throwable ignored) {
                    // 某些 mpv 构建不允许运行时改 hwdec；loadfile 仍继续尝试。
                }
                player.command(new String[]{"loadfile", ch.url, "replace"});
                player.setPropertyBoolean("pause", false);
                ui.post(() -> {
                    if (!destroyed && token == playToken.get()) {
                        playerView.requestFocus();
                    }
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (!destroyed && token == playToken.get()) {
                        statusTextSafe("MPV 播放失败：" + e.getClass().getSimpleName() +
                                (e.getMessage() == null ? "" : "\n" + e.getMessage()));
                    }
                });
            }
        });
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
            queuePlayCurrent();
        }, 1500);
    }

    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);

    private void previousChannel() {
        if (channels.isEmpty()) return;
        current--;
        if (current < 0) current = channels.size() - 1;
        playCurrent();
    }

    private void nextChannel() {
        if (channels.isEmpty()) return;
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
        final MPVLib oldPlayer = player;
        player = null;
        mpvInitialized = false;
        surfaceReady = false;
        if (oldPlayer != null) {
            try { oldPlayer.detachSurface(); } catch (Throwable ignored) {}
            try { oldPlayer.command(new String[]{"stop"}); } catch (Throwable ignored) {}
            try { oldPlayer.destroy(); } catch (Throwable ignored) {}
        }
        playerExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();

        if (listVisible) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) {
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
