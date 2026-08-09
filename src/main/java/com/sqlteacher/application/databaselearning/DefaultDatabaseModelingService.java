package com.sqlteacher.application.databaselearning;

import java.util.List;
import java.util.Locale;

public final class DefaultDatabaseModelingService implements DatabaseModelingService {
    @Override
    public ModelDraft draft(String requirement) {
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("请先描述业务需求");
        String value = requirement.toLowerCase(Locale.ROOT);
        if (value.contains("订单") || value.contains("商品") || value.contains("购物")) return orders();
        if (value.contains("图书") || value.contains("借阅")) return library();
        if (value.contains("学生") || value.contains("课程") || value.contains("选课")) return enrollment();
        return new ModelDraft("需求需要进一步拆解",
            "请补充核心对象、对象之间的关系以及需要保持唯一或必填的数据。当前不会猜测表结构，也不会生成可执行 SQL。",
            List.of(), "");
    }

    private static ModelDraft enrollment() {
        var student = table("student", "保存学生基本信息", col("id", "INTEGER", true, false, ""), col("name", "VARCHAR(100)", false, false, ""));
        var course = table("course", "保存课程", col("id", "INTEGER", true, false, ""), col("title", "VARCHAR(160)", false, false, ""));
        var enrollment = table("enrollment", "记录学生选课关系", col("student_id", "INTEGER", true, false, "student(id)"), col("course_id", "INTEGER", true, false, "course(id)"), col("enrolled_at", "TIMESTAMP", false, false, ""));
        return model("学生选课模型", "多对多关系由 enrollment 关联表表达，联合主键阻止重复选课。", student, course, enrollment);
    }

    private static ModelDraft orders() {
        var customer = table("customer", "保存顾客", col("id", "INTEGER", true, false, ""), col("name", "VARCHAR(100)", false, false, ""));
        var product = table("product", "保存商品", col("id", "INTEGER", true, false, ""), col("name", "VARCHAR(160)", false, false, ""), col("price", "DECIMAL(12,2)", false, false, ""));
        var order = table("customer_order", "保存订单头", col("id", "INTEGER", true, false, ""), col("customer_id", "INTEGER", false, false, "customer(id)"), col("created_at", "TIMESTAMP", false, false, ""));
        var line = table("order_line", "保存订单明细", col("order_id", "INTEGER", true, false, "customer_order(id)"), col("product_id", "INTEGER", true, false, "product(id)"), col("quantity", "INTEGER", false, false, ""));
        return model("电商订单模型", "订单头和明细分离，明细引用商品并保留购买数量。", customer, product, order, line);
    }

    private static ModelDraft library() {
        var reader = table("reader", "保存读者", col("id", "INTEGER", true, false, ""), col("name", "VARCHAR(100)", false, false, ""));
        var book = table("book", "保存图书", col("id", "INTEGER", true, false, ""), col("title", "VARCHAR(200)", false, false, ""));
        var loan = table("loan", "记录借阅与归还", col("id", "INTEGER", true, false, ""), col("reader_id", "INTEGER", false, false, "reader(id)"), col("book_id", "INTEGER", false, false, "book(id)"), col("borrowed_at", "TIMESTAMP", false, false, ""), col("returned_at", "TIMESTAMP", false, true, ""));
        return model("图书借阅模型", "借阅记录独立保存时间，returned_at 为空表示尚未归还。", reader, book, loan);
    }

    private static ModelDraft model(String title, String explanation, TableDraft... tables) {
        List<TableDraft> list = List.of(tables);
        String ddl = list.stream().map(DefaultDatabaseModelingService::ddl).reduce((a, b) -> a + "\n\n" + b).orElse("");
        return new ModelDraft(title, explanation, list, ddl);
    }

    private static String ddl(TableDraft table) {
        var primary = table.columns().stream().filter(ColumnDraft::primaryKey).map(ColumnDraft::name).toList();
        var lines = new java.util.ArrayList<String>();
        for (ColumnDraft column : table.columns()) {
            String line = "    " + column.name() + " " + column.type() + (column.nullable() ? "" : " NOT NULL");
            lines.add(line);
        }
        if (!primary.isEmpty()) lines.add("    PRIMARY KEY (" + String.join(", ", primary) + ")");
        table.columns().stream().filter(column -> !column.reference().isBlank()).forEach(column ->
            lines.add("    FOREIGN KEY (" + column.name() + ") REFERENCES " + column.reference()));
        return "CREATE TABLE " + table.name() + " (\n" + String.join(",\n", lines) + "\n);";
    }

    private static TableDraft table(String name, String purpose, ColumnDraft... columns) { return new TableDraft(name, purpose, List.of(columns)); }
    private static ColumnDraft col(String name, String type, boolean pk, boolean nullable, String reference) { return new ColumnDraft(name, type, pk, nullable, reference); }
}
