package org.stvnadore.plugin.refactoring;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts.DialogMessage;
import com.intellij.patterns.ElementPattern;
import com.intellij.patterns.PatternCondition;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.rename.RenameInputValidatorEx;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.ValueKeyword;

import java.util.regex.Pattern;

/**
 * Validates identifier input in the IntelliJ rename refactoring dialog for STVN symbols.
 * <p>
 * Enforces strict sigil classification: type declarations and references require a leading colon
 * ({@code :}), whereas constant declarations and references require a leading hash ({@code #}).
 * Prevents cross-symbol refactoring errors and validates bare names for automatic sigil prefixing.
 */
@NullMarked
public final class StvnRenameInputValidator implements RenameInputValidatorEx {

    public static final String PREFIX_ERROR_MESSAGE =
        "Identifier must be a bare name without ':' or '#' prefix";

    public static final String KEYWORD_ERROR_MESSAGE =
        "Identifier cannot be a reserved section keyword: ";

    public static final String INVALID_IDENTIFIER_ERROR_MESSAGE =
        "Identifier is not a valid bare STVN identifier: ";

    private static final Pattern BARE_IDENTIFIER_PATTERN = Pattern.compile(
        "^[a-zA-Z_][a-zA-Z0-9_]*(/[a-zA-Z_][a-zA-Z0-9_]*)*$"
    );

    private final ElementPattern<? extends PsiElement> pattern;

    /**
     * Constructs an StvnRenameInputValidator and configures target element patterns.
     */
    public StvnRenameInputValidator() {
        this.pattern = PlatformPatterns.psiElement(PsiElement.class)
            .with(new PatternCondition<PsiElement>("stvnSymbolTarget") {
                @Override
                public boolean accepts(@NotNull PsiElement element, ProcessingContext context) {
                    return element instanceof TypeDefinition
                        || element instanceof TypeKeyword
                        || element instanceof ConstantDefinition
                        || element instanceof ValueKeyword
                        || element instanceof IncludeMapAlias
                        || element instanceof UseMapAlias;
                }
            });
    }

    @Override
    public @NotNull ElementPattern<? extends PsiElement> getPattern() {
        return pattern;
    }

    @Override
    public boolean isInputValid(@NotNull String newName, @NotNull PsiElement element, @NotNull ProcessingContext context) {
        if (newName.startsWith(":") || newName.startsWith("#")) {
            return false;
        }
        var namesValidator = new StvnNamesValidator();
        return namesValidator.isIdentifier(newName, element.getProject());
    }

    @Override
    public @Nullable @DialogMessage String getErrorMessage(@NotNull String newName, @NotNull Project project) {
        if (newName.startsWith(":") || newName.startsWith("#")) {
            return PREFIX_ERROR_MESSAGE;
        }
        var namesValidator = new StvnNamesValidator();
        if (namesValidator.isKeyword(newName, project)) {
            return KEYWORD_ERROR_MESSAGE + newName;
        }
        if (!namesValidator.isIdentifier(newName, project)) {
            return INVALID_IDENTIFIER_ERROR_MESSAGE + newName;
        }
        return null;
    }

    /**
     * Evaluates error messages directly for a specified target PSI element.
     *
     * @param newName the proposed new symbol name
     * @param element the target element undergoing rename
     * @param project the current project context
     * @return an error message string if invalid, or {@code null} if valid
     */
    public @Nullable @DialogMessage String getErrorMessage(
        @NotNull String newName,
        @NotNull PsiElement element,
        @NotNull Project project
    ) {
        return getErrorMessage(newName, project);
    }

    /**
     * Validates whether a bare name without leading sigils conforms to STVN identifier grammar.
     *
     * @param name the un-prefixed name string
     * @param project the project context, or {@code null}
     * @return {@code true} if the bare name is valid for auto-prefixing, {@code false} otherwise
     */
    public static boolean isValidBareIdentifier(String name, @Nullable Project project) {
        if (name.isEmpty()) {
            return false;
        }
        if (Character.isDigit(name.charAt(0))) {
            return false;
        }
        var namesValidator = new StvnNamesValidator();
        if (namesValidator.isKeyword(name, project)) {
            return false;
        }
        return BARE_IDENTIFIER_PATTERN.matcher(name).matches();
    }
}
