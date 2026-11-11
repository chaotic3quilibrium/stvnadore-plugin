package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.ir.StvnSchema;
import org.stvnadore.plugin.psi.StvnSchemaFormatter;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.Visitor;

import javax.swing.*;
import java.awt.*;

/**
 * Inspects body values bound to degraded schemas to highlight degraded nominal types.
 */
@NullMarked
public final class StvnDegradedSchemaInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnDegradedSchemaInspection instance.
     */
    public StvnDegradedSchemaInspection() {
    }

    /**
     * Configuration flag controlling whether body values bound to degraded schemas are highlighted.
     */
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
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(value)) {
                    return;
                }

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
                        var irSchema = StvnTypeResolver.resolveIrSchemaByAlias(value.getContainingFile(), aliasName);
                        String rawMessage;
                        if (irSchema instanceof StvnSchema.PoisonedSchema poisoned && poisoned.isUngrounded()) {
                            rawMessage = "Value bound to degraded schema '" + aliasName
                                + "' (payload evaluation is suspended for ungrounded schema)";
                        } else {
                            String fallbackBase;
                            if (irSchema instanceof StvnSchema.PoisonedSchema poisoned && poisoned.isGrounded()) {
                                fallbackBase = StvnSchemaFormatter.formatCleanIrSchema(poisoned.bestEffortFallback().get());
                            } else {
                                var resolved = StvnTypeResolver.resolveNominalSchema(schema);
                                fallbackBase = resolved != null
                                    ? StvnSchemaFormatter.formatCleanSchema(resolved)
                                    : StvnSchemaFormatter.formatCleanSchema(schema);
                            }
                            rawMessage = "Value bound to degraded schema '" + aliasName
                                + "' (underlying base '" + fallbackBase + "' \u2014 payload evaluation is suspended)";
                        }
                        var message = StvnDiagnosticFormatter.formatPlugin(
                            rawMessage,
                            StvnDiagnosticCodes.WARN_DEGRADED_SCHEMA_USAGE
                        );

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
