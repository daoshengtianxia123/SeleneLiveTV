package com.daosheng.selenelivetv;

import dev.jdtech.mpv.MPVLib;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 稳定版直播播放控制器。
 *
 * 原则：
 * 1. 只有一个控制线程操作 MPV，绝不跨线程 destroy/stop 同一个 native 实例。
 * 2. 人工切台使用 mpv 标准 loadfile ... replace，不再先 stop，避免 stop 卡住控制线程。
 * 3. 用户连续切台时，清除尚未执行的旧任务，只保留最后一次选择。
 * 4. 不自动软解、不自动换台、不在 watchdog 中强杀播放器。
 */
public final class PlaybackController {
    public interface Listener {
        void onControllerStage(String stage);
        void onControllerFailure(String message);
    }

    private final ThreadPoolExecutor executor;
    private final AtomicInteger generation = new AtomicInteger(0);
    private final Listener listener;

    private volatile MPVLib player;
    private volatile boolean shutdown = false;

    public PlaybackController(Listener listener) {
        this.listener = listener;
        this.executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "playback-controller");
                    t.setDaemon(true);
                    return t;
                }
        );
        this.executor.prestartAllCoreThreads();
    }

    public void setPlayer(MPVLib player) {
        this.player = player;
    }

    public void clearPlayer(MPVLib expected) {
        if (this.player == expected) this.player = null;
    }

    public int getGeneration() {
        return generation.get();
    }

    public boolean isCurrent(int gen) {
        return !shutdown && generation.get() == gen;
    }

    /**
     * 用户主动切台：清除排队中的旧命令，立即把“最后选择的频道”作为唯一目标。
     * 不先 stop，直接由 MPV 的 replace 语义完成旧流切换。
     */
    public int switchChannel(Channel channel) {
        final int gen = generation.incrementAndGet();
        executor.getQueue().clear();

        submit(gen, () -> {
            MPVLib p = player;
            if (p == null || channel == null || !isCurrent(gen)) return;

            stage("切台：正在打开 " + channel.name);
            p.command(new String[]{"loadfile", channel.url, "replace"});

            if (!isCurrent(gen)) return;
            try {
                p.setPropertyBoolean("pause", false);
            } catch (Throwable ignored) {
            }
        });

        return gen;
    }

    /**
     * 当前频道需要人工/显式重试时，仍使用 replace，不做 stop + sleep。
     */
    public void reconnectCurrent(Channel channel, int expectedGeneration) {
        if (channel == null || !isCurrent(expectedGeneration)) return;

        executor.getQueue().clear();
        submit(expectedGeneration, () -> {
            MPVLib p = player;
            if (p == null || !isCurrent(expectedGeneration)) return;

            stage("正在重新打开当前频道");
            p.command(new String[]{"loadfile", channel.url, "replace"});

            if (!isCurrent(expectedGeneration)) return;
            try {
                p.setPropertyBoolean("pause", false);
            } catch (Throwable ignored) {
            }
        });
    }

    public void shutdown() {
        shutdown = true;
        generation.incrementAndGet();
        executor.getQueue().clear();
        executor.shutdownNow();
        player = null;
    }

    private void submit(int gen, ThrowingRunnable action) {
        executor.execute(() -> {
            if (!isCurrent(gen)) return;
            try {
                action.run();
            } catch (Throwable e) {
                if (isCurrent(gen) && listener != null) {
                    String msg = e.getClass().getSimpleName();
                    if (e.getMessage() != null && !e.getMessage().trim().isEmpty()) {
                        msg += ": " + e.getMessage();
                    }
                    listener.onControllerFailure(msg);
                }
            }
        });
    }

    private void stage(String text) {
        if (listener != null) listener.onControllerStage(text);
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
