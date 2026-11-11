package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import java.time.Clock;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnPayloadValueQuickFixProvider;
import org.stvnadore.psi.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Real-time inspection validating STVN Enum Subset invariants:
 * 1. Root relative declaration order matching root :Enum (with reorder quick-fix)
 * 2. Monotonic narrowing against immediate parent type
 * 3. Mutual exclusivity of #filterIncl and #filterExcl
 * 4. Target attachment constraints (no inline enum constructors, non-enum primitives, or constants)
 * 5. Non-empty filter lists and complete exclusion guards
 */
@NullMarked
public final class StvnEnumSubsetInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnEnumSubsetInspection instance.
     */
    public StvnEnumSubsetInspection() {}

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition typeDef) {
                super.visitTypeDefinition(typeDef);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeDef)) {
                    return;
                }
                var metaMap = typeDef.getMetadataMap();
                if (metaMap == null) return;

                var entries = metaMap.getMetadataEntryList();
                var filterEntries = new ArrayList<MetadataFilter>();
                for (var entry : entries) {
                    if (entry.getMetadataFilter() != null) {
                        filterEntries.add(entry.getMetadataFilter());
                    }
                }
                if (filterEntries.isEmpty()) return;

                var schemaType = typeDef.getSchemaType();
                var kw = typeDef.getTypeKeyword() != null ? typeDef.getTypeKeyword().getText() : ":Type";

                // Attachment check 1: Inline enum constructors prohibited
                if (schemaType != null && schemaType.getSchemaConstructor() != null &&
                    schemaType.getSchemaConstructor().getSumType() != null &&
                    schemaType.getSchemaConstructor().getSumType().getEnumDef() != null) {
                    for (var filter : filterEntries) {
                        holder.registerProblem(
                            filter,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Constraint violation (" + kw + "): filter facets cannot be applied to inline enum constructors; filter facets are only allowed on nominal aliases of :Enum or existing enum subsets",
                                StvnDiagnosticCodes.ERR_FILTER_ON_INLINE_ENUM
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                    return;
                }

                // Attachment check 2: Non-enum primitives prohibited
                var parentSubset = StvnTypeResolver.resolveEnumSubset(schemaType);
                var resolvedParent = schemaType != null ? StvnTypeResolver.resolveNominalSchema(schemaType) : null;
                boolean isEnum = parentSubset != null || (resolvedParent != null && resolvedParent.getSchemaConstructor() != null &&
                    resolvedParent.getSchemaConstructor().getSumType() != null &&
                    resolvedParent.getSchemaConstructor().getSumType().getEnumDef() != null);
                if (!isEnum) {
                    var baseName = schemaType != null ? schemaType.getText() : "non-enum";
                    for (var filter : filterEntries) {
                        holder.registerProblem(
                            filter,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Constraint violation (" + kw + "): filter facets are not allowed on " + baseName,
                                StvnDiagnosticCodes.ERR_FILTER_ON_NON_ENUM
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                    return;
                }

                // Mutual exclusivity check
                boolean hasIncl = filterEntries.stream().anyMatch(f -> f.getNode().findChildByType(StvnTypes.KW_FILTER_INCL) != null);
                boolean hasExcl = filterEntries.stream().anyMatch(f -> f.getNode().findChildByType(StvnTypes.KW_FILTER_EXCL) != null);
                if (hasIncl && hasExcl) {
                    for (var filter : filterEntries) {
                        holder.registerProblem(
                            filter,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Constraint violation (" + kw + "): #filterIncl and #filterExcl are mutually exclusive",
                                StvnDiagnosticCodes.ERR_FILTER_MUTUALLY_EXCLUSIVE
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                    return;
                }

                // Retrieve immediate parent and root variants
                if (parentSubset == null) {
                    parentSubset = StvnTypeResolver.resolveEnumSubset(schemaType);
                }
                List<String> parentAllowed;
                String parentName;
                String rootEnumName;
                List<String> rootVariants;

                if (parentSubset != null) {
                    parentAllowed = parentSubset.allowedVariants();
                    parentName = parentSubset.name();
                    rootEnumName = parentSubset.rootEnum();
                    rootVariants = parentSubset.rootVariants();
                } else {
                    var enumDef = resolvedParent.getSchemaConstructor().getSumType().getEnumDef();
                    rootVariants = enumDef.getValueKeywordList().stream().map(ValueKeyword::getText).toList();
                    parentAllowed = rootVariants;
                    parentName = schemaType.getTypeKeyword() != null ? schemaType.getTypeKeyword().getText() : StvnVocabulary.TYPE_ENUM;
                    rootEnumName = parentName;
                }

                for (var filter : filterEntries) {
                    var variantList = filter.getVariantList();
                    if (variantList == null) continue;
                    var valueKeywords = variantList.getValueKeywordList();

                    // Non-empty bracket check
                    if (valueKeywords.isEmpty()) {
                        holder.registerProblem(
                            variantList,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Constraint violation (" + kw + "): enum filter variant list cannot be empty",
                                StvnDiagnosticCodes.ERR_FILTER_EMPTY_LIST
                            ),
                            ProblemHighlightType.GENERIC_ERROR
                        );
                        continue;
                    }

                    // Duplicate variant check
                    var seen = new HashSet<String>();
                    for (var vkw : valueKeywords) {
                        var text = vkw.getText();
                        if (!seen.add(text)) {
                            holder.registerProblem(
                                vkw,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Enum subset violation (" + kw + "): duplicate variant '" + text + "' in filter list",
                                    StvnDiagnosticCodes.ERR_FILTER_DUPLICATE_VARIANT
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                new StvnRemoveDuplicateFilterVariantQuickFix(vkw, text)
                            );
                        }
                    }

                    // Monotonic narrowing check
                    for (var vkw : valueKeywords) {
                        var text = vkw.getText();
                        if (!parentAllowed.contains(text)) {
                            holder.registerProblem(
                                vkw,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Constraint violation (" + kw + "): Monotonic narrowing violation: variant " + text + " does not exist in immediate parent type " + parentName,
                                    StvnDiagnosticCodes.ERR_FILTER_MONOTONIC_NARROWING_VIOLATION
                                ),
                                ProblemHighlightType.GENERIC_ERROR
                            );
                        }
                    }

                    // Relative root order check
                    int prevRootIdx = -1;
                    ValueKeyword outOfOrderNode = null;
                    var seenForOrder = new HashSet<String>();
                    for (var vkw : valueKeywords) {
                        var text = vkw.getText();
                        if (!seenForOrder.add(text)) {
                            continue;
                        }
                        int idx = rootVariants.indexOf(text);
                        if (idx >= 0) {
                            if (idx <= prevRootIdx) {
                                outOfOrderNode = vkw;
                                break;
                            }
                            prevRootIdx = idx;
                        }
                    }

                    if (outOfOrderNode != null) {
                        var outOfOrderText = outOfOrderNode.getText();
                        var quickFix = new StvnReorderEnumFilterVariantsQuickFix(variantList);
                        var msg = StvnDiagnosticFormatter.formatPlugin(
                            "Constraint violation (" + kw + "): Root ordering violation: variant " + outOfOrderText + " does not match relative declaration order of root :Enum " + rootEnumName,
                            StvnDiagnosticCodes.ENUM_SUBSET_ORDER_VIOLATION
                        );
                        for (var vkw : valueKeywords) {
                            if (vkw == outOfOrderNode) {
                                holder.registerProblem(
                                    vkw,
                                    msg,
                                    ProblemHighlightType.GENERIC_ERROR,
                                    quickFix
                                );
                            } else if (isOnTheFly) {
                                holder.registerProblem(
                                    vkw,
                                    "",
                                    ProblemHighlightType.INFORMATION,
                                    quickFix
                                );
                            }
                        }
                    }

                    // Complete exclusion check
                    if (filter.getNode().findChildByType(StvnTypes.KW_FILTER_EXCL) != null) {
                        var facetList = valueKeywords.stream().map(ValueKeyword::getText).toList();
                        var remaining = parentAllowed.stream().filter(v -> !facetList.contains(v)).toList();
                        if (remaining.isEmpty()) {
                            holder.registerProblem(
                                variantList,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Constraint violation (" + kw + "): Complete exclusion violation: enum subset results in empty variant list",
                                    StvnDiagnosticCodes.ERR_FILTER_COMPLETE_EXCLUSION_VIOLATION
                                ),
                                ProblemHighlightType.GENERIC_ERROR
                            );
                        }
                    }
                }
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                super.visitConstantDefinition(constDef);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)) {
                    return;
                }
                var metaMap = constDef.getMetadataMap();
                if (metaMap == null) return;

                for (var entry : metaMap.getMetadataEntryList()) {
                    var filter = entry.getMetadataFilter();
                    if (filter != null) {
                        var constName = constDef.getValueKeyword() != null ? constDef.getValueKeyword().getText() : "#CONST";
                        var facetTag = filter.getNode().findChildByType(StvnTypes.KW_FILTER_INCL) != null
                            ? StvnVocabulary.FACET_KW_FILTER_INCL
                            : StvnVocabulary.FACET_KW_FILTER_EXCL;
                        holder.registerProblem(
                            filter,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Constraint violation (" + constName + "): filter facets are not allowed on constants",
                                StvnDiagnosticCodes.ERR_FILTER_ON_CONSTANT
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            new RemoveRedundantFacetQuickFix(entry, facetTag, "Remove invalid facet")
                        );
                    }
                }
            }

            @Override
            public void visitValue(@NotNull Value value) {
                super.visitValue(value);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(value)) {
                    return;
                }
                var vkw = value.getValueKeyword();
                if (vkw == null) return;

                var typeInfo = StvnTypeResolver.resolveBaseTypeInfo(value);
                if (typeInfo == null) return;

                if (isGovernedByPoisonedOrDegradedEnumSubset(value.getContainingFile(), typeInfo.getSchema())) {
                    return;
                }

                var subset = StvnTypeResolver.resolveEnumSubset(typeInfo.getSchema());
                if (subset == null) return;

                var text = vkw.getText();
                if (subset.rootVariants().contains(text) && !subset.containsVariant(text)) {
                    var msg = StvnDiagnosticFormatter.formatPlugin(
                        "Payload variant '" + text + "' is not a member of active enum subset '" + subset.name() + "'",
                        StvnDiagnosticCodes.ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION
                    );
                    var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                        value,
                        StvnDiagnosticCodes.ERR_ENUM_SUBSET_PAYLOAD_MEMBERSHIP_VIOLATION,
                        msg,
                        Clock.systemDefaultZone()
                    ).toArray(LocalQuickFix.EMPTY_ARRAY);
                    holder.registerProblem(
                        vkw,
                        msg,
                        ProblemHighlightType.GENERIC_ERROR,
                        fixes
                    );
                }
            }
        };
    }

    private static boolean isGovernedByPoisonedOrDegradedEnumSubset(
            @Nullable PsiFile file,
            @Nullable SchemaType schemaType
    ) {
        if (file == null || schemaType == null) {
            return false;
        }
        var hops = new ArrayList<TypeDefinition>();
        var visited = new HashSet<String>();
        SchemaType current = schemaType;
        while (current != null && current.getTypeKeyword() != null) {
            String name = current.getTypeKeyword().getText().trim();
            if (!visited.add(name)) {
                return true;
            }
            if (StvnTypeResolver.isDegradedNominalAlias(file, name)) {
                return true;
            }
            var target = StvnTypeReference.resolveTypeInFile(file, name, new HashSet<>());
            var typeDef = StvnPsiUtils.resolveTargetTypeDefinition(target);
            if (typeDef == null) {
                return true;
            }
            hops.add(typeDef);
            current = typeDef.getSchemaType();
        }
        if (hops.isEmpty()) {
            return false;
        }
        var rootSchema = hops.get(hops.size() - 1).getSchemaType();
        if (rootSchema == null || rootSchema.getSchemaConstructor() == null
                || rootSchema.getSchemaConstructor().getSumType() == null
                || rootSchema.getSchemaConstructor().getSumType().getEnumDef() == null) {
            return false;
        }
        var rootVariants = rootSchema.getSchemaConstructor().getSumType().getEnumDef()
            .getValueKeywordList().stream().map(ValueKeyword::getText).toList();
        var effective = new ArrayList<>(rootVariants);
        for (int i = hops.size() - 2; i >= 0; i--) {
            if (!isHealthyEnumSubsetHop(hops.get(i), rootVariants, effective)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHealthyEnumSubsetHop(
            TypeDefinition hopDef,
            List<String> rootVariants,
            List<String> parentEffective
    ) {
        var metaMap = hopDef.getMetadataMap();
        if (metaMap == null && hopDef.getSchemaType() != null) {
            metaMap = hopDef.getSchemaType().getMetadataMap();
        }
        if (metaMap == null) {
            return true;
        }
        var filters = new ArrayList<MetadataFilter>();
        for (var entry : metaMap.getMetadataEntryList()) {
            if (entry.getMetadataFilter() != null) {
                filters.add(entry.getMetadataFilter());
            }
        }
        if (filters.isEmpty()) {
            return true;
        }
        boolean hasIncl = filters.stream().anyMatch(f -> f.getNode().findChildByType(StvnTypes.KW_FILTER_INCL) != null);
        boolean hasExcl = filters.stream().anyMatch(f -> f.getNode().findChildByType(StvnTypes.KW_FILTER_EXCL) != null);
        if (hasIncl && hasExcl) {
            return false;
        }
        for (var filter : filters) {
            var variantList = filter.getVariantList();
            if (variantList == null) {
                return false;
            }
            var kws = variantList.getValueKeywordList();
            if (kws.isEmpty()) {
                return false;
            }
            var seen = new HashSet<String>();
            var filterVariants = new ArrayList<String>();
            int lastRootIdx = -1;
            for (var kw : kws) {
                var v = kw.getText().trim();
                if (!seen.add(v)) {
                    return false;
                }
                int rootIdx = rootVariants.indexOf(v);
                if (rootIdx < 0 || !parentEffective.contains(v)) {
                    return false;
                }
                if (rootIdx <= lastRootIdx) {
                    return false;
                }
                lastRootIdx = rootIdx;
                filterVariants.add(v);
            }
            if (hasIncl) {
                parentEffective.retainAll(filterVariants);
            } else {
                parentEffective.removeAll(filterVariants);
            }
            if (parentEffective.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
