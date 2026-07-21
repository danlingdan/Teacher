package com.sqlteacher.application.collaboration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAccessProfileTest {
    @Test
    void guestShouldOnlyReceiveLocalLearningCapabilities() {
        var guest = DesktopAccessProfile.guest();

        assertTrue(guest.can(DesktopCapability.SQL_PRACTICE));
        assertTrue(guest.can(DesktopCapability.STUDENT_EXERCISE));
        assertFalse(guest.can(DesktopCapability.CLOUD_CENTER));
        assertFalse(guest.can(DesktopCapability.EXERCISE_MANAGEMENT));
        assertFalse(guest.can(DesktopCapability.SETTINGS));
    }

    @Test
    void studentShouldReceiveCloudLearningWithoutTeacherManagement() {
        var profile = profile(UserRole.STUDENT);

        assertTrue(profile.can(DesktopCapability.CLOUD_CENTER));
        assertTrue(profile.can(DesktopCapability.KNOWLEDGE_CENTER));
        assertFalse(profile.can(DesktopCapability.EXERCISE_MANAGEMENT));
        assertFalse(profile.can(DesktopCapability.EXERCISE_PROGRESS));
    }

    @Test
    void teacherAndAdminShouldReceiveManagementCapabilities() {
        var teacher = profile(UserRole.TEACHER);
        var admin = profile(UserRole.ADMIN);

        assertTrue(teacher.can(DesktopCapability.EXERCISE_MANAGEMENT));
        assertTrue(teacher.can(DesktopCapability.EXERCISE_PROGRESS));
        assertTrue(admin.can(DesktopCapability.SETTINGS));
        assertTrue(admin.can(DesktopCapability.CLOUD_CENTER));
    }

    private static DesktopAccessProfile profile(UserRole role) {
        var user = new AuthenticatedUser("user-1", "user@example.com", "User", Set.of(role));
        return DesktopAccessProfile.from(new CloudAuthenticationService.Session(
            "token",
            Instant.now().plusSeconds(60),
            user
        ));
    }
}
