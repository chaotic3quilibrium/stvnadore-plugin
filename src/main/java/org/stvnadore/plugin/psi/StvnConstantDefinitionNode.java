package org.stvnadore.plugin.psi;

import com.intellij.psi.PsiNameIdentifierOwner;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetadataMap;

/**
 * Custom contract for ConstantDefinition PSI nodes, exposing metadata map accessor.
 */
@NullMarked
public interface StvnConstantDefinitionNode extends PsiNameIdentifierOwner {

    /**
     * Returns the metadata map attached to this constant definition, if present.
     *
     * @return the metadata map, or null if unadorned
     */
    @Nullable
    MetadataMap getMetadataMap();
}
