package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.Visitor;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Validates that integer and continuous floating-point literals assigned to typed constants
 * fit within the physical bit-width boundaries of their declared schema types.
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
                org.stvnadore.psi.SchemaType resolved = null;
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

                var entries = new java.util.ArrayList<org.stvnadore.psi.MetadataEntry>();
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
                    if (txt.equals("#unsigned") || txt.startsWith("#unsigned ")) {
                        isUnsigned = true;
                    } else if (txt.startsWith("#size")) {
                        var parts = txt.split("\\s+");
                        if (parts.length >= 2 && rawBitWidth == null) {
                            rawBitWidth = StvnPsiLiteralEvaluator.parseBigInteger(parts[1]).orElse(null);
                        }
                    } else if (txt.equals("#exact") || txt.startsWith("#exact ")) {
                        isExact = true;
                    } else if (txt.equals("#ns") || txt.startsWith("#ns ")) {
                        if (epochScale == null) epochScale = "ns";
                    } else if (txt.equals("#s") || txt.startsWith("#s ")
                            || txt.equals("#ms") || txt.startsWith("#ms ")
                            || txt.equals("#us") || txt.startsWith("#us ")) {
                        if (epochScale == null) epochScale = "standard";
                    }
                }

                if (isEpoch) {
                    if (rawBitWidth == null) {
                        rawBitWidth = BigInteger.valueOf("ns".equals(epochScale) ? 128L : 64L);
                    }
                }

                // Continuous float evaluation branch
                if (isFloat) {
                    if (isExact) {
                        return; // Discrete exact float has arbitrary capacity; governed by #maxSize
                    }
                    if (value.getFloatLiteral() == null) {
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

                    var litText = value.getFloatLiteral().getText();
                    var floatValOpt = StvnPsiLiteralEvaluator.parseBigDecimal(litText);
                    if (floatValOpt.isEmpty()) {
                        return;
                    }
                    BigDecimal floatVal = floatValOpt.get();

                    if (floatVal.compareTo(minBound) < 0 || floatVal.compareTo(maxBound) > 0) {
                        String targetType = (rawBitWidth != null) ? "{ #size " + rawBitWidth + " } " + cleanType : cleanType;
                        holder.registerProblem(
                            value.getFloatLiteral(),
                            StvnDiagnosticFormatter.formatPlugin(
                                "Float literal " + litText + " out of range for " + targetType + " [" + minBound + ", " + maxBound + "]",
                                StvnDiagnosticCodes.ERR_CONSTANT_FLOAT_OVERFLOW
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                    return;
                }

                if (value.getIntegerLiteral() == null) {
                    return;
                }

                if (rawBitWidth == null || rawBitWidth.compareTo(BigInteger.ONE) < 0) {
                    return;
                }

                var litText = value.getIntegerLiteral().getText();
                var valOpt = StvnPsiLiteralEvaluator.parseBigInteger(litText);
                if (valOpt.isEmpty()) {
                    return;
                }
                BigInteger val = valOpt.get();

                if (rawBitWidth.compareTo(BigInteger.valueOf(StvnPsiLiteralEvaluator.MAX_EAGER_SHIFT_BIT_WIDTH)) <= 0) {
                    int bitWidth = rawBitWidth.intValueExact();
                    var min = isUnsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
                    var max = isUnsigned
                        ? BigInteger.ONE.shiftLeft(bitWidth).subtract(BigInteger.ONE)
                        : BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(BigInteger.ONE);

                    if (val.compareTo(min) < 0 || val.compareTo(max) > 0) {
                        holder.registerProblem(
                            value.getIntegerLiteral(),
                            StvnDiagnosticFormatter.formatPlugin(
                                "Integer literal " + val + " out of range for " + baseType + " [" + min + ", " + max + "]",
                                StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                } else {
                    boolean outOfRange = isUnsigned
                        ? (val.signum() < 0 || BigInteger.valueOf(val.bitLength()).compareTo(rawBitWidth) > 0)
                        : (BigInteger.valueOf(val.bitLength()).compareTo(rawBitWidth.subtract(BigInteger.ONE)) > 0);
                    if (outOfRange) {
                        String rangeStr = isUnsigned
                            ? "[0, 2^" + rawBitWidth + " - 1]"
                            : "[-2^" + rawBitWidth.subtract(BigInteger.ONE) + ", 2^" + rawBitWidth.subtract(BigInteger.ONE) + " - 1]";
                        holder.registerProblem(
                            value.getIntegerLiteral(),
                            StvnDiagnosticFormatter.formatPlugin(
                                "Integer literal " + val + " out of range for " + baseType + " " + rangeStr,
                                StvnDiagnosticCodes.ERR_CONSTANT_INTEGER_OVERFLOW
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                }
            }
        };
    }
}
