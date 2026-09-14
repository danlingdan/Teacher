package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.component.ManagedComponentService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.runner.LocalCodeWorkspaceLauncher;
import com.sqlteacher.application.support.DiagnosticBundleService;
import com.sqlteacher.application.support.ProblemReportService;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.update.UpdateService;
import com.sqlteacher.infrastructure.component.WindowsManagedComponentService;
import com.sqlteacher.infrastructure.runner.WindowsLocalCodeWorkspaceLauncher;
import com.sqlteacher.infrastructure.runner.WindowsLocalIdeCodeRunner;
import com.sqlteacher.infrastructure.support.FileDiagnosticBundleService;
import com.sqlteacher.infrastructure.support.HttpProblemReportService;
import com.sqlteacher.infrastructure.system.FileGeneralSoftwareService;
import com.sqlteacher.infrastructure.update.SecureUpdateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Platform services: local code runners, managed components, general-software catalog,
 * diagnostic bundles, problem reports, and secure updates
 * (v3.4.0 REF-20 split of SqlTeacherApplicationConfig).
 */
@Configuration
public class SystemServiceConfig {

    @Bean
    public LocalCodeWorkspaceLauncher localCodeWorkspaceLauncher(SqlTeacherConfiguration configuration) {
        return new WindowsLocalCodeWorkspaceLauncher(
            configuration.dataDirectory().resolve("local-code-workspaces")
        );
    }

    @Bean
    public LocalCodeRunner localCodeRunner(SqlTeacherConfiguration configuration) {
        return new WindowsLocalIdeCodeRunner(configuration.dataDirectory().resolve("local-code-runs"));
    }

    @Bean
    public ManagedComponentService managedComponentService() {
        return new WindowsManagedComponentService();
    }

    @Bean public GeneralSoftwareService generalSoftwareService(SqlTeacherConfiguration configuration, URI cloudBaseUri) {
        return new FileGeneralSoftwareService(configuration.dataDirectory(), cloudBaseUri);
    }

    @Bean public DiagnosticBundleService diagnosticBundleService(SqlTeacherConfiguration configuration) {
        return new FileDiagnosticBundleService(configuration.dataDirectory());
    }

    @Bean public ProblemReportService problemReportService(SqlTeacherConfiguration configuration, URI cloudBaseUri,
                                                            GeneralSoftwareService generalSoftwareService) {
        return new HttpProblemReportService(cloudBaseUri, configuration.dataDirectory(), generalSoftwareService);
    }

    @Bean public UpdateService updateService(SqlTeacherConfiguration configuration, URI cloudBaseUri,
                                              GeneralSoftwareService generalSoftwareService) {
        return new SecureUpdateService(cloudBaseUri, configuration.dataDirectory(), generalSoftwareService);
    }
}
