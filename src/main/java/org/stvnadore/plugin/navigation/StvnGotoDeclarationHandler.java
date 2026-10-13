package org.stvnadore.plugin.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnConstantReference;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.ValueKeyword;

/**
 * Custom Goto Declaration handler enabling direct navigation from STVN root metadata facets
 * (#name, #domain, #kind, and #DEFS) and constant references (#regex #CONST) to their
 * corresponding canonical declarations in the standard library prelude or local schemas.
 */
@NullMarked
public final class StvnGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) return null;

        var file = sourceElement.getContainingFile();
        if (file == null) return null;

        // 1. MetaKindValue (#DEFS, #BODY, etc.)
        var kindVal = PsiTreeUtil.getParentOfType(sourceElement, MetaKindValue.class, false);
        if (kindVal != null) {
            var variantName = kindVal.getText();
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeEnumVariant(file, ":org/stvnadore/prelude/meta/StvnDocumentKind", variantName)
                : StvnPreludeBridge.resolvePreludeEnumVariant(file.getProject(), ":org/stvnadore/prelude/meta/StvnDocumentKind", variantName);
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 2. MetaKindFacet (#kind)
        var kindFacet = PsiTreeUtil.getParentOfType(sourceElement, MetaKindFacet.class, false);
        if (kindFacet != null && sourceElement.getText().equals("#kind")) {
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeTypeDefinition(file, ":org/stvnadore/prelude/meta/StvnDocumentKind")
                : StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), ":org/stvnadore/prelude/meta/StvnDocumentKind");
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 3. MetaNameFacet (#name)
        var nameFacet = PsiTreeUtil.getParentOfType(sourceElement, MetaNameFacet.class, false);
        if (nameFacet != null && sourceElement.getText().equals("#name")) {
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeTypeDefinition(file, ":org/stvnadore/prelude/meta/StvnPosixId64")
                : StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), ":org/stvnadore/prelude/meta/StvnPosixId64");
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 4. MetaDomainFacet (#domain)
        var domainFacet = PsiTreeUtil.getParentOfType(sourceElement, MetaDomainFacet.class, false);
        if (domainFacet != null && sourceElement.getText().equals("#domain")) {
            var target = StvnPreludeBridge.isPreludeFile(file)
                ? StvnPreludeBridge.resolvePreludeTypeDefinition(file, ":org/stvnadore/prelude/meta/StvnPosixId64")
                : StvnPreludeBridge.resolvePreludeTypeDefinition(file.getProject(), ":org/stvnadore/prelude/meta/StvnPosixId64");
            return target != null ? new PsiElement[]{ target } : null;
        }

        // 5. ValueKeyword (constant references in metadata facets, body entries, or schemas)
        var valKw = PsiTreeUtil.getParentOfType(sourceElement, ValueKeyword.class, false);
        if (valKw != null) {
            if (valKw.getParent() instanceof ConstantDefinition constDef && constDef.getValueKeyword() == valKw) {
                return null;
            }
            if (valKw.getParent() instanceof org.stvnadore.psi.EnumDef) {
                return null;
            }
            var ref = valKw.getReference();
            var resolved = ref != null ? ref.resolve() : new StvnConstantReference(valKw).resolve();
            if (resolved != null) {
                var target = resolved.getParent() instanceof ConstantDefinition cDef ? cDef : resolved;
                return new PsiElement[]{ target };
            }
        }

        return null;
    }

    public @Nullable TextRange getGotoDeclarationRange(@Nullable PsiElement sourceElement, int offset, @Nullable Editor editor) {
        if (sourceElement == null) return null;
        var kindVal = PsiTreeUtil.getParentOfType(sourceElement, MetaKindValue.class, false);
        if (kindVal != null) {
            return kindVal.getTextRange();
        }
        var text = sourceElement.getText();
        if (text.equals("#kind") || text.equals("#name") || text.equals("#domain")) {
            return sourceElement.getTextRange();
        }
        var valKw = PsiTreeUtil.getParentOfType(sourceElement, ValueKeyword.class, false);
        if (valKw != null) {
            return valKw.getTextRange();
        }
        return sourceElement.getTextRange();
    }
}
