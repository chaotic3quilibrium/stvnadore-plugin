package org.stvnadore.plugin.metadata;

import org.jspecify.annotations.NullMarked;
import java.util.Set;

/**
 * Metadata descriptor representing a canonical STVN metadata facet, its target base types,
 * canonical tier hierarchy, overridability, and authoring syntax.
 */
@NullMarked
public record StvnFacetMetadata(
    String facetTag,
    int tier,
    boolean overridable,
    String description,
    Set<String> targetDomains,
    String syntaxExample
) {
    /**
     * Constructs a validated StvnFacetMetadata descriptor.
     */
    public StvnFacetMetadata {
        java.util.Objects.requireNonNull(facetTag);
        java.util.Objects.requireNonNull(description);
        java.util.Objects.requireNonNull(targetDomains);
        java.util.Objects.requireNonNull(syntaxExample);
    }
}
