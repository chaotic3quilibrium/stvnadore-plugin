package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.MetadataValue;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Visitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeDef)) {
                    return;
                }
                var schemaType = typeDef.getSchemaType();
                var metaMap = typeDef.getMetadataMap() != null
                        ? typeDef.getMetadataMap()
                        : (schemaType != null ? schemaType.getMetadataMap() : null);
                if (schemaType != null && metaMap != null) {
                    validateIntervalBounds(schemaType, metaMap, holder, isOnTheFly);
                }
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                super.visitConstantDefinition(constDef);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)) {
                    return;
                }
                var schemaType = constDef.getSchemaType();
                var metaMap = constDef.getMetadataMap() != null
                        ? constDef.getMetadataMap()
                        : (schemaType != null ? schemaType.getMetadataMap() : null);
                if (schemaType != null && metaMap != null) {
                    validateIntervalBounds(schemaType, metaMap, holder, isOnTheFly);
                }
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(schemaType)) {
                    return;
                }
                if (schemaType.getParent() instanceof TypeDefinition
                        || schemaType.getParent() instanceof ConstantDefinition) {
                    return;
                }
                var metaMap = schemaType.getMetadataMap();
                if (metaMap == null) {
                    return;
                }
                validateIntervalBounds(schemaType, metaMap, holder, isOnTheFly);
            }
        };
    }

    private static void validateIntervalBounds(
            @NotNull SchemaType schemaType,
            @NotNull MetadataMap metaMap,
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly
    ) {
        boolean discrete = isDiscreteType(schemaType);
        var baseType = resolveBaseTypeString(schemaType);
        boolean isDateTime = StvnVocabulary.TYPE_DATE_TIME.equals(baseType);
        boolean isString = StvnVocabulary.TYPE_STRING.equals(baseType);
        boolean isFloat = StvnVocabulary.TYPE_FLOAT.equals(baseType);

        if (!discrete && !isDateTime && !isString && !isFloat) {
            return;
        }

        String ownerLabel = resolveIntervalOwnerLabel(metaMap, baseType);
        String discreteDesc = formatDiscreteTypeDescription(baseType);

        StvnSchema.DateTimeMode dateTimeMode = isDateTime ? extractEffectiveDateTimeMode(schemaType, metaMap) : null;

        MetadataEntry firstValidLower = null;
        MetadataEntry firstValidUpper = null;
        boolean sameSideConflict = false;

        MetadataEntry maxExclEntry = null;
        MetadataEntry lowerEntry = null;
        MetadataEntry upperEntry = null;
        int localLowerIdx = -1;
        int localUpperIdx = -1;
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

        List<MetadataEntry> entries = metaMap.getMetadataEntryList();
        for (int idx = 0; idx < entries.size(); idx++) {
            var entry = entries.get(idx);
            var entryText = entry.getText();
            var rawVal = entry.getMetadataValue() != null ? entry.getMetadataValue().getText().trim() : null;
            if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                if (discrete) {
                    var kwPsi = StvnMetadataOrderInspection.resolveMetadataEntryKeywordPsi(entry);
                    var quickFixes = createDiscreteBoundQuickFixes(entry, true);
                    holder.registerProblem(
                        kwPsi,
                        StvnDiagnosticFormatter.formatPlugin(
                            discreteDesc + " prohibits bound '"
                                + StvnVocabulary.FACET_KW_MAX_INCL
                                + "'; use half-open bound '"
                                + StvnVocabulary.FACET_KW_MAX_EXCL + "'",
                            StvnDiagnosticCodes.ERR_DISCRETE_BOUND_KIND_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        StvnMetadataOrderInspection.appendReorderFixIfDisordered(kwPsi, quickFixes)
                    );
                }
                if (firstValidUpper != null) {
                    sameSideConflict = true;
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Constraint violation (" + ownerLabel + "): "
                                + StvnVocabulary.FACET_KW_MAX_INCL + " and "
                                + StvnVocabulary.FACET_KW_MAX_EXCL + " are mutually exclusive",
                            StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        StvnMetadataOrderInspection.appendReorderFixIfDisordered(
                            entry,
                            new RemoveRedundantFacetQuickFix(entry, StvnVocabulary.FACET_KW_MAX_INCL, "Remove conflicting facet")
                        )
                    );
                    if (isOnTheFly) {
                        var firstKw = firstValidUpper.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null
                            ? StvnVocabulary.FACET_KW_MAX_EXCL
                            : StvnVocabulary.FACET_KW_MAX_INCL;
                        holder.registerProblem(
                            firstValidUpper,
                            "Remove conflicting facet",
                            ProblemHighlightType.INFORMATION,
                            new RemoveRedundantFacetQuickFix(firstValidUpper, firstKw, "Remove conflicting facet")
                        );
                    }
                    continue;
                }
                firstValidUpper = entry;
                localUpperIdx = idx;
                maxVal = extractBoundBigDecimal(entry);
                maxInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                maxStr = extractBoundString(entry);
                upperEntry = entry;
                upperKw = StvnVocabulary.FACET_KW_MAX_INCL;
                maxRaw = rawVal;
                upperExcl = false;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                if (discrete) {
                    var kwPsi = StvnMetadataOrderInspection.resolveMetadataEntryKeywordPsi(entry);
                    var quickFixes = createDiscreteBoundQuickFixes(entry, false);
                    holder.registerProblem(
                        kwPsi,
                        StvnDiagnosticFormatter.formatPlugin(
                            discreteDesc + " prohibits bound '"
                                + StvnVocabulary.FACET_KW_MIN_EXCL
                                + "'; use half-open bound '"
                                + StvnVocabulary.FACET_KW_MIN_INCL + "'",
                            StvnDiagnosticCodes.ERR_DISCRETE_BOUND_KIND_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        StvnMetadataOrderInspection.appendReorderFixIfDisordered(kwPsi, quickFixes)
                    );
                }
                if (firstValidLower != null) {
                    sameSideConflict = true;
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Constraint violation (" + ownerLabel + "): "
                                + StvnVocabulary.FACET_KW_MIN_INCL + " and "
                                + StvnVocabulary.FACET_KW_MIN_EXCL + " are mutually exclusive",
                            StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        StvnMetadataOrderInspection.appendReorderFixIfDisordered(
                            entry,
                            new RemoveRedundantFacetQuickFix(entry, StvnVocabulary.FACET_KW_MIN_EXCL, "Remove conflicting facet")
                        )
                    );
                    if (isOnTheFly) {
                        var firstKw = firstValidLower.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null
                            ? StvnVocabulary.FACET_KW_MIN_EXCL
                            : StvnVocabulary.FACET_KW_MIN_INCL;
                        holder.registerProblem(
                            firstValidLower,
                            "Remove conflicting facet",
                            ProblemHighlightType.INFORMATION,
                            new RemoveRedundantFacetQuickFix(firstValidLower, firstKw, "Remove conflicting facet")
                        );
                    }
                    continue;
                }
                firstValidLower = entry;
                localLowerIdx = idx;
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                minStr = extractBoundString(entry);
                lowerEntry = entry;
                lowerKw = StvnVocabulary.FACET_KW_MIN_EXCL;
                minRaw = rawVal;
                lowerExcl = true;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null) {
                if (firstValidLower != null) {
                    sameSideConflict = true;
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Constraint violation (" + ownerLabel + "): "
                                + StvnVocabulary.FACET_KW_MIN_INCL + " and "
                                + StvnVocabulary.FACET_KW_MIN_EXCL + " are mutually exclusive",
                            StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        StvnMetadataOrderInspection.appendReorderFixIfDisordered(
                            entry,
                            new RemoveRedundantFacetQuickFix(entry, StvnVocabulary.FACET_KW_MIN_INCL, "Remove conflicting facet")
                        )
                    );
                    if (isOnTheFly) {
                        var firstKw = firstValidLower.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null
                            ? StvnVocabulary.FACET_KW_MIN_EXCL
                            : StvnVocabulary.FACET_KW_MIN_INCL;
                        holder.registerProblem(
                            firstValidLower,
                            "Remove conflicting facet",
                            ProblemHighlightType.INFORMATION,
                            new RemoveRedundantFacetQuickFix(firstValidLower, firstKw, "Remove conflicting facet")
                        );
                    }
                    continue;
                }
                firstValidLower = entry;
                localLowerIdx = idx;
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                minStr = extractBoundString(entry);
                lowerEntry = entry;
                lowerKw = StvnVocabulary.FACET_KW_MIN_INCL;
                minRaw = rawVal;
                lowerExcl = false;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null) {
                if (firstValidUpper != null) {
                    sameSideConflict = true;
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Constraint violation (" + ownerLabel + "): "
                                + StvnVocabulary.FACET_KW_MAX_INCL + " and "
                                + StvnVocabulary.FACET_KW_MAX_EXCL + " are mutually exclusive",
                            StvnDiagnosticCodes.ERR_MUTUALLY_EXCLUSIVE
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        StvnMetadataOrderInspection.appendReorderFixIfDisordered(
                            entry,
                            new RemoveRedundantFacetQuickFix(entry, StvnVocabulary.FACET_KW_MAX_EXCL, "Remove conflicting facet")
                        )
                    );
                    if (isOnTheFly) {
                        var firstKw = firstValidUpper.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null
                            ? StvnVocabulary.FACET_KW_MAX_EXCL
                            : StvnVocabulary.FACET_KW_MAX_INCL;
                        holder.registerProblem(
                            firstValidUpper,
                            "Remove conflicting facet",
                            ProblemHighlightType.INFORMATION,
                            new RemoveRedundantFacetQuickFix(firstValidUpper, firstKw, "Remove conflicting facet")
                        );
                    }
                    continue;
                }
                firstValidUpper = entry;
                localUpperIdx = idx;
                maxExclEntry = entry;
                maxVal = extractBoundBigDecimal(entry);
                maxInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                maxStr = extractBoundString(entry);
                upperEntry = entry;
                upperKw = StvnVocabulary.FACET_KW_MAX_EXCL;
                maxRaw = rawVal;
                upperExcl = true;
            }
        }

        if (sameSideConflict) {
            return;
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
                                minInstant = isDateTime ? extractBoundInstant(pEntry, dateTimeMode) : null;
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
                                maxInstant = isDateTime ? extractBoundInstant(pEntry, dateTimeMode) : null;
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
            ? new LocalQuickFix[]{new SwapInvertedBoundsQuickFix(metaMap, lowerKw, minRaw, upperKw, maxRaw, false)}
            : LocalQuickFix.EMPTY_ARRAY;

        if (minVal != null && maxVal != null && upperEntry != null) {
            int cmp = minVal.compareTo(maxVal);
            if (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl)) {
                registerBoundPairProblem(
                    holder,
                    isOnTheFly,
                    metaMap,
                    localLowerEntry,
                    localLowerIdx,
                    localUpperEntry,
                    localUpperIdx,
                    localBoundTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Discrete interval defines an empty domain [" + minVal.toPlainString() + ", " + maxVal.toPlainString() + ")",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    LocalQuickFix.EMPTY_ARRAY
                );
            } else if (cmp > 0) {
                registerBoundPairProblem(
                    holder,
                    isOnTheFly,
                    metaMap,
                    localLowerEntry,
                    localLowerIdx,
                    localUpperEntry,
                    localUpperIdx,
                    localBoundTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Discrete interval lower bound " + minVal.toPlainString() + " is greater than upper bound " + maxVal.toPlainString(),
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    swapFixes
                );
            }
        } else if (isDateTime && minInstant != null && maxInstant != null && upperEntry != null) {
            int cmp = minInstant.compareTo(maxInstant);
            if (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl)) {
                registerBoundPairProblem(
                    holder,
                    isOnTheFly,
                    metaMap,
                    localLowerEntry,
                    localLowerIdx,
                    localUpperEntry,
                    localUpperIdx,
                    localBoundTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal interval defines an empty domain [" + minStr + ", " + maxStr + ")",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    LocalQuickFix.EMPTY_ARRAY
                );
            } else if (cmp > 0) {
                registerBoundPairProblem(
                    holder,
                    isOnTheFly,
                    metaMap,
                    localLowerEntry,
                    localLowerIdx,
                    localUpperEntry,
                    localUpperIdx,
                    localBoundTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal interval lower bound " + minStr + " is greater than upper bound " + maxStr,
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    swapFixes
                );
            }
        } else if (isString && minStr != null && maxStr != null && upperEntry != null) {
            int cmp = Arrays.compareUnsigned(
                minStr.getBytes(StandardCharsets.UTF_8),
                maxStr.getBytes(StandardCharsets.UTF_8)
            );
            if (cmp == 0 && (lowerExcl || upperExcl)) {
                registerBoundPairProblem(
                    holder,
                    isOnTheFly,
                    metaMap,
                    localLowerEntry,
                    localLowerIdx,
                    localUpperEntry,
                    localUpperIdx,
                    localBoundTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "String interval defines an empty domain",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    LocalQuickFix.EMPTY_ARRAY
                );
            } else if (cmp > 0) {
                registerBoundPairProblem(
                    holder,
                    isOnTheFly,
                    metaMap,
                    localLowerEntry,
                    localLowerIdx,
                    localUpperEntry,
                    localUpperIdx,
                    localBoundTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "String interval lower bound '" + minStr + "' is lexicographically greater than upper bound '" + maxStr + "'",
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    swapFixes
                );
            }
        }
    }

    private static void registerBoundPairProblem(
            ProblemsHolder holder,
            boolean isOnTheFly,
            MetadataMap metaMap,
            @Nullable MetadataEntry localLowerEntry,
            int localLowerIdx,
            @Nullable MetadataEntry localUpperEntry,
            int localUpperIdx,
            @Nullable MetadataEntry singleLocalFallback,
            String formattedMessage,
            LocalQuickFix[] quickFixes
    ) {
        LocalQuickFix[] mergedFixes = StvnMetadataOrderInspection.appendReorderFixIfDisordered(metaMap, quickFixes);
        if (localLowerEntry != null && localUpperEntry != null && localLowerIdx >= 0 && localUpperIdx >= 0) {
            boolean lowerFirst = localLowerIdx <= localUpperIdx;
            MetadataEntry earlier = lowerFirst ? localLowerEntry : localUpperEntry;
            MetadataEntry later = lowerFirst ? localUpperEntry : localLowerEntry;
            if (Math.abs(localLowerIdx - localUpperIdx) == 1) {
                int mapStart = metaMap.getTextRange().getStartOffset();
                var relRange = new TextRange(
                    earlier.getTextRange().getStartOffset() - mapStart,
                    later.getTextRange().getEndOffset() - mapStart
                );
                holder.registerProblem(metaMap, formattedMessage, ProblemHighlightType.GENERIC_ERROR, relRange, mergedFixes);
                return;
            }
            holder.registerProblem(later, formattedMessage, ProblemHighlightType.GENERIC_ERROR, mergedFixes);
            if (isOnTheFly && quickFixes.length > 0) {
                holder.registerProblem(earlier, quickFixes[0].getFamilyName(), ProblemHighlightType.INFORMATION, quickFixes);
            }
            return;
        }
        PsiElement target = singleLocalFallback != null ? singleLocalFallback : metaMap;
        holder.registerProblem(target, formattedMessage, ProblemHighlightType.GENERIC_ERROR, mergedFixes);
    }

    static @Nullable TextRange findLocalIntervalErrorRange(SchemaType schemaType, MetadataMap metaMap) {
        boolean discrete = isDiscreteType(schemaType);
        var baseType = resolveBaseTypeString(schemaType);
        boolean isDateTime = StvnVocabulary.TYPE_DATE_TIME.equals(baseType);
        boolean isString = StvnVocabulary.TYPE_STRING.equals(baseType);
        boolean isFloat = StvnVocabulary.TYPE_FLOAT.equals(baseType);
        if (!discrete && !isDateTime && !isString && !isFloat) {
            return null;
        }
        StvnSchema.DateTimeMode dateTimeMode = isDateTime ? extractEffectiveDateTimeMode(schemaType, metaMap) : null;
        MetadataEntry lowerEntry = null;
        MetadataEntry upperEntry = null;
        MetadataEntry maxExclEntry = null;
        int localLowerIdx = -1;
        int localUpperIdx = -1;
        boolean lowerExcl = false;
        boolean upperExcl = false;
        BigDecimal minVal = null;
        BigDecimal maxVal = null;
        Instant minInstant = null;
        Instant maxInstant = null;
        String minStr = null;
        String maxStr = null;

        List<MetadataEntry> entries = metaMap.getMetadataEntryList();
        for (int idx = 0; idx < entries.size(); idx++) {
            var entry = entries.get(idx);
            var text = entry.getText();
            if (text.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                if (upperEntry != null) return null;
                upperEntry = entry;
                localUpperIdx = idx;
                maxVal = extractBoundBigDecimal(entry);
                maxInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                maxStr = extractBoundString(entry);
                upperExcl = false;
            } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null) {
                if (upperEntry != null) return null;
                upperEntry = entry;
                localUpperIdx = idx;
                maxExclEntry = entry;
                maxVal = extractBoundBigDecimal(entry);
                maxInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                maxStr = extractBoundString(entry);
                upperExcl = true;
            } else if (text.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                if (lowerEntry != null) return null;
                lowerEntry = entry;
                localLowerIdx = idx;
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                minStr = extractBoundString(entry);
                lowerExcl = true;
            } else if (text.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null) {
                if (lowerEntry != null) return null;
                lowerEntry = entry;
                localLowerIdx = idx;
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry, dateTimeMode) : null;
                minStr = extractBoundString(entry);
                lowerExcl = false;
            }
        }
        if (lowerEntry == null || upperEntry == null) {
            return null;
        }
        boolean invalid = false;
        if (minVal != null && maxVal != null) {
            int cmp = minVal.compareTo(maxVal);
            invalid = cmp > 0 || (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl));
        } else if (isDateTime && minInstant != null && maxInstant != null) {
            int cmp = minInstant.compareTo(maxInstant);
            invalid = cmp > 0 || (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl));
        } else if (isString && minStr != null && maxStr != null) {
            int cmp = Arrays.compareUnsigned(
                minStr.getBytes(StandardCharsets.UTF_8),
                maxStr.getBytes(StandardCharsets.UTF_8)
            );
            invalid = cmp > 0 || (cmp == 0 && (lowerExcl || upperExcl));
        }
        if (!invalid) {
            return null;
        }
        boolean lowerFirst = localLowerIdx <= localUpperIdx;
        MetadataEntry earlier = lowerFirst ? lowerEntry : upperEntry;
        MetadataEntry later = lowerFirst ? upperEntry : lowerEntry;
        if (Math.abs(localLowerIdx - localUpperIdx) == 1) {
            return new TextRange(earlier.getTextRange().getStartOffset(), later.getTextRange().getEndOffset());
        }
        return later.getTextRange();
    }

    /**
     * Creates a {@link SwapInvertedBoundsQuickFix} for the given {@link MetadataMap} when both a lower
     * value bound facet and an upper value bound facet are present.
     *
     * @param metaMap metadata map containing inverted value bounds
     * @return quick-fix instance, or {@code null} when bounds are incomplete
     */
    public static @Nullable SwapInvertedBoundsQuickFix createSwapFixForMetadataMap(@Nullable MetadataMap metaMap) {
        return createSwapFixForMetadataMap(metaMap, false);
    }

    /**
     * Creates a {@link SwapInvertedBoundsQuickFix} for the given {@link MetadataMap} when both a lower
     * bound facet and an upper bound facet (value or cardinality) are present.
     *
     * @param metaMap           metadata map containing inverted bounds
     * @param cardinalityBounds {@code true} to inspect {@code #minSize}/{@code #maxSize}, {@code false} for value bounds
     * @return quick-fix instance, or {@code null} when bounds are incomplete
     */
    public static @Nullable SwapInvertedBoundsQuickFix createSwapFixForMetadataMap(
            @Nullable MetadataMap metaMap,
            boolean cardinalityBounds
    ) {
        if (metaMap == null) {
            return null;
        }
        MetadataEntry lowerEntry = null;
        MetadataEntry upperEntry = null;
        String lowerKw = cardinalityBounds ? StvnVocabulary.FACET_KW_MIN_SIZE : StvnVocabulary.FACET_KW_MIN_INCL;
        String upperKw = cardinalityBounds ? StvnVocabulary.FACET_KW_MAX_SIZE : StvnVocabulary.FACET_KW_MAX_EXCL;
        String minRaw = null;
        String maxRaw = null;
        for (var entry : metaMap.getMetadataEntryList()) {
            var entryText = entry.getText();
            var rawVal = entry.getMetadataValue() != null ? entry.getMetadataValue().getText().trim() : null;
            if (cardinalityBounds) {
                if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)
                        || entry.getNode().findChildByType(StvnTypes.KW_MIN_SIZE) != null) {
                    lowerEntry = entry;
                    lowerKw = StvnVocabulary.FACET_KW_MIN_SIZE;
                    minRaw = rawVal;
                } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)
                        || entry.getNode().findChildByType(StvnTypes.KW_MAX_SIZE) != null) {
                    upperEntry = entry;
                    upperKw = StvnVocabulary.FACET_KW_MAX_SIZE;
                    maxRaw = rawVal;
                }
            } else {
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
        }
        if (lowerEntry != null && upperEntry != null && minRaw != null && maxRaw != null) {
            return new SwapInvertedBoundsQuickFix(metaMap, lowerKw, minRaw, upperKw, maxRaw, cardinalityBounds);
        }
        return null;
    }

    static @Nullable MetadataEntry findNonAdjacentEarlierBoundEntry(
            @Nullable MetadataMap metaMap,
            boolean cardinalityBounds
    ) {
        if (metaMap == null) {
            return null;
        }
        MetadataEntry lowerEntry = null;
        MetadataEntry upperEntry = null;
        int lowerIdx = -1;
        int upperIdx = -1;
        List<MetadataEntry> entries = metaMap.getMetadataEntryList();
        for (int idx = 0; idx < entries.size(); idx++) {
            var entry = entries.get(idx);
            var text = entry.getText();
            if (cardinalityBounds) {
                if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)
                        || entry.getNode().findChildByType(StvnTypes.KW_MIN_SIZE) != null) {
                    lowerEntry = entry;
                    lowerIdx = idx;
                } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)
                        || entry.getNode().findChildByType(StvnTypes.KW_MAX_SIZE) != null) {
                    upperEntry = entry;
                    upperIdx = idx;
                }
            } else {
                if (text.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                        || entry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null
                        || text.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                        || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                    lowerEntry = entry;
                    lowerIdx = idx;
                } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                        || entry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null
                        || text.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                        || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                    upperEntry = entry;
                    upperIdx = idx;
                }
            }
        }
        if (lowerEntry != null && upperEntry != null && lowerIdx >= 0 && upperIdx >= 0
                && Math.abs(lowerIdx - upperIdx) > 1) {
            return lowerIdx < upperIdx ? lowerEntry : upperEntry;
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

    private static StvnSchema.@Nullable DateTimeMode extractEffectiveDateTimeMode(
            SchemaType schemaType,
            MetadataMap localMap
    ) {
        int localCount = 0;
        StvnSchema.DateTimeMode localMode = null;
        for (var entry : localMap.getMetadataEntryList()) {
            var text = entry.getText();
            if (text.startsWith(StvnVocabulary.FACET_KW_OFFSET)
                    || entry.getNode().findChildByType(StvnTypes.KW_OFFSET) != null) {
                localMode = StvnSchema.DateTimeMode.OFFSET;
                localCount++;
            } else if (text.startsWith(StvnVocabulary.FACET_KW_ZONED)
                    || entry.getNode().findChildByType(StvnTypes.KW_ZONED) != null) {
                localMode = StvnSchema.DateTimeMode.ZONED;
                localCount++;
            } else if (text.startsWith(StvnVocabulary.FACET_KW_AUDITED)
                    || entry.getNode().findChildByType(StvnTypes.KW_AUDITED) != null) {
                localMode = StvnSchema.DateTimeMode.AUDITED;
                localCount++;
            }
        }
        if (localCount > 1) {
            return null;
        }
        if (localCount == 1) {
            return localMode;
        }
        String inheritedFlag = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(schemaType);
        if (inheritedFlag == null) {
            var terminalSchema = StvnTypeResolver.resolveNominalSchema(schemaType);
            if (terminalSchema != null) {
                inheritedFlag = StvnTypeResolver.extractDateTimeModeFlagFromSchemaType(terminalSchema);
            }
        }
        if (StvnVocabulary.FACET_KW_OFFSET.equals(inheritedFlag)) {
            return StvnSchema.DateTimeMode.OFFSET;
        }
        if (StvnVocabulary.FACET_KW_ZONED.equals(inheritedFlag)) {
            return StvnSchema.DateTimeMode.ZONED;
        }
        if (StvnVocabulary.FACET_KW_AUDITED.equals(inheritedFlag)) {
            return StvnSchema.DateTimeMode.AUDITED;
        }
        return null;
    }

    private static @Nullable Instant extractBoundInstant(
            MetadataEntry entry,
            StvnSchema.@Nullable DateTimeMode mode
    ) {
        if (mode == null) {
            return null;
        }
        var mv = entry.getMetadataValue();
        if (mv == null) {
            return null;
        }
        String rawText = mv.getText().trim();
        if (rawText.startsWith("\"\"\"")) {
            return null;
        }
        String decoded = extractBoundString(entry);
        if (decoded == null || decoded.isEmpty()) {
            return null;
        }
        String validationToken = mv.getStringLiteral() != null
            ? rawText
            : StvnPsiLiteralEvaluator.formatCanonicalQuotedString(decoded);
        if (StvnCompiler.validateDateTimeLiteral(validationToken, mode).isPresent()) {
            return null;
        }
        try {
            return switch (mode) {
                case OFFSET -> OffsetDateTime.parse(decoded).toInstant();
                case AUDITED -> {
                    int bracketIdx = decoded.indexOf('[');
                    yield OffsetDateTime.parse(decoded.substring(0, bracketIdx)).toInstant();
                }
                case ZONED -> {
                    int openBracket = decoded.indexOf('[');
                    int closeBracket = decoded.lastIndexOf(']');
                    var ldt = LocalDateTime.parse(decoded.substring(0, openBracket));
                    var zone = ZoneId.of(decoded.substring(openBracket + 1, closeBracket));
                    yield ldt.atZone(zone).toInstant();
                }
            };
        } catch (RuntimeException ex) {
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

    /**
     * Builds the ordered column-preserving quick-fixes for a prohibited discrete bound entry
     * ({@code #maxIncl} or {@code #minExcl}), shared across Pass B ({@link StvnDiscreteIntervalInspection})
     * and Pass A ({@code StvnPayloadValueQuickFixProvider}).
     *
     * @param entry     enclosing metadata bound entry
     * @param isMaxIncl {@code true} when converting {@code #maxIncl} to {@code #maxExcl};
     *                  {@code false} when converting {@code #minExcl} to {@code #minIncl}
     * @return ordered array of quick-fixes implementing both {@link LocalQuickFix} and
     *         {@link com.intellij.codeInsight.intention.IntentionAction}
     */
    public static LocalQuickFixAndIntentionActionOnPsiElement[] createDiscreteBoundQuickFixes(
            MetadataEntry entry,
            boolean isMaxIncl
    ) {
        if (isMaxIncl) {
            var replaceKwFix = new ReplaceBoundKeywordQuickFix(
                entry,
                StvnVocabulary.FACET_KW_MAX_INCL,
                StvnVocabulary.FACET_KW_MAX_EXCL,
                "Replace '" + StvnVocabulary.FACET_KW_MAX_INCL
                    + "' with half-open bound '" + StvnVocabulary.FACET_KW_MAX_EXCL + "'"
            );
            return canIncrementBound(entry)
                ? new LocalQuickFixAndIntentionActionOnPsiElement[]{
                    new ConvertMaxInclToMaxExclQuickFix(entry),
                    replaceKwFix
                }
                : new LocalQuickFixAndIntentionActionOnPsiElement[]{replaceKwFix};
        }
        var replaceKwFix = new ReplaceBoundKeywordQuickFix(
            entry,
            StvnVocabulary.FACET_KW_MIN_EXCL,
            StvnVocabulary.FACET_KW_MIN_INCL,
            "Replace '" + StvnVocabulary.FACET_KW_MIN_EXCL
                + "' with closed bound '" + StvnVocabulary.FACET_KW_MIN_INCL + "'"
        );
        return canIncrementBound(entry)
            ? new LocalQuickFixAndIntentionActionOnPsiElement[]{
                new ConvertMinExclToMinInclQuickFix(entry),
                replaceKwFix
            }
            : new LocalQuickFixAndIntentionActionOnPsiElement[]{replaceKwFix};
    }

    private static void replacePreviewElementText(Project project, PsiElement target, String newText) {
        if (target instanceof LeafPsiElement leaf) {
            leaf.replaceWithText(newText);
            return;
        }
        PsiElement firstLeaf = PsiTreeUtil.getDeepestFirst(target);
        PsiElement lastLeaf = PsiTreeUtil.getDeepestLast(target);
        if (firstLeaf == lastLeaf && firstLeaf instanceof LeafPsiElement singleLeaf) {
            singleLeaf.replaceWithText(newText);
            return;
        }
        var dummyEntry = StvnElementFactory.createMetadataEntry(
            project,
            StvnVocabulary.FACET_KW_MIN_INCL + " " + newText
        );
        if (target instanceof MetadataValue && dummyEntry.getMetadataValue() != null) {
            target.replace(dummyEntry.getMetadataValue());
        }
    }

    private static void replaceBoundTokensInPlace(
            Project project,
            PsiFile file,
            MetadataEntry entry,
            String oldKw,
            String newKw,
            @Nullable String replacementValueText
    ) {
        if (!entry.isValid()) {
            return;
        }
        var kwPsi = StvnMetadataOrderInspection.resolveMetadataEntryKeywordPsi(entry);
        var valPsi = entry.getMetadataValue();
        if (IntentionPreviewUtils.isPreviewElement(entry)) {
            if (replacementValueText != null && valPsi != null) {
                replacePreviewElementText(project, valPsi, replacementValueText);
            }
            if (kwPsi != entry || entry.getText().trim().equals(oldKw)) {
                replacePreviewElementText(project, kwPsi, newKw);
            } else {
                PsiElement firstLeaf = PsiTreeUtil.getDeepestFirst(entry);
                if (firstLeaf instanceof LeafPsiElement leaf && oldKw.equals(leaf.getText())) {
                    leaf.replaceWithText(newKw);
                }
            }
            return;
        }
        var doc = file.getViewProvider().getDocument();
        if (doc != null) {
            if (replacementValueText != null && valPsi != null) {
                var valRange = valPsi.getTextRange();
                doc.replaceString(valRange.getStartOffset(), valRange.getEndOffset(), replacementValueText);
            }
            if (kwPsi != entry || entry.getText().trim().equals(oldKw)) {
                var kwRange = kwPsi.getTextRange();
                doc.replaceString(kwRange.getStartOffset(), kwRange.getEndOffset(), newKw);
            } else {
                var entryRange = entry.getTextRange();
                String rawEntry = doc.getText(entryRange);
                if (rawEntry.startsWith(oldKw)) {
                    doc.replaceString(entryRange.getStartOffset(), entryRange.getStartOffset() + oldKw.length(), newKw);
                }
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc);
            return;
        }
        String fallbackVal = replacementValueText != null
            ? replacementValueText
            : (valPsi != null ? valPsi.getText().trim() : null);
        String fallbackEntryText = fallbackVal != null
            ? newKw + " " + fallbackVal
            : entry.getText().trim().replaceFirst("^" + java.util.regex.Pattern.quote(oldKw), newKw);
        entry.replace(StvnElementFactory.createMetadataEntry(project, fallbackEntryText));
    }

    private static final class ConvertMaxInclToMaxExclQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        ConvertMaxInclToMaxExclQuickFix(MetadataEntry entry) {
            super(entry);
        }

        @Override
        public @NotNull String getText() {
            return getFamilyName();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Convert '" + StvnVocabulary.FACET_KW_MAX_INCL + " <N>' to '"
                + StvnVocabulary.FACET_KW_MAX_EXCL + " <N+1>'";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            var entry = startElement instanceof MetadataEntry me
                ? me
                : PsiTreeUtil.getParentOfType(startElement, MetadataEntry.class, false);
            if (entry == null || !entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            replaceBoundTokensInPlace(
                project,
                file,
                entry,
                StvnVocabulary.FACET_KW_MAX_INCL,
                StvnVocabulary.FACET_KW_MAX_EXCL,
                nextVal
            );
        }
    }

    private static final class ConvertMinExclToMinInclQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        ConvertMinExclToMinInclQuickFix(MetadataEntry entry) {
            super(entry);
        }

        @Override
        public @NotNull String getText() {
            return getFamilyName();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Convert '" + StvnVocabulary.FACET_KW_MIN_EXCL + " <N>' to '"
                + StvnVocabulary.FACET_KW_MIN_INCL + " <N+1>'";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            var entry = startElement instanceof MetadataEntry me
                ? me
                : PsiTreeUtil.getParentOfType(startElement, MetadataEntry.class, false);
            if (entry == null || !entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            replaceBoundTokensInPlace(
                project,
                file,
                entry,
                StvnVocabulary.FACET_KW_MIN_EXCL,
                StvnVocabulary.FACET_KW_MIN_INCL,
                nextVal
            );
        }
    }

    private static final class ReplaceBoundKeywordQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String oldKw;
        @SafeFieldForPreview
        private final String newKw;
        @SafeFieldForPreview
        private final String label;

        ReplaceBoundKeywordQuickFix(MetadataEntry entry, String oldKw, String newKw, String label) {
            super(entry);
            this.oldKw = oldKw;
            this.newKw = newKw;
            this.label = label;
        }

        @Override
        public @NotNull String getText() {
            return label;
        }

        @Override
        public @NotNull String getFamilyName() {
            return label;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            var entry = startElement instanceof MetadataEntry me
                ? me
                : PsiTreeUtil.getParentOfType(startElement, MetadataEntry.class, false);
            if (entry == null || !entry.isValid()) return;
            replaceBoundTokensInPlace(project, file, entry, oldKw, newKw, null);
        }
    }

    /**
     * Quick-fix that swaps inverted lower and upper bound values while preserving each facet's bound keyword.
     */
    public static final class SwapInvertedBoundsQuickFix
            extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String lowerKw;
        @SafeFieldForPreview
        private final String lowerValText;
        @SafeFieldForPreview
        private final String upperKw;
        @SafeFieldForPreview
        private final String upperValText;
        @SafeFieldForPreview
        private final boolean cardinalityBounds;
        @SafeFieldForPreview
        private final String label;

        /**
         * Constructs a new {@link SwapInvertedBoundsQuickFix}.
         *
         * @param metaMap           enclosing metadata map PSI element
         * @param lowerKw           lower bound facet keyword
         * @param lowerValText      authored lower bound value text
         * @param upperKw           upper bound facet keyword
         * @param upperValText      authored upper bound value text
         * @param cardinalityBounds {@code true} for {@code #minSize}/{@code #maxSize}, {@code false} for value bounds
         */
        public SwapInvertedBoundsQuickFix(
                MetadataMap metaMap,
                String lowerKw,
                String lowerValText,
                String upperKw,
                String upperValText,
                boolean cardinalityBounds
        ) {
            super(metaMap);
            this.lowerKw = lowerKw;
            this.lowerValText = lowerValText;
            this.upperKw = upperKw;
            this.upperValText = upperValText;
            this.cardinalityBounds = cardinalityBounds;
            this.label = "Swap inverted bounds ('" + lowerKw + " " + upperValText
                + "' and '" + upperKw + " " + lowerValText + "')";
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
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetadataMap targetMap = startElement instanceof MetadataMap mm
                ? mm
                : PsiTreeUtil.getParentOfType(startElement, MetadataMap.class, false);
            if (targetMap == null) {
                return;
            }
            MetadataEntry lower = null;
            MetadataEntry upper = null;
            for (var entry : targetMap.getMetadataEntryList()) {
                var text = entry.getText();
                if (cardinalityBounds) {
                    if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)
                            || entry.getNode().findChildByType(StvnTypes.KW_MIN_SIZE) != null) {
                        lower = entry;
                    } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)
                            || entry.getNode().findChildByType(StvnTypes.KW_MAX_SIZE) != null) {
                        upper = entry;
                    }
                } else {
                    if (text.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                            || entry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null
                            || text.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                            || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                        lower = entry;
                    } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                            || entry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null
                            || text.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                            || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                        upper = entry;
                    }
                }
            }
            if (lower == null || upper == null) {
                return;
            }
            var lowerValPsi = lower.getMetadataValue();
            var upperValPsi = upper.getMetadataValue();
            if (IntentionPreviewUtils.isPreviewElement(targetMap) && lowerValPsi != null && upperValPsi != null) {
                if (lowerValPsi.getTextRange().getStartOffset() < upperValPsi.getTextRange().getStartOffset()) {
                    replacePreviewElementText(project, upperValPsi, lowerValText);
                    replacePreviewElementText(project, lowerValPsi, upperValText);
                } else {
                    replacePreviewElementText(project, lowerValPsi, upperValText);
                    replacePreviewElementText(project, upperValPsi, lowerValText);
                }
                return;
            }
            var doc = file.getViewProvider().getDocument();
            if (doc != null && lowerValPsi != null && upperValPsi != null && !IntentionPreviewUtils.isPreviewElement(targetMap)) {
                var lowerValRange = lowerValPsi.getTextRange();
                var upperValRange = upperValPsi.getTextRange();
                if (lowerValRange.getStartOffset() < upperValRange.getStartOffset()) {
                    doc.replaceString(upperValRange.getStartOffset(), upperValRange.getEndOffset(), lowerValText);
                    doc.replaceString(lowerValRange.getStartOffset(), lowerValRange.getEndOffset(), upperValText);
                } else {
                    doc.replaceString(lowerValRange.getStartOffset(), lowerValRange.getEndOffset(), upperValText);
                    doc.replaceString(upperValRange.getStartOffset(), upperValRange.getEndOffset(), lowerValText);
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                return;
            }
            String lowerReplacementText = lowerKw + " " + upperValText;
            String upperReplacementText = upperKw + " " + lowerValText;
            if (doc != null && !IntentionPreviewUtils.isPreviewElement(targetMap)) {
                var lowerRange = lower.getTextRange();
                var upperRange = upper.getTextRange();
                if (lowerRange.getStartOffset() < upperRange.getStartOffset()) {
                    doc.replaceString(upperRange.getStartOffset(), upperRange.getEndOffset(), upperReplacementText);
                    doc.replaceString(lowerRange.getStartOffset(), lowerRange.getEndOffset(), lowerReplacementText);
                } else {
                    doc.replaceString(lowerRange.getStartOffset(), lowerRange.getEndOffset(), lowerReplacementText);
                    doc.replaceString(upperRange.getStartOffset(), upperRange.getEndOffset(), upperReplacementText);
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc);
                return;
            }
            var newLower = StvnElementFactory.createMetadataEntry(project, lowerReplacementText);
            var newUpper = StvnElementFactory.createMetadataEntry(project, upperReplacementText);
            lower.replace(newLower);
            upper.replace(newUpper);
        }
    }

    private static String resolveIntervalOwnerLabel(MetadataMap metaMap, @Nullable String fallbackBaseType) {
        var typeDef = PsiTreeUtil.getParentOfType(metaMap, TypeDefinition.class);
        if (typeDef != null && typeDef.getTypeKeyword() != null) {
            return typeDef.getTypeKeyword().getText().trim();
        }
        var constDef = PsiTreeUtil.getParentOfType(metaMap, ConstantDefinition.class);
        if (constDef != null && constDef.getValueKeyword() != null) {
            return constDef.getValueKeyword().getText().trim();
        }
        if (PsiTreeUtil.getParentOfType(metaMap, org.stvnadore.psi.TypeEntry.class) != null) {
            return "<" + StvnVocabulary.KEYWORD_TYPE + ">";
        }
        return fallbackBaseType != null ? fallbackBaseType : "";
    }

    private static String formatDiscreteTypeDescription(@Nullable String baseType) {
        if (StvnVocabulary.TYPE_TIME_EPOCH.equals(baseType)) {
            return "Discrete temporal type '" + StvnVocabulary.TYPE_TIME_EPOCH + "'";
        }
        if (StvnVocabulary.TYPE_DATE_TIME.equals(baseType)) {
            return "Discrete temporal type '" + StvnVocabulary.TYPE_DATE_TIME + "'";
        }
        if (StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
            return "Discrete exact float '{ " + StvnVocabulary.FACET_KW_EXACT + " } " + StvnVocabulary.TYPE_FLOAT + "'";
        }
        return "Discrete type '" + StvnVocabulary.TYPE_INT + "'";
    }
}
