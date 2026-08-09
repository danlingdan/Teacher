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
        assertTrue(guest.can(DesktopCapability.COURSE_MAP));
        assertTrue(guest.can(DesktopCapability.DATABASE_LEARNING));
        assertTrue(guest.can(DesktopCapability.ACTIVITY_WORKSPACE));
        assertTrue(guest.can(DesktopCapability.STUDENT_EXERCISE));
        assertFalse(guest.can(DesktopCapability.CLOUD_CENTER));
        assertFalse(guest.can(DesktopCapability.EXERCISE_MANAGEMENT));
        assertTrue(guest.can(DesktopCapability.SETTINGS));
        assertTrue(guest.canConfigure(DesktopSettingPermission.APPEARANCE));
        assertTrue(guest.canConfigure(DesktopSettingPermission.LOCAL_CONNECTIONS));
        assertFalse(guest.canConfigure(DesktopSettingPermission.LOCAL_DATA_MAINTENANCE));
    }

    @Test
    void studentShouldReceiveCloudLearningWithoutTeacherManagement() {
        var profile = profile(UserRole.STUDENT);

        assertTrue(profile.can(DesktopCapability.CLOUD_CENTER));
        assertTrue(profile.can(DesktopCapability.KNOWLEDGE_CENTER));
        assertFalse(profile.can(DesktopCapability.EXERCISE_MANAGEMENT));
        assertFalse(profile.can(DesktopCapability.EXERCISE_PROGRESS));
        assertTrue(profile.can(DesktopCapability.SETTINGS));
        assertFalse(profile.canConfigure(DesktopSettingPermission.TEACHING_DEFAULTS));
    }

    @Test
    void teacherAndAdminShouldReceiveManagementCapabilities() {
        var teacher = profile(UserRole.TEACHER);
        var admin = profile(UserRole.ADMIN);

        assertTrue(teacher.can(DesktopCapability.EXERCISE_MANAGEMENT));
        assertTrue(teacher.can(DesktopCapability.EXERCISE_PROGRESS));
        assertTrue(teacher.canConfigure(DesktopSettingPermission.TEACHING_DEFAULTS));
        assertFalse(teacher.canConfigure(DesktopSettingPermission.LOCAL_DATA_MAINTENANCE));
        assertTrue(admin.can(DesktopCapability.SETTINGS));
        assertTrue(admin.can(DesktopCapability.CLOUD_CENTER));
        assertTrue(admin.canConfigure(DesktopSettingPermission.LOCAL_DATA_MAINTENANCE));
        assertTrue(admin.canConfigure(DesktopSettingPermission.CLOUD_OPERATIONS));
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
