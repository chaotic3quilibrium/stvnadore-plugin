package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.psi.*;

import java.util.ArrayList;

/**
 * Validates STVN 2.0.0 temporal mode and scale facet requirements.
 * Enforces explicit bare scale flags ({@code #s}, {@code #ms}, {@code #us}, {@code #ns}) on {@code :TimeEpoch}
 * and mode facets ({@code #offset}, {@code #zoned}, {@code #audited}) on {@code :DateTime},
 * and flags mutually exclusive temporal mode or scale declarations.
 */
@NullMarked
public final class StvnTemporalModeInspection extends LocalInspectionTool {

    /**
     * Constructs a new {@code StvnTemporalModeInspection} instance.
     */
    public StvnTemporalModeInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnTemporalMode";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Temporal mode and scale facet governance inspection";
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
                if (schemaType == null) {
                    return;
                }
                var metaMap = typeDef.getMetadataMap() != null ? typeDef.getMetadataMap() : schemaType.getMetadataMap();
                validateTemporalSchema(schemaType, metaMap, holder);
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                super.visitConstantDefinition(constDef);
                var schemaType = constDef.getSchemaType();
                if (schemaType == null) {
                    return;
                }
                var metaMap = constDef.getMetadataMap() != null ? constDef.getMetadataMap() : schemaType.getMetadataMap();
                validateTemporalSchema(schemaType, metaMap, holder);
            }

            @Override
            public void visitTypeEntry(@NotNull TypeEntry typeEntry) {
                super.visitTypeEntry(typeEntry);
                // Handled via visitSchemaType
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (schemaType.getParent() instanceof TypeDefinition
                        || schemaType.getParent() instanceof ConstantDefinition) {
                    return; // Handled by visitTypeDefinition / visitConstantDefinition
                }
                validateTemporalSchema(schemaType, schemaType.getMetadataMap(), holder);
            }
        };
    }

    private static void validateTemporalSchema(
            @NotNull SchemaType schemaType,
            @Nullable MetadataMap metaMap,
            @NotNull ProblemsHolder holder
    ) {
        var typeText = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
        if (StvnVocabulary.TYPE_TIME_EPOCH.equals(typeText)) {
            var scaleEntries = new ArrayList<MetadataEntry>();
            var scaleKeywords = new ArrayList<String>();
            if (metaMap != null) {
                for (var e : metaMap.getMetadataEntryList()) {
                    if (KeepOnlyTemporalFacetQuickFix.isScaleEntry(e)) {
                        scaleEntries.add(e);
                        scaleKeywords.add(KeepOnlyTemporalFacetQuickFix.extractTemporalKeyword(e));
                    }
                }
            }
            if (scaleEntries.size() > 1) {
                for (int i = 0; i < scaleEntries.size(); i++) {
                    var scaleEntry = scaleEntries.get(i);
                    var clickedKw = scaleKeywords.get(i);
                    var others = new ArrayList<String>();
                    for (int j = 0; j < scaleKeywords.size(); j++) {
                        if (i != j && !others.contains(scaleKeywords.get(j))) {
                            others.add(scaleKeywords.get(j));
                        }
                    }
                    holder.registerProblem(
                        scaleEntry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Temporal scale facets (#s, #ms, #us, #ns) are mutually exclusive",
                            StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MUTUALLY_EXCLUSIVE
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        new KeepOnlyTemporalFacetQuickFix(scaleEntry, clickedKw, others, false),
                        new RemoveRedundantFacetQuickFix(scaleEntry, clickedKw)
                    );
                }
            } else if (scaleEntries.isEmpty()) {
                holder.registerProblem(
                    schemaType,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal type ':TimeEpoch' requires a scale facet: '#s', '#ms', '#us', or '#ns'",
                        StvnDiagnosticCodes.ERR_TEMPORAL_SCALE_MISSING
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_SCALE_MS)
                );
            }
        } else if (StvnVocabulary.TYPE_DATE_TIME.equals(typeText)) {
            var modeEntries = new ArrayList<MetadataEntry>();
            var modeKeywords = new ArrayList<String>();
            if (metaMap != null) {
                for (var e : metaMap.getMetadataEntryList()) {
                    if (KeepOnlyTemporalFacetQuickFix.isModeEntry(e)) {
                        modeEntries.add(e);
                        modeKeywords.add(KeepOnlyTemporalFacetQuickFix.extractTemporalKeyword(e));
                    }
                }
            }
            if (modeEntries.size() > 1) {
                for (int i = 0; i < modeEntries.size(); i++) {
                    var modeEntry = modeEntries.get(i);
                    var clickedKw = modeKeywords.get(i);
                    var others = new ArrayList<String>();
                    for (int j = 0; j < modeKeywords.size(); j++) {
                        if (i != j && !others.contains(modeKeywords.get(j))) {
                            others.add(modeKeywords.get(j));
                        }
                    }
                    holder.registerProblem(
                        modeEntry,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Temporal facets '#offset', '#zoned', and '#audited' are mutually exclusive",
                            StvnDiagnosticCodes.ERR_DATETIME_MODE_MUTUALLY_EXCLUSIVE
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        new KeepOnlyTemporalFacetQuickFix(modeEntry, clickedKw, others, true),
                        new RemoveRedundantFacetQuickFix(modeEntry, clickedKw)
                    );
                }
            } else if (modeEntries.isEmpty()) {
                holder.registerProblem(
                    schemaType,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal type ':DateTime' requires explicit mode facet: '#offset', '#zoned', or '#audited'",
                        StvnDiagnosticCodes.ERR_DATETIME_MODE_MISSING
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_OFFSET)
                );
            }
        }
    }

    private static final class AddDefaultTemporalFacetQuickFix implements LocalQuickFix {
        private final SchemaType schemaType;
        private final String facetText;

        AddDefaultTemporalFacetQuickFix(SchemaType schemaType, String facetText) {
            this.schemaType = schemaType;
            this.facetText = facetText;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Add temporal facet '" + facetText + "'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!schemaType.isValid()) return;
            var existingMeta = schemaType.getMetadataMap();
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
                schemaType.addBefore(newMeta, schemaType.getFirstChild());
            }
        }
    }
}
