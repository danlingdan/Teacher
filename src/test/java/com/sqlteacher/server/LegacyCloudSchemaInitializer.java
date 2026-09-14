package com.sqlteacher.server;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Golden-master fixture for the v3.4.0 REF-5 migrator cutover: verbatim copies of the store
 * initializers as they existed before {@link CloudSchemaMigrator} absorbed them
 * (CloudStoreBase, V14CloudStore, V19CloudStore, V110SupportStore, V111AccountStore).
 * The alignment tests initialize one database through these legacy methods and another
 * through the current stores, then require identical schema objects in sqlite_master.
 */
final class LegacyCloudSchemaInitializer {
    private LegacyCloudSchemaInitializer() { }

    /** Former CloudStoreBase.initialize(): core tables, column patches, backfills, indexes. */
    static void core(Path database) throws SQLException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("pragma foreign_keys=on");
            statement.executeUpdate("create table if not exists users(id text primary key,email text not null unique,"
                + "display_name text not null,password_hash blob not null,password_salt blob not null,"
                + "disabled integer not null default 0,created_at text not null)");
            statement.executeUpdate("create table if not exists user_roles(user_id text not null references users(id),"
                + "role text not null check(role in ('ADMIN','TEACHER','STUDENT')),primary key(user_id,role))");
            statement.executeUpdate("create table if not exists access_tokens(token_hash blob primary key,"
                + "user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
            statement.executeUpdate("create table if not exists refresh_tokens(token_hash blob primary key,"
                + "user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
            statement.executeUpdate("create table if not exists classrooms(id text primary key,name text not null,created_at text not null)");
            statement.executeUpdate("create table if not exists classroom_members(classroom_id text not null references classrooms(id),"
                + "user_id text not null references users(id),role text not null check(role in ('TEACHER','STUDENT')),"
                + "primary key(classroom_id,user_id))");
            statement.executeUpdate("create table if not exists class_assignments(id text primary key,"
                + "classroom_id text not null references classrooms(id),exercise_id text not null,title text not null,"
                + "description text not null default '',created_at text not null,status text not null default 'PUBLISHED',"
                + "due_at text,published_at text,copied_from_assignment_id text,version integer not null default 1,"
                + "updated_at text not null)");
            statement.executeUpdate("create table if not exists sync_events(version integer primary key autoincrement,"
                + "user_id text not null references users(id),event_id text not null,event_type text not null,"
                + "payload_json text not null,occurred_at text not null,unique(user_id,event_id))");
            statement.executeUpdate("create table if not exists assignment_submissions(id text primary key,"
                + "operation_id text not null,classroom_id text not null references classrooms(id),"
                + "assignment_id text not null references class_assignments(id),user_id text not null references users(id),"
                + "attempt_number integer not null,status text not null check(status in ('PASSED','FAILED')),"
                + "result_hash text not null,error_code text,client_completed_at text,submitted_at text not null,"
                + "unique(user_id,operation_id),unique(assignment_id,user_id,attempt_number))");
            statement.executeUpdate("create table if not exists admin_audit(id text primary key,"
                + "actor_user_id text references users(id),action text not null,target_type text not null,"
                + "target_id text,result text not null,reason_code text,correlation_id text not null,"
                + "created_at text not null)");
            statement.executeUpdate("create table if not exists export_audit(id text primary key,"
                + "user_id text not null references users(id),classroom_id text not null references classrooms(id),"
                + "row_count integer not null,created_at text not null,assignment_id text,"
                + "export_type text not null default 'CLASS_ANALYTICS',filter_summary text)");
            statement.executeUpdate("create table if not exists retention_jobs(id text primary key,"
                + "category text not null,cutoff text not null,preview_count integer not null,"
                + "affected_count integer not null default 0,status text not null,confirmation_hash blob not null,"
                + "expires_at text not null,backup_reference text,safety_backup text,created_at text not null,executed_at text,"
                + "restored_at text,actor_user_id text not null references users(id))");
            statement.executeUpdate("create table if not exists retention_archive(job_id text not null "
                + "references retention_jobs(id),category text not null,row_key text not null,payload_json text not null,"
                + "archived_at text not null,primary key(job_id,row_key))");
            addColumnIfMissing(statement, "class_assignments", "status text not null default 'PUBLISHED'");
            addColumnIfMissing(statement, "class_assignments", "due_at text");
            addColumnIfMissing(statement, "class_assignments", "updated_at text");
            addColumnIfMissing(statement, "class_assignments", "description text not null default ''");
            addColumnIfMissing(statement, "class_assignments", "published_at text");
            addColumnIfMissing(statement, "class_assignments", "copied_from_assignment_id text");
            addColumnIfMissing(statement, "class_assignments", "version integer not null default 1");
            addColumnIfMissing(statement, "export_audit", "assignment_id text");
            addColumnIfMissing(statement, "export_audit", "export_type text not null default 'CLASS_ANALYTICS'");
            addColumnIfMissing(statement, "export_audit", "filter_summary text");
            addColumnIfMissing(statement, "retention_jobs", "safety_backup text");
            statement.executeUpdate("update class_assignments set updated_at=created_at where updated_at is null");
            statement.executeUpdate("update class_assignments set description='' where description is null");
            statement.executeUpdate("update class_assignments set version=1 where version is null or version<1");
            statement.executeUpdate("update class_assignments set published_at=created_at "
                + "where published_at is null and status<>'DRAFT'");
            statement.executeUpdate("create index if not exists idx_assignments_class_status "
                + "on class_assignments(classroom_id,status,created_at desc)");
            statement.executeUpdate("create index if not exists idx_submissions_assignment_user "
                + "on assignment_submissions(assignment_id,user_id,submitted_at)");
            statement.executeUpdate("create index if not exists idx_admin_audit_action_time "
                + "on admin_audit(action,created_at desc)");
            statement.executeUpdate("create index if not exists idx_retention_jobs_created "
                + "on retention_jobs(created_at desc)");
        }
    }

