package com.sqlteacher.desktop.editor;

import com.sqlteacher.domain.activity.CodeLanguage;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;
import org.reactfx.Subscription;

import java.time.Duration;

/** Installs highlighting and explicit Ctrl+Space completion on a RichTextFX code area. */
public final class CodeEditorSupport {
    private CodeEditorSupport() { }

    public static Subscription install(CodeArea editor, CodeLanguage language) {
        ContextMenu completion = new ContextMenu();
        completion.getStyleClass().add("code-completion-menu");
        Runnable highlight = () -> editor.setStyleSpans(0,
            CodeSyntaxHighlighter.highlight(language, editor.getText()));
        highlight.run();
        Subscription changes = editor.multiPlainChanges().successionEnds(Duration.ofMillis(90))
            .subscribe(ignored -> {
                highlight.run();
                if (completion.isShowing()) showCompletions(editor, language, completion);
            });
        EventHandler<KeyEvent> keys = event -> {
            if (event.getCode() == KeyCode.SPACE && event.isControlDown()) {
                showCompletions(editor, language, completion);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                completion.hide();
            }
        };
        editor.addEventFilter(KeyEvent.KEY_PRESSED, keys);
        var focus = new javafx.beans.value.ChangeListener<Boolean>() {
            @Override public void changed(javafx.beans.value.ObservableValue<? extends Boolean> observable,
                                          Boolean oldValue, Boolean focused) {
                if (!focused) completion.hide();
            }
        };
        editor.focusedProperty().addListener(focus);
        return () -> {
            changes.unsubscribe();
            completion.hide();
            editor.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
            editor.focusedProperty().removeListener(focus);
        };
    }

    private static void showCompletions(CodeArea editor, CodeLanguage language, ContextMenu completion) {
        int caret = editor.getCaretPosition();
        String source = editor.getText();
        var suggestions = CodeCompletionCatalog.suggest(language, source, caret, 12);
        completion.getItems().clear();
        int start = CodeCompletionCatalog.prefixStart(source, caret);
        for (String suggestion : suggestions) {
            MenuItem item = new MenuItem(suggestion);
            item.setOnAction(ignored -> {
                editor.replaceText(start, editor.getCaretPosition(), suggestion);
                editor.requestFocus();
            });
            completion.getItems().add(item);
        }
        if (suggestions.isEmpty()) {
            completion.hide();
            return;
        }
        Bounds caretBounds = editor.getCaretBounds().orElse(null);
        if (caretBounds == null) return;
        if (completion.isShowing()) completion.hide();
        completion.show(editor, caretBounds.getMinX(), caretBounds.getMaxY());
    }
}
