package com.daosheng.selenelivetv;

import dev.jdtech.mpv.MPVLib;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 直播播放控制器。
 *
 * 设计目标：
 * 1. 所有正常 MPV 控制命令只在一个专用控制线程中串行执行，避免多个线程同时操作同一个 MPV 实例。
 * 2. 用户手动切台优先级最高：清掉尚未执行的自动恢复任务，并用 generation 让旧任务失效。
 * 3. 不自动切软件解码、不自动换台。
 * 4. 独立 watchdog 只负责发现“控制线程被 native 调用卡住”，由 Activity 决定是否重建 MPV session。
 */
public final class PlaybackController {
    public interface Listener {
        void onControllerStage(String stage);
        void onControllerFailure(String message);
        void onControllerBlocked(long blockedMs, int generation);
    }

    private final ThreadPoolExecutor executor;
    private final Thread watchdogThread;
    private final AtomicInteger generation = new AtomicInteger(0);
    private final Listener listener;

    private volatile MPVLib player;
    private volatile boolean shutdown = false;
    private volatile long commandStartedAt = 0L;
    private volatile int activeGeneration = 0;
    private volatile int reportedBlockedGeneration = -1;

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

        watchdogThread = new Thread(() -> {
            while (!shutdown) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                if (shutdown) break;

                long started = commandStartedAt;
                if (started <= 0) continue;

                long blocked = System.currentTimeMillis() - started;
                int gen = activeGeneration;
                if (blocked >= 5000 && reportedBlockedGeneration != gen) {
                    reportedBlockedGeneration = gen;
                    if (listener != null) listener.onControllerBlocked(blocked, gen);
                }
            }
        }, "playback-controller-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
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

    /**
     * 用户主动切台：最高优先级。
     * 清除所有尚未开始的旧命令，旧 generation 的任务即使稍后醒来也会自行退出。
     */
    public int switchChannel(Channel channel) {
        final int gen = generation.incrementAndGet();
        executor.getQueue().clear();
        reportedBlockedGeneration = -1;

        submit(gen, () -> {
            MPVLib p = player;
            if (p == null || channel == null) return;

            stage("切台：正在释放上一直播流");
            try {
                p.command(new String[]{"stop"});
            } catch (Throwable ignored) {
            }

            if (!isCurrent(gen)) return;

            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }

            if (!isCurrent(gen)) return;
            stage("切台：正在打开 " + channel.name);

            try {
                p.setPropertyString("hwdec", "auto-safe");
            } catch (Throwable ignored) {
            }

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
     * 当前频道自动恢复，只允许重连当前频道，不改变频道。
     */
    public void reconnectCurrent(Channel channel, int expectedGeneration) {
        if (channel == null || !isCurrent(expectedGeneration)) return;

        submit(expectedGeneration, () -> {
            MPVLib p = player;
            if (p == null || !isCurrent(expectedGeneration)) return;

            stage("恢复：正在重连当前频道");
            try {
                p.command(new String[]{"stop"});
            } catch (Throwable ignored) {
            }

            if (!isCurrent(expectedGeneration)) return;
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }

            if (!isCurrent(expectedGeneration)) return;
            try {
                p.setPropertyString("hwdec", "auto-safe");
            } catch (Throwable ignored) {
            }

            p.command(new String[]{"loadfile", channel.url, "replace"});
            if (!isCurrent(expectedGeneration)) return;
            try {
                p.setPropertyBoolean("pause", false);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * MPV session 被重建后，继续播放用户最后选择的频道。
     */
    public void reloadAfterPlayerRebuild(Channel channel, int expectedGeneration) {
        if (channel == null || !isCurrent(expectedGeneration)) return;
        executor.getQueue().clear();
        reportedBlockedGeneration = -1;

        submit(expectedGeneration, () -> {
            MPVLib p = player;
            if (p == null || !isCurrent(expectedGeneration)) return;
            stage("播放器已重建，正在继续当前频道");
            try {
                p.setPropertyString("hwdec", "auto-safe");
            } catch (Throwable ignored) {
            }
            p.command(new String[]{"loadfile", channel.url, "replace"});
            if (!isCurrent(expectedGeneration)) return;
            try {
                p.setPropertyBoolean("pause", false);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 用户切台时先递增 generation，Activity 中所有旧自动恢复也可据此失效。
     */
    public int invalidateAndGetNewGeneration() {
        int gen = generation.incrementAndGet();
        executor.getQueue().clear();
        reportedBlockedGeneration = -1;
        return gen;
    }

    public boolean isCurrent(int gen) {
        return !shutdown && generation.get() == gen;
    }

    private void submit(int gen, ThrowingRunnable action) {
        executor.execute(() -> {
            if (!isCurrent(gen)) return;
            activeGeneration = gen;
            commandStartedAt = System.currentTimeMillis();
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
            } finally {
                if (activeGeneration == gen) {
                    commandStartedAt = 0L;
                }
            }
        });
    }

    private void stage(String text) {
        if (listener != null) listener.onControllerStage(text);
    }

    public void shutdown() {
        shutdown = true;
        generation.incrementAndGet();
        executor.getQueue().clear();
        executor.shutdownNow();
        watchdogThread.interrupt();
        player = null;
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
