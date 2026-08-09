package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudArtifactSyncItem;
import com.sqlteacher.application.collaboration.CloudArtifactSyncResult;
import com.sqlteacher.application.collaboration.CloudArtifactSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Local-first outbox for versioned metadata. Failures never remove local facts. */
public final class JdbcCloudArtifactSyncService implements CloudArtifactSyncService {
    private final JdbcConnectionFactory connections;
    private final LearningEventOwnerProvider owners;
    private final CloudApiClient api;
    private final CloudSessionService sessions;

    public JdbcCloudArtifactSyncService(JdbcConnectionFactory connections, LearningEventOwnerProvider owners,
                                        CloudApiClient api, CloudSessionService sessions) {
        this.connections = Objects.requireNonNull(connections);
        this.owners = Objects.requireNonNull(owners);
        this.api = Objects.requireNonNull(api);
        this.sessions = Objects.requireNonNull(sessions);
    }

    @Override
    public void enqueue(CloudArtifactSyncItem item) {
        Objects.requireNonNull(item, "item must not be null");
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            insert into cloud_sync_operation(operation_id,owner_id,aggregate_type,aggregate_id,aggregate_version,
                payload_sha256,summary_json,status,conflict_code,created_at,updated_at)
            values(?,?,?,?,?,?,?,'PENDING','',?,?)
            on conflict(operation_id) do nothing
            """)) {
            Instant now = Instant.now();
            statement.setString(1, item.operationId()); statement.setString(2, owner());
            statement.setString(3, item.aggregateType()); statement.setString(4, item.aggregateId());
            statement.setLong(5, item.aggregateVersion()); statement.setString(6, item.payloadSha256());
            statement.setString(7, item.summaryJson()); statement.setString(8, now.toString());
            statement.setString(9, now.toString()); statement.executeUpdate();
        } catch (SQLException error) {
            throw new SqlTeacherException("CLOUD_SYNC_ENQUEUE_FAILED", "Failed to retain local sync operation", error);
        }
    }

    @Override
    public synchronized SyncReport synchronize() {
        if (!api.capabilities().supports("ARTIFACT_SYNC_V2")) {
            throw new IllegalStateException("CLOUD_CAPABILITY_UNAVAILABLE");
        }
        var session = sessions.refresh().or(() -> sessions.current())
            .orElseThrow(() -> new IllegalStateException("Cloud sign-in is required"));
        String owner = owner();
        List<CloudArtifactSyncItem> pending = pending(owner);
        List<CloudArtifactSyncResult> uploaded;
        try {
            uploaded = api.uploadArtifactSyncItems(session.accessToken(), pending);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Cloud sync failed; local operations were retained", error);
        }
        int conflicts = applyResults(owner, uploaded);
        long cursor = cursor(owner);
        var page = api.downloadArtifactSyncItems(session.accessToken(), cursor);
        int downloaded = importPage(owner, page.items());
        saveCursor(owner, page.cursor());
        int accepted = (int) uploaded.stream().filter(item -> item.status() != CloudArtifactSyncResult.Status.CONFLICT).count();
        return new SyncReport(accepted, downloaded, conflicts, page.cursor(), Instant.now());
    }

    private List<CloudArtifactSyncItem> pending(String owner) {
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            select operation_id,aggregate_type,aggregate_id,aggregate_version,payload_sha256,summary_json,created_at
            from cloud_sync_operation where owner_id=? and status='PENDING' order by created_at limit 200
            """)) {
            statement.setString(1, owner);
            List<CloudArtifactSyncItem> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new CloudArtifactSyncItem(row.getString(1), row.getString(2),
                    row.getString(3), row.getLong(4), row.getString(5), row.getString(6),
                    Instant.parse(row.getString(7)), 0));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw new SqlTeacherException("CLOUD_SYNC_OUTBOX_LOAD_FAILED", "Failed to load local sync outbox", error);
        }
    }

    private int applyResults(String owner, List<CloudArtifactSyncResult> results) {
        int conflicts = 0;
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            update cloud_sync_operation set status=?,conflict_code=?,updated_at=?
            where owner_id=? and operation_id=? and status='PENDING'
            """)) {
            for (var result : results) {
                boolean conflict = result.status() == CloudArtifactSyncResult.Status.CONFLICT;
                if (conflict) conflicts++;
                statement.setString(1, conflict ? "CONFLICT" : "SYNCED");
                statement.setString(2, result.conflictCode()); statement.setString(3, Instant.now().toString());
                statement.setString(4, owner); statement.setString(5, result.operationId()); statement.addBatch();
            }
            statement.executeBatch();
            return conflicts;
        } catch (SQLException error) {
            throw new SqlTeacherException("CLOUD_SYNC_RESULT_SAVE_FAILED", "Failed to save sync results", error);
        }
    }

    private int importPage(String owner, List<CloudArtifactSyncItem> items) {
        int imported = 0;
        for (CloudArtifactSyncItem item : items) {
            try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
                insert or ignore into cloud_sync_operation(operation_id,owner_id,aggregate_type,aggregate_id,
                    aggregate_version,payload_sha256,summary_json,status,conflict_code,created_at,updated_at)
                values(?,?,?,?,?,?,?,'SYNCED','',?,?)
                """)) {
                statement.setString(1, item.operationId()); statement.setString(2, owner);
                statement.setString(3, item.aggregateType()); statement.setString(4, item.aggregateId());
                statement.setLong(5, item.aggregateVersion()); statement.setString(6, item.payloadSha256());
                statement.setString(7, item.summaryJson()); statement.setString(8, item.occurredAt().toString());
                statement.setString(9, Instant.now().toString());
                imported += statement.executeUpdate();
            } catch (SQLException error) {
                throw new SqlTeacherException("CLOUD_SYNC_IMPORT_FAILED", "Failed to import sync metadata", error);
            }
        }
        return imported;
    }

    private long cursor(String owner) {
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement(
                "select cursor from cloud_sync_cursor where owner_id=?")) {
            statement.setString(1, owner);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        } catch (SQLException error) { throw new SqlTeacherException("CLOUD_SYNC_CURSOR_LOAD_FAILED", "Failed to load cursor", error); }
    }

    private void saveCursor(String owner, long cursor) {
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            insert into cloud_sync_cursor(owner_id,cursor,updated_at) values(?,?,?)
            on conflict(owner_id) do update set cursor=excluded.cursor,updated_at=excluded.updated_at
            """)) {
            statement.setString(1, owner); statement.setLong(2, cursor); statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException error) { throw new SqlTeacherException("CLOUD_SYNC_CURSOR_SAVE_FAILED", "Failed to save cursor", error); }
    }

    private String owner() {
        String owner = owners.currentOwnerId();
        return owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
    }
}
