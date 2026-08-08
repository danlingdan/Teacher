package com.sqlteacher.desktop.navigation;

import com.sqlteacher.application.collaboration.DesktopAccessProfile;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Capability-aware route registry used by shell shortcuts and command discovery. */
public final class ShellNavigationModel {
    public List<ShellRoute> availableRoutes(DesktopAccessProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        return Arrays.stream(ShellRoute.values()).filter(route -> profile.can(route.capability())).toList();
    }

    public List<ShellRoute> routesIn(ShellWorkspace workspace, DesktopAccessProfile profile) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        return availableRoutes(profile).stream().filter(route -> route.workspace() == workspace).toList();
    }
}
