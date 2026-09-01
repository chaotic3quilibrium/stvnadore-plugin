package org.stvnadore.plugin.actions;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.template.TemplateBuilderImpl;
import com.intellij.codeInsight.template.impl.ConstantNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeEntry;
import org.stvnadore.psi.Value;

/**
 * Alt+Enter intention action that automatically generates a canonical, schema-compliant
 * data skeleton with interactive Live Template tab stops when invoked on an empty or un-authored :body block.
 */
@NullMarked
public final class StvnSchemaSkeletonIntentionAction extends PsiElementBaseIntentionAction {

    /** Constructs an StvnSchemaSkeletonIntentionAction instance. */
    public StvnSchemaSkeletonIntentionAction() {}

    @Override
    public @NotNull String getText() {
        return "Generate schema data skeleton";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Schema Scaffolding";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) {
        if (!element.isValid()) {
            return false;
        }

        var file = element.getContainingFile();
        if (file == null) {
            return false;
        }

        var bodyEntry = findTargetBodyEntry(element);
        if (bodyEntry == null) {
            return false;
        }

        var value = bodyEntry.getValue();
        if (value != null) {
            var text = value.getText().trim();
            if (!text.isEmpty() && !(value.getFirstChild() instanceof com.intellij.psi.PsiErrorElement)) {
                return false;
            }
        }

        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        return typeEntry != null && typeEntry.getSchemaType() != null;
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @NotNull PsiElement element) throws IncorrectOperationException {
        var file = element.getContainingFile();
        if (file == null) return;

        var bodyEntry = findTargetBodyEntry(element);
        if (bodyEntry == null) return;

        var typeEntry = PsiTreeUtil.findChildOfType(file, TypeEntry.class);
        if (typeEntry == null || typeEntry.getSchemaType() == null) return;

        var baseIndent = extractBaseIndent(bodyEntry);
        var scaffold = StvnSchemaSkeletonScaffolder.generateSkeleton(typeEntry.getSchemaType(), baseIndent);
        if (scaffold == null) return;

        var doc = file.getViewProvider().getDocument();
        var docManager = PsiDocumentManager.getInstance(project);

        if (doc != null) {
            var existingValue = bodyEntry.getValue();
            if (existingValue != null) {
                var range = existingValue.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), scaffold.getCodeText());
            } else {
                var bodyKeyword = bodyEntry.getFirstChild();
                var startOffset = (bodyKeyword != null) ? bodyKeyword.getTextRange().getEndOffset() : bodyEntry.getTextRange().getStartOffset();
                var text = doc.getText();
                var lineEnd = text.indexOf('\n', startOffset);
                var endOffset = (lineEnd != -1) ? lineEnd : bodyEntry.getTextRange().getEndOffset();
                doc.replaceString(startOffset, endOffset, " " + scaffold.getCodeText());
            }
            docManager.commitDocument(doc);

            if (editor != null) {
                try {
                    var updatedBody = findTargetBodyEntry(file.findElementAt(bodyEntry.getTextRange().getStartOffset()));
                    if (updatedBody != null && updatedBody.getValue() != null) {
                        var targetValue = updatedBody.getValue();
                        var builder = new TemplateBuilderImpl(targetValue);

                        var leafValues = PsiTreeUtil.findChildrenOfType(targetValue, Value.class);
                        for (var leaf : leafValues) {
                            if (isLeafLiteral(leaf)) {
                                builder.replaceElement(leaf, new ConstantNode(leaf.getText()));
                            }
                        }
                        builder.run(editor, false);
                    }
                } catch (Exception ignored) {
                    // Gracefully handle live template initiation in headless or test environments
                }
            }
        }
    }

    private static @Nullable BodyEntry findTargetBodyEntry(PsiElement element) {
        if (element instanceof BodyEntry) {
            return (BodyEntry) element;
        }
        var parent = PsiTreeUtil.getParentOfType(element, BodyEntry.class);
        if (parent != null) {
            return parent;
        }
        var prev = PsiTreeUtil.prevVisibleLeaf(element);
        if (prev != null && prev.getNode().getElementType() == StvnTypes.KW_BODY) {
            return PsiTreeUtil.getParentOfType(prev, BodyEntry.class);
        }
        return null;
    }

    private static boolean isLeafLiteral(Value value) {
        return value.getIntegerLiteral() != null ||
               value.getFloatLiteral() != null ||
               value.getStringLiteral() != null ||
               value.getBooleanValue() != null ||
               value.getValueKeyword() != null;
    }

    private static String extractBaseIndent(PsiElement element) {
        var file = element.getContainingFile();
        if (file == null) return "  ";
        var text = file.getText();
        var offset = element.getTextRange().getStartOffset();
        var lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1));
        if (lineStart == -1) lineStart = 0;
        else lineStart += 1;

        var indentSb = new StringBuilder();
        for (int i = lineStart; i < offset && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                indentSb.append(c);
            } else {
                break;
            }
        }
        return indentSb.toString();
    }
}
