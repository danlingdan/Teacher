package com.sqlteacher.application.ai;

import java.util.List;

/** Discovers locally installed models and controls which model is used for future AI requests. */
public interface AiModelSelectionService {
    AiModelSelection refresh();

    AiModelSelection current();

    AiModelSelection select(String model);

    static AiModelSelectionService fixed(String model) {
        AiModelSelection selection = new AiModelSelection(List.of(model), model, "Fixed model");
        return new AiModelSelectionService() {
            @Override
            public AiModelSelection refresh() {
                return selection;
            }

            @Override
            public AiModelSelection current() {
                return selection;
            }

            @Override
            public AiModelSelection select(String selectedModel) {
                if (!model.equals(selectedModel)) {
                    throw new IllegalArgumentException("model is not installed: " + selectedModel);
                }
                return selection;
            }
        };
    }
}
