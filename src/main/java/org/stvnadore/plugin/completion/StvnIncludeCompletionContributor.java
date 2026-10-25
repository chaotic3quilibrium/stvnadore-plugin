package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.icons.StvnIcons;
import org.stvnadore.plugin.reference.StvnIncludeFileReferenceSet;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

import java.util.HashSet;
import java.util.Set;

/**
 * Completion contributor for bare-bracket include statements (:include [ <caret> ]).
 * <p>
 * Proposes relative schema files (.stvn_d, .stvn_di, .stvn_bd) and child directories
 * anchored to the active containing file's directory. Employs StvnIncludeQuoteInsertHandler
 * to wrap selected candidates automatically in double quotation marks.
 */
@NullMarked
public final class StvnIncludeCompletionContributor extends CompletionContributor {

    public StvnIncludeCompletionContributor() {
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

                    // If caret is already inside quotes, FileReferenceSet handles completion
                    if (PsiTreeUtil.getParentOfType(position, StringLiteral.class) != null) {
                        return;
                    }

                    int offset = parameters.getOffset();
                    var includeStmt = PsiTreeUtil.getParentOfType(position, IncludeStmt.class);
                    if (!isBareIncludeContext(position, includeStmt, offset)) {
                        return;
                    }

                    var containingFile = parameters.getOriginalFile();
                    var virtualFile = containingFile.getVirtualFile();
                    if (virtualFile == null) {
                        virtualFile = containingFile.getViewProvider().getVirtualFile();
                    }
                    if (virtualFile == null) {
                        return;
                    }

                    var parentDir = virtualFile.getParent();
                    if (parentDir == null) {
                        return;
                    }

                    Set<String> alreadyIncluded = new HashSet<>();
                    if (includeStmt != null) {
                        for (var incElem : includeStmt.getIncludeElementList()) {
                            var str = incElem.getStringLiteral();
                            if (str != null) {
                                var clean = str.getText().replace("\"", "").trim();
                                alreadyIncluded.add(clean);
                            }
                        }
                    }

                    var psiManager = PsiManager.getInstance(containingFile.getProject());

                    // Scan sibling directory contents
                    for (var child : parentDir.getChildren()) {
                        if (child.isDirectory()) {
                            if (!StvnIncludeFileReferenceSet.isIgnoredDirectory(child.getName())) {
                                var dirName = child.getName() + "/";
                                result.addElement(LookupElementBuilder.create(dirName)
                                    .withIcon(AllIcons.Nodes.Folder)
                                    .withTypeText("Directory", true)
                                    .withInsertHandler(StvnIncludeQuoteInsertHandler.DIRECTORY_INSTANCE));
                            }
                        } else {
                            // Self-exclusion invariant: active file itself must be excluded
                            if (child.equals(virtualFile)) {
                                continue;
                            }
                            var fileName = child.getName();
                            if (alreadyIncluded.contains(fileName)) {
                                continue;
                            }
                            if (StvnIncludeFileReferenceSet.isAllowedSchemaFile(fileName)) {
                                var psiChild = psiManager.findFile(child);
                                var icon = psiChild != null ? psiChild.getIcon(0) : null;
                                if (icon == null) {
                                    icon = fileName.endsWith(".stvn_di")
                                        ? StvnIcons.STVN_DI
                                        : (fileName.endsWith(".stvn_d") ? StvnIcons.STVN_D : StvnIcons.STVN_BD);
                                }
                                var typeText = fileName.endsWith(".stvn_di")
                                    ? "Modular Schema Module"
                                    : (fileName.endsWith(".stvn_d") ? "Hermetic Schema" : "Binary Schema");

                                result.addElement(LookupElementBuilder.create(fileName)
                                    .withIcon(icon)
                                    .withTypeText(typeText, true)
                                    .withInsertHandler(StvnIncludeQuoteInsertHandler.FILE_INSTANCE));
                            }
                        }
                    }
                }
            }
        );
    }

    private static boolean isBareIncludeContext(PsiElement position, @com.intellij.openapi.util.NlsSafe com.intellij.psi.PsiElement includeStmtElem, int offset) {
        if (includeStmtElem instanceof IncludeStmt includeStmt) {
            var lbrack = includeStmt.getNode().findChildByType(StvnTypes.LBRACK);
            if (lbrack == null || offset <= lbrack.getStartOffset()) {
                return false;
            }
            var rbrack = includeStmt.getNode().findChildByType(StvnTypes.RBRACK);
            return rbrack == null || offset <= rbrack.getStartOffset();
        }

        // Fallback for broken AST during in-flight typing
        var prev = PsiTreeUtil.prevVisibleLeaf(position);
        if (prev != null && "[".equals(prev.getText())) {
            var grandPrev = PsiTreeUtil.prevVisibleLeaf(prev);
            return grandPrev != null && ":include".equals(grandPrev.getText());
        }

        return false;
    }
}
