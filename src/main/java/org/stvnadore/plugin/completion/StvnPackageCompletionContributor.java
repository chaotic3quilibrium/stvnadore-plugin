package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.core.validation.StvnTypeResolver;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.psi.PackageEnclosure;
import org.stvnadore.psi.PackagePath;
import org.stvnadore.psi.UseStmt;
import org.stvnadore.psi.UseTarget;

import java.util.HashSet;
import java.util.Set;

/**
 * Code completion contributor for package paths (:package :<caret>) and module imports (:use [ :<caret> ]).
 * <p>
 * Enforces STVN Specification § 3.9 Invariant 8:
 * 1. Proactively suppresses reserved package namespace ':org/stvnadore/*' in ':package' declarations.
 * 2. Explicitly proposes standard library prelude packages in ':use' directives.
 */
@NullMarked
public final class StvnPackageCompletionContributor extends CompletionContributor {

    public StvnPackageCompletionContributor() {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(StvnLanguage.INSTANCE),
            new CompletionProvider<>() {
                @Override
                protected void addCompletions(
                    @NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result
                ) {
                    var position = parameters.getPosition();
                    var file = parameters.getOriginalFile();
                    var project = file.getProject();

                    var packagePath = PsiTreeUtil.getParentOfType(position, PackagePath.class);
                    var useTarget = PsiTreeUtil.getParentOfType(position, UseTarget.class);
                    boolean isPackageContext = packagePath != null || isCaretDirectlyAfterPackageKeyword(position);
                    boolean isUseContext = useTarget != null || isCaretDirectlyInUseStatement(position);

                    if (!isPackageContext && !isUseContext) {
                        return;
                    }

                    var prefix = result.getPrefixMatcher().getPrefix();
                    var matcher = result.getPrefixMatcher();
                    if (!prefix.isEmpty() && !prefix.startsWith(StvnVocabulary.SIGIL_TYPIC)) {
                        matcher = matcher.cloneWithPrefix(StvnVocabulary.SIGIL_TYPIC + prefix);
                    }
                    var targetResult = result.withPrefixMatcher(matcher);

                    // Caret in :package :<caret>
                    if (isPackageContext) {
                        var candidatePackages = collectWorkspacePackages(project);
                        for (var pkg : candidatePackages) {
                            // Rule E037: Strictly suppress reserved packages from package declarations
                            if (!StvnTypeResolver.isReservedPackage(pkg)) {
                                targetResult.addElement(LookupElementBuilder.create(pkg)
                                    .withIcon(AllIcons.Nodes.Package)
                                    .withTypeText("Package Enclosure", true));
                            }
                        }
                        return;
                    }

                    // Caret in :use [ :<caret> ]
                    if (isUseContext) {
                        // Propose Standard Library Prelude packages (permitted under Invariant 7 and 8)
                        targetResult.addElement(LookupElementBuilder.create(":org/stvnadore/prelude")
                            .withIcon(AllIcons.Nodes.PpLib)
                            .withTypeText("Standard Library Prelude", true));
                        targetResult.addElement(LookupElementBuilder.create(":org/stvnadore/prelude/meta")
                            .withIcon(AllIcons.Nodes.PpLib)
                            .withTypeText("Standard Library Metadata", true));

                        // Propose workspace user packages
                        var candidatePackages = collectWorkspacePackages(project);
                        for (var pkg : candidatePackages) {
                            if (!StvnTypeResolver.isReservedPackage(pkg)) {
                                targetResult.addElement(LookupElementBuilder.create(pkg)
                                    .withIcon(AllIcons.Nodes.Package)
                                    .withTypeText("Import Target", true));
                            }
                        }
                    }
                }
            }
        );
    }

    private static boolean isCaretDirectlyAfterPackageKeyword(PsiElement position) {
        var prev = PsiTreeUtil.prevVisibleLeaf(position);
        if (prev != null && ":package".equals(prev.getText())) {
            return true;
        }
        var pkgEnc = PsiTreeUtil.getParentOfType(position, PackageEnclosure.class);
        if (pkgEnc != null) {
            var openBrace = pkgEnc.getNode().findChildByType(org.stvnadore.psi.StvnTypes.LBRACE);
            if (openBrace == null || position.getTextOffset() < openBrace.getStartOffset()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCaretDirectlyInUseStatement(PsiElement elem) {
        var useStmt = PsiTreeUtil.getParentOfType(elem, UseStmt.class);
        if (useStmt == null) {
            var prev = PsiTreeUtil.prevVisibleLeaf(elem);
            if (prev != null && (":use".equals(prev.getText()) || "[".equals(prev.getText()))) {
                var grandPrev = PsiTreeUtil.prevVisibleLeaf(prev);
                return (":use".equals(prev.getText())) || (grandPrev != null && ":use".equals(grandPrev.getText()));
            }
            return false;
        }
        var rbrack = useStmt.getNode().findChildByType(org.stvnadore.psi.StvnTypes.RBRACK);
        return rbrack == null || elem.getTextOffset() < rbrack.getStartOffset();
    }

    private static Set<String> collectWorkspacePackages(com.intellij.openapi.project.Project project) {
        var packages = new HashSet<String>();
        var scope = GlobalSearchScope.projectScope(project);
        var psiManager = com.intellij.psi.PsiManager.getInstance(project);

        var virtualFiles = new java.util.ArrayList<com.intellij.openapi.vfs.VirtualFile>();
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Defs.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.DefsInclude.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.PayloadInclude.INSTANCE, scope));
        virtualFiles.addAll(FileTypeIndex.getFiles(StvnFileType.Payload.INSTANCE, scope));

        for (var vf : virtualFiles) {
            var psiFile = psiManager.findFile(vf);
            if (psiFile == null) continue;
            for (var pkg : PsiTreeUtil.findChildrenOfType(psiFile, PackageEnclosure.class)) {
                var path = pkg.getPackagePath();
                if (path != null && path.getTypeKeyword() != null) {
                    packages.add(path.getTypeKeyword().getText());
                }
            }
        }
        return packages;
    }
}
