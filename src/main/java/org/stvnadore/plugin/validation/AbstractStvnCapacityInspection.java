package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.FileModifier;
import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
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
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.psi.AtomicType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.CollectionType;
import org.stvnadore.psi.CollectionValue;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.ProductType;
import org.stvnadore.psi.SchemaConstructor;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.SumType;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.Visitor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared Pure-PSI capacity governance engine parameterized by {@link CapacityTargetKind}.
 * <p>
 * Enforces explicit {@code #minSize} and {@code #maxSize} capacity facets on root constructors
 * in {@code :defs} and {@code :type}, flags declared capacities exceeding the configured
 * inspection threshold, and optionally inspects {@code :body} and {@code #CONST} literals.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public abstract class AbstractStvnCapacityInspection extends LocalInspectionTool {

    /**
     * Default capacity threshold across string and collection governance inspections (4,096).
     */
    public static final int DEFAULT_INSPECTION_THRESHOLD = StvnVocabulary.DEFAULT_INSPECTION_THRESHOLD;

    /**
     * Maximum configurable capacity threshold matching STVN default allocation cap (16,777,216).
     */
    public static final int MAX_INSPECTION_THRESHOLD = StvnVocabulary.DEFAULT_MAX_SIZE.intValue();

    /**
     * Partitioned target constructor taxonomy governed by each peer capacity inspection.
     */
    public enum CapacityTargetKind {
        STRING("string", "characters", StvnVocabulary.TYPE_STRING),
        SEQ("collection", "elements", StvnVocabulary.TYPE_SEQ),
        SET("collection", "elements", StvnVocabulary.TYPE_SET),
        MAP("collection", "entries", StvnVocabulary.TYPE_MAP);

        private final String schemaCategoryNoun;
        private final String unitNoun;
        private final String constructorKeyword;

        CapacityTargetKind(String schemaCategoryNoun, String unitNoun, String constructorKeyword) {
            this.schemaCategoryNoun = schemaCategoryNoun;
            this.unitNoun = unitNoun;
            this.constructorKeyword = constructorKeyword;
        }

        /**
         * Returns the schema category noun ({@code string} or {@code collection}).
         *
         * @return schema category noun
         */
        public String schemaCategoryNoun() {
            return schemaCategoryNoun;
        }

        /**
         * Returns the unit noun ({@code characters}, {@code elements}, or {@code entries}).
         *
         * @return unit noun
         */
        public String unitNoun() {
            return unitNoun;
        }

        /**
         * Returns the canonical STVN constructor keyword ({@code :String}, {@code :Seq}, {@code :Set}, or {@code :Map}).
         *
         * @return constructor keyword
         */
        public String constructorKeyword() {
            return constructorKeyword;
        }

        /**
         * Returns the singular literal kind noun ({@code string}, {@code sequence}, {@code set}, or {@code map}).
         *
         * @return literal kind noun
         */
        public String literalKindNoun() {
            return switch (this) {
                case STRING -> "string";
                case SEQ -> "sequence";
                case SET -> "set";
                case MAP -> "map";
            };
        }

        /**
         * Returns {@code true} when this kind represents a collection constructor.
         *
         * @return {@code true} for {@code SEQ}, {@code SET}, or {@code MAP}
         */
        public boolean isCollection() {
            return this != STRING;
        }
    }

    /**
     * Resolved capacity lineage profile for a {@link SchemaType}.
     *
     * @param kind                grounded {@link CapacityTargetKind}
     * @param capacity            effective {@code #maxSize} bound (defaults to {@code 16,777,216})
     * @param hasExplicitCapacity {@code true} when any {@code #minSize}, {@code #maxSize}, or {@code #size} is declared
     * @param hasExplicitMaxSize  {@code true} when an explicit upper {@code #maxSize} (or Prelude cap) is declared
     */
    public record ResolvedCapacityProfile(
        CapacityTargetKind kind,
        BigInteger capacity,
        boolean hasExplicitCapacity,
        boolean hasExplicitMaxSize
    ) {}

    private final CapacityTargetKind targetKind;

    /**
     * Constructs a capacity governance inspection bound to {@code targetKind}.
     *
     * @param targetKind governed {@link CapacityTargetKind}
     */
    protected AbstractStvnCapacityInspection(CapacityTargetKind targetKind) {
        this.targetKind = targetKind;
    }

    /**
     * Returns the {@link CapacityTargetKind} governed exclusively by this inspection.
     *
     * @return governed target kind
     */
    public final CapacityTargetKind getTargetKind() {
        return targetKind;
    }

    protected abstract int getConfiguredThreshold();

    protected abstract boolean isInspectLiteralsEnabled();

    protected abstract String getConfiguredSeverityOverride();

    protected abstract String getThresholdFieldName();

    protected abstract String getInspectLiteralsFieldName();

    protected abstract String getThresholdOptionLabel();

    protected abstract String getInspectLiteralsOptionLabel();

    @Override
    public @NotNull String getGroupDisplayName() {
        return "Capacity";
    }

    @Override
    public String @NotNull [] getGroupPath() {
        return new String[]{"STVN", "Governance", "Capacity"};
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull OptPane getOptionsPane() {
        return OptPane.pane(
            OptPane.number(
                getThresholdFieldName(),
                getThresholdOptionLabel(),
                1,
                MAX_INSPECTION_THRESHOLD
            ),
            OptPane.checkbox(
                getInspectLiteralsFieldName(),
                getInspectLiteralsOptionLabel()
            )
        );
    }

    /**
     * Determines whether the active inspection profile evaluates this inspection at {@code ERROR} severity.
     *
     * @param holder active problems holder
     * @return {@code true} when severity is {@code ERROR}
     */
    public boolean isErrorSeverity(ProblemsHolder holder) {
        if ("ERROR".equalsIgnoreCase(getConfiguredSeverityOverride())) {
            return true;
        }
        var key = HighlightDisplayKey.find(getShortName());
        if (key != null) {
            var profile = InspectionProjectProfileManager.getInstance(holder.getProject()).getCurrentProfile();
            var level = profile.getErrorLevel(key, holder.getFile());
            if (level != null && HighlightSeverity.ERROR.equals(level.getSeverity())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition typeDefinition) {
                super.visitTypeDefinition(typeDefinition);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeDefinition)) {
                    return;
                }
                if (PsiTreeUtil.getParentOfType(typeDefinition, DefsEntry.class) == null) {
                    return;
                }
                var schemaType = typeDefinition.getSchemaType();
                if (schemaType == null) {
                    return;
                }
                inspectTopLevelTypeDefinition(holder, isOnTheFly, typeDefinition, typeDefinition.getTypeDefTarget(), schemaType);
                inspectCompositeChildSchemas(holder, schemaType, false);
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constantDefinition) {
                super.visitConstantDefinition(constantDefinition);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constantDefinition)) {
                    return;
                }
                if (PsiTreeUtil.getParentOfType(constantDefinition, DefsEntry.class) == null) {
                    return;
                }
                var schemaType = constantDefinition.getSchemaType();
                if (schemaType == null) {
                    return;
                }
                inspectInlineCompositeSlot(holder, schemaType, true);
                inspectCompositeChildSchemas(holder, schemaType, true);
            }

            @Override
            public void visitTypeEntry(@NotNull TypeEntry typeEntry) {
                super.visitTypeEntry(typeEntry);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeEntry)) {
                    return;
                }
                var schemaType = typeEntry.getSchemaType();
                if (schemaType == null) {
                    return;
                }
                inspectTopLevelTypeEntry(holder, schemaType);
                inspectCompositeChildSchemas(holder, schemaType, false);
            }

            @Override
            public void visitStringLiteral(@NotNull StringLiteral literal) {
                super.visitStringLiteral(literal);
                if (targetKind != CapacityTargetKind.STRING
                        || !isInspectLiteralsEnabled()
                        || StvnMetaTemplateUtil.isInSecondaryRootSection(literal)) {
                    return;
                }
                inspectStringLiteralCapacity(holder, literal);
            }

            @Override
            public void visitListLiteral(@NotNull ListLiteral literal) {
                super.visitListLiteral(literal);
                if ((targetKind != CapacityTargetKind.SEQ && targetKind != CapacityTargetKind.SET)
                        || !isInspectLiteralsEnabled()
                        || StvnMetaTemplateUtil.isInSecondaryRootSection(literal)) {
                    return;
                }
                inspectCollectionLiteralCapacity(holder, literal, literal.getValueList().size());
            }

            @Override
            public void visitMapLiteral(@NotNull MapLiteral literal) {
                super.visitMapLiteral(literal);
                if (targetKind != CapacityTargetKind.MAP
                        || !isInspectLiteralsEnabled()
                        || StvnMetaTemplateUtil.isInSecondaryRootSection(literal)) {
                    return;
                }
                inspectCollectionLiteralCapacity(holder, literal, countMapLiteralEntries(literal));
            }
        };
    }

    private void inspectTopLevelTypeDefinition(
            ProblemsHolder holder,
            boolean isOnTheFly,
            TypeDefinition typeDef,
            PsiElement target,
            SchemaType schemaType
    ) {
        if (schemaType.getSchemaConstructor() == null) {
            return;
        }
        var metaMap = schemaType.getMetadataMap() != null ? schemaType.getMetadataMap() : typeDef.getMetadataMap();
        if (metaMap != null && PsiTreeUtil.hasErrorElements(metaMap)) {
            return;
        }
        var resolved = resolveCapacityProfile(schemaType, typeDef.getContainingFile(), new HashSet<>());
        if (resolved == null || resolved.kind() != targetKind) {
            return;
        }
        DirectFacetScan direct = scanDirectCapacityFacets(metaMap);
        boolean hasCapacityFacet = direct.hasAnyCapacityFacet() || resolved.hasExplicitCapacity();
        BigInteger effectiveCapacity = direct.directMaxCapacity() != null
            ? direct.directMaxCapacity()
            : (direct.hasAnyCapacityFacet() ? StvnVocabulary.DEFAULT_MAX_SIZE : resolved.capacity());

        int threshold = clampThreshold(getConfiguredThreshold());
        boolean isUnadorned = !hasCapacityFacet;
        boolean exceedsThreshold = effectiveCapacity.compareTo(BigInteger.valueOf(threshold)) > 0;
        if (!isUnadorned && !exceedsThreshold) {
            return;
        }

        boolean isError = isErrorSeverity(holder);
        String declaredName = target.getText().trim();
        String msg = formatSchemaCapacityMessage(
            "Nominal " + targetKind.schemaCategoryNoun() + " type '",
            declaredName,
            isUnadorned,
            effectiveCapacity,
            threshold
        );
        String diagCode = resolveSchemaDiagnosticCode(isUnadorned);
        registerCapacityProblem(holder, target, StvnDiagnosticFormatter.formatPlugin(msg, diagCode), threshold, isError);
        if (isOnTheFly) {
            registerCapacityIntentions(holder, resolveHighlightAnchor(schemaType), threshold, isError);
        }
    }

    private void inspectTopLevelTypeEntry(ProblemsHolder holder, SchemaType schemaType) {
        if (schemaType.getSchemaConstructor() == null) {
            return;
        }
        var metaMap = schemaType.getMetadataMap();
        if (metaMap != null && PsiTreeUtil.hasErrorElements(metaMap)) {
            return;
        }
        var resolved = resolveCapacityProfile(schemaType, schemaType.getContainingFile(), new HashSet<>());
        if (resolved == null || resolved.kind() != targetKind) {
            return;
        }
        DirectFacetScan direct = scanDirectCapacityFacets(metaMap);
        boolean hasCapacityFacet = direct.hasAnyCapacityFacet() || resolved.hasExplicitCapacity();
        BigInteger effectiveCapacity = direct.directMaxCapacity() != null
            ? direct.directMaxCapacity()
            : (direct.hasAnyCapacityFacet() ? StvnVocabulary.DEFAULT_MAX_SIZE : resolved.capacity());

        int threshold = clampThreshold(getConfiguredThreshold());
        boolean isUnadorned = !hasCapacityFacet;
        boolean exceedsThreshold = effectiveCapacity.compareTo(BigInteger.valueOf(threshold)) > 0;
        if (!isUnadorned && !exceedsThreshold) {
            return;
        }

        boolean isError = isErrorSeverity(holder);
        String typeName = resolveSchemaDisplayName(schemaType);
        String msg = formatSchemaCapacityMessage(
            "Nominal " + targetKind.schemaCategoryNoun() + " type '",
            typeName,
            isUnadorned,
            effectiveCapacity,
            threshold
        );
        String diagCode = resolveSchemaDiagnosticCode(isUnadorned);
        registerCapacityProblem(
            holder,
            resolveHighlightAnchor(schemaType),
            StvnDiagnosticFormatter.formatPlugin(msg, diagCode),
            threshold,
            isError
        );
    }

    private void inspectCompositeChildSchemas(
            ProblemsHolder holder,
            SchemaType parentSchema,
            boolean inConstantDefinition
    ) {
        SchemaConstructor constructor = parentSchema.getSchemaConstructor();
        if (constructor == null) {
            return;
        }
        for (SchemaType childSlot : extractDirectChildSchemas(constructor)) {
            inspectInlineCompositeSlot(holder, childSlot, inConstantDefinition);
            inspectCompositeChildSchemas(holder, childSlot, inConstantDefinition);
        }
    }

    private void inspectInlineCompositeSlot(
            ProblemsHolder holder,
            SchemaType slotSchema,
            boolean inConstantDefinition
    ) {
        if (slotSchema.getSchemaConstructor() == null) {
            return;
        }
        var metaMap = slotSchema.getMetadataMap();
        if (metaMap != null && PsiTreeUtil.hasErrorElements(metaMap)) {
            return;
        }
        var resolved = resolveCapacityProfile(slotSchema, slotSchema.getContainingFile(), new HashSet<>());
        if (resolved == null || resolved.kind() != targetKind) {
            return;
        }
        DirectFacetScan direct = scanDirectCapacityFacets(metaMap);
        boolean hasCapacityFacet = direct.hasAnyCapacityFacet() || resolved.hasExplicitCapacity();
        BigInteger effectiveCapacity = direct.directMaxCapacity() != null
            ? direct.directMaxCapacity()
            : (direct.hasAnyCapacityFacet() ? StvnVocabulary.DEFAULT_MAX_SIZE : resolved.capacity());

        boolean isUnadorned = !hasCapacityFacet;
        if (inConstantDefinition && isUnadorned) {
            return;
        }
        int threshold = clampThreshold(getConfiguredThreshold());
        boolean exceedsThreshold = effectiveCapacity.compareTo(BigInteger.valueOf(threshold)) > 0;
        if (!isUnadorned && !exceedsThreshold) {
            return;
        }

        boolean isError = isErrorSeverity(holder);
        String typeName = resolveSchemaDisplayName(slotSchema);
        String ownerPrefix = inConstantDefinition
            ? "Constant " + targetKind.schemaCategoryNoun() + " schema '"
            : "Nominal " + targetKind.schemaCategoryNoun() + " type '";
        String msg = formatSchemaCapacityMessage(ownerPrefix, typeName, isUnadorned, effectiveCapacity, threshold);
        String diagCode = resolveSchemaDiagnosticCode(isUnadorned);
        registerCapacityProblem(
            holder,
            resolveHighlightAnchor(slotSchema),
            StvnDiagnosticFormatter.formatPlugin(msg, diagCode),
            threshold,
            isError
        );
    }

    private void inspectStringLiteralCapacity(ProblemsHolder holder, StringLiteral literal) {
        Value valueNode = PsiTreeUtil.getParentOfType(literal, Value.class, false);
        if (valueNode == null || !isInBodyOrConstantPayload(valueNode)) {
            return;
        }
        SchemaType governingSchema = resolveLeafGoverningSchemaForLiteral(valueNode, CapacityTargetKind.STRING);
        if (governingSchema == null) {
            return;
        }
        ResolvedCapacityProfile profile = resolveEffectiveBoundWithDirectFacets(governingSchema);
        if (profile == null || profile.kind() != CapacityTargetKind.STRING) {
            return;
        }
        boolean preserveIndent = StvnPsiLiteralEvaluator.resolvePreserveIndentForStringLiteral(literal);
        var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(literal.getText(), preserveIndent);
        if (decodedOpt.isEmpty()) {
            return;
        }
        String decoded = decodedOpt.get();
        int decodedLength = decoded.codePointCount(0, decoded.length());
        BigInteger thresholdBI = BigInteger.valueOf(clampThreshold(getConfiguredThreshold()));
        boolean boundedByDeclaredSchema = profile.hasExplicitMaxSize() && profile.capacity().compareTo(thresholdBI) <= 0;
        BigInteger effectiveLimit = boundedByDeclaredSchema ? profile.capacity() : thresholdBI;

        if (BigInteger.valueOf(decodedLength).compareTo(effectiveLimit) <= 0) {
            return;
        }
        int capInt = effectiveLimit.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
            ? Integer.MAX_VALUE
            : effectiveLimit.intValue();
        String msg = boundedByDeclaredSchema
            ? "String literal length (" + decodedLength + ") exceeds declared schema capacity (" + effectiveLimit + ")"
            : "String literal length (" + decodedLength + ") exceeds configured capacity threshold (" + effectiveLimit + " characters)";
        holder.registerProblem(
            literal,
            StvnDiagnosticFormatter.formatPlugin(msg, StvnDiagnosticCodes.WARN_STRING_LITERAL_CAPACITY_OVERFLOW),
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new StvnStringCapacityInspection.TruncateStringLiteralQuickFix(capInt),
            new StvnStringCapacityInspection.WidenSchemaCapacityQuickFix(governingSchema, decodedLength)
        );
    }

    private void inspectCollectionLiteralCapacity(ProblemsHolder holder, PsiElement literal, int itemCount) {
        if (PsiTreeUtil.hasErrorElements(literal)) {
            return;
        }
        CollectionValue collVal = literal.getParent() instanceof CollectionValue cv ? cv : null;
        Value valueNode = collVal != null && collVal.getParent() instanceof Value v ? v : null;
        if (valueNode == null || !isInBodyOrConstantPayload(valueNode)) {
            return;
        }
        SchemaType governingSchema = resolveLeafGoverningSchemaForLiteral(valueNode, targetKind);
        if (governingSchema == null) {
            return;
        }
        ResolvedCapacityProfile profile = resolveEffectiveBoundWithDirectFacets(governingSchema);
        if (profile == null || profile.kind() != targetKind) {
            return;
        }
        BigInteger thresholdBI = BigInteger.valueOf(clampThreshold(getConfiguredThreshold()));
        boolean boundedByDeclaredSchema = profile.hasExplicitMaxSize() && profile.capacity().compareTo(thresholdBI) <= 0;
        BigInteger effectiveLimit = boundedByDeclaredSchema ? profile.capacity() : thresholdBI;
        if (BigInteger.valueOf(itemCount).compareTo(effectiveLimit) <= 0) {
            return;
        }

        int capInt = effectiveLimit.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
            ? Integer.MAX_VALUE
            : effectiveLimit.intValue();
        String kindLabel = capitalize(targetKind.literalKindNoun());
        String countNoun = targetKind == CapacityTargetKind.MAP ? "entry count" : "element count";
        String msg = boundedByDeclaredSchema
            ? kindLabel + " literal " + countNoun + " (" + itemCount + ") exceeds declared schema capacity ("
                + effectiveLimit + " " + targetKind.unitNoun() + ")"
            : kindLabel + " literal " + countNoun + " (" + itemCount + ") exceeds configured capacity threshold ("
                + effectiveLimit + " " + targetKind.unitNoun() + ")";

        PsiElement anchor = resolveCollectionLiteralHighlightAnchor(literal);
        holder.registerProblem(
            anchor,
            StvnDiagnosticFormatter.formatPlugin(msg, StvnDiagnosticCodes.WARN_COLLECTION_LITERAL_CAPACITY_OVERFLOW),
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new TruncateCollectionLiteralQuickFix(targetKind, capInt),
            new WidenCollectionSchemaCapacityQuickFix(governingSchema, targetKind, itemCount)
        );
    }

    private static boolean isInBodyOrConstantPayload(Value valueNode) {
        if (PsiTreeUtil.getParentOfType(valueNode, MetadataEntry.class, false) != null) {
            return false;
        }
        return PsiTreeUtil.getParentOfType(valueNode, BodyEntry.class, false) != null
            || PsiTreeUtil.getParentOfType(valueNode, ConstantDefinition.class, false) != null;
    }

    private static @Nullable SchemaType resolveLeafGoverningSchemaForLiteral(
            Value valueNode,
            CapacityTargetKind expectedKind
    ) {
        SchemaType raw = StvnPayloadValueQuickFixProvider.resolveGoverningSchemaForValue(valueNode);
        if (raw == null) {
            ConstantDefinition constDef = PsiTreeUtil.getParentOfType(valueNode, ConstantDefinition.class, false);
            if (constDef != null && constDef.getValue() == valueNode) {
                raw = constDef.getSchemaType();
            } else if (PsiTreeUtil.getParentOfType(valueNode, BodyEntry.class, false) != null) {
                TypeEntry typeEntry = PsiTreeUtil.findChildOfType(valueNode.getContainingFile(), TypeEntry.class);
                if (typeEntry != null) {
                    raw = typeEntry.getSchemaType();
                }
            }
        }
        if (raw == null) {
            return null;
        }
        return unwrapImplicitSumBranchForKind(raw, expectedKind, new HashSet<>());
    }

    private static @Nullable SchemaType unwrapImplicitSumBranchForKind(
            SchemaType schemaType,
            CapacityTargetKind expectedKind,
            Set<PsiElement> visited
    ) {
        if (!visited.add(schemaType)) {
            return null;
        }
        ResolvedCapacityProfile direct = resolveCapacityProfile(schemaType, schemaType.getContainingFile(), new HashSet<>());
        if (direct != null && direct.kind() == expectedKind) {
            return schemaType;
        }
        SchemaConstructor ctor = schemaType.getSchemaConstructor();
        if (ctor != null && ctor.getSumType() != null) {
            List<SchemaType> matches = new ArrayList<>();
            for (SchemaType branch : PsiTreeUtil.getChildrenOfTypeAsList(ctor.getSumType(), SchemaType.class)) {
                SchemaType resolvedBranch = unwrapImplicitSumBranchForKind(branch, expectedKind, visited);
                if (resolvedBranch != null) {
                    matches.add(resolvedBranch);
                }
            }
            return matches.size() == 1 ? matches.get(0) : null;
        }
        TypeKeyword kw = schemaType.getTypeKeyword();
        if (kw != null) {
            TypeDefinition def = StvnTypeReference.resolveTypeDefinitionAtContext(kw, kw.getText().trim());
            if (def != null && def.getSchemaType() != null && visited.add(def)) {
                SchemaType defSchema = def.getSchemaType();
                SchemaType unwrapped = unwrapImplicitSumBranchForKind(defSchema, expectedKind, visited);
                if (unwrapped != null && defSchema.getSchemaConstructor() != null
                        && defSchema.getSchemaConstructor().getSumType() != null) {
                    return unwrapped;
                }
            }
        }
        return null;
    }

    private static @Nullable ResolvedCapacityProfile resolveEffectiveBoundWithDirectFacets(SchemaType schemaType) {
        ResolvedCapacityProfile base = resolveCapacityProfile(schemaType, schemaType.getContainingFile(), new HashSet<>());
        if (base == null) {
            return null;
        }
        MetadataMap metaMap = schemaType.getMetadataMap();
        if (metaMap == null && schemaType.getParent() instanceof TypeDefinition td && td.getSchemaType() == schemaType) {
            metaMap = td.getMetadataMap();
        }
        DirectFacetScan direct = scanDirectCapacityFacets(metaMap);
        if (direct.directMaxCapacity() != null) {
            return new ResolvedCapacityProfile(base.kind(), direct.directMaxCapacity(), true, true);
        }
        return base;
    }

    /**
     * Resolves the grounded {@link CapacityTargetKind} and effective capacity bound for {@code schemaType}
     * across local {@code :defs}, {@code :package} enclaves, {@code :use} aliases, {@code :include} modules,
     * and the Standard Library Prelude, keying cycle detection strictly on {@link PsiElement} identity.
     *
     * @param schemaType schema type AST node
     * @param file       containing PSI file
     * @param visited    visited {@link PsiElement} cycle guard
     * @return resolved capacity profile, or {@code null} when not a string or collection schema
     */
    public static @Nullable ResolvedCapacityProfile resolveCapacityProfile(
            SchemaType schemaType,
            PsiFile file,
            Set<PsiElement> visited
    ) {
        if (!visited.add(schemaType)) {
            return null;
        }
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            CollectionType coll = constructor.getCollectionType();
            if (coll != null) {
                ASTNode node = coll.getNode();
                if (node.findChildByType(StvnTypes.COLL_MAP) != null) {
                    return new ResolvedCapacityProfile(CapacityTargetKind.MAP, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
                }
                if (node.findChildByType(StvnTypes.COLL_SEQ) != null) {
                    return new ResolvedCapacityProfile(CapacityTargetKind.SEQ, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
                }
                if (node.findChildByType(StvnTypes.COLL_SET) != null) {
                    return new ResolvedCapacityProfile(CapacityTargetKind.SET, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
                }
                return null;
            }
            AtomicType atomic = constructor.getAtomicType();
            if (atomic != null && StvnVocabulary.TYPE_STRING.equals(atomic.getText().trim())) {
                return new ResolvedCapacityProfile(CapacityTargetKind.STRING, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
            }
            return null;
        }
        TypeKeyword kw = schemaType.getTypeKeyword();
        if (kw != null) {
            return traceNominalCapacityLineage(kw, kw.getText().trim(), file, visited);
        }
        return null;
    }

    /**
     * Traces nominal type lineage from {@code contextElement} using {@link PsiElement} declaration identity
     * as the cycle guard so homonymous {@code :Type} declarations across {@code :package} boundaries never false-abort.
     *
     * @param contextElement lexical lookup context element, or {@code null} for file-level lookup
     * @param targetName     referenced nominal type identifier
     * @param file           containing PSI file
     * @param visited        visited {@link PsiElement} cycle guard
     * @return resolved capacity profile, or {@code null} when unresolvable or non-capacity-governed
     */
    public static @Nullable ResolvedCapacityProfile traceNominalCapacityLineage(
            @Nullable PsiElement contextElement,
            String targetName,
            PsiFile file,
            Set<PsiElement> visited
    ) {
        if (StvnVocabulary.TYPE_STRING.equals(targetName)) {
            return new ResolvedCapacityProfile(CapacityTargetKind.STRING, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
        }
        if (StvnVocabulary.TYPE_MAP.equals(targetName)) {
            return new ResolvedCapacityProfile(CapacityTargetKind.MAP, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
        }
        if (StvnVocabulary.TYPE_SEQ.equals(targetName)) {
            return new ResolvedCapacityProfile(CapacityTargetKind.SEQ, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
        }
        if (StvnVocabulary.TYPE_SET.equals(targetName)) {
            return new ResolvedCapacityProfile(CapacityTargetKind.SET, StvnVocabulary.DEFAULT_MAX_SIZE, false, false);
        }
        Integer preludeCap = StvnStringCapacityInspection.PRELUDE_STRING_CAPACITIES.get(targetName);
        if (preludeCap != null) {
            return new ResolvedCapacityProfile(CapacityTargetKind.STRING, BigInteger.valueOf(preludeCap), true, true);
        }

        PsiElement lookupSite = contextElement != null ? contextElement : file;
        TypeDefinition resolvedDef = StvnTypeReference.resolveTypeDefinitionAtContext(lookupSite, targetName);
        if (resolvedDef == null) {
            resolvedDef = StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), targetName);
        }
        if (resolvedDef != null && resolvedDef.getSchemaType() != null && visited.add(resolvedDef)) {
            SchemaType defSchema = resolvedDef.getSchemaType();
            MetadataMap metaMap = defSchema.getMetadataMap() != null
                ? defSchema.getMetadataMap()
                : resolvedDef.getMetadataMap();
            DirectFacetScan direct = scanDirectCapacityFacets(metaMap);
            ResolvedCapacityProfile downstream = resolveCapacityProfile(
                defSchema,
                resolvedDef.getContainingFile(),
                visited
            );
            if (downstream != null) {
                if (direct.hasAnyCapacityFacet()) {
                    BigInteger cap = direct.directMaxCapacity() != null
                        ? direct.directMaxCapacity()
                        : downstream.capacity();
                    return new ResolvedCapacityProfile(
                        downstream.kind(),
                        cap,
                        true,
                        direct.directMaxCapacity() != null || downstream.hasExplicitMaxSize()
                    );
                }
                return downstream;
            }
        }
        return null;
    }

    private record DirectFacetScan(boolean hasAnyCapacityFacet, @Nullable BigInteger directMaxCapacity) {}

    private static DirectFacetScan scanDirectCapacityFacets(@Nullable MetadataMap metaMap) {
        if (metaMap == null) {
            return new DirectFacetScan(false, null);
        }
        boolean hasCap = false;
        BigInteger maxCap = null;
        for (MetadataEntry entry : metaMap.getMetadataEntryList()) {
            String text = entry.getText().trim();
            if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE) || text.startsWith(StvnVocabulary.FACET_KW_SIZE)) {
                hasCap = true;
                var metaVal = entry.getMetadataValue();
                if (metaVal != null) {
                    maxCap = StvnPsiLiteralEvaluator
                        .resolveBigIntegerOrConst(entry, metaVal.getText().trim())
                        .orElse(null);
                }
            } else if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
                hasCap = true;
            }
        }
        if (!hasCap) {
            String sanitized = StvnStringCapacityInspection.sanitizeMetadataText(metaMap.getText());
            if (StvnStringCapacityInspection.containsCapacityFacetLexically(sanitized)) {
                hasCap = true;
            }
        }
        return new DirectFacetScan(hasCap, maxCap);
    }

    private String formatSchemaCapacityMessage(
            String ownerPrefix,
            String typeName,
            boolean isUnadorned,
            BigInteger effectiveCapacity,
            int threshold
    ) {
        if (isUnadorned) {
            return "Nominal " + targetKind.schemaCategoryNoun() + " type '" + typeName
                + "' is unadorned; default capacity is " + StvnVocabulary.DEFAULT_MAX_SIZE + " "
                + targetKind.unitNoun() + ". Specify explicit capacity facets '"
                + StvnVocabulary.DELIM_OPEN_BRACE + " " + StvnVocabulary.FACET_KW_MIN_SIZE + " ... "
                + StvnVocabulary.FACET_KW_MAX_SIZE + " ... " + StvnVocabulary.DELIM_CLOSE_BRACE + "'.";
        }
        return ownerPrefix + typeName + "' specifies capacity " + effectiveCapacity
            + ", exceeding configured threshold of " + threshold + " " + targetKind.unitNoun() + ".";
    }

    private String resolveSchemaDiagnosticCode(boolean isUnadorned) {
        if (isUnadorned) {
            return targetKind.isCollection()
                ? StvnDiagnosticCodes.WARN_NOMINAL_COLLECTION_UNADORNED
                : StvnDiagnosticCodes.WARN_NOMINAL_STRING_UNADORNED;
        }
        return StvnDiagnosticCodes.WARN_NOMINAL_STRING_CAPACITY_OVERFLOW;
    }

    protected static void registerCapacityProblem(
            ProblemsHolder holder,
            PsiElement targetElem,
            String formattedMsg,
            int threshold,
            boolean isError
    ) {
        LocalQuickFix[] fixes = createNominalCapacityQuickFixes(threshold, isError);
        holder.registerProblem(targetElem, formattedMsg, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, fixes);
    }

    protected static void registerCapacityIntentions(
            ProblemsHolder holder,
            PsiElement targetElem,
            int threshold,
            boolean isError
    ) {
        LocalQuickFix[] fixes = createNominalCapacityQuickFixes(threshold, isError);
        holder.registerProblem(targetElem, "Set nominal capacity", ProblemHighlightType.INFORMATION, fixes);
    }

    private static LocalQuickFix[] createNominalCapacityQuickFixes(int threshold, boolean isError) {
        String open = StvnVocabulary.DELIM_OPEN_BRACE;
        String close = StvnVocabulary.DELIM_CLOSE_BRACE;
        var f1 = new StvnStringCapacityInspection.ApplyNominalCapacityQuickFix(
            "Set nominal capacity to " + threshold,
            open + " " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + threshold + " " + close,
            PriorityAction.Priority.TOP
        );
        var f2 = new StvnStringCapacityInspection.ApplyNominalCapacityQuickFix(
            "Set nominal capacity to 0.." + threshold + " (allow empty)",
            open + " " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + threshold + " " + close,
            PriorityAction.Priority.HIGH
        );
        var f3 = new StvnStringCapacityInspection.ApplyNominalCapacityQuickFix(
            "Set nominal capacity to 1.." + threshold + " (non-empty)",
            open + " " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + threshold + " " + close,
            PriorityAction.Priority.NORMAL
        );
        if (isError) {
            return new LocalQuickFix[]{f1, f2, f3};
        }
        return new LocalQuickFix[]{
            f1, f2, f3,
            new StvnStringCapacityInspection.ApplyNominalCapacityQuickFix(
                "Set nominal capacity to " + StvnVocabulary.DEFAULT_MAX_SIZE + " (default allocation cap)",
                open + " " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + StvnVocabulary.DEFAULT_MAX_SIZE + " " + close,
                PriorityAction.Priority.LOW
            ),
            new StvnStringCapacityInspection.ApplyNominalCapacityQuickFix(
                "Set nominal capacity to 0.." + StvnVocabulary.DEFAULT_MAX_SIZE + " (allow empty)",
                open + " " + StvnVocabulary.FACET_KW_MIN_SIZE + " 0 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + StvnVocabulary.DEFAULT_MAX_SIZE + " " + close,
                PriorityAction.Priority.BOTTOM
            ),
            new StvnStringCapacityInspection.ApplyNominalCapacityQuickFix(
                "Set nominal capacity to 1.." + StvnVocabulary.DEFAULT_MAX_SIZE + " (non-empty)",
                open + " " + StvnVocabulary.FACET_KW_MIN_SIZE + " 1 " + StvnVocabulary.FACET_KW_MAX_SIZE + " " + StvnVocabulary.DEFAULT_MAX_SIZE + " " + close,
                PriorityAction.Priority.BOTTOM
            )
        };
    }

    protected static PsiElement resolveHighlightAnchor(SchemaType schemaType) {
        var constructor = schemaType.getSchemaConstructor();
        if (constructor != null) {
            var collectionType = constructor.getCollectionType();
            if (collectionType != null) {
                var firstChild = collectionType.getFirstChild();
                return firstChild != null ? firstChild : collectionType;
            }
            var atomicType = constructor.getAtomicType();
            if (atomicType != null) {
                return atomicType;
            }
            return constructor;
        }
        var typeKw = schemaType.getTypeKeyword();
        return typeKw != null ? typeKw : schemaType;
    }

    protected static String resolveSchemaDisplayName(SchemaType schemaType) {
        return resolveHighlightAnchor(schemaType).getText().trim();
    }

    private static List<SchemaType> extractDirectChildSchemas(SchemaConstructor constructor) {
        CollectionType collectionType = constructor.getCollectionType();
        if (collectionType != null) {
            return collectionType.getSchemaTypeList();
        }
        ProductType productType = constructor.getProductType();
        if (productType != null) {
            return productType.getSchemaTypeList();
        }
        SumType sumType = constructor.getSumType();
        if (sumType != null) {
            return PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
        }
        return List.of();
    }

    private static int countMapLiteralEntries(MapLiteral map) {
        int count = 0;
        ASTNode open = map.getNode().findChildByType(StvnTypes.LBRACE);
        ASTNode close = findLastDirectAstChild(map.getNode(), StvnTypes.RBRACE);
        if (open == null) {
            return 0;
        }
        for (ASTNode cur = open.getTreeNext(); cur != null && cur != close; cur = cur.getTreeNext()) {
            if (cur.getElementType() == StvnTypes.LBRACK) {
                count++;
            }
        }
        return count;
    }

    private static @Nullable ASTNode findLastDirectAstChild(ASTNode parent, com.intellij.psi.tree.IElementType type) {
        ASTNode found = null;
        for (ASTNode cur = parent.getFirstChildNode(); cur != null; cur = cur.getTreeNext()) {
            if (cur.getElementType() == type) {
                found = cur;
            }
        }
        return found;
    }

    private static PsiElement resolveCollectionLiteralHighlightAnchor(PsiElement literal) {
        if (literal.textContains('\n')) {
            ASTNode open = literal instanceof MapLiteral
                ? literal.getNode().findChildByType(StvnTypes.LBRACE)
                : literal.getNode().findChildByType(StvnTypes.LBRACK);
            if (open != null && open.getPsi() != null) {
                return open.getPsi();
            }
        }
        return literal;
    }

    private static int clampThreshold(int rawThreshold) {
        return Math.max(1, Math.min(MAX_INSPECTION_THRESHOLD, rawThreshold));
    }

    private static String capitalize(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /**
     * QuickFix truncating excess entries or elements from a {@code :Map}, {@code :Seq}, or {@code :Set} literal.
     */
    public static final class TruncateCollectionLiteralQuickFix implements LocalQuickFix {
        @SafeFieldForPreview
        private final CapacityTargetKind kind;
        @SafeFieldForPreview
        private final int maxCapacity;

        /**
         * Constructs a new {@code TruncateCollectionLiteralQuickFix}.
         *
         * @param kind        governed collection {@link CapacityTargetKind}
         * @param maxCapacity maximum permitted entry or element count
         */
        public TruncateCollectionLiteralQuickFix(CapacityTargetKind kind, int maxCapacity) {
            this.kind = kind;
            this.maxCapacity = maxCapacity;
        }

        @Override
        public @NotNull String getName() {
            return "Truncate " + kind.literalKindNoun() + " literal to " + maxCapacity + " " + kind.unitNoun();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Truncate collection literal to capacity bound";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (elem == null) {
                return;
            }
            PsiElement literal = (elem instanceof MapLiteral || elem instanceof ListLiteral)
                ? elem
                : elem.getParent();
            PsiFile file = elem.getContainingFile();
            if (literal == null || file == null) {
                return;
            }
            Document doc = file.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
            boolean commitPhysical = file.isPhysical() && !IntentionPreviewUtils.isPreviewElement(file);

            if (literal instanceof ListLiteral listLit) {
                List<Value> values = listLit.getValueList();
                if (values.size() <= maxCapacity) {
                    return;
                }
                ASTNode open = listLit.getNode().findChildByType(StvnTypes.LBRACK);
                ASTNode close = findLastDirectAstChild(listLit.getNode(), StvnTypes.RBRACK);
                if (open == null || close == null) {
                    return;
                }
                int cutStart = maxCapacity == 0
                    ? open.getTextRange().getEndOffset()
                    : values.get(maxCapacity - 1).getTextRange().getEndOffset();
                doc.deleteString(cutStart, close.getStartOffset());
            } else if (literal instanceof MapLiteral mapLit) {
                List<TextRange> entryRanges = collectMapEntryRanges(mapLit);
                if (entryRanges.size() <= maxCapacity) {
                    return;
                }
                ASTNode open = mapLit.getNode().findChildByType(StvnTypes.LBRACE);
                ASTNode close = findLastDirectAstChild(mapLit.getNode(), StvnTypes.RBRACE);
                if (open == null || close == null) {
                    return;
                }
                int cutStart = maxCapacity == 0
                    ? open.getTextRange().getEndOffset()
                    : entryRanges.get(maxCapacity - 1).getEndOffset();
                doc.deleteString(cutStart, close.getStartOffset());
            }
            if (commitPhysical) {
                docManager.commitDocument(doc);
            }
        }

        private static List<TextRange> collectMapEntryRanges(MapLiteral map) {
            List<TextRange> ranges = new ArrayList<>();
            ASTNode open = map.getNode().findChildByType(StvnTypes.LBRACE);
            ASTNode close = findLastDirectAstChild(map.getNode(), StvnTypes.RBRACE);
            if (open == null) {
                return ranges;
            }
            int currentStart = -1;
            for (ASTNode cur = open.getTreeNext(); cur != null && cur != close; cur = cur.getTreeNext()) {
                if (cur.getElementType() == StvnTypes.LBRACK && currentStart < 0) {
                    currentStart = cur.getStartOffset();
                } else if (cur.getElementType() == StvnTypes.RBRACK && currentStart >= 0) {
                    ranges.add(new TextRange(currentStart, cur.getTextRange().getEndOffset()));
                    currentStart = -1;
                }
            }
            return ranges;
        }
    }

    /**
     * QuickFix widening the governing {@code :Map}, {@code :Seq}, or {@code :Set} root schema {@code #maxSize}.
     */
    public static final class WidenCollectionSchemaCapacityQuickFix implements LocalQuickFix {
        private final SchemaType schemaType;
        @SafeFieldForPreview
        private final CapacityTargetKind kind;
        @SafeFieldForPreview
        private final int requiredCapacity;

        /**
         * Constructs a new {@code WidenCollectionSchemaCapacityQuickFix}.
         *
         * @param schemaType       governing schema type AST node
         * @param kind             governed collection {@link CapacityTargetKind}
         * @param requiredCapacity required minimum {@code #maxSize} capacity
         */
        public WidenCollectionSchemaCapacityQuickFix(
                SchemaType schemaType,
                CapacityTargetKind kind,
                int requiredCapacity
        ) {
            this.schemaType = schemaType;
            this.kind = kind;
            this.requiredCapacity = requiredCapacity;
        }

        @Override
        public @NotNull String getName() {
            return "Widen schema " + kind.literalKindNoun() + " capacity to " + requiredCapacity;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Widen schema collection capacity";
        }

        @Override
        public @Nullable FileModifier getFileModifierForPreview(@NotNull PsiFile target) {
            if (!schemaType.isValid() || schemaType.getContainingFile() != target.getOriginalFile()) {
                return null;
            }
            return new WidenCollectionSchemaCapacityQuickFix(
                PsiTreeUtil.findSameElementInCopy(schemaType, target),
                kind,
                requiredCapacity
            );
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            SchemaType rootSchema = resolveEditableRootSchema(schemaType);
            if (rootSchema == null || !rootSchema.isValid()) {
                return;
            }
            PsiFile targetFile = rootSchema.getContainingFile();
            if (targetFile == null) {
                return;
            }
            Document doc = targetFile.getViewProvider().getDocument();
            if (doc == null) {
                return;
            }
            PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
            boolean commitPhysical = targetFile.isPhysical() && !IntentionPreviewUtils.isPreviewElement(targetFile);

            MetadataMap metaMap = rootSchema.getMetadataMap();
            if (metaMap == null && rootSchema.getParent() instanceof TypeDefinition td && td.getSchemaType() == rootSchema) {
                metaMap = td.getMetadataMap();
            }
            if (metaMap != null) {
                StvnMetadataFacetInsertionUtil.insertOrReplaceCapacityFacets(
                    doc,
                    metaMap,
                    null,
                    String.valueOf(requiredCapacity),
                    true
                );
            } else {
                doc.insertString(
                    rootSchema.getTextRange().getStartOffset(),
                    StvnVocabulary.DELIM_OPEN_BRACE + " " + StvnVocabulary.FACET_KW_MAX_SIZE + " "
                        + requiredCapacity + " " + StvnVocabulary.DELIM_CLOSE_BRACE + " "
                );
            }
            if (commitPhysical) {
                docManager.commitDocument(doc);
            }
        }

        private static @Nullable SchemaType resolveEditableRootSchema(SchemaType start) {
            SchemaType cur = start;
            Set<PsiElement> visited = new HashSet<>();
            while (cur != null && visited.add(cur)) {
                if (cur.getSchemaConstructor() != null) {
                    return cur;
                }
                TypeKeyword kw = cur.getTypeKeyword();
                if (kw == null) {
                    return null;
                }
                TypeDefinition def = StvnTypeReference.resolveTypeDefinitionAtContext(kw, kw.getText().trim());
                if (def == null || !visited.add(def)) {
                    return null;
                }
                cur = def.getSchemaType();
            }
            return null;
        }
    }
}
