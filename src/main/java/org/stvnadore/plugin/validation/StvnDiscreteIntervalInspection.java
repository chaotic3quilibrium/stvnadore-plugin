package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Visitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates STVN 2.0.0 discrete interval rules and interval bound ordering.
 * Discrete types (:Int, :TimeEpoch, :DateTime, and { #exact } :Float) reject closed upper bounds (#maxIncl)
 * and open lower bounds (#minExcl) in favor of half-open intervals [minIncl, maxExcl).
 */
@NullMarked
public final class StvnDiscreteIntervalInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnDiscreteIntervalInspection instance.
     */
    public StvnDiscreteIntervalInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnDiscreteInterval";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Discrete interval bound governance inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "STVN";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition typeDef) {
                super.visitTypeDefinition(typeDef);
                var schemaType = typeDef.getSchemaType();
                var metaMap = typeDef.getMetadataMap() != null
                        ? typeDef.getMetadataMap()
                        : (schemaType != null ? schemaType.getMetadataMap() : null);
                if (schemaType != null && metaMap != null) {
                    validateIntervalBounds(schemaType, metaMap, holder);
                }
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                super.visitConstantDefinition(constDef);
                var schemaType = constDef.getSchemaType();
                var metaMap = constDef.getMetadataMap() != null
                        ? constDef.getMetadataMap()
                        : (schemaType != null ? schemaType.getMetadataMap() : null);
                if (schemaType != null && metaMap != null) {
                    validateIntervalBounds(schemaType, metaMap, holder);
                }
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (schemaType.getParent() instanceof TypeDefinition
                        || schemaType.getParent() instanceof ConstantDefinition) {
                    return;
                }
                var metaMap = schemaType.getMetadataMap();
                if (metaMap == null) {
                    return;
                }
                validateIntervalBounds(schemaType, metaMap, holder);
            }
        };
    }

    private static void validateIntervalBounds(
            @NotNull SchemaType schemaType,
            @NotNull MetadataMap metaMap,
            @NotNull ProblemsHolder holder
    ) {
        boolean discrete = isDiscreteType(schemaType);
        var baseType = resolveBaseTypeString(schemaType);
        boolean isDateTime = StvnVocabulary.TYPE_DATE_TIME.equals(baseType);
        boolean isString = StvnVocabulary.TYPE_STRING.equals(baseType);
        boolean isFloat = StvnVocabulary.TYPE_FLOAT.equals(baseType);

        if (!discrete && !isDateTime && !isString && !isFloat) {
            return;
        }

        MetadataEntry maxExclEntry = null;
        MetadataEntry lowerEntry = null;
        MetadataEntry upperEntry = null;
        String lowerKw = StvnVocabulary.FACET_KW_MIN_INCL;
        String upperKw = StvnVocabulary.FACET_KW_MAX_EXCL;
        String minRaw = null;
        String maxRaw = null;
        boolean lowerExcl = false;
        boolean upperExcl = false;
        BigDecimal minVal = null;
        BigDecimal maxVal = null;
        Instant minInstant = null;
        Instant maxInstant = null;
        String minStr = null;
        String maxStr = null;

        for (var entry : metaMap.getMetadataEntryList()) {
            var entryText = entry.getText();
            var rawVal = entry.getMetadataValue() != null ? entry.getMetadataValue().getText().trim() : null;
            if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                if (discrete) {
                    var replaceKwFix = new ReplaceBoundKeywordQuickFix(
                        entry,
                        StvnVocabulary.FACET_KW_MAX_INCL,
                        StvnVocabulary.FACET_KW_MAX_EXCL,
                        "Replace '" + StvnVocabulary.FACET_KW_MAX_INCL
                            + "' with half-open bound '" + StvnVocabulary.FACET_KW_MAX_EXCL + "'"
                    );
                    var quickFixes = canIncrementBound(entry)
                        ? new LocalQuickFix[]{new ConvertMaxInclToMaxExclQuickFix(entry), replaceKwFix}
                        : new LocalQuickFix[]{replaceKwFix};
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Discrete types reject closed upper bound '"
                                + StvnVocabulary.FACET_KW_MAX_INCL
                                + "'; use half-open bound '"
                                + StvnVocabulary.FACET_KW_MAX_EXCL + "'",
                            StvnDiagnosticCodes.ERR_DISCRETE_BOUND_KIND_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        quickFixes
                    );
                }
                maxVal = extractBoundBigDecimal(entry);
                maxInstant = isDateTime ? extractBoundInstant(entry) : null;
                maxStr = extractBoundString(entry);
                upperEntry = entry;
                upperKw = StvnVocabulary.FACET_KW_MAX_INCL;
                maxRaw = rawVal;
                upperExcl = false;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                if (discrete) {
                    var replaceKwFix = new ReplaceBoundKeywordQuickFix(
                        entry,
                        StvnVocabulary.FACET_KW_MIN_EXCL,
                        StvnVocabulary.FACET_KW_MIN_INCL,
                        "Replace '" + StvnVocabulary.FACET_KW_MIN_EXCL
                            + "' with closed bound '" + StvnVocabulary.FACET_KW_MIN_INCL + "'"
                    );
                    var quickFixes = canIncrementBound(entry)
                        ? new LocalQuickFix[]{new ConvertMinExclToMinInclQuickFix(entry), replaceKwFix}
                        : new LocalQuickFix[]{replaceKwFix};
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Discrete types reject open lower bound '"
                                + StvnVocabulary.FACET_KW_MIN_EXCL
                                + "'; use half-open bound '"
                                + StvnVocabulary.FACET_KW_MIN_INCL + "'",
                            StvnDiagnosticCodes.ERR_DISCRETE_BOUND_KIND_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        quickFixes
                    );
                }
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry) : null;
                minStr = extractBoundString(entry);
                lowerEntry = entry;
                lowerKw = StvnVocabulary.FACET_KW_MIN_EXCL;
                minRaw = rawVal;
                lowerExcl = true;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null) {
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry) : null;
                minStr = extractBoundString(entry);
                lowerEntry = entry;
                lowerKw = StvnVocabulary.FACET_KW_MIN_INCL;
                minRaw = rawVal;
                lowerExcl = false;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null) {
                maxExclEntry = entry;
                maxVal = extractBoundBigDecimal(entry);
                maxInstant = isDateTime ? extractBoundInstant(entry) : null;
                maxStr = extractBoundString(entry);
                upperEntry = entry;
                upperKw = StvnVocabulary.FACET_KW_MAX_EXCL;
                maxRaw = rawVal;
                upperExcl = true;
            }
        }

        MetadataEntry localLowerEntry = lowerEntry;
        MetadataEntry localUpperEntry = upperEntry;

        if ((localLowerEntry != null && localUpperEntry == null)
                || (localLowerEntry == null && localUpperEntry != null)) {
            var kw = schemaType.getTypeKeyword();
            var file = schemaType.getContainingFile();
            if (kw != null && file != null) {
                var visited = new HashSet<String>();
                String parentAlias = kw.getText().trim();
                while (parentAlias != null && parentAlias.startsWith(StvnVocabulary.SIGIL_TYPIC) && visited.add(parentAlias)) {
                    var resolved = StvnTypeReference.resolveTypeInFile(file, parentAlias, new HashSet<>());
                    var parentDef = resolved != null ? PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class, false) : null;
                    if (parentDef == null) {
                        break;
                    }
                    var parentMeta = parentDef.getMetadataMap() != null
                        ? parentDef.getMetadataMap()
                        : (parentDef.getSchemaType() != null ? parentDef.getSchemaType().getMetadataMap() : null);
                    if (parentMeta != null) {
                        for (var pEntry : parentMeta.getMetadataEntryList()) {
                            var pText = pEntry.getText();
                            if (lowerEntry == null
                                    && (pText.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                                        || pEntry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null
                                        || pText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                                        || pEntry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null)) {
                                boolean isMinExcl = pText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                                    || pEntry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null;
                                minVal = extractBoundBigDecimal(pEntry);
                                minInstant = isDateTime ? extractBoundInstant(pEntry) : null;
                                minStr = extractBoundString(pEntry);
                                lowerEntry = pEntry;
                                lowerKw = isMinExcl ? StvnVocabulary.FACET_KW_MIN_EXCL : StvnVocabulary.FACET_KW_MIN_INCL;
                                lowerExcl = isMinExcl;
                            } else if (upperEntry == null
                                    && (pText.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                                        || pEntry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null
                                        || pText.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                                        || pEntry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null)) {
                                boolean isMaxExcl = pText.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                                    || pEntry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null;
                                if (isMaxExcl) {
                                    maxExclEntry = pEntry;
                                }
                                maxVal = extractBoundBigDecimal(pEntry);
                                maxInstant = isDateTime ? extractBoundInstant(pEntry) : null;
                                maxStr = extractBoundString(pEntry);
                                upperEntry = pEntry;
                                upperKw = isMaxExcl ? StvnVocabulary.FACET_KW_MAX_EXCL : StvnVocabulary.FACET_KW_MAX_INCL;
                                upperExcl = isMaxExcl;
                            }
                        }
                    }
                    if (lowerEntry != null && upperEntry != null) {
                        break;
                    }
                    var parentSchema = parentDef.getSchemaType();
                    parentAlias = (parentSchema != null && parentSchema.getTypeKeyword() != null)
                        ? parentSchema.getTypeKeyword().getText().trim()
                        : null;
                }
            }
        }

        boolean bothBoundsLocal = (localLowerEntry != null && localUpperEntry != null);
        MetadataEntry localBoundTarget = localUpperEntry != null ? localUpperEntry : localLowerEntry;
        LocalQuickFix[] swapFixes = (bothBoundsLocal && minRaw != null && maxRaw != null)
            ? new LocalQuickFix[]{new SwapInvertedBoundsQuickFix(localLowerEntry, lowerKw, minRaw, localUpperEntry, upperKw, maxRaw)}
            : LocalQuickFix.EMPTY_ARRAY;

        if (minVal != null && maxVal != null && upperEntry != null) {
            int cmp = minVal.compareTo(maxVal);
            if (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl)) {
                holder.registerProblem(
                    bothBoundsLocal
                        ? (maxExclEntry != null ? maxExclEntry : upperEntry)
                        : (localBoundTarget != null ? localBoundTarget : metaMap),
                    StvnDiagnosticFormatter.formatPlugin(
                        "Discrete interval defines an empty domain [" + minVal.toPlainString() + ", " + maxVal.toPlainString() + ")",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            } else if (cmp > 0) {
                holder.registerProblem(
                    bothBoundsLocal ? metaMap : (localBoundTarget != null ? localBoundTarget : metaMap),
                    StvnDiagnosticFormatter.formatPlugin(
                        "Discrete interval lower bound " + minVal.toPlainString() + " is greater than upper bound " + maxVal.toPlainString(),
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    swapFixes
                );
            }
        } else if (isDateTime && minInstant != null && maxInstant != null && upperEntry != null) {
            int cmp = minInstant.compareTo(maxInstant);
            if (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl)) {
                holder.registerProblem(
                    bothBoundsLocal
                        ? (maxExclEntry != null ? maxExclEntry : upperEntry)
                        : (localBoundTarget != null ? localBoundTarget : metaMap),
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal interval defines an empty domain [" + minStr + ", " + maxStr + ")",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            } else if (cmp > 0) {
                holder.registerProblem(
                    bothBoundsLocal ? metaMap : (localBoundTarget != null ? localBoundTarget : metaMap),
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal interval lower bound " + minStr + " is greater than upper bound " + maxStr,
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    swapFixes
                );
            }
        } else if (isString && minStr != null && maxStr != null && upperEntry != null) {
            int cmp = minStr.compareTo(maxStr);
            if (cmp == 0 && (lowerExcl || upperExcl)) {
                holder.registerProblem(
                    bothBoundsLocal ? upperEntry : (localBoundTarget != null ? localBoundTarget : metaMap),
                    StvnDiagnosticFormatter.formatPlugin(
                        "String interval defines an empty domain",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            } else if (cmp > 0) {
                holder.registerProblem(
                    bothBoundsLocal ? metaMap : (localBoundTarget != null ? localBoundTarget : metaMap),
                    StvnDiagnosticFormatter.formatPlugin(
                        "String interval lower bound '" + minStr + "' is lexicographically greater than upper bound '" + maxStr + "'",
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    swapFixes
                );
            }
        }
    }

    /**
     * Creates a {@link SwapInvertedBoundsQuickFix} for the given {@link MetadataMap} when both a lower
     * bound facet and an upper bound facet are present.
     *
     * @param metaMap metadata map containing inverted bounds
     * @return quick-fix instance, or {@code null} when bounds are incomplete
     */
    public static @Nullable SwapInvertedBoundsQuickFix createSwapFixForMetadataMap(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return null;
        }
        MetadataEntry lowerEntry = null;
        MetadataEntry upperEntry = null;
        String lowerKw = StvnVocabulary.FACET_KW_MIN_INCL;
        String upperKw = StvnVocabulary.FACET_KW_MAX_EXCL;
        String minRaw = null;
        String maxRaw = null;
        for (var entry : metaMap.getMetadataEntryList()) {
            var entryText = entry.getText();
            var rawVal = entry.getMetadataValue() != null ? entry.getMetadataValue().getText().trim() : null;
            if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null) {
                lowerEntry = entry;
                lowerKw = StvnVocabulary.FACET_KW_MIN_INCL;
                minRaw = rawVal;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                lowerEntry = entry;
                lowerKw = StvnVocabulary.FACET_KW_MIN_EXCL;
                minRaw = rawVal;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null) {
                upperEntry = entry;
                upperKw = StvnVocabulary.FACET_KW_MAX_EXCL;
                maxRaw = rawVal;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                upperEntry = entry;
                upperKw = StvnVocabulary.FACET_KW_MAX_INCL;
                maxRaw = rawVal;
            }
        }
        if (lowerEntry != null && upperEntry != null && minRaw != null && maxRaw != null) {
            return new SwapInvertedBoundsQuickFix(lowerEntry, lowerKw, minRaw, upperEntry, upperKw, maxRaw);
        }
        return null;
    }

    private static boolean isDiscreteType(@Nullable SchemaType schemaType) {
        if (schemaType == null) return false;
        var cleanType = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
        var baseType = resolveBaseTypeString(schemaType);
        if (baseType.equals(StvnVocabulary.TYPE_INT)
                || baseType.equals(StvnVocabulary.TYPE_TIME_EPOCH)
                || baseType.equals(StvnVocabulary.TYPE_DATE_TIME)) {
            return true;
        }
        if (cleanType.equals(StvnVocabulary.TYPE_FLOAT) || baseType.equals(StvnVocabulary.TYPE_FLOAT)) {
            return hasExactFacet(schemaType, new HashSet<>());
        }
        return false;
    }

    private static boolean hasExactFacet(@NotNull SchemaType schemaType, Set<String> visited) {
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            for (var e : metaMap.getMetadataEntryList()) {
                if (e.getText().startsWith(StvnVocabulary.FACET_KW_EXACT)
                    || e.getNode().findChildByType(StvnTypes.KW_EXACT) != null) {
                    return true;
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (!visited.add(text)) {
                return false;
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return hasExactFacet(parentDef.getSchemaType(), visited);
                    }
                }
            }
        }
        var resolvedNominal = StvnTypeResolver.resolveNominalSchema(schemaType);
        if (resolvedNominal != null && resolvedNominal != schemaType) {
            var resKw = resolvedNominal.getTypeKeyword();
            String resText = resKw != null ? resKw.getText().trim() : resolvedNominal.getText().trim();
            if (visited.add(resText)) {
                return hasExactFacet(resolvedNominal, visited);
            }
        }
        return false;
    }

    private static @Nullable BigDecimal extractBoundBigDecimal(MetadataEntry entry) {
        var val = entry.getMetadataValue();
        if (val == null) return null;
        return StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(entry, val.getText().trim()).orElse(null);
    }

    private static @Nullable String extractBoundString(MetadataEntry entry) {
        var val = entry.getMetadataValue();
        if (val == null) return null;
        return StvnPsiLiteralEvaluator.resolveStringOrConst(entry, val.getText().trim(), false).orElse(null);
    }

    private static @Nullable Instant extractBoundInstant(MetadataEntry entry) {
        String decoded = extractBoundString(entry);
        if (decoded == null || decoded.isEmpty()) {
            return null;
        }
        try {
            if (decoded.contains("[")) {
                return ZonedDateTime.parse(decoded).toInstant();
            }
            return OffsetDateTime.parse(decoded).toInstant();
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String resolveBaseTypeString(SchemaType schemaType) {
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            var atomic = constructor.getAtomicType();
            if (atomic != null) {
                return atomic.getText().trim();
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveBaseTypeString(parentDef.getSchemaType());
                    }
                }
            }
            return text;
        }
        return schemaType.getText().trim();
    }

    private static boolean canIncrementBound(MetadataEntry entry) {
        var val = entry.getMetadataValue();
        if (val == null) return false;
        return StvnPsiLiteralEvaluator.parseBigInteger(val.getText().trim()).isPresent();
    }

    private static String incrementBound(String valText) {
        if (valText.startsWith("0x") || valText.startsWith("0X")) {
            return StvnPsiLiteralEvaluator.parseBigInteger(valText)
                .map(bi -> "0x" + bi.add(BigInteger.ONE).toString(16))
                .orElse(valText);
        }
        if (valText.startsWith("0b") || valText.startsWith("0B")) {
            return StvnPsiLiteralEvaluator.parseBigInteger(valText)
                .map(bi -> "0b" + bi.add(BigInteger.ONE).toString(2))
                .orElse(valText);
        }
        if (valText.startsWith("0o") || valText.startsWith("0O")) {
            return StvnPsiLiteralEvaluator.parseBigInteger(valText)
                .map(bi -> "0o" + bi.add(BigInteger.ONE).toString(8))
                .orElse(valText);
        }
        return StvnPsiLiteralEvaluator.parseBigInteger(valText)
            .map(bi -> bi.add(BigInteger.ONE).toString())
            .orElse(valText);
    }

    private static final class ConvertMaxInclToMaxExclQuickFix implements LocalQuickFix {
        private final MetadataEntry entry;

        ConvertMaxInclToMaxExclQuickFix(MetadataEntry entry) {
            this.entry = entry;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Convert '" + StvnVocabulary.FACET_KW_MAX_INCL + " <N>' to '"
                + StvnVocabulary.FACET_KW_MAX_EXCL + " <N+1>'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            var replacement = StvnElementFactory.createMetadataEntry(
                project,
                StvnVocabulary.FACET_KW_MAX_EXCL + " " + nextVal
            );
            entry.replace(replacement);
        }
    }

    private static final class ConvertMinExclToMinInclQuickFix implements LocalQuickFix {
        private final MetadataEntry entry;

        ConvertMinExclToMinInclQuickFix(MetadataEntry entry) {
            this.entry = entry;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Convert '" + StvnVocabulary.FACET_KW_MIN_EXCL + " <N>' to '"
                + StvnVocabulary.FACET_KW_MIN_INCL + " <N+1>'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            var replacement = StvnElementFactory.createMetadataEntry(
                project,
                StvnVocabulary.FACET_KW_MIN_INCL + " " + nextVal
            );
            entry.replace(replacement);
        }
    }

    private static final class ReplaceBoundKeywordQuickFix implements LocalQuickFix {
        private final MetadataEntry entry;
        private final String oldKw;
        private final String newKw;
        private final String label;

        ReplaceBoundKeywordQuickFix(MetadataEntry entry, String oldKw, String newKw, String label) {
            this.entry = entry;
            this.oldKw = oldKw;
            this.newKw = newKw;
            this.label = label;
        }

        @Override
        public @NotNull String getName() {
            return label;
        }

        @Override
        public @NotNull String getFamilyName() {
            return label;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            if (val != null) {
                var replacement = StvnElementFactory.createMetadataEntry(
                    project,
                    newKw + " " + val.getText().trim()
                );
                entry.replace(replacement);
            } else {
                var text = entry.getText().trim().replaceFirst("^" + java.util.regex.Pattern.quote(oldKw), newKw);
                var replacement = StvnElementFactory.createMetadataEntry(project, text);
                entry.replace(replacement);
            }
        }
    }

    /**
     * Quick-fix that swaps inverted lower and upper bound values while preserving each facet's bound keyword.
     */
    public static final class SwapInvertedBoundsQuickFix implements LocalQuickFix, IntentionAction, PriorityAction {
        private final MetadataEntry lowerEntry;
        private final String lowerKw;
        private final String lowerValText;
        private final MetadataEntry upperEntry;
        private final String upperKw;
        private final String upperValText;
        private final String label;

        /**
         * Constructs a new {@link SwapInvertedBoundsQuickFix}.
         *
         * @param lowerEntry   PSI entry for the lower bound facet
         * @param lowerKw      lower bound facet keyword
         * @param lowerValText authored lower bound value text
         * @param upperEntry   PSI entry for the upper bound facet
         * @param upperKw      upper bound facet keyword
         * @param upperValText authored upper bound value text
         */
        public SwapInvertedBoundsQuickFix(
                MetadataEntry lowerEntry,
                String lowerKw,
                String lowerValText,
                MetadataEntry upperEntry,
                String upperKw,
                String upperValText
        ) {
            this.lowerEntry = lowerEntry;
            this.lowerKw = lowerKw;
            this.lowerValText = lowerValText;
            this.upperEntry = upperEntry;
            this.upperKw = upperKw;
            this.upperValText = upperValText;
            this.label = "Swap inverted bounds ('" + lowerKw + " " + upperValText
                + "' and '" + upperKw + " " + lowerValText + "')";
        }

        @Override
        public @NotNull String getName() {
            return label;
        }

        @Override
        public @NotNull String getText() {
            return label;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Swap inverted interval bounds";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            return lowerEntry.isValid() && upperEntry.isValid();
        }

        @Override
        public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            swapBounds(project);
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            swapBounds(project);
        }

        private void swapBounds(Project project) {
            if (!lowerEntry.isValid() || !upperEntry.isValid()) {
                return;
            }
            var newLower = StvnElementFactory.createMetadataEntry(project, lowerKw + " " + upperValText);
            var newUpper = StvnElementFactory.createMetadataEntry(project, upperKw + " " + lowerValText);
            lowerEntry.replace(newLower);
            upperEntry.replace(newUpper);
        }
    }
}
