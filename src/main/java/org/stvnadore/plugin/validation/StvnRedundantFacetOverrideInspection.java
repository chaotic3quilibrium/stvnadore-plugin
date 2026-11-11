package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BooleanValue;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Visitor;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

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
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeDef)) {
                    return;
                }
                var metaMap = typeDef.getMetadataMap();
                if (metaMap == null || metaMap.getMetadataEntryList().isEmpty()) {
                    return;
                }
                var schemaType = typeDef.getSchemaType();
                if (schemaType == null) {
                    return;
                }

                var rawIrSchema = StvnTypeResolver.resolveNominalIrSchema(typeDef);
                StvnSchema irSchema = rawIrSchema instanceof StvnSchema.PoisonedSchema poisoned
                    ? poisoned.bestEffortFallback().orElse(null)
                    : rawIrSchema;

                StvnSchema parentIr = null;
                var targetKw = schemaType.getTypeKeyword();
                if (targetKw != null) {
                    String parentAlias = targetKw.getText().trim();
                    if (!StvnVocabulary.isBaseScalarType(parentAlias)
                        && !StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(parentAlias)) {
                        var resolvedParentElem = StvnTypeReference.resolveTypeInFile(
                            typeDef.getContainingFile(),
                            parentAlias,
                            new HashSet<>()
                        );
                        var parentTypeDef = StvnPsiUtils.getParentTypeDefinition(resolvedParentElem);
                        if (parentTypeDef != null && parentTypeDef != typeDef) {
                            parentIr = StvnTypeResolver.resolveNominalIrSchema(parentTypeDef);
                        }
                        if (parentIr == null) {
                            parentIr = StvnTypeResolver.resolveIrSchemaByAlias(typeDef.getContainingFile(), parentAlias);
                        }
                        if (parentIr instanceof StvnSchema.PoisonedSchema poisonedParent) {
                            parentIr = poisonedParent.bestEffortFallback().orElse(null);
                        }
                        if (parentIr == null) {
                            return;
                        }
                    }
                }

                String ultimateBase;
                if (irSchema != null) {
                    ultimateBase = StvnSchemaFormatter.getPrimitiveBaseType(irSchema);
                } else if (parentIr != null) {
                    ultimateBase = StvnSchemaFormatter.getPrimitiveBaseType(parentIr);
                } else {
                    var resolvedPsi = StvnTypeResolver.resolveNominalSchema(schemaType);
                    ultimateBase = StvnSchemaFormatter.formatCleanSchema(resolvedPsi != null ? resolvedPsi : schemaType).trim();
                }

                for (var entry : metaMap.getMetadataEntryList()) {
                    checkRedundantFacet(entry, typeDef, metaMap, ultimateBase, irSchema, parentIr, holder);
                }
            }
        };
    }

    private static void checkRedundantFacet(
        MetadataEntry entry,
        TypeDefinition typeDef,
        MetadataMap metaMap,
        String ultimateBase,
        @Nullable StvnSchema irSchema,
        @Nullable StvnSchema parentIr,
        ProblemsHolder holder
    ) {
        // Suppress inspection warnings on floor tautologies, primitive trait re-assertions,
        // and natural bounds already enforced as W003 warnings by StvnExternalAnnotator.
        if (isGovernedByCoreRuleW003(entry, typeDef, metaMap, ultimateBase, irSchema, parentIr)) {
            return;
        }

        var trait = entry.getMetadataTrait();
        if (trait != null) {
            var bVal = trait.getBooleanValue();
            if (bVal != null) {
                boolean declared = isBooleanTrue(bVal);
                boolean parentEff = parentIr instanceof StvnSchema.StringSchema s && s.preserveIndent();
                if (declared == parentEff) {
                    registerRedundantProblem(entry, declared ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE, holder);
                }
            }
            return;
        }

        var bareFlag = entry.getMetadataBareFlag();
        if (bareFlag != null) {
            // Tier-1 bare definition flags (#unsigned, #exact, #invertible, scale/mode flags)
            // are non-overridable on derived nominal types and are governed by Core E040.
            return;
        }

        var text = entry.getText().trim();
        if (text.startsWith(StvnVocabulary.FACET_KW_SIZE)
            || text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)
            || text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)) {
            // Tier-1 #size, #minSize, and #maxSize are non-overridable facets governed by Core W003 (on root types)
            // and Core E040 (on derived nominal types). Never emit a local W003 override warning.
            return;
        }
        if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE)) {
            extractBooleanLiteral(text).ifPresent(val -> {
                boolean parentVal = resolveParentDefaultEquatable(typeDef, metaMap, ultimateBase, parentIr);
                if (val == parentVal) {
                    registerRedundantProblem(entry, val ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE, holder);
                }
            });
        } else if (text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
            extractBooleanLiteral(text).ifPresent(val -> {
                boolean parentVal = resolveParentDefaultComparable(typeDef, ultimateBase, parentIr);
                if (val == parentVal) {
                    registerRedundantProblem(entry, val ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE, holder);
                }
            });
        }
    }

    private static boolean resolveParentDefaultEquatable(
        TypeDefinition typeDef,
        MetadataMap metaMap,
        String ultimateBase,
        @Nullable StvnSchema parentIr
    ) {
        if (StvnVocabulary.TYPE_FLOAT.equals(ultimateBase)) {
            if (hasLocalExactFacet(metaMap)) {
                return true;
            }
            if (parentIr != null) {
                return parentIr.traits().equatable();
            }
            return false;
        }
        if (StvnVocabulary.TYPE_TUPLE.equals(ultimateBase)) {
            if (parentIr != null) {
                return parentIr.traits().equatable();
            }
            return resolveInductiveTupleEquatable(typeDef, new HashSet<>());
        }
        if (parentIr != null) {
            return parentIr.traits().equatable();
        }
        return true;
    }

    private static boolean resolveParentDefaultComparable(
        TypeDefinition typeDef,
        String ultimateBase,
        @Nullable StvnSchema parentIr
    ) {
        if (StvnVocabulary.TYPE_TUPLE.equals(ultimateBase)) {
            if (parentIr != null) {
                return parentIr.traits().comparable();
            }
            return resolveInductiveTupleComparable(typeDef, new HashSet<>());
        }
        if (parentIr != null) {
            return parentIr.traits().comparable();
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
            if (bareFlag != null && bareFlag.getText().trim().startsWith(StvnVocabulary.FACET_KW_EXACT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean resolveInductiveTupleEquatable(@NotNull TypeDefinition typeDef, Set<String> visited) {
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

    private static boolean resolveInductiveTupleComparable(@NotNull TypeDefinition typeDef, Set<String> visited) {
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

    private static boolean isFieldEquatable(@NotNull SchemaType field, Set<String> visited) {
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
                    var resolved = StvnTypeReference.resolveTypeInFile(field.getContainingFile(), typeName, new HashSet<>());
                    targetDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                }
                if (targetDef != null) {
                    var targetIr = StvnTypeResolver.resolveNominalIrSchema(targetDef);
                    if (targetIr != null && !(targetIr instanceof StvnSchema.PoisonedSchema)) {
                        return targetIr.traits().equatable();
                    }
                    return resolveFallbackTypeDefEquatable(targetDef, visited);
                }
                var aliasIr = StvnTypeResolver.resolveIrSchemaByAlias(field.getContainingFile(), typeName);
                if (aliasIr != null && !(aliasIr instanceof StvnSchema.PoisonedSchema)) {
                    return aliasIr.traits().equatable();
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
                return prod.getSchemaTypeList().stream().allMatch(f -> isFieldEquatable(f, visited));
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

    private static boolean isFieldComparable(@NotNull SchemaType field, Set<String> visited) {
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
                    var resolved = StvnTypeReference.resolveTypeInFile(field.getContainingFile(), typeName, new HashSet<>());
                    targetDef = StvnPsiUtils.getParentTypeDefinition(resolved);
                }
                if (targetDef != null) {
                    var targetIr = StvnTypeResolver.resolveNominalIrSchema(targetDef);
                    if (targetIr != null && !(targetIr instanceof StvnSchema.PoisonedSchema)) {
                        return targetIr.traits().comparable();
                    }
                    return resolveFallbackTypeDefComparable(targetDef, visited);
                }
                var aliasIr = StvnTypeResolver.resolveIrSchemaByAlias(field.getContainingFile(), typeName);
                if (aliasIr != null && !(aliasIr instanceof StvnSchema.PoisonedSchema)) {
                    return aliasIr.traits().comparable();
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
                return prod.getSchemaTypeList().stream().allMatch(f -> isFieldComparable(f, visited));
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

    private static boolean resolveFallbackTypeDefEquatable(@NotNull TypeDefinition targetDef, Set<String> visited) {
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

    private static boolean resolveFallbackTypeDefComparable(@NotNull TypeDefinition targetDef, Set<String> visited) {
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

    private static boolean isGovernedByCoreRuleW003(
        MetadataEntry entry,
        TypeDefinition typeDef,
        MetadataMap metaMap,
        String ultimateBase,
        @Nullable StvnSchema irSchema,
        @Nullable StvnSchema parentIr
    ) {
        var text = entry.getText().trim();

        // 1. #minSize 0 on string or collection is warning W003 in core
        if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
            var valOpt = extractBigInteger(text);
            if (valOpt.isPresent() && valOpt.get().signum() == 0) {
                return StvnVocabulary.TYPE_STRING.equals(ultimateBase)
                    || StvnVocabulary.TYPE_SEQ.equals(ultimateBase)
                    || StvnVocabulary.TYPE_SET.equals(ultimateBase)
                    || StvnVocabulary.TYPE_MAP.equals(ultimateBase);
            }
        }

        // 2. Natural integer boundary tautologies (Rule W003)
        if (text.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)) {
            var biOpt = extractBigInteger(text);
            if (biOpt.isPresent() && StvnVocabulary.TYPE_INT.equals(ultimateBase)) {
                boolean isUnsigned = hasLocalUnsignedFacet(metaMap)
                    || (parentIr instanceof StvnSchema.IntSchema pi && pi.unsigned());
                if (isUnsigned && biOpt.get().signum() == 0) {
                    return true;
                }
                var bitWidthOpt = resolveEffectiveShiftableBitWidth(metaMap, parentIr);
                if (!isUnsigned && bitWidthOpt.isPresent()) {
                    var naturalMin = BigInteger.ONE.shiftLeft(bitWidthOpt.getAsInt() - 1).negate();
                    if (biOpt.get().compareTo(naturalMin) == 0) {
                        return true;
                    }
                }
            }
        } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)) {
            var biOpt = extractBigInteger(text);
            if (biOpt.isPresent() && StvnVocabulary.TYPE_INT.equals(ultimateBase)) {
                boolean isUnsigned = hasLocalUnsignedFacet(metaMap)
                    || (parentIr instanceof StvnSchema.IntSchema pi && pi.unsigned());
                var bitWidthOpt = resolveEffectiveShiftableBitWidth(metaMap, parentIr);
                if (bitWidthOpt.isPresent()) {
                    int bitWidth = bitWidthOpt.getAsInt();
                    var naturalMax = isUnsigned
                        ? BigInteger.ONE.shiftLeft(bitWidth)
                        : BigInteger.ONE.shiftLeft(bitWidth - 1);
                    if (biOpt.get().compareTo(naturalMax) == 0) {
                        return true;
                    }
                }
            }
        }

        // 3. Default trait re-assertion on scalar primitives is warning W003 in core
        if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE) || text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
            var boolOpt = extractBooleanLiteral(text);
            if (boolOpt.isPresent() && boolOpt.get()) {
                return StvnVocabulary.TYPE_INT.equals(ultimateBase)
                    || StvnVocabulary.TYPE_BOOLEAN.equals(ultimateBase)
                    || StvnVocabulary.TYPE_STRING.equals(ultimateBase);
            }
        }

        // 4. Primitive Default Sizing Tautologies (Rule W003)
        if (text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
            var valOpt = extractBigInteger(text);
            if (valOpt.isPresent()) {
                BigInteger val = valOpt.get();
                if (val.equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH) && StvnVocabulary.TYPE_INT.equals(ultimateBase)) {
                    return true;
                }
                if (val.equals(StvnVocabulary.DEFAULT_FLOAT_BIT_WIDTH) && StvnVocabulary.TYPE_FLOAT.equals(ultimateBase)) {
                    boolean isExact = hasLocalExactFacet(metaMap)
                        || (irSchema instanceof StvnSchema.FloatSchema fs
                            && fs.representation() == StvnSchema.FloatRepresentation.EXACT_DECIMAL);
                    if (!isExact) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static boolean hasLocalUnsignedFacet(@Nullable MetadataMap metaMap) {
        if (metaMap == null) return false;
        for (var entry : metaMap.getMetadataEntryList()) {
            var flag = entry.getMetadataBareFlag();
            if (flag != null && flag.getText().startsWith(StvnVocabulary.FACET_KW_UNSIGNED)) {
                return true;
            }
            if (entry.getText().trim().startsWith(StvnVocabulary.FACET_KW_UNSIGNED)) {
                return true;
            }
        }
        return false;
    }

    private static OptionalInt resolveEffectiveShiftableBitWidth(
        @Nullable MetadataMap metaMap,
        @Nullable StvnSchema parentIr
    ) {
        BigInteger bw = parentIr instanceof StvnSchema.IntSchema pi
            ? pi.bitWidth()
            : StvnVocabulary.DEFAULT_INT_BIT_WIDTH;
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var text = entry.getText().trim();
                if (text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                    var valOpt = extractBigInteger(text);
                    if (valOpt.isPresent()) {
                        bw = valOpt.get();
                    }
                }
            }
        }
        if (bw.compareTo(BigInteger.ONE) >= 0
            && bw.compareTo(BigInteger.valueOf(StvnPsiLiteralEvaluator.MAX_EAGER_SHIFT_BIT_WIDTH)) <= 0) {
            return OptionalInt.of(bw.intValueExact());
        }
        return OptionalInt.empty();
    }

    private static Optional<BigInteger> extractBigInteger(String text) {
        var parts = text.split("\\s+");
        if (parts.length >= 2) {
            return StvnPsiLiteralEvaluator.parseBigInteger(parts[1].trim());
        }
        return Optional.empty();
    }

    private static void registerRedundantProblem(MetadataEntry entry, String effectiveValue, ProblemsHolder holder) {
        var entryText = entry.getText().trim();
        holder.registerProblem(
            entry,
            StvnDiagnosticFormatter.formatPlugin(
                "Redundant facet override: parent type already specifies effective value '" + effectiveValue + "'",
                StvnDiagnosticCodes.WARN_REDUNDANT_FACET_OVERRIDE
            ),
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new RemoveRedundantFacetQuickFix(entry, entryText)
        );
    }

    private static boolean isBooleanTrue(BooleanValue bVal) {
        var t = bVal.getText().trim();
        return StvnVocabulary.VAL_TRUE.equals(t) || StvnVocabulary.VAL_TRUE_SHORT.equals(t);
    }

    private static Optional<Boolean> extractBooleanLiteral(String text) {
        var parts = text.split("\\s+");
        if (parts.length >= 2) {
            var val = parts[1].trim();
            if (StvnVocabulary.VAL_TRUE.equals(val) || StvnVocabulary.VAL_TRUE_SHORT.equals(val)) return Optional.of(true);
            if (StvnVocabulary.VAL_FALSE.equals(val) || StvnVocabulary.VAL_FALSE_SHORT.equals(val)) return Optional.of(false);
        }
        return Optional.empty();
    }
}
