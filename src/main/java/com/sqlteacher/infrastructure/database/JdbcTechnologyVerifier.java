package com.sqlteacher.infrastructure.database;

import com.sqlteacher.infrastructure.environment.VerificationItem;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class JdbcTechnologyVerifier {
    public VerificationItem verifySqliteInMemoryQuery() {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table student(id integer primary key, name text not null)");
            statement.executeUpdate("insert into student(name) values ('Alice')");

            try (ResultSet resultSet = statement.executeQuery("select count(*) from student")) {
                if (resultSet.next() && resultSet.getInt(1) == 1) {
                    return VerificationItem.passed("SQLite JDBC", "in-memory query succeeded");
                }
            }
            return VerificationItem.failed("SQLite JDBC", "unexpected query result");
        } catch (SQLException ex) {
            return VerificationItem.failed("SQLite JDBC", ex.getMessage());
        }
    }

    public VerificationItem verifyMysqlDriverAvailable() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            return VerificationItem.passed("MySQL JDBC", "driver class is available");
        } catch (ClassNotFoundException ex) {
            return VerificationItem.failed("MySQL JDBC", "driver class not found");
        }
    }
}
