package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.*;

/**
 * Validates STVN 2.0.0 string cardinality rules (MCT § 3.1.3).
 * Flags prohibited '#size' facets on ':String' and offers intention quick-fixes.
 */
@NullMarked
public final class StvnStringCardinalityInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getShortName() {
        return "StvnStringCardinality";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "String cardinality facet governance inspection";
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
                var baseType = resolveBaseTypeString(schemaType);
                if (!baseType.startsWith(":String")) {
                    return;
                }
                var metaMap = typeDef.getMetadataMap();
                if (metaMap == null) {
                    return;
                }

                for (var entry : metaMap.getMetadataEntryList()) {
                    if (entry.getText().startsWith("#size") || (entry.getNode().findChildByType(StvnTypes.KW_SIZE) != null)) {
                        holder.registerProblem(
                            entry,
                            "Facet '#size' is prohibited on ':String' (MCT § 3.1.3). Use '#minSize' and '#maxSize' for character bounds.",
                            ProblemHighlightType.GENERIC_ERROR,
                            new ConvertSizeToStringBoundsQuickFix(entry)
                        );
                    }
                }
            }
        };
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
            var file = schemaType.getContainingFile();
            if (file != null) {
                var resolved = StvnTypeReference.resolveTypeInFile(file, text, new java.util.HashSet<>());
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

    private static final class ConvertSizeToStringBoundsQuickFix implements LocalQuickFix {
        private final MetadataEntry entry;

        ConvertSizeToStringBoundsQuickFix(MetadataEntry entry) {
            this.entry = entry;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Convert '#size' to '#minSize 1 #maxSize <N>'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            var sizeStr = val != null ? val.getText().trim() : "64";
            var parentMap = entry.getParent();
            if (parentMap instanceof MetadataMap metaMap) {
                var minEntry = StvnElementFactory.createMetadataEntry(project, "#minSize 1");
                var maxEntry = StvnElementFactory.createMetadataEntry(project, "#maxSize " + sizeStr);
                metaMap.addAfter(maxEntry, entry);
                entry.replace(minEntry);
            }
        }
    }
}
