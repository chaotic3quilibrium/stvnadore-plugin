package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.psi.*;

/**
 * Local inspection tool flagging degenerate arity-1 composites (:Enum, :Union, :Tuple)
 * in STVN schema definitions, providing atomic unwrapping quick-fixes for :Union and :Tuple.
 */
@NullMarked
public final class StvnDegenerateCompositeInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitProductType(@NotNull ProductType o) {
                super.visitProductType(o);
                var types = o.getSchemaTypeList();
                if (types.size() == 1) {
                    var innerType = types.get(0);
                    var highlightElement = findKeywordToken(o, StvnTypes.KW_TUPLE);
                    var formatted = StvnSchemaFormatter.formatCleanSchema(o);
                    var innerFormatted = StvnSchemaFormatter.formatCleanSchema(innerType);
                    var message = "Degenerate 1-element tuple: '" + formatted + "' provides redundant container wrapping";

                    holder.registerProblem(
                        highlightElement != null ? highlightElement : o,
                        message,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new UnwrapDegenerateCompositeQuickFix(innerFormatted)
                    );
                }
            }

            @Override
            public void visitSumType(@NotNull SumType o) {
                super.visitSumType(o);
                var enumDef = o.getEnumDef();
                if (enumDef != null) {
                    var variants = enumDef.getValueKeywordList();
                    if (variants.size() == 1) {
                        var highlightElement = findKeywordToken(o, StvnTypes.KW_ENUM);
                        var formatted = StvnSchemaFormatter.formatCleanSchema(o);
                        var message = "Degenerate 1-variant enum: '" + formatted + "' provides zero branching entropy";

                        holder.registerProblem(
                            highlightElement != null ? highlightElement : o,
                            message,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                        );
                    }
                    return;
                }

                if (isUnionSumType(o)) {
                    var branches = PsiTreeUtil.getChildrenOfTypeAsList(o, SchemaType.class);
                    if (branches.size() == 1) {
                        var innerType = branches.get(0);
                        var highlightElement = findKeywordToken(o, StvnTypes.KW_UNION);
                        var formatted = StvnSchemaFormatter.formatCleanSchema(o);
                        var innerFormatted = StvnSchemaFormatter.formatCleanSchema(innerType);
                        var message = "Degenerate 1-branch union: '" + formatted + "' is isomorphic to its inner type";

                        holder.registerProblem(
                            highlightElement != null ? highlightElement : o,
                            message,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new UnwrapDegenerateCompositeQuickFix(innerFormatted)
                        );
                    }
                }
            }

            @Override
            public void visitCollectionType(@NotNull CollectionType o) {
                super.visitCollectionType(o);
                var firstChild = o.getFirstChild();
                if (firstChild != null && firstChild.getText().equals(":Tuple")) {
                    var types = o.getSchemaTypeList();
                    if (types.size() == 1) {
                        var innerType = types.get(0);
                        var formatted = StvnSchemaFormatter.formatCleanSchema(o);
                        var innerFormatted = StvnSchemaFormatter.formatCleanSchema(innerType);
                        var message = "Degenerate 1-element tuple: '" + formatted + "' provides redundant container wrapping";

                        holder.registerProblem(
                            firstChild,
                            message,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new UnwrapDegenerateCompositeQuickFix(innerFormatted)
                        );
                    }
                }
            }
        };
    }

    private static @Nullable PsiElement findKeywordToken(PsiElement parent, IElementType tokenType) {
        var node = parent.getNode();
        if (node != null) {
            var child = node.findChildByType(tokenType);
            if (child != null) {
                return child.getPsi();
            }
        }
        return parent.getFirstChild();
    }

    private static boolean isUnionSumType(SumType sumType) {
        if (sumType.getEnumDef() != null) {
            return false;
        }
        var node = sumType.getNode();
        if (node != null && node.findChildByType(StvnTypes.KW_UNION) != null) {
            return true;
        }
        var firstChild = sumType.getFirstChild();
        return firstChild != null && firstChild.getText().equals(":Union");
    }

    public static final class UnwrapDegenerateCompositeQuickFix implements LocalQuickFix {
        private final String targetTypeName;

        public UnwrapDegenerateCompositeQuickFix(String targetTypeName) {
            this.targetTypeName = targetTypeName;
        }

        @Override
        public @NotNull String getName() {
            return "Unwrap degenerate composite to '" + targetTypeName + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Unwrap degenerate composite";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }

            // 1. Resolve ProductType (:Tuple)
            var product = element instanceof ProductType
                ? (ProductType) element
                : PsiTreeUtil.getParentOfType(element, ProductType.class, false);
            if (product != null && product.getSchemaTypeList().size() == 1) {
                var inner = product.getSchemaTypeList().get(0);
                var outerSchemaType = PsiTreeUtil.getParentOfType(product, SchemaType.class, false);
                if (outerSchemaType != null) {
                    var newSchema = StvnElementFactory.createSchemaType(project, inner.getText());
                    outerSchemaType.replace(newSchema);
                }
                return;
            }

            // 2. Resolve CollectionType with :Tuple (defensive)
            var collection = element instanceof CollectionType
                ? (CollectionType) element
                : PsiTreeUtil.getParentOfType(element, CollectionType.class, false);
            if (collection != null && collection.getFirstChild() != null && collection.getFirstChild().getText().equals(":Tuple")
                && collection.getSchemaTypeList().size() == 1) {
                var inner = collection.getSchemaTypeList().get(0);
                var outerSchemaType = PsiTreeUtil.getParentOfType(collection, SchemaType.class, false);
                if (outerSchemaType != null) {
                    var newSchema = StvnElementFactory.createSchemaType(project, inner.getText());
                    outerSchemaType.replace(newSchema);
                }
                return;
            }

            // 3. Resolve SumType (:Union)
            var sum = element instanceof SumType
                ? (SumType) element
                : PsiTreeUtil.getParentOfType(element, SumType.class, false);
            if (sum != null && isUnionSumType(sum)) {
                var branches = PsiTreeUtil.getChildrenOfTypeAsList(sum, SchemaType.class);
                if (branches.size() == 1) {
                    var inner = branches.get(0);
                    var outerSchemaType = PsiTreeUtil.getParentOfType(sum, SchemaType.class, false);
                    if (outerSchemaType != null) {
                        var newSchema = StvnElementFactory.createSchemaType(project, inner.getText());
                        outerSchemaType.replace(newSchema);
                    }
                }
            }
        }
    }
}
