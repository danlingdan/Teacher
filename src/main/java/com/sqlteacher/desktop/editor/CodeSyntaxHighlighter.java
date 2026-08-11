package com.sqlteacher.desktop.editor;

import com.sqlteacher.domain.activity.CodeLanguage;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Visual-only highlighting for built-in programming activities. */
public final class CodeSyntaxHighlighter {
    private static final Map<CodeLanguage, Pattern> PATTERNS = patterns();

    private CodeSyntaxHighlighter() { }

    public static StyleSpans<Collection<String>> highlight(CodeLanguage language, String code) {
        String source = code == null ? "" : code;
        Matcher matcher = PATTERNS.get(language).matcher(source);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int last = 0;
        while (matcher.find()) {
            spans.add(Collections.emptyList(), matcher.start() - last);
            String style = matcher.group("COMMENT") != null ? "code-comment"
                : matcher.group("STRING") != null ? "code-string"
                : matcher.group("KEYWORD") != null ? "code-keyword"
                : matcher.group("NUMBER") != null ? "code-number"
                : "code-directive";
            spans.add(Collections.singleton(style), matcher.end() - matcher.start());
            last = matcher.end();
        }
        spans.add(Collections.emptyList(), source.length() - last);
        return spans.create();
    }

    private static Map<CodeLanguage, Pattern> patterns() {
        Map<CodeLanguage, Pattern> values = new EnumMap<>(CodeLanguage.class);
        String javaKeywords = "abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|record|return|sealed|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|var|void|volatile|while|yield|true|false|null";
        String pythonKeywords = "and|as|assert|async|await|break|class|continue|def|del|elif|else|except|False|finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|or|pass|raise|return|True|try|while|with|yield|match|case";
        String cKeywords = "auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|inline|int|long|register|restrict|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while|_Bool|class|namespace|template|typename|public|private|protected|virtual|override|constexpr|decltype|nullptr|new|delete|using|try|catch|throw|true|false|this|friend|operator|std";
        values.put(CodeLanguage.JAVA, pattern(javaKeywords, "//[^\\r\\n]*|/\\*(?:.|\\R)*?\\*/", "@[A-Za-z_$][\\w$]*"));
        values.put(CodeLanguage.PYTHON, pattern(pythonKeywords, "#[^\\r\\n]*", "@[A-Za-z_][\\w.]*"));
        Pattern nativePattern = pattern(cKeywords, "//[^\\r\\n]*|/\\*(?:.|\\R)*?\\*/", "(?m)^\\s*#\\s*[A-Za-z_]+[^\\r\\n]*");
        values.put(CodeLanguage.C, nativePattern);
        values.put(CodeLanguage.CPP, nativePattern);
        return Map.copyOf(values);
    }

    private static Pattern pattern(String keywords, String comments, String directives) {
        return Pattern.compile(
            "(?<COMMENT>" + comments + ")|" +
                "(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
                "(?<KEYWORD>\\b(?:" + keywords + ")\\b)|" +
                "(?<NUMBER>\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b)|" +
                "(?<DIRECTIVE>" + directives + ")"
        );
    }
}
