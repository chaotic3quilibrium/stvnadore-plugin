package org.stvnadore.plugin.templates;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Defines template context for STVN source files.
 */
@NullMarked
public final class StvnTemplateContextType extends TemplateContextType {

    public StvnTemplateContextType() {
        super("STVN");
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext templateActionContext) {
        return templateActionContext.getFile().getLanguage().isKindOf(StvnLanguage.INSTANCE);
    }
}
