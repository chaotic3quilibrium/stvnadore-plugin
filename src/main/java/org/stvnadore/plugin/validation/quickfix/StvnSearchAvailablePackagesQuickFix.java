package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.validation.quickfix.StvnUsePackageLocatorPopup.PackageCandidateEntry;
import org.stvnadore.psi.UseTarget;

import java.util.List;

/**
 * High-priority interactive quick-fix ({@link Priority#HIGH}) opening {@link StvnUsePackageLocatorPopup}
 * to search, filter, select, auto-{@code :include}, or navigate to {@code :package} declarations across
 * Prelude, local {@code :defs}, included modules, and workspace {@code .stvn_d}/{@code .stvn_di} schemas.
 */
@NullMarked
public final class StvnSearchAvailablePackagesQuickFix
        extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    @SafeFieldForPreview
    private final String targetPackage;

    /**
     * Constructs an interactive package search quick-fix anchored to {@code useTarget}.
     *
     * @param useTarget     unresolved {@link UseTarget} PSI element
     * @param targetPackage unresolved package path text
     */
    public StvnSearchAvailablePackagesQuickFix(UseTarget useTarget, String targetPackage) {
        super(useTarget);
        this.targetPackage = targetPackage;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.HIGH;
    }

    @Override
    public @NotNull String getText() {
        return "Search available packages for '" + targetPackage + "'...";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Search available packages for " + StvnVocabulary.KEYWORD_USE;
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public @Nullable PsiElement getElementToMakeWritable(@NotNull PsiFile currentFile) {
        return currentFile;
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid()) {
            return false;
        }
        String name = file.getOriginalFile().getName();
        return !name.endsWith(".stvn_b") && !name.endsWith(".stvn_bd");
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        return buildHtmlPreview(project, file.getOriginalFile());
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement elem = previewDescriptor.getPsiElement();
        PsiFile file = elem != null ? elem.getContainingFile() : null;
        return file != null ? buildHtmlPreview(project, file.getOriginalFile()) : IntentionPreviewInfo.EMPTY;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        UseTarget useTarget = startElement instanceof UseTarget ut
            ? ut
            : PsiTreeUtil.getParentOfType(startElement, UseTarget.class, false);
        if (useTarget == null) {
            return;
        }
        StvnUsePackageLocatorPopup.showPopup(project, editor, file, useTarget, targetPackage);
    }

    private IntentionPreviewInfo buildHtmlPreview(Project project, PsiFile hostFile) {
        List<PackageCandidateEntry> candidates =
            StvnUsePackageLocatorPopup.collectCandidates(project, hostFile, targetPackage);
        StringBuilder html = new StringBuilder();
        html.append("<p>Opens an interactive locator indexing <b>")
            .append(candidates.size())
            .append("</b> available ")
            .append("<code>").append(escapeHtml(StvnVocabulary.KEYWORD_PACKAGE)).append("</code>")
            .append(" targets for <code>").append(escapeHtml(targetPackage)).append("</code>:</p><ul>");
        int limit = Math.min(5, candidates.size());
        for (int i = 0; i < limit; i++) {
            PackageCandidateEntry c = candidates.get(i);
            html.append("<li><code>").append(escapeHtml(c.packagePath())).append("</code> ")
                .append(escapeHtml(c.originLabel())).append("</li>");
        }
        if (candidates.size() > limit) {
            html.append("<li><i>+").append(candidates.size() - limit).append(" additional packages...</i></li>");
        }
        html.append("</ul><p>Press <b>Enter</b> to select or <b>F4</b> to navigate to declaration.</p>");
        return new IntentionPreviewInfo.Html(html.toString());
    }

    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
