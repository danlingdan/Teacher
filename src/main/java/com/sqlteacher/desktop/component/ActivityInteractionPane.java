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
import com.sqlteacher.domain.activity.LabActivityArtifact;
import com.sqlteacher.domain.activity.LabActivitySpecification;
import com.sqlteacher.domain.activity.QuizActivityArtifact;
import com.sqlteacher.domain.activity.QuizActivitySpecification;
import com.sqlteacher.domain.activity.ProjectActivityArtifact;
import com.sqlteacher.domain.activity.ProjectActivitySpecification;
import com.sqlteacher.domain.activity.ReadingActivityArtifact;
import com.sqlteacher.domain.activity.ReadingActivitySpecification;
import com.sqlteacher.domain.activity.SimulationAction;
import com.sqlteacher.domain.activity.SimulationActivityArtifact;
import com.sqlteacher.domain.activity.SimulationActivitySpecification;
import com.sqlteacher.domain.activity.TraceActivityArtifact;
import com.sqlteacher.domain.activity.TraceActivitySpecification;
import com.sqlteacher.domain.activity.TraceNode;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
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
import java.util.concurrent.atomic.AtomicReference;
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
            case LabActivitySpecification lab -> renderLab(lab);
            case QuizActivitySpecification quiz -> renderQuiz(quiz);
            case ProjectActivitySpecification project -> renderProject(project);
            case ReadingActivitySpecification reading -> renderReading(reading);
            case SimulationActivitySpecification simulation -> renderSimulation(simulation);
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

    private void renderSimulation(SimulationActivitySpecification specification) {
        Map<String, com.sqlteacher.domain.activity.SimulationState> states = specification.statesById();
        Map<String, SimulationAction> actions = specification.actionsById();
        List<String> actionIds = new ArrayList<>();
        var currentState = new AtomicReference<>(specification.initialStateId());
        var visitedStates = new java.util.HashSet<String>();
        visitedStates.add(specification.initialStateId());
        var invalidStep = new AtomicBoolean();

        Label offline = new Label(AppI18n.get("simulation.offline"));
        offline.setWrapText(true);
        offline.getStyleClass().add("simulation-offline-note");
        VBox stateCard = new VBox(9);
        stateCard.getStyleClass().add("simulation-state-card");
        Label stateTitle = new Label();
        stateTitle.getStyleClass().add("simulation-state-title");
        Label stateDescription = new Label();
        stateDescription.setWrapText(true);
        VBox observations = new VBox(6);
        Label history = new Label();
        history.setWrapText(true);
        history.getStyleClass().add("simulation-history");
        Label explanation = new Label(AppI18n.get("simulation.chooseAction"));
        explanation.setWrapText(true);
        explanation.getStyleClass().add("simulation-explanation");
        stateCard.getChildren().addAll(stateTitle, stateDescription, observations);

        VBox checkpoints = new VBox(7);
        checkpoints.getStyleClass().add("simulation-checkpoints");
        Map<String, Label> checkpointLabels = new LinkedHashMap<>();
        for (var checkpoint : specification.checkpoints()) {
            Label item = new Label();
            item.setWrapText(true);
            item.getStyleClass().add("simulation-checkpoint");
            checkpointLabels.put(checkpoint.id(), item);
            checkpoints.getChildren().add(item);
        }

        FlowPane actionBar = new FlowPane(9, 9);
        actionBar.setPrefWrapLength(720);
        Map<String, Button> actionButtons = new LinkedHashMap<>();
        for (SimulationAction action : specification.actions()) {
            Button button = new Button(action.label());
            button.setAccessibleText(AppI18n.format("simulation.actionAccessible", action.label()));
            actionButtons.put(action.id(), button);
            actionBar.getChildren().add(button);
        }

        Runnable refresh = () -> {
            var state = states.get(currentState.get());
            stateTitle.setText(state.title());
            stateDescription.setText(state.description());
            observations.getChildren().setAll(state.observations().stream().map(value -> {
                Label item = new Label("• " + value);
                item.setWrapText(true);
                item.getStyleClass().add("simulation-observation");
                return item;
            }).toList());
            history.setText(actionIds.isEmpty() ? AppI18n.get("simulation.historyEmpty")
                : AppI18n.format("simulation.history", actionIds.stream().map(actions::get)
                    .filter(Objects::nonNull).map(SimulationAction::label)
                    .collect(java.util.stream.Collectors.joining(" → "))));
            for (var checkpoint : specification.checkpoints()) {
                boolean reached = visitedStates.contains(checkpoint.stateId());
                Label item = checkpointLabels.get(checkpoint.id());
                item.setText(AppI18n.format(reached ? "simulation.checkpointReached" : "simulation.checkpointPending",
                    checkpoint.title()));
                item.getStyleClass().removeAll("reached");
                if (reached) item.getStyleClass().add("reached");
            }
            boolean finished = currentState.get().equals(specification.goalStateId());
            actionButtons.forEach((id, button) -> {
                SimulationAction action = actions.get(id);
                button.setDisable(invalidStep.get() || finished);
                button.getStyleClass().removeAll("suggested");
                if (!invalidStep.get() && action.fromStateId().equals(currentState.get())) {
                    button.getStyleClass().add("suggested");
                }
            });
            stateCard.setAccessibleText(AppI18n.format("simulation.stateAccessible", state.title(),
                state.description()));
        };

        actionButtons.forEach((id, button) -> button.setOnAction(ignored -> {
            SimulationAction action = actions.get(id);
            actionIds.add(id);
            if (!action.fromStateId().equals(currentState.get())) {
                invalidStep.set(true);
                feedback.getStyleClass().removeAll("success");
                feedback.getStyleClass().add("danger");
                feedback.setText(AppI18n.format("simulation.invalidTransition", action.label()));
            } else {
                currentState.set(action.toStateId());
                visitedStates.add(action.toStateId());
                explanation.setText(action.explanation());
                pulse(stateCard);
            }
            refresh.run();
        }));

        Button undo = new Button(AppI18n.get("simulation.undo"));
        undo.setOnAction(ignored -> {
            if (actionIds.isEmpty()) return;
            actionIds.removeLast();
            replaySimulation(specification, actionIds, currentState, visitedStates);
            invalidStep.set(false);
            feedback.setText("");
            explanation.setText(AppI18n.get("simulation.chooseAction"));
            refresh.run();
        });
        Button reset = new Button(AppI18n.get("simulation.reset"));
        reset.setOnAction(ignored -> {
            actionIds.clear();
            currentState.set(specification.initialStateId());
            visitedStates.clear();
            visitedStates.add(specification.initialStateId());
            invalidStep.set(false);
            feedback.setText("");
            explanation.setText(AppI18n.get("simulation.chooseAction"));
            refresh.run();
        });
        Button submit = primaryButton(AppI18n.get("simulation.submit"));
        submit.setOnAction(ignored -> submit(submit, new SimulationActivityArtifact(actionIds)));
        HBox controls = new HBox(9, undo, reset, submit);
        controls.setAlignment(Pos.CENTER_RIGHT);
        refresh.run();
        content.getChildren().addAll(heading(specification.prompt()), offline, stateCard,
            new Label(AppI18n.get("simulation.actions")), actionBar, explanation,
            new Label(AppI18n.get("simulation.checkpoints")), checkpoints, history, controls);
    }

    private void renderProject(ProjectActivitySpecification specification) {
        VBox milestoneList = new VBox(9);
        List<CheckBox> milestoneChecks = new ArrayList<>();
        for (var milestone : specification.milestones()) {
            CheckBox item = new CheckBox(milestone.title() + " — " + milestone.acceptanceCriterion());
            item.setUserData(milestone.id());
            item.setWrapText(true);
            milestoneChecks.add(item);
            milestoneList.getChildren().add(item);
        }
        TextArea evidence = new TextArea();
        evidence.setPromptText(AppI18n.format("alpha7.projectEvidencePrompt",
            specification.minimumEvidenceCharacters()));
        evidence.setPrefRowCount(5);
        evidence.setWrapText(true);
        TextArea reflection = new TextArea();
        reflection.setPromptText(AppI18n.format("alpha7.projectReflectionPrompt",
            specification.minimumReflectionCharacters()));
        reflection.setPrefRowCount(4);
        reflection.setWrapText(true);
        Label reviewBoundary = new Label(AppI18n.get("alpha7.projectReviewBoundary"));
        reviewBoundary.setWrapText(true);
        reviewBoundary.getStyleClass().add("runner-boundary-note");
        Button submit = primaryButton(AppI18n.get("alpha7.submitProject"));
        submit.setDisable(true);
        var nextVersion = new java.util.concurrent.atomic.AtomicInteger(0);
        String requestedId = definition.id();
        DesktopExecutors.background().execute(() -> {
            try {
                int version = learningService.nextSubmissionVersion(requestedId);
                Platform.runLater(() -> {
                    if (definition != null && definition.id().equals(requestedId)) {
                        nextVersion.set(version);
                        submit.setText(AppI18n.format("alpha7.submitProjectVersion", version));
                        submit.setDisable(false);
                    }
                });
            } catch (RuntimeException error) {
                Platform.runLater(() -> feedback.setText(AppI18n.format("alpha3.evaluateFailed", safeMessage(error))));
            }
        });
        submit.setOnAction(ignored -> submit(submit, new ProjectActivityArtifact(
            nextVersion.get(),
            milestoneChecks.stream().filter(CheckBox::isSelected).map(item -> String.valueOf(item.getUserData())).toList(),
            evidence.getText(), reflection.getText()
        )));
        HBox actions = new HBox(submit);
        actions.setAlignment(Pos.CENTER_RIGHT);
        content.getChildren().addAll(heading(specification.prompt()), reviewBoundary,
            new Label(AppI18n.get("alpha7.projectMilestones")), milestoneList,
            new Label(AppI18n.get("alpha7.projectEvidence")), evidence,
            new Label(AppI18n.get("alpha7.projectReflection")), reflection, actions);
    }

    private void renderLab(LabActivitySpecification specification) {
        Map<String, CheckBox> completed = new LinkedHashMap<>();
        Map<String, TextArea> observations = new LinkedHashMap<>();
        VBox steps = new VBox(10);
        for (var step : specification.steps()) {
            VBox card = new VBox(7);
            card.getStyleClass().add("panel-card");
            CheckBox done = new CheckBox(step.title());
            done.setAccessibleText(AppI18n.format("beta.labStepAccessible", step.title()));
            Label instruction = new Label(step.instruction());
            instruction.setWrapText(true);
            TextArea observation = new TextArea();
            observation.setPromptText(AppI18n.get("beta.labObservationPrompt"));
            observation.setPrefRowCount(2);
            observation.setWrapText(true);
            completed.put(step.id(), done);
            observations.put(step.observationKey(), observation);
            card.getChildren().addAll(done, instruction, observation);
            steps.getChildren().add(card);
        }
        TextArea conclusion = new TextArea();
        conclusion.setPromptText(AppI18n.format("beta.labConclusionPrompt", specification.minimumConclusionCharacters()));
        conclusion.setPrefRowCount(4);
        conclusion.setWrapText(true);
        Button submit = primaryButton(AppI18n.get("beta.submitLab"));
        submit.setOnAction(ignored -> {
            Map<String, String> values = new LinkedHashMap<>();
            observations.forEach((key, value) -> values.put(key, value.getText()));
            submit(submit, new LabActivityArtifact(completed.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList(),
                values, conclusion.getText()));
        });
        content.getChildren().addAll(heading(specification.prompt()), steps,
            new Label(AppI18n.get("beta.labConclusion")), conclusion, submit);
    }

    private void renderReading(ReadingActivitySpecification specification) {
        Label provenance = new Label(AppI18n.format("beta.readingSource", specification.sourceTitle(),
            specification.license()));
        provenance.setWrapText(true);
        provenance.getStyleClass().add("runner-boundary-note");
        Label article = new Label(specification.content());
        article.setWrapText(true);
        article.getStyleClass().add("reading-content");
        CheckBox readToEnd = new CheckBox(AppI18n.get("beta.readingComplete"));
        Map<String, TextArea> answers = new LinkedHashMap<>();
        VBox checks = new VBox(10);
        for (var check : specification.checks()) {
            VBox card = new VBox(7);
            card.getStyleClass().add("panel-card");
            Label prompt = new Label(check.prompt());
            prompt.setWrapText(true);
            TextArea answer = new TextArea();
            answer.setPromptText(AppI18n.get("beta.readingAnswerPrompt"));
            answer.setPrefRowCount(2);
            answer.setWrapText(true);
            answers.put(check.id(), answer);
            card.getChildren().addAll(prompt, answer);
            checks.getChildren().add(card);
        }
        Button submit = primaryButton(AppI18n.get("beta.submitReading"));
        submit.setOnAction(ignored -> {
            Map<String, String> values = new LinkedHashMap<>();
            answers.forEach((key, value) -> values.put(key, value.getText()));
            submit(submit, new ReadingActivityArtifact(readToEnd.isSelected(), values));
        });
        content.getChildren().addAll(provenance, article, readToEnd, checks, submit);
    }

    private static void replaySimulation(SimulationActivitySpecification specification, List<String> actionIds,
                                         AtomicReference<String> currentState, java.util.Set<String> visitedStates) {
        currentState.set(specification.initialStateId());
        visitedStates.clear();
        visitedStates.add(specification.initialStateId());
        Map<String, SimulationAction> actions = specification.actionsById();
        for (String id : actionIds) {
            SimulationAction action = actions.get(id);
            if (action == null || !action.fromStateId().equals(currentState.get())) return;
            currentState.set(action.toStateId());
            visitedStates.add(action.toStateId());
        }
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
