package com.sqlteacher.desktop;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 桌面端共享后台线程池工具类。
 *
 * <p>所有桌面控制器的耗时操作（SQL 执行、表元数据加载、AI 调用等）统一复用此单线程守护池，
 * 避免每个控制器各自创建线程池造成资源浪费与生命周期管理复杂度。
 *
 * <p><b>线程模型</b>：使用单线程守护线程（daemon），保证：
 * <ul>
 *   <li>任务串行执行，避免并发干扰 SQLite 等单写者场景；</li>
 *   <li>守护线程不会阻止 JVM 退出，无需显式关闭钩子。</li>
 * </ul>
 *
 * <p>调用方式：{@code DesktopExecutors.background().execute(task)}。
 */
public final class DesktopExecutors {

    /** 共享单线程守护线程池，所有桌面控制器复用。 */
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "desktop-background-executor");
        thread.setDaemon(true);
        return thread;
    });

    private DesktopExecutors() {
    }

    /**
     * 获取桌面端共享后台线程池。
     *
     * @return 单线程守护线程池实例
     */
    public static ExecutorService background() {
        return BACKGROUND;
    }
}
