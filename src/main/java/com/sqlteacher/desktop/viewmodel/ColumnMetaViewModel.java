package com.sqlteacher.desktop.viewmodel;

import com.sqlteacher.application.metadata.DatabaseColumn;

/**
 * 桌面端表字段视图模型，隔离 application 层 {@link DatabaseColumn} DTO。
 *
 * <p>仅承载 UI 渲染所需的最小字段集合：字段名、类型名、是否可空、是否主键。
 * 控制器层通过 {@link #from(DatabaseColumn)} 工厂方法从 application 层 DTO 转换，
 * 避免在 JavaFX 控件中直接引用 infrastructure / application 包类型。
 */
public record ColumnMetaViewModel(
    String name,
    String typeName,
    boolean nullable,
    boolean primaryKey
) {

    /**
     * 从 application 层 {@link DatabaseColumn} 构造桌面端视图模型。
     *
     * @param column application 层字段 DTO，不可为 {@code null}
     * @return 与入参字段一一对应的视图模型
     */
    public static ColumnMetaViewModel from(DatabaseColumn column) {
        return new ColumnMetaViewModel(
            column.name(),
            column.typeName(),
            column.nullable(),
            column.primaryKey()
        );
    }
}
