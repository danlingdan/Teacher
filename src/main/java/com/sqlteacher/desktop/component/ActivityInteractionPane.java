package com.sqlteacher.desktop.component;

import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivitySubmission;
import com.sqlteacher.application.runner.CodeRunRequest;
import com.sqlteacher.application.runner.LocalCodeRunner;
import com.sqlteacher.application.runner.LocalCodeWorkspaceLauncher;
import com.sqlteacher.desktop.AppI18n;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.appearance.UiPreferencesService;
import com.sqlteacher.domain.activity.CodeActivityArtifact;
import com.sqlteacher.domain.activity.CodeActivitySpecification;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.QuizActivitySpecification;
import com.sqlteacher.domain.activity.TraceActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivitySpecification;
import com.sqlteacher.domain.activity.TraceNode;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Extensible non-SQL activity surface. Evaluation remains in application services. */
public final class ActivityInteractionPane extends VBox {
    private static final Logger LOG = LoggerFactory.getLogger(ActivityInteractionPane.class);
    private final ActivityLearningService learningService;
    private final LocalCodeRunner localCodeRunner;
    private final LocalCodeWorkspaceLauncher localWorkspaceLauncher;
    private final Consumer<ActivitySubmission> onSubmitted;
    private final Label feedback = new Label();
    private final Label teacherFeedback = new Label();
    private final VBox content = new VBox(16);
    private LearningActivityDefinition definition;
    private AtomicBoolean currentRunCancellation;

