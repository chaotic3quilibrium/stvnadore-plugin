package org.stvnadore.plugin.navigation;

import com.intellij.codeInsight.TargetElementEvaluatorEx2;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnMetaReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.ValueKeyword;

/**
 * Teaches IntelliJ Platform's TargetElementUtil to accept STVN TypeKeyword, ValueKeyword,
 * and root metadata facets as valid targets for mouse hover documentation and reference hyperlinks.
 */
@NullMarked
public final class StvnTargetElementEvaluator extends TargetElementEvaluatorEx2 {

    public @Nullable PsiElement getTargetElement(@NotNull Editor editor, int offset) {
        var project = editor.getProject();
        if (project == null) return null;
        var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (file == null) return null;
        var element = file.findElementAt(offset);
        if (element == null) return null;

        // 1. MetaKindValue (#DEFS, #BODY, etc.)
        var kindVal = PsiTreeUtil.getParentOfType(element, MetaKindValue.class, false);
        if (kindVal != null) {
            return StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeEnumVariant(file, ":org/stvnadore/prelude/meta/StvnDocumentKind", kindVal.getText())
                : StvnPreludeBridge.resolvePreludeEnumVariant(project, ":org/stvnadore/prelude/meta/StvnDocumentKind", kindVal.getText());
        }

        // 2. MetaKindFacet (#kind)
        var kindFacet = PsiTreeUtil.getParentOfType(element, MetaKindFacet.class, false);
        if (kindFacet != null) {
            return StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeType(file, ":org/stvnadore/prelude/meta/StvnDocumentKind")
                : StvnPreludeBridge.resolvePreludeType(project, ":org/stvnadore/prelude/meta/StvnDocumentKind");
        }

        // 3. MetaNameFacet (#name)
        var nameFacet = PsiTreeUtil.getParentOfType(element, MetaNameFacet.class, false);
        if (nameFacet != null) {
            return StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeType(file, ":org/stvnadore/prelude/meta/StvnPosixId64")
                : StvnPreludeBridge.resolvePreludeType(project, ":org/stvnadore/prelude/meta/StvnPosixId64");
        }

        // 4. MetaDomainFacet (#domain)
        var domainFacet = PsiTreeUtil.getParentOfType(element, MetaDomainFacet.class, false);
        if (domainFacet != null) {
            return StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeType(file, ":org/stvnadore/prelude/meta/StvnPosixId64")
                : StvnPreludeBridge.resolvePreludeType(project, ":org/stvnadore/prelude/meta/StvnPosixId64");
        }

        return null;
    }

    @Override
    public @Nullable PsiElement adjustReferenceOrReferencedElement(
            @NotNull PsiFile file,
            @NotNull Editor editor,
            int offset,
            int flags,
            @Nullable PsiElement refElement
    ) {
        if (refElement != null) {
            return refElement;
        }
        return getTargetElement(editor, offset);
    }

    @Override
    public @Nullable PsiElement adjustElement(
            @NotNull Editor editor,
            int flags,
            @Nullable PsiElement element,
            @Nullable PsiElement contextElement
    ) {
        if (element != null) {
            return element;
        }
        if (contextElement != null) {
            return contextElement;
        }
        return getTargetElement(editor, editor.getCaretModel().getOffset());
    }

    @Override
    public @Nullable PsiElement getElementByReference(@NotNull PsiReference ref, int flags) {
        if (ref instanceof StvnMetaReference metaRef) {
            return metaRef.resolve();
        }
        return null;
    }

    @Override
    public boolean isAcceptableNamedParent(@NotNull PsiElement parent) {
        return parent instanceof TypeKeyword
            || parent instanceof ValueKeyword
            || parent instanceof TypeDefinition;
    }

    @Override
    public @Nullable PsiElement getNamedElement(@NotNull PsiElement element) {
        if (element instanceof TypeKeyword || element instanceof ValueKeyword) {
            return element;
        }
        return null;
    }
}
