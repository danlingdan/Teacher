package com.sqlteacher.desktop.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

/**
 * 高危SQL二次确认弹窗控制器。
 *
 * <p>由 {@link SqlRiskConfirmDialogUtil} 统一管理实例化与生命周期，
 * 对外不暴露直接使用接口。
 */
public final class SqlRiskConfirmDialogController {

    @FXML
    private Label riskTitleLabel;

    @FXML
    private Label riskTypeLabel;

    @FXML
    private Label affectedTablesLabel;

    @FXML
    private TextArea sqlPreviewArea;

    @FXML
    private Button cancelButton;

    @FXML
    private Button confirmButton;

    private Runnable onConfirm;

    /**
     * 设置弹窗内容。
     *
     * @param sql          待执行的SQL语句
     * @param riskType     风险类型描述
     * @param affectedTables 涉及的数据表列表
     * @param onConfirm    确认回调
     */
    public void setContent(String sql, String riskType, String affectedTables, Runnable onConfirm) {
        this.onConfirm = onConfirm;
        riskTypeLabel.setText("风险类型：" + riskType);
        affectedTablesLabel.setText("涉及数据表：" + affectedTables);
        sqlPreviewArea.setText(sql);
    }

    @FXML
    private void onCancel() {
        cancelButton.getScene().getWindow().hide();
    }

    @FXML
    private void onConfirm() {
        if (onConfirm != null) {
            onConfirm.run();
        }
        confirmButton.getScene().getWindow().hide();
    }
}
