package com.sqlteacher.infrastructure.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class SqliteSchemaMigrator {
    private static final List<Migration> DEFAULT_MIGRATIONS = List.of(
        new Migration(
            1,
            "Create the Demo baseline application tables",
            List.of(
                """
                    create table if not exists app_event (
                        id integer primary key autoincrement,
                        event_type text not null,
                        message text,
                        created_at text not null default current_timestamp
                    )
                    """,
                """
                    create table if not exists learning_events (
                        id integer primary key autoincrement,
                        event_type text not null,
                        occurred_at text not null,
                        connection_id text not null,
                        successful integer not null,
                        attributes text,
                        created_at text not null default current_timestamp
                    )
                    """
            )
        ),
        new Migration(
            2,
            "Create database connection profile tables",
            List.of(
                """
                    create table connection_profiles (
                        id text primary key,
                        display_name text not null,
                        dialect text not null check (dialect in ('SQLITE', 'MYSQL', 'MARIADB')),
                        sqlite_path text,
                        host text,
                        port integer,
                        database_name text,
                        username text,
                        read_only integer not null check (read_only in (0, 1)),
                        enabled integer not null check (enabled in (0, 1)),
                        built_in integer not null check (built_in in (0, 1)),
                        created_at text not null default current_timestamp,
                        updated_at text not null default current_timestamp,
                        check (
                            (dialect = 'SQLITE' and sqlite_path is not null
                                and host is null and port is null and database_name is null and username is null)
                            or
                            (dialect in ('MYSQL', 'MARIADB') and sqlite_path is null
                                and host is not null and port between 1 and 65535
                                and database_name is not null and username is not null)
                        )
                    )
                    """,
                """
                    create table connection_selection (
                        singleton_id integer primary key check (singleton_id = 1),
                        connection_id text not null,
                        updated_at text not null default current_timestamp
                    )
                """
            )
        ),
        new Migration(
            3,
            "Create exercise catalog and attempt tables",
            List.of(
                """
                    create table exercise_datasets (
                        id text primary key,
                        name text not null,
                        setup_sql text not null,
                        version integer not null check (version > 0),
                        created_at text not null default current_timestamp,
                        updated_at text not null default current_timestamp
                    )
                    """,
                """
                    create table exercises (
                        id text primary key,
                        title text not null,
                        description text not null,
                        knowledge_point text not null,
                        difficulty text not null check (
                            difficulty in ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')
                        ),
                        dataset_id text not null references exercise_datasets(id),
                        reference_sql text not null,
                        evaluation_rule_json text not null,
                        hints_json text not null,
                        version integer not null check (version > 0),
                        enabled integer not null check (enabled in (0, 1)),
                        created_at text not null default current_timestamp,
                        updated_at text not null default current_timestamp
                    )
                    """,
                """
                    create index exercises_enabled_order
                    on exercises(enabled, difficulty, knowledge_point, title)
                    """,
                """
                    create table exercise_sessions (
                        id text primary key,
                        exercise_id text not null references exercises(id),
                        exercise_version integer not null check (exercise_version > 0),
                        started_at text not null,
                        completed_at text,
                        hints_used integer not null default 0 check (hints_used between 0 and 3)
                    )
                    """,
                """
                    create index exercise_sessions_exercise_started
                    on exercise_sessions(exercise_id, started_at)
                    """,
                """
                    create table exercise_attempts (
                        id text primary key,
                        session_id text not null references exercise_sessions(id),
                        status text not null check (
                            status in ('RUN', 'SUBMITTED', 'PASSED', 'FAILED')
                        ),
                        sql_text text not null,
                        execution_success integer check (execution_success in (0, 1)),
                        passed integer check (passed in (0, 1)),
                        duration_ms integer not null check (duration_ms >= 0),
                        result_columns_json text not null default '[]',
                        result_rows_json text not null default '[]',
                        feedback_json text not null default '[]',
                        error_code text,
                        created_at text not null
                    )
                    """,
                """
                    create index exercise_attempts_session_created
                    on exercise_attempts(session_id, created_at)
                    """
            )
        ),
        new Migration(
            4,
            "Create local knowledge document and FTS5 index tables",
            List.of(
                """
                    create table knowledge_documents (
                        id text primary key,
                        title text not null,
                        source_name text not null,
                        content_hash text not null unique,
                        chunk_count integer not null check (chunk_count > 0),
                        imported_at text not null
                    )
                    """,
                """
                    create table knowledge_chunks (
                        id text primary key,
                        document_id text not null references knowledge_documents(id) on delete cascade,
                        chunk_index integer not null check (chunk_index >= 0),
                        content text not null,
                        unique(document_id, chunk_index)
                    )
                    """,
                """
                    create virtual table knowledge_chunks_fts using fts5(
                        content,
                        content='knowledge_chunks',
                        content_rowid='rowid',
                        tokenize='unicode61 remove_diacritics 2'
                    )
                    """,
                """
                    create trigger knowledge_chunks_ai after insert on knowledge_chunks begin
                        insert into knowledge_chunks_fts(rowid, content) values (new.rowid, new.content);
                    end
                    """,
                """
                    create trigger knowledge_chunks_ad after delete on knowledge_chunks begin
                        insert into knowledge_chunks_fts(knowledge_chunks_fts, rowid, content)
                        values ('delete', old.rowid, old.content);
                    end
                    """,
                """
                    create trigger knowledge_chunks_au after update on knowledge_chunks begin
                        insert into knowledge_chunks_fts(knowledge_chunks_fts, rowid, content)
                        values ('delete', old.rowid, old.content);
                        insert into knowledge_chunks_fts(rowid, content) values (new.rowid, new.content);
                    end
                    """,
                "create index knowledge_chunks_document_order on knowledge_chunks(document_id, chunk_index)"
            )
        ),
        new Migration(
            5,
            "Create account-isolated assignment submission queue",
            List.of(
                """
                    create table assignment_submission_queue (
                        operation_id text primary key,
                        account_id text not null,
                        classroom_id text not null,
                        assignment_id text not null,
                        passed integer not null check (passed in (0, 1)),
                        result_hash text not null,
                        error_code text,
                        client_completed_at text not null,
                        status text not null check (status in ('QUEUED', 'DELIVERED', 'REJECTED')),
                        retry_count integer not null default 0 check (retry_count >= 0),
                        next_retry_at text not null,
                        last_error_type text,
                        created_at text not null,
                        updated_at text not null
                    )
                    """,
                """
                    create index assignment_submission_queue_account_status_retry
                    on assignment_submission_queue(account_id, status, next_retry_at)
                    """
            )
        ),
        new Migration(
            6,
            "Create account-isolated v1.4 teaching content cache",
            List.of(
                """
                    create table teaching_content_cache (
                        account_id text not null,
                        cache_key text not null,
                        payload_json text not null,
                        updated_at text not null,
                        primary key(account_id, cache_key)
                    )
                    """,
                """
                    create index teaching_content_cache_updated
                    on teaching_content_cache(account_id, updated_at desc)
                    """
            )
        ),
        new Migration(
            7,
            "Create v1.7 deterministic learning diagnosis state",
            List.of(
                "alter table exercise_sessions add column owner_id text not null default 'guest'",
                "create index exercise_sessions_owner_active on exercise_sessions(owner_id, completed_at, started_at desc)",
                """
                    create table mastery_snapshot (
                        owner_id text not null,
                        knowledge_point text not null,
                        level text not null check (level in ('UNKNOWN','NEEDS_PRACTICE','DEVELOPING','MASTERED')),
                        attempts integer not null check (attempts >= 0),
                        passes integer not null check (passes >= 0),
                        failures integer not null check (failures >= 0),
                        hints_used integer not null check (hints_used >= 0),
                        mastery_percent integer not null check (mastery_percent between 0 and 100),
                        reason_codes text not null,
                        evidence_hash text not null,
                        policy_version text not null,
                        updated_at text not null,
                        primary key(owner_id, knowledge_point, policy_version)
                    )
                    """,
                "create index mastery_snapshot_owner_level on mastery_snapshot(owner_id, level, updated_at desc)",
                """
                    create table learning_action_state (
                        action_id text primary key,
                        owner_id text not null,
                        state text not null check (state in ('OPEN','DISMISSED')),
                        updated_at text not null
                    )
                    """,
                "create index learning_action_state_owner on learning_action_state(owner_id, state, updated_at desc)",
                """
                    create table intervention_state (
                        candidate_id text primary key,
                        status text not null check (status in ('OPEN','ACKNOWLEDGED','RESOLVED','DISMISSED')),
                        updated_at text not null
                    )
                    """
            )
        ),
        new Migration(
            8,
            "Create v1.8 course knowledge articles and revision history",
            List.of(
                """
                    create table course_knowledge_articles (
                        id text primary key,
                        document_id text not null unique,
                        owner_id text not null,
                        course_title text not null,
                        section_title text not null,
                        visibility text not null check (visibility in ('PRIVATE','PUBLISHED','INACTIVE')),
                        current_revision integer not null check (current_revision > 0),
                        created_at text not null,
                        updated_at text not null,
                        foreign key(document_id) references knowledge_documents(id) on delete cascade
                    )
                    """,
                "create index course_knowledge_articles_scope on course_knowledge_articles(owner_id, course_title, section_title, visibility, updated_at desc)",
                """
                    create table course_knowledge_revisions (
                        id text primary key,
                        article_id text not null,
                        revision integer not null check (revision > 0),
                        title text not null,
                        content text not null,
                        content_hash text not null,
                        source_name text not null,
                        heading_path text not null,
                        created_at text not null,
                        unique(article_id, revision),
                        foreign key(article_id) references course_knowledge_articles(id) on delete cascade
                    )
                    """,
                "create index course_knowledge_revisions_article on course_knowledge_revisions(article_id, revision desc)",
                """
                    create table course_knowledge_point_links (
                        revision_id text not null,
                        knowledge_point text not null,
                        primary key(revision_id, knowledge_point),
                        foreign key(revision_id) references course_knowledge_revisions(id) on delete cascade
                    )
                    """,
                "create index course_knowledge_point_lookup on course_knowledge_point_links(knowledge_point, revision_id)"
            )
        ),
        new Migration(
            9,
            "Create v1.8.5 hybrid retrieval, indexing, read state, and feedback tables",
            List.of(
                """
                    create table knowledge_chunks_v2 (
                        id text primary key,
                        document_id text not null,
                        article_id text not null,
                        revision_id text not null,
                        chunk_index integer not null check (chunk_index >= 0),
                        parent_chunk_id text,
                        heading_path text not null default '',
                        start_offset integer not null default 0 check (start_offset >= 0),
                        end_offset integer not null check (end_offset >= start_offset),
                        token_count integer not null check (token_count > 0),
                        content text not null,
                        content_hash text not null,
                        index_status text not null default 'PENDING' check (index_status in ('PENDING','INDEXED','FAILED')),
                        unique(revision_id, chunk_index),
                        foreign key(document_id) references knowledge_documents(id) on delete cascade,
                        foreign key(article_id) references course_knowledge_articles(id) on delete cascade,
                        foreign key(revision_id) references course_knowledge_revisions(id) on delete cascade
                    )
                    """,
                "create index knowledge_chunks_v2_scope on knowledge_chunks_v2(article_id, revision_id, chunk_index)",
                """
                    create table knowledge_index_jobs (
                        id text primary key,
                        article_id text not null,
                        revision_id text not null,
                        status text not null check (status in ('PENDING','RUNNING','COMPLETED','FAILED')),
                        attempt_count integer not null default 0 check (attempt_count >= 0),
                        error_message text,
                        created_at text not null,
                        updated_at text not null,
                        foreign key(article_id) references course_knowledge_articles(id) on delete cascade,
                        foreign key(revision_id) references course_knowledge_revisions(id) on delete cascade
                    )
                    """,
                "create index knowledge_index_jobs_status on knowledge_index_jobs(status, updated_at)",
                """
                    create table knowledge_embedding_profiles (
                        profile_id text primary key,
                        provider text not null,
                        model text not null,
                        dimensions integer not null check (dimensions > 0),
                        content_fingerprint text not null,
                        updated_at text not null
                    )
                    """,
                """
                    create table knowledge_read_state (
                        owner_id text not null,
                        article_id text not null,
                        revision integer not null check (revision > 0),
                        progress_percent integer not null check (progress_percent between 0 and 100),
                        last_read_at text not null,
                        primary key(owner_id, article_id),
                        foreign key(article_id) references course_knowledge_articles(id) on delete cascade
                    )
                    """,
                """
                    create table knowledge_web_sources (
                        id text primary key,
                        owner_id text not null,
                        query_text text not null,
                        title text not null,
                        source_url text not null,
                        content_hash text not null,
                        fetched_at text not null,
                        expires_at text not null
                    )
                    """,
                "create index knowledge_web_sources_owner_expiry on knowledge_web_sources(owner_id, expires_at)",
                """
                    create table retrieval_feedback (
                        id text primary key,
                        owner_id text not null,
                        query_hash text not null,
                        document_id text not null,
                        chunk_index integer not null,
                        helpful integer not null check (helpful in (0, 1)),
                        created_at text not null
                    )
                    """,
                """
                    insert into knowledge_chunks_v2(
                        id, document_id, article_id, revision_id, chunk_index, heading_path,
                        start_offset, end_offset, token_count, content, content_hash, index_status
                    )
                    select c.id, c.document_id, a.id, r.id, c.chunk_index, r.heading_path,
                        0, length(c.content), max(1, (length(c.content) + 3) / 4), c.content,
                        lower(hex(cast(c.content as blob))), 'PENDING'
                    from knowledge_chunks c
                    join course_knowledge_articles a on a.document_id = c.document_id
                    join course_knowledge_revisions r on r.article_id = a.id and r.revision = a.current_revision
                    """,
                """
                    insert into knowledge_index_jobs(id, article_id, revision_id, status, created_at, updated_at)
                    select lower(hex(randomblob(16))), a.id, r.id, 'PENDING', current_timestamp, current_timestamp
                    from course_knowledge_articles a
                    join course_knowledge_revisions r on r.article_id = a.id and r.revision = a.current_revision
                    """
            )
        ),
        new Migration(
            10,
            "Create v1.9 course objective and deterministic study plan state",
            List.of(
                """
                    create table course_objective_cache (
                        account_id text not null,
                        course_id text not null,
                        objective_id text not null,
                        title text not null,
                        description text not null,
                        completion_criteria text not null,
                        sort_order integer not null check (sort_order >= 0),
                        status text not null check (status in ('ACTIVE','INACTIVE')),
                        version integer not null check (version > 0),
                        updated_at text not null,
                        primary key(account_id, course_id, objective_id)
                    )
                    """,
                "create index course_objective_cache_scope on course_objective_cache(account_id, course_id, status, sort_order)",
                """
                    create table objective_resource_cache (
                        account_id text not null,
                        course_id text not null,
                        objective_id text not null,
                        resource_type text not null check (resource_type in ('KNOWLEDGE_POINT','KNOWLEDGE_ARTICLE','EXERCISE_VERSION')),
                        resource_id text not null,
                        updated_at text not null,
                        primary key(account_id, course_id, objective_id, resource_type, resource_id)
                    )
                    """,
                """
                    create table study_plan_snapshot (
                        id text primary key,
                        owner_id text not null,
                        course_id text not null,
                        policy_version text not null,
                        fact_watermark text not null,
                        generated_at text not null,
                        expires_at text not null,
                        status text not null check (status in ('ACTIVE','EXPIRED','INVALIDATED'))
                    )
                    """,
                "create index study_plan_snapshot_owner on study_plan_snapshot(owner_id, course_id, status, generated_at desc)",
                """
                    create table study_plan_action (
                        id text primary key,
                        snapshot_id text not null references study_plan_snapshot(id) on delete cascade,
                        action_key text not null,
                        objective_id text not null,
                        action_type text not null check (action_type in ('REVIEW_KNOWLEDGE','PRACTICE_EXERCISE')),
                        title text not null,
                        description text not null,
                        resource_type text not null check (resource_type in ('KNOWLEDGE_POINT','KNOWLEDGE_ARTICLE','EXERCISE_VERSION')),
                        resource_id text not null,
                        reason_code text not null,
                        resolution_condition text not null,
                        priority integer not null check (priority between 1 and 100),
                        state text not null check (state in ('OPEN','STARTED','COMPLETED','DISMISSED','INVALIDATED')),
                        sync_version integer not null default 0 check (sync_version >= 0),
                        updated_at text not null
                    )
                    """,
                "create index study_plan_action_snapshot_order on study_plan_action(snapshot_id, state, priority desc, id)",
                "create unique index study_plan_action_key on study_plan_action(snapshot_id, action_key)",
                """
                    create table study_plan_evidence (
                        action_id text not null references study_plan_action(id) on delete cascade,
                        evidence_type text not null,
                        evidence_id text not null,
                        evidence_version text not null,
                        evidence_hash text not null,
                        primary key(action_id, evidence_type, evidence_id)
                    )
                    """,
                """
                    create table study_plan_outbox (
                        operation_id text primary key,
                        owner_id text not null,
                        action_id text not null,
                        requested_state text not null check (requested_state in ('STARTED','COMPLETED','DISMISSED')),
                        status text not null check (status in ('PENDING','DELIVERED','REJECTED')),
                        attempt_count integer not null default 0 check (attempt_count >= 0),
                        next_attempt_at text not null,
                        last_error_code text,
                        created_at text not null,
                        updated_at text not null
                    )
                    """,
                "create index study_plan_outbox_pending on study_plan_outbox(owner_id, status, next_attempt_at)",
                """
                    create table grounded_tutor_session (
                        id text primary key,
                        owner_id text not null,
                        course_id text not null,
                        objective_id text not null,
                        retrieval_snapshot_hash text not null,
                        provider text not null,
                        model text not null,
                        result_code text not null,
                        degraded integer not null check (degraded in (0, 1)),
                        created_at text not null
                    )
                    """,
                """
                    create table grounded_tutor_feedback (
                        session_id text primary key references grounded_tutor_session(id) on delete cascade,
                        feedback_type text not null check (feedback_type in ('HELPFUL','CITATION_ERROR','STILL_CONFUSED','INAPPROPRIATE_ANSWER')),
                        note text not null default '',
                        created_at text not null
                    )
                    """
            )
        ),
        new Migration(
            11,
            "Create v2 alpha.1 course graph, activity, evaluation, and evidence skeleton",
            List.of(
                """
                    create table course_definition (
                        id text primary key,
                        version text not null,
                        title text not null,
                        language text not null,
                        license text not null,
                        maintainer text not null,
                        visibility text not null check (visibility in ('PRIVATE','PUBLISHED','INACTIVE')),
                        created_at text not null,
                        updated_at text not null
                    )
                    """,
                """
                    create table course_section (
                        id text primary key,
                        course_id text not null references course_definition(id) on delete cascade,
                        title text not null,
                        sort_order integer not null check (sort_order >= 0),
                        unlock_policy text not null default 'OPEN',
                        unique(course_id, sort_order)
                    )
                    """,
                """
                    create table learning_outcome (
                        id text primary key,
                        course_id text not null references course_definition(id) on delete cascade,
                        description text not null,
                        expected_level text not null,
                        sort_order integer not null check (sort_order >= 0),
                        unique(course_id, sort_order)
                    )
                    """,
                """
                    create table knowledge_point_definition (
                        id text primary key,
                        course_id text not null references course_definition(id) on delete cascade,
                        name text not null,
                        aliases_json text not null default '[]',
                        cs2023_mappings_json text not null default '[]',
                        unique(course_id, name)
                    )
                    """,
                """
                    create table knowledge_point_relation (
                        course_id text not null references course_definition(id) on delete cascade,
                        source_id text not null references knowledge_point_definition(id) on delete cascade,
                        target_id text not null references knowledge_point_definition(id) on delete cascade,
                        relation_type text not null check (relation_type in ('PREREQUISITE','RELATED','TRANSFER')),
                        primary key(source_id, target_id, relation_type),
                        check (source_id <> target_id)
                    )
                    """,
                """
                    create table learning_activity_definition (
                        id text primary key,
                        course_id text not null references course_definition(id),
                        section_id text not null references course_section(id),
                        activity_type text not null check (activity_type in (
                            'SQL','QUIZ','TRACE','SIMULATION','CODE','LAB','PROJECT','READING'
                        )),
                        title text not null,
                        description text not null,
                        difficulty text not null check (difficulty in ('BEGINNER','INTERMEDIATE','ADVANCED')),
                        estimated_minutes integer not null check (estimated_minutes > 0),
                        definition_version integer not null check (definition_version > 0),
                        specification_format_version integer not null check (specification_format_version > 0),
                        specification_json text not null,
                        source_kind text not null,
                        source_id text not null,
                        enabled integer not null check (enabled in (0, 1)),
                        created_at text not null,
                        updated_at text not null,
                        unique(source_kind, source_id)
                    )
                    """,
                "create index learning_activity_course_section on learning_activity_definition(course_id, section_id, enabled, activity_type)",
                """
                    create table activity_session (
                        id text primary key,
                        owner_id text not null,
                        activity_id text not null references learning_activity_definition(id),
                        activity_version integer not null check (activity_version > 0),
                        status text not null check (status in ('STARTED','PAUSED','COMPLETED','CLOSED')),
                        source_kind text not null,
                        source_id text not null,
                        started_at text not null,
                        updated_at text not null,
                        unique(source_kind, source_id)
                    )
                    """,
                "create index activity_session_owner_status on activity_session(owner_id, status, updated_at desc)",
                """
                    create table activity_knowledge_point (
                        activity_id text not null references learning_activity_definition(id) on delete cascade,
                        knowledge_point_id text not null references knowledge_point_definition(id) on delete cascade,
                        primary key(activity_id, knowledge_point_id)
                    )
                    """,
                """
                    create table activity_evaluation_result (
                        id text primary key,
                        owner_id text not null,
                        activity_id text not null references learning_activity_definition(id),
                        activity_version integer not null check (activity_version > 0),
                        activity_type text not null,
                        status text not null check (status in ('PASSED','FAILED','REJECTED','ERROR')),
                        reason_code text not null default '',
                        criteria_json text not null,
                        evidence_summary_json text not null,
                        evaluator_version text not null,
                        evidence_version text not null,
                        duration_ms integer not null check (duration_ms >= 0),
                        artifact_hash text not null,
                        occurred_at text not null
                    )
                    """,
                "create index activity_evaluation_owner_activity on activity_evaluation_result(owner_id, activity_id, occurred_at desc)",
                "alter table learning_events add column activity_id text",
                "alter table learning_events add column activity_type text",
                "alter table learning_events add column evaluator_version text",
                "alter table learning_events add column evidence_version text",
                "alter table learning_events add column reason_code text",
                """
                    insert into course_definition(
                        id, version, title, language, license, maintainer, visibility, created_at, updated_at
                    ) values (
                        'builtin-data-management', '1', '数据管理', 'zh-CN',
                        'SQLTeacher built-in content', 'SQLTeacher', 'PUBLISHED', current_timestamp, current_timestamp
                    )
                    """,
                """
                    insert into course_section(id, course_id, title, sort_order)
                    values ('sql-practice', 'builtin-data-management', 'SQL 练习', 0)
                    """,
                """
                    insert into knowledge_point_definition(id, course_id, name)
                    select 'legacy-sql:' || lower(hex(cast(knowledge_point as blob))),
                        'builtin-data-management', knowledge_point
                    from exercises
                    group by knowledge_point
                    """,
                """
                    insert into learning_activity_definition(
                        id, course_id, section_id, activity_type, title, description, difficulty,
                        estimated_minutes, definition_version, specification_format_version,
                        specification_json, source_kind, source_id, enabled, created_at, updated_at
                    )
                    select id, 'builtin-data-management', 'sql-practice', 'SQL', title, description, difficulty,
                        15, version, 1, '{}', 'SQL_EXERCISE', id, enabled, created_at, updated_at
                    from exercises
                    """,
                """
                    insert into activity_knowledge_point(activity_id, knowledge_point_id)
                    select id, 'legacy-sql:' || lower(hex(cast(knowledge_point as blob)))
                    from exercises
                    """,
                """
                    insert into activity_session(
                        id, owner_id, activity_id, activity_version, status,
                        source_kind, source_id, started_at, updated_at
                    )
                    select id, owner_id, exercise_id, exercise_version,
                        case when completed_at is null then 'STARTED' else 'COMPLETED' end,
                        'SQL_EXERCISE_SESSION', id, started_at, coalesce(completed_at, started_at)
                    from exercise_sessions
                    """,
                """
                    create trigger exercises_activity_insert after insert on exercises begin
                        insert or ignore into knowledge_point_definition(id, course_id, name)
                        values (
                            'legacy-sql:' || lower(hex(cast(new.knowledge_point as blob))),
                            'builtin-data-management', new.knowledge_point
                        );
                        insert into learning_activity_definition(
                            id, course_id, section_id, activity_type, title, description, difficulty,
                            estimated_minutes, definition_version, specification_format_version,
                            specification_json, source_kind, source_id, enabled, created_at, updated_at
                        ) values (
                            new.id, 'builtin-data-management', 'sql-practice', 'SQL', new.title,
                            new.description, new.difficulty, 15, new.version, 1, '{}',
                            'SQL_EXERCISE', new.id, new.enabled, new.created_at, new.updated_at
                        );
                        insert into activity_knowledge_point(activity_id, knowledge_point_id)
                        values (
                            new.id, 'legacy-sql:' || lower(hex(cast(new.knowledge_point as blob)))
                        );
                    end
                    """,
                """
                    create trigger exercises_activity_update after update on exercises begin
                        insert or ignore into knowledge_point_definition(id, course_id, name)
                        values (
                            'legacy-sql:' || lower(hex(cast(new.knowledge_point as blob))),
                            'builtin-data-management', new.knowledge_point
                        );
                        update learning_activity_definition set
                            title = new.title,
                            description = new.description,
                            difficulty = new.difficulty,
                            definition_version = new.version,
                            enabled = new.enabled,
                            updated_at = new.updated_at
                        where source_kind = 'SQL_EXERCISE' and source_id = new.id;
                        delete from activity_knowledge_point where activity_id = new.id;
                        insert into activity_knowledge_point(activity_id, knowledge_point_id)
                        values (
                            new.id, 'legacy-sql:' || lower(hex(cast(new.knowledge_point as blob)))
                        );
                    end
                    """,
                """
                    create trigger exercises_activity_delete after delete on exercises begin
                        delete from learning_activity_definition
                        where source_kind = 'SQL_EXERCISE' and source_id = old.id;
                    end
                    """,
                """
                    create trigger exercise_sessions_activity_insert after insert on exercise_sessions begin
                        insert into activity_session(
                            id, owner_id, activity_id, activity_version, status,
                            source_kind, source_id, started_at, updated_at
                        ) values (
                            new.id, new.owner_id, new.exercise_id, new.exercise_version,
                            case when new.completed_at is null then 'STARTED' else 'COMPLETED' end,
                            'SQL_EXERCISE_SESSION', new.id, new.started_at, coalesce(new.completed_at, new.started_at)
                        );
                    end
                    """,
                """
                    create trigger exercise_sessions_activity_update after update on exercise_sessions begin
                        update activity_session set
                            owner_id = new.owner_id,
                            status = case when new.completed_at is null then 'STARTED' else 'COMPLETED' end,
                            updated_at = coalesce(new.completed_at, new.started_at)
                        where source_kind = 'SQL_EXERCISE_SESSION' and source_id = new.id;
                    end
                    """,
                """
                    create trigger exercise_sessions_activity_delete after delete on exercise_sessions begin
                        delete from activity_session
                        where source_kind = 'SQL_EXERCISE_SESSION' and source_id = old.id;
                    end
                    """
            )
        )
    );

    private final List<Migration> migrations;

    SqliteSchemaMigrator() {
        this(DEFAULT_MIGRATIONS);
    }

    SqliteSchemaMigrator(List<Migration> migrations) {
        this.migrations = validateMigrations(migrations);
    }

    int migrate(Path databasePath) throws SQLException {
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            connection.setAutoCommit(false);
            try {
                createVersionTable(connection);
                List<Integer> appliedVersions = readAppliedVersions(connection);
                validateAppliedVersions(appliedVersions);

                for (int index = appliedVersions.size(); index < migrations.size(); index++) {
                    applyMigration(connection, migrations.get(index));
                }

                connection.commit();
                return latestVersion();
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    int latestVersion() {
        return migrations.getLast().version();
    }

    int currentVersion(Path databasePath) throws SQLException {
        if (java.nio.file.Files.notExists(databasePath)) {
            return 0;
        }
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                "select max(version) from schema_version"
            )) {
                return result.next() ? result.getInt(1) : 0;
            } catch (SQLException missingVersionTable) {
                return 0;
            }
        }
    }

    private static void createVersionTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                create table if not exists schema_version (
                    version integer primary key,
                    description text not null,
                    applied_at text not null default current_timestamp
                )
                """);
        }
    }

    private static List<Integer> readAppliedVersions(Connection connection) throws SQLException {
        List<Integer> versions = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select version from schema_version order by version")) {
            while (resultSet.next()) {
                versions.add(resultSet.getInt("version"));
            }
        }
        return List.copyOf(versions);
    }

    private void validateAppliedVersions(List<Integer> appliedVersions) throws SQLException {
        if (appliedVersions.size() > migrations.size()) {
            throw new SQLException("Application database schema is newer than this SQLTeacher version");
        }
        for (int index = 0; index < appliedVersions.size(); index++) {
            int expected = migrations.get(index).version();
            int actual = appliedVersions.get(index);
            if (actual != expected) {
                throw new SQLException(
                    "Application database migration history is invalid: expected version "
                        + expected + " but found " + actual
                );
            }
        }
    }

    private static void applyMigration(Connection connection, Migration migration) throws SQLException {
        for (String sql : migration.statements()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into schema_version(version, description) values (?, ?)"
        )) {
            statement.setInt(1, migration.version());
            statement.setString(2, migration.description());
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Throwable originalError) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            originalError.addSuppressed(rollbackError);
        }
    }

    private static List<Migration> validateMigrations(List<Migration> migrations) {
        if (migrations == null || migrations.isEmpty()) {
            throw new IllegalArgumentException("At least one SQLite schema migration is required");
        }
        List<Migration> copy = List.copyOf(migrations);
        for (int index = 0; index < copy.size(); index++) {
            int expectedVersion = index + 1;
            if (copy.get(index).version() != expectedVersion) {
                throw new IllegalArgumentException(
                    "SQLite schema migrations must be ordered and contiguous from version 1"
                );
            }
        }
        return copy;
    }

    record Migration(int version, String description, List<String> statements) {
        Migration {
            if (version < 1) {
                throw new IllegalArgumentException("Migration version must be positive");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("Migration description must not be blank");
            }
            if (statements == null || statements.isEmpty()) {
                throw new IllegalArgumentException("Migration statements must not be empty");
            }
            statements = List.copyOf(statements);
            if (statements.stream().anyMatch(sql -> sql == null || sql.isBlank())) {
                throw new IllegalArgumentException("Migration SQL must not be blank");
            }
        }
    }
}
