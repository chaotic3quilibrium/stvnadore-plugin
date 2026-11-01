package org.stvnadore.plugin.validation;

import com.intellij.psi.util.PsiTreeUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.*;

import java.util.HashSet;
import java.util.Set;

/**
 * Authoritative enforcement of the STVN 2.0.0 Facet Target Governance Matrix (SPEC &sect; 6.1).
 */
@NullMarked
public final class StvnMetadataFacetAllowlist {

    private StvnMetadataFacetAllowlist() {}

    private static final Set<String> COMMON_TRAIT_FACETS = Set.of(
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE
    );

    private static final Set<String> INT_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_UNSIGNED,
        StvnVocabulary.FACET_KW_SIZE,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    private static final Set<String> CONTINUOUS_FLOAT_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_SIZE,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MIN_EXCL,
        StvnVocabulary.FACET_KW_MAX_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    private static final Set<String> EXACT_FLOAT_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_EXACT,
        StvnVocabulary.FACET_KW_MAX_SIZE,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    private static final Set<String> TIME_EPOCH_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_SCALE_S,
        StvnVocabulary.FACET_KW_SCALE_MS,
        StvnVocabulary.FACET_KW_SCALE_US,
        StvnVocabulary.FACET_KW_SCALE_NS,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    private static final Set<String> DATE_TIME_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_OFFSET,
        StvnVocabulary.FACET_KW_ZONED,
        StvnVocabulary.FACET_KW_AUDITED,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    private static final Set<String> STRING_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_MIN_SIZE,
        StvnVocabulary.FACET_KW_MAX_SIZE,
        StvnVocabulary.FACET_KW_PRESERVE_INDENT,
        StvnVocabulary.FACET_KW_REGEX,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE,
        StvnVocabulary.FACET_KW_MIN_INCL,
        StvnVocabulary.FACET_KW_MIN_EXCL,
        StvnVocabulary.FACET_KW_MAX_INCL,
        StvnVocabulary.FACET_KW_MAX_EXCL
    );

    private static final Set<String> BOOLEAN_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE
    );

    private static final Set<String> MAP_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_INVERTIBLE,
        StvnVocabulary.FACET_KW_MIN_SIZE,
        StvnVocabulary.FACET_KW_MAX_SIZE,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE
    );

    private static final Set<String> COLLECTION_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_MIN_SIZE,
        StvnVocabulary.FACET_KW_MAX_SIZE,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE
    );

    private static final Set<String> ENUM_PERMITTED_FACETS = Set.of(
        StvnVocabulary.FACET_KW_FILTER_INCL,
        StvnVocabulary.FACET_KW_FILTER_EXCL,
        StvnVocabulary.FACET_KW_EQUATABLE,
        StvnVocabulary.FACET_KW_COMPARABLE
    );

    /**
     * Determines whether the facet is permitted on the specified base type.
     *
     * @param facetKeyword the facet keyword (e.g. "#us", "#audited")
     * @param baseType the underlying type string (e.g. ":DateTime", ":TimeEpoch")
     * @param isExactActive whether #exact is active on float types
     * @return true if permitted; false otherwise
     */
    public static boolean isFacetPermittedOnBaseType(String facetKeyword, String baseType, boolean isExactActive) {
        if (StvnVocabulary.TYPE_INT.equals(baseType)) {
            return INT_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
            if (isExactActive) {
                return EXACT_FLOAT_PERMITTED_FACETS.contains(facetKeyword);
            }
            if (StvnVocabulary.FACET_KW_EXACT.equals(facetKeyword)) {
                return true;
            }
            return CONTINUOUS_FLOAT_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)) {
            return TIME_EPOCH_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (StvnVocabulary.TYPE_DATE_TIME.equals(baseType)) {
            return DATE_TIME_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (StvnVocabulary.TYPE_STRING.equals(baseType) || isCanonicalPreludeStringType(baseType)) {
            return STRING_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (StvnVocabulary.TYPE_BOOLEAN.equals(baseType)) {
            return BOOLEAN_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (isMapType(baseType)) {
            return MAP_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (isCollectionType(baseType)) {
            return COLLECTION_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (isEnumConstructor(baseType)) {
            return ENUM_PERMITTED_FACETS.contains(facetKeyword);
        }
        if (StvnVocabulary.TYPE_TUPLE.equals(baseType) || baseType.startsWith(StvnVocabulary.TYPE_TUPLE)
                || StvnVocabulary.TYPE_UNION.equals(baseType) || baseType.startsWith(StvnVocabulary.TYPE_UNION)
                || StvnVocabulary.TYPE_EITHER.equals(baseType) || baseType.startsWith(StvnVocabulary.TYPE_EITHER)
                || StvnVocabulary.TYPE_OPTION.equals(baseType) || baseType.startsWith(StvnVocabulary.TYPE_OPTION)) {
            return COMMON_TRAIT_FACETS.contains(facetKeyword);
        }
        return false;
    }

    /**
     * Evaluates whether a metadata entry is valid for 5-tier ordering purposes.
     *
     * @param entry the metadata entry PSI node
     * @param metadataMap the enclosing metadata map
     * @return true if valid for ordering; false if illegal or unknown
     */
    public static boolean isFacetPermittedForOrdering(MetadataEntry entry, MetadataMap metadataMap) {
        if (entry.getMetadataUnknownEntry() != null) {
            return false;
        }
        String facetKeyword = StvnMetadataOrderInspection.extractFacetKeyword(entry);
        if (facetKeyword.isEmpty()) {
            return false;
        }
        if (StvnVocabulary.FACET_KW_STRIP.equals(facetKeyword)) {
            var parent = metadataMap.getParent();
            return parent instanceof UseOptionsBlock || parent instanceof IncludeOptionsBlock;
        }

        var schemaType = PsiTreeUtil.getParentOfType(metadataMap, SchemaType.class, false);
        if (schemaType == null) {
            var typeDef = PsiTreeUtil.getParentOfType(metadataMap, TypeDefinition.class, false);
            if (typeDef != null) {
                schemaType = typeDef.getSchemaType();
            }
        }
        if (schemaType == null) {
            var constDef = PsiTreeUtil.getParentOfType(metadataMap, ConstantDefinition.class, false);
            if (constDef != null) {
                schemaType = constDef.getSchemaType();
            }
        }
        if (schemaType == null) {
            return true;
        }

        var baseType = resolveUnderlyingBaseType(schemaType, new HashSet<>());
        if (baseType == null) {
            return true; // Bail out on undeclared base types
        }
        boolean exactActive = isExactActive(metadataMap, schemaType);
        return isFacetPermittedOnBaseType(facetKeyword, baseType, exactActive);
    }

    /**
     * Recursively resolves the underlying base type string for a schema type.
     *
     * @param schemaType the schema type to inspect
     * @param visitedTypeNames visited set preventing cycle loops
     * @return canonical base type string, or null if unresolved
     */
    public static @Nullable String resolveUnderlyingBaseType(SchemaType schemaType, Set<String> visitedTypeNames) {
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            var atomic = constructor.getAtomicType();
            if (atomic != null) return atomic.getText().trim();
            var collection = constructor.getCollectionType();
            if (collection != null) return collection.getText().trim();
            var product = constructor.getProductType();
            if (product != null) return product.getText().trim();
            var sum = constructor.getSumType();
            if (sum != null) return sum.getText().trim();
            return constructor.getText().trim();
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (StvnVocabulary.TYPE_ENUM.equals(text)) return StvnVocabulary.TYPE_ENUM;
            if (StvnVocabulary.isBaseScalarType(text) || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(text)) {
                return text;
            }
            if (!visitedTypeNames.add(text)) return null;
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveUnderlyingBaseType(parentDef.getSchemaType(), visitedTypeNames);
                    }
                }
            }
            return null;
        }
        var rawText = schemaType.getText().trim();
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            var metaText = metaMap.getText().trim();
            if (rawText.startsWith(metaText)) {
                rawText = rawText.substring(metaText.length()).trim();
            }
        }
        if (rawText.startsWith(":")) {
            if (StvnVocabulary.isBaseScalarType(rawText) || StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(rawText)) {
                return rawText;
            }
            if (!visitedTypeNames.add(rawText)) return null;
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, rawText, new HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveUnderlyingBaseType(parentDef.getSchemaType(), visitedTypeNames);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Determines whether the exact float precision facet (#exact) is active in the hierarchy.
     *
     * @param metaMap the active metadata map
     * @param schemaType the schema type node
     * @return true if exact float is active; false otherwise
     */
    public static boolean isExactActive(@Nullable MetadataMap metaMap, SchemaType schemaType) {
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var bare = entry.getMetadataBareFlag();
                if (bare != null && bare.getNode().findChildByType(StvnTypes.KW_EXACT) != null) {
                    return true;
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (!StvnVocabulary.isBaseScalarType(text)) {
                var file = schemaType.getContainingFile();
                if (file != null) {
                    var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                    if (resolved != null) {
                        var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                        if (parentDef != null && parentDef.getSchemaType() != null) {
                            return isExactActive(parentDef.getSchemaType().getMetadataMap(), parentDef.getSchemaType());
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if baseType matches canonical prelude string aliases.
     *
     * @param baseType the type name string
     * @return true if prelude string type
     */
    public static boolean isCanonicalPreludeStringType(String baseType) {
        return baseType.equals(":org/stvnadore/prelude/UuidLowerCase")
            || baseType.equals(":org/stvnadore/prelude/UuidMixedCase")
            || baseType.equals(":org/stvnadore/prelude/Uuid")
            || baseType.equals(":org/stvnadore/prelude/Ulid")
            || baseType.equals(":org/stvnadore/prelude/Sha256LowerCase")
            || baseType.equals(":org/stvnadore/prelude/Sha256MixedCase")
            || baseType.equals(":org/stvnadore/prelude/Sha256")
            || baseType.equals(":org/stvnadore/prelude/SemVer")
            || baseType.equals(":org/stvnadore/prelude/EmailLowerCase")
            || baseType.equals(":org/stvnadore/prelude/EmailMixedCase")
            || baseType.equals(":org/stvnadore/prelude/Email")
            || baseType.equals(":org/stvnadore/prelude/IPv4")
            || baseType.equals(":UuidLowerCase")
            || baseType.equals(":UuidMixedCase")
            || baseType.equals(":Uuid")
            || baseType.equals(":Ulid")
            || baseType.equals(":Sha256LowerCase")
            || baseType.equals(":Sha256MixedCase")
            || baseType.equals(":Sha256")
            || baseType.equals(":SemVer")
            || baseType.equals(":EmailLowerCase")
            || baseType.equals(":EmailMixedCase")
            || baseType.equals(":Email")
            || baseType.equals(":IPv4");
    }

    /**
     * Checks if baseType is a sequence or set collection type.
     *
     * @param baseType the type name string
     * @return true if collection
     */
    public static boolean isCollectionType(String baseType) {
        return baseType.equals(StvnVocabulary.TYPE_SEQ) || baseType.startsWith(StvnVocabulary.TYPE_SEQ)
            || baseType.equals(StvnVocabulary.TYPE_SET) || baseType.startsWith(StvnVocabulary.TYPE_SET)
            || isMapType(baseType);
    }

    /**
     * Checks if baseType is a map type.
     *
     * @param baseType the type name string
     * @return true if map
     */
    public static boolean isMapType(String baseType) {
        return baseType.equals(StvnVocabulary.TYPE_MAP) || baseType.startsWith(StvnVocabulary.TYPE_MAP);
    }

    /**
     * Checks if baseType is an enum constructor.
     *
     * @param baseType the type name string
     * @return true if enum constructor
     */
    public static boolean isEnumConstructor(String baseType) {
        if (baseType.equals(StvnVocabulary.TYPE_ENUM)) return true;
        if (baseType.startsWith(StvnVocabulary.TYPE_ENUM)) {
            char next = baseType.charAt(StvnVocabulary.TYPE_ENUM.length());
            return next == '[' || Character.isWhitespace(next);
        }
        return false;
    }
}
