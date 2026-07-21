package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Tab;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

public final class SettingsController {
    private final ConnectionManagementService connectionManagementService;
    private final DatabaseConnectionTestService databaseConnectionTestService;
    private final ApplicationExceptionMapper applicationExceptionMapper;
    private final DatabaseCredentialSession databaseCredentialSession;
    private final ApplicationBackupService backupService;
    private final SqlTeacherConfiguration configuration;

    @FXML private Tab connectionsTab;
    @FXML private Tab dataTab;

    public SettingsController(
            ConnectionManagementService connectionManagementService,
            DatabaseConnectionTestService databaseConnectionTestService,
            ApplicationExceptionMapper applicationExceptionMapper,
            DatabaseCredentialSession databaseCredentialSession,
            ApplicationBackupService backupService,
            SqlTeacherConfiguration configuration) {
        this.connectionManagementService = Objects.requireNonNull(connectionManagementService);
        this.databaseConnectionTestService = Objects.requireNonNull(databaseConnectionTestService);
        this.applicationExceptionMapper = Objects.requireNonNull(applicationExceptionMapper);
        this.databaseCredentialSession = Objects.requireNonNull(databaseCredentialSession);
        this.backupService = Objects.requireNonNull(backupService);
        this.configuration = Objects.requireNonNull(configuration);
    }

    @FXML
    private void initialize() {
        connectionsTab.setContent(load("/fxml/connection-settings.fxml", ConnectionSettingsController.class));
        dataTab.setContent(load("/fxml/data-maintenance.fxml", DataMaintenanceController.class));
    }

    private Node load(String resource, Class<?> controllerType) {
        URL fxml = SettingsController.class.getResource(resource);
        if (fxml == null) {
            throw new IllegalStateException("Missing FXML resource: " + resource);
        }
        FXMLLoader loader = new FXMLLoader(fxml);
        loader.setControllerFactory(type -> {
            if (type == ConnectionSettingsController.class && controllerType == type) {
                return new ConnectionSettingsController(
                    connectionManagementService,
                    databaseConnectionTestService,
                    applicationExceptionMapper,
                    databaseCredentialSession
                );
            }
            if (type == DataMaintenanceController.class && controllerType == type) {
                return new DataMaintenanceController(backupService, configuration);
            }
            throw new IllegalStateException("Unexpected controller type: " + type);
        });
        try {
            return loader.load();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load " + resource, error);
        }
    }
}