    public ActivityInteractionPane(ActivityLearningService learningService,
                                   LocalCodeRunner localCodeRunner,
                                   LocalCodeWorkspaceLauncher localWorkspaceLauncher,
                                   Consumer<ActivitySubmission> onSubmitted) {
        this.learningService = Objects.requireNonNull(learningService);
        this.localCodeRunner = Objects.requireNonNull(localCodeRunner);
        this.localWorkspaceLauncher = Objects.requireNonNull(localWorkspaceLauncher);
        this.onSubmitted = Objects.requireNonNull(onSubmitted);
        getStyleClass().add("activity-interaction-pane");
        setSpacing(14);
        setPadding(new Insets(20));
        feedback.setWrapText(true);
        feedback.getStyleClass().add("activity-feedback");
        teacherFeedback.setWrapText(true);
        teacherFeedback.getStyleClass().addAll("activity-feedback", "teacher-feedback");
        teacherFeedback.setVisible(false);
        teacherFeedback.setManaged(false);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("activity-interaction-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(scroll, feedback, teacherFeedback);
    }

    public void show(LearningActivityDefinition definition) {
        if (currentRunCancellation != null) currentRunCancellation.set(true);
        currentRunCancellation = null;
        this.definition = Objects.requireNonNull(definition);
        feedback.setText("");
        teacherFeedback.setVisible(false);
        teacherFeedback.setManaged(false);
        content.getChildren().clear();
        switch (definition.specification()) {
            case CodeActivitySpecification code -> renderCode(code);
            case QuizActivitySpecification quiz -> renderQuiz(quiz);
            case TraceActivitySpecification trace -> renderTrace(trace);
            default -> showUnsupported();
        }
        loadTeacherFeedback(definition.id());
    }

    private void renderCode(CodeActivitySpecification specification) {
        Label boundary = new Label(AppI18n.format("alpha4.ideRunner", specification.language()));
        boundary.setWrapText(true);
        boundary.getStyleClass().add("runner-boundary-note");
        CodeArea editor = new CodeArea();
        editor.replaceText(specification.starterCode());
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.setPrefHeight(420);
        editor.getStyleClass().add("code-activity-editor");
        TextArea input = new TextArea();
        input.setPromptText(AppI18n.get("alpha4.stdinPrompt"));
        input.setPrefRowCount(3);
        TextArea console = new TextArea();
        console.setEditable(false);
        console.setPromptText(AppI18n.get("alpha4.consolePrompt"));
        console.setPrefRowCount(8);
        console.getStyleClass().add("code-console");
        Button localRun = primaryButton(AppI18n.get("alpha4.runLocal"));
        Button safeTests = new Button(AppI18n.get("alpha4.runTests"));
        Button cancel = new Button(AppI18n.get("alpha4.cancel"));
        cancel.setDisable(true);
        localRun.setOnAction(ignored -> runLocal(localRun, safeTests, cancel, console,
            new CodeRunRequest(specification.language(), editor.getText(), input.getText(), specification.limits())));
        safeTests.setOnAction(ignored -> submitCode(safeTests, localRun, cancel,
            new CodeActivityArtifact(specification.language(), editor.getText())));
        cancel.setOnAction(ignored -> {
            AtomicBoolean cancellation = currentRunCancellation;
            if (cancellation != null) {
                cancellation.set(true);
                cancel.setDisable(true);
                feedback.setText(AppI18n.get("alpha4.cancelling"));
            }
        });
        HBox actions = new HBox(10);
        Button terminal = new Button(AppI18n.get("alpha4.openLocal"));
        terminal.setOnAction(ignored -> openLocalWorkspace(terminal, specification, editor.getText()));
        actions.getChildren().addAll(terminal, cancel, safeTests, localRun);
        actions.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().addAll(heading(specification.prompt()), boundary, editor,
            new Label(AppI18n.get("alpha4.stdin")), input,
            new Label(AppI18n.get("alpha4.console")), console, actions);
    }

    private void openLocalWorkspace(Button button, CodeActivitySpecification specification, String sourceCode) {
        button.setDisable(true);
        feedback.setText(AppI18n.get("alpha4.openingLocal"));
        DesktopExecutors.background().execute(() -> {
            try {
                var workspace = localWorkspaceLauncher.open(specification.language(), sourceCode);
                Platform.runLater(() -> {
                    button.setDisable(false);
                    feedback.setText(AppI18n.format("alpha4.localOpened", workspace.environmentName(),
                        workspace.directory()));
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    button.setDisable(false);
                    feedback.getStyleClass().add("danger");
                    feedback.setText(AppI18n.format("alpha4.localFailed", safeMessage(error)));
                });
            }
        });
    }

    private void runLocal(Button localRun, Button safeTests, Button cancel, TextArea console,
                          CodeRunRequest request) {
        localRun.setDisable(true);
        safeTests.setDisable(true);
        cancel.setDisable(false);
        console.setText(AppI18n.get("alpha4.localRunning"));
        feedback.getStyleClass().removeAll("success", "danger");
        feedback.setText(AppI18n.get("alpha4.localResponsibility"));
        String requestedId = definition.id();
        AtomicBoolean cancellation = new AtomicBoolean();
        currentRunCancellation = cancellation;
        DesktopExecutors.background().execute(() -> {
            var result = localCodeRunner.run(request, cancellation::get);
            Platform.runLater(() -> {
                if (definition == null || !definition.id().equals(requestedId)
                        || currentRunCancellation != cancellation) return;
                currentRunCancellation = null;
                localRun.setDisable(false);
                safeTests.setDisable(false);
                cancel.setDisable(true);
                console.setText(localConsole(result));
                boolean succeeded = result.succeeded();
                feedback.getStyleClass().add(succeeded ? "success" : "danger");
                feedback.setText(succeeded ? AppI18n.get("alpha4.localSucceeded")
                    : cancellation.get() ? AppI18n.get("alpha4.cancelled")
                    : AppI18n.format("alpha4.localRunFailed", result.failureReason()));
            });
        });
    }

    private void renderQuiz(QuizActivitySpecification specification) {
        content.getChildren().add(heading(definition.description()));
        Map<String, ToggleGroup> answers = new LinkedHashMap<>();
        for (var question : specification.questions()) {
            VBox card = new VBox(10);
            card.getStyleClass().addAll("panel-card", "quiz-question-card");
            ToggleGroup choices = new ToggleGroup();
            answers.put(question.id(), choices);
            card.getChildren().add(heading(question.prompt()));
            for (var option : question.options()) {
                RadioButton choice = new RadioButton(option.text());
                choice.setUserData(option.id());
                choice.setToggleGroup(choices);
                choice.getStyleClass().add("quiz-option");
                card.getChildren().add(choice);
            }
            content.getChildren().add(card);
        }
        Button submit = primaryButton(AppI18n.get("alpha3.submitQuiz"));
        submit.setOnAction(ignored -> {
            Map<String, String> selected = new LinkedHashMap<>();
            answers.forEach((questionId, group) -> {
                if (group.getSelectedToggle() != null) {
                    selected.put(questionId, String.valueOf(group.getSelectedToggle().getUserData()));
                }
            });
            submit(submit, new QuizActivityArtifact(selected));
        });
        content.getChildren().add(submit);
    }

    private void renderTrace(TraceActivitySpecification specification) {
        Label sequence = new Label(AppI18n.get("alpha3.trace.empty"));
        sequence.getStyleClass().add("trace-sequence");
        List<String> visited = new ArrayList<>();
        Map<String, Button> buttons = new HashMap<>();
        Pane tree = treePane(specification, buttons);
        buttons.forEach((id, button) -> button.setOnAction(ignored -> {
            visited.add(id);
            button.setDisable(true);
            sequence.setText(AppI18n.format("alpha3.trace.sequence", labels(specification, visited)));
            pulse(button);
        }));
        Button undo = new Button(AppI18n.get("alpha3.trace.undo"));
        undo.setOnAction(ignored -> {
            if (visited.isEmpty()) return;
            String removed = visited.removeLast();
            buttons.get(removed).setDisable(false);
            sequence.setText(visited.isEmpty() ? AppI18n.get("alpha3.trace.empty")
                : AppI18n.format("alpha3.trace.sequence", labels(specification, visited)));
        });
        Button reset = new Button(AppI18n.get("alpha3.trace.reset"));
        reset.setOnAction(ignored -> {
            visited.clear();
            buttons.values().forEach(button -> button.setDisable(false));
            sequence.setText(AppI18n.get("alpha3.trace.empty"));
            feedback.setText("");
        });
        Button submit = primaryButton(AppI18n.get("alpha3.submitTrace"));
        submit.setOnAction(ignored -> submit(submit, new TraceActivityArtifact(visited)));
        HBox actions = new HBox(10, undo, reset, submit);
        actions.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().addAll(heading(specification.prompt()), tree, sequence, actions);
    }

    private Pane treePane(TraceActivitySpecification specification, Map<String, Button> buttons) {
        Pane pane = new Pane();
        pane.setMinSize(560, 310);
        pane.setPrefSize(620, 310);
        pane.getStyleClass().add("binary-tree-canvas");
        Map<String, TraceNode> nodes = new LinkedHashMap<>();
        specification.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, Position> positions = positions(specification, nodes);
        for (TraceNode node : specification.nodes()) {
            Position from = positions.get(node.id());
            addEdge(pane, from, positions.get(node.leftChildId()));
            addEdge(pane, from, positions.get(node.rightChildId()));
        }
        for (TraceNode node : specification.nodes()) {
            Position position = positions.get(node.id());
            Button button = new Button(node.label());
            button.setAccessibleText(AppI18n.format("alpha3.trace.node", node.label()));
            button.setLayoutX(position.x() - 24);
            button.setLayoutY(position.y() - 24);
            button.getStyleClass().add("tree-node-button");
            buttons.put(node.id(), button);
            pane.getChildren().add(button);
        }
        return pane;
    }

    private static Map<String, Position> positions(TraceActivitySpecification specification,
                                                   Map<String, TraceNode> nodes) {
        Map<Integer, List<String>> levels = new LinkedHashMap<>();
        var queue = new ArrayDeque<NodeDepth>();
        queue.add(new NodeDepth(specification.rootNodeId(), 0));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            levels.computeIfAbsent(current.depth(), ignored -> new ArrayList<>()).add(current.id());
            TraceNode node = nodes.get(current.id());
            if (!node.leftChildId().isEmpty()) queue.addLast(new NodeDepth(node.leftChildId(), current.depth() + 1));
            if (!node.rightChildId().isEmpty()) queue.addLast(new NodeDepth(node.rightChildId(), current.depth() + 1));
        }
        Map<String, Position> result = new HashMap<>();
        levels.forEach((depth, ids) -> {
            for (int index = 0; index < ids.size(); index++) {
                result.put(ids.get(index), new Position(620.0 * (index + 1) / (ids.size() + 1), 52 + depth * 100));
            }
        });
        return result;
    }

