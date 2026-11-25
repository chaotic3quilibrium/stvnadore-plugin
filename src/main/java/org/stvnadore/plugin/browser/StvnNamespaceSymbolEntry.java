package org.stvnadore.plugin.browser;

import com.intellij.psi.PsiElement;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Immutable 5-column row data transfer object for the interactive {@code STVN Namespace Dependencies} dialog.
 *
 * @param name             active symbol identifier in scope (for example, {@code :ValveMode} or {@code #INIT_VALVE_MODE})
 * @param source           originating root namespace or document filename (for example, {@code :cooling/manifold})
 * @param upstreamTarget   upstream symbol bound by {@code :use}, or {@link #LOCAL_UPSTREAM_DASH} for local root declarations
 * @param resolvedType     canonical zero-bracket-padded STVN 2.0.0 schema type or constant nominal trajectory
 * @param useDepth         deterministic non-negative integer hop depth ({@code 0, 1, 2, ...})
 * @param targetElement    target declaration PSI element for navigation
 * @param isPrelude        {@code true} if the symbol originates from the standard library prelude catalog
 * @param navigationOffset target character offset within the declaration file
 * @param scope            section scope under which the symbol was collected
 */
@NullMarked
public record StvnNamespaceSymbolEntry(
    String name,
    String source,
    String upstreamTarget,
    String resolvedType,
    int useDepth,
    @Nullable PsiElement targetElement,
    boolean isPrelude,
    int navigationOffset,
    StvnNamespaceScope scope
) {

    /** Em-dash sentinel displayed in {@code Upstream Target} for direct root declarations. */
    public static final String LOCAL_UPSTREAM_DASH = "\u2014";

    /**
     * Convenience constructor defaulting {@code upstreamTarget} to {@link #LOCAL_UPSTREAM_DASH}.
     *
     * @param name             active symbol identifier
     * @param source           originating namespace or filename
     * @param resolvedType     canonical resolved schema type
     * @param useDepth         non-negative hop depth
     * @param targetElement    navigation PSI element
     * @param isPrelude        {@code true} for prelude catalog entries
     * @param navigationOffset target character offset
     * @param scope            enclosing collection scope
     */
    public StvnNamespaceSymbolEntry(
        String name,
        String source,
        String resolvedType,
        int useDepth,
        @Nullable PsiElement targetElement,
        boolean isPrelude,
        int navigationOffset,
        StvnNamespaceScope scope
    ) {
        this(
            name,
            source,
            LOCAL_UPSTREAM_DASH,
            resolvedType,
            useDepth,
            targetElement,
            isPrelude,
            navigationOffset,
            scope
        );
    }

    /**
     * Alias accessor returning {@link #resolvedType()} for callers inspecting the resolved schema column.
     *
     * @return canonical zero-bracket-padded STVN 2.0.0 schema type
     */
    public String typeStructure() {
        return resolvedType;
    }
}
