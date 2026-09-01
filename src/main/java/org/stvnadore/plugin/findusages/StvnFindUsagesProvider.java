package org.stvnadore.plugin.findusages;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.IncludeMapAlias;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.ValueKeyword;

@NullMarked
public final class StvnFindUsagesProvider implements FindUsagesProvider {

    @Override
    public @Nullable WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(
                new com.intellij.lexer.FlexAdapter(new org.stvnadore.parser._StvnLexer(null)),
                TokenSet.create(StvnTypes.IDENTIFIER, StvnTypes.TYPE_KEYWORD_BASE, StvnTypes.VALUE_KEYWORD_BASE),
                TokenSet.create(StvnTypes.COMMENT),
                TokenSet.create(StvnTypes.LITERAL_STRING_SIMPLE, StvnTypes.LITERAL_STRING_BLOCK, StvnTypes.LITERAL_STRING_FENCED)
        );
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement psiElement) {
        if (psiElement instanceof TypeDefinition || psiElement instanceof ConstantDefinition || psiElement instanceof IncludeMapAlias) {
            return true;
        }
        if (psiElement instanceof TypeKeyword || psiElement instanceof ValueKeyword) {
            return isDeclarationKeyword(psiElement);
        }
        return false;
    }

    @Override
    public @Nullable String getHelpId(@NotNull PsiElement psiElement) {
        return null;
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        if (element instanceof ConstantDefinition) {
            return "Constant Definition";
        }
        if (element instanceof TypeDefinition) {
            return "Type Definition";
        }
        if (element instanceof IncludeMapAlias) {
            return "Include Alias";
        }
        if (element instanceof ValueKeyword) {
            return "Constant Declaration";
        }
        if (element instanceof TypeKeyword) {
            return "Type Declaration";
        }
        return "Symbol";
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        if (element instanceof TypeDefinition) {
            var name = ((TypeDefinition) element).getName();
            return name != null ? name : "";
        }
        if (element instanceof ConstantDefinition) {
            var name = ((ConstantDefinition) element).getName();
            return name != null ? name : "";
        }
        if (element instanceof IncludeMapAlias) {
            var name = ((IncludeMapAlias) element).getName();
            return name != null ? name : "";
        }
        if (element instanceof TypeKeyword || element instanceof ValueKeyword) {
            return element.getText();
        }
        return "";
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        return getDescriptiveName(element);
    }

    private static boolean isDeclarationKeyword(PsiElement keyword) {
        var parent = keyword.getParent();
        if (parent instanceof TypeDefinition) {
            return ((TypeDefinition) parent).getTypeKeyword() == keyword;
        }
        if (parent instanceof ConstantDefinition) {
            return ((ConstantDefinition) parent).getValueKeyword() == keyword;
        }
        if (parent instanceof IncludeMapAlias) {
            var alias = (IncludeMapAlias) parent;
            var list = alias.getTypeKeywordList();
            return list.size() >= 2 && list.get(1) == keyword;
        }
        return false;
    }
}
