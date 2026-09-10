package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.ExerciseBankBlock;
import com.sqlteacher.application.collaboration.ExerciseBankManifest;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streaming (incremental) exercise bank updates from the cloud server. The client fetches
 * the manifest, computes the pending diff locally (version comparison only; no student or
 * personal data leaves the device), downloads only the changed DSL blocks, verifies their
 * content hashes, self-tests the reassembled package, and applies it through the shared
 * versioned upsert in one transaction. Any failure leaves the local bank untouched and the
 * local learning flow fully usable.
 */
public final class ExerciseBankSyncService {
    private static final Logger log = LoggerFactory.getLogger(ExerciseBankSyncService.class);
    public static final String DEFAULT_CHANNEL = "network";

    private final CloudApiClient cloudApiClient;
    private final String appDatabasePath;
    private final ExerciseBankContent content = new ExerciseBankContent();
    private final ExerciseBankWriter writer = new ExerciseBankWriter();

    public ExerciseBankSyncService(CloudApiClient cloudApiClient, String appDatabasePath) {
        this.cloudApiClient = cloudApiClient;
        this.appDatabasePath = appDatabasePath;
    }

    public record BankUpdateStatus(
        int appliedVersion, int serverVersion, int pendingBlocks, boolean upToDate, String message
    ) {
    }

    public record BankUpdateResult(
        boolean applied, int appliedVersion, int updatedDatasets, int updatedExercises, String message
    ) {
    }

    public BankUpdateStatus check() {
        return check(DEFAULT_CHANNEL);
    }

    /** Checks one distribution channel; failures degrade silently to an up-to-date state. */
    public BankUpdateStatus check(String channel) {
        try {
            ExerciseBankManifest manifest = cloudApiClient.fetchExerciseBankManifest(channel);
            LocalVersions local = readLocalVersions();
            int pending = countPending(manifest, local);
            int applied = appliedVersion(channel);
            boolean upToDate = pending == 0;
            return new BankUpdateStatus(
                applied, manifest.bankVersion(), pending, upToDate,
                upToDate ? "题库已是最新。" : "服务器有 " + pending + " 个题库内容更新待应用。"
            );
        } catch (RuntimeException error) {
            log.info("Exercise bank update check failed: {}", error.getClass().getSimpleName());
            return new BankUpdateStatus(appliedVersion(channel), -1, 0, true, "暂时无法连接题库服务器，本地题库不受影响。");
        }
    }

    public BankUpdateResult update(Consumer<String> progress) {
        return update(DEFAULT_CHANNEL, progress);
    }

