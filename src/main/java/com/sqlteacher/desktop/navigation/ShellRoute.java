package com.sqlteacher.desktop.navigation;

import com.sqlteacher.application.collaboration.DesktopCapability;

/** Route metadata independent from JavaFX nodes and controller instances. */
public enum ShellRoute {
    HOME("home", ShellWorkspace.LEARNING, DesktopCapability.HOME, "任务中心", "task home"),
    STUDENT_EXERCISE("student", ShellWorkspace.LEARNING, DesktopCapability.STUDENT_EXERCISE, "闯关练习", "student practice"),
    COURSE_MAP("course", ShellWorkspace.COURSE, DesktopCapability.COURSE_MAP, "课程地图", "course activity map"),
    KNOWLEDGE("knowledge", ShellWorkspace.COURSE, DesktopCapability.KNOWLEDGE_CENTER, "课程知识", "course knowledge"),
    ACTIVITY_WORKSPACE("practice", ShellWorkspace.EXPERIMENT, DesktopCapability.ACTIVITY_WORKSPACE, "实验工作区", "activity sql practice editor"),
    TABLE_SCHEMA("schema", ShellWorkspace.EXPERIMENT, DesktopCapability.TABLE_SCHEMA, "表结构", "table schema"),
    AI_ASSISTANT("ai", ShellWorkspace.EXPERIMENT, DesktopCapability.AI_ASSISTANT, "AI 助手", "ai assistant"),
    TEACHING_CONTENT("content", ShellWorkspace.TEACHING, DesktopCapability.TEACHING_CONTENT, "教学工作台", "teaching content"),
    EXERCISE_MANAGEMENT("teaching", ShellWorkspace.TEACHING, DesktopCapability.EXERCISE_MANAGEMENT, "题库管理", "exercise management"),
    EXERCISE_PROGRESS("progress", ShellWorkspace.TEACHING, DesktopCapability.EXERCISE_PROGRESS, "学情看板", "learning analytics"),
    CLOUD_CENTER("cloud", ShellWorkspace.TEACHING, DesktopCapability.CLOUD_CENTER, "教学协作", "cloud collaboration"),
    SETTINGS("settings", ShellWorkspace.SYSTEM, DesktopCapability.SETTINGS, "设置", "settings preferences");

    private final String id;
    private final ShellWorkspace workspace;
    private final DesktopCapability capability;
    private final String title;
    private final String keywords;

    ShellRoute(String id, ShellWorkspace workspace, DesktopCapability capability, String title, String keywords) {
        this.id = id;
        this.workspace = workspace;
        this.capability = capability;
        this.title = title;
        this.keywords = keywords;
    }

    public String id() { return id; }
    public ShellWorkspace workspace() { return workspace; }
    public DesktopCapability capability() { return capability; }
    public String title() { return title; }
    public String keywords() { return keywords; }
}
