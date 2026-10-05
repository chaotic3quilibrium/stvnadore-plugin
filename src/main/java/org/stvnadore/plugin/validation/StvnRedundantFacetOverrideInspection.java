package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.BooleanValue;
import org.stvnadore.psi.MetadataEntry;
import org.stvnadore.psi.MetadataMap;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.Visitor;

/**
 * Validates nominal type definitions in {@code :defs} blocks to detect redundant metadata facet overrides.
 * Flags declared facets that merely restate the parent schema's already-resolved effective state.
 */
@NullMarked
public final class StvnRedundantFacetOverrideInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitTypeDefinition(@NotNull TypeDefinition typeDef) {
                var metaMap = typeDef.getMetadataMap();
                if (metaMap == null || metaMap.getMetadataEntryList().isEmpty()) {
                    return;
                }
                var schemaType = typeDef.getSchemaType();
                if (schemaType == null) {
                    return;
                }

                var rs = StvnTypeResolver.resolveNominalResolvedSchema(typeDef);
                if (rs == null || rs.isPoisonedSentinel()) {
                    return;
                }

                var targetKw = schemaType.getTypeKeyword();
                var parentRs = rs.underlyingSchema().orElse(null);
                if (targetKw != null && parentRs == null) {
                    return;
                }

                var parentConstraints = parentRs != null
                    ? parentRs.constraints()
                    : org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints.empty();

                for (var entry : metaMap.getMetadataEntryList()) {
                    checkRedundantFacet(entry, parentConstraints, holder);
                }
            }
        };
    }

    private static void checkRedundantFacet(
            MetadataEntry entry,
            org.stvnadore.core.validation.StvnTypeResolver.StvnConstraints parentConstraints,
            ProblemsHolder holder
    ) {
        var trait = entry.getMetadataTrait();
        if (trait != null) {
            var bVal = trait.getBooleanValue();
            if (bVal != null) {
                boolean declared = isBooleanTrue(bVal);
                boolean parentEff = parentConstraints.preserveIndent();
                if (declared == parentEff) {
                    registerRedundantProblem(entry, declared ? "#TRUE" : "#FALSE", holder);
                }
            }
            return;
        }

        var bareFlag = entry.getMetadataBareFlag();
        if (bareFlag != null) {
            var text = bareFlag.getText().trim();
            if (text.startsWith(StvnVocabulary.FACET_KW_UNSIGNED) && parentConstraints.unsigned()) {
                registerRedundantProblem(entry, "#TRUE", holder);
            } else if (text.startsWith(StvnVocabulary.FACET_KW_EXACT) && parentConstraints.exact()) {
                registerRedundantProblem(entry, "#TRUE", holder);
            } else if (text.startsWith(StvnVocabulary.FACET_KW_INVERTIBLE) && parentConstraints.invertible()) {
                registerRedundantProblem(entry, "#TRUE", holder);
            }
            return;
        }

        var text = entry.getText().trim();
        if (text.startsWith(StvnVocabulary.FACET_KW_MIN_SIZE)) {
            extractNumericValue(text).ifPresent(val -> {
                int parentVal = parentConstraints.minSize().orElse(0);
                if (val == parentVal) {
                    registerRedundantProblem(entry, String.valueOf(val), holder);
                }
            });
        } else if (text.startsWith(StvnVocabulary.FACET_KW_MAX_SIZE)) {
            extractNumericValue(text).ifPresent(val -> {
                int parentVal = parentConstraints.maxSize().orElse(StvnVocabulary.DEFAULT_UNBOUNDED_STRING_CAPACITY);
                if (val == parentVal) {
                    registerRedundantProblem(entry, String.valueOf(val), holder);
                }
            });
        } else if (text.startsWith(StvnVocabulary.FACET_KW_EQUATABLE)) {
            extractBooleanLiteral(text).ifPresent(val -> {
                boolean parentVal = parentConstraints.equatable().orElse(true);
                if (val == parentVal) {
                    registerRedundantProblem(entry, val ? "#TRUE" : "#FALSE", holder);
                }
            });
        } else if (text.startsWith(StvnVocabulary.FACET_KW_COMPARABLE)) {
            extractBooleanLiteral(text).ifPresent(val -> {
                boolean parentVal = parentConstraints.comparable().orElse(true);
                if (val == parentVal) {
                    registerRedundantProblem(entry, val ? "#TRUE" : "#FALSE", holder);
                }
            });
        }
    }

    private static void registerRedundantProblem(MetadataEntry entry, String effectiveValue, ProblemsHolder holder) {
        var entryText = entry.getText().trim();
        holder.registerProblem(
            entry,
            "Redundant facet override: parent type already specifies effective value '" + effectiveValue + "'",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new RemoveRedundantFacetQuickFix(entryText)
        );
    }

    private static boolean isBooleanTrue(BooleanValue bVal) {
        var t = bVal.getText().trim();
        return "#TRUE".equals(t) || "#T".equals(t);
    }

    private static java.util.OptionalInt extractNumericValue(String text) {
        var parts = text.split("\\s+");
        if (parts.length >= 2) {
            try {
                return java.util.OptionalInt.of(Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {}
        }
        return java.util.OptionalInt.empty();
    }

    private static java.util.Optional<Boolean> extractBooleanLiteral(String text) {
        var parts = text.split("\\s+");
        if (parts.length >= 2) {
            var val = parts[1].trim();
            if ("#TRUE".equals(val) || "#T".equals(val)) return java.util.Optional.of(true);
            if ("#FALSE".equals(val) || "#F".equals(val)) return java.util.Optional.of(false);
        }
        return java.util.Optional.empty();
    }

    private static final class RemoveRedundantFacetQuickFix implements LocalQuickFix {
        private final String facetSnippet;

        RemoveRedundantFacetQuickFix(String facetSnippet) {
            this.facetSnippet = facetSnippet;
        }

        @Override
        public @Nls @NotNull String getFamilyName() {
            return "Remove redundant facet";
        }

        @Override
        public @Nls @NotNull String getName() {
            return "Remove redundant facet '" + facetSnippet + "'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element instanceof MetadataEntry entry && entry.isValid()) {
                var map = PsiTreeUtil.getParentOfType(entry, MetadataMap.class);
                if (map != null && map.getMetadataEntryList().size() == 1) {
                    var prev = map.getPrevSibling();
                    if (prev instanceof PsiWhiteSpace) {
                        prev.delete();
                    }
                    map.delete();
                } else {
                    var prev = entry.getPrevSibling();
                    if (prev instanceof PsiWhiteSpace) {
                        prev.delete();
                    }
                    entry.delete();
                }
            }
        }
    }
}