    private static void addEdge(Pane pane, Position from, Position to) {
        if (from == null || to == null) return;
        Line line = new Line(from.x(), from.y(), to.x(), to.y());
        line.getStyleClass().add("tree-edge");
        pane.getChildren().add(line);
    }

    private void submit(Button button, com.sqlteacher.domain.activity.ActivityArtifact artifact) {
        button.setDisable(true);
        feedback.getStyleClass().removeAll("success", "danger");
        feedback.setText(AppI18n.get("alpha3.evaluating"));
        String requestedId = definition.id();
        DesktopExecutors.background().execute(() -> {
            try {
                ActivitySubmission submission = learningService.submit(requestedId, artifact);
                Platform.runLater(() -> {
                    if (definition == null || !definition.id().equals(requestedId)) return;
                    button.setDisable(false);
                    feedback.getStyleClass().add(submission.evaluation().passed() ? "success" : "danger");
                    feedback.setText(submission.evaluation().summary() + " " + firstFailure(submission));
                    onSubmitted.accept(submission);
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    if (definition == null || !definition.id().equals(requestedId)) return;
                    button.setDisable(false);
                    feedback.getStyleClass().add("danger");
                    feedback.setText(AppI18n.format("alpha3.evaluateFailed", safeMessage(error)));
                });
            }
        });
    }

