package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.ir.StvnValue;
import org.stvnadore.plugin.ir.StvnSumTrajectory;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inspection that detects and fixes inconsistent sum variant style (short-form vs long-form)
 * and redundant sum-type discriminator tags verified via full-spine Pure-IR equivalence.
 */
@NullMarked
public final class StvnVariantStyleInspection extends LocalInspectionTool {

    /**
     * Constructs a new sum variant style inspection.
     */
    public StvnVariantStyleInspection() {
    }

    @SuppressWarnings("unchecked")
    private static <T extends StvnValue> @Nullable T findMatchingSumNode(
        @Nullable StvnValue coreVal,
        Class<T> sumClass
    ) {
        var curr = coreVal;
        while (StvnSumTrajectory.isSumValue(curr)) {
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
        if (StvnPreludeBridge.isPreludeFile(holder.getFile())) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        var projSettings = StvnProjectSettings.getInstance(project);
        if (projSettings == null) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        var state = projSettings.getState();
        if (!state.enableRedundantTagInspection && !state.enableFormDiscrepancyInspection) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        Map<Integer, PsiElement> redundantTags = (state.enableRedundantTagInspection && state.preferImpliedSumTypes)
            ? StvnSumTagRedundancyPsiBridge.getRedundantTagElements(holder.getFile())
            : Map.of();

        return new Visitor() {
            @Override
            public void visitExplicitOptionValue(@NotNull ExplicitOptionValue o) {
                super.visitExplicitOptionValue(o);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(o)) {
                    return;
                }
                var coreVal = StvnSumTagRedundancyPsiBridge.resolveCoreOrConstantValue(o);
                var optVal = findMatchingSumNode(coreVal, StvnValue.StvnOption.class);
                if (optVal != null) {
                    var isSomeTag = o.getSomeLiteral() != null || o.getSomeShortLiteral() != null;
                    var tagElement = o.getSomeLiteral() != null ? o.getSomeLiteral() :
                                     (o.getSomeShortLiteral() != null ? o.getSomeShortLiteral() : null);

                    // 1. Redundant Tag check (Warning A)
                    if (isSomeTag
                        && tagElement != null
                        && redundantTags.containsKey(tagElement.getTextRange().getStartOffset())) {
                        holder.registerProblem(
                            tagElement,
                            StvnDiagnosticFormatter.formatPlugin("Redundant variant tag", StvnDiagnosticCodes.WARN_REDUNDANT_VARIANT_TAG),
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new StripVariantTagQuickFix()
                        );
                    }

                    // 2. Form Discrepancy check (Warning B)
                    if (state.enableFormDiscrepancyInspection) {
                        var useLong = StvnSettings.getInstance(project).getState().useLongFormSumTypes;
                        if (useLong) {
                            if (o.getSomeShortLiteral() != null) {
                                holder.registerProblem(o.getSomeShortLiteral(), StvnDiagnosticFormatter.formatPlugin("Use long-form tag '#Some'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_SOME));
                            }
                            if (o.getNoneShortLiteral() != null) {
                                holder.registerProblem(o.getNoneShortLiteral(), StvnDiagnosticFormatter.formatPlugin("Use long-form tag '#None'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_NONE));
                            }
                        } else {
                            if (o.getSomeLiteral() != null) {
                                holder.registerProblem(o.getSomeLiteral(), StvnDiagnosticFormatter.formatPlugin("Use short-form tag '#S'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_SOME_SHORT));
                            }
                            if (o.getNoneLiteral() != null) {
                                holder.registerProblem(o.getNoneLiteral(), StvnDiagnosticFormatter.formatPlugin("Use short-form tag '#N'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_NONE_SHORT));
                            }
                        }
                    }
                }
            }

            @Override
            public void visitExplicitEitherValue(@NotNull ExplicitEitherValue o) {
                super.visitExplicitEitherValue(o);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(o)) {
                    return;
                }
                var coreVal = StvnSumTagRedundancyPsiBridge.resolveCoreOrConstantValue(o);
                var either = findMatchingSumNode(coreVal, StvnValue.StvnEither.class);
                if (either != null) {
                    var isRightTag = o.getRightLiteral() != null || o.getRightShortLiteral() != null;
                    var tagElement = o.getLeftLiteral() != null ? o.getLeftLiteral() :
                                     (o.getLeftShortLiteral() != null ? o.getLeftShortLiteral() :
                                     (o.getRightLiteral() != null ? o.getRightLiteral() :
                                     (o.getRightShortLiteral() != null ? o.getRightShortLiteral() : null)));

                    // 1. Redundant Tag check (Rule E: #Left is never inferable; Rule B: #Right is inferable if unambiguous)
                    if (isRightTag
                        && tagElement != null
                        && redundantTags.containsKey(tagElement.getTextRange().getStartOffset())) {
                        holder.registerProblem(
                            tagElement,
                            StvnDiagnosticFormatter.formatPlugin("Redundant variant tag", StvnDiagnosticCodes.WARN_REDUNDANT_VARIANT_TAG),
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new StripVariantTagQuickFix()
                        );
                    }

                    // 2. Form Discrepancy check (Warning B)
                    if (state.enableFormDiscrepancyInspection) {
                        var useLong = StvnSettings.getInstance(project).getState().useLongFormSumTypes;
                        if (useLong) {
                            if (o.getLeftShortLiteral() != null) {
                                holder.registerProblem(o.getLeftShortLiteral(), StvnDiagnosticFormatter.formatPlugin("Use long-form tag '#Left'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_LEFT));
                            }
                            if (o.getRightShortLiteral() != null) {
                                holder.registerProblem(o.getRightShortLiteral(), StvnDiagnosticFormatter.formatPlugin("Use long-form tag '#Right'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_RIGHT));
                            }
                        } else {
                            if (o.getLeftLiteral() != null) {
                                holder.registerProblem(o.getLeftLiteral(), StvnDiagnosticFormatter.formatPlugin("Use short-form tag '#L'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_LEFT_SHORT));
                            }
                            if (o.getRightLiteral() != null) {
                                holder.registerProblem(o.getRightLiteral(), StvnDiagnosticFormatter.formatPlugin("Use short-form tag '#R'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_RIGHT_SHORT));
                            }
                        }
                    }
                }
            }

            @Override
            public void visitExplicitUnionValue(@NotNull ExplicitUnionValue o) {
                super.visitExplicitUnionValue(o);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(o)) {
                    return;
                }
                var coreVal = StvnSumTagRedundancyPsiBridge.resolveCoreOrConstantValue(o);
                var union = findMatchingSumNode(coreVal, StvnValue.StvnUnion.class);
                if (union != null) {
                    var tagElement = o.getUnionTagPrefix();

                    // 1. Redundant Tag check with Strict Disjointness Invariant (Rule C)
                    if (tagElement != null
                        && redundantTags.containsKey(tagElement.getTextRange().getStartOffset())) {
                        holder.registerProblem(
                            tagElement,
                            StvnDiagnosticFormatter.formatPlugin("Redundant variant tag", StvnDiagnosticCodes.WARN_REDUNDANT_VARIANT_TAG),
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new StripVariantTagQuickFix()
                        );
                    }
                }
            }

            @Override
            public void visitBooleanValue(@NotNull BooleanValue o) {
                super.visitBooleanValue(o);
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(o)) {
                    return;
                }
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
                        holder.registerProblem(trueShort, StvnDiagnosticFormatter.formatPlugin("Use long-form tag '#TRUE'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_TRUE));
                    }
                    var falseShort = o.getFalseShortLiteral();
                    if (falseShort != null) {
                        holder.registerProblem(falseShort, StvnDiagnosticFormatter.formatPlugin("Use long-form tag '#FALSE'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_FALSE));
                    }
                } else {
                    var trueLong = o.getTrueLiteral();
                    if (trueLong != null) {
                        holder.registerProblem(trueLong, StvnDiagnosticFormatter.formatPlugin("Use short-form tag '#T'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_TRUE_SHORT));
                    }
                    var falseLong = o.getFalseLiteral();
                    if (falseLong != null) {
                        holder.registerProblem(falseLong, StvnDiagnosticFormatter.formatPlugin("Use short-form tag '#F'", StvnDiagnosticCodes.WARN_VARIANT_FORM_DISCREPANCY), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, new ChangeVariantTagFormQuickFix(StvnVocabulary.VAL_FALSE_SHORT));
                    }
                }
            }
        };
    }

    private static final class StripVariantTagQuickFix implements LocalQuickFix, BatchQuickFix {
        @Override
        public @NotNull String getFamilyName() {
            return "Remove redundant tag";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element != null) {
                stripSingleElement(element);
            }
        }

        @Override
        public void applyFix(
            @NotNull Project project,
            @NotNull CommonProblemDescriptor[] descriptors,
            @NotNull List<PsiElement> psiElementsToIgnore,
            @Nullable Runnable refreshViews
        ) {
            if (descriptors.length == 0) {
                return;
            }
            record StripTarget(PsiElement element, int startOffset) {
            }
            List<StripTarget> targets = new ArrayList<>(descriptors.length);
            Set<PsiElement> seen = new HashSet<>();
            for (CommonProblemDescriptor desc : descriptors) {
                if (desc instanceof ProblemDescriptor pd) {
                    PsiElement psi = pd.getPsiElement();
                    if (psi != null && psi.isValid() && seen.add(psi)) {
                        targets.add(new StripTarget(psi, psi.getTextRange().getStartOffset()));
                    }
                }
            }
            // Apply innermost-to-outermost (descending offset) so outer parent.replace(inner) never detaches inner tags
            targets.sort((a, b) -> Integer.compare(b.startOffset(), a.startOffset()));
            for (StripTarget target : targets) {
                if (target.element().isValid()) {
                    stripSingleElement(target.element());
                }
            }
            if (refreshViews != null) {
                refreshViews.run();
            }
        }

        private static void stripSingleElement(PsiElement element) {
            if (!element.isValid()) {
                return;
            }
            PsiElement explicitWrapper = resolveImmediateExplicitWrapper(element);
            if (explicitWrapper == null || !StvnSumTagRedundancyPsiBridge.canSafelyStripWrapper(explicitWrapper)) {
                return;
            }

            Value inner = switch (explicitWrapper) {
                case ExplicitOptionValue opt -> opt.getValue();
                case ExplicitEitherValue eith -> eith.getValue();
                case ExplicitUnionValue un -> un.getValue();
                default -> null;
            };
            PsiElement parent = explicitWrapper.getParent();
            if (inner == null || parent == null) {
                return;
            }
            hoistInteriorCommentsBeforeParent(explicitWrapper, inner, parent);
            parent.replace(inner);
        }

        private static @Nullable PsiElement resolveImmediateExplicitWrapper(PsiElement element) {
            if (element instanceof ExplicitOptionValue
                || element instanceof ExplicitEitherValue
                || element instanceof ExplicitUnionValue) {
                return element;
            }
            return PsiTreeUtil.getParentOfType(
                element,
                ExplicitOptionValue.class,
                ExplicitEitherValue.class,
                ExplicitUnionValue.class
            );
        }

        private static void hoistInteriorCommentsBeforeParent(
            PsiElement explicitWrapper,
            Value inner,
            PsiElement wrapperValueParent
        ) {
            PsiElement container = wrapperValueParent.getParent();
            PsiElement tagTok = StvnSumTagRedundancyPsiBridge.extractTagTokenElement(explicitWrapper);
            if (container == null || tagTok == null) {
                return;
            }
            boolean sawComment = false;
            for (PsiElement cursor = tagTok.getNextSibling();
                 cursor != null && cursor != inner;
                 cursor = cursor.getNextSibling()) {
                if (cursor instanceof PsiComment) {
                    sawComment = true;
                    container.addBefore(cursor.copy(), wrapperValueParent);
                } else if (sawComment && cursor instanceof PsiWhiteSpace) {
                    container.addBefore(cursor.copy(), wrapperValueParent);
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
            if (val != null && (targetTag.equals(StvnVocabulary.VAL_TRUE) || targetTag.equals(StvnVocabulary.VAL_FALSE) || targetTag.equals(StvnVocabulary.VAL_TRUE_SHORT) || targetTag.equals(StvnVocabulary.VAL_FALSE_SHORT))) {
                if (!StvnTypeResolver.resolvesToBoolean(val)) {
                    return;
                }
                var dummy = StvnElementFactory.createValue(project, targetTag);
                val.replace(dummy);
                return;
            }

            var coreVal = StvnSumTagRedundancyPsiBridge.resolveCoreOrConstantValue(element);
            if (coreVal != null) {
                if ((targetTag.equals(StvnVocabulary.VAL_SOME) || targetTag.equals(StvnVocabulary.VAL_SOME_SHORT) || targetTag.equals(StvnVocabulary.VAL_NONE) || targetTag.equals(StvnVocabulary.VAL_NONE_SHORT))
                    && findMatchingSumNode(coreVal, StvnValue.StvnOption.class) == null) {
                    return;
                }
                if ((targetTag.equals(StvnVocabulary.VAL_LEFT) || targetTag.equals(StvnVocabulary.VAL_LEFT_SHORT) || targetTag.equals(StvnVocabulary.VAL_RIGHT) || targetTag.equals(StvnVocabulary.VAL_RIGHT_SHORT))
                    && findMatchingSumNode(coreVal, StvnValue.StvnEither.class) == null) {
                    return;
                }
            }

            PsiElement newLiteral = null;
            if (targetTag.equals(StvnVocabulary.VAL_SOME)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_SOME + " 1");
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getSomeLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_SOME_SHORT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_SOME_SHORT + " 1");
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getSomeShortLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_NONE)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_NONE);
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getNoneLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_NONE_SHORT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_NONE_SHORT);
                var opt = dummy.getExplicitOptionValue();
                if (opt != null) {
                    newLiteral = opt.getNoneShortLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_LEFT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_LEFT + " 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getLeftLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_LEFT_SHORT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_LEFT_SHORT + " 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getLeftShortLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_RIGHT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_RIGHT + " 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getRightLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_RIGHT_SHORT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_RIGHT_SHORT + " 1");
                var either = dummy.getExplicitEitherValue();
                if (either != null) {
                    newLiteral = either.getRightShortLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_TRUE)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_TRUE);
                var boolVal = dummy.getBooleanValue();
                if (boolVal != null) {
                    newLiteral = boolVal.getTrueLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_TRUE_SHORT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_TRUE_SHORT);
                var boolVal = dummy.getBooleanValue();
                if (boolVal != null) {
                    newLiteral = boolVal.getTrueShortLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_FALSE)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_FALSE);
                var boolVal = dummy.getBooleanValue();
                if (boolVal != null) {
                    newLiteral = boolVal.getFalseLiteral();
                }
            } else if (targetTag.equals(StvnVocabulary.VAL_FALSE_SHORT)) {
                var dummy = StvnElementFactory.createValue(project, StvnVocabulary.VAL_FALSE_SHORT);
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
