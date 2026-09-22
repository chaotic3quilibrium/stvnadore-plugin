package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.*;

/**
 * Validates STVN 2.0.0 temporal mode and unit facet requirements.
 * Enforces explicit '#unit' on ':TimeEpoch' and mode facets ('#offset', '#zoned', '#audited')
 * on ':DateTime', and flags mutually exclusive temporal mode declarations.
 */
@NullMarked
public final class StvnTemporalModeInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getShortName() {
        return "StvnTemporalMode";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Temporal mode and unit facet governance inspection";
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
            public void visitTypeDefinition(@NotNull TypeDefinition typeDef) {
                super.visitTypeDefinition(typeDef);
                var schemaType = typeDef.getSchemaType();
                if (schemaType == null) return;
                var typeText = schemaType.getText().trim();

                if (":TimeEpoch".equals(typeText)) {
                    var metaMap = typeDef.getMetadataMap();
                    boolean hasUnit = false;
                    if (metaMap != null) {
                        for (var e : metaMap.getMetadataEntryList()) {
                            if (e.getText().startsWith("#unit") || e.getNode().findChildByType(StvnTypes.KW_UNIT) != null) {
                                hasUnit = true;
                                break;
                            }
                        }
                    }
                    if (!hasUnit) {
                        holder.registerProblem(
                            schemaType,
                            "Temporal type ':TimeEpoch' requires explicit mode or unit facet (e.g. '#unit #ms').",
                            ProblemHighlightType.GENERIC_ERROR,
                            new AddDefaultTemporalFacetQuickFix(typeDef, "#unit #ms")
                        );
                    }
                } else if (":DateTime".equals(typeText)) {
                    var metaMap = typeDef.getMetadataMap();
                    boolean hasOffset = false;
                    boolean hasZoned = false;
                    boolean hasAudited = false;
                    if (metaMap != null) {
                        for (var e : metaMap.getMetadataEntryList()) {
                            var text = e.getText();
                            if (text.startsWith("#offset") || e.getNode().findChildByType(StvnTypes.KW_OFFSET) != null) hasOffset = true;
                            if (text.startsWith("#zoned") || e.getNode().findChildByType(StvnTypes.KW_ZONED) != null) hasZoned = true;
                            if (text.startsWith("#audited") || e.getNode().findChildByType(StvnTypes.KW_AUDITED) != null) hasAudited = true;
                        }
                    }
                    int modeCount = (hasOffset ? 1 : 0) + (hasZoned ? 1 : 0) + (hasAudited ? 1 : 0);
                    if (modeCount > 1 && metaMap != null) {
                        holder.registerProblem(
                            metaMap,
                            "Temporal facets '#offset', '#zoned', and '#audited' are mutually exclusive.",
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    } else if (modeCount == 0) {
                        holder.registerProblem(
                            schemaType,
                            "Temporal type ':DateTime' requires explicit mode or unit facet (e.g. '#offset').",
                            ProblemHighlightType.GENERIC_ERROR,
                            new AddDefaultTemporalFacetQuickFix(typeDef, "#offset")
                        );
                    }
                }
            }

            @Override
            public void visitTypeEntry(@NotNull TypeEntry typeEntry) {
                super.visitTypeEntry(typeEntry);
                var schemaType = typeEntry.getSchemaType();
                if (schemaType == null) return;
                var text = schemaType.getText().trim();
                if (":TimeEpoch".equals(text)) {
                    holder.registerProblem(
                        schemaType,
                        "Temporal type ':TimeEpoch' requires explicit mode or unit facet (e.g. '#unit #ms').",
                        ProblemHighlightType.GENERIC_ERROR
                    );
                } else if (":DateTime".equals(text)) {
                    holder.registerProblem(
                        schemaType,
                        "Temporal type ':DateTime' requires explicit mode or unit facet (e.g. '#offset').",
                        ProblemHighlightType.GENERIC_ERROR
                    );
                }
            }
        };
    }

    private static final class AddDefaultTemporalFacetQuickFix implements LocalQuickFix {
        private final TypeDefinition typeDef;
        private final String facetText;

        AddDefaultTemporalFacetQuickFix(TypeDefinition typeDef, String facetText) {
            this.typeDef = typeDef;
            this.facetText = facetText;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Add temporal facet '" + facetText + "'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!typeDef.isValid()) return;
            var schemaType = typeDef.getSchemaType();
            if (schemaType == null) return;

            var existingMeta = typeDef.getMetadataMap();
            if (existingMeta != null) {
                var entry = StvnElementFactory.createMetadataEntry(project, facetText);
                var entries = existingMeta.getMetadataEntryList();
                if (!entries.isEmpty()) {
                    existingMeta.addAfter(entry, entries.get(entries.size() - 1));
                } else {
                    existingMeta.add(entry);
                }
            } else {
                var newMeta = StvnElementFactory.createMetadataMap(project, facetText);
                typeDef.addBefore(newMeta, schemaType);
            }
        }
    }
}
