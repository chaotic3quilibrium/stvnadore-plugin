package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
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
import org.stvnadore.psi.*;

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
        MetadataEntry upperEntry = null;
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
            if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                if (discrete) {
                    var quickFixes = canIncrementBound(entry)
                        ? new LocalQuickFix[]{new ConvertMaxInclToMaxExclQuickFix(entry)}
                        : LocalQuickFix.EMPTY_ARRAY;
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Discrete types reject closed upper bound '#maxIncl'; use half-open bound '#maxExcl'",
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
                upperExcl = false;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                if (discrete) {
                    var quickFixes = canIncrementBound(entry)
                        ? new LocalQuickFix[]{new ConvertMinExclToMinInclQuickFix(entry)}
                        : LocalQuickFix.EMPTY_ARRAY;
                    holder.registerProblem(
                        entry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Discrete types reject open lower bound '#minExcl'; use half-open bound '#minIncl'",
                            StvnDiagnosticCodes.ERR_DISCRETE_BOUND_KIND_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        quickFixes
                    );
                }
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry) : null;
                minStr = extractBoundString(entry);
                lowerExcl = true;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MIN_INCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MIN_INCL) != null) {
                minVal = extractBoundBigDecimal(entry);
                minInstant = isDateTime ? extractBoundInstant(entry) : null;
                minStr = extractBoundString(entry);
                lowerExcl = false;
            } else if (entryText.startsWith(StvnVocabulary.FACET_KW_MAX_EXCL)
                    || entry.getNode().findChildByType(StvnTypes.KW_MAX_EXCL) != null) {
                maxExclEntry = entry;
                maxVal = extractBoundBigDecimal(entry);
                maxInstant = isDateTime ? extractBoundInstant(entry) : null;
                maxStr = extractBoundString(entry);
                upperEntry = entry;
                upperExcl = true;
            }
        }

        if (minVal != null && maxVal != null && upperEntry != null) {
            int cmp = minVal.compareTo(maxVal);
            if (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl)) {
                holder.registerProblem(
                    maxExclEntry != null ? maxExclEntry : upperEntry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Discrete interval defines an empty domain [" + minVal.toPlainString() + ", " + maxVal.toPlainString() + ")",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            } else if (cmp > 0) {
                holder.registerProblem(
                    upperEntry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Discrete interval lower bound " + minVal.toPlainString() + " is greater than upper bound " + maxVal.toPlainString(),
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            }
        } else if (isDateTime && minInstant != null && maxInstant != null && upperEntry != null) {
            int cmp = minInstant.compareTo(maxInstant);
            if (cmp == 0 && (maxExclEntry != null || lowerExcl || upperExcl)) {
                holder.registerProblem(
                    maxExclEntry != null ? maxExclEntry : upperEntry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal interval defines an empty domain [" + minStr + ", " + maxStr + ")",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            } else if (cmp > 0) {
                holder.registerProblem(
                    upperEntry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal interval lower bound " + minStr + " is greater than upper bound " + maxStr,
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            }
        } else if (isString && minStr != null && maxStr != null && upperEntry != null) {
            int cmp = minStr.compareTo(maxStr);
            if (cmp == 0 && (lowerExcl || upperExcl)) {
                holder.registerProblem(
                    upperEntry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "String interval defines an empty domain",
                        StvnDiagnosticCodes.ERR_EMPTY_INTERVAL_DOMAIN
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            } else if (cmp > 0) {
                holder.registerProblem(
                    upperEntry,
                    StvnDiagnosticFormatter.formatPlugin(
                        "String interval lower bound '" + minStr + "' is lexicographically greater than upper bound '" + maxStr + "'",
                        StvnDiagnosticCodes.ERR_INVERTED_RANGE
                    ),
                    ProblemHighlightType.GENERIC_ERROR
                );
            }
        }
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
        var intLit = val.getIntegerLiteral();
        if (intLit != null) {
            return StvnPsiLiteralEvaluator.parseBigInteger(intLit.getText())
                .map(BigDecimal::new)
                .orElse(null);
        }
        var floatLit = val.getFloatLiteral();
        if (floatLit != null) {
            return StvnPsiLiteralEvaluator.parseBigDecimal(floatLit.getText()).orElse(null);
        }
        var text = val.getText().trim();
        if (!text.isEmpty()) {
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return StvnPsiLiteralEvaluator.parseBigDecimal(text).orElse(null);
            }
            return StvnPsiLiteralEvaluator.parseBigInteger(text)
                .map(BigDecimal::new)
                .orElse(null);
        }
        return null;
    }

    private static @Nullable String extractBoundString(MetadataEntry entry) {
        var val = entry.getMetadataValue();
        if (val == null) return null;
        var strLit = val.getStringLiteral();
        if (strLit != null) {
            return StvnPsiLiteralEvaluator.decodeStringLiteral(strLit.getText(), false).orElse(null);
        }
        return StvnPsiLiteralEvaluator.decodeStringLiteral(val.getText().trim(), false).orElse(null);
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
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new java.util.HashSet<>());
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
            return "Convert '#maxIncl <N>' to '#maxExcl <N+1>'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            var replacement = StvnElementFactory.createMetadataEntry(project, "#maxExcl " + nextVal);
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
            return "Convert '#minExcl <N>' to '#minIncl <N+1>'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            var replacement = StvnElementFactory.createMetadataEntry(project, "#minIncl " + nextVal);
            entry.replace(replacement);
        }
    }
}
