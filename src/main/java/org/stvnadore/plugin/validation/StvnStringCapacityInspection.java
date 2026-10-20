package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.AtomicType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Visitor;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

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
     * Default capacity threshold in characters for schema and diagnostic analyzers (4,096 characters).
     */
    public static final int DEFAULT_INSPECTION_THRESHOLD = 4096;

    /**
     * Default allocation capacity limit in characters for unadorned :String instances (16,777,216 characters / 16 MiB).
     */
    public static final int DEFAULT_UNBOUNDED_STRING_CAPACITY = 16_777_216;

    /**
     * Authoritative map of Standard Prelude string types to their canonical #maxSize character capacity.
     */
    public static final Map<String, Integer> PRELUDE_STRING_CAPACITIES = Map.ofEntries(
        Map.entry(":UuidLowerCase", 36),
        Map.entry(":org/stvnadore/prelude/UuidLowerCase", 36),
        Map.entry(":UuidMixedCase", 36),
        Map.entry(":org/stvnadore/prelude/UuidMixedCase", 36),
        Map.entry(":Ulid", 26),
        Map.entry(":org/stvnadore/prelude/Ulid", 26),
        Map.entry(":Sha256LowerCase", 64),
        Map.entry(":org/stvnadore/prelude/Sha256LowerCase", 64),
        Map.entry(":Sha256MixedCase", 64),
        Map.entry(":org/stvnadore/prelude/Sha256MixedCase", 64),
        Map.entry(":SemVer", 128),
        Map.entry(":org/stvnadore/prelude/SemVer", 128),
        Map.entry(":EmailLowerCase", 254),
        Map.entry(":org/stvnadore/prelude/EmailLowerCase", 254),
        Map.entry(":EmailMixedCase", 254),
        Map.entry(":org/stvnadore/prelude/EmailMixedCase", 254),
        Map.entry(":IPv4", 15),
        Map.entry(":org/stvnadore/prelude/IPv4", 15),
        Map.entry(":PosixId", 255),
        Map.entry(":org/stvnadore/prelude/PosixId", 255),
        Map.entry(":PolyglotCodeId", 128),
        Map.entry(":org/stvnadore/prelude/PolyglotCodeId", 128),
        Map.entry(":StvnPosixId64", 64),
        Map.entry(":org/stvnadore/prelude/meta/StvnPosixId64", 64)
    );

    /**
     * Configured capacity threshold in characters (default: 4,096).
     */
    public int thresholdCapacity = DEFAULT_INSPECTION_THRESHOLD;

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
            private void inspectTypeDefinition(@NotNull TypeDefinition typeDef) {
                // 1. Enforce Schema Scope: inspect only elements inside :defs
                boolean inSchema = PsiTreeUtil.getParentOfType(typeDef, DefsEntry.class) != null;
                if (!inSchema) {
                    return;
                }

                var target = typeDef.getTypeDefTarget();
                var schemaType = typeDef.getSchemaType();
                if (schemaType == null) {
                    return;
                }

                String declaredName = target.getText().trim();
                var metaMap = schemaType.getMetadataMap() != null ? schemaType.getMetadataMap() : typeDef.getMetadataMap();

                boolean hasDirectCapacityFacet = false;
                int directCapacity = -1;
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        var text = entry.getText();
                        if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE) || text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                            hasDirectCapacityFacet = true;
                            var intLit = PsiTreeUtil.findChildOfType(entry, org.stvnadore.psi.IntegerLiteral.class);
                            if (intLit != null) {
                                try {
                                    directCapacity = Integer.parseInt(intLit.getText());
                                } catch (Exception ignored) {}
                            }
                        } else if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                            hasDirectCapacityFacet = true;
                        }
                    }
                }

                var resolvedInfo = resolveEffectiveTypeCapacity(schemaType, typeDef.getContainingFile(), new HashSet<>());
                if (resolvedInfo == null || (!resolvedInfo.isString && !resolvedInfo.isCollection)) {
                    return;
                }

                boolean hasCapacityFacet = hasDirectCapacityFacet || resolvedInfo.hasExplicitCapacity;
                int effectiveCapacity = hasDirectCapacityFacet ? directCapacity : resolvedInfo.capacity;

                boolean isUnadorned = !hasCapacityFacet;
                boolean exceedsThreshold = effectiveCapacity > thresholdCapacity;

                if (isUnadorned || exceedsThreshold) {
                    boolean isError = isErrorSeverity(holder);
                    String category = resolvedInfo.isCollection ? "collection" : "string";
                    String units = resolvedInfo.isCollection ? (schemaType.getText().contains(StvnVocabulary.TYPE_MAP) ? "entries" : "elements") : "characters";
                    String msg;
                    String diagCode;
                    if (isUnadorned) {
                        msg = "Nominal " + category + " type '" + declaredName + "' is unadorned; default capacity is "
                            + DEFAULT_UNBOUNDED_STRING_CAPACITY
                            + " " + units + ". Specify explicit capacity facets '{ #minSize ... #maxSize ... }'.";
                        diagCode = StvnDiagnosticCodes.WARN_NOMINAL_STRING_UNADORNED;
                    } else {
                        msg = "Nominal " + category + " type '" + declaredName + "' specifies capacity " + effectiveCapacity
                            + ", exceeding configured threshold of " + thresholdCapacity + " " + units + ".";
                        diagCode = StvnDiagnosticCodes.WARN_NOMINAL_STRING_CAPACITY_OVERFLOW;
                    }
                    var formattedMsg = StvnDiagnosticFormatter.formatPlugin(msg, diagCode);

                    // Blame Attribution Invariant: register problem strictly on LHS TypeDefTarget
                    registerCapacityProblem(holder, target, formattedMsg, isError);
                    if (schemaType != null) {
                        registerCapacityIntentions(holder, schemaType, isError);
                    }
                }
            }

            private void inspectTypeEntry(@NotNull TypeEntry typeEntry) {
                var schemaType = typeEntry.getSchemaType();
                if (schemaType == null) return;
                var resolvedInfo = resolveEffectiveTypeCapacity(schemaType, typeEntry.getContainingFile(), new HashSet<>());
                if (resolvedInfo == null || (!resolvedInfo.isString && !resolvedInfo.isCollection)) return;

                boolean hasDirectCapacityFacet = false;
                int directCapacity = -1;
                var metaMap = schemaType.getMetadataMap();
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        var text = entry.getText();
                        if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE) || text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                            hasDirectCapacityFacet = true;
                            var intLit = PsiTreeUtil.findChildOfType(entry, org.stvnadore.psi.IntegerLiteral.class);
                            if (intLit != null) {
                                try { directCapacity = Integer.parseInt(intLit.getText()); } catch (Exception ignored) {}
                            }
                        } else if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                            hasDirectCapacityFacet = true;
                        }
                    }
                }
                boolean hasCapacityFacet = hasDirectCapacityFacet || resolvedInfo.hasExplicitCapacity;
                int effectiveCapacity = hasDirectCapacityFacet ? directCapacity : resolvedInfo.capacity;
                boolean isUnadorned = !hasCapacityFacet;
                boolean exceedsThreshold = effectiveCapacity > thresholdCapacity;

                if (isUnadorned || exceedsThreshold) {
                    boolean isError = isErrorSeverity(holder);
                    String typeName = schemaType.getText().trim();
                    String category = resolvedInfo.isCollection ? "collection" : "string";
                    String units = resolvedInfo.isCollection ? (typeName.contains(StvnVocabulary.TYPE_MAP) ? "entries" : "elements") : "characters";
                    String msg = isUnadorned
                        ? "Nominal " + category + " type '" + typeName + "' is unadorned; default capacity is " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " " + units + ". Specify explicit capacity facets '{ #minSize ... #maxSize ... }'."
                        : "Nominal " + category + " type '" + typeName + "' specifies capacity " + effectiveCapacity + ", exceeding configured threshold of " + thresholdCapacity + " " + units + ".";
                    String diagCode = isUnadorned ? StvnDiagnosticCodes.WARN_NOMINAL_STRING_UNADORNED : StvnDiagnosticCodes.WARN_NOMINAL_STRING_CAPACITY_OVERFLOW;
                    registerCapacityProblem(holder, schemaType, StvnDiagnosticFormatter.formatPlugin(msg, diagCode), isError);
                }
            }

            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition typeDefinition) {
                super.visitTypeDefinition(typeDefinition);
                inspectTypeDefinition(typeDefinition);
            }

            @Override
            public void visitTypeEntry(@NotNull TypeEntry typeEntry) {
                super.visitTypeEntry(typeEntry);
                inspectTypeEntry(typeEntry);
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
                        StvnDiagnosticFormatter.formatPlugin(
                            "String literal length (" + content.length() + ") exceeds declared schema capacity (" + capacityBound + ")",
                            StvnDiagnosticCodes.WARN_STRING_LITERAL_CAPACITY_OVERFLOW
                        ),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new TruncateStringLiteralQuickFix(capacityBound),
                        new WidenSchemaCapacityQuickFix(schemaType, content.length())
                    );
                }
            }
        };
    }

    private void registerCapacityProblem(ProblemsHolder holder, PsiElement targetElem, String formattedMsg, boolean isError) {
        if (isError) {
            holder.registerProblem(
                targetElem,
                formattedMsg,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                new ApplyNominalCapacityQuickFix("Set nominal capacity to " + thresholdCapacity, "{ " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.TOP),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 0.." + thresholdCapacity + " (allow empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.HIGH),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 1.." + thresholdCapacity + " (non-empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.NORMAL)
            );
        } else {
            holder.registerProblem(
                targetElem,
                formattedMsg,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                new ApplyNominalCapacityQuickFix("Set nominal capacity to " + thresholdCapacity, "{ " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.TOP),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 0.." + thresholdCapacity + " (allow empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.HIGH),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 1.." + thresholdCapacity + " (non-empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.NORMAL),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " (default allocation cap)", "{ " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " }", PriorityAction.Priority.LOW),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 0.." + DEFAULT_UNBOUNDED_STRING_CAPACITY + " (allow empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " }", PriorityAction.Priority.BOTTOM),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 1.." + DEFAULT_UNBOUNDED_STRING_CAPACITY + " (non-empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " }", PriorityAction.Priority.BOTTOM)
            );
        }
    }

    private void registerCapacityIntentions(ProblemsHolder holder, PsiElement targetElem, boolean isError) {
        if (isError) {
            holder.registerProblem(
                targetElem,
                "",
                ProblemHighlightType.INFORMATION,
                new ApplyNominalCapacityQuickFix("Set nominal capacity to " + thresholdCapacity, "{ " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.TOP),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 0.." + thresholdCapacity + " (allow empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.HIGH),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 1.." + thresholdCapacity + " (non-empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.NORMAL)
            );
        } else {
            holder.registerProblem(
                targetElem,
                "",
                ProblemHighlightType.INFORMATION,
                new ApplyNominalCapacityQuickFix("Set nominal capacity to " + thresholdCapacity, "{ " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.TOP),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 0.." + thresholdCapacity + " (allow empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.HIGH),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 1.." + thresholdCapacity + " (non-empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + thresholdCapacity + " }", PriorityAction.Priority.NORMAL),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " (default allocation cap)", "{ " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " }", PriorityAction.Priority.LOW),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 0.." + DEFAULT_UNBOUNDED_STRING_CAPACITY + " (allow empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " }", PriorityAction.Priority.BOTTOM),
                new ApplyNominalCapacityQuickFix("Set nominal capacity to 1.." + DEFAULT_UNBOUNDED_STRING_CAPACITY + " (non-empty)", "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + DEFAULT_UNBOUNDED_STRING_CAPACITY + " }", PriorityAction.Priority.BOTTOM)
            );
        }
    }

    public static final class EffectiveTypeCapacity {
        public final boolean isString;
        public final boolean isCollection;
        public final int capacity;
        public final boolean hasExplicitCapacity;

        public EffectiveTypeCapacity(boolean isString, boolean isCollection, int capacity, boolean hasExplicitCapacity) {
            this.isString = isString;
            this.isCollection = isCollection;
            this.capacity = capacity;
            this.hasExplicitCapacity = hasExplicitCapacity;
        }
    }

    public static @Nullable EffectiveTypeCapacity resolveEffectiveTypeCapacity(SchemaType schemaType, PsiFile file, Set<String> visited) {
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            if (constructor.getCollectionType() != null) {
                return new EffectiveTypeCapacity(false, true, DEFAULT_UNBOUNDED_STRING_CAPACITY, false);
            }
            if (constructor.getAtomicType() != null) {
                String text = constructor.getAtomicType().getText().trim();
                if (StvnVocabulary.TYPE_STRING.equals(text)) {
                    return new EffectiveTypeCapacity(true, false, DEFAULT_UNBOUNDED_STRING_CAPACITY, false);
                }
                return null;
            }
        }
        var kw = schemaType.getTypeKeyword();
        if (kw != null) {
            String typeName = kw.getText().trim();
            return traceTypeHierarchy(file, typeName, visited);
        }
        return null;
    }

    public static @Nullable EffectiveTypeCapacity traceTypeHierarchy(PsiFile file, String targetName, Set<String> visited) {
        if (!visited.add(targetName)) {
            return null;
        }
        if (StvnVocabulary.TYPE_STRING.equals(targetName)) {
            return new EffectiveTypeCapacity(true, false, DEFAULT_UNBOUNDED_STRING_CAPACITY, false);
        }
        if (StvnVocabulary.TYPE_SEQ.equals(targetName) || StvnVocabulary.TYPE_SET.equals(targetName) || StvnVocabulary.TYPE_MAP.equals(targetName)) {
            return new EffectiveTypeCapacity(false, true, DEFAULT_UNBOUNDED_STRING_CAPACITY, false);
        }
        if (PRELUDE_STRING_CAPACITIES.containsKey(targetName)) {
            return new EffectiveTypeCapacity(true, false, PRELUDE_STRING_CAPACITIES.get(targetName), true);
        }

        // Trace :use imports
        var useStmts = PsiTreeUtil.findChildrenOfType(file, org.stvnadore.psi.UseStmt.class);
        for (var use : useStmts) {
            var aliasBlock = use.getUseAliasBlock();
            if (aliasBlock != null) {
                for (var alias : aliasBlock.getUseMapAliasList()) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var remoteKw = list.get(0);
                        var localKw = list.get(1);
                        if (localKw != null && localKw.getText().equals(targetName) && remoteKw != null) {
                            var target = use.getUseTarget();
                            String remoteName = remoteKw.getText();
                            if (target != null) {
                                String fqni = target.getText() + "/" + (remoteName.startsWith(":") ? remoteName.substring(1) : remoteName);
                                if (PRELUDE_STRING_CAPACITIES.containsKey(fqni) || PRELUDE_STRING_CAPACITIES.containsKey(remoteName)) {
                                    int cap = PRELUDE_STRING_CAPACITIES.getOrDefault(fqni, PRELUDE_STRING_CAPACITIES.get(remoteName));
                                    return new EffectiveTypeCapacity(true, false, cap, true);
                                }
                                var res = traceTypeHierarchy(file, fqni, visited);
                                if (res != null) return res;
                            }
                            var res = traceTypeHierarchy(file, remoteName, visited);
                            if (res != null) return res;
                        }
                    }
                }
            }
            var optBlock = use.getUseOptionsBlock();
            if (optBlock != null && optBlock.getText().contains("#strip")) {
                var target = use.getUseTarget();
                if (target != null && targetName.startsWith(":") && !targetName.substring(1).contains("/")) {
                    String fqni = target.getText() + "/" + targetName.substring(1);
                    if (PRELUDE_STRING_CAPACITIES.containsKey(fqni)) {
                        return new EffectiveTypeCapacity(true, false, PRELUDE_STRING_CAPACITIES.get(fqni), true);
                    }
                }
            }
        }

        // Trace :include imports
        var includes = PsiTreeUtil.findChildrenOfType(file, IncludeElement.class);
        for (var incl : includes) {
            var aliasBlock = incl.getIncludeAliasBlock();
            if (aliasBlock != null) {
                var aliases = PsiTreeUtil.findChildrenOfType(aliasBlock, IncludeMapAlias.class);
                for (var alias : aliases) {
                    var list = alias.getTypeKeywordList();
                    if (list.size() >= 2) {
                        var remoteKw = list.get(0);
                        var localKw = list.get(1);
                        if (localKw != null && localKw.getText().equals(targetName) && remoteKw != null) {
                            var stringLit = incl.getStringLiteral();
                            if (stringLit != null) {
                                var targetFile = StvnTypeReference.resolveIncludeFile(stringLit);
                                if (targetFile != null) {
                                    return traceTypeHierarchy(targetFile, remoteKw.getText(), visited);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Trace local definitions
        var defs = PsiTreeUtil.findChildrenOfType(file, TypeDefinition.class);
        for (var def : defs) {
            var target = def.getTypeDefTarget();
            if (target != null && target.getText().equals(targetName)) {
                var metaMap = def.getMetadataMap() != null ? def.getMetadataMap() : (def.getSchemaType() != null ? def.getSchemaType().getMetadataMap() : null);
                int explicitCap = -1;
                boolean hasCap = false;
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        var text = entry.getText();
                        if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE) || text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                            hasCap = true;
                            var intLit = PsiTreeUtil.findChildOfType(entry, org.stvnadore.psi.IntegerLiteral.class);
                            if (intLit != null) {
                                try { explicitCap = Integer.parseInt(intLit.getText()); } catch (Exception ignored) {}
                            }
                        } else if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                            hasCap = true;
                        }
                    }
                }
                if (def.getSchemaType() != null) {
                    var downstream = resolveEffectiveTypeCapacity(def.getSchemaType(), file, visited);
                    if (downstream != null) {
                        if (hasCap) {
                            return new EffectiveTypeCapacity(downstream.isString, downstream.isCollection, explicitCap, true);
                        }
                        return downstream;
                    }
                }
            }
        }

        // Trace Prelude Bridge
        var preludeDef = StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), targetName);
        if (preludeDef != null && preludeDef.getSchemaType() != null) {
            var metaMap = preludeDef.getMetadataMap() != null ? preludeDef.getMetadataMap() : preludeDef.getSchemaType().getMetadataMap();
            int explicitCap = -1;
            boolean hasCap = false;
            if (metaMap != null) {
                for (var entry : metaMap.getMetadataEntryList()) {
                    var text = entry.getText();
                    if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE) || text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                        hasCap = true;
                        var intLit = PsiTreeUtil.findChildOfType(entry, org.stvnadore.psi.IntegerLiteral.class);
                        if (intLit != null) {
                            try { explicitCap = Integer.parseInt(intLit.getText()); } catch (Exception ignored) {}
                        }
                    }
                }
            }
            var downstream = resolveEffectiveTypeCapacity(preludeDef.getSchemaType(), preludeDef.getContainingFile(), visited);
            if (downstream != null) {
                if (hasCap) {
                    return new EffectiveTypeCapacity(downstream.isString, downstream.isCollection, explicitCap, true);
                }
                return downstream;
            }
        }

        return null;
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
        var info = resolveEffectiveTypeCapacity(schemaType, file, new HashSet<>());
        return info != null ? info.capacity : -1;
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
     * Structural QuickFix injecting explicit capacity facets immediately before the type constructor.
     */
    public static final class ApplyNominalCapacityQuickFix implements LocalQuickFix, PriorityAction {
        private final String actionName;
        private final String facetSnippet;
        private final Priority priority;

        /**
         * Constructs a new ApplyNominalCapacityQuickFix with explicit intention priority.
         *
         * @param actionName the user-visible intention action name
         * @param facetSnippet the metadata facet snippet (e.g. {@code { #maxSize 4096 }})
         * @param priority the intention display priority
         */
        public ApplyNominalCapacityQuickFix(String actionName, String facetSnippet, Priority priority) {
            this.actionName = actionName;
            this.facetSnippet = facetSnippet;
            this.priority = priority;
        }

        /**
         * Constructs a new ApplyNominalCapacityQuickFix with normal priority.
         *
         * @param actionName the user-visible intention action name
         * @param facetSnippet the metadata facet snippet (e.g. {@code { #maxSize 4096 }})
         */
        public ApplyNominalCapacityQuickFix(String actionName, String facetSnippet) {
            this(actionName, facetSnippet, Priority.NORMAL);
        }

        private static boolean isCapacityFacet(MetadataEntry entry) {
            String text = entry.getText().trim();
            return text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)
                || text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)
                || text.startsWith(StvnVocabulary.FACET_KW_SIZE);
        }

        @Override
        public @NotNull Priority getPriority() {
            return priority;
        }

        @Override
        public @NotNull String getName() {
            return actionName;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Set nominal capacity";
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

            var enclosingSchema = element instanceof SchemaType st ? st : PsiTreeUtil.getParentOfType(element, SchemaType.class);
            var typeDef = element instanceof TypeDefinition td ? td : PsiTreeUtil.getParentOfType(element, TypeDefinition.class);
            var metaMap = (enclosingSchema != null && enclosingSchema.getMetadataMap() != null)
                ? enclosingSchema.getMetadataMap()
                : (typeDef != null ? (typeDef.getMetadataMap() != null ? typeDef.getMetadataMap() : (typeDef.getSchemaType() != null ? typeDef.getSchemaType().getMetadataMap() : null)) : null);

            if (metaMap != null) {
                var entries = metaMap.getMetadataEntryList();
                boolean allCapacity = entries.isEmpty() || entries.stream().allMatch(ApplyNominalCapacityQuickFix::isCapacityFacet);
                if (allCapacity) {
                    // Replace the entire metadata map text range with the new snippet
                    var range = metaMap.getTextRange();
                    doc.replaceString(range.getStartOffset(), range.getEndOffset(), facetSnippet);
                } else {
                    // Mixed facets: delete existing capacity entries and insert snippet before closing '}'
                    var capacityEntries = entries.stream()
                        .filter(ApplyNominalCapacityQuickFix::isCapacityFacet)
                        .sorted((a, b) -> Integer.compare(b.getTextRange().getStartOffset(), a.getTextRange().getStartOffset()))
                        .toList();

                    for (var capEntry : capacityEntries) {
                        int start = capEntry.getTextRange().getStartOffset();
                        int end = capEntry.getTextRange().getEndOffset();
                        if (start > 0 && doc.getCharsSequence().charAt(start - 1) == ' ') {
                            start--;
                        }
                        doc.deleteString(start, end);
                    }

                    int openOffset = metaMap.getTextRange().getStartOffset();
                    int closeOffset = doc.getText().indexOf('}', openOffset);
                    if (closeOffset >= 0) {
                        String innerFacets = facetSnippet.replace("{", "").replace("}", "").trim();
                        if (closeOffset > 0 && doc.getCharsSequence().charAt(closeOffset - 1) == ' ') {
                            doc.insertString(closeOffset - 1, " " + innerFacets);
                        } else {
                            doc.insertString(closeOffset, " " + innerFacets + " ");
                        }
                    }
                }
            } else {
                // Unadorned type constructor: insert metadata map immediately before constructor
                int startOffset;
                if (typeDef != null && typeDef.getSchemaType() != null) {
                    startOffset = typeDef.getSchemaType().getTextRange().getStartOffset();
                } else {
                    startOffset = element.getTextRange().getStartOffset();
                }
                doc.insertString(startOffset, facetSnippet + " ");
            }
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

            var parentDef = PsiTreeUtil.getParentOfType(schemaType, TypeDefinition.class);
            if (parentDef == null && schemaType.getTypeKeyword() != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, schemaType.getTypeKeyword().getText().trim(), new java.util.HashSet<>());
                if (resolved != null) {
                    parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                }
            }
            if (parentDef != null) {
                var metaMap = parentDef.getMetadataMap();
                if (metaMap != null) {
                    for (var entry : metaMap.getMetadataEntryList()) {
                        var text = entry.getText();
                        if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE) || text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                            var intLit = PsiTreeUtil.findChildOfType(entry, org.stvnadore.psi.IntegerLiteral.class);
                            if (intLit != null) {
                                var range = intLit.getTextRange();
                                doc.replaceString(range.getStartOffset(), range.getEndOffset(), String.valueOf(requiredCapacity));
                                docManager.commitDocument(doc);
                                return;
                            }
                        }
                    }
                }
            }
            var atomicType = PsiTreeUtil.findChildOfType(schemaType, AtomicType.class);
            PsiElement targetElem = atomicType != null ? atomicType : schemaType.getTypeKeyword();
            if (targetElem != null) {
                String widened = "{ " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + requiredCapacity + " } " + StvnVocabulary.TYPE_STRING;
                var range = targetElem.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), widened);
                docManager.commitDocument(doc);
            }
        }
    }

    /**
     * Inspects whether the specified type identifier represents an STVN nominal string type.
     *
     * @param typeName the type identifier to inspect (e.g. {@code :String})
     * @return true if the type identifier belongs to the STVN string taxonomy, false otherwise
     */
    public static boolean isNominalStringType(@Nullable String typeName) {
        if (typeName == null) {
            return false;
        }
        return typeName.equals(StvnVocabulary.TYPE_STRING)
            || PRELUDE_STRING_CAPACITIES.containsKey(typeName);
    }
}
