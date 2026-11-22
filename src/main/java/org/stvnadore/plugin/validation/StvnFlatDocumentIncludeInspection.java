package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.plugin.psi.StvnNamespaceRules;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.psi.StvnPsiUtils;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnConvertToModularQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveUnresolvedUsePackageQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnUnresolvedUsePackageQuickFixProvider;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.DefsElement;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.IncludeElement;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.UseElement;
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
                PsiFile file = stmt.getContainingFile();
                if (file != null && prohibitsIncludeStatements(file)) {
                    List<LocalQuickFix> fixes = new ArrayList<>(2);
                    fixes.add(new RemoveIncludeStatementQuickFix(extractSingleIncludeTargetPath(stmt)));
                    if (StvnConvertToModularQuickFix.canConvertToModular(file)) {
                        fixes.add(new StvnConvertToModularQuickFix(stmt, file.getName().endsWith(".stvn_d")));
                    }
                    holder.registerProblem(
                        stmt,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Hermetic document or schema (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot contain include statements: "
                                + file.getName(),
                            StvnDiagnosticCodes.ERR_INCLUDES_PROHIBITED_IN_FLAT_DOCUMENT
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                }
            }

            @Override
            public void visitUseElement(@NotNull UseElement element) {
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(element)) {
                    return;
                }
                PsiFile file = element.getContainingFile();
                if (file == null || StvnPreludeBridge.isPreludeFile(file) || !isHermeticCompilationUnit(file)) {
                    return;
                }
                UseTarget useTarget = element.getUseTarget();
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

                List<LocalQuickFix> fixes = StvnUnresolvedUsePackageQuickFixProvider.createLocalQuickFixes(
                    file,
                    useTarget,
                    target
                );
                holder.registerProblem(
                    useTarget,
                    StvnDiagnosticFormatter.formatPlugin(
                        "Scoped " + StvnVocabulary.KEYWORD_USE
                            + " in hermetic document (.stvn, .stvn_d, .stvn_b, .stvn_bd) cannot target external package: "
                            + target,
                        StvnDiagnosticCodes.ERR_UNRESOLVED_PACKAGE_IN_HERMETIC_DOCUMENT
                    ),
                    ProblemHighlightType.GENERIC_ERROR,
                    fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                );
            }
        };
    }

    /**
     * Determines whether {@code file} prohibits {@code :include} statements ({@code E005}),
     * matching {@code StvnDefsLowerer.prohibitsIncludeStatements()} 1:1 across physical hermetic
     * extensions ({@code .stvn}, {@code .stvn_d}, {@code .stvn_b}, {@code .stvn_bd}) and explicit
     * {@code #kind #BODY} or {@code #kind #DEFS} headers.
     *
     * @param file target PSI file, or {@code null}
     * @return {@code true} when {@code :include} statements are prohibited in {@code file}
     */
    public static boolean prohibitsIncludeStatements(@Nullable PsiFile file) {
        if (file == null || StvnPreludeBridge.isPreludeFile(file)) {
            return false;
        }
        String name = file.getName();
        if (StvnPsiUtils.isHermeticDocument(file)
            || name.endsWith(".stvn_b")
            || name.endsWith(".stvn_bd")) {
            return true;
        }
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
            }
            break;
        }
        return false;
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
                    String pkgPath = StvnTrailingSlashInspection.stripTrailingSlashes(
                        StvnTrailingSlashInspection.extractNonCommentText(elem.getPackageEnclosure().getPackagePath())
                    );
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

    private static @Nullable String extractSingleIncludeTargetPath(IncludeStmt stmt) {
        List<IncludeElement> elements = stmt.getIncludeElementList();
        if (elements.size() != 1) {
            return null;
        }
        StringLiteral literal = elements.get(0).getStringLiteral();
        if (literal == null) {
            return null;
        }
        String raw = literal.getText().trim();
        if (!raw.startsWith("\"\"\"")) {
            var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(raw, true);
            if (decodedOpt.isEmpty()) {
                return null;
            }
            String unquoted = decodedOpt.get().trim();
            if (!unquoted.isEmpty() && unquoted.indexOf('\n') < 0 && unquoted.indexOf('\r') < 0) {
                return unquoted;
            }
        }
        return null;
    }

    private static boolean isDefsSchemaDocument(PsiFile file) {
        for (MetaEntry metaEntry : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (StvnMetaTemplateUtil.isInSecondaryRootSection(metaEntry)) {
                continue;
            }
            MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
            if (kindFacet != null && kindFacet.getMetaKindValue() != null) {
                String kindText = kindFacet.getMetaKindValue().getText().trim();
                if (StvnVocabulary.KIND_KW_DEFS.equals(kindText)
                    || StvnVocabulary.KIND_KW_DEFS_INCLUDE.equals(kindText)) {
                    return true;
                }
                if (StvnVocabulary.KIND_KW_BODY.equals(kindText)
                    || StvnVocabulary.KIND_KW_BODY_INCLUDE.equals(kindText)) {
                    return false;
                }
            }
            break;
        }
        String fileName = file.getName();
        if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_di") || fileName.endsWith(".stvn_bd")) {
            return true;
        }
        if (fileName.endsWith(".stvn") || fileName.endsWith(".stvn_i") || fileName.endsWith(".stvn_b")) {
            return false;
        }
        return PsiTreeUtil.findChildOfType(file, TypeEntry.class) == null
            && PsiTreeUtil.findChildOfType(file, BodyEntry.class) == null;
    }

    private static boolean hasNonDirectiveContentInDefs(DefsEntry defsEntry, DefsElement targetElement) {
        ASTNode lBrace = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
        ASTNode rBrace = defsEntry.getNode().findChildByType(StvnTypes.RBRACE);
        if (lBrace == null || rBrace == null) {
            return true;
        }
        for (PsiElement child = lBrace.getPsi().getNextSibling();
             child != null && child != rBrace.getPsi();
             child = child.getNextSibling()) {
            if (child == targetElement || child instanceof PsiWhiteSpace) {
                continue;
            }
            return true;
        }
        if (PsiTreeUtil.findChildOfType(targetElement, PsiComment.class) != null) {
            return true;
        }
        return false;
    }

    /**
     * Removes a top-level or package-nested directive statement ({@code :include} or {@code :use})
     * while preserving adjacent {@code //} comments and omitting comment-free empty {@code :defs}
     * sections in payload documents.
     *
     * @param project       active project
     * @param directiveStmt target directive PSI element
     */
    public static void removeDirectiveFromDefs(Project project, PsiElement directiveStmt) {
        PsiFile file = directiveStmt.getContainingFile();
        if (file == null) {
            return;
        }
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            doc = PsiDocumentManager.getInstance(project).getDocument(file);
        }
        if (doc == null) {
            return;
        }
        if (file.isPhysical()) {
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(doc);
        }

        CharSequence chars = doc.getCharsSequence();
        DefsElement parentDefsElem = directiveStmt.getParent() instanceof DefsElement de ? de : null;
        DefsEntry defsEntry = parentDefsElem != null && parentDefsElem.getParent() instanceof DefsEntry entry
            ? entry
            : null;

        if (defsEntry != null
            && defsEntry.getDefsElementList().size() == 1
            && !hasNonDirectiveContentInDefs(defsEntry, parentDefsElem)) {
            if (isDefsSchemaDocument(file)) {
                ASTNode lBrace = defsEntry.getNode().findChildByType(StvnTypes.LBRACE);
                ASTNode rBrace = defsEntry.getNode().findChildByType(StvnTypes.RBRACE);
                if (lBrace != null && rBrace != null) {
                    int defsStart = defsEntry.getTextRange().getStartOffset();
                    int defsLineStart = defsStart;
                    while (defsLineStart > 0
                        && (chars.charAt(defsLineStart - 1) == ' ' || chars.charAt(defsLineStart - 1) == '\t')) {
                        defsLineStart--;
                    }
                    String defsIndent = (defsLineStart == 0 || chars.charAt(defsLineStart - 1) == '\n')
                        ? chars.subSequence(defsLineStart, defsStart).toString()
                        : "  ";
                    doc.replaceString(lBrace.getStartOffset() + 1, rBrace.getStartOffset(), "\n" + defsIndent);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                    return;
                }
            } else {
                int defsStart = defsEntry.getTextRange().getStartOffset();
                int defsEnd = defsEntry.getTextRange().getEndOffset();
                int defsWsEnd = defsEnd;
                while (defsWsEnd < chars.length()
                    && (chars.charAt(defsWsEnd) == ' ' || chars.charAt(defsWsEnd) == '\t')) {
                    defsWsEnd++;
                }
                boolean hasSameLineTrailingComment = defsWsEnd + 1 < chars.length()
                    && chars.charAt(defsWsEnd) == '/'
                    && chars.charAt(defsWsEnd + 1) == '/';
                if (!hasSameLineTrailingComment) {
                    int lineStart = defsStart;
                    while (lineStart > 0
                        && (chars.charAt(lineStart - 1) == ' ' || chars.charAt(lineStart - 1) == '\t')) {
                        lineStart--;
                    }
                    int deleteStart = (lineStart == 0 || chars.charAt(lineStart - 1) == '\n') ? lineStart : defsStart;
                    int deleteEnd = advancePastSingleNewline(chars, defsWsEnd, defsEnd);
                    if (isPrecededByBlankLine(chars, deleteStart)
                        && deleteEnd < chars.length()
                        && (chars.charAt(deleteEnd) == '\n' || chars.charAt(deleteEnd) == '\r')) {
                        deleteEnd = advancePastSingleNewline(chars, deleteEnd, deleteEnd);
                    }
                    doc.deleteString(deleteStart, deleteEnd);
                    PsiDocumentManager.getInstance(project).commitDocument(doc);
                    return;
                }
            }
        }

        int start = directiveStmt.getTextRange().getStartOffset();
        int end = directiveStmt.getTextRange().getEndOffset();
        int lineStart = start;
        while (lineStart > 0 && (chars.charAt(lineStart - 1) == ' ' || chars.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        int wsEnd = end;
        while (wsEnd < chars.length() && (chars.charAt(wsEnd) == ' ' || chars.charAt(wsEnd) == '\t')) {
            wsEnd++;
        }
        boolean ownsLinePrefix = lineStart == 0 || chars.charAt(lineStart - 1) == '\n';
        if (ownsLinePrefix && (wsEnd == chars.length() || chars.charAt(wsEnd) == '\n' || chars.charAt(wsEnd) == '\r')) {
            int deleteEnd = advancePastSingleNewline(chars, wsEnd, wsEnd);
            doc.deleteString(lineStart, deleteEnd);
        } else if (ownsLinePrefix && wsEnd + 1 < chars.length()
            && chars.charAt(wsEnd) == '/' && chars.charAt(wsEnd + 1) == '/') {
            doc.deleteString(start, wsEnd);
        } else {
            doc.deleteString(start, Math.max(end, wsEnd));
        }
        PsiDocumentManager.getInstance(project).commitDocument(doc);
    }

    private static boolean isPrecededByBlankLine(CharSequence chars, int lineStart) {
        if (lineStart <= 0 || chars.charAt(lineStart - 1) != '\n') {
            return false;
        }
        int prevEnd = lineStart - 1;
        if (prevEnd > 0 && chars.charAt(prevEnd - 1) == '\r') {
            prevEnd--;
        }
        while (prevEnd > 0 && (chars.charAt(prevEnd - 1) == ' ' || chars.charAt(prevEnd - 1) == '\t')) {
            prevEnd--;
        }
        return prevEnd > 0 && chars.charAt(prevEnd - 1) == '\n';
    }

    private static int advancePastSingleNewline(CharSequence chars, int wsEnd, int fallbackEnd) {
        if (wsEnd < chars.length()) {
            if (chars.charAt(wsEnd) == '\r') {
                return (wsEnd + 1 < chars.length() && chars.charAt(wsEnd + 1) == '\n') ? wsEnd + 2 : wsEnd + 1;
            }
            if (chars.charAt(wsEnd) == '\n') {
                return wsEnd + 1;
            }
        }
        return fallbackEnd;
    }

    /**
     * Quick-fix that removes a prohibited {@code :include} directive from a hermetic document or schema.
     */
    public static final class RemoveIncludeStatementQuickFix implements LocalQuickFix, PriorityAction {
        @SafeFieldForPreview
        private final @Nullable String singleTargetPath;

        /**
         * Constructs a new {@code :include} removal quick-fix.
         *
         * @param singleTargetPath single quoted include target path when unambiguous, or {@code null}
         */
        public RemoveIncludeStatementQuickFix(@Nullable String singleTargetPath) {
            this.singleTargetPath = singleTargetPath;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @NotNull String getName() {
            if (singleTargetPath != null && !singleTargetPath.isEmpty()) {
                return "Remove " + StvnVocabulary.KEYWORD_INCLUDE + " directive for '" + singleTargetPath + "'";
            }
            return "Remove " + StvnVocabulary.KEYWORD_INCLUDE + " directive";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove invalid " + StvnVocabulary.KEYWORD_INCLUDE + " directive";
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            applyFix(project, previewDescriptor);
            return IntentionPreviewInfo.DIFF;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            IncludeStmt includeStmt = elem instanceof IncludeStmt inc
                ? inc
                : PsiTreeUtil.getParentOfType(elem, IncludeStmt.class, false);
            if (includeStmt == null) {
                return;
            }
            removeDirectiveFromDefs(project, includeStmt);
        }
    }

    /**
     * Quick-fix that removes an unresolved package entry from a {@code :use} directive in a hermetic document.
     */
    public static final class RemoveUseStatementQuickFix implements LocalQuickFix, PriorityAction {
        @SafeFieldForPreview
        private final String targetPackage;

        /**
         * Constructs a new {@code :use} package removal quick-fix.
         *
         * @param targetPackage unresolved external package target path
         */
        public RemoveUseStatementQuickFix(String targetPackage) {
            this.targetPackage = targetPackage;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.NORMAL;
        }

        @Override
        public @NotNull String getName() {
            return "Remove unresolved package '" + targetPackage + "' from " + StvnVocabulary.KEYWORD_USE;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove unresolved package from " + StvnVocabulary.KEYWORD_USE;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            applyFix(project, previewDescriptor);
            return IntentionPreviewInfo.DIFF;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var elem = descriptor.getPsiElement();
            if (elem != null) {
                StvnRemoveUnresolvedUsePackageQuickFix.removeTargetFromUse(project, elem);
            }
        }
    }
}