    public BankUpdateResult update(String channel, Consumer<String> progress) {
        ExerciseBankManifest manifest = cloudApiClient.fetchExerciseBankManifest(channel);
        LocalVersions local = readLocalVersions();
        int appliedVersion = appliedVersion(channel);
        if (manifest.bankVersion() <= appliedVersion && countPending(manifest, local) == 0) {
            return new BankUpdateResult(false, appliedVersion, 0, 0, "题库已是最新。");
        }
        List<ExerciseBankManifest.BlockRef> pendingDatasets = pendingRefs(manifest.datasets(), local.datasets());
        List<ExerciseBankManifest.BlockRef> pendingExercises = pendingRefs(manifest.exercises(), local.exercises());
        int total = pendingDatasets.size() + pendingExercises.size();
        if (total == 0) {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDatabasePath)) {
                recordAppliedVersion(connection, channel, manifest.bankVersion());
            } catch (SQLException error) {
                throw new SqlTeacherException("EXERCISE_BANK_APPLY_FAILED", "无法记录题库版本。", error);
            }
            return new BankUpdateResult(false, manifest.bankVersion(), 0, 0, "题库已是最新。");
        }

        StringBuilder packageText = new StringBuilder("# SQLTeacherExercisePackage 1\n");
        int done = 0;
        for (ExerciseBankManifest.BlockRef ref : pendingDatasets) {
            packageText.append(fetchVerifiedBlock("DATASET", ref, manifest.bankVersion()).content()).append('\n');
            done++;
            if (progress != null) progress.accept("已下载 " + done + "/" + total + " 项");
        }
        for (ExerciseBankManifest.BlockRef ref : pendingExercises) {
            packageText.append(fetchVerifiedBlock("EXERCISE", ref, manifest.bankVersion()).content()).append('\n');
            done++;
            if (progress != null) progress.accept("已下载 " + done + "/" + total + " 项");
        }
        if (progress != null) progress.accept("正在校验题库内容");

        ExerciseBankContent.ParsedBank bank = content.parse(packageText.toString());
        List<String> failures = content.selfTestFailures(bank, this::localDataset);
        if (!failures.isEmpty()) {
            throw new SqlTeacherException(
                "EXERCISE_BANK_INVALID", "服务器题库未通过自测，已取消应用：" + String.join("；", failures)
            );
        }
        if (progress != null) progress.accept("正在应用题库更新");

        try {
            SqliteDriver.ensureLoaded();
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_APPLY_FAILED", "无法初始化本地数据库驱动。", error);
        }
        int updatedDatasets = 0;
        int updatedExercises = 0;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDatabasePath)) {
            connection.setAutoCommit(false);
            try {
                for (var dataset : bank.datasets()) {
                    var outcome = writer.upsertDataset(connection, dataset);
                    if (outcome == ExerciseBankWriter.DatasetOutcome.CONFLICT) {
                        throw new SqlTeacherException(
                            "EXERCISE_BANK_INVALID", "数据集 " + dataset.id() + " 与本地内容冲突，已取消应用"
                        );
                    }
                    if (outcome == ExerciseBankWriter.DatasetOutcome.INSERTED) updatedDatasets++;
                }
                for (var exercise : bank.exercises()) {
                    if (writer.upsertExercise(connection, exercise) != ExerciseBankWriter.ExerciseOutcome.SKIPPED) {
                        updatedExercises++;
                    }
                }
                recordAppliedVersion(connection, channel, manifest.bankVersion());
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_APPLY_FAILED", "题库更新应用失败，本地题库保持不变。", error);
        }
        if (progress != null) progress.accept("completed");
        log.info("Applied exercise bank update to version {}", manifest.bankVersion());
        return new BankUpdateResult(
            true, manifest.bankVersion(), updatedDatasets, updatedExercises,
            "题库已更新到第 " + manifest.bankVersion() + " 版。"
        );
    }

    private ExerciseBankBlock fetchVerifiedBlock(
        String type, ExerciseBankManifest.BlockRef ref, int bankVersion
    ) {
        ExerciseBankBlock block = cloudApiClient.fetchExerciseBankBlock(type, ref.id());
        String actualHash = sha256Hex(block.content());
        if (!actualHash.equalsIgnoreCase(ref.sha256())) {
            throw new SqlTeacherException(
                "EXERCISE_BANK_INVALID", "题库分块校验失败（" + ref.id() + "），已取消应用。"
            );
        }
        if (block.version() > bankVersion) {
            throw new SqlTeacherException(
                "EXERCISE_BANK_INVALID", "题库分块版本异常（" + ref.id() + "），已取消应用。"
            );
        }
        return block;
    }

    private List<ExerciseBankManifest.BlockRef> pendingRefs(
        List<ExerciseBankManifest.BlockRef> refs, Map<String, Integer> localVersions
    ) {
        List<ExerciseBankManifest.BlockRef> pending = new ArrayList<>();
        for (ExerciseBankManifest.BlockRef ref : refs) {
            Integer localVersion = localVersions.get(ref.id());
            if (localVersion == null || ref.version() > localVersion) {
                pending.add(ref);
            }
        }
        return pending;
    }

    private int countPending(ExerciseBankManifest manifest, LocalVersions local) {
        return pendingRefs(manifest.datasets(), local.datasets()).size()
            + pendingRefs(manifest.exercises(), local.exercises()).size();
    }

    private record LocalVersions(Map<String, Integer> datasets, Map<String, Integer> exercises) {
    }

    private LocalVersions readLocalVersions() {
        return new LocalVersions(
            localVersionsByTable("exercise_datasets"),
            localVersionsByTable("exercises")
        );
    }

    private Map<String, Integer> localVersionsByTable(String table) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDatabasePath);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select id, version from " + table)) {
            while (rows.next()) {
                versions.put(rows.getString(1), rows.getInt(2));
            }
            return versions;
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_BANK_READ_FAILED", "无法读取本地题库版本。", error);
        }
    }

    private java.util.Optional<com.sqlteacher.domain.exercise.ExerciseDataset> localDataset(String id) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDatabasePath);
             PreparedStatement statement = connection.prepareStatement(
                 "select name, setup_sql, version from exercise_datasets where id = ?"
             )) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(new com.sqlteacher.domain.exercise.ExerciseDataset(
                    id, row.getString("name"), row.getString("setup_sql"), row.getInt("version")
                ));
            }
        } catch (SQLException error) {
            return java.util.Optional.empty();
        }
    }

    private int appliedVersion(String channel) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDatabasePath);
             PreparedStatement statement = connection.prepareStatement(
                 "select bank_version from exercise_bank_state where channel = ?"
             )) {
            statement.setString(1, channel);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        } catch (SQLException error) {
            return 0;
        }
    }

    private void recordAppliedVersion(Connection connection, String channel, int bankVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into exercise_bank_state(channel, bank_version, manifest_sha256, updated_at)"
                + " values (?, ?, NULL, ?)"
                + " on conflict(channel) do update set bank_version = excluded.bank_version,"
                + " updated_at = excluded.updated_at"
        )) {
            statement.setString(1, channel);
            statement.setInt(2, bankVersion);
            statement.setString(3, java.time.Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
