package org.stvnadore.plugin.browser;

import com.intellij.psi.PsiElement;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Immutable row data transfer object for the interactive namespace dependency browser.
 *
 * @param name symbol identifier token (for example, {@code :AccountHolder})
 * @param source root namespaced origin source file path, module URI, or prelude namespace
 * @param typeStructure terminal schema structure, signature, or constructor layout
 * @param useDepth resolution depth metric (alias hop count, derivation depth, or nesting depth)
 * @param targetElement target declaration PSI element for navigation
 * @param isPrelude {@code true} if the symbol originates from the standard library prelude
 * @param navigationOffset target character offset within the source file
 * @param scope section scope under which the symbol was collected
 */
@NullMarked
public record StvnNamespaceSymbolEntry(
    String name,
    String source,
    String typeStructure,
    int useDepth,
    @Nullable PsiElement targetElement,
    boolean isPrelude,
    int navigationOffset,
    StvnNamespaceScope scope
) {
}
