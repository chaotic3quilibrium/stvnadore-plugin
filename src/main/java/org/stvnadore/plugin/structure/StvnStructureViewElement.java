package org.stvnadore.plugin.structure;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.icons.StvnIcons;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.psi.*;

/**
 * Tree element representation for STVN document components within the Structure View panel.
 */
@NullMarked
public final class StvnStructureViewElement implements StructureViewTreeElement, SortableTreeElement {

    private final PsiElement element;

    /**
     * Constructs a new structure view element wrapping the given PSI element.
     *
     * @param element the underlying PSI element represented by this tree node
     */
    public StvnStructureViewElement(PsiElement element) {
        this.element = element;
    }

    @Override
    public Object getValue() {
        return element;
    }

    @Override
    public void navigate(boolean requestFocus) {
        if (element instanceof Navigatable navigatable) {
            navigatable.navigate(requestFocus);
        }
    }

    @Override
    public boolean canNavigate() {
        return element instanceof Navigatable navigatable && navigatable.canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
        return element instanceof Navigatable navigatable && navigatable.canNavigateToSource();
    }

    @Override
    public @NotNull String getAlphaSortKey() {
        if (element instanceof PsiNamedElement named) {
            var name = named.getName();
            return name != null ? name : "";
        }
        var text = element.getText();
        return text != null ? text : "";
    }

    @Override
    public @NotNull ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override
            public @Nullable String getPresentableText() {
                if (element instanceof TypeDefinition typeDef) {
                    var name = typeDef.getName();
                    return name != null && !name.isEmpty() ? ":" + name : ":Type";
                }
                if (element instanceof ConstantDefinition constDef) {
                    var kw = constDef.getValueKeyword();
                    return kw != null ? kw.getText() : "#Constant";
                }
                if (element instanceof TypeEntry) {
                    return ":type";
                }
                if (element instanceof DefsEntry) {
                    return ":defs";
                }
                if (element instanceof BodyEntry) {
                    return ":body";
                }
                if (element instanceof PsiNamedElement named) {
                    return named.getName();
                }
                return element.getText();
            }

            @Override
            public @Nullable String getLocationString() {
                if (element instanceof TypeDefinition typeDef) {
                    return StvnSchemaFormatter.formatSchema(typeDef.getSchemaType());
                }
                if (element instanceof ConstantDefinition constDef) {
                    return StvnSchemaFormatter.formatSchema(constDef.getSchemaType());
                }
                if (element instanceof TypeEntry typeEntry) {
                    return StvnSchemaFormatter.formatSchema(typeEntry.getSchemaType());
                }
                return null;
            }

            @Override
            public @Nullable Icon getIcon(boolean unused) {
                if (element instanceof TypeDefinition || element instanceof ConstantDefinition) {
                    return StvnIcons.STVN;
                }
                if (element instanceof DefsEntry || element instanceof TypeEntry || element instanceof BodyEntry) {
                    return StvnIcons.STVN_INCL;
                }
                return StvnIcons.STVN;
            }
        };
    }

    @Override
    public TreeElement @NotNull [] getChildren() {
        var children = new ArrayList<TreeElement>();
        if (element instanceof StvnFile file) {
            var defs = PsiTreeUtil.findChildOfType(file, DefsEntry.class);
            if (defs != null) {
                children.add(new StvnStructureViewElement(defs));
            }

            var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
            if (typeEntry != null) {
                children.add(new StvnStructureViewElement(typeEntry));
            }

            var body = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
            if (body != null) {
                children.add(new StvnStructureViewElement(body));
            }
        } else if (element instanceof DefsEntry defs) {
            for (var child : defs.getChildren()) {
                if (child instanceof DefsElement defsElem) {
                    if (defsElem.getTypeDefinition() != null) {
                        children.add(new StvnStructureViewElement(defsElem.getTypeDefinition()));
                    } else if (defsElem.getConstantDefinition() != null) {
                        children.add(new StvnStructureViewElement(defsElem.getConstantDefinition()));
                    }
                } else if (child instanceof TypeDefinition typeDef) {
                    children.add(new StvnStructureViewElement(typeDef));
                } else if (child instanceof ConstantDefinition constDef) {
                    children.add(new StvnStructureViewElement(constDef));
                }
            }
        }
        return children.toArray(new TreeElement[0]);
    }
}
