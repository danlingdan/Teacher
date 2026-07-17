package com.sqlteacher.desktop;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局复用 Loading 遮罩组件。
 *
 * <p>提供全屏半透明遮罩 + 居中加载动画，阻塞底层界面交互；所有耗时操作（SQL 执行、
 * 元数据加载等）统一调用 {@link #show(String)} / {@link #hide()}，保证视觉一致且不会
 * 遗留卡死遮罩。
 *
 * <p><b>生命周期</b>：
 * <ul>
 *   <li>由 {@link com.sqlteacher.desktop.controller.MainWindowController} 在 FXML 注入完成后
 *       调用 {@link #initialize(StackPane, Label)} 初始化一次；</li>
 *   <li>{@link #show(String)} 与 {@link #hide()} 可在任意线程调用，内部通过
 *       {@link Platform#runLater(Runnable)} 切回 FX 线程更新 UI；</li>
 *   <li>支持嵌套调用计数：同一线程/任务内多次 show 不会提前隐藏，只有计数归零才关闭遮罩，
 *       避免多个并发耗时操作互相冲掉 loading 状态；</li>
 *   <li>自带 60 秒超时兜底：若调用方因异常分支未正确 hide，遮罩会自动强制关闭，杜绝残留遮挡。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：内部状态变更均在 FX 线程串行执行，调用方无需额外同步。
 */
public final class GlobalLoading {

    /** 默认自动隐藏超时：60 秒。超过该时间未收到 hide 则强制关闭遮罩。 */
    private static final Duration DEFAULT_AUTO_HIDE_TIMEOUT = Duration.seconds(60);

    /** 单例引用，由主窗口控制器初始化。 */
    private static GlobalLoading instance;

    /** 遮罩根容器：覆盖整个主窗口内容区。 */
    private final StackPane overlay;

    /** 提示文字 Label。 */
    private final Label messageLabel;

    /**
     * 调用计数器。值为 0 时遮罩关闭；大于 0 时保持显示。
     * 用于处理多个并发耗时操作场景，避免某个任务提前关闭其他任务需要的 loading。
     */
    private final AtomicInteger showCounter = new AtomicInteger(0);

    /** 超时自动隐藏定时器，防止调用方异常分支未 hide 导致遮罩卡死。 */
    private PauseTransition autoHideTimer;

    private GlobalLoading(StackPane overlay, Label messageLabel) {
        this.overlay = Objects.requireNonNull(overlay, "overlay must not be null");
        this.messageLabel = Objects.requireNonNull(messageLabel, "messageLabel must not be null");
    }

    /**
     * 初始化全局 Loading 组件。必须在 FX Application Thread 上调用一次（通常由主窗口
     * {@code initialize()} 调用）。
     *
     * @param overlay      遮罩根 StackPane，覆盖在主窗口内容之上
     * @param messageLabel 居中提示文字 Label
     */
    public static synchronized void initialize(StackPane overlay, Label messageLabel) {
        if (instance != null) {
            return;
        }
        instance = new GlobalLoading(overlay, messageLabel);
    }

    /**
     * 显示全局 Loading 遮罩。
     *
     * <p>可在任意线程调用；若当前不在 FX 线程，会自动通过 {@link Platform#runLater(Runnable)}
     * 切换到 FX 线程执行。传入 {@code null} 或空白字符串时使用默认提示。
     *
     * @param message 提示文字，例如 "正在加载表结构…"
     */
    public static void show(String message) {
        GlobalLoading loading = instance;
        if (loading == null) {
            return;
        }
        Runnable action = () -> loading.doShow(message);
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * 隐藏全局 Loading 遮罩。
     *
     * <p>与 {@link #show(String)} 成对调用；考虑到嵌套 show 计数，只有当所有 show
     * 都对应 hide 后遮罩才会真正关闭。
     */
    public static void hide() {
        GlobalLoading loading = instance;
        if (loading == null) {
            return;
        }
        Runnable action = loading::doHide;
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** 强制关闭遮罩并把计数器归零。用于异常兜底，防止遮罩卡死。 */
    public static void forceHide() {
        GlobalLoading loading = instance;
        if (loading == null) {
            return;
        }
        Runnable action = loading::doForceHide;
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private void doShow(String message) {
        cancelAutoHideTimer();
        showCounter.incrementAndGet();
        messageLabel.setText(message == null || message.isBlank() ? "加载中…" : message);
        overlay.setVisible(true);
        overlay.setManaged(true);
        overlay.toFront();
        scheduleAutoHide();
    }

    private void doHide() {
        cancelAutoHideTimer();
        int count = showCounter.decrementAndGet();
        if (count <= 0) {
            showCounter.set(0);
            overlay.setVisible(false);
            overlay.setManaged(false);
        } else {
            // 仍有其他 show 未关闭，重新启动超时兜底。
            scheduleAutoHide();
        }
    }

    private void doForceHide() {
        cancelAutoHideTimer();
        showCounter.set(0);
        overlay.setVisible(false);
        overlay.setManaged(false);
    }

    private void scheduleAutoHide() {
        autoHideTimer = new PauseTransition(DEFAULT_AUTO_HIDE_TIMEOUT);
        autoHideTimer.setOnFinished(event -> doForceHide());
        autoHideTimer.play();
    }

    private void cancelAutoHideTimer() {
        if (autoHideTimer != null) {
            autoHideTimer.stop();
            autoHideTimer.setOnFinished(null);
            autoHideTimer = null;
        }
    }
}
