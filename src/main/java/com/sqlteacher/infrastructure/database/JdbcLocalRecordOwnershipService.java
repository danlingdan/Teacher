package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LocalRecordOwnershipService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * v3.8.0 ACC-D4: guest-owned local learning data and its one-time adoption. Scope is the two
 * tables the learning-sync pipeline owns (learning_events, sql_history); other owner-keyed
 * tables are deliberately untouched. Legacy NULL rows (pre-v3.7.0) stay visible to everyone
 * and are never adopted.
 */
public final class JdbcLocalRecordOwnershipService implements LocalRecordOwnershipService {

    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventOwnerProvider ownerProvider;

    public JdbcLocalRecordOwnershipService(JdbcConnectionFactory connectionFactory,
                                           LearningEventOwnerProvider ownerProvider) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.ownerProvider = Objects.requireNonNull(ownerProvider);
    }

    @Override
    public GuestRecordCount countGuestRecords() {
        long events = count("select count(*) from learning_events where owner=?");
        long history = count("select count(*) from sql_history where owner_id=?");
        return new GuestRecordCount(events, history);
    }

    @Override
    public long mergeGuestRecordsIntoCurrentUser() {
        String owner = ownerProvider.currentOwnerId();
        if (owner == null || owner.isBlank() || LearningEventOwnerProvider.GUEST_OWNER.equals(owner)) {
            throw new IllegalStateException("adopting local records requires a signed-in account");
        }
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement events = connection.prepareStatement(
                     "update learning_events set owner=? where owner=?");
                 PreparedStatement history = connection.prepareStatement(
                     "update sql_history set owner_id=? where owner_id=?")) {
                long merged = 0;
                events.setString(1, owner);
                events.setString(2, LearningEventOwnerProvider.GUEST_OWNER);
                merged += events.executeUpdate();
                history.setString(1, owner);
                history.setString(2, LearningEventOwnerProvider.GUEST_OWNER);
                merged += history.executeUpdate();
                connection.commit();
                return merged;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("local record adoption failed", error);
        }
    }

    private long count(String sql) {
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, LearningEventOwnerProvider.GUEST_OWNER);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("local record count failed", error);
        }
    }
}
