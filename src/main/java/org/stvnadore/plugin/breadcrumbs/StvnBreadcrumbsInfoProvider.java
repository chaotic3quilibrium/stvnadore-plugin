package org.stvnadore.plugin.breadcrumbs;

import com.intellij.lang.Language;
import com.intellij.psi.PsiElement;
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.psi.*;

/**
 * Breadcrumb info provider projecting canonical STVN element signatures into the editor breadcrumb bar.
 */
@NullMarked
public final class StvnBreadcrumbsInfoProvider implements BreadcrumbsProvider {

    private static final Language[] LANGUAGES = new Language[]{StvnLanguage.INSTANCE};
    private static final int MAX_CRUMB_LENGTH = 48;

    /**
     * Constructs a new {@code StvnBreadcrumbsInfoProvider} instance.
     */
    public StvnBreadcrumbsInfoProvider() {
    }

    @Override
    public Language @NotNull [] getLanguages() {
        return LANGUAGES;
    }

    @Override
    public boolean acceptElement(@NotNull PsiElement e) {
        return e instanceof TypeDefinition
            || e instanceof ConstantDefinition
            || e instanceof TypeEntry
            || e instanceof DefsEntry
            || e instanceof BodyEntry
            || e instanceof SchemaType;
    }

    @Override
    public @NotNull String getElementInfo(@NotNull PsiElement e) {
        String info;
        if (e instanceof TypeDefinition typeDef) {
            var name = typeDef.getName();
            var schema = StvnSchemaFormatter.formatSchema(typeDef.getSchemaType());
            info = (":" + name + " -> " + schema).trim();
        } else if (e instanceof ConstantDefinition constDef) {
            var kw = constDef.getValueKeyword();
            var name = kw != null ? kw.getText() : "#Const";
            var schema = StvnSchemaFormatter.formatSchema(constDef.getSchemaType());
            info = (name + " : " + schema).trim();
        } else if (e instanceof TypeEntry typeEntry) {
            info = (":type " + StvnSchemaFormatter.formatSchema(typeEntry.getSchemaType())).trim();
        } else if (e instanceof DefsEntry) {
            info = ":defs";
        } else if (e instanceof BodyEntry) {
            info = ":body";
        } else if (e instanceof SchemaType schemaType) {
            info = StvnSchemaFormatter.formatSchema(schemaType);
        } else {
            var text = e.getText();
            info = text != null ? text : "";
        }

        if (info.length() > MAX_CRUMB_LENGTH) {
            return info.substring(0, MAX_CRUMB_LENGTH - 3) + "...";
        }
        return info;
    }

    @Override
    public @Nullable String getElementTooltip(@NotNull PsiElement e) {
        if (e instanceof TypeDefinition typeDef) {
            return "Type Definition: " + StvnSchemaFormatter.formatSchema(typeDef.getSchemaType());
        }
        if (e instanceof ConstantDefinition constDef) {
            return "Constant: " + StvnSchemaFormatter.formatSchema(constDef.getSchemaType());
        }
        if (e instanceof SchemaType schemaType) {
            return "Schema: " + StvnSchemaFormatter.formatSchema(schemaType);
        }
        return null;
    }
}
