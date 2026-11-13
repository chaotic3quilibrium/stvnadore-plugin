package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
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
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(typeDef)) {
                    return;
                }
                var schemaType = typeDef.getSchemaType();
                if (schemaType == null) {
                    return;
                }
                var metaMap = typeDef.getMetadataMap() != null ? typeDef.getMetadataMap() : schemaType.getMetadataMap();
                validateTemporalSchema(schemaType, metaMap, holder, isOnTheFly);
            }

            @Override
            public void visitConstantDefinition(@NotNull ConstantDefinition constDef) {
                super.visitConstantDefinition(constDef);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(constDef)) {
                    return;
                }
                var schemaType = constDef.getSchemaType();
                if (schemaType == null) {
                    return;
                }
                var metaMap = constDef.getMetadataMap() != null ? constDef.getMetadataMap() : schemaType.getMetadataMap();
                validateTemporalSchema(schemaType, metaMap, holder, isOnTheFly);
            }

            @Override
            public void visitTypeEntry(@NotNull TypeEntry typeEntry) {
                super.visitTypeEntry(typeEntry);
                // Handled via visitSchemaType
            }

            @Override
            public void visitSchemaType(@NotNull SchemaType schemaType) {
                super.visitSchemaType(schemaType);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(schemaType)) {
                    return;
                }
                if (schemaType.getParent() instanceof TypeDefinition
                        || schemaType.getParent() instanceof ConstantDefinition) {
                    return; // Handled by visitTypeDefinition / visitConstantDefinition
                }
                validateTemporalSchema(schemaType, schemaType.getMetadataMap(), holder, isOnTheFly);
            }
        };
    }

    private static void validateTemporalSchema(
            @NotNull SchemaType schemaType,
            @Nullable MetadataMap metaMap,
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly
    ) {
        var typeText = StvnSchemaFormatter.formatCleanSchema(schemaType).trim();
        var ctor = schemaType.getSchemaConstructor();
        var pinTarget = (ctor != null && ctor.getAtomicType() != null)
            ? ctor.getAtomicType()
            : schemaType;
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
                    var fixes = new LocalQuickFix[] {
                        new KeepOnlyTemporalFacetQuickFix(scaleEntry, clickedKw, others, false),
                        new RemoveRedundantFacetQuickFix(scaleEntry, clickedKw, "Remove conflicting facet")
                    };
                    if (i >= 1) {
                        holder.registerProblem(
                            scaleEntry,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Temporal scale facets ('" + StvnVocabulary.FACET_KW_SCALE_NS + "', '"
                                    + StvnVocabulary.FACET_KW_SCALE_US + "', '"
                                    + StvnVocabulary.FACET_KW_SCALE_MS + "', '"
                                    + StvnVocabulary.FACET_KW_SCALE_S + "') are mutually exclusive",
                                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            fixes
                        );
                    } else if (isOnTheFly) {
                        holder.registerProblem(scaleEntry, "", ProblemHighlightType.INFORMATION, fixes);
                    }
                }
            } else if (scaleEntries.isEmpty()) {
                holder.registerProblem(
                    pinTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal primitive '" + StvnVocabulary.TYPE_TIME_EPOCH
                            + "' requires exactly one scale facet ('" + StvnVocabulary.FACET_KW_SCALE_NS
                            + "', '" + StvnVocabulary.FACET_KW_SCALE_US + "', '"
                            + StvnVocabulary.FACET_KW_SCALE_MS + "', or '"
                            + StvnVocabulary.FACET_KW_SCALE_S + "')",
                        DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    createTimeEpochScaleQuickFixes(schemaType)
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
                    var fixes = new LocalQuickFix[] {
                        new KeepOnlyTemporalFacetQuickFix(modeEntry, clickedKw, others, true),
                        new RemoveRedundantFacetQuickFix(modeEntry, clickedKw, "Remove conflicting facet")
                    };
                    if (i >= 1) {
                        holder.registerProblem(
                            modeEntry,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Temporal mode facets ('" + StvnVocabulary.FACET_KW_OFFSET + "', '"
                                    + StvnVocabulary.FACET_KW_ZONED + "', '"
                                    + StvnVocabulary.FACET_KW_AUDITED + "') are mutually exclusive",
                                DiagnosticBag.ERR_MUTUALLY_EXCLUSIVE
                            ),
                            ProblemHighlightType.GENERIC_ERROR,
                            fixes
                        );
                    } else if (isOnTheFly) {
                        holder.registerProblem(modeEntry, "", ProblemHighlightType.INFORMATION, fixes);
                    }
                }
            } else if (modeEntries.isEmpty()) {
                holder.registerProblem(
                    pinTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Temporal primitive '" + StvnVocabulary.TYPE_DATE_TIME
                            + "' requires exactly one mode facet ('" + StvnVocabulary.FACET_KW_OFFSET
                            + "', '" + StvnVocabulary.FACET_KW_ZONED + "', or '"
                            + StvnVocabulary.FACET_KW_AUDITED + "')",
                        DiagnosticBag.ERR_MISSING_TEMPORAL_FACET
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    createDateTimeModeQuickFixes(schemaType)
                );
            }
        }
    }

    static AddDefaultTemporalFacetQuickFix @NotNull [] createTimeEpochScaleQuickFixes(@NotNull SchemaType schemaType) {
        return new AddDefaultTemporalFacetQuickFix[] {
            new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_SCALE_MS),
            new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_SCALE_S),
            new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_SCALE_US),
            new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_SCALE_NS)
        };
    }

    static AddDefaultTemporalFacetQuickFix @NotNull [] createDateTimeModeQuickFixes(@NotNull SchemaType schemaType) {
        return new AddDefaultTemporalFacetQuickFix[] {
            new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_OFFSET),
            new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_ZONED),
            new AddDefaultTemporalFacetQuickFix(schemaType, StvnVocabulary.FACET_KW_AUDITED)
        };
    }

    static final class AddDefaultTemporalFacetQuickFix implements LocalQuickFix, IntentionAction {
        private final SchemaType schemaType;
        private final String facetText;

        AddDefaultTemporalFacetQuickFix(SchemaType schemaType, String facetText) {
            this.schemaType = schemaType;
            this.facetText = facetText;
        }

        @Override
        public @NotNull String getText() {
            return getFamilyName();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Add temporal facet '" + facetText + "'";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            return schemaType.isValid();
        }

        @Override
        public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            applyTemporalFacet(project);
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            applyTemporalFacet(project);
        }

        private void applyTemporalFacet(@NotNull Project project) {
            if (!schemaType.isValid()) {
                return;
            }
            MetadataMap existingMeta = schemaType.getMetadataMap();
            if (existingMeta == null && schemaType.getParent() instanceof TypeDefinition td) {
                existingMeta = td.getMetadataMap();
            } else if (existingMeta == null && schemaType.getParent() instanceof ConstantDefinition cd) {
                existingMeta = cd.getMetadataMap();
            }
            if (existingMeta != null) {
                var entry = StvnElementFactory.createMetadataEntry(project, facetText);
                var entries = existingMeta.getMetadataEntryList();
                if (!entries.isEmpty()) {
                    existingMeta.addAfter(entry, entries.get(entries.size() - 1));
                } else {
                    existingMeta.add(entry);
                }
                StvnReorderMetadataFacetsQuickFix.reorderMetadataMap(project, existingMeta);
            } else {
                var newMeta = StvnElementFactory.createMetadataMap(project, facetText);
                schemaType.addBefore(newMeta, schemaType.getFirstChild());
            }
        }
    }
}
