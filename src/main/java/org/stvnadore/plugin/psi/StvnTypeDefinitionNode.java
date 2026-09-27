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

    /**
     * Returns the metadata map attached to this type definition, if present.
     *
     * @return the metadata map, or null if unadorned
     */
    @Nullable
    org.stvnadore.psi.MetadataMap getMetadataMap();

    @Nullable
    TypeKeyword getTypeKeyword();
}
