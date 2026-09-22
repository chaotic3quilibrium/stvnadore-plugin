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

import java.math.BigInteger;

/**
 * Validates STVN 2.0.0 discrete interval rules.
 * Discrete types (:Int and { #exact } :Float) reject closed upper bounds (#maxIncl)
 * and open lower bounds (#minExcl) in favor of half-open intervals [minIncl, maxExcl).
 */
@NullMarked
public final class StvnDiscreteIntervalInspection extends LocalInspectionTool {

    @Override
    public @NotNull String getShortName() {
        return "StvnDiscreteInterval";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Discrete interval bound governance inspection";
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
                if (!isDiscreteType(typeDef)) {
                    return;
                }
                var metaMap = typeDef.getMetadataMap();
                if (metaMap == null) {
                    return;
                }

                for (var entry : metaMap.getMetadataEntryList()) {
                    var entryText = entry.getText();
                    if (entryText.startsWith("#maxIncl") || entry.getNode().findChildByType(StvnTypes.KW_MAX_INCL) != null) {
                        holder.registerProblem(
                            entry,
                            "Discrete types reject closed upper bound '#maxIncl'. Use half-open bound '#maxExcl'.",
                            ProblemHighlightType.GENERIC_ERROR,
                            new ConvertMaxInclToMaxExclQuickFix(entry)
                        );
                    } else if (entryText.startsWith("#minExcl") || entry.getNode().findChildByType(StvnTypes.KW_MIN_EXCL) != null) {
                        holder.registerProblem(
                            entry,
                            "Discrete types reject open lower bound '#minExcl'. Use half-open bound '#minIncl'.",
                            ProblemHighlightType.GENERIC_ERROR,
                            new ConvertMinExclToMinInclQuickFix(entry)
                        );
                    }
                }
            }
        };
    }

    private static boolean isDiscreteType(TypeDefinition typeDef) {
        var schemaType = typeDef.getSchemaType();
        if (schemaType == null) return false;
        var baseType = resolveBaseTypeString(schemaType);
        if (baseType.startsWith(":Int")) {
            return true;
        }
        if (baseType.startsWith(":Float")) {
            var metaMap = typeDef.getMetadataMap();
            if (metaMap != null) {
                for (var e : metaMap.getMetadataEntryList()) {
                    if (e.getText().startsWith("#exact") || e.getNode().findChildByType(StvnTypes.KW_EXACT) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static String incrementBound(String valText) {
        try {
            if (valText.startsWith("0x") || valText.startsWith("0X")) {
                var bi = new BigInteger(valText.substring(2), 16).add(BigInteger.ONE);
                return "0x" + bi.toString(16);
            } else if (valText.startsWith("0b") || valText.startsWith("0B")) {
                var bi = new BigInteger(valText.substring(2), 2).add(BigInteger.ONE);
                return "0b" + bi.toString(2);
            } else if (valText.startsWith("0o") || valText.startsWith("0O")) {
                var bi = new BigInteger(valText.substring(2), 8).add(BigInteger.ONE);
                return "0o" + bi.toString(8);
            } else {
                return new BigInteger(valText).add(BigInteger.ONE).toString();
            }
        } catch (Exception e) {
            return valText;
        }
    }

    private static final class ConvertMaxInclToMaxExclQuickFix implements LocalQuickFix {
        private final MetadataEntry entry;

        ConvertMaxInclToMaxExclQuickFix(MetadataEntry entry) {
            this.entry = entry;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Convert '#maxIncl <N>' to '#maxExcl <N+1>'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            var replacement = StvnElementFactory.createMetadataEntry(project, "#maxExcl " + nextVal);
            entry.replace(replacement);
        }
    }

    private static final class ConvertMinExclToMinInclQuickFix implements LocalQuickFix {
        private final MetadataEntry entry;

        ConvertMinExclToMinInclQuickFix(MetadataEntry entry) {
            this.entry = entry;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Convert '#minExcl <N>' to '#minIncl <N+1>'";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            if (!entry.isValid()) return;
            var val = entry.getMetadataValue();
            var valText = val != null ? val.getText().trim() : "0";
            var nextVal = incrementBound(valText);
            var replacement = StvnElementFactory.createMetadataEntry(project, "#minIncl " + nextVal);
            entry.replace(replacement);
        }
    }
}
