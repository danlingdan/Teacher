package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.risk.SqlSafetyModeService;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

import java.util.Objects;

public final class SqlSafetySettingsController {
    private final SqlSafetyModeService safetyModeService;

    @FXML private CheckBox unrestrictedModeCheck;
    @FXML private Label statusLabel;

    public SqlSafetySettingsController(SqlSafetyModeService safetyModeService) {
        this.safetyModeService = Objects.requireNonNull(safetyModeService);
    }

    @FXML
    private void initialize() {
        unrestrictedModeCheck.setSelected(safetyModeService.isUnrestrictedModeEnabled());
        renderStatus();
    }

    @FXML
    private void onToggleUnrestrictedMode() {
        boolean requested = unrestrictedModeCheck.isSelected();
        if (requested && !confirmEnable()) {
            unrestrictedModeCheck.setSelected(false);
            renderStatus();
            return;
        }
        try {
            safetyModeService.setUnrestrictedModeEnabled(requested);
            renderStatus();
        } catch (RuntimeException error) {
            unrestrictedModeCheck.setSelected(safetyModeService.isUnrestrictedModeEnabled());
            statusLabel.setText(error.getMessage() == null ? "SQL 安全设置保存失败。" : error.getMessage());
            setStatusStyle("status-error");
        }
    }

    private boolean confirmEnable() {
        ButtonType enable = new ButtonType("确认启用无限模式", ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(
            Alert.AlertType.WARNING,
            "启用后，SQLTeacher 将不再拦截 DROP、TRUNCATE、UPDATE、DELETE、多语句等操作，"
                + "也不会要求二次确认。SQL 可能永久删除或修改数据。\n\n"
                + "该设置仅影响当前设备；数据库权限、驱动限制仍然有效。AI 只会生成草稿，不会自动执行。",
            enable,
            ButtonType.CANCEL
        );
        alert.setTitle("启用无限模式");
        alert.setHeaderText("关闭应用层 SQL 安全限制？");
        return alert.showAndWait().filter(enable::equals).isPresent();
    }

    private void renderStatus() {
        if (safetyModeService.isUnrestrictedModeEnabled()) {
            statusLabel.setText("无限模式已启用：危险 SQL 将直接交给数据库执行。建议只用于可恢复的练习数据。");
            setStatusStyle("status-error");
        } else {
            statusLabel.setText("标准安全模式已启用：危险、多语句及需确认的 SQL 会被拦截或提示。");
            setStatusStyle("status-success");
        }
    }

    private void setStatusStyle(String styleClass) {
        statusLabel.getStyleClass().removeAll("status-info", "status-success", "status-error");
        statusLabel.getStyleClass().add(styleClass);
    }
}
