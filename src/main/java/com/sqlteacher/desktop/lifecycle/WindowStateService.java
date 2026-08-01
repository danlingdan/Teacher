package com.sqlteacher.desktop.lifecycle;

import com.sqlteacher.infrastructure.system.AtomicJsonFile;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.file.Path;

public final class WindowStateService {
    private final Path stateFile;
    public WindowStateService(Path dataDirectory) { stateFile = dataDirectory.toAbsolutePath().normalize().resolve("support/window-state.json"); }

    public void restore(Stage stage, double defaultWidth, double defaultHeight) {
        State state = AtomicJsonFile.read(stateFile, State.class, new State(Double.NaN, Double.NaN, defaultWidth, defaultHeight, false));
        double width = Math.clamp(state.width(), 840, Math.max(840, Screen.getPrimary().getVisualBounds().getWidth()));
        double height = Math.clamp(state.height(), 600, Math.max(600, Screen.getPrimary().getVisualBounds().getHeight()));
        Rectangle2D bounds = Screen.getScreens().stream().map(Screen::getVisualBounds)
            .filter(screen -> contains(screen, state.x(), state.y())).findFirst().orElse(Screen.getPrimary().getVisualBounds());
        stage.setWidth(width); stage.setHeight(height);
        stage.setX(Double.isFinite(state.x()) ? Math.clamp(state.x(), bounds.getMinX(), bounds.getMaxX() - 120) : bounds.getMinX() + (bounds.getWidth() - width) / 2);
        stage.setY(Double.isFinite(state.y()) ? Math.clamp(state.y(), bounds.getMinY(), bounds.getMaxY() - 80) : bounds.getMinY() + (bounds.getHeight() - height) / 2);
        stage.setMaximized(state.maximized());
    }
    public void save(Stage stage) {
        if (stage.isIconified()) return;
        AtomicJsonFile.write(stateFile, new State(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), stage.isMaximized()));
    }
    private static boolean contains(Rectangle2D value, double x, double y) { return Double.isFinite(x) && Double.isFinite(y) && value.contains(x, y); }
    private record State(double x, double y, double width, double height, boolean maximized) { }
}
