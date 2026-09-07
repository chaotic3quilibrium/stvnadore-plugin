package org.stvnadore.plugin.editor;

import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateBuilderImpl;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.ConstantNode;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.rename.RenameHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;

import java.util.regex.Pattern;

/**
 * Provides in-editor Shift+F6 interactive tag renaming for balanced STVN fenced strings (Rule STR-04).
 * Synchronizes opening and closing delimiter tags in real time via TemplateBuilderImpl.
 */
@NullMarked
public final class StvnFencedStringTagRenameHandler implements RenameHandler {

    private static final Pattern VALID_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");
    private static final Pattern DELIMITER_BOUNDARY_PATTERN =
        Pattern.compile("(\"\"\"(?:->)?\\[([a-zA-Z0-9_-]{1,256})\\])|(\\[([a-zA-Z0-9_-]{1,256})\\]\"\"\")");

    public StvnFencedStringTagRenameHandler() {}

    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        Project project = CommonDataKeys.PROJECT.getData(dataContext);
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
        if (project == null || editor == null || file == null || !(file instanceof StvnFile)) {
            return false;
        }

        int offset = editor.getCaretModel().getOffset();
        if (offset < 0 || offset > file.getTextLength()) {
            return false;
        }

        PsiElement element = file.findElementAt(offset);
        if (element == null && offset > 0) {
            element = file.findElementAt(offset - 1);
        }

        PsiElement fencedElement = findFencedStringElement(element);
        if (fencedElement == null) {
            return false;
        }

        FencedBlockData blockData = parseFencedBlock(fencedElement);
        if (blockData == null) {
            return false;
        }

        int elementStart = fencedElement.getTextRange().getStartOffset();
        int relOffset = offset - elementStart;

        boolean inOpenTag = relOffset >= blockData.openBracketIdx && relOffset <= blockData.openCloseBracketIdx + 1;
        boolean inCloseTag = relOffset >= blockData.closingFenceStart && relOffset <= blockData.closingTagEnd + 1;

        return inOpenTag || inCloseTag;
    }

    @Override
    public boolean isRenaming(@NotNull DataContext dataContext) {
        return isAvailableOnDataContext(dataContext);
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file, @Nullable DataContext dataContext) {
        if (editor == null || file == null) {
            return;
        }

        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null && offset > 0) {
            element = file.findElementAt(offset - 1);
        }

        PsiElement fencedElement = findFencedStringElement(element);
        if (fencedElement == null) {
            return;
        }

        FencedBlockData blockData = parseFencedBlock(fencedElement);
        if (blockData == null) {
            return;
        }

        int elementStart = fencedElement.getTextRange().getStartOffset();
        int relOffset = offset - elementStart;
        boolean inCloseTag = relOffset >= blockData.closingFenceStart && relOffset <= blockData.closingTagEnd + 1;

        TextRange openTagRange = new TextRange(blockData.openBracketIdx + 1, blockData.openCloseBracketIdx);
        TextRange closeTagRange = new TextRange(blockData.closingTagStart, blockData.closingTagEnd);

        int initialCaretOffset = editor.getCaretModel().getOffset();

        WriteCommandAction.runWriteCommandAction(project, () -> {
            TemplateBuilderImpl builder = new TemplateBuilderImpl(fencedElement);
            builder.replaceRange(closeTagRange, "TAG", new ConstantNode(blockData.tag), true);
            builder.replaceRange(openTagRange, "TAG", new ConstantNode(blockData.tag), false);

            Template template = builder.buildInlineTemplate();
            if (inCloseTag && template instanceof TemplateImpl impl) {
                impl.setPrimarySegment(1);
            }

            var listener = new StvnFencedStringTagRenameListener(
                project,
                editor,
                file,
                elementStart,
                blockData.tag,
                openTagRange,
                closeTagRange,
                blockData.payloadStart,
                blockData.payloadEnd,
                initialCaretOffset
            );

            editor.getCaretModel().moveToOffset(elementStart);
            TemplateManager.getInstance(project).startTemplate(editor, template, listener);
        });
    }

    @Override
    public void invoke(@NotNull Project project, @NotNull PsiElement[] elements, @Nullable DataContext dataContext) {
        if (dataContext != null) {
            Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
            PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
            if (editor != null && file != null) {
                invoke(project, editor, file, dataContext);
            }
        }
    }

    private static @Nullable PsiElement findFencedStringElement(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        if (element.getNode() != null && element.getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED) {
            return element;
        }
        if (element instanceof StringLiteral sl) {
            var child = sl.getNode().findChildByType(StvnTypes.LITERAL_STRING_FENCED);
            if (child != null) {
                return child.getPsi();
            }
        }
        if (element.getParent() != null && element.getParent().getNode() != null &&
            element.getParent().getNode().getElementType() == StvnTypes.LITERAL_STRING_FENCED) {
            return element.getParent();
        }
        return null;
    }

    private static @Nullable FencedBlockData parseFencedBlock(PsiElement element) {
        String text = element.getText();
        if (!text.startsWith("\"\"\"")) {
            return null;
        }

        int openBracket = text.indexOf('[');
        int openCloseBracket = text.indexOf(']', openBracket >= 0 ? openBracket : 0);
        if (openBracket < 0 || openCloseBracket <= openBracket) {
            return null;
        }

        String openTag = text.substring(openBracket + 1, openCloseBracket);
        if (!VALID_TAG_PATTERN.matcher(openTag).matches()) {
            return null;
        }

        int openDelimiterEnd = text.indexOf('\n', openCloseBracket);
        if (openDelimiterEnd < 0) {
            openDelimiterEnd = text.length();
        } else {
            openDelimiterEnd += 1;
        }

        var matcher = DELIMITER_BOUNDARY_PATTERN.matcher(text);
        int depth = 0;
        int searchStart = openDelimiterEnd;
        String candidateClosingTag = null;
        int closingFenceStart = -1;
        int closingTagStart = -1;
        int closingTagEnd = -1;
        boolean found = false;

        while (matcher.find(searchStart)) {
            if (matcher.group(1) != null) {
                depth++;
            } else if (matcher.group(3) != null) {
                if (depth > 0) {
                    depth--;
                } else {
                    String tag = matcher.group(4);
                    if (tag.equals(openTag)) {
                        candidateClosingTag = tag;
                        closingFenceStart = matcher.start(3);
                        closingTagStart = matcher.start(4);
                        closingTagEnd = matcher.end(4);
                        found = true;
                        break;
                    } else if (matcher.end(3) == text.length()) {
                        return null; // Mismatched terminal delimiter
                    }
                }
            }
            searchStart = matcher.end();
        }

        if (!found || candidateClosingTag == null) {
            return null;
        }

        return new FencedBlockData(
            openTag,
            openBracket,
            openCloseBracket,
            closingFenceStart,
            closingTagStart,
            closingTagEnd,
            openDelimiterEnd,
            closingFenceStart
        );
    }

    private record FencedBlockData(
        String tag,
        int openBracketIdx,
        int openCloseBracketIdx,
        int closingFenceStart,
        int closingTagStart,
        int closingTagEnd,
        int payloadStart,
        int payloadEnd
    ) {}
}
