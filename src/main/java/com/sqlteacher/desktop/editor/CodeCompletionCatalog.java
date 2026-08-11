package com.sqlteacher.desktop.editor;

import com.sqlteacher.domain.activity.CodeLanguage;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic local completions: language keywords plus identifiers already present in the source. */
public final class CodeCompletionCatalog {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Map<CodeLanguage, List<String>> TERMS = terms();

    private CodeCompletionCatalog() { }

    public static List<String> suggest(CodeLanguage language, String source, int caretPosition, int limit) {
        String code = source == null ? "" : source;
        int caret = Math.max(0, Math.min(caretPosition, code.length()));
        String prefix = code.substring(prefixStart(code, caret), caret);
        LinkedHashSet<String> candidates = new LinkedHashSet<>(TERMS.get(language));
        IDENTIFIER.matcher(code).results().map(result -> result.group()).forEach(candidates::add);
        return candidates.stream()
            .filter(candidate -> !candidate.equals(prefix) && candidate.startsWith(prefix))
            .sorted()
            .limit(Math.max(1, limit))
            .toList();
    }

    public static int prefixStart(String source, int caretPosition) {
        String code = source == null ? "" : source;
        int start = Math.max(0, Math.min(caretPosition, code.length()));
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) start--;
        return start;
    }

    private static Map<CodeLanguage, List<String>> terms() {
        Map<CodeLanguage, List<String>> values = new EnumMap<>(CodeLanguage.class);
        values.put(CodeLanguage.JAVA, words("boolean break case catch char class continue default do double else enum extends false final finally float for if implements import instanceof int interface long new null package private protected public record return short static super switch this throw throws true try var void while System Scanner String Math ArrayList List Map"));
        values.put(CodeLanguage.PYTHON, words("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda match None nonlocal not or pass raise return True try while with yield print input int float str list dict set range enumerate zip len open"));
        values.put(CodeLanguage.C, words("auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while printf scanf malloc free NULL size_t"));
        values.put(CodeLanguage.CPP, words("alignas auto bool break case catch char class const constexpr continue default delete do double else enum explicit extern false float for friend if inline int long namespace new nullptr operator override private protected public return short signed sizeof static struct switch template this throw true try typedef typename union unsigned using virtual void volatile while std string vector map set cout cin endl"));
        return Map.copyOf(values);
    }

    private static List<String> words(String value) {
        return Arrays.stream(value.split("\\s+")).toList();
    }
}
