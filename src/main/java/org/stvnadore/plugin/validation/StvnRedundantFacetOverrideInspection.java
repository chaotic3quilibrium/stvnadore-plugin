package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.BooleanValue;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Visitor;

/**
 * Validates nominal type definitions in {@code :defs} blocks to detect redundant metadata facet overrides.
 * Flags declared facets that merely restate the parent schema's already-resolved effective state.
 */
@NullMarked
public final class StvnRedundantFacetOverrideInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition typeDef) {
                var metaMap = typeDef.getMetadataMap();
                if (metaMap == null || metaMap.getMetadataEntryList().isEmpty()) {
                    return;
                }
                var schemaType = typeDef.getSchemaType();
                if (schemaType == null) {
                    return;
                }

                var rs = StvnTypeResolver.resolveNominalResolvedSchema(typeDef);
                if (rs == null || rs.isPoisonedSentinel()) {
                    return;
                }

                var targetKw = schemaType.getTypeKeyword();
                var parentRs = rs.underlyingSchema().orElse(null);
                if (targetKw != null && parentRs == null) {
                    return;
                }

                var parentConstraints = parentRs != null
                    ? parentRs.constraints()
                    : org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints.empty();

                for (var entry : metaMap.getMetadataEntryList()) {
                    checkRedundantFacet(entry, typeDef, metaMap, rs, parentRs, parentConstraints, holder);
                }
            }
        };
    }

    private static void checkRedundantFacet(
            MetadataEntry entry,
            TypeDefinition typeDef,
            MetadataMap metaMap,
            org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema rs,
            org.stvnadore.core.validation.StvnTypeResolver.@Nullable ResolvedSchema parentRs,
            org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints parentConstraints,
            ProblemsHolder holder
    ) {
        var trait = entry.getMetadataTrait();
        if (trait != null) {
            var bVal = trait.getBooleanValue();
            if (bVal != null) {
                boolean declared = isBooleanTrue(bVal);
                boolean parentEff = parentConstraints.preserveIndent();
                if (declared == parentEff) {
                    registerRedundantProblem(entry, declared ? "#TRUE" : "#FALSE", holder);
                }
            }
            return;
        }

        var bareFlag = entry.getMetadataBareFlag();
        if (bareFlag != null) {
            var text = bareFlag.getText().trim();
            if (text.startsWith(StvnVocabulary.FACET_KW_UNSIGNED) && parentConstraints.unsigned()) {
                registerRedundantProblem(entry, "#TRUE", holder);
            } else if (text.startsWith(StvnVocabulary.FACET_KW_EXACT) && parentConstraints.exact()) {
                registerRedundantProblem(entry, "#TRUE", holder);
            } else if (text.startsWith(StvnVocabulary.FACET_KW_INVERTIBLE) && parentConstraints.invertible()) {
                registerRedundantProblem(entry, "#TRUE", holder);
            }
            return;
        }

        var text = entry.getText().trim();
        if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
            extractNumericValue(text).ifPresent(val -> {
                int parentVal = parentConstraints.minSize().orElse(0);
                if (val == parentVal) {
                    registerRedundantProblem(entry, String.valueOf(val), holder);
                }
            });
        } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)) {
            extractNumericValue(text).ifPresent(val -> {
                int parentVal = parentConstraints.maxSize().orElse(StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY);
                if (val == parentVal) {
                    registerRedundantProblem(entry, String.valueOf(val), holder);
                }
            });
        } else if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE)) {
            extractBooleanLiteral(text).ifPresent(val -> {
                boolean parentVal = resolveParentDefaultEquatable(typeDef, metaMap, rs, parentRs, parentConstraints);
                if (val == parentVal) {
                    registerRedundantProblem(entry, val ? "#TRUE" : "#FALSE", holder);
                }
            });
        } else if (text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
            extractBooleanLiteral(text).ifPresent(val -> {
                boolean parentVal = resolveParentDefaultComparable(typeDef, metaMap, rs, parentRs, parentConstraints);
                if (val == parentVal) {
                    registerRedundantProblem(entry, val ? "#TRUE" : "#FALSE", holder);
                }
            });
        }
    }

    private static boolean resolveParentDefaultEquatable(
            TypeDefinition typeDef,
            MetadataMap metaMap,
            org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema rs,
            org.stvnadore.core.validation.StvnTypeResolver.@Nullable ResolvedSchema parentRs,
            org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints parentConstraints
    ) {
        var ultimateBase = getUltimateBaseType(rs);
        if (StvnVocabulary.TYPE_FLOAT.equals(ultimateBase)) {
            // Local co-declared #exact establishes natural equatable baseline as true (§ 6.2).
            if (hasLocalExactFacet(metaMap)) {
                return true;
            }
            if (parentConstraints.equatable().isPresent()) {
                return parentConstraints.equatable().get();
            }
            return parentRs != null && parentRs.constraints().exact();
        }
        if (StvnVocabulary.TYPE_TUPLE.equals(ultimateBase) || (ultimateBase != null && ultimateBase.startsWith(StvnVocabulary.TYPE_TUPLE))) {
            if (parentConstraints.equatable().isPresent()) {
                return parentConstraints.equatable().get();
            }
            return resolveInductiveTupleEquatable(typeDef, new java.util.HashSet<>());
        }
        if (parentConstraints.equatable().isPresent()) {
            return parentConstraints.equatable().get();
        }
        return true;
    }

    private static boolean resolveParentDefaultComparable(
            TypeDefinition typeDef,
            MetadataMap metaMap,
            org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema rs,
            org.stvnadore.core.validation.StvnTypeResolver.@Nullable ResolvedSchema parentRs,
            org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints parentConstraints
    ) {
        var ultimateBase = getUltimateBaseType(rs);
        if (StvnVocabulary.TYPE_TUPLE.equals(ultimateBase) || (ultimateBase != null && ultimateBase.startsWith(StvnVocabulary.TYPE_TUPLE))) {
            if (parentConstraints.comparable().isPresent()) {
                return parentConstraints.comparable().get();
            }
            return resolveInductiveTupleComparable(typeDef, new java.util.HashSet<>());
        }
        if (parentConstraints.comparable().isPresent()) {
            return parentConstraints.comparable().get();
        }
        if (StvnVocabulary.TYPE_SET.equals(ultimateBase) || StvnVocabulary.TYPE_MAP.equals(ultimateBase)) {
            return false;
        }
        return true;
    }

    private static boolean hasLocalExactFacet(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return false;
        }
        for (var entry : metaMap.getMetadataEntryList()) {
            var bareFlag = entry.getMetadataBareFlag();
            if (bareFlag != null) {
                var text = bareFlag.getText().trim();
                if (text.startsWith(StvnVocabulary.FACET_KW_EXACT)) {
                    var bVal = bareFlag.getBooleanValue();
                    return bVal == null || isBooleanTrue(bVal);
                }
            }
        }
        return false;
    }

    private static boolean resolveInductiveTupleEquatable(@NotNull TypeDefinition typeDef, java.util.Set<String> visited) {
        var product = findProductType(typeDef);
        if (product == null) {
            return false;
        }
        var fields = product.getSchemaTypeList();
        if (fields.isEmpty()) {
            return true;
        }
        for (var field : fields) {
            if (!isFieldEquatable(field, visited)) {
                return false;
            }
        }
        return true;
    }

    private static boolean resolveInductiveTupleComparable(@NotNull TypeDefinition typeDef, java.util.Set<String> visited) {
        var product = findProductType(typeDef);
        if (product == null) {
            return false;
        }
        var fields = product.getSchemaTypeList();
        if (fields.isEmpty()) {
            return true;
        }
        for (var field : fields) {
            if (!isFieldComparable(field, visited)) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable ProductType findProductType(@NotNull TypeDefinition typeDef) {
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) {
            return null;
        }
        var ctor = schemaType.getSchemaConstructor();
        if (ctor != null && ctor.getProductType() != null) {
            return ctor.getProductType();
        }
        var resolvedNominal = StvnTypeResolver.resolveNominalSchema(schemaType);
        if (resolvedNominal != null) {
            var nomCtor = resolvedNominal.getSchemaConstructor();
            if (nomCtor != null && nomCtor.getProductType() != null) {
                return nomCtor.getProductType();
            }
            var prod = PsiTreeUtil.findChildOfType(resolvedNominal, ProductType.class);
            if (prod != null) {
                return prod;
            }
        }
        return PsiTreeUtil.findChildOfType(schemaType, ProductType.class);
    }

    private static boolean isFieldEquatable(@NotNull SchemaType field, java.util.Set<String> visited) {
        var metaMap = field.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE)) {
                    var boolOpt = extractBooleanLiteral(text);
                    if (boolOpt.isPresent()) {
                        return boolOpt.get();
                    }
                }
            }
        }

        var kw = field.getTypeKeyword();
        if (kw != null) {
            var typeName = kw.getText().trim();
            if (!visited.add(typeName)) {
                return false;
            }
            try {
                var targetDef = StvnTypeResolver.findTypeDefinition(field.getContainingFile(), typeName, kw);
                if (targetDef == null) {
                    var resolved = StvnTypeReference.resolveTypeInFile(field.getContainingFile(), typeName, new java.util.HashSet<>());
                    targetDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                }
                if (targetDef != null) {
                    var targetRs = StvnTypeResolver.resolveNominalResolvedSchema(targetDef);
                    if (targetRs != null && !targetRs.isPoisonedSentinel() && targetRs.constraints().equatable().isPresent()) {
                        return targetRs.constraints().equatable().get();
                    }
                    return resolveFallbackTypeDefEquatable(targetDef, visited);
                }
                return resolvePrimitiveKeywordEquatable(typeName, metaMap);
            } finally {
                visited.remove(typeName);
            }
        }

        var ctor = field.getSchemaConstructor();
        if (ctor != null) {
            var atomic = ctor.getAtomicType();
            if (atomic != null) {
                var text = atomic.getText().trim();
                if (StvnVocabulary.TYPE_FLOAT.equals(text)) {
                    return hasLocalExactFacet(metaMap);
                }
                return true;
            }
            var prod = ctor.getProductType();
            if (prod != null) {
                var innerFields = prod.getSchemaTypeList();
                return innerFields.stream().allMatch(f -> isFieldEquatable(f, visited));
            }
            var coll = ctor.getCollectionType();
            if (coll != null) {
                var inners = PsiTreeUtil.getChildrenOfTypeAsList(coll, SchemaType.class);
                if (!inners.isEmpty()) {
                    return isFieldEquatable(inners.get(0), visited);
                }
                return true;
            }
            var sum = ctor.getSumType();
            if (sum != null) {
                if (sum.getEnumDef() != null) {
                    return true;
                }
                var inners = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return inners.stream().allMatch(f -> isFieldEquatable(f, visited));
            }
        }
        return true;
    }

    private static boolean isFieldComparable(@NotNull SchemaType field, java.util.Set<String> visited) {
        var metaMap = field.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
                    var boolOpt = extractBooleanLiteral(text);
                    if (boolOpt.isPresent()) {
                        return boolOpt.get();
                    }
                }
            }
        }

        var kw = field.getTypeKeyword();
        if (kw != null) {
            var typeName = kw.getText().trim();
            if (!visited.add(typeName)) {
                return false;
            }
            try {
                var targetDef = StvnTypeResolver.findTypeDefinition(field.getContainingFile(), typeName, kw);
                if (targetDef == null) {
                    var resolved = StvnTypeReference.resolveTypeInFile(field.getContainingFile(), typeName, new java.util.HashSet<>());
                    targetDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                }
                if (targetDef != null) {
                    var targetRs = StvnTypeResolver.resolveNominalResolvedSchema(targetDef);
                    if (targetRs != null && !targetRs.isPoisonedSentinel() && targetRs.constraints().comparable().isPresent()) {
                        return targetRs.constraints().comparable().get();
                    }
                    return resolveFallbackTypeDefComparable(targetDef, visited);
                }
                return resolvePrimitiveKeywordComparable(typeName);
            } finally {
                visited.remove(typeName);
            }
        }

        var ctor = field.getSchemaConstructor();
        if (ctor != null) {
            var atomic = ctor.getAtomicType();
            if (atomic != null) {
                return true;
            }
            var prod = ctor.getProductType();
            if (prod != null) {
                var innerFields = prod.getSchemaTypeList();
                return innerFields.stream().allMatch(f -> isFieldComparable(f, visited));
            }
            var coll = ctor.getCollectionType();
            if (coll != null) {
                var text = coll.getText().trim();
                if (text.startsWith(StvnVocabulary.TYPE_SET) || text.startsWith(StvnVocabulary.TYPE_MAP)) {
                    return false;
                }
                var inners = PsiTreeUtil.getChildrenOfTypeAsList(coll, SchemaType.class);
                if (!inners.isEmpty()) {
                    return isFieldComparable(inners.get(0), visited);
                }
                return true;
            }
            var sum = ctor.getSumType();
            if (sum != null) {
                if (sum.getEnumDef() != null) {
                    return true;
                }
                var inners = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                return inners.stream().allMatch(f -> isFieldComparable(f, visited));
            }
        }
        return true;
    }

    private static boolean resolveFallbackTypeDefEquatable(@NotNull TypeDefinition targetDef, java.util.Set<String> visited) {
        var metaMap = targetDef.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE)) {
                    var boolOpt = extractBooleanLiteral(text);
                    if (boolOpt.isPresent()) {
                        return boolOpt.get();
                    }
                }
            }
        }
        var targetSchema = targetDef.getSchemaType();
        return targetSchema == null || isFieldEquatable(targetSchema, visited);
    }

    private static boolean resolveFallbackTypeDefComparable(@NotNull TypeDefinition targetDef, java.util.Set<String> visited) {
        var metaMap = targetDef.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
                    var boolOpt = extractBooleanLiteral(text);
                    if (boolOpt.isPresent()) {
                        return boolOpt.get();
                    }
                }
            }
        }
        var targetSchema = targetDef.getSchemaType();
        return targetSchema == null || isFieldComparable(targetSchema, visited);
    }

    private static boolean resolvePrimitiveKeywordEquatable(String typeName, @Nullable MetadataMap metaMap) {
        if (StvnVocabulary.TYPE_FLOAT.equals(typeName)) {
            return hasLocalExactFacet(metaMap);
        }
        return true;
    }

    private static boolean resolvePrimitiveKeywordComparable(String typeName) {
        if (StvnVocabulary.TYPE_SET.equals(typeName) || StvnVocabulary.TYPE_MAP.equals(typeName)) {
            return false;
        }
        return true;
    }

    private static @Nullable String getUltimateBaseType(org.stvnadore.core.validation.StvnTypeResolver.ResolvedSchema rs) {
        var curr = rs;
        while (curr.underlyingSchema().isPresent()) {
            curr = curr.underlyingSchema().get();
        }
        return org.stvnadore.core.validation.StvnTypeResolver.getPrimitiveBaseType(curr.node());
    }

    private static void registerRedundantProblem(MetadataEntry entry, String effectiveValue, ProblemsHolder holder) {
        var entryText = entry.getText().trim();
        holder.registerProblem(
            entry,
            "Redundant facet override: parent type already specifies effective value '" + effectiveValue + "'",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new RemoveRedundantFacetQuickFix(entryText)
        );
    }

    private static boolean isBooleanTrue(BooleanValue bVal) {
        var t = bVal.getText().trim();
        return "#TRUE".equals(t) || "#T".equals(t);
    }

    private static java.util.OptionalInt extractNumericValue(String text) {
        var parts = text.split("\\s+");
        if (parts.length >= 2) {
            try {
                return java.util.OptionalInt.of(Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {}
        }
        return java.util.OptionalInt.empty();
    }

    private static java.util.Optional<Boolean> extractBooleanLiteral(String text) {
        var parts = text.split("\\s+");
        if (parts.length >= 2) {
            var val = parts[1].trim();
            if ("#TRUE".equals(val) || "#T".equals(val)) return java.util.Optional.of(true);
            if ("#FALSE".equals(val) || "#F".equals(val)) return java.util.Optional.of(false);
        }
        return java.util.Optional.empty();
    }

    private static final class RemoveRedundantFacetQuickFix implements LocalQuickFix {
        private final String facetSnippet;

        RemoveRedundantFacetQuickFix(String facetSnippet) {
            this.facetSnippet = facetSnippet;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Remove redundant facet";
        }

        @Override
        public @Nls @NotNull String getName() {
            return "Remove redundant facet '" + facetSnippet + "'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element instanceof MetadataEntry entry && entry.isValid()) {
                var map = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
                if (map != null && map.getMetadataEntryList().size() == 1) {
                    var prev = map.getPrevSibling();
                    if (prev instanceof PsiWhiteSpace) {
                        prev.delete();
                    }
                    map.delete();
                } else {
                    var prev = entry.getPrevSibling();
                    if (prev instanceof PsiWhiteSpace) {
                        prev.delete();
                    }
                    entry.delete();
                }
            }
        }
    }
}
