package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnLiteralParser;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.Visitor;

import java.math.BigInteger;

/**
 * Validates that integer literals assigned to typed constants fit within the physical
 * bit-width boundaries of their declared integer schema types.
 */
@NullMarked
public final class StvnConstantRangeInspection extends LocalInspectionTool {

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

                var baseType = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
                if (!baseType.startsWith(":Uint") && !baseType.startsWith(":Int")) {
                    return;
                }

                var isUnsigned = baseType.startsWith(":Uint");
                var suffix = isUnsigned ? baseType.substring(5) : baseType.substring(4);
                var bitWidth = 32;
                if (!suffix.isEmpty() && suffix.matches("\\d+")) {
                    try {
                        bitWidth = Integer.parseInt(suffix);
                    } catch (NumberFormatException ignored) {
                        return;
                    }
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
