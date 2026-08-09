package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.activity.ActivityEvaluationStatus;
import com.sqlteacher.application.activity.ProjectPortfolioEntry;
import com.sqlteacher.application.activity.ProjectPortfolioService;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owner-filtered portfolio projection. It exports hashes and evaluation metadata, never source content. */
public final class JdbcProjectPortfolioService implements ProjectPortfolioService {
    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventOwnerProvider ownerProvider;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public JdbcProjectPortfolioService(JdbcConnectionFactory connectionFactory,
                                       LearningEventOwnerProvider ownerProvider) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.ownerProvider = Objects.requireNonNull(ownerProvider);
    }

    @Override
    public List<ProjectPortfolioEntry> listOwnEntries() {
        String owner = currentOwner();
        try (var connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 select e.activity_id,d.title,e.status,e.reason_code,e.artifact_hash,e.occurred_at,
                    (select count(*) from activity_evaluation_result earlier
                     where earlier.owner_id=e.owner_id and earlier.activity_id=e.activity_id
                       and earlier.activity_type='PROJECT' and
                       earlier.rowid<=e.rowid) as submission_version
                 from activity_evaluation_result e
                 join learning_activity_definition d on d.id=e.activity_id
                 where e.owner_id=? and e.activity_type='PROJECT'
                 order by e.rowid desc
                 """)) {
            statement.setString(1, owner);
            List<ProjectPortfolioEntry> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    var status = ActivityEvaluationStatus.valueOf(row.getString("status"));
                    result.add(new ProjectPortfolioEntry(row.getString("activity_id"), row.getString("title"),
                        row.getInt("submission_version"), status, row.getString("reason_code"),
                        row.getString("artifact_hash"), Instant.parse(row.getString("occurred_at")),
                        status == ActivityEvaluationStatus.PASSED ? "PENDING_TEACHER_REVIEW" : "REVISION_REQUIRED"));
                }
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw new SqlTeacherException("PORTFOLIO_LOAD_FAILED", "Failed to load own portfolio", error);
        }
    }

    @Override
    public String exportOwnPortfolio(boolean userConfirmed) {
        if (!userConfirmed) throw new SecurityException("PORTFOLIO_EXPORT_CONFIRMATION_REQUIRED");
        try {
            return json.writeValueAsString(Map.of(
                "formatVersion", 1,
                "visibility", "PRIVATE_EXPORT",
                "entries", listOwnEntries()
            ));
        } catch (JsonProcessingException error) {
            throw new SqlTeacherException("PORTFOLIO_EXPORT_FAILED", "Failed to export own portfolio", error);
        }
    }

    private String currentOwner() {
        String owner = ownerProvider.currentOwnerId();
        return owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
    }
}
