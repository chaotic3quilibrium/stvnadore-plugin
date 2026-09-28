package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnLiteralParser;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.Visitor;

import java.math.BigInteger;

/**
 * Validates that integer literals assigned to typed constants fit within the physical
 * bit-width boundaries of their declared integer schema types.
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
                if (schemaType == null || value == null || value.getIntegerLiteral() == null) {
                    return;
                }

                var baseType = StvnSchemaFormatter.formatSchema(schemaType).trim();
                boolean isUnsigned = false;
                Integer bitWidth = null;

                var meta = schemaType.getMetadataMap();
                var cleanType = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
                if (meta == null || !StvnVocabulary.TYPE_INT.equals(cleanType)) {
                    var resolved = StvnTypeResolver.resolveNominalSchema(schemaType);
                    if (resolved != null) {
                        if (meta == null) {
                            meta = resolved.getMetadataMap();
                        }
                        cleanType = StvnSchemaFormatter.formatCleanSchema(resolved).trim();
                    }
                }

                if (meta != null) {
                    for (var entry : meta.getMetadataEntryList()) {
                        var txt = entry.getText().trim();
                        if (txt.equals("#unsigned") || txt.startsWith("#unsigned ")) {
                            isUnsigned = true;
                        } else if (txt.startsWith("#size")) {
                            var parts = txt.split("\\s+");
                            if (parts.length >= 2) {
                                try {
                                    bitWidth = Integer.parseInt(parts[1]);
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }

                if (!StvnVocabulary.TYPE_INT.equals(cleanType)) {
                    return;
                }

                if (bitWidth == null) {
                    return;
                }

                var litText = value.getIntegerLiteral().getText();
                BigInteger val;
                try {
                    val = StvnLiteralParser.parseBigInteger(litText);
                } catch (Exception e) {
                    return;
                }

                var min = isUnsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bitWidth - 1).negate();
                var max = isUnsigned 
                    ? BigInteger.ONE.shiftLeft(bitWidth).subtract(BigInteger.ONE)
                    : BigInteger.ONE.shiftLeft(bitWidth - 1).subtract(BigInteger.ONE);

                if (val.compareTo(min) < 0 || val.compareTo(max) > 0) {
                    holder.registerProblem(
                        value.getIntegerLiteral(),
                        "Integer literal " + val + " out of range for " + baseType + " [" + min + ", " + max + "]",
                        ProblemHighlightType.ERROR
                    );
                }
            }
        };
    }
}
