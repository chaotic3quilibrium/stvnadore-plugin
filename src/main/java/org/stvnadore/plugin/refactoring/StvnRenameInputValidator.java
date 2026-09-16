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

    /**
     * Target symbol category for rename validation.
     */
    public enum TargetKind {
        /**
         * Represents STVN nominal type declarations and references.
         */
        TYPE,
        /**
         * Represents STVN constant bindings, enum variants, and value keywords.
         */
        CONSTANT
    }

    /**
     * Error message displayed when a hash sigil is provided for a type symbol.
     */
    public static final String TYPE_ERROR_MESSAGE =
        "Type identifier cannot begin with '#'; type declarations must begin with ':'";

    /**
     * Error message displayed when a colon sigil is provided for a constant symbol.
     */
    public static final String CONSTANT_ERROR_MESSAGE =
        "Constant identifier cannot begin with ':'; constant declarations must begin with '#'";

    private static final Pattern BARE_IDENTIFIER_PATTERN = Pattern.compile(
        "^[a-zA-Z_][a-zA-Z0-9_]*(/[a-zA-Z_][a-zA-Z0-9_]*)*$"
    );

    private static final ThreadLocal<TargetKind> CURRENT_TARGET = new ThreadLocal<>();

    private final ElementPattern<? extends PsiElement> pattern;

    /**
     * Constructs an StvnRenameInputValidator and configures target element patterns.
     */
    public StvnRenameInputValidator() {
        this.pattern = PlatformPatterns.psiElement(PsiElement.class)
            .with(new PatternCondition<PsiElement>("stvnSymbolTarget") {
                @Override
                public boolean accepts(@NotNull PsiElement element, ProcessingContext context) {
                    var kind = classifyTarget(element);
                    if (kind != null) {
                        CURRENT_TARGET.set(kind);
                        return true;
                    }
                    return false;
                }
            });
    }

    @Override
    public @NotNull ElementPattern<? extends PsiElement> getPattern() {
        return pattern;
    }

    /**
     * Classifies a target PSI element into a type or constant symbol category.
     *
     * @param element the PSI element undergoing rename
     * @return the resolved {@link TargetKind}, or {@code null} if not an STVN symbol
     */
    public static @Nullable TargetKind classifyTarget(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        if (element instanceof TypeDefinition || element instanceof TypeKeyword || element instanceof IncludeMapAlias) {
            return TargetKind.TYPE;
        }
        if (element instanceof ConstantDefinition || element instanceof ValueKeyword) {
            return TargetKind.CONSTANT;
        }
        if (element instanceof UseMapAlias alias) {
            if (alias.getTypeKeywordList().size() >= 2) {
                return TargetKind.TYPE;
            }
            if (alias.getValueKeywordList().size() >= 2) {
                return TargetKind.CONSTANT;
            }
        }
        return null;
    }

    @Override
    public boolean isInputValid(@NotNull String newName, @NotNull PsiElement element, @NotNull ProcessingContext context) {
        var kind = classifyTarget(element);
        if (kind != null) {
            CURRENT_TARGET.set(kind);
        }
        if (kind == TargetKind.TYPE && newName.startsWith("#")) {
            return true;
        }
        if (kind == TargetKind.CONSTANT && newName.startsWith(":")) {
            return true;
        }
        if (!newName.startsWith(":") && !newName.startsWith("#")) {
            return isValidBareIdentifier(newName, element.getProject());
        }
        var namesValidator = new StvnNamesValidator();
        return namesValidator.isIdentifier(newName, element.getProject());
    }

    @Override
    public @Nullable @DialogMessage String getErrorMessage(@NotNull String newName, @NotNull Project project) {
        var kind = CURRENT_TARGET.get();
        return getErrorMessage(newName, kind, project);
    }

    /**
     * Evaluates error messages for a specified symbol kind and proposed name.
     *
     * @param newName the proposed new symbol name
     * @param kind the target symbol category
     * @param project the current project context
     * @return an error message string if invalid, or {@code null} if valid
     */
    public @Nullable @DialogMessage String getErrorMessage(
        @NotNull String newName,
        @Nullable TargetKind kind,
        @NotNull Project project
    ) {
        if (kind == TargetKind.TYPE) {
            if (newName.startsWith("#")) {
                return TYPE_ERROR_MESSAGE;
            }
            if (newName.startsWith(":")) {
                var namesValidator = new StvnNamesValidator();
                if (!namesValidator.isIdentifier(newName, project)) {
                    return "Identifier '" + newName + "' is not a valid STVN type name";
                }
                return null;
            }
            if (!isValidBareIdentifier(newName, project)) {
                return "Identifier '" + newName + "' is not a valid STVN identifier body";
            }
            return null;
        } else if (kind == TargetKind.CONSTANT) {
            if (newName.startsWith(":")) {
                return CONSTANT_ERROR_MESSAGE;
            }
            if (newName.startsWith("#")) {
                var namesValidator = new StvnNamesValidator();
                if (!namesValidator.isIdentifier(newName, project)) {
                    return "Identifier '" + newName + "' is not a valid STVN constant name";
                }
                return null;
            }
            if (!isValidBareIdentifier(newName, project)) {
                return "Identifier '" + newName + "' is not a valid STVN identifier body";
            }
            return null;
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
        var kind = classifyTarget(element);
        if (kind != null) {
            CURRENT_TARGET.set(kind);
        }
        return getErrorMessage(newName, kind, project);
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
