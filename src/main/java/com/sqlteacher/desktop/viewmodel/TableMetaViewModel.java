package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.metadata.DatabaseTable;

import java.util.List;

/**
 * 桌面端表结构视图模型，隔离 application 层 {@link DatabaseTable} DTO。
 *
 * <p>承载表名与字段列表（{@link ColumnMetaViewModel}）。控制器层通过
 * {@link #from(DatabaseTable)} 工厂方法从 application 层 DTO 转换，
 * 避免在 JavaFX 控件中直接引用 application 包类型。
 */
public record TableMetaViewModel(
    String name,
    List<ColumnMetaViewModel> columns
) {

    /**
     * 紧凑构造器：字段列表防御性拷贝并兜底为空列表，避免 null 进入 UI 层。
     */
    public TableMetaViewModel {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }

    /**
     * 从 application 层 {@link DatabaseTable} 构造桌面端视图模型，
     * 同时将每个 {@link com.sqlteacher.application.metadata.DatabaseColumn} 转换为
     * {@link ColumnMetaViewModel}。
     *
     * @param table application 层表 DTO，不可为 {@code null}
     * @return 与入参表一一对应的视图模型
     */
    public static TableMetaViewModel from(DatabaseTable table) {
        List<ColumnMetaViewModel> columns = table.columns().stream()
            .map(ColumnMetaViewModel::from)
            .toList();
        return new TableMetaViewModel(table.name(), columns);
    }
}
