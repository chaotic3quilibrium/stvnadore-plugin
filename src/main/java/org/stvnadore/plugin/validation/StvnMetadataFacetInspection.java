package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.*;

/**
 * Validates STVN metadata facet compatibility and detects empty blocks in directives and metadata.
 */
@NullMarked
public final class StvnMetadataFacetInspection extends LocalInspectionTool {

    private static final TokenSet NUMERIC_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_MIN_INCL, StvnTypes.KW_MIN_EXCL, StvnTypes.KW_MAX_INCL, StvnTypes.KW_MAX_EXCL,
        StvnTypes.KW_SIZE, StvnTypes.KW_UNSIGNED, StvnTypes.KW_EXACT
    );
    private static final TokenSet STRING_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_REGEX, StvnTypes.KW_MIN_SIZE, StvnTypes.KW_MAX_SIZE
    );
    private static final TokenSet TEMPORAL_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_SCALE_S, StvnTypes.KW_SCALE_MS, StvnTypes.KW_SCALE_US, StvnTypes.KW_SCALE_NS,
        StvnTypes.KW_OFFSET, StvnTypes.KW_ZONED, StvnTypes.KW_AUDITED
    );
    private static final TokenSet MAP_FACET_TOKENS = TokenSet.create(
        StvnTypes.KW_INVERTIBLE
    );

    @Override
    public @NotNull String getShortName() {
        return "StvnMetadataFacet";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Metadata facet and block governance inspection";
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
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition def) {
                var metaMap = def.getMetadataMap();
                if (metaMap != null) {
                    if (metaMap.getMetadataEntryList().isEmpty()) {
                        holder.registerProblem(
                            metaMap,
                            "Empty metadata block is invalid; remove '{}' or specify valid facets",
                            ProblemHighlightType.ERROR,
                            new RemoveElementQuickFix("Remove empty metadata block")
                        );
                    } else {
                        validateMetadataEntries(metaMap, def, holder);
                    }
                }
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition def) {
                var metaMap = def.getMetadataMap();
                if (metaMap != null) {
                    if (metaMap.getMetadataEntryList().isEmpty()) {
                        holder.registerProblem(
                            metaMap,
                            "Empty metadata block is invalid; remove '{}' or specify valid facets",
                            ProblemHighlightType.ERROR,
                            new RemoveElementQuickFix("Remove empty metadata block")
                        );
                    } else {
                        for (var entry : metaMap.getMetadataEntryList()) {
                            holder.registerProblem(
                                entry,
                                "Metadata facets are not permitted on constants; permitted facets: []",
                                ProblemHighlightType.ERROR,
                                new RemoveElementQuickFix("Remove invalid facet")
                            );
                        }
                    }
                }
            }

            @Override
            public void visitUseStmt(@NotNull UseStmt stmt) {
                var optBlock = stmt.getUseOptionsBlock();
                if (optBlock != null && optBlock.getText().replaceAll("\\s+", "").equals("{}")) {
                    holder.registerProblem(
                        optBlock,
                        "Empty directive block in :use statement is invalid; specify {#strip} or remove '{}'",
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
                var aliasBlock = stmt.getUseAliasBlock();
                if (aliasBlock != null && aliasBlock.getUseMapAliasList().isEmpty()) {
                    holder.registerProblem(
                        aliasBlock,
                        "Empty directive block in :use statement is invalid; specify alias mappings or remove '{}'",
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
            }

            @Override
            public void visitIncludeElement(@NotNull IncludeElement element) {
                var optBlock = element.getIncludeOptionsBlock();
                if (optBlock != null && optBlock.getIncludeOptionList().isEmpty()) {
                    holder.registerProblem(
                        optBlock,
                        "Empty directive block in :include statement is invalid; specify {#strip} or remove '{}'",
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
                var aliasBlock = element.getIncludeAliasBlock();
                if (aliasBlock != null && aliasBlock.getIncludeMapAliasList().isEmpty()) {
                    holder.registerProblem(
                        aliasBlock,
                        "Empty directive block in :include statement is invalid; specify alias mappings or remove '{}'",
                        ProblemHighlightType.ERROR,
                        new RemoveElementQuickFix("Remove empty directive block")
                    );
                }
            }
        };
    }

    private void validateMetadataEntries(MetadataMap metaMap, TypeDefinition def, ProblemsHolder holder) {
        var schemaType = def.getSchemaType();
        var baseType = schemaType != null ? resolveBaseTypeString(schemaType) : "";
        boolean isNumeric = isNumericType(baseType);
        boolean isString = isStringType(baseType);
        boolean isTemporal = isTemporalType(baseType);
        boolean isMap = isMapType(baseType);
        boolean isEnum = ":Enum".equals(baseType) || baseType.startsWith(":Enum");

        for (var entry : metaMap.getMetadataEntryList()) {
            if (entry.getMetadataDirective() != null) {
                holder.registerProblem(
                    entry,
                    "Directive facet '#strip' is not permitted on type declarations; directive facets are valid strictly in :use and :include blocks",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (entry.getNode().findChildByType(NUMERIC_FACET_TOKENS) != null && !isNumeric && !isString) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for numeric types: [#equatable, #comparable, #size, #unsigned, #exact, #minIncl, #maxIncl, #minExcl, #maxExcl]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (entry.getNode().findChildByType(STRING_FACET_TOKENS) != null && !isString) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for string types: [#equatable, #comparable, #regex, #minSize, #maxSize, #preserveIndent]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (entry.getNode().findChildByType(TEMPORAL_FACET_TOKENS) != null && !isTemporal) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for temporal types: [#s, #ms, #us, #ns, #offset, #zoned, #audited]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (entry.getNode().findChildByType(MAP_FACET_TOKENS) != null && !isMap) {
                holder.registerProblem(
                    entry,
                    "Facet is not permitted on " + baseType + "; permitted facets for map types: [#invertible]",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            } else if (entry.getMetadataFilter() != null && !isEnum) {
                holder.registerProblem(
                    entry,
                    "Filter facets are permitted strictly on nominal aliases of :Enum and enum subsets",
                    ProblemHighlightType.ERROR,
                    new RemoveElementQuickFix("Remove invalid facet")
                );
            }
        }
    }

    private static String resolveBaseTypeString(SchemaType schemaType) {
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
            if (":Enum".equals(text)) {
                return ":Enum";
            }
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = org.stvnadore.plugin.reference.StvnTypeReference.resolveTypeInFile(file, text, new java.util.HashSet<>());
                if (resolved != null) {
                    var parentDef = PsiTreeUtil.getParentOfType(resolved, TypeDefinition.class);
                    if (parentDef != null && parentDef.getSchemaType() != null) {
                        return resolveBaseTypeString(parentDef.getSchemaType());
                    }
                }
            }
            return text;
        }
        return schemaType.getText().trim();
    }

    private static boolean isNumericType(String baseType) {
        return baseType.startsWith(":Int") || baseType.startsWith(":Uint") || baseType.startsWith(":Float");
    }

    private static boolean isStringType(String baseType) {
        return baseType.startsWith(":String");
    }

    private static boolean isTemporalType(String baseType) {
        return baseType.startsWith(":TimeEpoch") || baseType.startsWith(":DateTime");
    }

    private static boolean isMapType(String baseType) {
        return baseType.startsWith(":Map");
    }

    private static final class RemoveElementQuickFix implements LocalQuickFix {
        private final String familyName;

        RemoveElementQuickFix(String familyName) {
            this.familyName = familyName;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return familyName;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element != null && element.isValid()) {
                if (!(element instanceof MetadataEntry)) {
                    var prev = element.getPrevSibling();
                    if (prev instanceof PsiWhiteSpace) {
                        prev.delete();
                    }
                }
                element.delete();
            }
        }
    }
}
