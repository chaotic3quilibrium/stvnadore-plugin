package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.MetadataValue;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.Visitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates schema metadata blocks across {@code :defs}, {@code :type}, and inline constructors
 * to detect redundant metadata facets (Rule W003 / {@code WARN_REDUNDANT_METADATA_FACET}) with 1:1
 * coordinate and Simplified Technical English (STE) parity with {@code stvnadore-core}.
 */
@NullMarked
public final class StvnRedundantFacetOverrideInspection extends LocalInspectionTool {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    /** Constructs a new {@code StvnRedundantFacetOverrideInspection}. */
    public StvnRedundantFacetOverrideInspection() {}

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitMetadataMap(@NotNull MetadataMap metaMap) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaMap)) {
                    return;
                }
                var entries = metaMap.getMetadataEntryList();
                if (entries.isEmpty()) {
                    return;
                }
                var ownerCtx = resolveOwnerContext(metaMap);
                if (ownerCtx == null) {
                    return;
                }
                for (var entry : entries) {
                    if (hasOverlappingFatalDiagnostic(entry)) {
                        continue;
                    }
                    checkRedundantEntry(entry, metaMap, ownerCtx, holder);
                }
            }
        };
    }

    private record OwnerContext(
        String ownerLabel,
        SchemaType schemaType,
        @Nullable TypeDefinition enclosingTypeDef,
        @Nullable String parentAlias,
        @Nullable StvnSchema irSchema,
        @Nullable StvnSchema parentIr,
        String ultimateBase
    ) {}

    private static @Nullable OwnerContext resolveOwnerContext(MetadataMap metaMap) {
        var parent = metaMap.getParent();
        SchemaType schemaType = null;
        TypeDefinition enclosingTypeDef = null;
        String ownerLabel;

        if (parent instanceof TypeDefinition td) {
            enclosingTypeDef = td;
            schemaType = td.getSchemaType();
            ownerLabel = td.getTypeKeyword() != null ? td.getTypeKeyword().getText().trim() : StvnVocabulary.KEYWORD_TYPE;
        } else if (parent instanceof ConstantDefinition cd) {
            schemaType = cd.getSchemaType();
            ownerLabel = (schemaType != null && schemaType.getTypeKeyword() != null)
                ? schemaType.getTypeKeyword().getText().trim()
                : (cd.getValueKeyword() != null ? cd.getValueKeyword().getText().trim() : StvnVocabulary.KEYWORD_DEFS);
        } else if (parent instanceof TypeEntry te) {
            schemaType = te.getSchemaType();
            ownerLabel = StvnVocabulary.KEYWORD_TYPE;
        } else if (parent instanceof SchemaType st) {
            schemaType = st;
            var td = PsiTreeUtil.getParentOfType(st, TypeDefinition.class);
            if (td != null && td.getSchemaType() == st && td.getMetadataMap() == null) {
                enclosingTypeDef = td;
                ownerLabel = td.getTypeKeyword() != null ? td.getTypeKeyword().getText().trim() : formatInlineOwnerLabel(st);
            } else if (st.getParent() instanceof TypeEntry) {
                ownerLabel = StvnVocabulary.KEYWORD_TYPE;
            } else {
                ownerLabel = formatInlineOwnerLabel(st);
            }
        } else {
            return null;
        }

        if (schemaType == null) {
            return null;
        }

        StvnSchema rawIr = enclosingTypeDef != null
            ? StvnTypeResolver.resolveNominalIrSchema(enclosingTypeDef)
            : StvnTypeResolver.resolveIrSchema(schemaType);
        StvnSchema irSchema = rawIr instanceof StvnSchema.PoisonedSchema p ? p.bestEffortFallback().orElse(null) : rawIr;

        String parentAlias = null;
        StvnSchema parentIr = null;
        var targetKw = schemaType.getTypeKeyword();
        if (targetKw != null) {
            String candidate = targetKw.getText().trim();
            if (!StvnVocabulary.isBaseScalarType(candidate)
                    && !StvnVocabulary.COMPOSITE_CONSTRUCTOR_TYPES.contains(candidate)) {
                parentAlias = candidate;
                var resolvedParentElem = StvnTypeReference.resolveTypeInFile(
                    metaMap.getContainingFile(),
                    candidate,
                    new HashSet<>()
                );
                var parentTypeDef = StvnPsiUtils.getParentTypeDefinition(resolvedParentElem);
                if (parentTypeDef != null && parentTypeDef != enclosingTypeDef) {
                    parentIr = StvnTypeResolver.resolveNominalIrSchema(parentTypeDef);
                }
                if (parentIr == null) {
                    parentIr = StvnTypeResolver.resolveIrSchemaByAlias(metaMap.getContainingFile(), candidate);
                }
                if (parentIr instanceof StvnSchema.PoisonedSchema poisonedParent) {
                    parentIr = poisonedParent.bestEffortFallback().orElse(null);
                }
                if (parentIr == null) {
                    return null;
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
            ultimateBase = extractConstructorHead(StvnSchemaFormatter.formatCleanSchema(resolvedPsi != null ? resolvedPsi : schemaType));
        }

        return new OwnerContext(ownerLabel, schemaType, enclosingTypeDef, parentAlias, irSchema, parentIr, ultimateBase);
    }

    private static String formatInlineOwnerLabel(SchemaType st) {
        if (st.getTypeKeyword() != null) {
            return st.getTypeKeyword().getText().trim();
        }
        return extractConstructorHead(StvnSchemaFormatter.formatCleanSchema(st));
    }

    private static String extractConstructorHead(String cleanSchema) {
        String trimmed = cleanSchema.trim();
        for (String base : List.of(
                StvnVocabulary.TYPE_TUPLE, StvnVocabulary.TYPE_SEQ, StvnVocabulary.TYPE_SET, StvnVocabulary.TYPE_MAP,
                StvnVocabulary.TYPE_OPTION, StvnVocabulary.TYPE_EITHER, StvnVocabulary.TYPE_UNION, StvnVocabulary.TYPE_ENUM)) {
            if (trimmed.startsWith(base)) {
                return base;
            }
        }
        return trimmed;
    }

    private static boolean hasOverlappingFatalDiagnostic(MetadataEntry entry) {
        var file = entry.getContainingFile();
        var range = entry.getTextRange();
        if (file == null || range == null) {
            return false;
        }
        for (var diag : StvnCompiledDocumentCache.getSnapshot(file).diagnostics()) {
            if (diag.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR
                    && diag.startOffset() < range.getEndOffset()
                    && diag.endOffset() > range.getStartOffset()) {
                return true;
            }
        }
        return false;
    }

    private static void checkRedundantEntry(
        MetadataEntry entry,
        MetadataMap metaMap,
        OwnerContext ctx,
        ProblemsHolder holder
    ) {
        // 1. Prefer authoritative pure-IR compiler W003 message when available at exact MetadataEntry coordinates
        var file = entry.getContainingFile();
        var range = entry.getTextRange();
        if (file != null && range != null) {
            for (var diag : StvnCompiledDocumentCache.getSnapshot(file).diagnostics()) {
                if (diag.severity() == StvnDiagnostic.DiagnosticSeverity.WARNING
                        && DiagnosticBag.WARN_REDUNDANT_METADATA_FACET.equals(diag.symbolicId().orElse(""))
                        && diag.startOffset() == range.getStartOffset()
                        && diag.endOffset() == range.getEndOffset()) {
                    registerCoreAlignedW003(entry, diag.message(), holder);
                    return;
                }
            }
        }

        // 2. Pure-PSI fallback when unrelated Pass 1 syntax errors prevent full Pass 2 compilation
        if (entry.getMetadataBareFlag() != null) {
            return;
        }
        String normalizedFacet = entry.getText().trim().replaceAll("\\s+", " ");
        var parts = normalizedFacet.split(" ", 2);
        if (parts.length < 2) {
            return;
        }
        String tag = parts[0];
        String rawArg = parts[1].trim();

        // 2a. #preserveIndent on :String (root default #FALSE or nominal override)
        if (StvnVocabulary.FACET_KW_PRESERVE_INDENT.equals(tag) && StvnVocabulary.TYPE_STRING.equals(ctx.ultimateBase())) {
            var boolOpt = resolveBooleanFacetArg(entry, rawArg);
            if (boolOpt.isPresent()) {
                boolean declared = boolOpt.get();
                String canonicalFacet = StvnVocabulary.FACET_KW_PRESERVE_INDENT + " "
                    + (declared ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE);
                if (ctx.parentIr() instanceof StvnSchema.StringSchema ps && ctx.parentAlias() != null) {
                    if (declared == ps.preserveIndent()) {
                        registerCoreAlignedW003(
                            entry,
                            "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet
                                + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + canonicalFacet + "'",
                            holder
                        );
                    }
                } else if (ctx.parentAlias() == null && !declared) {
                    registerCoreAlignedW003(
                        entry,
                        "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet
                            + "' is redundant; fenced strings trim common leading indentation by default",
                        holder
                    );
                }
            }
            return;
        }

        // 2b. #size default tautologies on root :Int (32) and root continuous :Float (64)
        if (StvnVocabulary.FACET_KW_SIZE.equals(tag) && ctx.parentAlias() == null) {
            var sizeOpt = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, rawArg);
            if (sizeOpt.isPresent()) {
                BigInteger val = sizeOpt.get();
                if (StvnVocabulary.TYPE_INT.equals(ctx.ultimateBase()) && val.equals(StvnVocabulary.DEFAULT_INT_BIT_WIDTH)) {
                    String desc = hasLocalUnsignedFacet(metaMap)
                        ? "unsigned integer '{ " + StvnVocabulary.FACET_KW_UNSIGNED + " } " + StvnVocabulary.TYPE_INT + "'"
                        : "signed integer '" + StvnVocabulary.TYPE_INT + "'";
                    registerCoreAlignedW003(
                        entry,
                        "Constraint violation (" + ctx.ownerLabel() + "): facet '"
                            + StvnVocabulary.FACET_KW_SIZE + " 32' is redundant; " + desc + " intrinsically defaults to 32-bit storage",
                        holder
                    );
                } else if (StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase())
                        && !hasLocalExactFacet(metaMap)
                        && val.equals(StvnVocabulary.DEFAULT_FLOAT_BIT_WIDTH)) {
                    registerCoreAlignedW003(
                        entry,
                        "Constraint violation (" + ctx.ownerLabel() + "): facet '"
                            + StvnVocabulary.FACET_KW_SIZE + " 64' is redundant; continuous '"
                            + StvnVocabulary.TYPE_FLOAT + "' intrinsically defaults to 64-bit IEEE-754 double precision",
                        holder
                    );
                }
            }
            return;
        }

        // 2c. #minSize 0 and #maxSize 16777216 on root types
        if (StvnVocabulary.FACET_KW_MIN_SIZE.equals(tag) && ctx.parentAlias() == null) {
            var minOpt = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, rawArg);
            if (minOpt.isPresent() && minOpt.get().signum() == 0) {
                if (StvnVocabulary.TYPE_STRING.equals(ctx.ultimateBase())) {
                    registerCoreAlignedW003(
                        entry,
                        "Constraint violation (" + ctx.ownerLabel() + "): facet '"
                            + StvnVocabulary.FACET_KW_MIN_SIZE + " 0' is redundant; string cardinality is inherently non-negative",
                        holder
                    );
                } else if (StvnVocabulary.TYPE_SEQ.equals(ctx.ultimateBase())
                        || StvnVocabulary.TYPE_SET.equals(ctx.ultimateBase())
                        || StvnVocabulary.TYPE_MAP.equals(ctx.ultimateBase())) {
                    registerCoreAlignedW003(
                        entry,
                        "Constraint violation (" + ctx.ownerLabel() + "): facet '"
                            + StvnVocabulary.FACET_KW_MIN_SIZE + " 0' is redundant; container cardinality is inherently non-negative",
                        holder
                    );
                }
            }
            return;
        }
        if (StvnVocabulary.FACET_KW_MAX_SIZE.equals(tag) && ctx.parentAlias() == null) {
            var maxOpt = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, rawArg);
            if (maxOpt.isPresent() && maxOpt.get().equals(StvnVocabulary.DEFAULT_MAX_SIZE)) {
                if (StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase()) && hasLocalExactFacet(metaMap)) {
                    registerCoreAlignedW003(
                        entry,
                        "Constraint violation (" + ctx.ownerLabel() + "): facet '"
                            + StvnVocabulary.FACET_KW_MAX_SIZE + " 16777216' is redundant; exact decimal float '{ "
                            + StvnVocabulary.FACET_KW_EXACT + " } " + StvnVocabulary.TYPE_FLOAT
                            + "' intrinsically defaults to a ceiling of 16,777,216 significant decimal digits",
                        holder
                    );
                } else if (StvnVocabulary.TYPE_STRING.equals(ctx.ultimateBase())
                        || StvnVocabulary.TYPE_SEQ.equals(ctx.ultimateBase())
                        || StvnVocabulary.TYPE_SET.equals(ctx.ultimateBase())
                        || StvnVocabulary.TYPE_MAP.equals(ctx.ultimateBase())) {
                    registerCoreAlignedW003(
                        entry,
                        "Constraint violation (" + ctx.ownerLabel() + "): facet '"
                            + StvnVocabulary.FACET_KW_MAX_SIZE + " 16777216' is redundant; '"
                            + ctx.ultimateBase() + "' intrinsically defaults to a maximum cardinality of 16,777,216",
                        holder
                    );
                }
            }
            return;
        }

        // 2d. :Int natural and nominal-parent bounds (#minIncl / #maxExcl)
        if ((StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MAX_EXCL.equals(tag))
                && StvnVocabulary.TYPE_INT.equals(ctx.ultimateBase())) {
            checkIntBoundFallback(entry, metaMap, ctx, tag, rawArg, holder);
            return;
        }

        // 2e. Nominal-parent bounds & patterns on :Float, :String, :TimeEpoch, :DateTime, and :EnumSubset #filterIncl
        if (ctx.parentAlias() != null && ctx.parentIr() != null) {
            if (StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase())
                    && ctx.parentIr() instanceof StvnSchema.FloatSchema pf
                    && (StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MIN_EXCL.equals(tag)
                        || StvnVocabulary.FACET_KW_MAX_INCL.equals(tag) || StvnVocabulary.FACET_KW_MAX_EXCL.equals(tag))) {
                checkNominalFloatBoundFallback(entry, ctx, pf, tag, rawArg, holder);
                return;
            }
            if (StvnVocabulary.TYPE_STRING.equals(ctx.ultimateBase())
                    && ctx.parentIr() instanceof StvnSchema.StringSchema ps
                    && (StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MIN_EXCL.equals(tag)
                        || StvnVocabulary.FACET_KW_MAX_INCL.equals(tag) || StvnVocabulary.FACET_KW_MAX_EXCL.equals(tag)
                        || StvnVocabulary.FACET_KW_REGEX.equals(tag))) {
                checkNominalStringFallback(entry, ctx, ps, tag, rawArg, holder);
                return;
            }
            if (StvnVocabulary.TYPE_TIME_EPOCH.equals(ctx.ultimateBase())
                    && ctx.parentIr() instanceof StvnSchema.TimeEpochSchema pt
                    && (StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MAX_EXCL.equals(tag))) {
                checkNominalTimeEpochBoundFallback(entry, ctx, pt, tag, rawArg, holder);
                return;
            }
            if (StvnVocabulary.TYPE_DATE_TIME.equals(ctx.ultimateBase())
                    && ctx.parentIr() instanceof StvnSchema.DateTimeSchema pd
                    && (StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MAX_EXCL.equals(tag))) {
                checkNominalDateTimeBoundFallback(entry, ctx, pd, tag, rawArg, holder);
                return;
            }
            if (StvnVocabulary.FACET_KW_FILTER_INCL.equals(tag)
                    && (ctx.parentIr() instanceof StvnSchema.EnumSchema || ctx.parentIr() instanceof StvnSchema.EnumSubsetSchema)) {
                checkNominalEnumFilterInclFallback(entry, ctx, holder);
                return;
            }
        }

        // 2f. #equatable and #comparable intrinsic and nominal-parent traits
        if (StvnVocabulary.FACET_KW_EQUATABLE.equals(tag) || StvnVocabulary.FACET_KW_COMPARABLE.equals(tag)) {
            checkTraitFallback(entry, metaMap, ctx, tag, rawArg, holder);
        }
    }

    private static void checkIntBoundFallback(
        MetadataEntry entry,
        MetadataMap metaMap,
        OwnerContext ctx,
        String tag,
        String rawArg,
        ProblemsHolder holder
    ) {
        var valOpt = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, rawArg);
        if (valOpt.isEmpty()) {
            return;
        }
        BigInteger val = valOpt.get();
        boolean localUnsigned = hasLocalUnsignedFacet(metaMap);
        boolean parentUnsigned = ctx.parentIr() instanceof StvnSchema.IntSchema pi && pi.unsigned();
        boolean effectiveUnsigned = localUnsigned || parentUnsigned;
        BigInteger effectiveBitWidth = resolveEffectiveIntBitWidth(entry, metaMap, ctx.parentIr());

        if (StvnVocabulary.FACET_KW_MIN_INCL.equals(tag)) {
            if (effectiveUnsigned && val.signum() == 0) {
                boolean ancestorEstablishesUnsigned = ctx.parentAlias() != null && parentUnsigned && !localUnsigned;
                String msg = ancestorEstablishesUnsigned
                    ? "Constraint violation (" + ctx.ownerLabel() + "): facet '" + StvnVocabulary.FACET_KW_MIN_INCL
                        + " 0' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '"
                        + StvnVocabulary.FACET_KW_UNSIGNED + "'"
                    : "Constraint violation (" + ctx.ownerLabel() + "): facet '" + StvnVocabulary.FACET_KW_MIN_INCL
                        + " 0' is redundant; '" + StvnVocabulary.FACET_KW_UNSIGNED + "' already bounds the domain to [0, \\infty)";
                registerCoreAlignedW003(entry, msg, holder);
            } else if (!effectiveUnsigned && isExactSignedMinBound(val, effectiveBitWidth)) {
                registerCoreAlignedW003(
                    entry,
                    "Constraint violation (" + ctx.ownerLabel() + "): facet '" + StvnVocabulary.FACET_KW_MIN_INCL + " " + val
                        + "' is redundant; '" + StvnVocabulary.FACET_KW_SIZE + " " + effectiveBitWidth
                        + "' already bounds the minimum value to " + val,
                    holder
                );
            } else if (ctx.parentAlias() != null
                    && ctx.parentIr() instanceof StvnSchema.IntSchema pi
                    && pi.explicitMinIncl().isPresent()
                    && val.equals(pi.explicitMinIncl().get())) {
                String minFacet = StvnVocabulary.FACET_KW_MIN_INCL + " " + val;
                registerCoreAlignedW003(
                    entry,
                    "Constraint violation (" + ctx.ownerLabel() + "): facet '" + minFacet
                        + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + minFacet + "'",
                    holder
                );
            }
        } else {
            boolean isExactUnsignedCeiling = effectiveUnsigned && isExactPowerOfTwo(val, effectiveBitWidth);
            boolean isExactSignedCeiling = !effectiveUnsigned
                && effectiveBitWidth.compareTo(BigInteger.ONE) >= 0
                && isExactPowerOfTwo(val, effectiveBitWidth.subtract(BigInteger.ONE));
            if (isExactUnsignedCeiling) {
                registerCoreAlignedW003(
                    entry,
                    "Constraint violation (" + ctx.ownerLabel() + "): facet '" + StvnVocabulary.FACET_KW_MAX_EXCL + " " + val
                        + "' is redundant; '" + StvnVocabulary.FACET_KW_UNSIGNED + " " + StvnVocabulary.FACET_KW_SIZE + " "
                        + effectiveBitWidth + "' already bounds the domain to [0, " + val + ")",
                    holder
                );
            } else if (isExactSignedCeiling) {
                registerCoreAlignedW003(
                    entry,
                    "Constraint violation (" + ctx.ownerLabel() + "): facet '" + StvnVocabulary.FACET_KW_MAX_EXCL + " " + val
                        + "' is redundant; '" + StvnVocabulary.FACET_KW_SIZE + " " + effectiveBitWidth
                        + "' already bounds the maximum exclusive bound to " + val,
                    holder
                );
            } else if (ctx.parentAlias() != null
                    && ctx.parentIr() instanceof StvnSchema.IntSchema pi
                    && pi.explicitMaxExcl().isPresent()
                    && val.equals(pi.explicitMaxExcl().get())) {
                String maxFacet = StvnVocabulary.FACET_KW_MAX_EXCL + " " + val;
                registerCoreAlignedW003(
                    entry,
                    "Constraint violation (" + ctx.ownerLabel() + "): facet '" + maxFacet
                        + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + maxFacet + "'",
                    holder
                );
            }
        }
    }

    private static void checkNominalFloatBoundFallback(
        MetadataEntry entry,
        OwnerContext ctx,
        StvnSchema.FloatSchema pf,
        String tag,
        String rawArg,
        ProblemsHolder holder
    ) {
        var bdOpt = StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(entry, rawArg);
        if (bdOpt.isEmpty() || ctx.parentAlias() == null) {
            return;
        }
        BigDecimal bd = bdOpt.get();
        boolean isLower = StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MIN_EXCL.equals(tag);
        StvnSchema.BoundKind kind = (StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MAX_INCL.equals(tag))
            ? StvnSchema.BoundKind.INCLUSIVE
            : StvnSchema.BoundKind.EXCLUSIVE;
        var parentBoundOpt = isLower ? pf.lowerBound() : pf.upperBound();
        if (parentBoundOpt.isPresent()
                && parentBoundOpt.get().kind() == kind
                && bd.compareTo(parentBoundOpt.get().value()) == 0) {
            String childLit = formatCanonicalFloatBound(bd, pf.representation());
            String parentLit = formatCanonicalFloatBound(parentBoundOpt.get().value(), pf.representation());
            registerCoreAlignedW003(
                entry,
                "Constraint violation (" + ctx.ownerLabel() + "): facet '" + tag + " " + childLit
                    + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + tag + " " + parentLit + "'",
                holder
            );
        }
    }

    private static void checkNominalStringFallback(
        MetadataEntry entry,
        OwnerContext ctx,
        StvnSchema.StringSchema ps,
        String tag,
        String rawArg,
        ProblemsHolder holder
    ) {
        var sOpt = StvnPsiLiteralEvaluator.resolveStringOrConst(entry, rawArg, false);
        if (sOpt.isEmpty() || ctx.parentAlias() == null) {
            return;
        }
        String sVal = sOpt.get();
        if (StvnVocabulary.FACET_KW_REGEX.equals(tag)) {
            if (ps.rawRegexPattern().isPresent() && sVal.equals(ps.rawRegexPattern().get())) {
                String regexFacet = StvnVocabulary.FACET_KW_REGEX + " " + formatQuotedStvnString(sVal);
                registerCoreAlignedW003(
                    entry,
                    "Constraint violation (" + ctx.ownerLabel() + "): facet '" + regexFacet
                        + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + regexFacet + "'",
                    holder
                );
            }
            return;
        }
        boolean isLower = StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MIN_EXCL.equals(tag);
        StvnSchema.BoundKind kind = (StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) || StvnVocabulary.FACET_KW_MAX_INCL.equals(tag))
            ? StvnSchema.BoundKind.INCLUSIVE
            : StvnSchema.BoundKind.EXCLUSIVE;
        var parentBoundOpt = isLower ? ps.lowerBound() : ps.upperBound();
        if (parentBoundOpt.isPresent()
                && parentBoundOpt.get().kind() == kind
                && sVal.equals(parentBoundOpt.get().value())) {
            String quoted = formatQuotedStvnString(sVal);
            registerCoreAlignedW003(
                entry,
                "Constraint violation (" + ctx.ownerLabel() + "): facet '" + tag + " " + quoted
                    + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + tag + " " + quoted + "'",
                holder
            );
        }
    }

    private static void checkNominalTimeEpochBoundFallback(
        MetadataEntry entry,
        OwnerContext ctx,
        StvnSchema.TimeEpochSchema pt,
        String tag,
        String rawArg,
        ProblemsHolder holder
    ) {
        var valOpt = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, rawArg);
        if (valOpt.isEmpty() || ctx.parentAlias() == null) {
            return;
        }
        BigInteger val = valOpt.get();
        var parentBound = StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) ? pt.explicitMinIncl() : pt.explicitMaxExcl();
        if (parentBound.isPresent() && val.equals(parentBound.get())) {
            String facetStr = tag + " " + val;
            registerCoreAlignedW003(
                entry,
                "Constraint violation (" + ctx.ownerLabel() + "): facet '" + facetStr
                    + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + facetStr + "'",
                holder
            );
        }
    }

    private static void checkNominalDateTimeBoundFallback(
        MetadataEntry entry,
        OwnerContext ctx,
        StvnSchema.DateTimeSchema pd,
        String tag,
        String rawArg,
        ProblemsHolder holder
    ) {
        var sOpt = StvnPsiLiteralEvaluator.resolveStringOrConst(entry, rawArg, false);
        if (sOpt.isEmpty() || ctx.parentAlias() == null) {
            return;
        }
        String rawStr = sOpt.get();
        var parentIsoOpt = StvnVocabulary.FACET_KW_MIN_INCL.equals(tag) ? pd.explicitMinInclIso() : pd.explicitMaxExclIso();
        if (parentIsoOpt.isEmpty()) {
            return;
        }
        var childNanos = parseDateTimeBoundEpochNanosQuiet(rawStr);
        var parentNanos = parseDateTimeBoundEpochNanosQuiet(parentIsoOpt.get());
        if (childNanos.isPresent() && parentNanos.isPresent() && childNanos.get().compareTo(parentNanos.get()) == 0) {
            registerCoreAlignedW003(
                entry,
                "Constraint violation (" + ctx.ownerLabel() + "): facet '" + tag + " " + formatQuotedStvnString(rawStr)
                    + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '"
                    + tag + " " + formatQuotedStvnString(parentIsoOpt.get()) + "'",
                holder
            );
        }
    }

    private static void checkNominalEnumFilterInclFallback(
        MetadataEntry entry,
        OwnerContext ctx,
        ProblemsHolder holder
    ) {
        if (ctx.parentAlias() == null || entry.getMetadataFilter() == null) {
            return;
        }
        var variantList = entry.getMetadataFilter().getVariantList();
        if (variantList == null || variantList.getValueKeywordList().isEmpty()) {
            return;
        }
        List<String> rootVars;
        List<String> parentActive;
        if (ctx.parentIr() instanceof StvnSchema.EnumSchema es) {
            rootVars = es.rootVariants();
            parentActive = es.rootVariants();
        } else if (ctx.parentIr() instanceof StvnSchema.EnumSubsetSchema sub) {
            rootVars = sub.rootEnum().rootVariants();
            parentActive = sub.activeVariants();
        } else {
            return;
        }
        Map<String, Integer> rootOrdinalMap = new HashMap<>();
        for (int i = 0; i < rootVars.size(); i++) {
            rootOrdinalMap.put(rootVars.get(i), i);
        }
        Set<String> parentSet = new HashSet<>(parentActive);
        Set<String> filterVariants = new LinkedHashSet<>();
        Integer lastOrd = null;
        for (var vk : variantList.getValueKeywordList()) {
            String vName = vk.getText().trim();
            if (!filterVariants.add(vName) || !parentSet.contains(vName)) {
                return;
            }
            Integer ord = rootOrdinalMap.get(vName);
            if (ord != null && lastOrd != null && ord.compareTo(lastOrd) < 0) {
                return;
            }
            if (ord != null) {
                lastOrd = ord;
            }
        }
        List<String> childActive = new ArrayList<>();
        for (String v : parentActive) {
            if (filterVariants.contains(v)) {
                childActive.add(v);
            }
        }
        if (childActive.equals(parentActive)) {
            String activeBracket = StvnVocabulary.DELIM_OPEN_BRACKET
                + String.join(" ", childActive)
                + StvnVocabulary.DELIM_CLOSE_BRACKET;
            String canonicalFilter = StvnVocabulary.FACET_KW_FILTER_INCL + " " + activeBracket;
            registerCoreAlignedW003(
                entry,
                "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFilter
                    + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes active variants '"
                    + activeBracket + "'",
                holder
            );
        }
    }

    private static void checkTraitFallback(
        MetadataEntry entry,
        MetadataMap metaMap,
        OwnerContext ctx,
        String tag,
        String rawArg,
        ProblemsHolder holder
    ) {
        var boolOpt = resolveBooleanFacetArg(entry, rawArg);
        if (boolOpt.isEmpty()) {
            return;
        }
        boolean declaredVal = boolOpt.get();
        boolean isEq = StvnVocabulary.FACET_KW_EQUATABLE.equals(tag);
        String traitName = isEq ? "equatable" : "comparable";
        String boolLit = declaredVal ? StvnVocabulary.VAL_TRUE : StvnVocabulary.VAL_FALSE;
        String canonicalFacet = tag + " " + boolLit;

        if (ctx.parentAlias() != null && ctx.parentIr() != null) {
            boolean parentVal = isEq ? ctx.parentIr().traits().equatable() : ctx.parentIr().traits().comparable();
            if (declaredVal == parentVal) {
                registerCoreAlignedW003(
                    entry,
                    "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet
                        + "' is redundant; ancestor '" + ctx.parentAlias() + "' already establishes '" + canonicalFacet + "'",
                    holder
                );
            }
            return;
        }

        boolean isExactFloat = StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase()) && hasLocalExactFacet(metaMap);
        boolean defaultVal = isEq
            ? resolveRootDefaultEquatable(ctx, metaMap)
            : resolveRootDefaultComparable(ctx);
        if (declaredVal != defaultVal) {
            return;
        }

        String message;
        if (StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase()) && !isExactFloat && isEq && !declaredVal) {
            message = "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet + "' is redundant; continuous '"
                + StvnVocabulary.TYPE_FLOAT + "' is non-equatable by default";
        } else if (StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase()) && !isExactFloat && !isEq && declaredVal) {
            message = "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet + "' is redundant; continuous '"
                + StvnVocabulary.TYPE_FLOAT + "' is intrinsically comparable";
        } else if (StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase()) && isExactFloat && declaredVal) {
            message = "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet
                + "' is redundant; exact decimal float is intrinsically " + traitName;
        } else if ((StvnVocabulary.TYPE_BOOLEAN.equals(ctx.ultimateBase())
                || StvnVocabulary.TYPE_INT.equals(ctx.ultimateBase())
                || StvnVocabulary.TYPE_STRING.equals(ctx.ultimateBase())
                || StvnVocabulary.TYPE_TIME_EPOCH.equals(ctx.ultimateBase())
                || StvnVocabulary.TYPE_DATE_TIME.equals(ctx.ultimateBase())) && declaredVal) {
            message = "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet
                + "' is redundant; scalar primitive '" + ctx.ultimateBase() + "' is intrinsically " + traitName;
        } else {
            message = "Constraint violation (" + ctx.ownerLabel() + "): facet '" + canonicalFacet
                + "' is redundant; '" + ctx.ultimateBase() + "' already resolves to '" + canonicalFacet + "'";
        }
        registerCoreAlignedW003(entry, message, holder);
    }

    private static boolean resolveRootDefaultEquatable(OwnerContext ctx, MetadataMap metaMap) {
        if (StvnVocabulary.TYPE_FLOAT.equals(ctx.ultimateBase())) {
            return hasLocalExactFacet(metaMap);
        }
        if (ctx.enclosingTypeDef() != null && StvnVocabulary.TYPE_TUPLE.equals(ctx.ultimateBase())) {
            return resolveInductiveTupleEquatable(ctx.enclosingTypeDef(), new HashSet<>());
        }
        var ctor = ctx.schemaType().getSchemaConstructor();
        if (ctor != null) {
            return isConstructorEquatable(ctor, metaMap, new HashSet<>());
        }
        return true;
    }

    private static boolean resolveRootDefaultComparable(OwnerContext ctx) {
        if (StvnVocabulary.TYPE_SET.equals(ctx.ultimateBase()) || StvnVocabulary.TYPE_MAP.equals(ctx.ultimateBase())) {
            return false;
        }
        if (ctx.enclosingTypeDef() != null && StvnVocabulary.TYPE_TUPLE.equals(ctx.ultimateBase())) {
            return resolveInductiveTupleComparable(ctx.enclosingTypeDef(), new HashSet<>());
        }
        var ctor = ctx.schemaType().getSchemaConstructor();
        if (ctor != null) {
            return isConstructorComparable(ctor, new HashSet<>());
        }
        return true;
    }

    private static boolean hasLocalExactFacet(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return false;
        }
        for (var entry : metaMap.getMetadataEntryList()) {
            var bareFlag = entry.getMetadataBareFlag();
            if (bareFlag != null && bareFlag.getText().trim().equals(StvnVocabulary.FACET_KW_EXACT)) {
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
                var norm = entry.getText().trim().replaceAll("\\s+", " ");
                var parts = norm.split(" ", 2);
                if (parts.length >= 2 && StvnVocabulary.FACET_KW_EQUATABLE.equals(parts[0])) {
                    var boolOpt = resolveBooleanFacetArg(entry, parts[1].trim());
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
            return isConstructorEquatable(ctor, metaMap, visited);
        }
        return true;
    }

    private static boolean isConstructorEquatable(
        SchemaConstructor ctor,
        @Nullable MetadataMap metaMap,
        Set<String> visited
    ) {
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
            return inners.stream().allMatch(f -> isFieldEquatable(f, visited));
        }
        var sum = ctor.getSumType();
        if (sum != null) {
            if (sum.getEnumDef() != null) {
                return true;
            }
            var inners = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            return inners.stream().allMatch(f -> isFieldEquatable(f, visited));
        }
        return true;
    }

    private static boolean isFieldComparable(@NotNull SchemaType field, Set<String> visited) {
        var metaMap = field.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var norm = entry.getText().trim().replaceAll("\\s+", " ");
                var parts = norm.split(" ", 2);
                if (parts.length >= 2 && StvnVocabulary.FACET_KW_COMPARABLE.equals(parts[0])) {
                    var boolOpt = resolveBooleanFacetArg(entry, parts[1].trim());
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
            return isConstructorComparable(ctor, visited);
        }
        return true;
    }

    private static boolean isConstructorComparable(SchemaConstructor ctor, Set<String> visited) {
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
            return inners.stream().allMatch(f -> isFieldComparable(f, visited));
        }
        var sum = ctor.getSumType();
        if (sum != null) {
            if (sum.getEnumDef() != null) {
                return true;
            }
            var inners = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
            return inners.stream().allMatch(f -> isFieldComparable(f, visited));
        }
        return true;
    }

    private static boolean resolveFallbackTypeDefEquatable(@NotNull TypeDefinition targetDef, Set<String> visited) {
        var metaMap = targetDef.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var norm = entry.getText().trim().replaceAll("\\s+", " ");
                var parts = norm.split(" ", 2);
                if (parts.length >= 2 && StvnVocabulary.FACET_KW_EQUATABLE.equals(parts[0])) {
                    var boolOpt = resolveBooleanFacetArg(entry, parts[1].trim());
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
                var norm = entry.getText().trim().replaceAll("\\s+", " ");
                var parts = norm.split(" ", 2);
                if (parts.length >= 2 && StvnVocabulary.FACET_KW_COMPARABLE.equals(parts[0])) {
                    var boolOpt = resolveBooleanFacetArg(entry, parts[1].trim());
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

    private static BigInteger resolveEffectiveIntBitWidth(
        MetadataEntry contextEntry,
        @Nullable MetadataMap metaMap,
        @Nullable StvnSchema parentIr
    ) {
        BigInteger bw = parentIr instanceof StvnSchema.IntSchema pi
            ? pi.bitWidth()
            : StvnVocabulary.DEFAULT_INT_BIT_WIDTH;
        if (metaMap != null) {
            for (var e : metaMap.getMetadataEntryList()) {
                var text = e.getText().trim().replaceAll("\\s+", " ");
                var parts = text.split(" ", 2);
                if (parts.length >= 2 && StvnVocabulary.FACET_KW_SIZE.equals(parts[0])) {
                    var valOpt = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(contextEntry, parts[1].trim());
                    if (valOpt.isPresent() && valOpt.get().compareTo(BigInteger.ONE) >= 0) {
                        bw = valOpt.get();
                    }
                }
            }
        }
        return bw;
    }

    private static boolean isExactSignedMinBound(BigInteger val, BigInteger bitWidth) {
        if (val.signum() >= 0 || bitWidth.compareTo(BigInteger.ONE) < 0) {
            return false;
        }
        BigInteger mag = val.negate();
        return mag.bitCount() == 1
            && BigInteger.valueOf(mag.getLowestSetBit()).equals(bitWidth.subtract(BigInteger.ONE));
    }

    private static boolean isExactPowerOfTwo(BigInteger val, BigInteger exponent) {
        if (val.signum() <= 0 || exponent.signum() < 0) {
            return false;
        }
        return val.bitCount() == 1 && BigInteger.valueOf(val.getLowestSetBit()).equals(exponent);
    }

    private static boolean hasLocalUnsignedFacet(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return false;
        }
        for (var entry : metaMap.getMetadataEntryList()) {
            var flag = entry.getMetadataBareFlag();
            if (flag != null && flag.getText().trim().equals(StvnVocabulary.FACET_KW_UNSIGNED)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Boolean> resolveBooleanFacetArg(MetadataEntry entry, String rawArg) {
        MetadataValue mv = entry.getMetadataTrait() != null
            ? entry.getMetadataTrait().getMetadataValue()
            : (entry.getMetadataBool() != null ? entry.getMetadataBool().getMetadataValue() : entry.getMetadataValue());
        String token = mv != null ? mv.getText().trim() : rawArg;
        return StvnPsiLiteralEvaluator.resolveBooleanOrConst(entry, token);
    }

    private static Optional<BigInteger> parseDateTimeBoundEpochNanosQuiet(String decodedText) {
        int lbIdx = decodedText.indexOf('[');
        int rbIdx = decodedText.endsWith("]") ? decodedText.length() - 1 : -1;
        try {
            Instant inst;
            if (lbIdx > 0 && rbIdx > lbIdx) {
                String prefix = decodedText.substring(0, lbIdx);
                String zoneStr = decodedText.substring(lbIdx + 1, rbIdx);
                if (!ZoneRulesProvider.getAvailableZoneIds().contains(zoneStr)) {
                    return Optional.empty();
                }
                ZoneId zoneId = ZoneId.of(zoneStr);
                if (hasExplicitOffsetSuffix(prefix)) {
                    OffsetDateTime odt = OffsetDateTime.parse(prefix);
                    var validOffsets = zoneId.getRules().getValidOffsets(odt.toLocalDateTime());
                    if (validOffsets.isEmpty() || !validOffsets.contains(odt.getOffset())) {
                        return Optional.empty();
                    }
                    inst = odt.toInstant();
                } else {
                    LocalDateTime ldt = LocalDateTime.parse(prefix);
                    if (zoneId.getRules().getValidOffsets(ldt).isEmpty()) {
                        return Optional.empty();
                    }
                    inst = ldt.atZone(zoneId).toInstant();
                }
            } else {
                inst = OffsetDateTime.parse(decodedText).toInstant();
            }
            return Optional.of(BigInteger.valueOf(inst.getEpochSecond())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(inst.getNano())));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private static boolean hasExplicitOffsetSuffix(String isoPrefix) {
        if (isoPrefix.endsWith("Z") || isoPrefix.endsWith("z")) {
            return true;
        }
        int tIdx = Math.max(isoPrefix.indexOf('T'), isoPrefix.indexOf('t'));
        if (tIdx < 0) {
            return false;
        }
        for (int i = tIdx + 1; i < isoPrefix.length(); i++) {
            char c = isoPrefix.charAt(i);
            if (c == '+' || c == '-') {
                return true;
            }
        }
        return false;
    }

    private static String formatCanonicalFloatBound(BigDecimal value, StvnSchema.FloatRepresentation representation) {
        String s = (representation == StvnSchema.FloatRepresentation.EXACT_DECIMAL)
            ? value.toPlainString()
            : value.toString();
        if (!s.contains(".")) {
            int eIdx = s.indexOf('e');
            if (eIdx == -1) {
                eIdx = s.indexOf('E');
            }
            return (eIdx == -1) ? s + ".0" : s.substring(0, eIdx) + ".0" + s.substring(eIdx);
        }
        return s;
    }

    private static String formatQuotedStvnString(String raw) {
        var sb = new StringBuilder(raw.length() + 4);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void registerCoreAlignedW003(MetadataEntry entry, String coreMessage, ProblemsHolder holder) {
        String entryText = entry.getText().trim();
        holder.registerProblem(
            entry,
            StvnDiagnosticFormatter.formatPlugin(
                coreMessage,
                DiagnosticBag.WARN_REDUNDANT_METADATA_FACET
            ),
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new RemoveRedundantFacetQuickFix(entry, entryText)
        );
    }
}
