package com.sqlteacher.application.component;

import java.util.List;
import java.util.function.Consumer;

public interface ManagedComponentService {
    List<ManagedComponentStatus> statuses();

    ManagedComponentStatus install(
        ManagedComponentId componentId,
        Consumer<ComponentInstallProgress> progress
    );

    void cancel(ManagedComponentId componentId);
}
