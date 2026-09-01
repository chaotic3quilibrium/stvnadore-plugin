package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.Visitor;

import javax.swing.*;
import java.awt.*;

@NullMarked
public final class StvnDegradedSchemaInspection extends LocalInspectionTool {

    @SuppressWarnings("PublicField")
    public boolean highlightBodyValuesBoundToDegradedSchemas = false;

    @Override
    public @Nullable JComponent createOptionsPanel() {
        var panel = new JPanel(new BorderLayout());
        var checkBox = new JCheckBox(
            "Highlight body values bound to degraded schemas",
            highlightBodyValuesBoundToDegradedSchemas
        );
        checkBox.addActionListener(e -> highlightBodyValuesBoundToDegradedSchemas = checkBox.isSelected());
        panel.add(checkBox, BorderLayout.NORTH);
        return panel;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if (!highlightBodyValuesBoundToDegradedSchemas) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }

        return new Visitor() {
            @Override
            public void visitValue(@NotNull Value value) {
                super.visitValue(value);

                // Only inspect values physically located inside :body
                var bodyParent = PsiTreeUtil.getParentOfType(value, BodyEntry.class);
                if (bodyParent == null) {
                    return;
                }

                // Check if this leaf value's resolved schema is degraded
                var info = StvnTypeResolver.resolveBaseTypeInfo(value);
                if (info != null && info.getSchema() != null) {
                    var schema = info.getSchema();
                    var kw = schema.getTypeKeyword();
                    var aliasName = kw != null ? kw.getText() : null;
                    if (aliasName != null && StvnTypeResolver.isDegradedNominalAlias(value.getContainingFile(), aliasName)) {
                        var resolved = StvnTypeResolver.resolveNominalSchema(schema);
                        var fallbackBase = resolved != null ? StvnSchemaFormatter.formatCleanSchema(resolved) : StvnSchemaFormatter.formatCleanSchema(schema);
                        var message = "Value bound to degraded schema '" + aliasName + 
                            "' (operating on fallback base '" + fallbackBase + "')";

                        holder.registerProblem(
                            value,
                            message,
                            ProblemHighlightType.WEAK_WARNING
                        );
                    }
                }
            }
        };
    }
}
