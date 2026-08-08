package com.sqlteacher.desktop.navigation;

import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellNavigationModelTest {
    private final ShellNavigationModel model = new ShellNavigationModel();

    @Test
    void guestRoutesShouldCoverTheOfflineAlpha2LearningLoop() {
        var routes = model.availableRoutes(DesktopAccessProfile.guest());

        assertTrue(routes.contains(ShellRoute.HOME));
        assertTrue(routes.contains(ShellRoute.COURSE_MAP));
        assertTrue(routes.contains(ShellRoute.ACTIVITY_WORKSPACE));
        assertFalse(routes.contains(ShellRoute.TEACHING_CONTENT));
        assertEquals(1, model.routesIn(ShellWorkspace.COURSE, DesktopAccessProfile.guest()).size());
    }
}
