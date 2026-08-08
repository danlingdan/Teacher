package com.sqlteacher.desktop.navigation;

import com.sqlteacher.application.system.CommandPaletteModel;
import javafx.collections.FXCollections;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.util.StringConverter;
import org.controlsfx.control.SearchableComboBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Search-only navigation surface. It never executes destructive commands. */
public final class ShellCommandPalette {
    public Optional<ShellRoute> show(List<ShellRoute> routes,
                                     Function<ShellRoute, String> labelProvider,
                                     String title,
                                     String heading,
                                     String prompt) {
        Objects.requireNonNull(routes);
        Objects.requireNonNull(labelProvider);
        CommandPaletteModel model = new CommandPaletteModel();
        routes.forEach(route -> model.register(
            route.id(), labelProvider.apply(route), route.keywords(), false, route.id()));

        SearchableComboBox<ShellRoute> picker = new SearchableComboBox<>(FXCollections.observableArrayList(routes));
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setPromptText(prompt);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(ShellRoute route) {
                return route == null ? "" : labelProvider.apply(route);
            }

            @Override
            public ShellRoute fromString(String text) {
                var matches = model.search(text, 1);
                if (matches.isEmpty()) return null;
                String target = matches.getFirst().target();
                return routes.stream().filter(route -> route.id().equals(target)).findFirst().orElse(null);
            }
        });
        if (!routes.isEmpty()) picker.setValue(routes.getFirst());

        Dialog<ShellRoute> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(heading);
        dialog.getDialogPane().setContent(picker);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? picker.getValue() : null);
        dialog.setOnShown(event -> picker.requestFocus());
        return dialog.showAndWait();
    }
}
