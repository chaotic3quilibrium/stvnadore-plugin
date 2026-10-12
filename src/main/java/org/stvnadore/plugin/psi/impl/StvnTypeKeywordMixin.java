package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;
import org.stvnadore.plugin.navigation.StvnNavigationUtil;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.plugin.reference.StvnTypeReference;

@NullMarked
public abstract class StvnTypeKeywordMixin extends ASTWrapperPsiElement implements TypeKeyword, PsiNameIdentifierOwner, Navigatable {

    public StvnTypeKeywordMixin(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public @NotNull PsiElement getNameIdentifier() {
        return this;
    }

    @Override
    public int getTextOffset() {
        return getTextRange().getStartOffset();
    }

    @Override
    public @Nullable String getName() {
        var text = getText();
        return text.startsWith(":") ? text.substring(1) : text;
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        if (name.startsWith(":") || name.startsWith("#")) {
            throw new IncorrectOperationException("Identifier must be a bare name without ':' or '#' prefix: " + name);
        }
        var newKw = org.stvnadore.plugin.psi.StvnElementFactory.createTypeKeyword(getProject(), ":" + name);
        return replace(newKw);
    }

    @Override
    public @Nullable ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public @Nullable String getPresentableText() {
                var name = getName();
                return name != null && !name.isEmpty() ? ":" + name : null;
            }

            @Override
            public @Nullable String getLocationString() {
                var file = getContainingFile();
                return file != null ? file.getName() : null;
            }

            @Override
            public @Nullable javax.swing.Icon getIcon(boolean unused) {
                return StvnIcons.STVN;
            }
        };
    }

    @Override
    public boolean canNavigate() {
        return StvnNavigationUtil.canNavigate(this);
    }

    @Override
    public boolean canNavigateToSource() {
        return StvnNavigationUtil.canNavigateToSource(this);
    }

    @Override
    public void navigate(boolean requestFocus) {
        StvnNavigationUtil.navigate(this, requestFocus);
    }

    @Override
    public @NotNull SearchScope getUseScope() {
        var file = getContainingFile();
        if (file != null && (org.stvnadore.plugin.reference.StvnPreludeBridge.isPreludeFile(file) || !file.isPhysical())) {
            return GlobalSearchScope.allScope(getProject());
        }
        return super.getUseScope();
    }

    @Override
    public @Nullable PsiReference getReference() {
        var references = getReferences();
        return references.length > 0 ? references[0] : null;
    }

    @Override
    public PsiReference @NotNull [] getReferences() {
        var parent = getParent();
        if (org.stvnadore.plugin.psi.StvnPsiUtils.isTypeDefinitionTarget(this)) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (parent instanceof org.stvnadore.psi.PackagePath) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (parent instanceof IncludeMapAlias alias) {
            var list = alias.getTypeKeywordList();
            if (list.size() >= 2 && list.get(1) == this) {
                return PsiReference.EMPTY_ARRAY;
            }
        }
        if (parent instanceof org.stvnadore.psi.UseMapAlias alias) {
            var list = alias.getTypeKeywordList();
            if (list.size() >= 2 && list.get(1) == this) {
                return PsiReference.EMPTY_ARRAY;
            }
        }
        return new PsiReference[]{new StvnTypeReference((TypeKeyword) this)};
    }
}
