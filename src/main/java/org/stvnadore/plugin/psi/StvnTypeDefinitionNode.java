package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiNameIdentifierOwner;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.TypeKeyword;

/**
 * Custom contract for TypeDefinition PSI nodes, exposing type keyword accessor.
 */
@NullMarked
public interface StvnTypeDefinitionNode extends PsiNameIdentifierOwner {

    @Nullable
    TypeKeyword getTypeKeyword();
}
