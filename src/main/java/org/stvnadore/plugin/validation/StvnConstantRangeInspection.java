package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.documentation.StvnExemplarValueGenerator;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.Visitor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;

/**
 * Validates that integer and continuous floating-point literals assigned to typed constants
 * fit within the physical bit-width and explicit range boundaries of their declared schema types.
 */
@NullMarked
public final class StvnConstantRangeInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnConstantRangeInspection instance.
     */
    public StvnConstantRangeInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)) {
                    return;
                }
                var schemaType = constDef.getSchemaType();
                var value = constDef.getValue();
                if (schemaType == null || value == null) {
                    return;
                }

                var baseType = StvnSchemaFormatter.formatSchema(schemaType).trim();
                boolean isUnsigned = false;
                BigInteger rawBitWidth = null;

                var meta = schemaType.getMetadataMap();
                var cleanType = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
                boolean isInt = StvnVocabulary.TYPE_INT.equals(cleanType);
                boolean isEpoch = StvnVocabulary.TYPE_TIME_EPOCH.equals(cleanType);
                boolean isFloat = StvnVocabulary.TYPE_FLOAT.equals(cleanType);
                SchemaType resolved = null;
                if (meta == null || (!isInt && !isEpoch && !isFloat)) {
                    resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
                    if (resolved != null) {
                        if (meta == null) {
                            meta = resolved.getMetadataMap();
                        }
                        cleanType = StvnSchemaFormatter.formatCleanSchema(resolved).trim();
                        isInt = StvnVocabulary.TYPE_INT.equals(cleanType);
                        isEpoch = StvnVocabulary.TYPE_TIME_EPOCH.equals(cleanType);
                        isFloat = StvnVocabulary.TYPE_FLOAT.equals(cleanType);
                    }
                }

                if (!isInt && !isEpoch && !isFloat) {
                    return;
                }

                var entries = new ArrayList<MetadataEntry>();
                if (meta != null) {
                    entries.addAll(meta.getMetadataEntryList());
                }
                if (resolved != null && resolved.getMetadataMap() != null && resolved.getMetadataMap() != meta) {
                    entries.addAll(resolved.getMetadataMap().getMetadataEntryList());
                }

                String epochScale = null;
                boolean isExact = false;
                for (var entry : entries) {
                    var txt = entry.getText().trim();
                    if (txt.equals(StvnVocabulary.FACET_KW_UNSIGNED)
                            || txt.startsWith(StvnVocabulary.FACET_KW_UNSIGNED + " ")) {
                        isUnsigned = true;
                    } else if (txt.startsWith(StvnVocabulary.FACET_KW_SIZE + " ")
                            || txt.equals(StvnVocabulary.FACET_KW_SIZE)) {
                        var parts = txt.split("\\s+");
                        if (parts.length >= 2 && rawBitWidth == null) {
                            rawBitWidth = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(entry, parts[1]).orElse(null);
                        }
                    } else if (txt.equals(StvnVocabulary.FACET_KW_EXACT)
                            || txt.startsWith(StvnVocabulary.FACET_KW_EXACT + " ")) {
                        isExact = true;
                    } else if (txt.equals(StvnVocabulary.FACET_KW_SCALE_NS)
                            || txt.startsWith(StvnVocabulary.FACET_KW_SCALE_NS + " ")) {
                        if (epochScale == null) epochScale = "ns";
                    } else if (txt.equals(StvnVocabulary.FACET_KW_SCALE_S)
                            || txt.startsWith(StvnVocabulary.FACET_KW_SCALE_S + " ")
                            || txt.equals(StvnVocabulary.FACET_KW_SCALE_MS)
                            || txt.startsWith(StvnVocabulary.FACET_KW_SCALE_MS + " ")
                            || txt.equals(StvnVocabulary.FACET_KW_SCALE_US)
                            || txt.startsWith(StvnVocabulary.FACET_KW_SCALE_US + " ")) {
                        if (epochScale == null) epochScale = "standard";
                    }
                }

                Map<String, String> lineageMeta = StvnExemplarValueGenerator.extractLineageMetadata(schemaType);
                if (constDef.getMetadataMap() != null) {
                    for (var entry : constDef.getMetadataMap().getMetadataEntryList()) {
                        String kw = StvnMetadataOrderInspection.extractFacetKeyword(entry);
                        String valText = entry.getText().trim().substring(kw.length()).trim();
                        lineageMeta.put(kw, valText.isEmpty() ? "#true" : valText);
                    }
                }
                if ("#true".equals(lineageMeta.get(StvnVocabulary.FACET_KW_UNSIGNED))) {
                    isUnsigned = true;
                }
                if ("#true".equals(lineageMeta.get(StvnVocabulary.FACET_KW_EXACT))) {
                    isExact = true;
                }
                if (rawBitWidth == null && lineageMeta.containsKey(StvnVocabulary.FACET_KW_SIZE)) {
                    rawBitWidth = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(
                        constDef,
                        lineageMeta.get(StvnVocabulary.FACET_KW_SIZE)
                    ).orElse(null);
                }

                if (isEpoch) {
                    if (rawBitWidth == null) {
                        rawBitWidth = BigInteger.valueOf("ns".equals(epochScale) ? 128L : 64L);
                    }
                }

                // Continuous and exact float evaluation branch
                if (isFloat) {
                    PsiElement floatTarget = value.getFloatLiteral() != null ? value.getFloatLiteral() : value;
                    var litText = floatTarget.getText();
                    var floatValOpt = StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(value, litText);
                    if (floatValOpt.isEmpty()) {
                        return;
                    }
                    BigDecimal floatVal = floatValOpt.get();

                    BigDecimal explicitMinIncl = parseDecimalOrNull(constDef, lineageMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                    BigDecimal explicitMinExcl = parseDecimalOrNull(constDef, lineageMeta.get(StvnVocabulary.FACET_KW_MIN_EXCL));
                    BigDecimal explicitMaxIncl = parseDecimalOrNull(constDef, lineageMeta.get(StvnVocabulary.FACET_KW_MAX_INCL));
                    BigDecimal explicitMaxExcl = parseDecimalOrNull(constDef, lineageMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));
                    if ((explicitMinIncl != null && floatVal.compareTo(explicitMinIncl) < 0)
                            || (explicitMinExcl != null && floatVal.compareTo(explicitMinExcl) <= 0)
                            || (explicitMaxIncl != null && floatVal.compareTo(explicitMaxIncl) > 0)
                            || (explicitMaxExcl != null && floatVal.compareTo(explicitMaxExcl) >= 0)) {
                        String constName = constDef.getValueKeyword() != null ? constDef.getValueKeyword().getText() : "#CONST";
                        String msg = StvnDiagnosticFormatter.formatPlugin(
                            "Constraint violation (" + constName + "): Float literal " + litText + " violates explicit range bounds on " + baseType,
                            StvnDiagnosticCodes.ERR_CONSTANT_FLOAT_OVERFLOW
                        );
                        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                            value,
                            StvnDiagnosticCodes.ERR_CONSTANT_FLOAT_OVERFLOW,
                            msg,
                            Clock.systemDefaultZone()
                        ).toArray(LocalQuickFix.EMPTY_ARRAY);
                        holder.registerProblem(
                            floatTarget,
                            msg,
                            ProblemHighlightType.GENERIC_ERROR,
                            fixes
                        );
                        return;
                    }

                    if (isExact) {
                        return;
                    }
                    if (rawBitWidth != null
                        && !rawBitWidth.equals(BigInteger.valueOf(32L))
                        && !rawBitWidth.equals(BigInteger.valueOf(64L))) {
                        return;
                    }
                    int floatBits = (rawBitWidth != null && rawBitWidth.intValue() == 32) ? 32 : 64;
                    BigDecimal minBound = (floatBits == 32) ? StvnVocabulary.FLOAT32_MIN : StvnVocabulary.FLOAT64_MIN;
                    BigDecimal maxBound = (floatBits == 32) ? StvnVocabulary.FLOAT32_MAX : StvnVocabulary.FLOAT64_MAX;

                    if (floatVal.compareTo(minBound) < 0 || floatVal.compareTo(maxBound) > 0) {
                        String targetType = (rawBitWidth != null)
                            ? "{ " + StvnVocabulary.FACET_KW_SIZE + " " + rawBitWidth + " } " + cleanType
                            : cleanType;
                        String msg = StvnDiagnosticFormatter.formatPlugin(
                            "Float literal " + litText + " out of range for " + targetType + " [" + minBound + ", " + maxBound + "]",
                            StvnDiagnosticCodes.ERR_CONSTANT_FLOAT_OVERFLOW
                        );
                        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                            value,
                            StvnDiagnosticCodes.ERR_CONSTANT_FLOAT_OVERFLOW,
                            msg,
                            Clock.systemDefaultZone()
                        ).toArray(LocalQuickFix.EMPTY_ARRAY);
                        holder.registerProblem(
                            floatTarget,
                            msg,
                            ProblemHighlightType.GENERIC_ERROR,
                            fixes
                        );
                    }
                    return;
                }

                PsiElement intTarget = value.getIntegerLiteral() != null ? value.getIntegerLiteral() : value;
                var litText = intTarget.getText();
                var valOpt = StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(value, litText);
                if (valOpt.isEmpty()) {
                    return;
                }
                BigInteger val = valOpt.get();

                BigInteger explicitMinIncl = parseIntOrNull(constDef, lineageMeta.get(StvnVocabulary.FACET_KW_MIN_INCL));
                BigInteger explicitMaxExcl = parseIntOrNull(constDef, lineageMeta.get(StvnVocabulary.FACET_KW_MAX_EXCL));

                if (rawBitWidth == null || rawBitWidth.compareTo(BigInteger.ONE) < 0) {
                    if ((explicitMinIncl != null && val.compareTo(explicitMinIncl) < 0)
                            || (explicitMaxExcl != null && val.compareTo(explicitMaxExcl) >= 0)) {
                        registerExplicitIntBoundViolation(holder, constDef, value, intTarget, val, explicitMinIncl, explicitMaxExcl, baseType);
                    }
                    return;
                }

                if (rawBitWidth.compareTo(BigInteger.valueOf(StvnPsiLiteralEvaluator.MAX_EAGER_SHIFT_BIT_WIDTH)) <= 0) {
                    int bitWidth = rawBitWidth.intValueExact();
                    var min = isUnsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
                    var max = isUnsigned
                        ? BigInteger.ONE.shiftLeft(bitWidth).subtract(BigInteger.ONE)
                        : BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(BigInteger.ONE);

                    if (val.compareTo(min) < 0 || val.compareTo(max) > 0) {
                        String msg = StvnDiagnosticFormatter.formatPlugin(
                            "Integer literal " + val + " out of range for " + baseType + " [" + min + ", " + max + "]",
                            StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW
                        );
                        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                            value,
                            StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW,
                            msg,
                            Clock.systemDefaultZone()
                        ).toArray(LocalQuickFix.EMPTY_ARRAY);
                        holder.registerProblem(
                            intTarget,
                            msg,
                            ProblemHighlightType.GENERIC_ERROR,
                            fixes
                        );
                        return;
                    }
                    if ((explicitMinIncl != null && val.compareTo(explicitMinIncl) < 0)
                            || (explicitMaxExcl != null && val.compareTo(explicitMaxExcl) >= 0)) {
                        registerExplicitIntBoundViolation(holder, constDef, value, intTarget, val, explicitMinIncl, explicitMaxExcl, baseType);
                    }
                } else {
                    boolean outOfRange = isUnsigned
                        ? (val.signum() < 0 || BigInteger.valueOf(val.bitLength()).compareTo(rawBitWidth) > 0)
                        : (BigInteger.valueOf(val.bitLength()).compareTo(rawBitWidth.subtract(BigInteger.ONE)) > 0);
                    if (outOfRange) {
                        String rangeStr = isUnsigned
                            ? "[0, 2^" + rawBitWidth + " - 1]"
                            : "[-2^" + rawBitWidth.subtract(BigInteger.ONE) + ", 2^" + rawBitWidth.subtract(BigInteger.ONE) + " - 1]";
                        String msg = StvnDiagnosticFormatter.formatPlugin(
                            "Integer literal " + val + " out of range for " + baseType + " " + rangeStr,
                            StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW
                        );
                        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                            value,
                            StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW,
                            msg,
                            Clock.systemDefaultZone()
                        ).toArray(LocalQuickFix.EMPTY_ARRAY);
                        holder.registerProblem(
                            intTarget,
                            msg,
                            ProblemHighlightType.GENERIC_ERROR,
                            fixes
                        );
                        return;
                    }
                    if ((explicitMinIncl != null && val.compareTo(explicitMinIncl) < 0)
                            || (explicitMaxExcl != null && val.compareTo(explicitMaxExcl) >= 0)) {
                        registerExplicitIntBoundViolation(holder, constDef, value, intTarget, val, explicitMinIncl, explicitMaxExcl, baseType);
                    }
                }
            }
        };
    }

    private static void registerExplicitIntBoundViolation(
            @NotNull ProblemsHolder holder,
            @NotNull ConstantDefinition constDef,
            @NotNull Value value,
            @NotNull PsiElement targetElem,
            @NotNull BigInteger val,
            @Nullable BigInteger minIncl,
            @Nullable BigInteger maxExcl,
            @NotNull String baseType
    ) {
        String constName = constDef.getValueKeyword() != null ? constDef.getValueKeyword().getText() : "#CONST";
        String lowerStr = minIncl != null ? minIncl.toString() : "-inf";
        String upperStr = maxExcl != null ? maxExcl.toString() : "+inf";
        String msg = StvnDiagnosticFormatter.formatPlugin(
            "Constraint violation (" + constName + "): Value " + val + " is outside allowable range [" + lowerStr + ", " + upperStr + ") for " + baseType,
            StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW
        );
        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
            value,
            StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW,
            msg,
            Clock.systemDefaultZone()
        ).toArray(LocalQuickFix.EMPTY_ARRAY);
        holder.registerProblem(
            targetElem,
            msg,
            ProblemHighlightType.GENERIC_ERROR,
            fixes
        );
    }

    private static @Nullable BigInteger parseIntOrNull(@Nullable PsiElement context, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigIntegerOrConst(context, text.trim()).orElse(null);
    }

    private static @Nullable BigDecimal parseDecimalOrNull(@Nullable PsiElement context, @Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return StvnPsiLiteralEvaluator.resolveBigDecimalOrConst(context, text.trim()).orElse(null);
    }
}
