package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.time.Clock;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnConstantReference;
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

                // Mutual exclusivity check (#filterIncl and #filterExcl): pin GENERIC_ERROR strictly to i >= 1
                boolean hasIncl = filterEntries.stream().anyMatch(f -> f.getNode().findChildByType(StvnTypes.KW_FILTER_INCL) != null);
                boolean hasExcl = filterEntries.stream().anyMatch(f -> f.getNode().findChildByType(StvnTypes.KW_FILTER_EXCL) != null);
                if (hasIncl && hasExcl) {
                    for (int i = 0; i < filterEntries.size(); i++) {
                        var filter = filterEntries.get(i);
                        var parentEntry = filter.getParent() instanceof MetadataEntry me ? me : null;
                        var fKw = filter.getNode().findChildByType(StvnTypes.KW_FILTER_INCL) != null
                            ? StvnVocabulary.FACET_KW_FILTER_INCL
                            : StvnVocabulary.FACET_KW_FILTER_EXCL;
                        var fixes = parentEntry != null
                            ? new LocalQuickFix[]{new RemoveRedundantFacetQuickFix(parentEntry, fKw, "Remove conflicting facet")}
                            : LocalQuickFix.EMPTY_ARRAY;
                        if (i >= 1) {
                            holder.registerProblem(
                                filter,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Constraint violation (" + kw + "): "
                                        + StvnVocabulary.FACET_KW_FILTER_INCL + " and "
                                        + StvnVocabulary.FACET_KW_FILTER_EXCL + " are mutually exclusive",
                                    DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                fixes
                            );
                        } else if (isOnTheFly && fixes.length > 0) {
                            holder.registerProblem(filter, "", ProblemHighlightType.INFORMATION, fixes);
                        }
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
                                    "Monotonic narrowing violation (" + kw + "): variant '" + text + "' is not active in parent enum",
                                    DiagnosticBag.ERR_ENUM_SUBSET_NOT_SUBSET
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
                            "Root ordering violation (" + kw + "): variant '" + outOfOrderText + "' violates monotonic root enum declaration order",
                            DiagnosticBag.ERR_ENUM_SUBSET_ORDER_VIOLATION
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
                                filter,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Complete exclusion violation (" + kw + "): filtered enum subset evaluates to zero active variants",
                                    DiagnosticBag.ERR_ENUM_SUBSET_EMPTY
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
                if (PsiTreeUtil.getParentOfType(value, MetadataMap.class) != null) {
                    return;
                }
                var vkw = value.getValueKeyword();
                if (vkw == null) return;

                // Exempt declared #CONST references so valid enum constants never trigger undeclared/subset errors
                var resolvedConst = StvnConstantReference.resolveConstantWithContext(vkw);
                if (resolvedConst instanceof ConstantDefinition
                        || (resolvedConst != null && resolvedConst.getParent() instanceof ConstantDefinition)) {
                    return;
                }

                var enclosingConst = PsiTreeUtil.getParentOfType(value, ConstantDefinition.class);
                if (enclosingConst != null && enclosingConst.getMetadataMap() != null
                        && enclosingConst.getMetadataMap().getMetadataEntryList().stream().anyMatch(e -> e.getMetadataFilter() != null)) {
                    return;
                }

                var governingSchema = StvnPayloadValueQuickFixProvider.resolveGoverningSchemaForValue(value);
                if (governingSchema == null) return;

                if (isGovernedByPoisonedOrDegradedEnumSubset(value.getContainingFile(), governingSchema)) {
                    var fallbackRootInfo = resolveDirectEnumInfo(value, governingSchema, true);
                    if (fallbackRootInfo != null && !fallbackRootInfo.variants().contains(vkw.getText())) {
                        var text = vkw.getText();
                        var msg = StvnDiagnosticFormatter.formatPlugin(
                            "Invalid enum value for '" + fallbackRootInfo.label() + "': variant '" + text
                                + "' is not declared; allowed variants: " + fallbackRootInfo.variants(),
                            DiagnosticBag.ERR_TYPE_MISMATCH
                        );
                        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                            value, DiagnosticBag.ERR_TYPE_MISMATCH, msg, Clock.systemDefaultZone()
                        ).toArray(LocalQuickFix.EMPTY_ARRAY);
                        holder.registerProblem(vkw, msg, ProblemHighlightType.GENERIC_ERROR, fixes);
                    }
                    return;
                }

                var subset = StvnTypeResolver.resolveEnumSubset(governingSchema);
                if (subset != null) {
                    var text = vkw.getText();
                    if (subset.containsVariant(text)) {
                        return;
                    }
                    if (subset.rootVariants().contains(text)) {
                        var msg = StvnDiagnosticFormatter.formatPlugin(
                            "Invalid enum value: variant '" + text + "' is not permitted in enum subset '"
                                + subset.name() + "'; allowed variants: " + subset.allowedVariants(),
                            DiagnosticBag.ERR_CONSTRAINT_VIOLATION
                        );
                        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                            value, DiagnosticBag.ERR_CONSTRAINT_VIOLATION, msg, Clock.systemDefaultZone()
                        ).toArray(LocalQuickFix.EMPTY_ARRAY);
                        holder.registerProblem(vkw, msg, ProblemHighlightType.GENERIC_ERROR, fixes);
                    } else {
                        var msg = StvnDiagnosticFormatter.formatPlugin(
                            "Invalid enum value for '" + subset.name() + "': variant '" + text
                                + "' is not declared; allowed variants: " + subset.allowedVariants(),
                            DiagnosticBag.ERR_TYPE_MISMATCH
                        );
                        var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                            value, DiagnosticBag.ERR_TYPE_MISMATCH, msg, Clock.systemDefaultZone()
                        ).toArray(LocalQuickFix.EMPTY_ARRAY);
                        holder.registerProblem(vkw, msg, ProblemHighlightType.GENERIC_ERROR, fixes);
                    }
                    return;
                }

                var directEnumInfo = resolveDirectEnumInfo(value, governingSchema, false);
                if (directEnumInfo != null && !directEnumInfo.variants().contains(vkw.getText())) {
                    var text = vkw.getText();
                    var msg = StvnDiagnosticFormatter.formatPlugin(
                        "Invalid enum value for '" + directEnumInfo.label() + "': variant '" + text
                            + "' is not declared; allowed variants: " + directEnumInfo.variants(),
                        DiagnosticBag.ERR_TYPE_MISMATCH
                    );
                    var fixes = StvnPayloadValueQuickFixProvider.computeFixesForValue(
                        value, DiagnosticBag.ERR_TYPE_MISMATCH, msg, Clock.systemDefaultZone()
                    ).toArray(LocalQuickFix.EMPTY_ARRAY);
                    holder.registerProblem(vkw, msg, ProblemHighlightType.GENERIC_ERROR, fixes);
                }
            }
        };
    }

    private record DirectEnumInfo(String label, List<String> variants) {}

    private static @Nullable DirectEnumInfo resolveDirectEnumInfo(
            @NotNull Value value,
            @NotNull SchemaType governingSchema,
            boolean useHealthyRootAliasForDegraded
    ) {
        if (governingSchema.getTypeKeyword() != null) {
            String directLabel = governingSchema.getTypeKeyword().getText().trim();
            var resolved = StvnTypeResolver.resolveNominalSchema(governingSchema);
            if (resolved != null
                    && resolved.getSchemaConstructor() != null
                    && resolved.getSchemaConstructor().getSumType() != null
                    && resolved.getSchemaConstructor().getSumType().getEnumDef() != null) {
                var variants = resolved.getSchemaConstructor().getSumType().getEnumDef()
                    .getValueKeywordList().stream().map(ValueKeyword::getText).toList();
                String label = directLabel;
                if (useHealthyRootAliasForDegraded && resolved.getParent() instanceof TypeDefinition rootTd
                        && rootTd.getTypeKeyword() != null) {
                    label = rootTd.getTypeKeyword().getText().trim();
                }
                return new DirectEnumInfo(label, variants);
            }
            return null;
        }
        if (governingSchema.getSchemaConstructor() != null
                && governingSchema.getSchemaConstructor().getSumType() != null
                && governingSchema.getSchemaConstructor().getSumType().getEnumDef() != null) {
            var constDef = PsiTreeUtil.getParentOfType(value, ConstantDefinition.class);
            String label = (constDef != null && value.getParent() == constDef && constDef.getValueKeyword() != null)
                ? constDef.getValueKeyword().getText().trim()
                : StvnVocabulary.TYPE_ENUM;
            var variants = governingSchema.getSchemaConstructor().getSumType().getEnumDef()
                .getValueKeywordList().stream().map(ValueKeyword::getText).toList();
            return new DirectEnumInfo(label, variants);
        }
        return null;
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
