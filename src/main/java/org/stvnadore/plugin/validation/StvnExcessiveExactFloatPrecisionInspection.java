package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
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
import org.stvnadore.psi.*;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates declared mantissa precision on exact decimal float definitions ({@code { #exact } :Float}).
 * <p>
 * Emits a warning when {@code #maxSize <N>} exceeds the configured warning threshold (default: 38 digits,
 * corresponding to the ANSI SQL {@code DECIMAL(38, s)} and IEEE 754-2008 {@code decimal128} boundary).
 * <p>
 * Suppresses warnings when {@code #maxSize 16777216} is declared, deferring to Core Rule {@code W003}.
 * Leaves continuous {@code :Float} uninspected, deferring to Core Rule {@code E008}.
 */
@NullMarked
public final class StvnExcessiveExactFloatPrecisionInspection extends LocalInspectionTool {

    public static final int DEFAULT_PRECISION_THRESHOLD = 38;

    /**
     * Exact float mantissa precision warning threshold (default: 38 significant decimal digits).
     */
    public int precisionThreshold = DEFAULT_PRECISION_THRESHOLD;

    /**
     * Constructs a new StvnExcessiveExactFloatPrecisionInspection instance.
     */
    public StvnExcessiveExactFloatPrecisionInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnExcessiveExactFloatPrecision";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Excessive exact float mantissa precision inspection";
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
    public @Nullable JComponent createOptionsPanel() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        var threshPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        threshPanel.add(new JLabel("Exact float mantissa precision warning threshold:"));
        var model = new SpinnerNumberModel(precisionThreshold, 1, 16_777_215, 1);
        var spinner = new JSpinner(model);
        spinner.addChangeListener(e -> precisionThreshold = (Integer) spinner.getValue());
        threshPanel.add(spinner);
        threshPanel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(threshPanel);

        return panel;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitMetadataEntry(@NotNull MetadataEntry entry) {
                super.visitMetadataEntry(entry);

                // 1. Assert facet token is #maxSize
                if (entry.getNode().findChildByType(StvnTypes.KW_MAX_SIZE) == null) {
                    return;
                }

                // 2. Extract declared integer value
                var metaVal = entry.getMetadataValue();
                if (metaVal == null) {
                    return;
                }
                var intLit = metaVal.getIntegerLiteral();
                if (intLit == null) {
                    return;
                }

                BigInteger declaredVal;
                try {
                    declaredVal = new BigInteger(intLit.getText().trim());
                } catch (Exception ignored) {
                    return;
                }

                // 3. Resolve enclosing SchemaType
                var schemaType = PsiTreeUtil.getParentOfType(entry, SchemaType.class);
                if (schemaType == null) {
                    return;
                }

                // 4. Resolve ultimate base type
                var baseType = resolveBaseTypeString(schemaType, new HashSet<>());
                if (!StvnVocabulary.TYPE_FLOAT.equals(baseType)) {
                    return;
                }

                // 5. Verify #exact is active (locally or transitively)
                var metaMap = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
                boolean exactActive = isExactActive(metaMap, schemaType);
                if (!exactActive) {
                    // Continuous :Float is governed strictly by Core Rule E008 via StvnExternalAnnotator
                    return;
                }

                // 6. Suppress tautology ceiling collision: #maxSize 16777216 is Core Rule W003
                if (declaredVal.compareTo(BigInteger.valueOf(StvnVocabulary.DEFAULT_EXACT_FLOAT_MAX_SIZE)) == 0) {
                    return;
                }

                // 7. Check if precision exceeds configured threshold
                if (declaredVal.compareTo(BigInteger.valueOf(precisionThreshold)) > 0) {
                    String msg = String.format(
                        "Declared mantissa precision of %s significant decimal digits is unusually large; "
                            + "may cause significant CPU/memory degradation in decimal arithmetic. "
                            + "(Did you mean a numeric bound like '#maxExcl'?)",
                        declaredVal
                    );
                    var formatted = StvnDiagnosticFormatter.formatPlugin(
                        msg,
                        StvnDiagnosticCodes.WARN_EXCESSIVE_EXACT_PRECISION
                    );

                    holder.registerProblem(
                        entry,
                        formatted,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new ClampExactFloatPrecisionQuickFix(precisionThreshold),
                        new ConvertToNumericBoundQuickFix(intLit.getText().trim())
                    );
                }
            }
        };
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
                    var resolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
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

    private static @Nullable String resolveBaseTypeString(@NotNull SchemaType schemaType, Set<String> visited) {
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
                var resolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, text, new HashSet<>());
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
     * Quick-Fix: Clamps #maxSize to the configured threshold (default: 38).
     */
    public static final class ClampExactFloatPrecisionQuickFix implements LocalQuickFix, PriorityAction {
        private final int targetPrecision;

        public ClampExactFloatPrecisionQuickFix(int targetPrecision) {
            this.targetPrecision = targetPrecision;
        }

        @Override
        public @Nls @NotNull String getName() {
            return "Clamp #maxSize to " + targetPrecision + " (Standard Financial / Decimal128 Precision)";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Clamp #maxSize to standard precision";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (!(element instanceof MetadataEntry entry)) {
                return;
            }
            var metaVal = entry.getMetadataValue();
            if (metaVal == null) return;
            var intLit = metaVal.getIntegerLiteral();
            if (intLit == null) return;

            var file = element.getContainingFile();
            if (file == null) return;
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            var range = intLit.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), String.valueOf(targetPrecision));
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }

    /**
     * Quick-Fix: Converts '#maxSize <N>' to numeric bound '#maxExcl <N>.0'.
     */
    public static final class ConvertToNumericBoundQuickFix implements LocalQuickFix, PriorityAction {
        private final String rawNumericValue;

        public ConvertToNumericBoundQuickFix(String rawNumericValue) {
            this.rawNumericValue = rawNumericValue;
        }

        @Override
        public @Nls @NotNull String getName() {
            return "Convert to numeric bound '#maxExcl " + rawNumericValue + ".0'";
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Convert to numeric bound '#maxExcl '";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.NORMAL;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (!(element instanceof MetadataEntry entry)) {
                return;
            }
            var file = element.getContainingFile();
            if (file == null) return;
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            String replacement = StvnVocabulary.FACET_KW_MAX_EXCL + " " + rawNumericValue + ".0";
            var range = entry.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }
}
