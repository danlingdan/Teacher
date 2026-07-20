package com.sqlteacher.application.ai;

import java.util.List;

/** Snapshot of the locally installed AI models and the model selected for new requests. */
public record AiModelSelection(
    List<String> installedModels,
    String selectedModel,
    String message
) {
    public AiModelSelection {
        installedModels = installedModels == null ? List.of() : List.copyOf(installedModels);
        selectedModel = selectedModel == null ? "" : selectedModel.strip();
        message = message == null ? "" : message;
        if (!selectedModel.isEmpty() && !installedModels.contains(selectedModel)) {
            throw new IllegalArgumentException("selectedModel must be installed");
        }
    }

    public boolean hasSelection() {
        return !selectedModel.isEmpty();
    }
}
