package com.sqlteacher.application.system;

import java.nio.file.Path;
import java.util.List;

public interface GeneralSoftwareService {
    GeneralSoftwareSettings settings();
    void saveSettings(GeneralSoftwareSettings settings);
    Path exportSettings(Path destination);
    GeneralSoftwareSettings importSettings(Path source);
    StorageOverview storage();
    long clearRebuildableFiles();
    List<TaskSnapshot> tasks();
    String startTask(String type, String title, boolean cancellable);
    void updateTask(String id, double progress);
    void completeTask(String id);
    void failTask(String id, String errorCode, boolean retryable);
    List<AppNotification> notifications();
    void notify(AppNotification.Category category, String title, String message, String target);
    void markNotificationsRead();
    String connectivitySummary();
    List<String> helpTopics();
    String help(String topicId);
}
