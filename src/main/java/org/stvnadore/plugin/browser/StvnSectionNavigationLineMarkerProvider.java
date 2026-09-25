package org.stvnadore.plugin.browser;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiElement;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.icons.StvnIcons;
import org.stvnadore.psi.*;

/**
 * Attaches gutter navigation icons to STVN section headers (:defs, :type, :body).
 */
@NullMarked
public final class StvnSectionNavigationLineMarkerProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(PsiElement element) {
        var node = element.getNode();
        if (node == null) {
            return null;
        }

        var elementType = node.getElementType();
        var parent = element.getParent();

        if (elementType == StvnTypes.KW_DEFS && parent instanceof DefsEntry) {
            return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                StvnIcons.FILE,
                elt -> "Browse :defs namespace dependencies",
                (e, elt) -> StvnNamespaceBrowserPopup.showPopup(elt.getProject(), elt.getContainingFile(), StvnNamespaceScope.DEFS),
                GutterIconRenderer.Alignment.RIGHT,
                () -> "STVN Section Navigation (:defs)"
            );
        }

        if (elementType == StvnTypes.KW_TYPE && parent instanceof TypeEntry) {
            return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                StvnIcons.FILE,
                elt -> "Browse :type schema contract dependencies",
                (e, elt) -> StvnNamespaceBrowserPopup.showPopup(elt.getProject(), elt.getContainingFile(), StvnNamespaceScope.TYPE),
                GutterIconRenderer.Alignment.RIGHT,
                () -> "STVN Section Navigation (:type)"
            );
        }

        if (elementType == StvnTypes.KW_BODY && parent instanceof BodyEntry) {
            return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                StvnIcons.FILE,
                elt -> "Browse :body payload dependencies",
                (e, elt) -> StvnNamespaceBrowserPopup.showPopup(elt.getProject(), elt.getContainingFile(), StvnNamespaceScope.BODY),
                GutterIconRenderer.Alignment.RIGHT,
                () -> "STVN Section Navigation (:body)"
            );
        }

        return null;
    }
}
