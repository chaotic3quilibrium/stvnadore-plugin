package org.stvnadore.plugin.refactoring;

import com.intellij.lang.refactoring.NamesValidator;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates identifier syntax and reserved keywords for STVN rename refactoring operations.
 * <p>
 * Enforces mandatory prefix syntax (leading colon {@code :} for type symbols and hash {@code #} for value symbols),
 * non-digit starting characters, valid alphanumeric character sets, and rejection of reserved section keywords.
 */
@NullMarked
public final class StvnNamesValidator implements NamesValidator {

    private static final Set<String> RESERVED_KEYWORDS = Set.of(
        "defs",
        "type",
        "body",
        "package",
        "use",
        "include",
        ":defs",
        ":type",
        ":body",
        ":package",
        ":use",
        ":include"
    );

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
        "^[a-zA-Z_][a-zA-Z0-9_]*(/[a-zA-Z_][a-zA-Z0-9_]*)*$"
    );

    /**
     * Constructs an StvnNamesValidator instance.
     */
    public StvnNamesValidator() {}

    /**
     * Checks if the specified name represents a reserved STVN section keyword.
     *
     * @param name the symbol name to evaluate
     * @param project the project context, or {@code null}
     * @return {@code true} if the name matches a reserved section keyword, {@code false} otherwise
     */
    @Override
    public boolean isKeyword(@NotNull String name, @Nullable Project project) {
        var trimmed = name.trim();
        return RESERVED_KEYWORDS.contains(trimmed);
    }

    /**
     * Validates whether the specified name adheres to STVN identifier naming grammar.
     * Requires a valid prefix (colon {@code :} or hash {@code #}), rejects whitespace,
     * leading digits following the prefix, and reserved section keywords.
     *
     * @param name the symbol name to validate
     * @param project the project context, or {@code null}
     * @return {@code true} if the name is a syntactically valid STVN identifier, {@code false} otherwise
     */
    @Override
    public boolean isIdentifier(@NotNull String name, @Nullable Project project) {
        if (name.isEmpty()) {
            return false;
        }
        if (name.startsWith(":") || name.startsWith("#")) {
            return false;
        }
        if (isKeyword(name, project)) {
            return false;
        }
        return IDENTIFIER_PATTERN.matcher(name).matches();
    }
}
