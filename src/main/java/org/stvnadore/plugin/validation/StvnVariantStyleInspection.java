package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.*;

@NullMarked
public final class StvnVariantStyleInspection extends LocalInspectionTool {

    @SuppressWarnings("unchecked")
    private static <T extends StvnValue.StvnSum> @Nullable T findMatchingSumNode(
        @Nullable StvnValue coreVal,
        Class<T> sumClass
    ) {
        var curr = coreVal;
        while (curr instanceof StvnValue.StvnSum) {
            if (sumClass.isInstance(curr)) {
                return (T) curr;
            }
            if (curr instanceof StvnValue.StvnOption opt) {
                curr = opt.value().orElse(null);
            } else if (curr instanceof StvnValue.StvnEither either) {
                curr = either.value();
            } else if (curr instanceof StvnValue.StvnUnion union) {
                curr = union.value();
            } else {
                break;
            }
        }
        return null;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        var project = holder.getProject();
        var projSettings = StvnProjectSettings.getInstance(project);
        if (projSettings == null) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        var state = projSettings.getState();
        if (!state.enableRedundantTagInspection && !state.enableFormDiscrepancyInspection) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        return new Visitor() {
            @Override
            public void visitExplicitOptionValue(@NotNull ExplicitOptionValue o) {
                super.visitExplicitOptionValue(o);
                var coreVal = StvnTypeResolver.resolveCoreValue(o);
                var optVal = findMatchingSumNode(coreVal, StvnValue.StvnOption.class);
                if (optVal != null) {
                    var isSomeTag = o.getSomeLiteral() != null || o.getSomeShortLiteral() != null;
                    var tagElement = o.getSomeLiteral() != null ? o.getSomeLiteral() : 
                                     (o.getSomeShortLiteral() != null ? o.getSomeShortLiteral() : null);

                    // 1. Redundant Tag check (Warning A)
                    if (state.enableRedundantTagInspection && state.preferImpliedSumTypes && isSomeTag && tagElement != null) {
                        var innerVal = o.getValue();
                        var isInnerNone = false;
                        if (innerVal != null) {
                            var innerOpt = innerVal.getExplicitOptionValue();
                            if (innerOpt != null && (innerOpt.getNoneLiteral() != null || innerOpt.getNoneShortLiteral() != null)) {
                                isInnerNone = true;
                            }
                        }

                        if (!isInnerNone) {
                            holder.registerProblem(tagElement, "Redundant variant tag", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new StripVariantTagQuickFix());
                        }
                    }

                    // 2. Form Discrepancy check (Warning B)
                    if (state.enableFormDiscrepancyInspection) {
                        var useLong = StvnSettings.getInstance(project).getState().useLongFormSumTypes;
                        if (useLong) {
                            if (o.getSomeShortLiteral() != null) {
                                holder.registerProblem(o.getSomeShortLiteral(), "Use long-form tag '#Some'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#Some"));
                            }
                            if (o.getNoneShortLiteral() != null) {
                                holder.registerProblem(o.getNoneShortLiteral(), "Use long-form tag '#None'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#None"));
                            }
                        } else {
                            if (o.getSomeLiteral() != null) {
                                holder.registerProblem(o.getSomeLiteral(), "Use short-form tag '#S'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#S"));
                            }
                            if (o.getNoneLiteral() != null) {
                                holder.registerProblem(o.getNoneLiteral(), "Use short-form tag '#N'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#N"));
                            }
                        }
                    }
                }
            }

            @Override
            public void visitExplicitEitherValue(@NotNull ExplicitEitherValue o) {
                super.visitExplicitEitherValue(o);
                var coreVal = StvnTypeResolver.resolveCoreValue(o);
                var either = findMatchingSumNode(coreVal, StvnValue.StvnEither.class);
                if (either != null) {
                    var isLeftTag = o.getLeftLiteral() != null || o.getLeftShortLiteral() != null;
                    var isRightTag = o.getRightLiteral() != null || o.getRightShortLiteral() != null;
                    var tagElement = o.getLeftLiteral() != null ? o.getLeftLiteral() :
                                     (o.getLeftShortLiteral() != null ? o.getLeftShortLiteral() :
                                     (o.getRightLiteral() != null ? o.getRightLiteral() :
                                     (o.getRightShortLiteral() != null ? o.getRightShortLiteral() : null)));

                    // 1. Redundant Tag check (Rule E: #Left is never inferable; Rule B: #Right is inferable if unambiguous)
                    if (state.enableRedundantTagInspection && state.preferImpliedSumTypes && isRightTag && tagElement != null) {
                        var child = o.getValue();
                        var valParent = PsiTreeUtil.getParentOfType(o, Value.class);
                        if (child != null && valParent != null) {
                            var info = StvnTypeResolver.resolveBaseTypeInfo(valParent);
                            if (info != null) {
                                var resolved = StvnTypeResolver.resolveNominalSchema(info.getSchema());
                                var schemaToInspect = (resolved != null) ? resolved : info.getSchema();
                                var ctor = schemaToInspect.getSchemaConstructor();
                                if (ctor != null && ctor.getSumType() != null) {
                                    var sumType = ctor.getSumType();
                                    while (sumType != null && sumType.getText().startsWith(":Option")) {
                                        var optBranches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                                        if (optBranches.isEmpty()) {
                                            break;
                                        }
                                        var innerSchema = optBranches.get(0);
                                        var res = StvnTypeResolver.resolveNominalSchema(innerSchema);
                                        schemaToInspect = (res != null) ? res : innerSchema;
                                        var c = schemaToInspect.getSchemaConstructor();
                                        sumType = (c != null) ? c.getSumType() : null;
                                    }
                                    if (sumType != null && sumType.getText().startsWith(":Either")) {
                                        var innerBranches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                                        if (innerBranches.size() >= 2) {
                                            var leftBranch = innerBranches.get(0);
                                            var rightBranch = innerBranches.get(1);
                                            var matchesLeft = StvnTypeResolver.matchesSchemaPattern(child, leftBranch);
                                            var matchesRight = StvnTypeResolver.matchesSchemaPattern(child, rightBranch);
                                            if (!matchesLeft && matchesRight) {
                                                holder.registerProblem(tagElement, "Redundant variant tag", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new StripVariantTagQuickFix());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. Form Discrepancy check (Warning B)
                    if (state.enableFormDiscrepancyInspection) {
                        var useLong = StvnSettings.getInstance(project).getState().useLongFormSumTypes;
                        if (useLong) {
                            if (o.getLeftShortLiteral() != null) {
                                holder.registerProblem(o.getLeftShortLiteral(), "Use long-form tag '#Left'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#Left"));
                            }
                            if (o.getRightShortLiteral() != null) {
                                holder.registerProblem(o.getRightShortLiteral(), "Use long-form tag '#Right'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#Right"));
                            }
                        } else {
                            if (o.getLeftLiteral() != null) {
                                holder.registerProblem(o.getLeftLiteral(), "Use short-form tag '#L'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#L"));
                            }
                            if (o.getRightLiteral() != null) {
                                holder.registerProblem(o.getRightLiteral(), "Use short-form tag '#R'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#R"));
                            }
                        }
                    }
                }
            }

            @Override
            public void visitExplicitUnionValue(@NotNull ExplicitUnionValue o) {
                super.visitExplicitUnionValue(o);
                var coreVal = StvnTypeResolver.resolveCoreValue(o);
                var union = findMatchingSumNode(coreVal, StvnValue.StvnUnion.class);
                if (union != null) {
                    var tagElement = o.getUnionTagPrefix();

                    // 1. Redundant Tag check with Strict Disjointness Invariant (Rule C)
                    if (state.enableRedundantTagInspection && state.preferImpliedSumTypes && tagElement != null) {
                        var tagText = tagElement.getText();
                        if (tagText.startsWith("#")) {
                            int tagIndex;
                            try {
                                tagIndex = Integer.parseInt(tagText.substring(1)) - 1;
                            } catch (NumberFormatException e) {
                                return;
                            }

                            var child = o.getValue();
                            var valParent = PsiTreeUtil.getParentOfType(o, Value.class);
                            if (child != null && valParent != null) {
                                var info = StvnTypeResolver.resolveBaseTypeInfo(valParent);
                                if (info != null) {
                                    var resolved = StvnTypeResolver.resolveNominalSchema(info.getSchema());
                                    var schemaToInspect = (resolved != null) ? resolved : info.getSchema();
                                    var ctor = schemaToInspect.getSchemaConstructor();
                                    if (ctor != null && ctor.getSumType() != null) {
                                        var sumType = ctor.getSumType();
                                        while (sumType != null && sumType.getText().startsWith(":Option")) {
                                            var optBranches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                                            if (optBranches.isEmpty()) {
                                                break;
                                            }
                                            var innerSchema = optBranches.get(0);
                                            var res = StvnTypeResolver.resolveNominalSchema(innerSchema);
                                            schemaToInspect = (res != null) ? res : innerSchema;
                                            var c = schemaToInspect.getSchemaConstructor();
                                            sumType = (c != null) ? c.getSumType() : null;
                                        }
                                        if (sumType != null && sumType.getText().startsWith(":Union")) {
                                            var innerBranches = PsiTreeUtil.getChildrenOfTypeAsList(sumType, SchemaType.class);
                                            if (!innerBranches.isEmpty() && tagIndex >= 0 && tagIndex < innerBranches.size()) {
                                                int matchCount = 0;
                                                int matchedBranchIndex = -1;
                                                for (int i = 0; i < innerBranches.size(); i++) {
                                                    var branch = innerBranches.get(i);
                                                    if (StvnTypeResolver.matchesSchemaPattern(child, branch)) {
                                                        matchCount++;
                                                        matchedBranchIndex = i;
                                                    }
                                                }
                                                if (matchCount == 1 && matchedBranchIndex == tagIndex) {
                                                    holder.registerProblem(tagElement, "Redundant variant tag", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new StripVariantTagQuickFix());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public void visitBooleanValue(@NotNull BooleanValue o) {
                super.visitBooleanValue(o);
                if (!state.enableFormDiscrepancyInspection) {
                    return;
                }

                var valueParent = PsiTreeUtil.getParentOfType(o, Value.class);
                if (valueParent == null || !StvnTypeResolver.resolvesToBoolean(valueParent)) {
                    return;
                }

                var useLong = StvnSettings.getInstance(project).getState().useLongFormSumTypes;
                if (useLong) {
                    var trueShort = o.getTrueShortLiteral();
                    if (trueShort != null) {
                        holder.registerProblem(trueShort, "Use long-form tag '#TRUE'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#TRUE"));
                    }
                    var falseShort = o.getFalseShortLiteral();
                    if (falseShort != null) {
                        holder.registerProblem(falseShort, "Use long-form tag '#FALSE'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#FALSE"));
                    }
                } else {
                    var trueLong = o.getTrueLiteral();
                    if (trueLong != null) {
                        holder.registerProblem(trueLong, "Use short-form tag '#T'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#T"));
                    }
                    var falseLong = o.getFalseLiteral();
                    if (falseLong != null) {
                        holder.registerProblem(falseLong, "Use short-form tag '#F'", ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix("#F"));
                    }
                }
            }
        };
    }

    private static final class StripVariantTagQuickFix implements LocalQuickFix {
        @Override
        public @NotNull String getFamilyName() {
            return "Remove redundant tag";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }

            var optionValue = element instanceof ExplicitOptionValue 
                ? (ExplicitOptionValue) element 
                : PsiTreeUtil.getParentOfType(element, ExplicitOptionValue.class);
            if (optionValue != null) {
                var coreVal = StvnTypeResolver.resolveCoreValue(optionValue);
                if (coreVal != null && findMatchingSumNode(coreVal, StvnValue.StvnOption.class) == null) {
                    return;
                }
                var parent = optionValue.getParent();
                var inner = optionValue.getValue();
                if (inner != null && parent != null) {
                    parent.replace(inner);
                }
                return;
            }

            var eitherValue = element instanceof ExplicitEitherValue 
                ? (ExplicitEitherValue) element 
                : PsiTreeUtil.getParentOfType(element, ExplicitEitherValue.class);
            if (eitherValue != null) {
                var coreVal = StvnTypeResolver.resolveCoreValue(eitherValue);
                if (coreVal != null && findMatchingSumNode(coreVal, StvnValue.StvnEither.class) == null) {
                    return;
                }
                var parent = eitherValue.getParent();
                var inner = eitherValue.getValue();
                if (inner != null && parent != null) {
                    parent.replace(inner);
                }
                return;
            }

            var unionValue = element instanceof ExplicitUnionValue 
                ? (ExplicitUnionValue) element 
                : PsiTreeUtil.getParentOfType(element, ExplicitUnionValue.class);
            if (unionValue != null) {
                var coreVal = StvnTypeResolver.resolveCoreValue(unionValue);
                if (coreVal != null && findMatchingSumNode(coreVal, StvnValue.StvnUnion.class) == null) {
                    return;
                }
                var parent = unionValue.getParent();
                var inner = unionValue.getValue();
                if (inner != null && parent != null) {
                    parent.replace(inner);
                }
            }
        }
    }

    static final class ChangeVariantTagFormQuickFix implements LocalQuickFix {
        private final String targetTag;

        public ChangeVariantTagFormQuickFix(String targetTag) {
            this.targetTag = targetTag;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Change tag to " + targetTag;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }

            var val = PsiTreeUtil.getParentOfType(element, Value.class);
            if (val != null && (targetTag.equals("#TRUE") || targetTag.equals("#FALSE") || targetTag.equals("#T") || targetTag.equals("#F"))) {
                if (!StvnTypeResolver.resolvesToBoolean(val)) {
                    return;
                }
                var dummy = StvnElementFactory.createValue(project, targetTag);
                val.replace(dummy);
                return;
            }

            var coreVal = StvnTypeResolver.resolveCoreValue(element);
            if (coreVal != null) {
                if ((targetTag.equals("#Some") || targetTag.equals("#S") || targetTag.equals("#None") || targetTag.equals("#N"))
                    && findMatchingSumNode(coreVal, StvnValue.StvnOption.class) == null) {
                    return;
                }
                if ((targetTag.equals("#Left") || targetTag.equals("#L") || targetTag.equals("#Right") || targetTag.equals("#R"))
                    && findMatchingSumNode(coreVal, StvnValue.StvnEither.class) == null) {
                    return;
                }
            }

            PsiElement newLiteral = null;
            if (targetTag.equals("#Some")) {
                var dummy = StvnElementFactory.createValue(project, "#Some 1");
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getSomeLiteral();
                }
            } else if (targetTag.equals("#S")) {
                var dummy = StvnElementFactory.createValue(project, "#S 1");
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getSomeShortLiteral();
                }
            } else if (targetTag.equals("#None")) {
                var dummy = StvnElementFactory.createValue(project, "#None");
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getNoneLiteral();
                }
            } else if (targetTag.equals("#N")) {
                var dummy = StvnElementFactory.createValue(project, "#N");
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getNoneShortLiteral();
                }
            } else if (targetTag.equals("#Left")) {
                var dummy = StvnElementFactory.createValue(project, "#Left 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getLeftLiteral();
                }
            } else if (targetTag.equals("#L")) {
                var dummy = StvnElementFactory.createValue(project, "#L 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getLeftShortLiteral();
                }
            } else if (targetTag.equals("#Right")) {
                var dummy = StvnElementFactory.createValue(project, "#Right 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getRightLiteral();
                }
            } else if (targetTag.equals("#R")) {
                var dummy = StvnElementFactory.createValue(project, "#R 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getRightShortLiteral();
                }
            } else if (targetTag.equals("#TRUE")) {
                var dummy = StvnElementFactory.createValue(project, "#TRUE");
                var boolVal = dummy.getBooleanValue();
                if (boolVal != null) {
                    newLiteral = boolVal.getTrueLiteral();
                }
            } else if (targetTag.equals("#T")) {
                var dummy = StvnElementFactory.createValue(project, "#T");
                var boolVal = dummy.getBooleanValue();
                if (boolVal != null) {
                    newLiteral = boolVal.getTrueShortLiteral();
                }
            } else if (targetTag.equals("#FALSE")) {
                var dummy = StvnElementFactory.createValue(project, "#FALSE");
                var boolVal = dummy.getBooleanValue();
                if (boolVal != null) {
                    newLiteral = boolVal.getFalseLiteral();
                }
            } else if (targetTag.equals("#F")) {
                var dummy = StvnElementFactory.createValue(project, "#F");
                var boolVal = dummy.getBooleanValue();
                if (boolVal != null) {
                    newLiteral = boolVal.getFalseShortLiteral();
                }
            }

            if (newLiteral != null) {
                element.replace(newLiteral);
            }
        }
    }
}
