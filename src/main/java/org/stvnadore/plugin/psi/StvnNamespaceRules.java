package org.stvnadore.plugin.psi;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;

/**
 * Pure-vocabulary namespace and reserved-symbol predicates for STVN 2.0.0 PSI analysis.
 */
@NullMarked
public final class StvnNamespaceRules {

    /** Reserved STVN system root namespace. */
    public static final String RESERVED_SYSTEM_PACKAGE = ":org/stvnadore";

    private StvnNamespaceRules() {
    }

    /**
     * Checks whether {@code packagePath} targets the reserved {@code :org/stvnadore} enclave.
     *
     * @param packagePath package path string including leading {@code :}
     * @return {@code true} if reserved under {@code :org/stvnadore}
     */
    public static boolean isReservedPackage(@Nullable String packagePath) {
        if (packagePath == null) {
            return false;
        }
        String trimmed = packagePath.trim();
        return RESERVED_SYSTEM_PACKAGE.equals(trimmed)
            || trimmed.startsWith(RESERVED_SYSTEM_PACKAGE + "/");
    }

    /**
     * Checks whether {@code typeName} is a built-in scalar, composite constructor, or control keyword.
     *
     * @param typeName type keyword string including leading {@code :}
     * @return {@code true} if {@code typeName} is a reserved fundamental STVN keyword
     */
    public static boolean isReservedFundamentalType(@Nullable String typeName) {
        if (typeName == null) {
            return false;
        }
        String trimmed = typeName.trim();
        return StvnVocabulary.isBaseScalarType(trimmed)
            || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(trimmed)
            || StvnVocabulary.CONTROL_KEYWORDS.contains(trimmed);
    }
}
