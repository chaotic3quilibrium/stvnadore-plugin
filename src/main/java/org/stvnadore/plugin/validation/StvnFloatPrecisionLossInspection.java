package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnLiteralParser;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.BooleanValue;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.ExplicitUnionValue;
import org.stvnadore.psi.FloatLiteral;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.Visitor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Validates floating-point literal mantissa precision on continuous float definitions.
 * <p>
 * Emits a warning when authored decimal precision exceeds IEEE-754 Single (32-bit) or Double (64-bit)
 * representation capacity, alerting authors to binary truncation data loss.
 * <p>
 * Bypasses discrete exact float definitions ({@code { #exact } :Float}), which support arbitrary decimal precision.
 */
@NullMarked
public final class StvnFloatPrecisionLossInspection extends LocalInspectionTool {

    public static final int IEEE754_SINGLE_MAX_DIGITS = 8;
    public static final int IEEE754_DOUBLE_MAX_DIGITS = 17;

    public StvnFloatPrecisionLossInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnFloatPrecisionLoss";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Continuous float precision loss inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "Numeric & Floating-Point";
    }

    @Override
    public String @NotNull [] getGroupPath() {
        return new String[]{"STVN"};
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public SuppressQuickFix[] getBatchSuppressActions(@Nullable PsiElement element) {
        // Comment-based suppression is strictly prohibited under STVN 2.0.0
        return SuppressQuickFix.EMPTY_ARRAY;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitFloatLiteral(@NotNull FloatLiteral literal) {
                super.visitFloatLiteral(literal);

                var rawText = literal.getText().trim();
                BigDecimal valBD;
                try {
                    valBD = StvnLiteralParser.parseBigDecimal(rawText);
                } catch (Exception ignored) {
                    return;
                }

                var value = PsiTreeUtil.getParentOfType(literal, Value.class);
                if (value == null) {
                    return;
                }

                // Resolve governing schema
                SchemaType targetSchema = null;
                var constDef = PsiTreeUtil.getParentOfType(literal, ConstantDefinition.class);
                if (constDef != null) {
                    targetSchema = constDef.getSchemaType();
                } else {
                    targetSchema = resolveGoverningSchemaForValue(value);
                }

                if (targetSchema == null) {
                    return;
                }

                // Verify base type is :Float
                var baseType = resolveBaseTypeString(targetSchema, new HashSet<>());
                if (!StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
                    return;
                }

                // Verify #exact is NOT active
                if (isExactActive(targetSchema.getMetadataMap(), targetSchema)) {
                    return;
                }

                // Determine bit width (32 or 64)
                int bitWidth = resolveFloatBitWidth(targetSchema);
                int precision = valBD.precision();
                int maxCapacityDigits = (bitWidth == 32) ? IEEE754_SINGLE_MAX_DIGITS : IEEE754_DOUBLE_MAX_DIGITS;

                if (precision > maxCapacityDigits) {
                    String roundedStr;
                    String msg;
                    if (bitWidth == 32) {
                        float f = valBD.floatValue();
                        roundedStr = Float.toString(f);
                        msg = String.format(
                            "Floating-point literal precision (%d digits) exceeds IEEE-754 Single (32-bit) capacity (~8 digits); binary encoding truncates value to %s",
                            precision, roundedStr
                        );
                    } else {
                        double d = valBD.doubleValue();
                        roundedStr = Double.toString(d);
                        msg = String.format(
                            "Floating-point literal precision (%d digits) exceeds IEEE-754 Double (64-bit) capacity (~16 digits); binary encoding truncates value to %s",
                            precision, roundedStr
                        );
                    }

                    try {
                        if (new BigDecimal(roundedStr).compareTo(valBD) == 0) {
                            return;
                        }
                    } catch (Exception ignored) {
                        return;
                    }

                    var formattedMsg = StvnDiagnosticFormatter.formatPlugin(
                        msg,
                        StvnDiagnosticCodes.WARN_FLOAT_PRECISION_LOSS
                    );

                    var fixes = new ArrayList<LocalQuickFix>();
                    fixes.add(new RoundFloatLiteralQuickFix(roundedStr));

                    // Context-aware Quick-Fix 2: Switch variant to exact float branch
                    var union = PsiTreeUtil.getParentOfType(literal, ExplicitUnionValue.class);
                    if (union != null) {
                        var exactBranchTag = findExactSiblingBranchTag(union);
                        if (exactBranchTag != null) {
                            fixes.add(new SwitchVariantQuickFix(exactBranchTag));
                        }
                    }

                    holder.registerProblem(
                        literal,
                        formattedMsg,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        fixes.toArray(new LocalQuickFix[0])
                    );
                }
            }
        };
    }

    private static @Nullable SchemaType resolveGoverningSchemaForValue(Value value) {
        var parentUnion = PsiTreeUtil.getParentOfType(value, ExplicitUnionValue.class, true);
        if (parentUnion != null) {
            var outerVal = PsiTreeUtil.getParentOfType(parentUnion, Value.class);
            if (outerVal != null) {
                var info = StvnTypeResolver.resolveBaseTypeInfo(outerVal);
                if (info != null && info.getSchema() != null) {
                    var nominalUnion = StvnTypeResolver.resolveNominalSchema(info.getSchema());
                    var unionSchema = (nominalUnion != null) ? nominalUnion : info.getSchema();
                    var ctor = unionSchema.getSchemaConstructor();
                    if (ctor != null && ctor.getSumType() != null) {
                        var branches = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
                        var tagPrefix = parentUnion.getUnionTagPrefix();
                        if (tagPrefix != null && tagPrefix.getText().startsWith("#")) {
                            try {
                                int idx = Integer.parseInt(tagPrefix.getText().substring(1)) - 1;
                                if (idx >= 0 && idx < branches.size()) {
                                    return branches.get(idx);
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        var info = StvnTypeResolver.resolveBaseTypeInfo(value);
        return info != null ? info.getSchema() : null;
    }

    private static int resolveFloatBitWidth(SchemaType schemaType) {
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null) {
            for (var entry : metaMap.getMetadataEntryList()) {
                var txt = entry.getText().trim();
                if (txt.startsWith("#size")) {
                    var parts = txt.split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            return Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (!StvnVocabulary.isBaseScalarType(text)) {
                var file = schemaType.getContainingFile();
                if (file != null) {
                    var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                    if (resolved != null) {
                        var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                        if (parentDef != null && parentDef.getSchemaType() != null) {
                            return resolveFloatBitWidth(parentDef.getSchemaType());
                        }
                    }
                }
            }
        }
        return 64;
    }

    private static boolean isExactActive(@Nullable MetadataMap metaMap, @NotNull SchemaType schemaType) {
        if (hasLocalExactFacet(metaMap)) {
            return true;
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            var text = kw.getText().trim();
            if (!StvnVocabulary.isBaseScalarType(text)) {
                var file = schemaType.getContainingFile();
                if (file != null) {
                    var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                    if (resolved != null) {
                        var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                        if (parentDef != null && parentDef.getSchemaType() != null) {
                            return isExactActive(parentDef.getSchemaType().getMetadataMap(), parentDef.getSchemaType());
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasLocalExactFacet(@Nullable MetadataMap metaMap) {
        if (metaMap == null) return false;
        for (var entry : metaMap.getMetadataEntryList()) {
            var bare = entry.getMetadataBareFlag();
            if (bare != null && bare.getNode().findChildByType(StvnTypes.KW_EXACT) != null) {
                var bVal = bare.getBooleanValue();
                return bVal == null || isBooleanTrue(bVal);
            }
        }
        return false;
    }

    private static boolean isBooleanTrue(@Nullable BooleanValue bVal) {
        if (bVal == null) return false;
        var t = bVal.getText().trim();
        return "#TRUE".equals(t) || "#T".equals(t);
    }

    private static @Nullable String findExactSiblingBranchTag(ExplicitUnionValue union) {
        var outerVal = PsiTreeUtil.getParentOfType(union, Value.class);
        if (outerVal == null) return null;
        var info = StvnTypeResolver.resolveBaseTypeInfo(outerVal);
        if (info == null || info.getSchema() == null) return null;

        var nominalUnion = StvnTypeResolver.resolveNominalSchema(info.getSchema());
        var unionSchema = (nominalUnion != null) ? nominalUnion : info.getSchema();
        var ctor = unionSchema.getSchemaConstructor();
        if (ctor == null || ctor.getSumType() == null) return null;

        var branches = PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class);
        for (int i = 0; i < branches.size(); i++) {
            var branch = branches.get(i);
            var base = resolveBaseTypeString(branch, new HashSet<>());
            if (StvnVocabulary.TYPE_FLOAT.equals(base) && isExactActive(branch.getMetadataMap(), branch)) {
                return "#" + (i + 1);
            }
        }
        return null;
    }

    private static @Nullable String resolveBaseTypeString(@NotNull SchemaType schemaType, java.util.Set<String> visited) {
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
            if (StvnVocabulary.isBaseScalarType(text)) {
                return text;
            }
            if (!visited.add(text)) {
                return null;
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveBaseTypeString(parentDef.getSchemaType(), visited);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Quick-Fix: Replaces the authored literal with the nearest IEEE-754 representation.
     */
    public static final class RoundFloatLiteralQuickFix implements LocalQuickFix, PriorityAction {
        private final String roundedValue;

        public RoundFloatLiteralQuickFix(String roundedValue) {
            this.roundedValue = roundedValue;
        }

        @Override
        public @Nls @NotNull String getName() {
            return "Round literal to nearest IEEE-754 representation (" + roundedValue + ")";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Round literal to nearest IEEE-754 representation";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            var range = element.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), roundedValue);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }

    /**
     * Quick-Fix: Switches a tagged union variant to the exact float branch.
     */
    public static final class SwitchVariantQuickFix implements LocalQuickFix, PriorityAction {
        private final String targetTag;

        public SwitchVariantQuickFix(String targetTag) {
            this.targetTag = targetTag;
        }

        @Override
        public @Nls @NotNull String getName() {
            return "Switch variant to exact float branch (" + targetTag + ")";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Switch variant to exact float branch";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.NORMAL;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var union = PsiTreeUtil.getParentOfType(element, ExplicitUnionValue.class);
            if (union == null) return;
            var prefix = union.getUnionTagPrefix();
            if (prefix == null) return;

            var file = element.getContainingFile();
            if (file == null) return;
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            var range = prefix.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), targetTag);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }
}
