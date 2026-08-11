package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeControllerTest {

    @Test
    void personalDiagnosisShouldExplainAdministratorBoundary() {
        assertFalse(HomeController.supportsPersonalDiagnosis(DesktopAccessProfile.Kind.ADMIN));
        assertTrue(HomeController.supportsPersonalDiagnosis(DesktopAccessProfile.Kind.STUDENT));
        assertTrue(HomeController.supportsPersonalDiagnosis(DesktopAccessProfile.Kind.GUEST));
    }
}
