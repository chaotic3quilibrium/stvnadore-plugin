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
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.psi.*;

/**
 * Validates STVN 2.0.0 temporal mode and unit facet requirements.
 * Enforces explicit '#unit' on ':TimeEpoch' and mode facets ('#offset', '#zoned', '#audited')
 * on ':DateTime', and flags mutually exclusive temporal mode declarations.
 */
@NullMarked
public final class StvnTemporalModeInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnTemporalModeInspection instance.
     */
    public StvnTemporalModeInspection() {
    }

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
                var typeText = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();

                if (":TimeEpoch".equals(typeText)) {
                    var metaMap = schemaType.getMetadataMap() != null ? schemaType.getMetadataMap() : typeDef.getMetadataMap();
                    boolean hasScale = false;
                    int scaleCount = 0;
                    boolean hasLegacyUnit = false;
                    MetadataEntry legacyUnitEntry = null;
                    if (metaMap != null) {
                        for (var e : metaMap.getMetadataEntryList()) {
                            var text = e.getText().trim();
                            if (e.getNode().findChildByType(StvnTypes.KW_SCALE_S) != null || "#s".equals(text)
                                    || e.getNode().findChildByType(StvnTypes.KW_SCALE_MS) != null || "#ms".equals(text)
                                    || e.getNode().findChildByType(StvnTypes.KW_SCALE_US) != null || "#us".equals(text)
                                    || e.getNode().findChildByType(StvnTypes.KW_SCALE_NS) != null || "#ns".equals(text)) {
                                hasScale = true;
                                scaleCount++;
                            }
                            if (text.startsWith("#unit")) {
                                hasLegacyUnit = true;
                                legacyUnitEntry = e;
                            }
                        }
                    }
                    if (hasLegacyUnit && legacyUnitEntry != null) {
                        holder.registerProblem(
                            legacyUnitEntry,
                            "Legacy temporal facet '#unit' is deprecated; use bare scale flag ('#s', '#ms', '#us', '#ns').",
                            ProblemHighlightType.LIKE_DEPRECATED
                        );
                    }
                    if (scaleCount > 1 && metaMap != null) {
                        holder.registerProblem(
                            metaMap,
                            "Temporal scale facets (#s, #ms, #us, #ns) are mutually exclusive.",
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    } else if (!hasScale && !hasLegacyUnit) {
                        holder.registerProblem(
                            schemaType,
                            "Temporal type ':TimeEpoch' requires a scale facet: '#s', '#ms', '#us', or '#ns'.",
                            ProblemHighlightType.GENERIC_ERROR,
                            new AddDefaultTemporalFacetQuickFix(typeDef, "#ms")
                        );
                    }
                } else if (":DateTime".equals(typeText)) {
                    var metaMap = schemaType.getMetadataMap() != null ? schemaType.getMetadataMap() : typeDef.getMetadataMap();
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
                // Handled via visitSchemaType
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (schemaType.getParent() instanceof TypeDefinition) {
                    return; // Handled by visitTypeDefinition with quick-fixes
                }
                var text = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
                if (":TimeEpoch".equals(text)) {
                    var metaMap = schemaType.getMetadataMap();
                    boolean hasScale = false;
                    if (metaMap != null) {
                        for (var e : metaMap.getMetadataEntryList()) {
                            var t = e.getText().trim();
                            if (e.getNode().findChildByType(StvnTypes.KW_SCALE_S) != null || "#s".equals(t)
                                    || e.getNode().findChildByType(StvnTypes.KW_SCALE_MS) != null || "#ms".equals(t)
                                    || e.getNode().findChildByType(StvnTypes.KW_SCALE_US) != null || "#us".equals(t)
                                    || e.getNode().findChildByType(StvnTypes.KW_SCALE_NS) != null || "#ns".equals(t)) {
                                hasScale = true;
                                break;
                            }
                        }
                    }
                    if (!hasScale) {
                        holder.registerProblem(
                            schemaType,
                            "Temporal type ':TimeEpoch' requires a scale facet: '#s', '#ms', '#us', or '#ns'.",
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
                } else if (":DateTime".equals(text)) {
                    var metaMap = schemaType.getMetadataMap();
                    boolean hasMode = false;
                    if (metaMap != null) {
                        for (var e : metaMap.getMetadataEntryList()) {
                            var t = e.getText();
                            if (t.startsWith("#offset") || e.getNode().findChildByType(StvnTypes.KW_OFFSET) != null
                                    || t.startsWith("#zoned") || e.getNode().findChildByType(StvnTypes.KW_ZONED) != null
                                    || t.startsWith("#audited") || e.getNode().findChildByType(StvnTypes.KW_AUDITED) != null) {
                                hasMode = true;
                                break;
                            }
                        }
                    }
                    if (!hasMode) {
                        holder.registerProblem(
                            schemaType,
                            "Temporal type ':DateTime' requires explicit mode or unit facet (e.g. '#offset').",
                            ProblemHighlightType.GENERIC_ERROR
                        );
                    }
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
