package org.stvnadore.plugin.documentation;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.ir.StvnSchema.DateTimeMode;
import org.stvnadore.core.ir.StvnSchema.TemporalScale;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.plugin.validation.quickfix.StvnStringConstraintUtil;
import org.stvnadore.plugin.validation.quickfix.StvnTraitViolationQuickFixProvider;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.ValueKeyword;

/**
 * Generates deterministic or live-clock STVN exemplar literals that satisfy all active metadata
 * facets of a governing {@link SchemaType} or lowered {@link StvnSchema}.
 */
@NullMarked
public final class StvnExemplarValueGenerator {

    private static final DateTimeFormatter LOCAL_SECONDS_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Deterministic RFC 4122 v4 lowercase UUID exemplar for documentation and fallback synthesis. */
    public static final String CANONICAL_UUID_LOWER_EXEMPLAR = "550e8400-e29b-41d4-a716-446655440000";

    private static final int MAX_MATERIALIZED_COLLECTION_EXEMPLAR_SIZE = 16;

    private StvnExemplarValueGenerator() {}

    /**
     * Generates a schema-compliant STVN exemplar literal using the system default clock.
     *
     * @param schemaType the PSI schema type node, if available
     * @param irSchema   the lowered compiler IR schema, if available
     * @return a valid STVN literal string, or {@code null} if degraded, uninhabited, or unavailable
     */
    public static @Nullable String generateExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema
    ) {
        return generateExemplar(schemaType, irSchema, Clock.systemDefaultZone(), 0, new HashSet<>(), new HashSet<>(), false);
    }

    /**
     * Generates a schema-compliant STVN exemplar literal using the supplied {@link Clock}.
     *
     * @param schemaType the PSI schema type node, if available
     * @param irSchema   the lowered compiler IR schema, if available
     * @param clock      the clock used for {@code :DateTime} and {@code :TimeEpoch} generation
     * @return a valid STVN literal string, or {@code null} if degraded, uninhabited, or unavailable
     */
    public static @Nullable String generateExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema,
        Clock clock
    ) {
        return generateExemplar(schemaType, irSchema, clock, 0, new HashSet<>(), new HashSet<>(), false);
    }

    /**
     * Generates an STVN exemplar formatted for hover documentation cards, rendering a clean
     * bounded preview when a valid inhabited collection declares {@code #minSize > 16}.
     *
     * @param schemaType the PSI schema type node, if available
     * @param irSchema   the lowered compiler IR schema, if available
     * @return exemplar string or bounded preview, or {@code null} when degraded or uninhabited
     */
    public static @Nullable String generateHoverExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema
    ) {
        return generateExemplar(schemaType, irSchema, Clock.systemDefaultZone(), 0, new HashSet<>(), new HashSet<>(), true);
    }

    private static boolean isRecursiveBackEdgeCode(@Nullable String code) {
        return "INTERNAL_RECURSIVE_NOMINAL_REF".equals(code)
            || "__RECURSIVE_BACK_EDGE__".equals(code)
            || "RECURSIVE_BACK_EDGE".equals(code);
    }

    private static boolean isDegradedOrPoisonedForExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema
    ) {
        if (irSchema instanceof StvnSchema.PoisonedSchema poisoned) {
            if (isRecursiveBackEdgeCode(poisoned.primaryDiagnosticCode()) && poisoned.aliasName().isPresent()) {
                if (schemaType != null && schemaType.getContainingFile() != null) {
                    StvnSchema catalogIr = StvnTypeResolver.resolveIrSchemaAtContext(
                        schemaType,
                        poisoned.aliasName().get()
                    );
                    if (catalogIr != null && !(catalogIr instanceof StvnSchema.PoisonedSchema)) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (schemaType != null && schemaType.getContainingFile() != null) {
            var file = schemaType.getContainingFile();
            if (StvnTypeResolver.isDegradedOrUngroundedSchema(file, schemaType)) {
                return true;
            }
            if (schemaType.getParent() instanceof TypeDefinition parentTd
                    && parentTd.getSchemaType() == schemaType
                    && parentTd.getTypeKeyword() != null
                    && StvnTypeResolver.isDegradedNominalAlias(parentTd.getTypeKeyword(), parentTd.getTypeKeyword().getText().trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPoisonedIrSchema(@Nullable StvnSchema irSchema, @Nullable PsiElement context) {
        if (!(irSchema instanceof StvnSchema.PoisonedSchema poisoned)) {
            return false;
        }
        if (isRecursiveBackEdgeCode(poisoned.primaryDiagnosticCode()) && poisoned.aliasName().isPresent()) {
            if (context != null && context.getContainingFile() != null) {
                StvnSchema catalogIr = StvnTypeResolver.resolveIrSchemaAtContext(
                    context,
                    poisoned.aliasName().get()
                );
                if (catalogIr != null && !(catalogIr instanceof StvnSchema.PoisonedSchema)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static @Nullable String generateExemplar(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema,
        Clock clock,
        int depth,
        Set<String> visitedIrAliases,
        Set<PsiElement> visitedPsiElements,
        boolean allowBoundedPreview
    ) {
        if (depth > 16 || (schemaType == null && irSchema == null)) {
            return null;
        }
        if (isDegradedOrPoisonedForExemplar(schemaType, irSchema)) {
            return null;
        }

        Set<String> nextIrAliases = new HashSet<>(visitedIrAliases);
        Set<PsiElement> nextPsiElements = new HashSet<>(visitedPsiElements);

        if (irSchema != null && irSchema.aliasName().isPresent()) {
            String irAlias = irSchema.aliasName().get();
            if (visitedIrAliases.contains(irAlias)) {
                return null;
            }
            nextIrAliases.add(irAlias);
        }

        if (schemaType != null) {
            if (visitedPsiElements.contains(schemaType)) {
                return null;
            }
            nextPsiElements.add(schemaType);
            if (schemaType.getParent() instanceof TypeDefinition parentTd
                    && parentTd.getSchemaType() == schemaType
                    && parentTd.getTypeKeyword() != null) {
                String lhsAlias = parentTd.getTypeKeyword().getText().trim();
                if (visitedIrAliases.contains(lhsAlias) || visitedPsiElements.contains(parentTd)) {
                    return null;
                }
                if (schemaType.getContainingFile() != null) {
                    StvnSchema lhsIr = StvnTypeResolver.resolveNominalIrSchema(parentTd);
                    if (isPoisonedIrSchema(lhsIr, parentTd)) {
                        return null;
                    }
                }
                nextIrAliases.add(lhsAlias);
                nextPsiElements.add(parentTd);
            }
            if (schemaType.getTypeKeyword() != null) {
                var kw = schemaType.getTypeKeyword();
                String kwAlias = kw.getText().trim();
                if (visitedIrAliases.contains(kwAlias)) {
                    return null;
                }
                if (schemaType.getContainingFile() != null) {
                    StvnSchema kwIr = StvnTypeResolver.resolveIrSchemaAtContext(kw, kwAlias);
                    if (isPoisonedIrSchema(kwIr, kw)) {
                        return null;
                    }
                }
                nextIrAliases.add(kwAlias);
            }
        }

        StvnSchema effectiveIr = irSchema;
        if (effectiveIr instanceof StvnSchema.PoisonedSchema poisoned
                && isRecursiveBackEdgeCode(poisoned.primaryDiagnosticCode())
                && poisoned.aliasName().isPresent()
                && schemaType != null
                && schemaType.getContainingFile() != null) {
            effectiveIr = StvnTypeResolver.resolveIrSchemaAtContext(
                schemaType,
                poisoned.aliasName().get()
            );
        }
        if (effectiveIr instanceof StvnSchema.PoisonedSchema) {
            return null;
        }

        Map<String, String> psiMeta = schemaType != null ? extractLineageMetadata(schemaType) : Map.of();
        SchemaType terminalPsi = schemaType != null
            ? resolveTerminalSchemaTypeWithVisited(
                schemaType,
                visitedIrAliases,
                visitedPsiElements,
                nextIrAliases,
                nextPsiElements
            )
            : null;
        if (schemaType != null && terminalPsi == null) {
            return null;
        }

        // 1. Check lowered IR schema first when it matches a leaf scalar or enum
        if (effectiveIr instanceof StvnSchema.DateTimeSchema dtSchema) {
            DateTimeMode mode = psiMeta.containsKey(StvnVocabulary.FACET_KW_ZONED)
                ? DateTimeMode.ZONED
                : psiMeta.containsKey(StvnVocabulary.FACET_KW_AUDITED)
                    ? DateTimeMode.AUDITED
                    : psiMeta.containsKey(StvnVocabulary.FACET_KW_OFFSET)
                        ? DateTimeMode.OFFSET
                        : dtSchema.mode();
            String minIncl = dtSchema.explicitMinInclIso()
                .orElseGet(() -> resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)));
            String maxExcl = dtSchema.explicitMaxExclIso()
                .orElseGet(() -> resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)));
            return generateDateTimeExemplar(mode, minIncl, maxExcl, clock);
        }
        if (effectiveIr instanceof StvnSchema.TimeEpochSchema epSchema) {
            TemporalScale scale = hasExplicitScaleInMeta(psiMeta)
                ? resolveEpochScaleFromMeta(psiMeta)
                : epSchema.scale();
            boolean unsigned = psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
            int defaultBits = scale.bitWidth().intValue();
            int sizeBits = parseIntOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_SIZE), defaultBits);
            BigInteger minIncl = epSchema.explicitMinIncl()
                .orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)));
            BigInteger maxExcl = epSchema.explicitMaxExcl()
                .orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)));
            return generateTimeEpochExemplar(scale, unsigned, sizeBits, minIncl, maxExcl, clock);
        }
        if (effectiveIr instanceof StvnSchema.IntSchema intSchema) {
            boolean unsigned = intSchema.unsigned() || psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
            int sizeBits = psiMeta.containsKey(StvnVocabulary.FACET_KW_SIZE)
                ? parseIntOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_SIZE), intSchema.bitWidth().intValue())
                : intSchema.bitWidth().intValue();
            BigInteger minIncl = intSchema.explicitMinIncl()
                .orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)));
            BigInteger maxExcl = intSchema.explicitMaxExcl()
                .orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)));
            return generateIntExemplar(unsigned, sizeBits, minIncl, maxExcl);
        }
        if (effectiveIr instanceof StvnSchema.FloatSchema floatSchema) {
            Double minIncl = null;
            Double minExcl = null;
            Double maxIncl = null;
            Double maxExcl = null;
            if (floatSchema.lowerBound().isPresent()) {
                var lb = floatSchema.lowerBound().get();
                if (lb.kind() == StvnSchema.BoundKind.INCLUSIVE) {
                    minIncl = lb.value().doubleValue();
                } else {
                    minExcl = lb.value().doubleValue();
                }
            }
            if (floatSchema.upperBound().isPresent()) {
                var ub = floatSchema.upperBound().get();
                if (ub.kind() == StvnSchema.BoundKind.INCLUSIVE) {
                    maxIncl = ub.value().doubleValue();
                } else {
                    maxExcl = ub.value().doubleValue();
                }
            }
            if (minIncl == null && minExcl == null) {
                minIncl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                minExcl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL));
            }
            if (maxIncl == null && maxExcl == null) {
                maxIncl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL));
                maxExcl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
            }
            return generateFloatExemplar(minIncl, minExcl, maxIncl, maxExcl);
        }
        if (effectiveIr instanceof StvnSchema.StringSchema strSchema) {
            long minSize = psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_SIZE)
                ? parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE), strSchema.minSize().longValue())
                : strSchema.minSize().longValue();
            long maxSize = psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE)
                ? parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE), strSchema.maxSize().longValue())
                : strSchema.maxSize().longValue();
            if (psiMeta.containsKey(StvnVocabulary.FACET_KW_SIZE)) {
                long fixedSize = parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_SIZE), minSize);
                minSize = fixedSize;
                maxSize = fixedSize;
            }
            boolean lowerExclusive = strSchema.lowerBound()
                .map(b -> b.kind() == StvnSchema.BoundKind.EXCLUSIVE)
                .orElseGet(() -> !psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_INCL)
                    && psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_EXCL));
            String lowerBound = strSchema.lowerBound()
                .map(StvnSchema.ContinuousBound::value)
                .orElseGet(() -> resolveStringOrConstOrNull(
                    schemaType,
                    psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_INCL)
                        ? psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)
                        : psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL)));
            boolean upperExclusive = strSchema.upperBound()
                .map(b -> b.kind() == StvnSchema.BoundKind.EXCLUSIVE)
                .orElseGet(() -> psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_EXCL));
            String upperBound = strSchema.upperBound()
                .map(StvnSchema.ContinuousBound::value)
                .orElseGet(() -> resolveStringOrConstOrNull(
                    schemaType,
                    psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_EXCL)
                        ? psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)
                        : psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL)));
            String regexPattern = strSchema.rawRegexPattern()
                .orElseGet(() -> resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_REGEX)));
            return generateStringExemplar(
                minSize,
                maxSize,
                lowerBound,
                lowerExclusive,
                upperBound,
                upperExclusive,
                regexPattern,
                strSchema.preserveIndent()
            );
        }
        if (effectiveIr instanceof StvnSchema.BooleanSchema) {
            return StvnVocabulary.VAL_TRUE;
        }
        if (effectiveIr instanceof StvnSchema.EnumSubsetSchema subsetSchema) {
            return generateEnumSubsetExemplarFromIr(subsetSchema, psiMeta);
        }
        if (effectiveIr instanceof StvnSchema.EnumSchema enumSchema) {
            return generateEnumExemplarFromIr(enumSchema, psiMeta);
        }

        // 2. Fallback or composite traversal via PSI SchemaType
        if (terminalPsi != null) {
            var ctor = terminalPsi.getSchemaConstructor();
            if (ctor != null) {
                if (ctor.getAtomicType() != null) {
                    String baseType = ctor.getAtomicType().getText().trim();
                    return switch (baseType) {
                        case StvnVocabulary.TYPE_DATE_TIME -> {
                            DateTimeMode mode = resolveDateTimeModeFromMeta(psiMeta);
                            yield generateDateTimeExemplar(
                                mode,
                                resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)),
                                resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)),
                                clock
                            );
                        }
                        case StvnVocabulary.TYPE_TIME_EPOCH -> {
                            TemporalScale scale = resolveEpochScaleFromMeta(psiMeta);
                            boolean unsigned = psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
                            int sizeBits = parseIntOrDefault(
                                schemaType,
                                psiMeta.get(StvnVocabulary.FACET_KW_SIZE),
                                scale.bitWidth().intValue()
                            );
                            BigInteger minIncl = parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                            BigInteger maxExcl = parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
                            yield generateTimeEpochExemplar(scale, unsigned, sizeBits, minIncl, maxExcl, clock);
                        }
                        case StvnVocabulary.TYPE_INT -> {
                            boolean unsigned = psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
                            int sizeBits = parseIntOrDefault(
                                schemaType,
                                psiMeta.get(StvnVocabulary.FACET_KW_SIZE),
                                StvnVocabulary.DEFAULT_INT_BIT_WIDTH.intValue()
                            );
                            BigInteger minIncl = parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                            BigInteger maxExcl = parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
                            yield generateIntExemplar(unsigned, sizeBits, minIncl, maxExcl);
                        }
                        case StvnVocabulary.TYPE_FLOAT -> generateFloatExemplar(
                            parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)),
                            parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL)),
                            parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL)),
                            parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL))
                        );
                        case StvnVocabulary.TYPE_STRING -> {
                            Long fixedSize = parseLongOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_SIZE));
                            Long minSize = fixedSize != null
                                ? fixedSize
                                : parseLongOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE));
                            Long maxSize = fixedSize != null
                                ? fixedSize
                                : parseLongOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE));
                            boolean lowerExclusive = !psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_INCL)
                                && psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_EXCL);
                            String lowerBound = resolveStringOrConstOrNull(
                                schemaType,
                                psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_INCL)
                                    ? psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)
                                    : psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL)
                            );
                            boolean upperExclusive = psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_EXCL);
                            String upperBound = resolveStringOrConstOrNull(
                                schemaType,
                                psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_EXCL)
                                    ? psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)
                                    : psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL)
                            );
                            String regexPattern = resolveStringOrConstOrNull(
                                schemaType,
                                psiMeta.get(StvnVocabulary.FACET_KW_REGEX)
                            );
                            yield generateStringExemplar(
                                minSize,
                                maxSize,
                                lowerBound,
                                lowerExclusive,
                                upperBound,
                                upperExclusive,
                                regexPattern,
                                resolvePsiPreserveIndentFlag(schemaType, psiMeta)
                            );
                        }
                        case StvnVocabulary.TYPE_BOOLEAN -> StvnVocabulary.VAL_TRUE;
                        default -> null;
                    };
                }
                if (ctor.getProductType() != null) {
                    List<SchemaType> slots = ctor.getProductType().getSchemaTypeList();
                    if (slots.isEmpty()) {
                        return null;
                    }
                    List<String> slotExemplars = new ArrayList<>(slots.size());
                    for (int i = 0; i < slots.size(); i++) {
                        StvnSchema slotIr = (effectiveIr instanceof StvnSchema.TupleSchema ts
                                && i < ts.fieldSchemas().size())
                            ? ts.fieldSchemas().get(i)
                            : null;
                        String sub = generateExemplar(
                            slots.get(i), slotIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview
                        );
                        if (sub == null) {
                            return null;
                        }
                        slotExemplars.add(sub);
                    }
                    return "(" + String.join(" ", slotExemplars) + ")";
                }
                if (ctor.getCollectionType() != null) {
                    var coll = ctor.getCollectionType();
                    String kind = coll.getFirstChild() != null ? coll.getFirstChild().getText().trim() : "";
                    var innerTypes = coll.getSchemaTypeList();
                    if (StvnVocabulary.TYPE_MAP.equals(kind)) {
                        if (innerTypes.size() != 2) {
                            return null;
                        }
                        StvnSchema keyIr = effectiveIr instanceof StvnSchema.MapSchema ms ? ms.keySchema() : null;
                        StvnSchema valIr = effectiveIr instanceof StvnSchema.MapSchema ms ? ms.valueSchema() : null;
                        if (isDegradedOrPoisonedForExemplar(innerTypes.get(0), keyIr)
                                || isDegradedOrPoisonedForExemplar(innerTypes.get(1), valIr)) {
                            return null;
                        }
                        boolean invertible = (effectiveIr instanceof StvnSchema.MapSchema ms && ms.invertible())
                            || psiMeta.containsKey(StvnVocabulary.FACET_KW_INVERTIBLE);
                        if (effectiveIr == null) {
                            if (!StvnTraitViolationQuickFixProvider.isSchemaTypeEquatable(innerTypes.get(0), new HashSet<>())
                                    || (invertible && !StvnTraitViolationQuickFixProvider.isSchemaTypeEquatable(innerTypes.get(1), new HashSet<>()))) {
                                return null;
                            }
                        }
                        long minSize = effectiveIr instanceof StvnSchema.MapSchema ms && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_SIZE)
                            ? ms.minSize().longValue()
                            : parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE), 0L);
                        long maxSize = effectiveIr instanceof StvnSchema.MapSchema ms && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE)
                            ? ms.maxSize().longValue()
                            : parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE), StvnVocabulary.DEFAULT_MAX_SIZE.longValue());
                        return synthesizeMapCollectionExemplar(
                            innerTypes.get(0),
                            keyIr,
                            innerTypes.get(1),
                            valIr,
                            minSize,
                            maxSize,
                            invertible,
                            clock,
                            depth,
                            nextIrAliases,
                            nextPsiElements,
                            allowBoundedPreview
                        );
                    }
                    if (innerTypes.size() != 1) {
                        return null;
                    }
                    boolean isSet = StvnVocabulary.TYPE_SET.equals(kind);
                    StvnSchema elemIr = effectiveIr instanceof StvnSchema.SeqSchema ss
                        ? ss.elementSchema()
                        : effectiveIr instanceof StvnSchema.SetSchema sets ? sets.elementSchema() : null;
                    if (isDegradedOrPoisonedForExemplar(innerTypes.get(0), elemIr)) {
                        return null;
                    }
                    if (isSet && effectiveIr == null
                            && !StvnTraitViolationQuickFixProvider.isSchemaTypeEquatable(innerTypes.get(0), new HashSet<>())) {
                        return null;
                    }
                    long minSize = (effectiveIr instanceof StvnSchema.SeqSchema ss && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_SIZE))
                        ? ss.minSize().longValue()
                        : (effectiveIr instanceof StvnSchema.SetSchema sets && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_SIZE))
                            ? sets.minSize().longValue()
                            : parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE), 0L);
                    long maxSize = (effectiveIr instanceof StvnSchema.SeqSchema ss && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE))
                        ? ss.maxSize().longValue()
                        : (effectiveIr instanceof StvnSchema.SetSchema sets && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE))
                            ? sets.maxSize().longValue()
                            : parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE), StvnVocabulary.DEFAULT_MAX_SIZE.longValue());
                    return synthesizeSeqOrSetCollectionExemplar(
                        innerTypes.get(0),
                        elemIr,
                        isSet,
                        minSize,
                        maxSize,
                        clock,
                        depth,
                        nextIrAliases,
                        nextPsiElements,
                        allowBoundedPreview
                    );
                }
                if (ctor.getSumType() != null) {
                    SumType sum = ctor.getSumType();
                    String sumText = sum.getText().trim();
                    if (sum.getEnumDef() != null || sumText.startsWith(StvnVocabulary.TYPE_ENUM)) {
                        return generateEnumExemplarFromPsi(sum, psiMeta);
                    }
                    List<SchemaType> branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                    if (sumText.startsWith(StvnVocabulary.TYPE_OPTION)) {
                        if (branches.size() != 1) {
                            return null;
                        }
                        StvnSchema innerIr = effectiveIr instanceof StvnSchema.OptionSchema os ? os.valueSchema() : null;
                        if (isDegradedOrPoisonedForExemplar(branches.get(0), innerIr)) {
                            return null;
                        }
                        String inner = generateExemplar(
                            branches.get(0), innerIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview
                        );
                        return inner != null ? StvnVocabulary.VAL_SOME + " " + inner : StvnVocabulary.VAL_NONE;
                    }
                    if (sumText.startsWith(StvnVocabulary.TYPE_EITHER)) {
                        if (branches.size() != 2) {
                            return null;
                        }
                        StvnSchema leftIr = effectiveIr instanceof StvnSchema.EitherSchema es ? es.leftSchema() : null;
                        StvnSchema rightIr = effectiveIr instanceof StvnSchema.EitherSchema es ? es.rightSchema() : null;
                        if (isDegradedOrPoisonedForExemplar(branches.get(0), leftIr)
                                || isDegradedOrPoisonedForExemplar(branches.get(1), rightIr)) {
                            return null;
                        }
                        String right = generateExemplar(
                            branches.get(1), rightIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview
                        );
                        if (right != null) {
                            return StvnVocabulary.VAL_RIGHT + " " + right;
                        }
                        String left = generateExemplar(
                            branches.get(0), leftIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview
                        );
                        return left != null ? StvnVocabulary.VAL_LEFT + " " + left : null;
                    }
                    if (sumText.startsWith(StvnVocabulary.TYPE_UNION)) {
                        if (branches.size() < 2) {
                            return null;
                        }
                        for (int i = 0; i < branches.size(); i++) {
                            StvnSchema branchIr = (effectiveIr instanceof StvnSchema.UnionSchema us
                                    && i < us.branchSchemas().size())
                                ? us.branchSchemas().get(i)
                                : null;
                            if (isDegradedOrPoisonedForExemplar(branches.get(i), branchIr)) {
                                return null;
                            }
                        }
                        for (int i = 0; i < branches.size(); i++) {
                            StvnSchema branchIr = (effectiveIr instanceof StvnSchema.UnionSchema us
                                    && i < us.branchSchemas().size())
                                ? us.branchSchemas().get(i)
                                : null;
                            String branchVal = generateExemplar(
                                branches.get(i),
                                branchIr,
                                clock,
                                depth + 1,
                                nextIrAliases,
                                nextPsiElements,
                                allowBoundedPreview
                            );
                            if (branchVal != null) {
                                return StvnVocabulary.SIGIL_VALUE + (i + 1) + " " + branchVal;
                            }
                        }
                        return null;
                    }
                }
            }
        }

        // 3. Pure-IR composite fallback when PSI SchemaType is null
        if (effectiveIr instanceof StvnSchema.TupleSchema ts) {
            if (ts.fieldSchemas().isEmpty()) {
                return null;
            }
            List<String> slotExemplars = new ArrayList<>(ts.fieldSchemas().size());
            for (StvnSchema elem : ts.fieldSchemas()) {
                String sub = generateExemplar(null, elem, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
                if (sub == null) {
                    return null;
                }
                slotExemplars.add(sub);
            }
            return "(" + String.join(" ", slotExemplars) + ")";
        }
        if (effectiveIr instanceof StvnSchema.SeqSchema ss) {
            return synthesizeSeqOrSetCollectionExemplar(
                null,
                ss.elementSchema(),
                false,
                ss.minSize().longValue(),
                ss.maxSize().longValue(),
                clock,
                depth,
                nextIrAliases,
                nextPsiElements,
                allowBoundedPreview
            );
        }
        if (effectiveIr instanceof StvnSchema.SetSchema sets) {
            return synthesizeSeqOrSetCollectionExemplar(
                null,
                sets.elementSchema(),
                true,
                sets.minSize().longValue(),
                sets.maxSize().longValue(),
                clock,
                depth,
                nextIrAliases,
                nextPsiElements,
                allowBoundedPreview
            );
        }
        if (effectiveIr instanceof StvnSchema.MapSchema ms) {
            return synthesizeMapCollectionExemplar(
                null,
                ms.keySchema(),
                null,
                ms.valueSchema(),
                ms.minSize().longValue(),
                ms.maxSize().longValue(),
                ms.invertible(),
                clock,
                depth,
                nextIrAliases,
                nextPsiElements,
                allowBoundedPreview
            );
        }
        if (effectiveIr instanceof StvnSchema.OptionSchema os) {
            if (isDegradedOrPoisonedForExemplar(null, os.valueSchema())) {
                return null;
            }
            String inner = generateExemplar(null, os.valueSchema(), clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
            return inner != null ? StvnVocabulary.VAL_SOME + " " + inner : StvnVocabulary.VAL_NONE;
        }
        if (effectiveIr instanceof StvnSchema.EitherSchema es) {
            if (isDegradedOrPoisonedForExemplar(null, es.leftSchema())
                    || isDegradedOrPoisonedForExemplar(null, es.rightSchema())) {
                return null;
            }
            String right = generateExemplar(null, es.rightSchema(), clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
            if (right != null) {
                return StvnVocabulary.VAL_RIGHT + " " + right;
            }
            String left = generateExemplar(null, es.leftSchema(), clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
            return left != null ? StvnVocabulary.VAL_LEFT + " " + left : null;
        }
        if (effectiveIr instanceof StvnSchema.UnionSchema us && us.branchSchemas().size() >= 2) {
            for (StvnSchema b : us.branchSchemas()) {
                if (isDegradedOrPoisonedForExemplar(null, b)) {
                    return null;
                }
            }
            for (int i = 0; i < us.branchSchemas().size(); i++) {
                String bVal = generateExemplar(
                    null,
                    us.branchSchemas().get(i),
                    clock,
                    depth + 1,
                    nextIrAliases,
                    nextPsiElements,
                    allowBoundedPreview
                );
                if (bVal != null) {
                    return StvnVocabulary.SIGIL_VALUE + (i + 1) + " " + bVal;
                }
            }
            return null;
        }

        return null;
    }

    private static @Nullable String synthesizeSeqOrSetCollectionExemplar(
        @Nullable SchemaType elemPsi,
        @Nullable StvnSchema elemIr,
        boolean isSet,
        long minSize,
        long maxSize,
        Clock clock,
        int depth,
        Set<String> nextIrAliases,
        Set<PsiElement> nextPsiElements,
        boolean allowBoundedPreview
    ) {
        if (minSize < 0 || maxSize < 1 || minSize > maxSize) {
            return null;
        }
        if (minSize == 0) {
            return "[]";
        }
        if (minSize == 1) {
            String elem = generateExemplar(elemPsi, elemIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
            return elem != null ? "[" + elem + "]" : null;
        }
        if (!isSet) {
            String elem = generateExemplar(elemPsi, elemIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
            if (elem == null) {
                return null;
            }
            if (minSize <= MAX_MATERIALIZED_COLLECTION_EXEMPLAR_SIZE) {
                return "[" + String.join(" ", Collections.nCopies((int) minSize, elem)) + "]";
            }
            if (!allowBoundedPreview) {
                return null;
            }
            return "[" + elem + " " + elem + " " + elem
                + " ... (" + StvnVocabulary.FACET_KW_MIN_SIZE + " " + minSize + ")]";
        }

        // :Set with minSize > 1 requires minSize distinct inhabitants
        Long maxDomainCard = estimateMaxDistinctCardinality(elemPsi, elemIr);
        if (maxDomainCard != null && maxDomainCard < minSize) {
            return null;
        }
        if (minSize <= MAX_MATERIALIZED_COLLECTION_EXEMPLAR_SIZE) {
            List<String> distinct = generateDistinctExemplars(
                elemPsi, elemIr, (int) minSize, clock, depth + 1, nextIrAliases, nextPsiElements
            );
            if (distinct.size() < minSize) {
                return null;
            }
            return "[" + String.join(" ", distinct) + "]";
        }
        if (!allowBoundedPreview) {
            return null;
        }
        List<String> previewItems = generateDistinctExemplars(
            elemPsi, elemIr, 3, clock, depth + 1, nextIrAliases, nextPsiElements
        );
        if (previewItems.size() < 3) {
            return null;
        }
        return "[" + previewItems.get(0) + " " + previewItems.get(1) + " " + previewItems.get(2)
            + " ... (" + StvnVocabulary.FACET_KW_MIN_SIZE + " " + minSize + ")]";
    }

    private static @Nullable String synthesizeMapCollectionExemplar(
        @Nullable SchemaType keyPsi,
        @Nullable StvnSchema keyIr,
        @Nullable SchemaType valPsi,
        @Nullable StvnSchema valIr,
        long minSize,
        long maxSize,
        boolean invertible,
        Clock clock,
        int depth,
        Set<String> nextIrAliases,
        Set<PsiElement> nextPsiElements,
        boolean allowBoundedPreview
    ) {
        if (minSize < 0 || maxSize < 1 || minSize > maxSize) {
            return null;
        }
        if (minSize == 0) {
            return "{}";
        }
        if (minSize == 1) {
            String k = generateExemplar(keyPsi, keyIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
            String v = generateExemplar(valPsi, valIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
            return (k != null && v != null) ? "{ [" + k + " " + v + "] }" : null;
        }
        Long maxKeyCard = estimateMaxDistinctCardinality(keyPsi, keyIr);
        if (maxKeyCard != null && maxKeyCard < minSize) {
            return null;
        }
        if (invertible) {
            Long maxValCard = estimateMaxDistinctCardinality(valPsi, valIr);
            if (maxValCard != null && maxValCard < minSize) {
                return null;
            }
        }
        if (minSize <= MAX_MATERIALIZED_COLLECTION_EXEMPLAR_SIZE) {
            int count = (int) minSize;
            List<String> keys = generateDistinctExemplars(
                keyPsi, keyIr, count, clock, depth + 1, nextIrAliases, nextPsiElements
            );
            if (keys.size() < count) {
                return null;
            }
            List<String> vals;
            if (invertible) {
                vals = generateDistinctExemplars(
                    valPsi, valIr, count, clock, depth + 1, nextIrAliases, nextPsiElements
                );
                if (vals.size() < count) {
                    return null;
                }
            } else {
                String v0 = generateExemplar(valPsi, valIr, clock, depth + 1, nextIrAliases, nextPsiElements, allowBoundedPreview);
                if (v0 == null) {
                    return null;
                }
                vals = Collections.nCopies(count, v0);
            }
            List<String> pairs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                pairs.add("[" + keys.get(i) + " " + vals.get(i) + "]");
            }
            return "{ " + String.join(" ", pairs) + " }";
        }
        if (!allowBoundedPreview) {
            return null;
        }
        List<String> keys = generateDistinctExemplars(
            keyPsi, keyIr, 3, clock, depth + 1, nextIrAliases, nextPsiElements
        );
        if (keys.size() < 3) {
            return null;
        }
        List<String> vals;
        if (invertible) {
            vals = generateDistinctExemplars(
                valPsi, valIr, 3, clock, depth + 1, nextIrAliases, nextPsiElements
            );
            if (vals.size() < 3) {
                return null;
            }
        } else {
            String v0 = generateExemplar(valPsi, valIr, clock, depth + 1, nextIrAliases, nextPsiElements, true);
            if (v0 == null) {
                return null;
            }
            vals = Collections.nCopies(3, v0);
        }
        return "{ [" + keys.get(0) + " " + vals.get(0) + "] [" + keys.get(1) + " " + vals.get(1)
            + "] [" + keys.get(2) + " " + vals.get(2) + "] ... (" + StvnVocabulary.FACET_KW_MIN_SIZE + " " + minSize + ") }";
    }

    private static @Nullable Long estimateMaxDistinctCardinality(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema
    ) {
        if (irSchema instanceof StvnSchema.BooleanSchema) {
            return 2L;
        }
        if (irSchema instanceof StvnSchema.EnumSchema es) {
            return (long) es.rootVariants().size();
        }
        if (irSchema instanceof StvnSchema.EnumSubsetSchema ess) {
            return (long) ess.activeVariants().size();
        }
        if (irSchema instanceof StvnSchema.IntSchema is) {
            BigInteger[] bounds = computeEffectiveIntegerBounds(
                is.unsigned(),
                is.bitWidth().intValue(),
                is.explicitMinIncl().orElse(null),
                is.explicitMaxExcl().orElse(null)
            );
            BigInteger span = bounds[1].subtract(bounds[0]).add(BigInteger.ONE);
            if (span.signum() <= 0) {
                return 0L;
            }
            return span.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? null : span.longValue();
        }
        if (schemaType != null) {
            SchemaType terminal = resolveTerminalSchemaType(schemaType);
            Map<String, String> meta = extractLineageMetadata(schemaType);
            var ctor = terminal.getSchemaConstructor();
            if (ctor != null) {
                if (ctor.getAtomicType() != null) {
                    String atom = ctor.getAtomicType().getText().trim();
                    if (StvnVocabulary.TYPE_BOOLEAN.equals(atom)) {
                        return 2L;
                    }
                    if (StvnVocabulary.TYPE_INT.equals(atom)) {
                        boolean unsigned = meta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
                        int bits = parseIntOrDefault(
                            schemaType, meta.get(StvnVocabulary.FACET_KW_SIZE), StvnVocabulary.DEFAULT_INT_BIT_WIDTH.intValue()
                        );
                        BigInteger[] bounds = computeEffectiveIntegerBounds(
                            unsigned,
                            bits,
                            parseBigIntOrNull(schemaType, meta.get(StvnVocabulary.FACET_KW_MIN_INCL)),
                            parseBigIntOrNull(schemaType, meta.get(StvnVocabulary.FACET_KW_MAX_EXCL))
                        );
                        BigInteger span = bounds[1].subtract(bounds[0]).add(BigInteger.ONE);
                        if (span.signum() <= 0) {
                            return 0L;
                        }
                        return span.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) >= 0 ? null : span.longValue();
                    }
                }
                if (ctor.getSumType() != null && ctor.getSumType().getEnumDef() != null) {
                    Set<String> active = new LinkedHashSet<>();
                    for (ValueKeyword kw : ctor.getSumType().getEnumDef().getValueKeywordList()) {
                        String t = kw.getText().trim();
                        if (!t.isEmpty()) {
                            active.add(ensureHashPrefix(t));
                        }
                    }
                    applyEnumFiltersFromMeta(active, meta);
                    return (long) active.size();
                }
            }
        }
        return null;
    }

    private static List<String> generateDistinctExemplars(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema irSchema,
        int count,
        Clock clock,
        int depth,
        Set<String> visitedIrAliases,
        Set<PsiElement> visitedPsiElements
    ) {
        if (count <= 0 || depth > 16 || isDegradedOrPoisonedForExemplar(schemaType, irSchema)) {
            return List.of();
        }
        String first = generateExemplar(schemaType, irSchema, clock, depth, visitedIrAliases, visitedPsiElements, false);
        if (first == null) {
            return List.of();
        }
        if (count == 1) {
            return List.of(first);
        }

        StvnSchema effectiveIr = irSchema;
        if (effectiveIr instanceof StvnSchema.PoisonedSchema poisoned
                && isRecursiveBackEdgeCode(poisoned.primaryDiagnosticCode())
                && poisoned.aliasName().isPresent()
                && schemaType != null
                && schemaType.getContainingFile() != null) {
            effectiveIr = StvnTypeResolver.resolveIrSchemaAtContext(
                schemaType,
                poisoned.aliasName().get()
            );
        }

        Map<String, String> psiMeta = schemaType != null ? extractLineageMetadata(schemaType) : Map.of();
        SchemaType terminalPsi = schemaType != null ? resolveTerminalSchemaType(schemaType) : null;
        Set<String> results = new LinkedHashSet<>();

        // 1. Boolean
        if (effectiveIr instanceof StvnSchema.BooleanSchema
                || (terminalPsi != null
                    && terminalPsi.getSchemaConstructor() != null
                    && terminalPsi.getSchemaConstructor().getAtomicType() != null
                    && StvnVocabulary.TYPE_BOOLEAN.equals(terminalPsi.getSchemaConstructor().getAtomicType().getText().trim()))) {
            results.add(StvnVocabulary.VAL_TRUE);
            if (count >= 2) {
                results.add(StvnVocabulary.VAL_FALSE);
            }
            return new ArrayList<>(results);
        }

        // 2. Enum / EnumSubset
        if (effectiveIr instanceof StvnSchema.EnumSubsetSchema subsetIr) {
            Set<String> active = new LinkedHashSet<>();
            for (String v : subsetIr.activeVariants()) {
                active.add(ensureHashPrefix(v));
            }
            applyEnumFiltersFromMeta(active, psiMeta);
            for (String v : active) {
                results.add(v);
                if (results.size() >= count) {
                    break;
                }
            }
            return new ArrayList<>(results);
        }
        if (effectiveIr instanceof StvnSchema.EnumSchema enumIr) {
            Set<String> active = new LinkedHashSet<>();
            for (String v : enumIr.rootVariants()) {
                active.add(ensureHashPrefix(v));
            }
            applyEnumFiltersFromMeta(active, psiMeta);
            for (String v : active) {
                results.add(v);
                if (results.size() >= count) {
                    break;
                }
            }
            return new ArrayList<>(results);
        }
        if (terminalPsi != null
                && terminalPsi.getSchemaConstructor() != null
                && terminalPsi.getSchemaConstructor().getSumType() != null
                && terminalPsi.getSchemaConstructor().getSumType().getEnumDef() != null) {
            Set<String> active = new LinkedHashSet<>();
            for (ValueKeyword kw : terminalPsi.getSchemaConstructor().getSumType().getEnumDef().getValueKeywordList()) {
                String t = kw.getText().trim();
                if (!t.isEmpty()) {
                    active.add(ensureHashPrefix(t));
                }
            }
            applyEnumFiltersFromMeta(active, psiMeta);
            for (String v : active) {
                results.add(v);
                if (results.size() >= count) {
                    break;
                }
            }
            return new ArrayList<>(results);
        }

        // 3. Int / TimeEpoch
        boolean isInt = effectiveIr instanceof StvnSchema.IntSchema
            || (terminalPsi != null
                && terminalPsi.getSchemaConstructor() != null
                && terminalPsi.getSchemaConstructor().getAtomicType() != null
                && StvnVocabulary.TYPE_INT.equals(terminalPsi.getSchemaConstructor().getAtomicType().getText().trim()));
        boolean isEpoch = effectiveIr instanceof StvnSchema.TimeEpochSchema
            || (terminalPsi != null
                && terminalPsi.getSchemaConstructor() != null
                && terminalPsi.getSchemaConstructor().getAtomicType() != null
                && StvnVocabulary.TYPE_TIME_EPOCH.equals(terminalPsi.getSchemaConstructor().getAtomicType().getText().trim()));
        if (isInt || isEpoch) {
            boolean unsigned = (effectiveIr instanceof StvnSchema.IntSchema is && is.unsigned())
                || psiMeta.containsKey(StvnVocabulary.FACET_KW_UNSIGNED);
            int defaultBits = isEpoch
                ? (effectiveIr instanceof StvnSchema.TimeEpochSchema ep
                    ? ep.scale().bitWidth().intValue()
                    : resolveEpochScaleFromMeta(psiMeta).bitWidth().intValue())
                : (effectiveIr instanceof StvnSchema.IntSchema is
                    ? is.bitWidth().intValue()
                    : StvnVocabulary.DEFAULT_INT_BIT_WIDTH.intValue());
            int bits = parseIntOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_SIZE), defaultBits);
            BigInteger minIncl = effectiveIr instanceof StvnSchema.IntSchema is
                ? is.explicitMinIncl().orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)))
                : effectiveIr instanceof StvnSchema.TimeEpochSchema ep
                    ? ep.explicitMinIncl().orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)))
                    : parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
            BigInteger maxExcl = effectiveIr instanceof StvnSchema.IntSchema is
                ? is.explicitMaxExcl().orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)))
                : effectiveIr instanceof StvnSchema.TimeEpochSchema ep
                    ? ep.explicitMaxExcl().orElseGet(() -> parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)))
                    : parseBigIntOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
            BigInteger[] bounds = computeEffectiveIntegerBounds(unsigned, bits, minIncl, maxExcl);
            BigInteger effMin = bounds[0];
            BigInteger effMaxIncl = bounds[1];
            BigInteger neededSpan = BigInteger.valueOf(count - 1L);
            if (effMaxIncl.subtract(effMin).compareTo(neededSpan) < 0) {
                return List.of();
            }
            BigInteger base = StvnPsiLiteralEvaluator.parseBigInteger(first).orElse(effMin);
            if (base.add(neededSpan).compareTo(effMaxIncl) > 0) {
                base = effMaxIncl.subtract(neededSpan);
            }
            if (base.compareTo(effMin) < 0) {
                base = effMin;
            }
            for (int i = 0; i < count; i++) {
                results.add(base.add(BigInteger.valueOf(i)).toString());
            }
            return new ArrayList<>(results);
        }

        // 4. DateTime
        boolean isDateTime = effectiveIr instanceof StvnSchema.DateTimeSchema
            || (terminalPsi != null
                && terminalPsi.getSchemaConstructor() != null
                && terminalPsi.getSchemaConstructor().getAtomicType() != null
                && StvnVocabulary.TYPE_DATE_TIME.equals(terminalPsi.getSchemaConstructor().getAtomicType().getText().trim()));
        if (isDateTime) {
            DateTimeMode mode = effectiveIr instanceof StvnSchema.DateTimeSchema dt && !psiMeta.containsKey(StvnVocabulary.FACET_KW_ZONED)
                && !psiMeta.containsKey(StvnVocabulary.FACET_KW_AUDITED) && !psiMeta.containsKey(StvnVocabulary.FACET_KW_OFFSET)
                ? dt.mode()
                : resolveDateTimeModeFromMeta(psiMeta);
            String minIso = effectiveIr instanceof StvnSchema.DateTimeSchema dt
                ? dt.explicitMinInclIso().orElseGet(() -> resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)))
                : resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
            String maxIso = effectiveIr instanceof StvnSchema.DateTimeSchema dt
                ? dt.explicitMaxExclIso().orElseGet(() -> resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)))
                : resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
            ZoneId zone = resolveExemplarZoneId(clock.getZone());
            Instant minInst = tryParseInstantBound(minIso, zone);
            Instant maxInst = tryParseInstantBound(maxIso, zone);
            Instant start = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
            if (minInst != null && start.isBefore(minInst)) {
                start = minInst;
            }
            if (maxInst != null && !start.plusSeconds(count - 1L).isBefore(maxInst)) {
                Instant shifted = maxInst.minusSeconds(count);
                start = (minInst != null && shifted.isBefore(minInst)) ? minInst : shifted;
            }
            for (int i = 0; i < count; i++) {
                Instant cur = start.plusSeconds(i);
                if (maxInst != null && !cur.isBefore(maxInst)) {
                    break;
                }
                results.add(generateDateTimeExemplar(mode, null, null, Clock.fixed(cur, zone)));
            }
            return new ArrayList<>(results);
        }

        // 5. Float (including { #exact } :Float)
        boolean isFloat = effectiveIr instanceof StvnSchema.FloatSchema
            || (terminalPsi != null
                && terminalPsi.getSchemaConstructor() != null
                && terminalPsi.getSchemaConstructor().getAtomicType() != null
                && StvnVocabulary.TYPE_FLOAT.equals(terminalPsi.getSchemaConstructor().getAtomicType().getText().trim()));
        if (isFloat) {
            Double minIncl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
            Double minExcl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL));
            Double maxIncl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL));
            Double maxExcl = parseDoubleOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
            if (effectiveIr instanceof StvnSchema.FloatSchema fs) {
                if (fs.lowerBound().isPresent()) {
                    var lb = fs.lowerBound().get();
                    if (lb.kind() == StvnSchema.BoundKind.INCLUSIVE) minIncl = lb.value().doubleValue();
                    else minExcl = lb.value().doubleValue();
                }
                if (fs.upperBound().isPresent()) {
                    var ub = fs.upperBound().get();
                    if (ub.kind() == StvnSchema.BoundKind.INCLUSIVE) maxIncl = ub.value().doubleValue();
                    else maxExcl = ub.value().doubleValue();
                }
            }
            double baseVal = Double.parseDouble(first);
            double upper = maxExcl != null ? maxExcl : (maxIncl != null ? maxIncl : Double.MAX_VALUE);
            boolean upperExcl = maxExcl != null;
            double step = (upper < Double.MAX_VALUE && baseVal + count > upper)
                ? Math.max(1e-6, (upper - baseVal) / (count + 1.0))
                : 1.0;
            for (int i = 0; i < count; i++) {
                double cand = baseVal + (i * step);
                if (upper < Double.MAX_VALUE && (upperExcl ? cand >= upper : cand > upper)) {
                    break;
                }
                results.add(formatFloatLiteral(cand));
            }
            return new ArrayList<>(results);
        }

        // 6. String
        boolean isString = effectiveIr instanceof StvnSchema.StringSchema
            || (terminalPsi != null
                && terminalPsi.getSchemaConstructor() != null
                && terminalPsi.getSchemaConstructor().getAtomicType() != null
                && StvnVocabulary.TYPE_STRING.equals(terminalPsi.getSchemaConstructor().getAtomicType().getText().trim()));
        if (isString) {
            return generateDistinctStringExemplars(schemaType, effectiveIr, psiMeta, first, count);
        }

        // 7. Composite fallback (:Option, :Either, :Union, :Tuple, :Seq)
        results.add(first);
        if (terminalPsi != null && terminalPsi.getSchemaConstructor() != null) {
            var ctor = terminalPsi.getSchemaConstructor();
            if (ctor.getSumType() != null) {
                var sum = ctor.getSumType();
                var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                String sumText = sum.getText().trim();
                if (sumText.startsWith(StvnVocabulary.TYPE_OPTION) && branches.size() == 1) {
                    results.add(StvnVocabulary.VAL_NONE);
                    StvnSchema innerIr = effectiveIr instanceof StvnSchema.OptionSchema os ? os.valueSchema() : null;
                    for (String sub : generateDistinctExemplars(branches.get(0), innerIr, count, clock, depth + 1, visitedIrAliases, visitedPsiElements)) {
                        results.add(StvnVocabulary.VAL_SOME + " " + sub);
                        if (results.size() >= count) break;
                    }
                } else if (sumText.startsWith(StvnVocabulary.TYPE_EITHER) && branches.size() == 2) {
                    StvnSchema leftIr = effectiveIr instanceof StvnSchema.EitherSchema es ? es.leftSchema() : null;
                    StvnSchema rightIr = effectiveIr instanceof StvnSchema.EitherSchema es ? es.rightSchema() : null;
                    for (String sub : generateDistinctExemplars(branches.get(0), leftIr, count, clock, depth + 1, visitedIrAliases, visitedPsiElements)) {
                        results.add(StvnVocabulary.VAL_LEFT + " " + sub);
                        if (results.size() >= count) break;
                    }
                    if (results.size() < count) {
                        for (String sub : generateDistinctExemplars(branches.get(1), rightIr, count, clock, depth + 1, visitedIrAliases, visitedPsiElements)) {
                            results.add(StvnVocabulary.VAL_RIGHT + " " + sub);
                            if (results.size() >= count) break;
                        }
                    }
                } else if (sumText.startsWith(StvnVocabulary.TYPE_UNION) && branches.size() >= 2) {
                    for (int i = 0; i < branches.size() && results.size() < count; i++) {
                        StvnSchema bIr = effectiveIr instanceof StvnSchema.UnionSchema us && i < us.branchSchemas().size()
                            ? us.branchSchemas().get(i) : null;
                        for (String sub : generateDistinctExemplars(branches.get(i), bIr, count, clock, depth + 1, visitedIrAliases, visitedPsiElements)) {
                            results.add(StvnVocabulary.SIGIL_VALUE + (i + 1) + " " + sub);
                            if (results.size() >= count) break;
                        }
                    }
                }
            } else if (ctor.getProductType() != null) {
                var slots = ctor.getProductType().getSchemaTypeList();
                List<List<String>> slotChoices = new ArrayList<>(slots.size());
                for (int i = 0; i < slots.size(); i++) {
                    StvnSchema sIr = effectiveIr instanceof StvnSchema.TupleSchema ts && i < ts.fieldSchemas().size()
                        ? ts.fieldSchemas().get(i) : null;
                    List<String> choices = generateDistinctExemplars(slots.get(i), sIr, count, clock, depth + 1, visitedIrAliases, visitedPsiElements);
                    if (choices.isEmpty()) return List.of();
                    slotChoices.add(choices);
                }
                collectTupleCartesianChoices(slotChoices, 0, new ArrayList<>(slots.size()), results, count);
            } else if (ctor.getCollectionType() != null) {
                var coll = ctor.getCollectionType();
                var inners = coll.getSchemaTypeList();
                if (inners.size() == 1 && coll.getText().trim().startsWith(StvnVocabulary.TYPE_SEQ)) {
                    long seqMin = effectiveIr instanceof StvnSchema.SeqSchema ss
                        ? ss.minSize().longValue()
                        : parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE), 0L);
                    long seqMax = effectiveIr instanceof StvnSchema.SeqSchema ss
                        ? ss.maxSize().longValue()
                        : parseLongOrDefault(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE), StvnVocabulary.DEFAULT_MAX_SIZE.longValue());
                    StvnSchema eIr = effectiveIr instanceof StvnSchema.SeqSchema ss ? ss.elementSchema() : null;
                    String elem = generateExemplar(inners.get(0), eIr, clock, depth + 1, visitedIrAliases, visitedPsiElements, false);
                    if (elem != null) {
                        for (long len = seqMin; len <= seqMax && results.size() < count && len <= MAX_MATERIALIZED_COLLECTION_EXEMPLAR_SIZE; len++) {
                            results.add(len == 0 ? "[]" : "[" + String.join(" ", Collections.nCopies((int) len, elem)) + "]");
                        }
                    }
                }
            }
        }
        return new ArrayList<>(results);
    }

    private static void collectTupleCartesianChoices(
        List<List<String>> slotChoices,
        int idx,
        List<String> current,
        Set<String> out,
        int maxCount
    ) {
        if (out.size() >= maxCount) {
            return;
        }
        if (idx == slotChoices.size()) {
            out.add("(" + String.join(" ", current) + ")");
            return;
        }
        for (String choice : slotChoices.get(idx)) {
            current.add(choice);
            collectTupleCartesianChoices(slotChoices, idx + 1, current, out, maxCount);
            current.remove(current.size() - 1);
            if (out.size() >= maxCount) {
                return;
            }
        }
    }

    private static List<String> generateDistinctStringExemplars(
        @Nullable SchemaType schemaType,
        @Nullable StvnSchema effectiveIr,
        Map<String, String> psiMeta,
        String firstQuoted,
        int count
    ) {
        Long minSize = effectiveIr instanceof StvnSchema.StringSchema ss && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_SIZE)
            ? ss.minSize().longValue()
            : parseLongOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MIN_SIZE));
        Long maxSize = effectiveIr instanceof StvnSchema.StringSchema ss && !psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_SIZE)
            ? ss.maxSize().longValue()
            : parseLongOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_MAX_SIZE));
        boolean lowerExcl = effectiveIr instanceof StvnSchema.StringSchema ss && ss.lowerBound().isPresent()
            ? ss.lowerBound().get().kind() == StvnSchema.BoundKind.EXCLUSIVE
            : (!psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_INCL) && psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_EXCL));
        String lowerBound = effectiveIr instanceof StvnSchema.StringSchema ss && ss.lowerBound().isPresent()
            ? ss.lowerBound().get().value()
            : resolveStringOrConstOrNull(
                schemaType,
                psiMeta.containsKey(StvnVocabulary.FACET_KW_MIN_INCL)
                    ? psiMeta.get(StvnVocabulary.FACET_KW_MIN_INCL)
                    : psiMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL)
            );
        boolean upperExcl = effectiveIr instanceof StvnSchema.StringSchema ss && ss.upperBound().isPresent()
            ? ss.upperBound().get().kind() == StvnSchema.BoundKind.EXCLUSIVE
            : psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_EXCL);
        String upperBound = effectiveIr instanceof StvnSchema.StringSchema ss && ss.upperBound().isPresent()
            ? ss.upperBound().get().value()
            : resolveStringOrConstOrNull(
                schemaType,
                psiMeta.containsKey(StvnVocabulary.FACET_KW_MAX_EXCL)
                    ? psiMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL)
                    : psiMeta.get(StvnVocabulary.FACET_KW_MAX_INCL)
            );
        String rawRegex = effectiveIr instanceof StvnSchema.StringSchema ss && ss.rawRegexPattern().isPresent()
            ? ss.rawRegexPattern().get()
            : resolveStringOrConstOrNull(schemaType, psiMeta.get(StvnVocabulary.FACET_KW_REGEX));
        String cleanRegex = rawRegex != null ? unquoteOrNull(rawRegex) : null;

        var profile = StvnStringConstraintUtil.createProfile(
            minSize, maxSize, lowerBound, lowerExcl, upperBound, upperExcl, cleanRegex
        );
        Set<String> results = new LinkedHashSet<>();
        results.add(firstQuoted);
        String baseRaw = StvnPsiLiteralEvaluator.decodeStringLiteral(firstQuoted, false).orElse("example");

        // 1. Try appending numeric suffixes ("example_1", "example_2", ...) when maxSize permits
        for (int i = 1; i <= count * 4 && results.size() < count; i++) {
            String suffixed = baseRaw + "_" + i;
            if (StvnStringConstraintUtil.isValidCandidate(suffixed, profile)) {
                results.add(StvnPsiLiteralEvaluator.formatCanonicalQuotedString(suffixed));
            }
        }

        // 2. Try single-character mutations at each position of baseRaw (preserves fixed-length UUID/ULID/code regexes!)
        char[] alphabet = "0123456789abcdefABCDEFghijklmnopqrstuvwxyzGHIJKLMNOPQRSTUVWXYZ_-".toCharArray();
        for (int pos = baseRaw.length() - 1; pos >= 0 && results.size() < count; pos--) {
            for (char ch : alphabet) {
                if (ch == baseRaw.charAt(pos)) {
                    continue;
                }
                String mutated = baseRaw.substring(0, pos) + ch + baseRaw.substring(pos + 1);
                if (StvnStringConstraintUtil.isValidCandidate(mutated, profile)) {
                    results.add(StvnPsiLiteralEvaluator.formatCanonicalQuotedString(mutated));
                    if (results.size() >= count) {
                        break;
                    }
                }
            }
        }

        // 3. Try short alphabetic/alphanumeric tokens when minSize == 0 or 1 and maxSize == 1..4
        for (int i = 0; i < alphabet.length && results.size() < count; i++) {
            String single = String.valueOf(alphabet[i]);
            if (StvnStringConstraintUtil.isValidCandidate(single, profile)) {
                results.add(StvnPsiLiteralEvaluator.formatCanonicalQuotedString(single));
            }
        }
        return new ArrayList<>(results);
    }

    /**
     * Generates a valid STVN {@code :DateTime} string literal (enclosed in double quotes) for the
     * designated {@link DateTimeMode}, clamped into {@code [#minIncl, #maxExcl)} if bounds are
     * present.
     *
     * @param mode    the target {@link DateTimeMode} ({@code #offset}, {@code #zoned}, or {@code #audited})
     * @param minIncl optional minimum inclusive ISO-8601 bound
     * @param maxExcl optional maximum exclusive ISO-8601 bound
     * @param clock   the clock supplying the current instant and timezone
     * @return the quoted STVN {@code :DateTime} literal
     */
    public static String generateDateTimeExemplar(
        DateTimeMode mode,
        @Nullable String minIncl,
        @Nullable String maxExcl,
        Clock clock
    ) {
        ZoneId zone = resolveExemplarZoneId(clock.getZone());
        Instant targetInstant = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        boolean clamped = false;

        Instant minInstant = tryParseInstantBound(minIncl, zone);
        Instant maxInstant = tryParseInstantBound(maxExcl, zone);
        if (minInstant != null && targetInstant.isBefore(minInstant)) {
            targetInstant = minInstant;
            clamped = true;
        }
        if (maxInstant != null && !targetInstant.isBefore(maxInstant)) {
            Instant candidate = maxInstant.minusSeconds(1);
            if (minInstant != null && candidate.isBefore(minInstant)) {
                long spanNanos = Duration.between(minInstant, maxInstant).toNanos();
                targetInstant = spanNanos > 1L ? minInstant.plusNanos(spanNanos / 2L) : minInstant;
            } else {
                targetInstant = candidate;
            }
            clamped = true;
        }

        ZonedDateTime zdt = ZonedDateTime.ofInstant(
            clamped ? targetInstant : targetInstant.truncatedTo(ChronoUnit.SECONDS),
            zone
        );
        String localIso = StvnPayloadValueQuickFixProvider.formatCanonicalLocalDateTime(zdt.toLocalDateTime());
        return switch (mode) {
            case OFFSET -> {
                OffsetDateTime odt = zdt.toOffsetDateTime();
                String offsetStr = odt.getOffset().getTotalSeconds() == 0 ? "Z" : odt.getOffset().getId();
                String formatted = StvnPayloadValueQuickFixProvider.formatCanonicalLocalDateTime(odt.toLocalDateTime())
                    + offsetStr;
                yield "\"" + formatted + "\"";
            }
            case ZONED -> {
                String formatted = localIso + "[" + zone.getId() + "]";
                yield "\"" + formatted + "\"";
            }
            case AUDITED -> {
                String offsetStr = zdt.getOffset().getTotalSeconds() == 0 ? "Z" : zdt.getOffset().getId();
                String formatted = localIso
                    + offsetStr
                    + "["
                    + zone.getId()
                    + "]";
                yield "\"" + formatted + "\"";
            }
        };
    }

    /**
     * Ensures that the returned {@link ZoneId} is a valid IANA timezone identifier (or {@code UTC})
     * rather than a bare fixed offset such as {@code +00:00} or {@code GMT+02:00}.
     *
     * @param candidate the candidate timezone
     * @return a region-based IANA {@link ZoneId} or {@code UTC}
     */
    public static ZoneId resolveExemplarZoneId(@Nullable ZoneId candidate) {
        if (candidate == null || candidate instanceof ZoneOffset) {
            return ZoneId.of("UTC");
        }
        String id = candidate.getId();
        if (id.equals("Z")
            || id.startsWith("+")
            || id.startsWith("-")
            || id.startsWith("GMT+")
            || id.startsWith("GMT-")
            || id.startsWith("UTC+")
            || id.startsWith("UTC-")
            || id.startsWith("UT+")
            || id.startsWith("UT-")) {
            return ZoneId.of("UTC");
        }
        return candidate;
    }

    /**
     * Generates a valid STVN {@code :TimeEpoch} integer literal in the designated {@link TemporalScale},
     * clamped into {@code [#minIncl, #maxExcl)}.
     *
     * @param scale      the active {@link TemporalScale}
     * @param isUnsigned whether the epoch integer is unsigned
     * @param sizeBits   the bit width of the epoch integer
     * @param minIncl    optional minimum inclusive bound
     * @param maxExcl    optional maximum exclusive bound
     * @param clock      the clock supplying the current instant
     * @return the unquoted STVN integer literal
     */
    public static String generateTimeEpochExemplar(
        TemporalScale scale,
        boolean isUnsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl,
        Clock clock
    ) {
        Instant now = Instant.now(clock);
        BigInteger ticks = instantToEpochTicks(now, scale);
        BigInteger[] bounds = computeEffectiveIntegerBounds(isUnsigned, sizeBits, minIncl, maxExcl);
        BigInteger effMin = bounds[0];
        BigInteger effMaxIncl = bounds[1];
        if (ticks.compareTo(effMin) < 0) {
            ticks = effMin;
        } else if (ticks.compareTo(effMaxIncl) > 0) {
            ticks = effMaxIncl;
        }
        return ticks.toString();
    }

    /**
     * Converts an {@link Instant} to integer ticks in the given {@link TemporalScale}.
     *
     * @param instant the instant to convert
     * @param scale   the target temporal scale
     * @return the epoch tick count
     */
    public static BigInteger instantToEpochTicks(Instant instant, TemporalScale scale) {
        BigInteger seconds = BigInteger.valueOf(instant.getEpochSecond());
        BigInteger nanos = BigInteger.valueOf(instant.getNano());
        return switch (scale) {
            case S -> seconds;
            case MS -> seconds.multiply(BigInteger.valueOf(1_000L)).add(nanos.divide(BigInteger.valueOf(1_000_000L)));
            case US -> seconds.multiply(BigInteger.valueOf(1_000_000L)).add(nanos.divide(BigInteger.valueOf(1_000L)));
            case NS -> seconds.multiply(BigInteger.valueOf(1_000_000_000L)).add(nanos);
        };
    }

    /**
     * Generates a valid STVN {@code :Int} literal respecting {@code #unsigned}, {@code #size},
     * {@code #minIncl}, and {@code #maxExcl}.
     *
     * @param isUnsigned whether the integer is unsigned
     * @param sizeBits   the bit width of the integer
     * @param minIncl    optional minimum inclusive bound
     * @param maxExcl    optional maximum exclusive bound
     * @return a compliant integer literal string
     */
    public static String generateIntExemplar(
        boolean isUnsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl
    ) {
        BigInteger[] bounds = computeEffectiveIntegerBounds(isUnsigned, sizeBits, minIncl, maxExcl);
        BigInteger effMin = bounds[0];
        BigInteger effMaxIncl = bounds[1];

        if (BigInteger.ZERO.compareTo(effMin) >= 0 && BigInteger.ZERO.compareTo(effMaxIncl) <= 0) {
            return "0";
        }
        if (BigInteger.ZERO.compareTo(effMin) < 0) {
            return effMin.toString();
        }
        return effMaxIncl.toString();
    }

    /**
     * Computes the effective inclusive {@code [minIncl, maxIncl]} bounds for an integer or
     * {@code :TimeEpoch} schema by intersecting bit-width capacity with {@code #minIncl} and
     * {@code #maxExcl}.
     *
     * @param isUnsigned whether the integer is unsigned
     * @param sizeBits   the bit width of the integer
     * @param minIncl    optional explicit minimum inclusive bound
     * @param maxExcl    optional explicit maximum exclusive bound
     * @return a two-element array {@code [effectiveMinIncl, effectiveMaxIncl]}
     */
    public static BigInteger[] computeEffectiveIntegerBounds(
        boolean isUnsigned,
        int sizeBits,
        @Nullable BigInteger minIncl,
        @Nullable BigInteger maxExcl
    ) {
        int clampedBits = Math.max(1, Math.min(sizeBits, 128));
        BigInteger physMin = isUnsigned
            ? BigInteger.ZERO
            : BigInteger.ONE.shiftLeft(clampedBits - 1).negate();
        BigInteger physMaxIncl = isUnsigned
            ? BigInteger.ONE.shiftLeft(clampedBits).subtract(BigInteger.ONE)
            : BigInteger.ONE.shiftLeft(clampedBits - 1).subtract(BigInteger.ONE);

        BigInteger effMin = (minIncl != null && minIncl.compareTo(physMin) > 0) ? minIncl : physMin;
        BigInteger effMaxIncl = physMaxIncl;
        if (maxExcl != null) {
            BigInteger explicitMaxIncl = maxExcl.subtract(BigInteger.ONE);
            if (explicitMaxIncl.compareTo(effMaxIncl) < 0) {
                effMaxIncl = explicitMaxIncl;
            }
        }
        return new BigInteger[] {effMin, effMaxIncl};
    }

    /**
     * Generates a valid STVN {@code :Float} literal respecting inclusive and exclusive bounds.
     *
     * @param minIncl optional minimum inclusive bound
     * @param minExcl optional minimum exclusive bound
     * @param maxIncl optional maximum inclusive bound
     * @param maxExcl optional maximum exclusive bound
     * @return a compliant floating-point literal string
     */
    public static String generateFloatExemplar(
        @Nullable Double minIncl,
        @Nullable Double minExcl,
        @Nullable Double maxIncl,
        @Nullable Double maxExcl
    ) {
        double lower = -Double.MAX_VALUE;
        boolean lowerInclusive = true;
        if (minIncl != null) {
            lower = minIncl;
            lowerInclusive = true;
        }
        if (minExcl != null && (minIncl == null || minExcl >= minIncl)) {
            lower = minExcl;
            lowerInclusive = false;
        }

        double upper = Double.MAX_VALUE;
        boolean upperInclusive = true;
        if (maxIncl != null) {
            upper = maxIncl;
            upperInclusive = true;
        }
        if (maxExcl != null && (maxIncl == null || maxExcl <= maxIncl)) {
            upper = maxExcl;
            upperInclusive = false;
        }

        boolean zeroValid = (lowerInclusive ? 0.0 >= lower : 0.0 > lower)
            && (upperInclusive ? 0.0 <= upper : 0.0 < upper);
        if (zeroValid) {
            return "0.0";
        }

        if (0.0 <= lower) {
            double candidate = lowerInclusive ? lower : lower + 1.0;
            if (upper < Double.MAX_VALUE && (upperInclusive ? candidate > upper : candidate >= upper)) {
                candidate = (lower + upper) / 2.0;
            }
            return formatFloatLiteral(candidate);
        } else {
            double candidate = upperInclusive ? upper : upper - 1.0;
            if (lower > -Double.MAX_VALUE && (lowerInclusive ? candidate < lower : candidate <= lower)) {
                candidate = (lower + upper) / 2.0;
            }
            return formatFloatLiteral(candidate);
        }
    }

    private static String formatFloatLiteral(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.format(java.util.Locale.ROOT, "%.1f", value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Generates a valid STVN {@code :String} literal respecting UTF-8 byte size and lexicographical
     * bounds.
     *
     * @param minSize        optional minimum UTF-8 byte size
     * @param maxSize        optional maximum UTF-8 byte size
     * @param minIncl        optional minimum inclusive lexicographical bound
     * @param maxExcl        optional maximum exclusive lexicographical bound
     * @param preserveIndent whether {@code #preserveIndent} is active
     * @return a compliant quoted string literal
     */
    /**
     * Generates a valid STVN {@code :String} literal respecting Unicode code-point length and
     * lexicographical bounds.
     *
     * @param minSize        optional minimum code-point size
     * @param maxSize        optional maximum code-point size
     * @param minIncl        optional minimum inclusive lexicographical bound
     * @param maxExcl        optional maximum exclusive lexicographical bound
     * @param preserveIndent whether {@code #preserveIndent} is active
     * @return a compliant quoted string literal, or {@code null} if uninhabited
     */
    public static @Nullable String generateStringExemplar(
        @Nullable Long minSize,
        @Nullable Long maxSize,
        @Nullable String minIncl,
        @Nullable String maxExcl,
        boolean preserveIndent
    ) {
        return generateStringExemplar(minSize, maxSize, minIncl, maxExcl, null, preserveIndent);
    }

    /**
     * Generates a valid STVN {@code :String} literal respecting Unicode code-point length, lexicographical
     * bounds, and an optional regular expression pattern.
     *
     * @param minSize        optional minimum code-point size
     * @param maxSize        optional maximum code-point size
     * @param minIncl        optional minimum inclusive lexicographical bound
     * @param maxExcl        optional maximum exclusive lexicographical bound
     * @param regexPattern   optional unquoted regular expression pattern
     * @param preserveIndent whether {@code #preserveIndent} is active
     * @return a compliant quoted string literal, or {@code null} if uninhabited
     */
    public static @Nullable String generateStringExemplar(
        @Nullable Long minSize,
        @Nullable Long maxSize,
        @Nullable String minIncl,
        @Nullable String maxExcl,
        @Nullable String regexPattern,
        boolean preserveIndent
    ) {
        return generateStringExemplar(
            minSize,
            maxSize,
            minIncl,
            false,
            maxExcl,
            maxExcl != null,
            regexPattern,
            preserveIndent
        );
    }

    /**
     * Generates a verified STVN {@code :String} exemplar literal respecting Unicode code-point bounds,
     * inclusive/exclusive UTF-8 lexicographical bounds, and compiled regular expressions.
     *
     * @param minSize        optional minimum code-point count
     * @param maxSize        optional maximum code-point count
     * @param lowerBound     optional unquoted lower bound
     * @param lowerExclusive {@code true} for {@code #minExcl}, {@code false} for {@code #minIncl}
     * @param upperBound     optional unquoted upper bound
     * @param upperExclusive {@code true} for {@code #maxExcl}, {@code false} for {@code #maxIncl}
     * @param regexPattern   optional unquoted regular expression
     * @param preserveIndent whether {@code #preserveIndent #TRUE} is active
     * @return verified canonically quoted STVN string literal, or {@code null} if uninhabited
     */
    public static @Nullable String generateStringExemplar(
        @Nullable Long minSize,
        @Nullable Long maxSize,
        @Nullable String lowerBound,
        boolean lowerExclusive,
        @Nullable String upperBound,
        boolean upperExclusive,
        @Nullable String regexPattern,
        boolean preserveIndent
    ) {
        String cleanRegex = regexPattern != null ? unquoteOrNull(regexPattern) : null;
        var profile = StvnStringConstraintUtil.createProfile(
            minSize,
            maxSize,
            lowerBound,
            lowerExclusive,
            upperBound,
            upperExclusive,
            cleanRegex
        );
        String inhabitant = StvnStringConstraintUtil.synthesizeExemplarInhabitant(profile);
        return inhabitant != null ? StvnPsiLiteralEvaluator.formatCanonicalQuotedString(inhabitant) : null;
    }

    /**
     * Synthesizes an unquoted string conforming to {@code regexPattern} and {@code [minSize, maxSize]}
     * when the pattern matches a known Standard Library Prelude or anchored structural regex.
     *
     * @param regexPattern unquoted regular expression pattern
     * @param minSize      minimum Unicode code-point length
     * @param maxSize      maximum Unicode code-point length
     * @return conforming unquoted string, or {@code null} if no deterministic synthesis matches
     */
    public static @Nullable String synthesizeRegexConformingString(
        String regexPattern,
        long minSize,
        long maxSize
    ) {
        String clean = unquoteOrNull(regexPattern);
        if (clean == null || clean.isBlank()) {
            return null;
        }
        var profile = StvnStringConstraintUtil.createProfile(minSize, maxSize, null, false, null, false, clean);
        return StvnStringConstraintUtil.synthesizeExemplarInhabitant(profile);
    }

    private static @Nullable String generateEnumSubsetExemplarFromIr(
        StvnSchema.EnumSubsetSchema subsetSchema,
        Map<String, String> psiMeta
    ) {
        Set<String> active = new LinkedHashSet<>(subsetSchema.activeVariants());
        applyEnumFiltersFromMeta(active, psiMeta);
        if (!active.isEmpty()) {
            return ensureHashPrefix(active.iterator().next());
        }
        return null;
    }

    private static @Nullable String generateEnumExemplarFromIr(
        StvnSchema.EnumSchema enumSchema,
        Map<String, String> psiMeta
    ) {
        Set<String> active = new LinkedHashSet<>(enumSchema.rootVariants());
        applyEnumFiltersFromMeta(active, psiMeta);
        if (!active.isEmpty()) {
            return ensureHashPrefix(active.iterator().next());
        }
        return null;
    }

    private static @Nullable String generateEnumExemplarFromPsi(SumType sumType, Map<String, String> psiMeta) {
        List<String> variants = new ArrayList<>();
        if (sumType.getEnumDef() != null) {
            for (ValueKeyword kw : sumType.getEnumDef().getValueKeywordList()) {
                String text = kw.getText().trim();
                if (!text.isEmpty()) {
                    variants.add(ensureHashPrefix(text));
                }
            }
        }
        if (variants.isEmpty()) {
            return null;
        }
        Set<String> active = new LinkedHashSet<>(variants);
        applyEnumFiltersFromMeta(active, psiMeta);
        if (!active.isEmpty()) {
            return ensureHashPrefix(active.iterator().next());
        }
        return null;
    }

    private static void applyEnumFiltersFromMeta(Set<String> active, Map<String, String> psiMeta) {
        List<String> filterIncl = parseEnumVariantList(psiMeta.get(StvnVocabulary.FACET_KW_FILTER_INCL));
        List<String> filterExcl = parseEnumVariantList(psiMeta.get(StvnVocabulary.FACET_KW_FILTER_EXCL));
        if (!filterIncl.isEmpty()) {
            active.retainAll(filterIncl);
        }
        if (!filterExcl.isEmpty()) {
            active.removeAll(filterExcl);
        }
    }

    private static String ensureHashPrefix(String variant) {
        String trimmed = variant.trim();
        if (trimmed.startsWith(StvnVocabulary.SIGIL_VALUE)) {
            return trimmed;
        }
        if (trimmed.startsWith("$")) {
            return StvnVocabulary.SIGIL_VALUE + trimmed.substring(1);
        }
        return StvnVocabulary.SIGIL_VALUE + trimmed;
    }

    private static List<String> parseEnumVariantList(@Nullable String rawList) {
        if (rawList == null || rawList.isBlank()) {
            return List.of();
        }
        String stripped = rawList.trim();
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1).trim();
        }
        if (stripped.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : stripped.split("[\\s,]+")) {
            if (!token.isEmpty()) {
                result.add(ensureHashPrefix(token));
            }
        }
        return result;
    }

    /**
     * Resolves the active {@link DateTimeMode} from extracted PSI metadata entries.
     *
     * @param meta the extracted metadata map
     * @return the resolved {@link DateTimeMode} (defaults to {@link DateTimeMode#OFFSET})
     */
    public static DateTimeMode resolveDateTimeModeFromMeta(Map<String, String> meta) {
        if (meta.containsKey(StvnVocabulary.FACET_KW_ZONED)) {
            return DateTimeMode.ZONED;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_AUDITED)) {
            return DateTimeMode.AUDITED;
        }
        return DateTimeMode.OFFSET;
    }

    /**
     * Resolves the active {@link TemporalScale} from extracted PSI metadata entries.
     *
     * @param meta the extracted metadata map
     * @return the resolved {@link TemporalScale} (defaults to {@link TemporalScale#MS})
     */
    public static TemporalScale resolveEpochScaleFromMeta(Map<String, String> meta) {
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_S)) {
            return TemporalScale.S;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_US)) {
            return TemporalScale.US;
        }
        if (meta.containsKey(StvnVocabulary.FACET_KW_SCALE_NS)) {
            return TemporalScale.NS;
        }
        return TemporalScale.MS;
    }

    private static boolean hasExplicitScaleInMeta(Map<String, String> meta) {
        return meta.containsKey(StvnVocabulary.FACET_KW_SCALE_S)
            || meta.containsKey(StvnVocabulary.FACET_KW_SCALE_MS)
            || meta.containsKey(StvnVocabulary.FACET_KW_SCALE_US)
            || meta.containsKey(StvnVocabulary.FACET_KW_SCALE_NS);
    }

    /**
     * Extracts all metadata facets across the nominal alias lineage of {@code schemaType},
     * with closer (child) facets overriding ancestor facets.
     *
     * @param schemaType the starting PSI schema type
     * @return a map of facet keyword to facet argument string (or {@code "#TRUE"} for bare flags)
     */
    public static Map<String, String> extractLineageMetadata(SchemaType schemaType) {
        List<MetadataMap> mapsHighestToLowest = new ArrayList<>();
        Set<PsiElement> visited = new HashSet<>();
        SchemaType curr = schemaType;
        while (curr != null && visited.add(curr)) {
            if (curr.getMetadataMap() != null) {
                mapsHighestToLowest.add(curr.getMetadataMap());
            }
            TypeDefinition parentDef = PsiTreeUtil.getParentOfType(curr, TypeDefinition.class);
            if (parentDef != null && parentDef.getSchemaType() == curr && parentDef.getMetadataMap() != null) {
                mapsHighestToLowest.add(parentDef.getMetadataMap());
            }
            var kw = curr.getTypeKeyword();
            if (kw == null) {
                break;
            }
            String alias = kw.getText().trim();
            var td = StvnTypeReference.resolveTypeDefinitionAtContext(kw, alias);
            if (td == null) {
                break;
            }
            if (td.getMetadataMap() != null) {
                mapsHighestToLowest.add(td.getMetadataMap());
            }
            curr = td.getSchemaType();
        }

        Map<String, String> merged = new LinkedHashMap<>();
        for (int i = mapsHighestToLowest.size() - 1; i >= 0; i--) {
            extractMetadataMapInto(mapsHighestToLowest.get(i), merged);
        }
        return merged;
    }

    private static void extractMetadataMapInto(MetadataMap metaMap, Map<String, String> target) {
        for (var entry : metaMap.getMetadataEntryList()) {
            String text = entry.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            String[] parts = text.split("\\s+", 2);
            String key = parts[0];
            String val = parts.length > 1 ? parts[1].trim() : StvnVocabulary.VAL_TRUE;
            if (StvnVocabulary.FACET_KW_MIN_INCL.equals(key) || StvnVocabulary.FACET_KW_MIN_EXCL.equals(key)) {
                target.remove(StvnVocabulary.FACET_KW_MIN_INCL);
                target.remove(StvnVocabulary.FACET_KW_MIN_EXCL);
            } else if (StvnVocabulary.FACET_KW_MAX_INCL.equals(key) || StvnVocabulary.FACET_KW_MAX_EXCL.equals(key)) {
                target.remove(StvnVocabulary.FACET_KW_MAX_INCL);
                target.remove(StvnVocabulary.FACET_KW_MAX_EXCL);
            }
            target.put(key, val);
        }
    }

    /**
     * Resolves nominal type references on {@code schemaType} to the terminal structural or atomic
     * {@link SchemaType}.
     *
     * @param schemaType the schema type to unwrap
     * @return the terminal {@link SchemaType}
     */
    public static SchemaType resolveTerminalSchemaType(SchemaType schemaType) {
        Set<SchemaType> visited = new HashSet<>();
        SchemaType curr = schemaType;
        while (curr != null && curr.getSchemaConstructor() == null && curr.getTypeKeyword() != null) {
            if (!visited.add(curr)) {
                break;
            }
            SchemaType next = StvnTypeResolver.resolveNominalSchema(curr);
            if (next == null || next == curr) {
                var kw = curr.getTypeKeyword();
                if (kw != null && !curr.getProject().isDisposed()) {
                    var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(
                        curr.getProject(),
                        kw.getText().trim()
                    );
                    if (preludeDef != null && preludeDef.getSchemaType() != null) {
                        next = preludeDef.getSchemaType();
                    }
                }
            }
            if (next == null || next == curr) {
                break;
            }
            curr = next;
        }
        return curr != null ? curr : schemaType;
    }

    private static @Nullable SchemaType resolveTerminalSchemaTypeWithVisited(
        SchemaType schemaType,
        Set<String> ancestorIrAliases,
        Set<PsiElement> ancestorPsiElements,
        Set<String> nextIrAliases,
        Set<PsiElement> nextPsiElements
    ) {
        Set<PsiElement> chainVisited = new HashSet<>();
        if (schemaType.getParent() instanceof TypeDefinition startTd && startTd.getSchemaType() == schemaType) {
            chainVisited.add(startTd);
        }
        SchemaType curr = schemaType;
        while (curr != null && curr.getSchemaConstructor() == null && curr.getTypeKeyword() != null) {
            if (!chainVisited.add(curr)) {
                return null;
            }
            if (curr != schemaType && ancestorPsiElements.contains(curr)) {
                return null;
            }
            String alias = curr.getTypeKeyword().getText().trim();
            if (curr != schemaType && ancestorIrAliases.contains(alias)) {
                return null;
            }
            if (curr.getContainingFile() != null) {
                PsiElement stepCtx = curr.getTypeKeyword() != null ? curr.getTypeKeyword() : curr;
                StvnSchema stepIr = StvnTypeResolver.resolveIrSchemaAtContext(stepCtx, alias);
                if (isPoisonedIrSchema(stepIr, stepCtx)) {
                    return null;
                }
            }
            nextIrAliases.add(alias);
            nextPsiElements.add(curr);
            SchemaType next = StvnTypeResolver.resolveNominalSchema(curr);
            if (next == null || next == curr) {
                var kw = curr.getTypeKeyword();
                if (kw != null && !curr.getProject().isDisposed()) {
                    var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(
                        curr.getProject(),
                        kw.getText().trim()
                    );
                    if (preludeDef != null && preludeDef.getSchemaType() != null) {
                        next = preludeDef.getSchemaType();
                    }
                }
            }
            if (next == curr) {
                return null;
            }
            if (next == null) {
                break;
            }
            if (next.getParent() instanceof TypeDefinition td) {
                if (!chainVisited.add(td) || ancestorPsiElements.contains(td)) {
                    return null;
                }
                nextPsiElements.add(td);
            }
            curr = next;
        }
        if (curr != null) {
            if (curr != schemaType && ancestorPsiElements.contains(curr)) {
                return null;
            }
            nextPsiElements.add(curr);
            return curr;
        }
        return null;
    }

    private static @Nullable Instant tryParseInstantBound(@Nullable String rawBound, ZoneId fallbackZone) {
        if (rawBound == null || rawBound.isBlank()) {
            return null;
        }
        String clean = unquoteOrNull(rawBound);
        if (clean == null || clean.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(clean, DateTimeFormatter.ISO_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(clean, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            int bracketIdx = clean.indexOf('[');
            if (bracketIdx > 0 && clean.endsWith("]")) {
                String localPart = clean.substring(0, bracketIdx);
                String zonePart = clean.substring(bracketIdx + 1, clean.length() - 1);
                LocalDateTime ldt = LocalDateTime.parse(localPart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(ZoneId.of(zonePart)).toInstant();
            }
            LocalDateTime ldt = LocalDateTime.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.atZone(fallbackZone).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int parseIntOrDefault(@Nullable PsiElement context, @Nullable String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(context, raw)
            .map(BigInteger::intValue)
            .orElse(defaultValue);
    }

    private static long parseLongOrDefault(@Nullable PsiElement context, @Nullable String raw, long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(context, raw)
            .map(BigInteger::longValue)
            .orElse(defaultValue);
    }

    private static @Nullable BigInteger parseBigIntOrNull(@Nullable PsiElement context, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(context, raw).orElse(null);
    }

    private static @Nullable Long parseLongOrNull(@Nullable PsiElement context, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(context, raw)
            .map(BigInteger::longValue)
            .orElse(null);
    }

    private static @Nullable Double parseDoubleOrNull(@Nullable PsiElement context, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(context, raw)
            .map(BigDecimal::doubleValue)
            .orElse(null);
    }

    private static boolean resolvePsiPreserveIndentFlag(@Nullable PsiElement context, Map<String, String> psiMeta) {
        if (!psiMeta.containsKey(StvnVocabulary.FACET_KW_PRESERVE_INDENT)) {
            return false;
        }
        String rawArg = psiMeta.get(StvnVocabulary.FACET_KW_PRESERVE_INDENT);
        if (rawArg == null || rawArg.isBlank()) {
            return true;
        }
        return StvnPsiLiteralEvaluator.resolveBooleanOrConst(context, rawArg).orElse(false);
    }

    private static @Nullable String resolveStringOrConstOrNull(@Nullable PsiElement context, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveStringOrConst(context, raw, false).orElse(null);
    }

    private static @Nullable String unquoteOrNull(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"\"\"") || (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return StvnPsiLiteralEvaluator.decodeStringLiteral(trimmed, false).orElse(null);
        }
        return trimmed;
    }
}
