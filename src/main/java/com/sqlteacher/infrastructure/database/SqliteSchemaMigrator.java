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
        ),
        new Migration(
            12,
            "Seed v2 alpha.3 binary-tree quiz and trace learning loop",
            List.of(
                """
                    insert into course_definition(
                        id,version,title,language,license,maintainer,visibility,created_at,updated_at
                    ) values (
                        'builtin-data-structures','1','数据结构与算法','zh-CN',
                        'SQLTeacher built-in content','SQLTeacher','PUBLISHED',
                        '2026-08-09T00:00:00Z','2026-08-09T00:00:00Z'
                    )
                    """,
                """
                    insert into course_section(id,course_id,title,sort_order)
                    values ('binary-tree-traversal','builtin-data-structures','二叉树遍历',0)
                    """,
                """
                    insert into learning_outcome(id,course_id,description,expected_level,sort_order)
                    values ('tree-outcome-traversal','builtin-data-structures',
                        '识别并正确执行二叉树前序遍历','APPLY',0)
                    """,
                """
                    insert into knowledge_point_definition(id,course_id,name,aliases_json,cs2023_mappings_json)
                    values ('ds-tree-traversal','builtin-data-structures','二叉树遍历',
                        '["前序遍历","树的遍历"]','["AL/BasicAnalysis"]')
                    """,
                """
                    insert into knowledge_point_definition(id,course_id,name,aliases_json,cs2023_mappings_json)
                    values ('ds-recursion','builtin-data-structures','递归调用',
                        '["递归"]','["SDF/Algorithms"]')
                    """,
                """
                    insert into knowledge_point_relation(course_id,source_id,target_id,relation_type)
                    values ('builtin-data-structures','ds-recursion','ds-tree-traversal','PREREQUISITE')
                    """,
                """
                    insert into learning_activity_definition(
                        id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,
                        definition_version,specification_format_version,specification_json,source_kind,source_id,
                        enabled,created_at,updated_at
                    ) values (
                        'tree-traversal-quiz','builtin-data-structures','binary-tree-traversal','QUIZ',
                        '遍历概念测验','先判断前序遍历的访问规则，再进入步骤跟踪。','BEGINNER',5,1,1,
                        '{"formatVersion":1,"questions":[{"id":"order-rule","prompt":"前序遍历的访问规则是什么？","options":[{"id":"root-left-right","text":"根 → 左 → 右"},{"id":"left-root-right","text":"左 → 根 → 右"},{"id":"left-right-root","text":"左 → 右 → 根"}],"correctOptionId":"root-left-right","explanation":"前序遍历先访问根节点，再递归访问左子树和右子树。"}],"passPercent":100}',
                        'BUILTIN_V2','tree-traversal-quiz',1,'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z'
                    )
                    """,
                """
                    insert into learning_activity_definition(
                        id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,
                        definition_version,specification_format_version,specification_json,source_kind,source_id,
                        enabled,created_at,updated_at
                    ) values (
                        'tree-preorder-trace','builtin-data-structures','binary-tree-traversal','TRACE',
                        '前序遍历步骤跟踪','点击节点构造前序访问序列，并由确定性评价器逐步检查。','BEGINNER',8,1,1,
                        '{"formatVersion":1,"prompt":"按前序遍历依次点击节点。","traversal":"前序","rootNodeId":"a","nodes":[{"id":"a","label":"A","leftChildId":"b","rightChildId":"c"},{"id":"b","label":"B","leftChildId":"d","rightChildId":"e"},{"id":"c","label":"C","leftChildId":"","rightChildId":"f"},{"id":"d","label":"D","leftChildId":"","rightChildId":""},{"id":"e","label":"E","leftChildId":"","rightChildId":""},{"id":"f","label":"F","leftChildId":"","rightChildId":""}],"expectedNodeIds":["a","b","d","e","c","f"]}',
                        'BUILTIN_V2','tree-preorder-trace',1,'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z'
                    )
                    """,
                """
                    insert into activity_knowledge_point(activity_id,knowledge_point_id) values
                        ('tree-traversal-quiz','ds-tree-traversal'),
                        ('tree-preorder-trace','ds-tree-traversal'),
                        ('tree-preorder-trace','ds-recursion')
                    """,
                """
                    create table activity_feedback (
                        id text primary key,
                        owner_id text not null,
                        activity_id text not null references learning_activity_definition(id),
                        evaluation_id text references activity_evaluation_result(id),
                        author_id text not null,
                        status text not null check (status in ('DRAFT','PUBLISHED','ARCHIVED')),
                        comment text not null,
                        reason_code text not null default '',
                        created_at text not null,
                        updated_at text not null
                    )
                    """,
                "create index activity_feedback_owner_activity on activity_feedback(owner_id,activity_id,status,updated_at desc)"
            )
        ),
        new Migration(
            13,
            "Seed v2 alpha.4 programming activities",
            List.of(
                """
                    insert into course_definition(
                        id,version,title,language,license,maintainer,visibility,created_at,updated_at
                    ) values (
                        'builtin-programming-basics','1','编程语言基础','zh-CN',
                        'SQLTeacher built-in content','SQLTeacher','PUBLISHED',
                        '2026-08-09T00:00:00Z','2026-08-09T00:00:00Z'
                    )
                    """,
                """
                    insert into course_section(id,course_id,title,sort_order)
                    values ('programming-io','builtin-programming-basics','输入、计算与输出',0)
                    """,
                """
                    insert into learning_outcome(id,course_id,description,expected_level,sort_order)
                    values ('programming-io-outcome','builtin-programming-basics',
                        '使用一种编程语言读取两个整数并输出它们的和','APPLY',0)
                    """,
                """
                    insert into knowledge_point_definition(id,course_id,name,aliases_json,cs2023_mappings_json)
                    values ('programming-basic-io','builtin-programming-basics','基本输入输出',
                        '["标准输入","标准输出"]','["SDF/SoftwareDevelopmentFundamentals"]')
                    """,
                codeActivitySql("code-sum-java", "Java 两数求和", "JAVA",
                    "public class Main { public static void main(String[] args) { } }"),
                codeActivitySql("code-sum-python", "Python 两数求和", "PYTHON",
                    "a, b = map(int, input().split())\nprint(a + b)"),
                codeActivitySql("code-sum-c", "C 两数求和", "C",
                    "#include <stdio.h>\nint main(void) { return 0; }"),
                codeActivitySql("code-sum-cpp", "C++ 两数求和", "CPP",
                    "#include <iostream>\nint main() { return 0; }"),
                """
                    insert into activity_knowledge_point(activity_id,knowledge_point_id) values
                        ('code-sum-java','programming-basic-io'),
                        ('code-sum-python','programming-basic-io'),
                        ('code-sum-c','programming-basic-io'),
                        ('code-sum-cpp','programming-basic-io')
                    """
            )
        ),
        new Migration(
            14,
            "Seed v2 alpha.5 systems simulations",
            List.of(
                simulationCourseDefinitionSql("builtin-computer-systems", "计算机系统"),
                simulationSectionSql("instruction-cycle", "builtin-computer-systems", "指令周期"),
                simulationOutcomeSql("builtin-computer-systems", "跟踪一条指令从取指、译码到执行的状态变化"),
                simulationKnowledgeSql("systems-instruction-cycle", "builtin-computer-systems", "指令周期",
                    "AR/Architecture"),
                simulationActivitySql("systems-instruction-cycle", "builtin-computer-systems", "instruction-cycle",
                    "CPU 指令周期", "操作控制器完成一条指令的取指、译码与执行，并观察关键寄存器。",
                    """
                    {"formatVersion":1,"prompt":"按 CPU 指令周期推进状态，并观察 PC、IR 和控制信号。","initialStateId":"ready","goalStateId":"executed","states":[{"id":"ready","title":"准备取指","description":"PC 指向下一条指令，存储器尚未响应。","observations":["PC=0x1000","IR=空","控制信号=空闲"]},{"id":"fetched","title":"取指完成","description":"指令从存储器进入 IR，PC 指向顺序下一条指令。","observations":["PC=0x1004","IR=ADD R1,R2,R3","MAR=0x1000"]},{"id":"decoded","title":"译码完成","description":"控制器识别操作码并读取源寄存器。","observations":["操作=ADD","源操作数=7,5","目标寄存器=R1"]},{"id":"executed","title":"执行并写回","description":"ALU 完成加法，结果写回目标寄存器。","observations":["ALU 结果=12","R1=12","状态=完成"]}],"actions":[{"id":"fetch","label":"发出取指控制信号","fromStateId":"ready","toStateId":"fetched","explanation":"根据 PC 读取指令并更新 PC。"},{"id":"decode","label":"译码并读取寄存器","fromStateId":"fetched","toStateId":"decoded","explanation":"解析操作码和寄存器字段。"},{"id":"execute","label":"执行并写回结果","fromStateId":"decoded","toStateId":"executed","explanation":"ALU 计算并将结果写回 R1。"}],"checkpoints":[{"id":"fetch","stateId":"fetched","title":"取指","successMessage":"已观察到 PC 更新和 IR 装载。","failureReasonCode":"SYSTEMS_FETCH_NOT_REACHED"},{"id":"decode","stateId":"decoded","title":"译码","successMessage":"已识别操作码与操作数。","failureReasonCode":"SYSTEMS_DECODE_NOT_REACHED"},{"id":"execute","stateId":"executed","title":"执行写回","successMessage":"已完成 ALU 执行与寄存器写回。","failureReasonCode":"SYSTEMS_EXECUTE_NOT_REACHED"}]}
                    """),
                simulationKnowledgeLinkSql("systems-instruction-cycle", "systems-instruction-cycle"),
                simulationCourseDefinitionSql("builtin-operating-systems", "操作系统"),
                simulationSectionSql("process-scheduling", "builtin-operating-systems", "进程调度"),
                simulationOutcomeSql("builtin-operating-systems", "按短作业优先策略完成就绪队列调度"),
                simulationKnowledgeSql("os-process-scheduling", "builtin-operating-systems", "短作业优先调度",
                    "OS/Scheduling"),
                simulationActivitySql("os-sjf-scheduling", "builtin-operating-systems", "process-scheduling",
                    "短作业优先调度", "根据到达时间和 CPU burst 选择、分派并完成最短作业。",
                    """
                    {"formatVersion":1,"prompt":"就绪队列中 P1=6ms、P2=2ms、P3=4ms；按非抢占式 SJF 完成第一次调度。","initialStateId":"queue","goalStateId":"completed","states":[{"id":"queue","title":"就绪队列","description":"三个进程同时到达，调度器需要比较 CPU burst。","observations":["P1=6ms","P2=2ms","P3=4ms"]},{"id":"selected","title":"已选择 P2","description":"P2 的 burst 最短，被调度器选中。","observations":["候选=P2","等待队列=P1,P3","策略=SJF"]},{"id":"running","title":"P2 运行","description":"完成上下文切换后，P2 占用 CPU。","observations":["CPU=P2","剩余时间=2ms","上下文切换=1 次"]},{"id":"completed","title":"P2 完成","description":"P2 退出，调度器将重新比较 P1 与 P3。","observations":["P2 周转时间=2ms","下一候选=P3","完成队列=P2"]}],"actions":[{"id":"select-shortest","label":"选择 burst 最短的 P2","fromStateId":"queue","toStateId":"selected","explanation":"SJF 在同时到达的进程中选择 P2。"},{"id":"dispatch","label":"分派 P2 到 CPU","fromStateId":"selected","toStateId":"running","explanation":"保存调度状态并切换到 P2。"},{"id":"complete","label":"运行 2ms 至完成","fromStateId":"running","toStateId":"completed","explanation":"P2 用完 CPU burst 并退出。"}],"checkpoints":[{"id":"selection","stateId":"selected","title":"正确选进程","successMessage":"已按最短 CPU burst 选择 P2。","failureReasonCode":"OS_SJF_SELECTION_NOT_REACHED"},{"id":"dispatch","stateId":"running","title":"完成分派","successMessage":"P2 已进入运行态。","failureReasonCode":"OS_DISPATCH_NOT_REACHED"},{"id":"completion","stateId":"completed","title":"完成首个作业","successMessage":"P2 已运行完成并记录周转时间。","failureReasonCode":"OS_PROCESS_COMPLETION_NOT_REACHED"}]}
                    """),
                simulationKnowledgeLinkSql("os-sjf-scheduling", "os-process-scheduling"),
                simulationCourseDefinitionSql("builtin-computer-networks", "计算机网络"),
                simulationSectionSql("packet-delivery", "builtin-computer-networks", "分组转发"),
                simulationOutcomeSql("builtin-computer-networks", "解释帧封装、下一跳解析、路由转发与交付"),
                simulationKnowledgeSql("network-packet-delivery", "builtin-computer-networks",
                    "局域网到远端主机的分组传递", "NC/NetworkLayer"),
                simulationActivitySql("network-packet-delivery", "builtin-computer-networks", "packet-delivery",
                    "跨网段分组传递", "把应用数据从主机 A 经默认网关传递到远端主机 B。",
                    """
                    {"formatVersion":1,"prompt":"主机 A 与 B 不在同一子网；依次完成封装、下一跳解析、路由转发和交付。","initialStateId":"payload","goalStateId":"delivered","states":[{"id":"payload","title":"应用数据待发送","description":"传输层数据已交给网络层，尚未形成链路帧。","observations":["源 IP=10.0.0.2","目的 IP=10.0.1.8","TTL=64"]},{"id":"encapsulated","title":"IP 分组已封装","description":"主机确认目的地址不在本地子网，需要交给默认网关。","observations":["下一跳=10.0.0.1","目的 IP 保持不变","需要解析网关 MAC"]},{"id":"resolved","title":"下一跳已解析","description":"ARP 缓存获得默认网关 MAC，可以发送以太网帧。","observations":["目的 MAC=网关 MAC","帧载荷=原 IP 分组","ARP=命中"]},{"id":"forwarded","title":"路由器已转发","description":"路由器解封装入站帧、递减 TTL，并为出站链路重新封装。","observations":["TTL=63","目的 IP=10.0.1.8","出接口=LAN2"]},{"id":"delivered","title":"主机 B 已接收","description":"目的主机验收帧并把分组向上传递。","observations":["IP 校验=通过","目的端口=已分用","状态=交付"]}],"actions":[{"id":"encapsulate","label":"判断子网并封装 IP 分组","fromStateId":"payload","toStateId":"encapsulated","explanation":"保留最终目的 IP，并把默认网关作为下一跳。"},{"id":"resolve","label":"解析默认网关 MAC","fromStateId":"encapsulated","toStateId":"resolved","explanation":"使用 ARP 获得本地链路下一跳地址。"},{"id":"forward","label":"由路由器查表并转发","fromStateId":"resolved","toStateId":"forwarded","explanation":"递减 TTL 并在出站链路重新封装。"},{"id":"deliver","label":"目的主机验收并上交","fromStateId":"forwarded","toStateId":"delivered","explanation":"主机 B 解封装并把数据交给上层协议。"}],"checkpoints":[{"id":"encapsulation","stateId":"encapsulated","title":"网络层封装","successMessage":"已保留最终目的 IP 并选择默认网关。","failureReasonCode":"NETWORK_ENCAPSULATION_NOT_REACHED"},{"id":"next-hop","stateId":"resolved","title":"下一跳解析","successMessage":"已获得网关 MAC 并形成链路帧。","failureReasonCode":"NETWORK_NEXT_HOP_NOT_REACHED"},{"id":"forwarding","stateId":"forwarded","title":"路由转发","successMessage":"已完成 TTL 更新和出站封装。","failureReasonCode":"NETWORK_FORWARDING_NOT_REACHED"},{"id":"delivery","stateId":"delivered","title":"目的交付","successMessage":"分组已由主机 B 接收并向上交付。","failureReasonCode":"NETWORK_DELIVERY_NOT_REACHED"}]}
                    """),
                simulationKnowledgeLinkSql("network-packet-delivery", "network-packet-delivery")
            )
        ),
        new Migration(
            15,
            "Seed v2 alpha.6 professional foundations",
            List.of(
                simulationCourseDefinitionSql("builtin-software-engineering", "软件工程"),
                simulationSectionSql("ci-quality-gate", "builtin-software-engineering", "持续集成质量门禁"),
                simulationOutcomeSql("builtin-software-engineering", "把验收标准、自动测试和评审证据连接到发布决策"),
                simulationKnowledgeSql("se-ci-quality-gate", "builtin-software-engineering", "持续集成质量门禁",
                    "SE/SoftwareProcess"),
                simulationActivitySql("se-ci-quality-gate", "builtin-software-engineering", "ci-quality-gate",
                    "从需求变更到发布门禁", "使用固定变更说明构造可追溯的测试与发布证据，不执行真实 Git 或 CI 命令。",
                    """
                    {"formatVersion":1,"prompt":"订单折扣规则发生变化；依次建立验收测试、运行固定 CI 检查并完成发布评审。","initialStateId":"change","goalStateId":"release-ready","states":[{"id":"change","title":"需求变更待验证","description":"折扣边界从 100 元调整为 80 元，尚无对应验收证据。","observations":["变更=订单满 80 元享受折扣","风险=边界值回归","追踪状态=未建立"]},{"id":"tests-selected","title":"验收测试已映射","description":"需求示例已转换为边界值和回归测试。","observations":["79 元=不折扣","80 元=折扣","历史规则=回归"]},{"id":"pipeline-passed","title":"固定 CI 检查通过","description":"单元、契约和回归测试均在内置结果集中通过。","observations":["单元测试=通过","契约测试=通过","回归测试=通过"]},{"id":"release-ready","title":"发布证据已评审","description":"需求、测试与评审记录可相互追踪，可以进入发布候选。","observations":["追踪链=完整","高风险缺陷=0","结论=可候选发布"]}],"actions":[{"id":"map-tests","label":"把验收标准映射为测试","fromStateId":"change","toStateId":"tests-selected","explanation":"为边界值和历史行为建立可复核测试。"},{"id":"run-ci","label":"运行内置 CI 结果集","fromStateId":"tests-selected","toStateId":"pipeline-passed","explanation":"检查单元、契约和回归结果，不调用外部 CI。"},{"id":"review-gate","label":"评审追踪与发布门禁","fromStateId":"pipeline-passed","toStateId":"release-ready","explanation":"确认需求、测试和发布结论之间的证据链。"}],"checkpoints":[{"id":"acceptance-tests","stateId":"tests-selected","title":"验收标准已测试化","successMessage":"边界值与回归测试已覆盖变更。","failureReasonCode":"SE_ACCEPTANCE_TESTS_NOT_REACHED"},{"id":"ci","stateId":"pipeline-passed","title":"CI 质量检查","successMessage":"固定测试集全部通过。","failureReasonCode":"SE_CI_NOT_REACHED"},{"id":"release-gate","stateId":"release-ready","title":"发布门禁评审","successMessage":"追踪链完整且无未处理高风险缺陷。","failureReasonCode":"SE_RELEASE_GATE_NOT_REACHED"}]}
                    """),
                simulationKnowledgeLinkSql("se-ci-quality-gate", "se-ci-quality-gate"),
                simulationCourseDefinitionSql("builtin-programming-languages", "程序设计语言与编译"),
                simulationSectionSql("lexer-pipeline", "builtin-programming-languages", "词法分析"),
                simulationOutcomeSql("builtin-programming-languages", "把字符流转换为带类型的 Token 序列并验证词法结果"),
                simulationKnowledgeSql("compiler-lexer-pipeline", "builtin-programming-languages", "词法分析流水线",
                    "PL/LanguageTranslation"),
                simulationActivitySql("compiler-lexer-pipeline", "builtin-programming-languages", "lexer-pipeline",
                    "从字符流到 Token 序列", "对固定表达式执行扫描、分类与词法验收，不运行外部编译器。",
                    """
                    {"formatVersion":1,"prompt":"对源文本 sum=12+3; 完成确定性词法分析。","initialStateId":"source","goalStateId":"accepted","states":[{"id":"source","title":"字符流待扫描","description":"词法分析器尚未划分词素。","observations":["输入=sum=12+3;","当前位置=0","Token=空"]},{"id":"scanned","title":"词素边界已识别","description":"最长匹配规则已划分标识符、运算符、整数和分隔符。","observations":["sum | = | 12 | + | 3 | ;","空白=忽略","未知字符=0"]},{"id":"classified","title":"Token 类型已分类","description":"每个词素都映射到确定的 Token 类型。","observations":["IDENT(sum)","ASSIGN","INT(12), PLUS, INT(3), SEMICOLON"]},{"id":"accepted","title":"词法结果已验收","description":"Token 顺序完整且没有未知字符。","observations":["Token 数=6","错误数=0","状态=可交给语法分析器"]}],"actions":[{"id":"scan","label":"按最长匹配扫描词素","fromStateId":"source","toStateId":"scanned","explanation":"逐字符识别词素边界并跳过无意义空白。"},{"id":"classify","label":"为词素分配 Token 类型","fromStateId":"scanned","toStateId":"classified","explanation":"将标识符、整数、运算符和分隔符分类。"},{"id":"validate-stream","label":"校验 Token 顺序与完整性","fromStateId":"classified","toStateId":"accepted","explanation":"确认 Token 流可安全交给后续语法分析。"}],"checkpoints":[{"id":"scan","stateId":"scanned","title":"词素划分","successMessage":"字符流已按最长匹配划分。","failureReasonCode":"COMPILER_TOKENIZATION_NOT_REACHED"},{"id":"classification","stateId":"classified","title":"Token 分类","successMessage":"全部词素已获得确定类型。","failureReasonCode":"COMPILER_CLASSIFICATION_NOT_REACHED"},{"id":"acceptance","stateId":"accepted","title":"词法验收","successMessage":"Token 流完整且无未知字符。","failureReasonCode":"COMPILER_STREAM_NOT_ACCEPTED"}]}
                    """),
                simulationKnowledgeLinkSql("compiler-lexer-pipeline", "compiler-lexer-pipeline"),
                simulationCourseDefinitionSql("builtin-discrete-mathematics", "离散数学与理论基础"),
                simulationSectionSql("induction-proof", "builtin-discrete-mathematics", "数学归纳法"),
                simulationOutcomeSql("builtin-discrete-mathematics", "按基础步、归纳假设和归纳步组织结构化证明"),
                simulationKnowledgeSql("discrete-induction-proof", "builtin-discrete-mathematics", "数学归纳法结构",
                    "MS/MathematicalFoundations"),
                simulationActivitySql("discrete-induction-proof", "builtin-discrete-mathematics", "induction-proof",
                    "自然数求和归纳证明", "用结构化步骤证明 1+2+...+n=n(n+1)/2，评价器只检查固定证明结构。",
                    """
                    {"formatVersion":1,"prompt":"按数学归纳法完成自然数前 n 项和公式的结构化证明。","initialStateId":"claim","goalStateId":"proved","states":[{"id":"claim","title":"命题已陈述","description":"需要证明对所有 n≥1，1+...+n=n(n+1)/2。","observations":["定义域=n≥1","量词=任意自然数","方法=数学归纳法"]},{"id":"base","title":"基础步成立","description":"代入 n=1 后等式两边均为 1。","observations":["左边=1","右边=1×2/2=1","P(1)=真"]},{"id":"hypothesis","title":"归纳假设已声明","description":"假设 P(k) 成立，且 k≥1。","observations":["1+...+k=k(k+1)/2","假设范围=固定 k","不可假设 P(k+1)"]},{"id":"step","title":"归纳步完成","description":"在假设基础上加入 k+1 并化简得到 P(k+1)。","observations":["k(k+1)/2+(k+1)","=(k+1)(k+2)/2","P(k)⇒P(k+1)"]},{"id":"proved","title":"归纳结论成立","description":"基础步与归纳步共同推出命题对所有 n≥1 成立。","observations":["基础步=通过","归纳步=通过","结论=∀n≥1 P(n)"]}],"actions":[{"id":"verify-base","label":"验证 n=1 的基础步","fromStateId":"claim","toStateId":"base","explanation":"直接计算等式两边并确认 P(1)。"},{"id":"state-hypothesis","label":"声明 P(k) 的归纳假设","fromStateId":"base","toStateId":"hypothesis","explanation":"只假设固定 k 的命题成立。"},{"id":"derive-next","label":"由 P(k) 推导 P(k+1)","fromStateId":"hypothesis","toStateId":"step","explanation":"加入 k+1 并进行代数化简。"},{"id":"conclude","label":"应用归纳原理得出结论","fromStateId":"step","toStateId":"proved","explanation":"由基础步和归纳步覆盖所有 n≥1。"}],"checkpoints":[{"id":"base","stateId":"base","title":"基础步","successMessage":"P(1) 已直接验证。","failureReasonCode":"DISCRETE_BASE_CASE_NOT_REACHED"},{"id":"hypothesis","stateId":"hypothesis","title":"归纳假设","successMessage":"P(k) 的范围已正确声明。","failureReasonCode":"DISCRETE_HYPOTHESIS_NOT_REACHED"},{"id":"step","stateId":"step","title":"归纳步","successMessage":"已从 P(k) 推导 P(k+1)。","failureReasonCode":"DISCRETE_INDUCTIVE_STEP_NOT_REACHED"},{"id":"conclusion","stateId":"proved","title":"归纳结论","successMessage":"证明结构已完整闭合。","failureReasonCode":"DISCRETE_PROOF_NOT_COMPLETE"}]}
                    """),
                simulationKnowledgeLinkSql("discrete-induction-proof", "discrete-induction-proof"),
                simulationCourseDefinitionSql("builtin-ai-foundations", "AI 与数据基础"),
                simulationSectionSql("classification-evaluation", "builtin-ai-foundations", "分类评价与局限"),
                simulationOutcomeSql("builtin-ai-foundations", "从固定混淆矩阵计算指标并识别数据与评价局限"),
                simulationKnowledgeSql("ai-classification-evaluation", "builtin-ai-foundations", "分类模型评价",
                    "AI/MachineLearning"),
                simulationActivitySql("ai-classification-evaluation", "builtin-ai-foundations", "classification-evaluation",
                    "固定分类结果评价", "使用内置匿名样本计算 precision、recall 并记录局限，不调用或训练真实模型。",
                    """
                    {"formatVersion":1,"prompt":"根据 20 条固定分类结果构造混淆矩阵、计算指标并完成责任边界检查。","initialStateId":"cases","goalStateId":"audited","states":[{"id":"cases","title":"匿名预测结果待汇总","description":"20 条固定结果只包含真实标签与预测标签。","observations":["正类样本=9","负类样本=11","不含个人信息"]},{"id":"matrix","title":"混淆矩阵已构造","description":"逐条计数得到 TP、FP、FN、TN。","observations":["TP=8","FP=2","FN=1","TN=9"]},{"id":"metrics","title":"核心指标已计算","description":"使用固定公式计算 precision 与 recall。","observations":["precision=8/(8+2)=0.80","recall=8/(8+1)≈0.889","accuracy=17/20=0.85"]},{"id":"audited","title":"评价局限已记录","description":"指标不能替代数据代表性、代价和人工责任审查。","observations":["样本量=20，较小","类别代价需业务定义","模型输出不直接决定权威状态"]}],"actions":[{"id":"build-matrix","label":"汇总 TP、FP、FN、TN","fromStateId":"cases","toStateId":"matrix","explanation":"只依据固定标签对进行确定性计数。"},{"id":"compute-metrics","label":"计算 precision 与 recall","fromStateId":"matrix","toStateId":"metrics","explanation":"按标准公式计算并保留分母。"},{"id":"audit-limits","label":"检查样本、代价与责任边界","fromStateId":"metrics","toStateId":"audited","explanation":"记录小样本和指标不能替代责任判断的限制。"}],"checkpoints":[{"id":"matrix","stateId":"matrix","title":"混淆矩阵","successMessage":"四类计数与固定结果一致。","failureReasonCode":"AI_CONFUSION_MATRIX_NOT_REACHED"},{"id":"metrics","stateId":"metrics","title":"分类指标","successMessage":"precision 与 recall 已按公式计算。","failureReasonCode":"AI_METRICS_NOT_REACHED"},{"id":"limits","stateId":"audited","title":"责任边界","successMessage":"已记录数据和指标的适用限制。","failureReasonCode":"AI_LIMITS_NOT_AUDITED"}]}
                    """),
                simulationKnowledgeLinkSql("ai-classification-evaluation", "ai-classification-evaluation"),
                simulationCourseDefinitionSql("builtin-security-foundations", "计算机安全基础"),
                simulationSectionSql("input-validation", "builtin-security-foundations", "输入验证与授权"),
                simulationOutcomeSql("builtin-security-foundations", "按约束验证、对象授权和上下文编码处理不可信输入"),
                simulationKnowledgeSql("security-input-validation", "builtin-security-foundations", "不可信输入防御链",
                    "SEC/SecureSoftware"),
                simulationActivitySql("security-input-validation", "builtin-security-foundations", "input-validation",
                    "不可信输入防御链", "在纯数据模拟中为资料更新请求建立验证、授权和编码门禁，不执行载荷或网络操作。",
                    """
                    {"formatVersion":1,"prompt":"处理一条来自客户端的资料更新请求；依次完成结构约束、对象授权和输出编码。","initialStateId":"untrusted","goalStateId":"handled","states":[{"id":"untrusted","title":"请求尚未信任","description":"客户端字段、对象标识和显示名称都不能直接使用。","observations":["来源=客户端","对象=user-42","displayName=待验证文本"]},{"id":"constrained","title":"结构与长度已验证","description":"只接受允许字段、正确类型和有限长度。","observations":["字段白名单=通过","长度≤80","未知字段=拒绝"]},{"id":"authorized","title":"对象级授权已通过","description":"当前主体仅能修改自己的 user-42 资料。","observations":["主体=user-42","目标=user-42","权限=PROFILE_WRITE_SELF"]},{"id":"encoded","title":"显示上下文已编码","description":"保存原始业务值前完成规范化，展示时执行上下文编码。","observations":["控制字符=拒绝","规范化=完成","HTML 上下文=编码"]},{"id":"handled","title":"请求被安全处理","description":"只有通过全部门禁的数据才进入受控更新路径。","observations":["验证=通过","授权=通过","编码=通过"]}],"actions":[{"id":"validate-schema","label":"执行字段、类型和长度约束","fromStateId":"untrusted","toStateId":"constrained","explanation":"在业务处理前拒绝不符合契约的数据。"},{"id":"authorize-object","label":"验证主体与目标对象权限","fromStateId":"constrained","toStateId":"authorized","explanation":"服务端根据真实主体执行对象级授权。"},{"id":"encode-context","label":"规范化并按显示上下文编码","fromStateId":"authorized","toStateId":"encoded","explanation":"避免把未经处理的文本拼接到输出上下文。"},{"id":"accept-request","label":"进入受控资料更新路径","fromStateId":"encoded","toStateId":"handled","explanation":"仅在全部防御门禁通过后接受请求。"}],"checkpoints":[{"id":"constraints","stateId":"constrained","title":"输入约束","successMessage":"字段、类型和长度已验证。","failureReasonCode":"SEC_INPUT_CONSTRAINTS_NOT_REACHED"},{"id":"authorization","stateId":"authorized","title":"对象级授权","successMessage":"主体具有目标对象的明确权限。","failureReasonCode":"SEC_AUTHORIZATION_NOT_REACHED"},{"id":"encoding","stateId":"encoded","title":"上下文编码","successMessage":"文本已按目标显示上下文处理。","failureReasonCode":"SEC_OUTPUT_ENCODING_NOT_REACHED"},{"id":"handling","stateId":"handled","title":"受控处理","successMessage":"请求仅在全部门禁通过后被接受。","failureReasonCode":"SEC_SAFE_HANDLING_NOT_REACHED"}]}
                    """),
                simulationKnowledgeLinkSql("security-input-validation", "security-input-validation")
            )
        ),
        new Migration(
            16,
            "Add v2 alpha.7 project learning and synchronization records",
            List.of(
                "insert into course_section(id,course_id,title,sort_order) values ('team-project','builtin-software-engineering','项目制实践',1)",
                "insert into knowledge_point_definition(id,course_id,name,aliases_json,cs2023_mappings_json) values ('se-project-delivery','builtin-software-engineering','可追溯项目交付','[]','[\"SE/ProjectManagement\"]')",
                projectActivitySql(),
                "insert into activity_knowledge_point(activity_id,knowledge_point_id) values ('se-versioned-project','se-project-delivery')",
                """
                    create table course_package_operation (
                        operation_id text primary key,
                        actor_id text not null,
                        package_id text not null,
                        course_id text not null,
                        course_version text not null,
                        content_sha256 text not null,
                        license text not null,
                        status text not null check (status in ('PREVIEWED','IMPORTED','REJECTED','ROLLED_BACK')),
                        result_json text not null,
                        created_at text not null
                    )
                    """,
                "create index course_package_course_version on course_package_operation(course_id,course_version,created_at desc)",
                """
                    create table cloud_sync_operation (
                        operation_id text primary key,
                        owner_id text not null,
                        aggregate_type text not null,
                        aggregate_id text not null,
                        aggregate_version integer not null check (aggregate_version >= 0),
                        payload_sha256 text not null,
                        summary_json text not null,
                        status text not null check (status in ('PENDING','SYNCED','CONFLICT','REJECTED')),
                        conflict_code text not null default '',
                        created_at text not null,
                        updated_at text not null
                    )
                    """,
                "create index cloud_sync_owner_status on cloud_sync_operation(owner_id,status,created_at)",
                """
                    create table cloud_sync_cursor (
                        owner_id text primary key,
                        cursor integer not null check (cursor >= 0),
                        updated_at text not null
                    )
                    """
            )
        ),
        new Migration(
            17,
            "Freeze v2 consolidated beta activity, content provenance, and course-path contracts",
            List.of(
                """
                    create table course_content_provenance (
                        course_id text primary key references course_definition(id) on delete cascade,
                        source_title text not null,
                        source_reference text not null,
                        license text not null,
                        author text not null,
                        content_version text not null,
                        reviewed_at text not null
                    )
                    """,
                """
                    insert into course_content_provenance(course_id,source_title,source_reference,license,author,content_version,reviewed_at)
                    select id,'SQLTeacher original built-in curriculum','project://docs/guide','Apache-2.0',
                        'SQLTeacher contributors',version,'2026-08-09T00:00:00Z'
                    from course_definition
                    """,
                """
                    insert into course_definition(id,version,title,language,license,maintainer,visibility,created_at,updated_at)
                    values ('builtin-capstone-project','1','综合项目','zh-CN','Apache-2.0','SQLTeacher contributors',
                        'PUBLISHED','2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')
                    """,
                "insert into course_section(id,course_id,title,sort_order) values ('capstone-delivery','builtin-capstone-project','跨课程项目交付',0)",
                "insert into learning_outcome(id,course_id,description,expected_level,sort_order) values ('capstone-outcome','builtin-capstone-project','综合运用多门课程知识完成可复核交付','CREATE',0)",
                "insert into knowledge_point_definition(id,course_id,name,aliases_json,cs2023_mappings_json) values ('capstone-evidence','builtin-capstone-project','跨课程证据链','[]','[\"SEP/ProfessionalPractice\"]')",
                capstoneProjectActivitySql(),
                "insert into activity_knowledge_point(activity_id,knowledge_point_id) values ('capstone-evidence-project','capstone-evidence')",
                labActivitySql(),
                "insert into activity_knowledge_point(activity_id,knowledge_point_id) values ('programming-debug-lab','programming-basic-io')",
                readingActivitySql(),
                "insert into activity_knowledge_point(activity_id,knowledge_point_id) values ('tree-complexity-reading','ds-tree-traversal')",
                """
                    insert into course_content_provenance(course_id,source_title,source_reference,license,author,content_version,reviewed_at)
                    values ('builtin-capstone-project','SQLTeacher original capstone curriculum','project://docs/guide/18-project-course-package-cloud2.md',
                        'Apache-2.0','SQLTeacher contributors','1','2026-08-09T00:00:00Z')
                    """,
                """
                    create table cross_course_knowledge_relation (
                        source_knowledge_point_id text not null references knowledge_point_definition(id),
                        target_knowledge_point_id text not null references knowledge_point_definition(id),
                        relation_type text not null check (relation_type in ('PREREQUISITE','TRANSFER','RELATED')),
                        rationale text not null,
                        primary key(source_knowledge_point_id,target_knowledge_point_id,relation_type)
                    )
                    """,
                "insert into cross_course_knowledge_relation values ('ds-tree-traversal','capstone-evidence','TRANSFER','算法证据可作为综合项目的可复核交付物')",
                "insert into cross_course_knowledge_relation values ('se-ci-quality-gate','capstone-evidence','PREREQUISITE','综合项目必须建立自动质量门禁')",
                "insert into cross_course_knowledge_relation values ('security-input-validation','capstone-evidence','RELATED','项目证据应包含输入与授权边界')"
            )
        )
    );

    private static String capstoneProjectActivitySql() {
        String specification = """
            {"formatVersion":1,"prompt":"完成一个跨课程综合项目，并提交范围、实现、验证和复盘证据。","milestones":[{"id":"scope","title":"范围冻结","acceptanceCriterion":"记录目标、非目标、许可和数据边界"},{"id":"implementation","title":"实现闭环","acceptanceCriterion":"实现具有真实运行路径且不包含占位入口"},{"id":"verification","title":"验证与交付","acceptanceCriterion":"固定测试、风险检查和复盘均可复现"}],"rubric":[{"id":"correctness","title":"正确性与证据","weight":35},{"id":"integration","title":"跨课程整合","weight":35},{"id":"reflection","title":"限制与改进","weight":30}],"minimumEvidenceCharacters":120,"minimumReflectionCharacters":80}
            """.strip();
        return "insert into learning_activity_definition(id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,"
            + "definition_version,specification_format_version,specification_json,source_kind,source_id,enabled,created_at,updated_at) values ("
            + "'capstone-evidence-project','builtin-capstone-project','capstone-delivery','PROJECT','跨课程证据项目',"
            + "'自动门禁验证交付完整性；教师量规负责最终能力判断。','ADVANCED',180,1,1,'"
            + specification.replace("'", "''") + "','BUILTIN_V2','capstone-evidence-project',1,"
            + "'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')";
    }

    private static String labActivitySql() {
        String specification = """
            {"formatVersion":1,"prompt":"在本地 IDE 中复现、定位并修复一个输入解析缺陷，记录每一步观测。","steps":[{"id":"reproduce","title":"复现缺陷","instruction":"使用固定输入运行程序并记录实际输出。","observationKey":"actual-output"},{"id":"diagnose","title":"定位原因","instruction":"比较期望与实际控制流，记录根因。","observationKey":"root-cause"},{"id":"verify","title":"验证修复","instruction":"运行正常与边界输入，记录测试摘要。","observationKey":"test-summary"}],"minimumConclusionCharacters":60}
            """.strip();
        return "insert into learning_activity_definition(id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,"
            + "definition_version,specification_format_version,specification_json,source_kind,source_id,enabled,created_at,updated_at) values ("
            + "'programming-debug-lab','builtin-programming-basics','programming-io','LAB','输入解析调试实验',"
            + "'本地运行由学生主动发起；评价仅检查步骤、观测和结论结构。','INTERMEDIATE',35,1,1,'"
            + specification.replace("'", "''") + "','BUILTIN_V2','programming-debug-lab',1,"
            + "'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')";
    }

    private static String readingActivitySql() {
        String specification = """
            {"formatVersion":1,"sourceTitle":"SQLTeacher 原创：遍历与复杂度","license":"Apache-2.0","content":"树遍历把结构关系转换为确定访问序列。前序遍历先访问根，再递归访问左子树和右子树；若每个结点只处理一次，时间复杂度与结点数 n 成正比，即 O(n)。递归实现的额外空间由树高 h 决定，最坏退化树为 O(n)，平衡树为 O(log n)。阅读完成只记录接触内容，必须通过主动回忆才能形成评价证据。","checks":[{"id":"order","prompt":"前序遍历访问根、左子树、右子树的顺序是什么？","expectedAnswer":"根 左 右","explanation":"回忆顺序应为：根 左 右。"},{"id":"time","prompt":"每个结点只处理一次时，遍历的时间复杂度是什么？","expectedAnswer":"O(n)","explanation":"每个结点访问一次，因此为 O(n)。"}],"passPercent":100}
            """.strip();
        return "insert into learning_activity_definition(id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,"
            + "definition_version,specification_format_version,specification_json,source_kind,source_id,enabled,created_at,updated_at) values ("
            + "'tree-complexity-reading','builtin-data-structures','binary-tree-traversal','READING','遍历与复杂度主动阅读',"
            + "'显示来源和许可，并以主动回忆而非阅读状态形成评价。','BEGINNER',8,1,1,'"
            + specification.replace("'", "''") + "','BUILTIN_V2','tree-complexity-reading',1,"
            + "'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')";
    }

    private static String projectActivitySql() {
        String specification = """
            {"formatVersion":1,"prompt":"以个人或小组方式完成一个可复核的小型学习工具，按版本提交里程碑、交付证据与反思。","milestones":[{"id":"scope","title":"范围与验收标准","acceptanceCriterion":"目标、非目标和验收示例均已记录"},{"id":"implementation","title":"实现与自动检查","acceptanceCriterion":"核心实现和固定自动检查均可复现"},{"id":"review","title":"交付评审","acceptanceCriterion":"已记录限制、反馈与后续改进"}],"rubric":[{"id":"correctness","title":"正确性与证据","weight":40},{"id":"design","title":"设计与可维护性","weight":30},{"id":"reflection","title":"复盘与改进","weight":30}],"minimumEvidenceCharacters":80,"minimumReflectionCharacters":60}
            """.strip();
        return "insert into learning_activity_definition("
            + "id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,"
            + "definition_version,specification_format_version,specification_json,source_kind,source_id,"
            + "enabled,created_at,updated_at) values ('se-versioned-project','builtin-software-engineering',"
            + "'team-project','PROJECT','版本化项目交付','自动门禁只验证里程碑与证据完整性；最终能力结论由教师量规评审。',"
            + "'INTERMEDIATE',90,1,1,'" + specification.replace("'", "''")
            + "','BUILTIN_V2','se-versioned-project',1,'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')";
    }

    private static String simulationCourseDefinitionSql(String courseId, String courseTitle) {
        return "insert into course_definition(id,version,title,language,license,maintainer,visibility,created_at,updated_at)"
            + " values ('" + courseId + "','1','" + courseTitle + "','zh-CN','SQLTeacher built-in content',"
            + "'SQLTeacher','PUBLISHED','2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')";
    }

    private static String simulationSectionSql(String sectionId, String courseId, String title) {
        return "insert into course_section(id,course_id,title,sort_order) values ('" + sectionId + "','"
            + courseId + "','" + title + "',0)";
    }

    private static String simulationOutcomeSql(String courseId, String outcome) {
        return "insert into learning_outcome(id,course_id,description,expected_level,sort_order) values ('"
            + courseId + "-outcome','" + courseId + "','" + outcome + "','APPLY',0)";
    }

    private static String simulationKnowledgeSql(String id, String courseId, String title, String mapping) {
        return "insert into knowledge_point_definition(id,course_id,name,aliases_json,cs2023_mappings_json) values ('"
            + id + "','" + courseId + "','" + title + "','[]','[\"" + mapping + "\"]')";
    }

    private static String simulationActivitySql(String id, String courseId, String sectionId, String title,
                                                String description, String specification) {
        return "insert into learning_activity_definition("
            + "id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,"
            + "definition_version,specification_format_version,specification_json,source_kind,source_id,"
            + "enabled,created_at,updated_at) values ('" + id + "','" + courseId + "','" + sectionId
            + "','SIMULATION','" + title + "','" + description + "','BEGINNER',12,1,1,'"
            + specification.strip().replace("'", "''") + "','BUILTIN_V2','" + id
            + "',1,'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')";
    }

    private static String simulationKnowledgeLinkSql(String activityId, String knowledgeId) {
        return "insert into activity_knowledge_point(activity_id,knowledge_point_id) values ('" + activityId
            + "','" + knowledgeId + "')";
    }

    private static String codeActivitySql(String id, String title, String language, String starterCode) {
        String specification = "{\"formatVersion\":1,\"language\":\"" + language
            + "\",\"prompt\":\"读取一行中的两个整数，输出它们的和。\",\"starterCode\":\""
            + starterCode.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
            + "\",\"tests\":[{\"id\":\"positive\",\"input\":\"2 3\\n\",\"expectedOutput\":\"5\\n\"},"
            + "{\"id\":\"mixed\",\"input\":\"-7 4\\n\",\"expectedOutput\":\"-3\\n\"}],"
            + "\"limits\":{\"wallTime\":\"PT5S\",\"cpuTime\":\"PT3S\",\"memoryBytes\":268435456,"
            + "\"outputBytes\":65536,\"workspaceBytes\":16777216,\"files\":64,\"processes\":8}}";
        return "insert into learning_activity_definition("
            + "id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,"
            + "definition_version,specification_format_version,specification_json,source_kind,source_id,"
            + "enabled,created_at,updated_at) values ('" + id + "','builtin-programming-basics','programming-io',"
            + "'CODE','" + title + "','在受控 Runner 中完成两数求和。','BEGINNER',10,1,1,'"
            + specification.replace("'", "''") + "','BUILTIN_V2','" + id
            + "',1,'2026-08-09T00:00:00Z','2026-08-09T00:00:00Z')";
    }

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
