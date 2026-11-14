package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.psi.StvnNamespaceRules;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnConvertToModularQuickFix;
import org.stvnadore.psi.DefsElement;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;
import org.stvnadore.psi.Visitor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates hermetic compilation-unit isolation rules under STVN 2.0.0 Specification § 1.2 and § 3.9:
 * <ul>
 *   <li>Prohibits {@code :include} statements inside hermetic documents ({@code E005}).</li>
 *   <li>Prohibits scoped {@code :use} directives targeting external unloaded packages inside hermetic documents ({@code E035}).</li>
 * </ul>
 */
@NullMarked
public final class StvnFlatDocumentIncludeInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnFlatDocumentIncludeInspection instance.
     */
    public StvnFlatDocumentIncludeInspection() {
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitIncludeStmt(@NotNull IncludeStmt stmt) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(stmt)) {
                    return;
                }
                var file = stmt.getContainingFile();
                if (StvnPsiUtils.isHermeticDocument(file)) {
                    holder.registerProblem(
                        stmt,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Hermetic document or schema (.stvn / .stvn_d) cannot contain include statements",
                            StvnDiagnosticCodes.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT
                        ),
                        ProblemHighlightType.GENERIC_ERROR
                    );
                }
            }

            @Override
            public void visitUseStmt(@NotNull UseStmt stmt) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(stmt)) {
                    return;
                }
                PsiFile file = stmt.getContainingFile();
                if (file == null || StvnPreludeBridge.isPreludeFile(file) || !isHermeticCompilationUnit(file)) {
                    return;
                }
                UseTarget useTarget = stmt.getUseTarget();
                if (useTarget == null || StvnTrailingSlashInspection.hasTrailingSlash(useTarget)) {
                    return;
                }
                String target = StvnTrailingSlashInspection.extractNonCommentText(useTarget).trim();
                if (target.isEmpty() || StvnVocabulary.SIGIL_TYPIC.equals(target)) {
                    return;
                }
                if (StvnPrelude.isPreludePackageTarget(target)) {
                    return;
                }
                Set<String> localPackages = collectLocalNonReservedPackages(file);
                if (matchesDeclaredOrParentPackage(target, localPackages)) {
                    return;
                }

                List<LocalQuickFix> fixes = new ArrayList<>(2);
                fixes.add(new RemoveUseStatementQuickFix(target));
                String fileName = file.getName();
                if (fileName.endsWith(".stvn") || fileName.endsWith(".stvn_d")) {
                    fixes.add(new StvnConvertToModularQuickFix(useTarget, fileName.endsWith(".stvn_d")));
                }
                holder.registerProblem(
                    useTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Scoped " + StvnVocabulary.KEYWORD_USE
                            + " in hermetic document (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot target external package: "
                            + target,
                        DiagnosticBag.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
            }
        };
    }

    /**
     * Determines whether a PSI file is a hermetic compilation unit ({@code .stvn}, {@code .stvn_d},
     * {@code .stvn_b}, {@code .stvn_bd}, or explicit {@code #kind #BODY} / {@code #DEFS}).
     *
     * @param file target PSI file
     * @return {@code true} when hermetic isolation rules apply; {@code false} for modular include units
     */
    public static boolean isHermeticCompilationUnit(PsiFile file) {
        for (MetaEntry metaEntry : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaEntry)) {
                continue;
            }
            MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
            if (kindFacet != null && kindFacet.getMetaKindValue() != null) {
                String kindText = kindFacet.getMetaKindValue().getText().trim();
                if (StvnVocabulary.KIND_KW_BODY.equals(kindText) || StvnVocabulary.KIND_KW_DEFS.equals(kindText)) {
                    return true;
                }
                if (StvnVocabulary.KIND_KW_BODY_INCLUDE.equals(kindText) || StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(kindText)) {
                    return false;
                }
            }
            break;
        }
        String name = file.getName();
        return StvnPsiUtils.isHermeticDocument(file)
            || name.endsWith(".stvn_b")
            || name.endsWith(".stvn_bd");
    }

    /**
     * Collects all non-reserved local package paths declared in the primary {@code :defs} section of {@code file},
     * including {@code :package :pkg} enclosures, qualified {@code :pkg/Type} definitions, and qualified {@code #pkg/CONST} constants.
     *
     * @param file target PSI file
     * @return ordered set of locally declared non-reserved package paths
     */
    public static Set<String> collectLocalNonReservedPackages(PsiFile file) {
        Set<String> declared = new LinkedHashSet<>();
        for (DefsEntry defsEntry : PsiTreeUtil.findChildrenOfType(file, DefsEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(defsEntry)) {
                continue;
            }
            for (DefsElement elem : defsEntry.getDefsElementList()) {
                if (elem.getPackageEnclosure() != null && elem.getPackageEnclosure().getPackagePath() != null) {
                    String pkgPath = StvnTrailingSlashInspection.extractNonCommentText(
                        elem.getPackageEnclosure().getPackagePath()).trim();
                    if (pkgPath.startsWith(StvnVocabulary.SIGIL_TYPIC) && !StvnNamespaceRules.isReservedPackage(pkgPath)) {
                        declared.add(pkgPath);
                    }
                } else if (elem.getTypeDefinition() != null && elem.getTypeDefinition().getTypeDefTarget() != null) {
                    String rawName = elem.getTypeDefinition().getTypeDefTarget().getText().trim();
                    int lastSlash = rawName.lastIndexOf('/');
                    if (rawName.startsWith(StvnVocabulary.SIGIL_TYPIC) && lastSlash > 1) {
                        String pkgPrefix = rawName.substring(0, lastSlash);
                        if (!StvnNamespaceRules.isReservedPackage(pkgPrefix)) {
                            declared.add(pkgPrefix);
                        }
                    }
                } else if (elem.getConstantDefinition() != null && elem.getConstantDefinition().getValueKeyword() != null) {
                    String rawConst = elem.getConstantDefinition().getValueKeyword().getText().trim();
                    int lastSlash = rawConst.lastIndexOf('/');
                    if (rawConst.startsWith(StvnVocabulary.SIGIL_VALUE) && lastSlash > 1) {
                        String pkgPrefix = StvnVocabulary.SIGIL_TYPIC + rawConst.substring(1, lastSlash);
                        if (!StvnNamespaceRules.isReservedPackage(pkgPrefix)) {
                            declared.add(pkgPrefix);
                        }
                    }
                }
            }
            break;
        }
        return declared;
    }

    private static boolean matchesDeclaredOrParentPackage(String target, Set<String> declaredPackages) {
        String prefix = target + "/";
        for (String pkg : declaredPackages) {
            if (pkg.equals(target) || pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quick-fix that removes an invalid {@code :use} directive from a hermetic document.
     */
    public static final class RemoveUseStatementQuickFix implements LocalQuickFix, PriorityAction {
        private final String targetPackage;

        /**
         * Constructs a new {@code :use} removal quick-fix.
         *
         * @param targetPackage unresolved external package target path
         */
        public RemoveUseStatementQuickFix(String targetPackage) {
            this.targetPackage = targetPackage;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @NotNull String getName() {
            return "Remove " + StvnVocabulary.KEYWORD_USE + " directive for '" + targetPackage + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove invalid " + StvnVocabulary.KEYWORD_USE + " directive";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var elem = descriptor.getPsiElement();
            var useStmt = elem instanceof UseStmt u ? u : PsiTreeUtil.getParentOfType(elem, UseStmt.class, false);
            if (useStmt == null || useStmt.getContainingFile() == null) {
                return;
            }
            Document doc = PsiDocumentManager.getInstance(project).getDocument(useStmt.getContainingFile());
            if (doc == null) {
                return;
            }
            int start = useStmt.getTextRange().getStartOffset();
            int end = useStmt.getTextRange().getEndOffset();
            CharSequence chars = doc.getCharsSequence();
            int lineStart = start;
            while (lineStart > 0 && (chars.charAt(lineStart - 1) == ' ' || chars.charAt(lineStart - 1) == '\t')) {
                lineStart--;
            }
            if ((lineStart == 0 || chars.charAt(lineStart - 1) == '\n') && end < chars.length() && chars.charAt(end) == '\n') {
                start = lineStart;
                end = end + 1;
            }
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(doc);
            doc.deleteString(start, end);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
    }
}
