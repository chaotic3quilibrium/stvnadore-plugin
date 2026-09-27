package org.stvnadore.plugin.psi.impl;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeKeyword;

@NullMarked
public abstract class StvnTypeDefinitionMixin extends ASTWrapperPsiElement implements TypeDefinition {

    protected StvnTypeDefinitionMixin(ASTNode node) {
        super(node);
    }

    /**
     * Returns the metadata map attached to this type definition via its schema type.
     *
     * @return the metadata map, or null if unadorned
     */
    @Override
    public @Nullable org.stvnadore.psi.MetadataMap getMetadataMap() {
        var schemaType = getSchemaType();
        return schemaType != null ? schemaType.getMetadataMap() : null;
    }

    @Override
    public @Nullable TypeKeyword getTypeKeyword() {
        var target = getTypeDefTarget();
        if (target != null) {
            return target.getTypeKeyword();
        }
        return findChildByClass(TypeKeyword.class);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        var target = getTypeDefTarget();
        if (target != null) {
            if (target.getTypeKeyword() != null) {
                return target.getTypeKeyword();
            }
            return target;
        }
        return findChildByClass(TypeKeyword.class);
    }

    @Override
    public String getName() {
        var identifier = getNameIdentifier();
        if (identifier instanceof com.intellij.psi.PsiNamedElement named) {
            var name = named.getName();
            return name != null ? name : "";
        }
        if (identifier != null) {
            var text = identifier.getText();
            return text.startsWith(":") ? text.substring(1) : text;
        }
        return "";
    }

    @Override
    public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
        if (name.startsWith(":") || name.startsWith("#")) {
            throw new IncorrectOperationException("Identifier must be a bare name without ':' or '#' prefix: " + name);
        }
        var identifier = getNameIdentifier();
        if (identifier != null) {
            var newKeyword = StvnElementFactory.createTypeKeyword(getProject(), ":" + name);
            identifier.replace(newKeyword);
        }
        return this;
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

    /**
     * Returns the text offset within the file where the declared type identifier begins.
     *
     * @return the start offset of the type name identifier token, or the container offset if null
     */
    @Override
    public int getTextOffset() {
        var identifier = getNameIdentifier();
        return identifier != null ? identifier.getTextOffset() : super.getTextOffset();
    }
}

