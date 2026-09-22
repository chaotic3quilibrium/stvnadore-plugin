package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.utils.StvnStringCapacityUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.AtomicType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.DefsInclEntry;
import org.stvnadore.psi.DefsInclfEntry;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.Visitor;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.util.HashSet;
import java.util.OptionalInt;

/**
 * Validates STVN String Capacity Governance invariants.
 * <p>
 * Enforces explicit capacity dimensions on nominal string types in schema declarations
 * ({@code :defs} and {@code :type}) to prevent unbounded allocation overhead.
 * Flags unadorned {@code :String} declarations and capacities exceeding the configured
 * inspection threshold.
 * </p>
 * <p>
 * Provides dual QuickFixes (configured threshold vs default capacity) under
 * {@code WARNING} severity, while strictly suppressing the secondary default QuickFix
 * when configured as {@code ERROR}.
 * </p>
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnStringCapacityInspection extends LocalInspectionTool {

    /**
     * Configured capacity threshold in characters (default: 4,096).
     */
    public int thresholdCapacity = StvnStringCapacityUtils.DEFAULT_INSPECTION_THRESHOLD;

    /**
     * Toggle enabling literal content length verification against schema bounds.
     */
    public boolean inspectPayload = false;

    /**
     * Explicit severity string override used during automated tests.
     */
    public String configuredSeverity = "WARNING";

    /**
     * Constructs a new StvnStringCapacityInspection instance.
     */
    public StvnStringCapacityInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnStringCapacity";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "String capacity governance inspection";
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
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));

        var threshPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        threshPanel.add(new JLabel("Capacity threshold (characters):"));
        var model = new SpinnerNumberModel(thresholdCapacity, 1, Integer.MAX_VALUE, 256);
        var spinner = new JSpinner(model);
        spinner.addChangeListener(e -> thresholdCapacity = (Integer) spinner.getValue());
        threshPanel.add(spinner);
        threshPanel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(threshPanel);

        var payloadCb = new JCheckBox("Inspect string literals in body payloads and constants", inspectPayload);
        payloadCb.addActionListener(e -> inspectPayload = payloadCb.isSelected());
        payloadCb.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(payloadCb);

        return panel;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            private void inspectStringElement(@NotNull PsiElement typeElem) {
                // 1. Enforce Schema Scope: inspect only elements inside :defs or :type
                boolean inSchema = PsiTreeUtil.getParentOfType(
                    typeElem,
                    DefsEntry.class,
                    DefsInclEntry.class,
                    DefsInclfEntry.class,
                    TypeEntry.class
                ) != null;
                if (!inSchema) {
                    return;
                }

                String typeName = typeElem.getText().trim();
                if (!StvnStringCapacityUtils.isNominalStringType(typeName)) {
                    return;
                }

                OptionalInt capacityOpt;
                try {
                    capacityOpt = StvnStringCapacityUtils.parseCapacitySuffix(typeName);
                } catch (Exception ignored) {
                    // Syntax-level malformed capacity errors are handled by compiler diagnostics
                    return;
                }

                boolean isUnadorned = capacityOpt.isEmpty();
                boolean exceedsThreshold = capacityOpt.isPresent() && capacityOpt.getAsInt() > thresholdCapacity;

                if (isUnadorned || exceedsThreshold) {
                    boolean isError = isErrorSeverity(holder);
                    String msg;
                    if (isUnadorned) {
                        msg = "Nominal string type '" + typeName + "' is unadorned; default capacity is "
                            + StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY
                            + " characters. Specify explicit capacity bound.";
                    } else {
                        msg = "Nominal string type '" + typeName + "' specifies capacity " + capacityOpt.getAsInt()
                            + ", exceeding configured threshold of " + thresholdCapacity + " characters.";
                    }

                    if (isError) {
                        // Protocol Constraint: Suppress Secondary QuickFix under ERROR severity
                        holder.registerProblem(
                            typeElem,
                            msg,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new ApplyConfiguredCapacityQuickFix(thresholdCapacity)
                        );
                    } else {
                        // Protocol Requirement: Offer Dual QuickFixes under WARNING / WEAK WARNING
                        holder.registerProblem(
                            typeElem,
                            msg,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new ApplyConfiguredCapacityQuickFix(thresholdCapacity),
                            new ApplyDefaultCapacityQuickFix()
                        );
                    }
                }
            }

            @Override
            public void visitAtomicType(@NotNull AtomicType atomicType) {
                super.visitAtomicType(atomicType);
                inspectStringElement(atomicType);
            }

            @Override
            public void visitTypeKeyword(@NotNull org.stvnadore.psi.TypeKeyword typeKeyword) {
                super.visitTypeKeyword(typeKeyword);
                inspectStringElement(typeKeyword);
            }

            @Override
            public void visitStringLiteral(@NotNull StringLiteral literal) {
                super.visitStringLiteral(literal);
                if (!inspectPayload) {
                    return;
                }

                var constDef = PsiTreeUtil.getParentOfType(literal, ConstantDefinition.class);
                var bodyEntry = PsiTreeUtil.getParentOfType(literal, BodyEntry.class);
                if (constDef == null && bodyEntry == null) {
                    return;
                }

                SchemaType schemaType = null;
                if (constDef != null) {
                    schemaType = constDef.getSchemaType();
                } else {
                    var typeEntry = PsiTreeUtil.findChildOfType(literal.getContainingFile(), TypeEntry.class);
                    if (typeEntry != null) {
                        schemaType = typeEntry.getSchemaType();
                    }
                }

                if (schemaType == null) {
                    return;
                }

                int capacityBound = resolveCapacityBound(schemaType, literal.getContainingFile());
                if (capacityBound <= 0) {
                    return;
                }

                String content = extractLiteralContent(literal.getText());
                if (content.length() > capacityBound) {
                    holder.registerProblem(
                        literal,
                        "String literal length (" + content.length() + ") exceeds declared schema capacity (" + capacityBound + ")",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new TruncateStringLiteralQuickFix(capacityBound),
                        new WidenSchemaCapacityQuickFix(schemaType, content.length())
                    );
                }
            }
        };
    }

    /**
     * Determines whether the active inspection context evaluates as ERROR severity.
     *
     * @param holder the current problems holder
     * @return true if severity is ERROR, false otherwise
     */
    public boolean isErrorSeverity(ProblemsHolder holder) {
        if ("ERROR".equalsIgnoreCase(configuredSeverity)) {
            return true;
        }
        var key = HighlightDisplayKey.find("StvnStringCapacity");
        if (key != null) {
            var profile = InspectionProjectProfileManager.getInstance(holder.getProject()).getCurrentProfile();
            var level = profile.getErrorLevel(key, holder.getFile());
            if (level != null && HighlightSeverity.ERROR.equals(level.getSeverity())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the effective capacity bound for the given schema type element.
     *
     * @param schemaType the schema type AST element
     * @param file the containing PSI file
     * @return the resolved capacity bound, or -1 if unresolvable
     */
    public static int resolveCapacityBound(SchemaType schemaType, PsiFile file) {
        var atomic = PsiTreeUtil.findChildOfType(schemaType, AtomicType.class);
        if (atomic != null) {
            String typeText = atomic.getText().trim();
            if (StvnStringCapacityUtils.isNominalStringType(typeText)) {
                try {
                    var opt = StvnStringCapacityUtils.parseCapacitySuffix(typeText);
                    return opt.orElse(StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY);
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            String typeText = kw.getText().trim();
            if (StvnStringCapacityUtils.isNominalStringType(typeText)) {
                try {
                    var opt = StvnStringCapacityUtils.parseCapacitySuffix(typeText);
                    return opt.orElse(StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY);
                } catch (Exception ignored) {
                    return -1;
                }
            }
            var resolved = StvnTypeReference.resolveTypeInFile(file, typeText, new HashSet<>());
            if (resolved != null) {
                var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                if (parentDef != null && parentDef.getSchemaType() != null) {
                    return resolveCapacityBound(parentDef.getSchemaType(), file);
                }
            }
        }
        return -1;
    }

    /**
     * Extracts unescaped text content from an STVN string literal token.
     *
     * @param raw the raw token text
     * @return unquoted string content
     */
    public static String extractLiteralContent(String raw) {
        if (raw.startsWith("\"\"\"")) {
            int firstNl = raw.indexOf('\n');
            int lastFence = raw.lastIndexOf("\"\"\"");
            if (firstNl >= 0 && lastFence > firstNl) {
                int closeBracket = raw.lastIndexOf(']');
                if (closeBracket >= 0 && closeBracket < lastFence) {
                    lastFence = raw.lastIndexOf('[', closeBracket);
                }
                return raw.substring(firstNl + 1, lastFence);
            }
        } else if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    /**
     * Truncates raw literal content to fit within the specified maximum capacity.
     *
     * @param raw the raw token text
     * @param maxCapacity the maximum allowed character length
     * @return truncated token text with preserved delimiter wrapping
     */
    public static String truncateLiteral(String raw, int maxCapacity) {
        if (raw.startsWith("\"\"\"")) {
            int firstNl = raw.indexOf('\n');
            int lastFence = raw.lastIndexOf("\"\"\"");
            if (firstNl >= 0 && lastFence > firstNl) {
                String header = raw.substring(0, firstNl + 1);
                String trailer = raw.substring(lastFence);
                String body = raw.substring(firstNl + 1, lastFence);
                String truncatedBody = body.length() > maxCapacity ? body.substring(0, maxCapacity) : body;
                return header + truncatedBody + trailer;
            }
        } else if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            String content = raw.substring(1, raw.length() - 1);
            String truncated = content.length() > maxCapacity ? content.substring(0, maxCapacity) : content;
            return "\"" + truncated + "\"";
        }
        return raw;
    }

    /**
     * Determines the base nominal string type identifier prefix.
     *
     * @param text the existing type token text
     * @return the nominal base prefix
     */
    public static String getBaseNominalTypeName(String text) {
        if (text.startsWith(":StringFixed")) {
            return ":StringFixed";
        }
        if (text.startsWith(":StringNonEmpty")) {
            return ":StringNonEmpty";
        }
        return ":String";
    }

    /**
     * Primary QuickFix rewriting nominal string types to the configured threshold.
     */
    public static final class ApplyConfiguredCapacityQuickFix implements LocalQuickFix {
        private final int targetCapacity;

        /**
         * Constructs a new ApplyConfiguredCapacityQuickFix.
         *
         * @param targetCapacity the target capacity bound to apply
         */
        public ApplyConfiguredCapacityQuickFix(int targetCapacity) {
            this.targetCapacity = targetCapacity;
        }

        @Override
        public @NotNull String getName() {
            return "Set nominal string capacity to " + targetCapacity;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Set nominal string capacity to configured threshold";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            String currentText = element.getText().trim();
            String baseName = getBaseNominalTypeName(currentText);
            String updatedType = baseName + targetCapacity;

            var range = element.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), updatedType);
            docManager.commitDocument(doc);
        }
    }

    /**
     * Secondary QuickFix rewriting nominal string types to the default capacity.
     */
    public static final class ApplyDefaultCapacityQuickFix implements LocalQuickFix {

        /**
         * Constructs a new ApplyDefaultCapacityQuickFix.
         */
        public ApplyDefaultCapacityQuickFix() {
        }

        @Override
        public @NotNull String getName() {
            return "Set nominal string capacity to default capacity (" + StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY + ")";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Set nominal string capacity to default capacity";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            String currentText = element.getText().trim();
            String baseName = getBaseNominalTypeName(currentText);
            String updatedType = baseName + StvnStringCapacityUtils.DEFAULT_UNBOUNDED_STRING_CAPACITY;

            var range = element.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), updatedType);
            docManager.commitDocument(doc);
        }
    }

    /**
     * QuickFix truncating string literal values to declared schema capacity.
     */
    public static final class TruncateStringLiteralQuickFix implements LocalQuickFix {
        private final int maxCapacity;

        /**
         * Constructs a new TruncateStringLiteralQuickFix.
         *
         * @param maxCapacity the maximum capacity to enforce
         */
        public TruncateStringLiteralQuickFix(int maxCapacity) {
            this.maxCapacity = maxCapacity;
        }

        @Override
        public @NotNull String getName() {
            return "Truncate string literal to " + maxCapacity + " characters";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Truncate string literal to declared schema capacity";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) return;
            var file = element.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            String rawText = element.getText();
            String truncatedText = truncateLiteral(rawText, maxCapacity);

            var range = element.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), truncatedText);
            docManager.commitDocument(doc);
        }
    }

    /**
     * QuickFix widening schema capacity to accommodate literal content length.
     */
    public static final class WidenSchemaCapacityQuickFix implements LocalQuickFix {
        private final SchemaType schemaType;
        private final int requiredCapacity;

        /**
         * Constructs a new WidenSchemaCapacityQuickFix.
         *
         * @param schemaType the governing schema type AST node
         * @param requiredCapacity the required string capacity
         */
        public WidenSchemaCapacityQuickFix(SchemaType schemaType, int requiredCapacity) {
            this.schemaType = schemaType;
            this.requiredCapacity = requiredCapacity;
        }

        @Override
        public @NotNull String getName() {
            return "Widen schema string capacity to " + requiredCapacity;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Widen schema string capacity";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!schemaType.isValid()) return;
            var file = schemaType.getContainingFile();
            if (file == null) return;
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc == null) return;

            var atomicType = PsiTreeUtil.findChildOfType(schemaType, AtomicType.class);
            PsiElement targetElem = atomicType;
            if (targetElem == null) {
                targetElem = schemaType.getTypeKeyword();
            }
            if (targetElem != null) {
                String current = targetElem.getText().trim();
                String base = getBaseNominalTypeName(current);
                String widened = base + requiredCapacity;
                var range = targetElem.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), widened);
                docManager.commitDocument(doc);
            }
        }
    }
}
