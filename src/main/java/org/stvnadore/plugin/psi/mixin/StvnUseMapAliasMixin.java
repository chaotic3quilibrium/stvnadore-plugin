package org.stvnadore.plugin.psi.mixin;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.UseMapAlias;
import org.stvnadore.psi.ValueKeyword;

/**
 * Mixin implementation for {@link UseMapAlias} PSI elements.
 * <p>
 * Binds use alias pairs to the IntelliJ Platform naming contract, identifying the local
 * alias symbol (index 1) as the declared identifier and anchoring rename refactoring cursors.
 */
@NullMarked
public abstract class StvnUseMapAliasMixin extends ASTWrapperPsiElement implements UseMapAlias {

    /**
     * Constructs an StvnUseMapAliasMixin instance.
     *
     * @param node AST node representing the use map alias element
     */
    protected StvnUseMapAliasMixin(ASTNode node) {
        super(node);
    }

    /**
     * Retrieves the PSI element representing the declared name identifier of this alias.
     *
     * @return the second TypeKeyword or ValueKeyword child representing the local alias name,
     *         or {@code null} if fewer than two keywords exist
     */
    @Override
    public @Nullable PsiElement getNameIdentifier() {
        var typeKeywords = getTypeKeywordList();
        if (typeKeywords.size() >= 2) {
            return typeKeywords.get(1);
        }
        var valueKeywords = getValueKeywordList();
        if (valueKeywords.size() >= 2) {
            return valueKeywords.get(1);
        }
        return null;
    }

    /**
     * Returns the name of the alias identifier.
     *
     * @return the canonical string name of the alias preserving symbol prefix,
     *         or an empty string if no identifier is present
     */
    @Override
    public String getName() {
        var identifier = getNameIdentifier();
        if (identifier instanceof com.intellij.psi.PsiNamedElement named) {
            var name = named.getName();
            return name != null ? name : "";
        }
        return identifier != null ? identifier.getText() : "";
    }

    /**
     * Updates the name of the alias identifier.
     *
     * @param name the new name string to assign to the alias
     * @return this PSI element after mutation
     * @throws IncorrectOperationException if the identifier cannot be replaced
     */
    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        var identifier = getNameIdentifier();
        if (identifier instanceof TypeKeyword) {
            var newKeyword = StvnElementFactory.createTypeKeyword(getProject(), name.startsWith(":") ? name : ":" + name);
            identifier.replace(newKeyword);
        } else if (identifier instanceof ValueKeyword) {
            var newKeyword = StvnElementFactory.createValueKeyword(getProject(), name.startsWith("#") ? name : "#" + name);
            identifier.replace(newKeyword);
        }
        return this;
    }

    /**
     * Returns the text offset within the file where the declared alias identifier begins.
     *
     * @return the start offset of the alias name identifier token, or the container offset if null
     */
    @Override
    public int getTextOffset() {
        var identifier = getNameIdentifier();
        return identifier != null ? identifier.getTextOffset() : super.getTextOffset();
    }
}
