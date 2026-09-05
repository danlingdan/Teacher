package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteAppDatabaseInitializer implements DatabaseInitializationService {
    private static final Logger log = LoggerFactory.getLogger(SqliteAppDatabaseInitializer.class);

    private final SqlTeacherConfiguration properties;

    public SqliteAppDatabaseInitializer(SqlTeacherConfiguration properties) {
        this.properties = properties;
    }

    @Override
    public DatabaseInitializationResult initialize() {
        Path dataDirectory = properties.dataDirectory();
        Path appDatabase = properties.database().appDatabasePath();
        Path demoDatabase = properties.database().demoDatabasePath();

        try {
            migrateLegacyDataDirectory(dataDirectory);
            Files.createDirectories(dataDirectory);
            Files.createDirectories(appDatabase.toAbsolutePath().getParent());
            Files.createDirectories(demoDatabase.toAbsolutePath().getParent());

            boolean appCreated = Files.notExists(appDatabase);
            boolean demoCreated = Files.notExists(demoDatabase);

            initializeAppDatabaseWithRecovery(appDatabase, dataDirectory.resolve("exercise-sessions"));
            initializeDemoDatabase(demoDatabase);

            log.info("SQLite databases initialized, appDatabase={}, demoDatabase={}", appDatabase, demoDatabase);
            return new DatabaseInitializationResult(appDatabase, demoDatabase, appCreated, demoCreated);
        } catch (IOException | SQLException ex) {
            throw new SqlTeacherException("SQLITE_INIT_FAILED", "Failed to initialize SQLite databases", ex);
        }
    }

    private static void initializeAppDatabase(Path databasePath, Path sessionDirectory) throws SQLException, IOException {
        new SqliteSchemaMigrator().migrate(databasePath);
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            connection.setAutoCommit(false);
            try {
                new DefaultExerciseCatalogSeeder().seed(connection);
                ExerciseSessionRuntimeCleaner.closeActiveSessions(connection, java.time.Instant.now());
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
        ExerciseSessionRuntimeCleaner.deleteSessionFiles(sessionDirectory);
    }

    private void initializeAppDatabaseWithRecovery(Path databasePath, Path sessionDirectory)
            throws SQLException, IOException {
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        boolean upgradeNeeded = Files.exists(databasePath)
            && migrator.currentVersion(databasePath) < migrator.latestVersion();
        String recoveryBackup = null;
        if (upgradeNeeded) {
            recoveryBackup = new SqliteApplicationBackupService(properties)
                .createAutomaticBackup("before-schema-" + migrator.latestVersion()).id();
        }
        try {
            initializeAppDatabase(databasePath, sessionDirectory);
        } catch (SQLException | IOException | RuntimeException error) {
            if (recoveryBackup != null) {
                try {
                    new SqliteApplicationBackupService(properties).restoreBackup(recoveryBackup);
                } catch (RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            throw error;
        }
    }

    private static void initializeDemoDatabase(Path databasePath) throws SQLException {
        createDemoDatabase(databasePath, false);
    }

    static void createDemoDatabase(Path databasePath, boolean reset) throws SQLException {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {

            // Always drop old tables first: SQLite is case-insensitive, so legacy "student"
            // would collide with the new "Student" table otherwise.
            String[] dropTables = {
                "DROP TABLE IF EXISTS student",
                "DROP TABLE IF EXISTS Student",
                "DROP TABLE IF EXISTS Course",
                "DROP TABLE IF EXISTS SC",
                "DROP TABLE IF EXISTS S",
                "DROP TABLE IF EXISTS P",
                "DROP TABLE IF EXISTS J",
                "DROP TABLE IF EXISTS SPJ"
            };
            for (String drop : dropTables) {
                statement.executeUpdate(drop);
            }

            // ── Table definitions ──
            statement.executeUpdate("""
                CREATE TABLE Student (
                    Sno        INTEGER PRIMARY KEY,
                    Sname      TEXT    NOT NULL,
                    Ssex       TEXT    NOT NULL,
                    Sbirthdate TEXT,
                    Smajor     TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE Course (
                    Cno     INTEGER PRIMARY KEY,
                    Cname   TEXT    NOT NULL,
                    Ccredit INTEGER,
                    Cpno    INTEGER
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE SC (
                    Sno           INTEGER NOT NULL,
                    Cno           INTEGER NOT NULL,
                    Grade         INTEGER,
                    Semester      INTEGER,
                    Teachingclass TEXT,
                    PRIMARY KEY (Sno, Cno)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE S (
                    SNO    TEXT    PRIMARY KEY,
                    SNAME  TEXT    NOT NULL,
                    STATUS INTEGER,
                    CITY   TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE P (
                    PNO    TEXT    PRIMARY KEY,
                    PNAME  TEXT    NOT NULL,
                    COLOR  TEXT,
                    WEIGHT INTEGER
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE J (
                    JNO   TEXT    PRIMARY KEY,
                    JNAME TEXT    NOT NULL,
                    CITY  TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE SPJ (
                    SNO TEXT    NOT NULL,
                    PNO TEXT    NOT NULL,
                    JNO TEXT    NOT NULL,
                    QTY INTEGER,
                    PRIMARY KEY (SNO, PNO, JNO)
                )
                """);

            // ── Student data (7 rows) ──
            statement.executeUpdate("INSERT INTO Student VALUES (20180001, '李勇',   '男', '2000-03-08', '信息安全')");
            statement.executeUpdate("INSERT INTO Student VALUES (20180002, '刘晨',   '女', '1999-09-01', '计算机科学与技术')");
            statement.executeUpdate("INSERT INTO Student VALUES (20180003, '王敏',   '女', '2001-08-01', '计算机科学与技术')");
            statement.executeUpdate("INSERT INTO Student VALUES (20180004, '张立',   '男', '2000-01-08', '计算机科学与技术')");
            statement.executeUpdate("INSERT INTO Student VALUES (20180005, '陈新奇', '男', '2001-11-01', '信息管理与信息系统')");
            statement.executeUpdate("INSERT INTO Student VALUES (20180006, '赵明',   '男', '2000-06-12', '数据科学与大数据技术')");
            statement.executeUpdate("INSERT INTO Student VALUES (20180007, '王佳佳', '女', '2001-12-07', '数据科学与大数据技术')");

            // ── Course data (8 rows) ──
            statement.executeUpdate("INSERT INTO Course VALUES (81001, '程序设计基础与C语言', 4, NULL)");
            statement.executeUpdate("INSERT INTO Course VALUES (81002, '数据结构',           4, 81001)");
            statement.executeUpdate("INSERT INTO Course VALUES (81003, '数据库系统概论',     4, 81002)");
            statement.executeUpdate("INSERT INTO Course VALUES (81004, '信息系统概论',       4, 81003)");
            statement.executeUpdate("INSERT INTO Course VALUES (81005, '操作系统',           4, 81001)");
            statement.executeUpdate("INSERT INTO Course VALUES (81006, 'Python语言',         3, 81002)");
            statement.executeUpdate("INSERT INTO Course VALUES (81007, '离散数学',           4, NULL)");
            statement.executeUpdate("INSERT INTO Course VALUES (81008, '大数据技术概论',     4, 81003)");

            // ── SC data (11 rows) ──
            statement.executeUpdate("INSERT INTO SC VALUES (20180001, 81001, 85, 20192, '81001-01')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180001, 81002, 96, 20201, '81002-01')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180001, 81003, 87, 20202, '81003-01')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180002, 81001, 80, 20192, '81001-02')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180002, 81002, 98, 20201, '81002-01')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180002, 81003, 71, 20202, '81003-02')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180003, 81001, 81, 20192, '81001-01')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180003, 81002, 76, 20201, '81002-02')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180004, 81001, 56, 20192, '81001-02')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180004, 81002, 97, 20201, '81002-02')");
            statement.executeUpdate("INSERT INTO SC VALUES (20180005, 81003, 68, 20202, '81003-01')");

            // ── S data (5 rows) ──
            statement.executeUpdate("INSERT INTO S VALUES ('S1', '精益',   20, '天津')");
            statement.executeUpdate("INSERT INTO S VALUES ('S2', '盛锡',   10, '北京')");
            statement.executeUpdate("INSERT INTO S VALUES ('S3', '东方红', 30, '北京')");
            statement.executeUpdate("INSERT INTO S VALUES ('S4', '丰泰盛', 20, '天津')");
            statement.executeUpdate("INSERT INTO S VALUES ('S5', '为民',   30, '上海')");

            // ── P data (6 rows) ──
            statement.executeUpdate("INSERT INTO P VALUES ('P1', '螺母',   '红', 12)");
            statement.executeUpdate("INSERT INTO P VALUES ('P2', '螺栓',   '绿', 17)");
            statement.executeUpdate("INSERT INTO P VALUES ('P3', '螺丝刀', '蓝', 14)");
            statement.executeUpdate("INSERT INTO P VALUES ('P4', '螺丝刀', '红', 14)");
            statement.executeUpdate("INSERT INTO P VALUES ('P5', '凸轮',   '蓝', 40)");
            statement.executeUpdate("INSERT INTO P VALUES ('P6', '齿轮',   '红', 30)");

            // ── J data (7 rows) ──
            statement.executeUpdate("INSERT INTO J VALUES ('J1', '三建',     '北京')");
            statement.executeUpdate("INSERT INTO J VALUES ('J2', '一汽',     '长春')");
            statement.executeUpdate("INSERT INTO J VALUES ('J3', '弹簧厂',   '天津')");
            statement.executeUpdate("INSERT INTO J VALUES ('J4', '造船厂',   '天津')");
            statement.executeUpdate("INSERT INTO J VALUES ('J5', '机车厂',   '唐山')");
            statement.executeUpdate("INSERT INTO J VALUES ('J6', '无线电厂', '常州')");
            statement.executeUpdate("INSERT INTO J VALUES ('J7', '半导体厂', '南京')");

            // ── SPJ data (19 rows) ──
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S1', 'P1', 'J1', 200)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S1', 'P1', 'J3', 100)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S1', 'P1', 'J4', 700)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S1', 'P2', 'J2', 100)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S2', 'P3', 'J1', 400)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S2', 'P3', 'J2', 200)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S2', 'P3', 'J4', 500)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S2', 'P3', 'J5', 400)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S2', 'P5', 'J1', 400)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S2', 'P5', 'J2', 100)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S3', 'P1', 'J1', 200)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S3', 'P3', 'J1', 200)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S4', 'P5', 'J1', 100)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S4', 'P6', 'J3', 300)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S4', 'P6', 'J4', 200)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S5', 'P2', 'J4', 100)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S5', 'P3', 'J1', 200)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S5', 'P6', 'J2', 200)");
            statement.executeUpdate("INSERT INTO SPJ VALUES ('S5', 'P6', 'J4', 500)");
        }
    }

    private static void migrateLegacyDataDirectory(Path dataDirectory) throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return;
        }
        Path expected = Path.of(localAppData, "SQLTeacher").toAbsolutePath().normalize();
        Path target = dataDirectory.toAbsolutePath().normalize();
        Path legacy = Path.of("app-data").toAbsolutePath().normalize();
        if (!target.equals(expected) || target.equals(legacy) || Files.notExists(legacy)
            || Files.exists(target.resolve("app.db"))) {
            return;
        }
        copyMissingLegacyFiles(legacy, target);
        log.info("Migrated legacy SQLTeacher data directory from {} to {}", legacy, target);
    }

    static void copyMissingLegacyFiles(Path legacy, Path target) throws IOException {
        Path normalizedLegacy = legacy.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);
        try (var paths = Files.walk(normalizedLegacy)) {
            for (Path source : paths.toList()) {
                Path relative = normalizedLegacy.relativize(source);
                Path destination = normalizedTarget.resolve(relative).normalize();
                if (!destination.startsWith(normalizedTarget)) {
                    throw new IOException("Legacy data path escaped target directory");
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else if (Files.notExists(destination)) {
                    Files.copy(source, destination, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