    /** Former V14CloudStore.initialize(): v1.4/v1.8.5/v2.0 tables plus the version stamps. */
    static void v14(Path database) throws SQLException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists courses(id text primary key,name text not null,"
                + "description text not null default '',status text not null check(status in ('ACTIVE','INACTIVE')),"
                + "version integer not null,created_by text not null references users(id),created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists course_sections(id text primary key,course_id text not null references courses(id),"
                + "name text not null,sort_order integer not null,status text not null check(status in ('ACTIVE','INACTIVE')),"
                + "version integer not null,created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists knowledge_points(id text primary key,course_id text not null references courses(id),"
                + "section_id text references course_sections(id),name text not null,description text not null default '',"
                + "sort_order integer not null,status text not null check(status in ('ACTIVE','INACTIVE')),version integer not null,"
                + "created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists shared_exercises(id text primary key,course_id text not null references courses(id),"
                + "current_version integer not null,status text not null check(status in ('ACTIVE','INACTIVE')),"
                + "created_by text not null references users(id),created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists shared_exercise_versions(id text primary key,exercise_id text not null references shared_exercises(id),"
                + "course_id text not null references courses(id),version integer not null,title text not null,prompt text not null,"
                + "dataset_version text not null,evaluation_rule text not null,knowledge_point_ids_json text not null,"
                + "content_hash text not null,created_by text not null references users(id),published_at text not null,"
                + "unique(exercise_id,version))");
            statement.executeUpdate("create table if not exists assignment_content_snapshots(assignment_id text primary key references class_assignments(id),"
                + "exercise_version_id text not null references shared_exercise_versions(id),title text not null,prompt text not null,"
                + "dataset_version text not null,evaluation_rule text not null,knowledge_point_ids_json text not null,"
                + "snapshot_hash text not null,created_at text not null)");
            statement.executeUpdate("create table if not exists submission_feedback(submission_id text primary key references assignment_submissions(id),"
                + "assignment_id text not null references class_assignments(id),student_user_id text not null references users(id),"
                + "status text not null check(status in ('NEEDS_WORK','REVIEWED','RESOLVED')),comment text not null,"
                + "knowledge_point_ids_json text not null,version integer not null,author_user_id text not null references users(id),updated_at text not null)");
            statement.executeUpdate("create table if not exists feedback_audit(id text primary key,submission_id text not null references assignment_submissions(id),"
                + "actor_user_id text not null references users(id),status text not null,version integer not null,created_at text not null)");
            statement.executeUpdate("create table if not exists cloud_notifications(id text primary key,recipient_user_id text not null references users(id),"
                + "type text not null,resource_type text not null,resource_id text not null,title text not null,message text not null,"
                + "idempotency_key text not null,read_at text,created_at text not null,unique(recipient_user_id,idempotency_key))");
            statement.executeUpdate("create table if not exists v14_operations(actor_user_id text not null references users(id),"
                + "operation_id text not null,resource_type text not null,resource_id text not null,created_at text not null,"
                + "primary key(actor_user_id,operation_id))");
            statement.executeUpdate("create table if not exists cloud_schema_version(version integer primary key,"
                + "description text not null,applied_at text not null)");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(1,'v1.3 cloud baseline',current_timestamp)");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(2,'v1.4 course content and feedback',current_timestamp)");
            statement.executeUpdate("create table if not exists cloud_knowledge_articles(id text primary key,course_id text not null references courses(id),"
                + "section_id text references course_sections(id),title text not null,visibility text not null check(visibility in ('PRIVATE','PUBLISHED','INACTIVE')),"
                + "current_revision integer not null,content_hash text not null,created_by text not null references users(id),created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists cloud_knowledge_revisions(id text primary key,article_id text not null references cloud_knowledge_articles(id) on delete cascade,"
                + "revision integer not null,content text not null,content_hash text not null,created_by text not null references users(id),created_at text not null,unique(article_id,revision))");
            statement.executeUpdate("create table if not exists cloud_knowledge_chunks(id text primary key,article_id text not null references cloud_knowledge_articles(id) on delete cascade,"
                + "revision_id text not null references cloud_knowledge_revisions(id) on delete cascade,chunk_index integer not null,content text not null,content_hash text not null,"
                + "index_status text not null check(index_status in ('PENDING','INDEXED','FAILED')),unique(revision_id,chunk_index))");
            statement.executeUpdate("create table if not exists cloud_knowledge_sync_cursor(user_id text not null references users(id),course_id text not null references courses(id),"
                + "cursor_version integer not null,updated_at text not null,primary key(user_id,course_id))");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(3,'v1.8.5 shared knowledge and vector indexing metadata',current_timestamp)");
            statement.executeUpdate("create table if not exists cloud_knowledge_index_outbox(id text primary key,"
                + "chunk_id text not null unique references cloud_knowledge_chunks(id) on delete cascade,operation text not null check(operation in ('UPSERT','DELETE')),"
                + "status text not null check(status in ('PENDING','FAILED','COMPLETED')),attempts integer not null default 0,next_attempt_at text not null,"
                + "last_error text,created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists cloud_knowledge_embedding_profile(id integer primary key check(id=1),"
                + "provider text not null,model text not null,dimension integer not null,index_version integer not null,updated_at text not null)");
            statement.executeUpdate("insert or ignore into cloud_knowledge_index_outbox(id,chunk_id,operation,status,attempts,next_attempt_at,created_at,updated_at) "
                + "select lower(hex(randomblob(16))),c.id,'UPSERT','PENDING',0,current_timestamp,current_timestamp,current_timestamp "
                + "from cloud_knowledge_chunks c join cloud_knowledge_articles a on a.id=c.article_id "
                + "join cloud_knowledge_revisions r on r.id=c.revision_id and r.revision=a.current_revision");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(4,'v1.8.5 qdrant embedding outbox and profile',current_timestamp)");
            statement.executeUpdate("create index if not exists idx_course_sections_order on course_sections(course_id,sort_order,id)");
            statement.executeUpdate("create index if not exists idx_knowledge_points_order on knowledge_points(course_id,sort_order,id)");
            statement.executeUpdate("create index if not exists idx_shared_exercise_course on shared_exercises(course_id,status,updated_at desc)");
            statement.executeUpdate("create index if not exists idx_feedback_assignment on submission_feedback(assignment_id,updated_at desc)");
            statement.executeUpdate("create index if not exists idx_notification_recipient on cloud_notifications(recipient_user_id,created_at desc)");
            statement.executeUpdate("create index if not exists idx_cloud_knowledge_scope on cloud_knowledge_articles(course_id,visibility,updated_at desc)");
            statement.executeUpdate("create index if not exists idx_cloud_knowledge_chunks on cloud_knowledge_chunks(article_id,revision_id,chunk_index)");
            statement.executeUpdate("create index if not exists idx_cloud_knowledge_outbox_pending on cloud_knowledge_index_outbox(status,next_attempt_at,created_at)");
            statement.executeUpdate("create table if not exists v20_course_package_operations(actor_user_id text not null references users(id),"
                + "operation_id text not null,package_id text not null,course_id text not null references courses(id),course_version text not null,"
                + "content_sha256 text not null,license text not null,created_at text not null,primary key(actor_user_id,operation_id),"
                + "unique(actor_user_id,package_id,course_version))");
            statement.executeUpdate("create table if not exists v20_artifact_sync(cursor integer primary key autoincrement,"
                + "actor_user_id text not null references users(id),operation_id text not null,aggregate_type text not null,aggregate_id text not null,"
                + "aggregate_version integer not null,payload_sha256 text not null,summary_json text not null,occurred_at text not null,"
                + "unique(actor_user_id,operation_id),unique(actor_user_id,aggregate_type,aggregate_id,aggregate_version))");
            statement.executeUpdate("create index if not exists idx_v20_artifact_sync_owner_cursor on v20_artifact_sync(actor_user_id,cursor)");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(6,'v2.0 secure course packages and capability sync',current_timestamp)");
        }
    }

    /** Former V19CloudStore.initialize(): planning tables plus the version-5 stamp and future guard. */
    static void v19(Path database) throws SQLException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery("select coalesce(max(version),0) from cloud_schema_version")) {
                if (row.next() && row.getInt(1) > 6) {
                    throw new SQLException("Cloud database schema is newer than this SQLTeacher version");
                }
            }
            statement.executeUpdate("""
                create table if not exists course_objectives(
                    id text primary key,course_id text not null references courses(id),title text not null,
                    description text not null,completion_criteria text not null,sort_order integer not null,
                    status text not null check(status in ('ACTIVE','INACTIVE')),version integer not null,
                    created_by text not null references users(id),created_at text not null,updated_at text not null)
                """);
            statement.executeUpdate("create index if not exists idx_course_objectives_order on course_objectives(course_id,status,sort_order,id)");
            statement.executeUpdate("""
                create table if not exists objective_prerequisites(
                    objective_id text not null references course_objectives(id) on delete cascade,
                    prerequisite_objective_id text not null references course_objectives(id) on delete cascade,
                    created_at text not null,primary key(objective_id,prerequisite_objective_id),
                    check(objective_id<>prerequisite_objective_id))
                """);
            statement.executeUpdate("""
                create table if not exists objective_resource_links(
                    objective_id text not null references course_objectives(id) on delete cascade,
                    resource_type text not null check(resource_type in ('KNOWLEDGE_POINT','KNOWLEDGE_ARTICLE','EXERCISE_VERSION')),
                    resource_id text not null,created_at text not null,
                    primary key(objective_id,resource_type,resource_id))
                """);
            statement.executeUpdate("""
                create table if not exists teaching_cycles(
                    id text primary key,course_id text not null references courses(id),classroom_id text,
                    phase text not null check(phase in ('BEFORE_CLASS','IN_CLASS','AFTER_CLASS','REVIEW')),
                    status text not null check(status in ('DRAFT','PUBLISHED','CLOSED')),version integer not null,
                    created_by text not null references users(id),created_at text not null,updated_at text not null)
                """);
            statement.executeUpdate("""
                create table if not exists study_plan_action_state(
                    owner_id text not null references users(id),action_id text not null,course_id text not null,
                    requested_state text not null check(requested_state in ('STARTED','COMPLETED','DISMISSED')),
                    version integer not null,updated_at text not null,primary key(owner_id,action_id))
                """);
            statement.executeUpdate("""
                create table if not exists study_plan_sync_operations(
                    owner_id text not null references users(id),operation_id text not null,status text not null,
                    created_at text not null,primary key(owner_id,operation_id))
                """);
            statement.executeUpdate("""
                create table if not exists intervention_audit_v2(
                    id text primary key,classroom_id text not null,student_user_id text not null,objective_id text not null,
                    reason_code text not null,action text not null,actor_user_id text not null,created_at text not null)
                """);
            statement.executeUpdate("""
                create table if not exists tutor_feedback_summary(
                    owner_id text not null references users(id),objective_id text not null,feedback_type text not null,
                    feedback_count integer not null,updated_at text not null,primary key(owner_id,objective_id,feedback_type))
                """);
            statement.executeUpdate("""
                create table if not exists objective_intervention_drafts(
                    id text primary key,classroom_id text not null references classrooms(id),
                    course_id text not null references courses(id),objective_id text not null references course_objectives(id),
                    reason_code text not null,action text not null,impact_count integer not null,
                    objective_version integer not null,confirmation_token_hash text not null,
                    status text not null check(status in ('DRAFT','CONFIRMED','EXPIRED')),
                    created_by text not null references users(id),created_at text not null,confirmed_at text)
                """);
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(5,'v1.9 course objectives and deterministic study planning',current_timestamp)");
        }
    }

    /** Former V110SupportStore.initialize(): problem-report tables and indexes. */
    static void v110(Path database) throws SQLException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists problem_reports(id text primary key,principal_key text not null,user_id text,idempotency_key text not null,install_id_hash text not null,type text not null,severity text not null,summary text not null,description text not null,reproduction_steps text not null,expected_result text not null,actual_result text not null,application_json text not null,diagnostics_json text not null,status text not null,query_token_hash text not null,created_at text not null,updated_at text not null,expires_at text not null,unique(principal_key,idempotency_key))");
            statement.executeUpdate("create table if not exists problem_report_contacts(report_id text primary key references problem_reports(id) on delete cascade,contact text not null)");
            statement.executeUpdate("create table if not exists problem_report_screenshots(report_id text primary key references problem_reports(id) on delete cascade,mime_type text not null,filename text not null,data_blob blob not null,created_at text not null)");
            statement.executeUpdate("create table if not exists problem_report_status_history(id integer primary key autoincrement,report_id text not null references problem_reports(id) on delete cascade,status text not null,reason_code text not null,created_at text not null)");
            statement.executeUpdate("create index if not exists idx_problem_reports_status_time on problem_reports(status,created_at desc)");
            statement.executeUpdate("create index if not exists idx_problem_reports_expiry on problem_reports(expires_at)");
        }
    }

    /** Former V111AccountStore.initialize(): account-lifecycle tables, indexes, column patches. */
    static void v111(Path database) throws SQLException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists users(id text primary key,email text unique not null,display_name text not null,password_hash blob not null,password_salt blob not null,disabled integer not null default 0,created_at text not null,email_verified integer not null default 0)");
            statement.executeUpdate("create table if not exists access_tokens(token_hash blob primary key,user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text,device_label text,last_seen_at text)");
            statement.executeUpdate("create table if not exists refresh_tokens(token_hash blob primary key,user_id text not null references users(id),expires_at text not null,created_at text not null,revoked_at text)");
            statement.executeUpdate("create table if not exists email_verifications(id text primary key,user_id text not null references users(id),email text not null,token_hash blob not null,created_at text not null,expires_at text not null,used_at text)");
            statement.executeUpdate("create table if not exists reset_tokens(id text primary key,user_id text not null references users(id),token_hash blob not null,created_at text not null,expires_at text not null,used_at text,attempts integer not null default 0)");
            statement.executeUpdate("create table if not exists account_tasks(id text primary key,user_id text not null references users(id),kind text not null,status text not null,payload_json text,cancel_before text,created_at text not null,updated_at text not null)");
            statement.executeUpdate("create index if not exists idx_reset_tokens_user on reset_tokens(user_id)");
            statement.executeUpdate("create index if not exists idx_account_tasks_user on account_tasks(user_id,kind)");
            addColumnIfMissing(connection, "access_tokens", "device_label", "text");
            addColumnIfMissing(connection, "access_tokens", "last_seen_at", "text");
            addColumnIfMissing(connection, "users", "email_verified", "integer not null default 0");
        }
    }

    private static void addColumnIfMissing(Statement statement, String table, String definition) throws SQLException {
        String column = definition.split("\\s+", 2)[0];
        boolean exists = false;
        try (ResultSet row = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (row.next()) {
                if (column.equalsIgnoreCase(row.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) statement.executeUpdate("alter table " + table + " add column " + definition);
    }

    private static void addColumnIfMissing(Connection connection, String table, String column,
                                           String definition) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            if (columns.next()) return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
        }
    }

    private static Connection open(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("pragma foreign_keys=on");
            statement.executeUpdate("pragma busy_timeout=5000");
        }
        return connection;
    }
}
