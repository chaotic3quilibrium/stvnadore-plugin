package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.Value;

import java.util.List;

/**
 * Smart structural auto-healer intention action and quick-fix that intercepts
 * raw flat bracketed lists supplied to map slots and restructures them into
 * canonical map literals ({ [ k v ] }).
 */
@NullMarked
public final class StvnMapAutoHealerQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {

    /**
     * Constructs an StvnMapAutoHealerQuickFix for the given ListLiteral element.
     *
     * @param element the offending ListLiteral PSI element
     */
    public StvnMapAutoHealerQuickFix(ListLiteral element) {
        super(element);
    }

    @Override
    public @NotNull String getText() {
        return "Convert flat list to canonical map literal '{ [ ... ] }'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Map Auto-Healer";
    }

    @Override
    public boolean isAvailable(@NotNull Project project,
                               @NotNull PsiFile file,
                               @NotNull PsiElement startElement,
                               @NotNull PsiElement endElement) {
        if (!startElement.isValid()) {
            return false;
        }
        var listLiteral = PsiTreeUtil.getNonStrictParentOfType(startElement, ListLiteral.class);
        return listLiteral != null && listLiteral.isValid();
    }

    @Override
    public void invoke(@NotNull Project project,
                       @NotNull PsiFile file,
                       @Nullable Editor editor,
                       @NotNull PsiElement startElement,
                       @NotNull PsiElement endElement) {
        var listLiteral = PsiTreeUtil.getNonStrictParentOfType(startElement, ListLiteral.class);
        if (listLiteral == null) {
            return;
        }

        var values = PsiTreeUtil.getChildrenOfTypeAsList(listLiteral, Value.class);
        if (values.isEmpty()) {
            var docManager = PsiDocumentManager.getInstance(project);
            var doc = file.getViewProvider().getDocument();
            if (doc != null) {
                var range = listLiteral.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), "{}");
                docManager.commitDocument(doc);
            } else {
                var emptyMap = StvnElementFactory.createValue(project, "{}");
                var parentVal = PsiTreeUtil.getParentOfType(listLiteral, Value.class);
                if (parentVal != null) {
                    parentVal.replace(emptyMap);
                } else {
                    listLiteral.replace(emptyMap);
                }
            }
            return;
        }

        var isMultiLine = listLiteral.getText().contains("\n");
        var baseIndent = extractBaseIndent(listLiteral);
        var entryIndent = baseIndent + "  ";

        var sb = new StringBuilder();
        if (isMultiLine) {
            sb.append("{\n");
            for (int i = 0; i < values.size() - 1; i += 2) {
                var key = values.get(i).getText();
                var val = values.get(i + 1).getText();
                sb.append(entryIndent).append("[ ").append(key).append(" ").append(val).append(" ]\n");
            }
            sb.append(baseIndent).append("}");
        } else {
            sb.append("{ ");
            for (int i = 0; i < values.size() - 1; i += 2) {
                var key = values.get(i).getText();
                var val = values.get(i + 1).getText();
                sb.append("[ ").append(key).append(" ").append(val).append(" ] ");
            }
            sb.append("}");
        }

        // Handle odd trailing element gracefully by appending it outside if present
        if (values.size() % 2 != 0) {
            var danglingVal = values.get(values.size() - 1).getText();
            sb.append(" ").append(danglingVal);
        }

        var docManager = PsiDocumentManager.getInstance(project);
        var doc = file.getViewProvider().getDocument();
        if (doc != null) {
            var range = listLiteral.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), sb.toString());
            docManager.commitDocument(doc);
        } else {
            var newElement = StvnElementFactory.createValue(project, sb.toString());
            var parentVal = PsiTreeUtil.getParentOfType(listLiteral, Value.class);
            if (parentVal != null) {
                parentVal.replace(newElement);
            } else {
                listLiteral.replace(newElement);
            }
        }
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