    private void submitCode(Button run, Button localRun, Button cancel, CodeActivityArtifact artifact) {
        run.setDisable(true);
        localRun.setDisable(true);
        cancel.setDisable(false);
        feedback.getStyleClass().removeAll("success", "danger");
        feedback.setText(AppI18n.get("alpha4.running"));
        String requestedId = definition.id();
        AtomicBoolean cancellation = new AtomicBoolean();
        currentRunCancellation = cancellation;
        DesktopExecutors.background().execute(() -> {
            try {
                ActivitySubmission submission = learningService.submit(requestedId, artifact, cancellation::get);
                Platform.runLater(() -> {
                    if (definition == null || !definition.id().equals(requestedId)
                            || currentRunCancellation != cancellation) return;
                    currentRunCancellation = null;
                    run.setDisable(false);
                    localRun.setDisable(false);
                    cancel.setDisable(true);
                    feedback.getStyleClass().add(submission.evaluation().passed() ? "success" : "danger");
                    feedback.setText(submission.evaluation().summary() + " " + firstFailure(submission));
                    onSubmitted.accept(submission);
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> {
                    if (definition == null || !definition.id().equals(requestedId)
                            || currentRunCancellation != cancellation) return;
                    currentRunCancellation = null;
                    run.setDisable(false);
                    localRun.setDisable(false);
                    cancel.setDisable(true);
                    feedback.getStyleClass().add("danger");
                    feedback.setText(cancellation.get() ? AppI18n.get("alpha4.cancelled")
                        : AppI18n.format("alpha3.evaluateFailed", safeMessage(error)));
                });
            }
        });
    }

    private static String localConsole(com.sqlteacher.application.runner.CodeRunResult result) {
        StringBuilder value = new StringBuilder();
        if (!result.standardOutput().isEmpty()) value.append(result.standardOutput());
        if (!result.standardError().isEmpty()) {
            if (!value.isEmpty() && value.charAt(value.length() - 1) != '\n') value.append('\n');
            value.append(result.standardError());
        }
        if (!value.isEmpty() && value.charAt(value.length() - 1) != '\n') value.append('\n');
        value.append(AppI18n.format("alpha4.processSummary", result.exitCode(), result.failureReason(),
            result.resourceUsage().wallTime().toMillis()));
        return value.toString();
    }

    private static String firstFailure(ActivitySubmission submission) {
        return submission.evaluation().criteria().stream().filter(item -> !item.passed())
            .map(item -> item.feedback()).findFirst().orElse("");
    }

    private void loadTeacherFeedback(String activityId) {
        DesktopExecutors.background().execute(() -> {
            try {
                var latest = learningService.latestFeedback(activityId);
                Platform.runLater(() -> {
                    if (definition == null || !definition.id().equals(activityId)) return;
                    teacherFeedback.setVisible(latest.isPresent());
                    teacherFeedback.setManaged(latest.isPresent());
                    teacherFeedback.setText(latest.map(item -> AppI18n.format(
                        "alpha3.teacherFeedback", item.comment())).orElse(""));
                });
            } catch (RuntimeException error) {
                LOG.warn("Activity feedback could not be loaded, exceptionType={}",
                    error.getClass().getSimpleName());
            }
        });
    }

    private void showUnsupported() {
        StatePanel state = new StatePanel();
        state.setTitle(AppI18n.get("alpha3.unsupported.title"));
        state.setMessage(AppI18n.get("alpha3.unsupported.message"));
        content.getChildren().setAll(state);
    }

    private static Label heading(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add("activity-prompt");
        return label;
    }

    private static Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary");
        return button;
    }

    private static String labels(TraceActivitySpecification specification, List<String> ids) {
        Map<String, String> labels = new HashMap<>();
        specification.nodes().forEach(node -> labels.put(node.id(), node.label()));
        return ids.stream().map(labels::get).collect(java.util.stream.Collectors.joining(" → "));
    }

    private static void pulse(Node node) {
        if (UiPreferencesService.shared().current().reducedMotion()) return;
        ScaleTransition transition = new ScaleTransition(Duration.millis(120), node);
        transition.setFromX(0.86); transition.setFromY(0.86);
        transition.setToX(1); transition.setToY(1);
        transition.play();
    }

    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? AppI18n.get("alpha2.unknownError") : error.getMessage();
    }

    private record Position(double x, double y) { }
    private record NodeDepth(String id, int depth) { }
}
