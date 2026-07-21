package com.sqlteacher.application.maintenance;

import java.util.List;

public interface ApplicationBackupService {
    BackupSnapshot createBackup();

    List<BackupSnapshot> listBackups();

    void restoreBackup(String backupId);

    void restoreDemoDatabase();
}
