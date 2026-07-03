package com.sqlteacher.infrastructure.environment;

import java.awt.GraphicsEnvironment;

public final class JavaFxEnvironmentVerifier {
    public VerificationItem verifyRuntime() {
        try {
            Class.forName("javafx.application.Application");
            Class.forName("javafx.stage.Stage");
            return VerificationItem.passed("JavaFX runtime", "required JavaFX classes are available");
        } catch (ClassNotFoundException ex) {
            return VerificationItem.failed("JavaFX runtime", "required JavaFX classes are missing");
        }
    }

    public VerificationItem verifyGraphicsEnvironment() {
        if (GraphicsEnvironment.isHeadless()) {
            return VerificationItem.warning(
                "Graphics environment",
                "headless environment detected; use CLI verification and skip JavaFX window launch"
            );
        }

        return VerificationItem.passed(
            "Graphics environment",
            "desktop graphics environment is available for JavaFX manual verification"
        );
    }
}
