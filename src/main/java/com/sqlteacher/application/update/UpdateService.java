package com.sqlteacher.application.update;

import java.nio.file.Path;
import java.util.function.DoubleConsumer;

public interface UpdateService {
    UpdateCheckResult check(boolean manual);
    Path download(UpdateManifest manifest, DoubleConsumer progress);
    boolean ready(UpdateManifest manifest, Path installer);
    void launchInstaller(UpdateManifest manifest, Path installer);
    void skip(SemanticVersion version);
    void clearDownloadedUpdates();
}
