package com.sqlteacher.application.connection;

public enum DatabaseDialect {
    SQLITE("SQLite", 0, Kind.FILE, Family.SQLITE),
    DUCKDB("DuckDB", 0, Kind.FILE, Family.DUCKDB),
    H2("H2", 0, Kind.FILE, Family.H2),
    MYSQL("MySQL", 3306, Kind.SERVER, Family.MYSQL),
    MARIADB("MariaDB", 3306, Kind.SERVER, Family.MYSQL),
    POSTGRESQL("PostgreSQL", 5432, Kind.SERVER, Family.POSTGRESQL),
    SQL_SERVER("SQL Server / Azure SQL", 1433, Kind.SERVER, Family.SQL_SERVER),
    ORACLE("Oracle Database", 1521, Kind.SERVER, Family.ORACLE),
    DB2("IBM Db2", 50000, Kind.SERVER, Family.DB2),
    DAMENG("达梦 DM8", 5236, Kind.SERVER, Family.DAMENG),
    TIDB("TiDB", 4000, Kind.SERVER, Family.MYSQL),
    OCEANBASE("OceanBase MySQL", 2881, Kind.SERVER, Family.MYSQL),
    GAUSSDB("GaussDB / openGauss", 5432, Kind.SERVER, Family.POSTGRESQL),
    GENERIC("通用 JDBC", 0, Kind.GENERIC, Family.GENERIC);

    private final String displayName;
    private final int defaultPort;
    private final Kind kind;
    private final Family family;

    DatabaseDialect(String displayName, int defaultPort, Kind kind, Family family) {
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.kind = kind;
        this.family = family;
    }

    public String displayName() { return displayName; }
    public int defaultPort() { return defaultPort; }
    public boolean fileBased() { return kind == Kind.FILE; }
    public boolean serverBased() { return kind == Kind.SERVER; }
    public boolean generic() { return kind == Kind.GENERIC; }
    public Family family() { return family; }

    @Override public String toString() { return displayName; }

    private enum Kind { FILE, SERVER, GENERIC }

    public enum Family {
        SQLITE, DUCKDB, H2, MYSQL, POSTGRESQL, SQL_SERVER, ORACLE, DB2, DAMENG, GENERIC
    }
}
