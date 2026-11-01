package org.stvnadore.plugin.ir;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;

import java.util.List;
import java.util.Optional;

/**
 * Pure-IR enum subset descriptor capturing derived variant filtering lineage, active variant
 * projections, and root enumeration membership.
 *
 * @param aliasName          nominal identifier of the derived enum subset
 * @param parentAliasName    direct parent nominal alias identifier, or {@code null} for root enums
 * @param rootEnumName       terminal root enum nominal alias identifier
 * @param allowedVariants    effective ordered variant tags permitted by this subset
 * @param rootVariants       ordered variant tags declared on the terminal root {@code :Enum}
 * @param inclusiveFilter    {@code true} for {@code #filterIncl}; {@code false} for {@code #filterExcl}
 * @param declaredFilterList ordered variant tags written directly inside the active filter facet
 */
@NullMarked
public record StvnEnumSubsetInfo(
    String aliasName,
    @Nullable String parentAliasName,
    String rootEnumName,
    List<String> allowedVariants,
    List<String> rootVariants,
    boolean inclusiveFilter,
    List<String> declaredFilterList
) {

    /**
     * Convenience 6-argument constructor initializing {@code declaredFilterList} to an empty list.
     *
     * @param aliasName       nominal identifier of the derived enum subset
     * @param parentAliasName direct parent nominal alias identifier, or {@code null}
     * @param rootEnumName    terminal root enum nominal alias identifier
     * @param allowedVariants effective ordered variant tags permitted by this subset
     * @param rootVariants    ordered variant tags declared on the terminal root {@code :Enum}
     * @param inclusiveFilter {@code true} for {@code #filterIncl}; {@code false} for {@code #filterExcl}
     */
    public StvnEnumSubsetInfo(
        String aliasName,
        @Nullable String parentAliasName,
        String rootEnumName,
        List<String> allowedVariants,
        List<String> rootVariants,
        boolean inclusiveFilter
    ) {
        this(
            aliasName,
            parentAliasName,
            rootEnumName,
            List.copyOf(allowedVariants),
            List.copyOf(rootVariants),
            inclusiveFilter,
            List.of()
        );
    }

    /**
     * Extracts subset metadata from a pure-IR {@link StvnSchema} when it is an {@link StvnSchema.EnumSubsetSchema}.
     *
     * @param schema pure-IR schema candidate
     * @return optional subset descriptor when {@code schema} is an {@link StvnSchema.EnumSubsetSchema}
     */
    public static Optional<StvnEnumSubsetInfo> fromIr(@Nullable StvnSchema schema) {
        if (!(schema instanceof StvnSchema.EnumSubsetSchema subset)) {
            return Optional.empty();
        }
        String alias = subset.aliasName().orElse(StvnVocabulary.TYPE_ENUM);
        String parent = subset.parentEnum().aliasName().orElse(null);
        String root = subset.rootEnum().aliasName().orElse(parent != null ? parent : alias);
        return Optional.of(new StvnEnumSubsetInfo(
            alias,
            parent,
            root,
            List.copyOf(subset.activeVariants()),
            List.copyOf(subset.rootEnum().rootVariants()),
            subset.inclusiveFilter(),
            List.copyOf(subset.declaredFilterList())
        ));
    }

    /**
     * Returns the nominal alias identifier of this enum subset.
     *
     * @return nominal alias identifier
     */
    public String name() {
        return aliasName;
    }

    /**
     * Returns the direct parent nominal alias identifier, or {@code null} if none.
     *
     * @return parent nominal alias identifier or {@code null}
     */
    public @Nullable String parentType() {
        return parentAliasName;
    }

    /**
     * Returns the terminal root enum nominal alias identifier.
     *
     * @return root enum nominal alias identifier
     */
    public String rootEnum() {
        return rootEnumName;
    }

    /**
     * Returns {@code true} when derived via {@code #filterIncl}, or {@code false} for {@code #filterExcl}.
     *
     * @return {@code true} if inclusive filtering applies
     */
    public boolean isInclusive() {
        return inclusiveFilter;
    }

    /**
     * Tests whether the specified variant tag is permitted by this enum subset.
     *
     * @param variantTag candidate variant tag (e.g., {@code #ACTIVE})
     * @return {@code true} if {@code variantTag} is in {@link #allowedVariants()}
     */
    public boolean containsVariant(String variantTag) {
        return allowedVariants.contains(variantTag);
    }
}
