package com.sqlteacher.desktop;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 桌面端共享后台线程池工具类。
 *
 * <p>所有桌面控制器的耗时操作（SQL 执行、表元数据加载、AI 调用等）统一复用有界守护池，
 * 避免每个控制器各自创建线程池造成资源浪费与生命周期管理复杂度。
 *
 * <p><b>线程模型</b>：使用两个守护线程和有界队列，保证：
 * <ul>
 *   <li>网络任务不会长期阻塞所有桌面后台工作；</li>
 *   <li>队列上限避免故障期间无限积压，并在应用退出时显式关闭。</li>
 * </ul>
 *
 * <p>调用方式：{@code DesktopExecutors.background().execute(task)}。
 */
public final class DesktopExecutors {

    /** 共享有界守护线程池，所有桌面控制器复用。 */
    private static final java.util.concurrent.atomic.AtomicInteger THREAD_NUMBER = new java.util.concurrent.atomic.AtomicInteger();
    private static final ExecutorService BACKGROUND = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), runnable -> {
        Thread thread = new Thread(runnable, "desktop-background-executor");
        thread.setName("desktop-background-" + THREAD_NUMBER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private DesktopExecutors() {
    }

    /**
     * 获取桌面端共享后台线程池。
     *
     * @return 有界守护线程池实例
     */
    public static ExecutorService background() {
        return BACKGROUND;
    }

    public static void shutdown() {
        BACKGROUND.shutdown();
        try {
            if (!BACKGROUND.awaitTermination(3, TimeUnit.SECONDS)) BACKGROUND.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            BACKGROUND.shutdownNow();
        }
    }
}
