package org.stvnadore.plugin.editor;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateEditingAdapter;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

import java.util.regex.Pattern;

/**
 * Intercepts template completion for fenced string tag renames.
 * Enforces Rule STR-04 character class constraints and blocks delimiter collisions inside the payload.
 */
@NullMarked
public final class StvnFencedStringTagRenameListener extends TemplateEditingAdapter {

    public static final String COLLISION_ERROR_MSG =
        "Cannot rename tag: proposed tag collides with a delimiter sequence inside the string payload.";
    public static final String INVALID_TAG_ERROR_MSG =
        "Cannot rename tag: tag must match ^[a-zA-Z0-9_-]{1,256}$ and cannot be empty or contain whitespace.";

    private static final Pattern VALID_TAG_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,256}$");

    private final Project project;
    private final Editor editor;
    private final PsiFile file;
    private final int elementStartOffset;
    private final String originalTag;
    private final TextRange originalOpenTagRange;
    private final TextRange originalCloseTagRange;
    private final int payloadRelativeStart;
    private final int payloadRelativeEnd;
    private final int initialCaretOffset;
    private final boolean inCloseTag;
    private final int openBracketIdx;

    private @Nullable String committedTag = null;
    private boolean reverted = false;

    public StvnFencedStringTagRenameListener(Project project,
                                            Editor editor,
                                            PsiFile file,
                                            int elementStartOffset,
                                            String originalTag,
                                            TextRange originalOpenTagRange,
                                            TextRange originalCloseTagRange,
                                            int payloadRelativeStart,
                                            int payloadRelativeEnd,
                                            int initialCaretOffset,
                                            boolean inCloseTag,
                                            int openBracketIdx) {
        this.project = project;
        this.editor = editor;
        this.file = file;
        this.elementStartOffset = elementStartOffset;
        this.originalTag = originalTag;
        this.originalOpenTagRange = originalOpenTagRange;
        this.originalCloseTagRange = originalCloseTagRange;
        this.payloadRelativeStart = payloadRelativeStart;
        this.payloadRelativeEnd = payloadRelativeEnd;
        this.initialCaretOffset = initialCaretOffset;
        this.inCloseTag = inCloseTag;
        this.openBracketIdx = openBracketIdx;
    }

    @Override
    public void beforeTemplateFinished(@NotNull TemplateState state, Template template) {
        String newTag = extractProposedTag(state);

        // Constraint 1: Character class and length bounds
        if (newTag == null || !VALID_TAG_PATTERN.matcher(newTag).matches()) {
            revertToOriginalTag();
            HintManager.getInstance().showErrorHint(editor, INVALID_TAG_ERROR_MSG);
            return;
        }

        // Constraint 2: Payload delimiter collision guard
        if (hasPayloadCollision(newTag)) {
            revertToOriginalTag();
            HintManager.getInstance().showErrorHint(editor, COLLISION_ERROR_MSG);
            return;
        }

        // Commit valid document changes
        this.committedTag = newTag;
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
    }

    @Override
    public void templateCancelled(Template template) {
        revertToOriginalTag();
    }

    @Override
    public void templateFinished(@NotNull Template template, boolean brokenOff) {
        Document doc = editor.getDocument();
        if (reverted) {
            if (initialCaretOffset >= 0 && initialCaretOffset <= doc.getTextLength()) {
                editor.getCaretModel().moveToOffset(initialCaretOffset);
            }
            return;
        }

        String newTag = this.committedTag;
        int openTagStart = elementStartOffset + openBracketIdx + 1;
        if (newTag == null) {
            int openCloseBracket = doc.getText().indexOf(']', openTagStart);
            if (openCloseBracket > openTagStart) {
                newTag = doc.getText(new TextRange(openTagStart, openCloseBracket));
            } else {
                newTag = originalTag;
            }
        }

        if (!inCloseTag) {
            int targetOffset = openTagStart + newTag.length();
            if (targetOffset >= 0 && targetOffset <= doc.getTextLength()) {
                editor.getCaretModel().moveToOffset(targetOffset);
            }
        } else {
            int delta = newTag.length() - originalTag.length();
            int closingTagStart = elementStartOffset + originalCloseTagRange.getStartOffset() + delta;
            if (closingTagStart < 0 || closingTagStart + newTag.length() > doc.getTextLength() ||
                !doc.getText(new TextRange(closingTagStart, closingTagStart + newTag.length())).equals(newTag)) {
                String fullText = doc.getText();
                int searchBoundary = Math.min(fullText.length(), elementStartOffset + payloadRelativeEnd + delta + 300);
                int lastTriple = fullText.lastIndexOf("\"\"\"", searchBoundary);
                if (lastTriple > elementStartOffset) {
                    int closeBracket = fullText.lastIndexOf(']', lastTriple);
                    if (closeBracket > elementStartOffset) {
                        int openBracket = fullText.lastIndexOf('[', closeBracket);
                        if (openBracket >= 0 && openBracket < closeBracket) {
                            closingTagStart = openBracket + 1;
                        }
                    }
                }
            }
            int targetOffset = closingTagStart + newTag.length();
            if (targetOffset >= 0 && targetOffset <= doc.getTextLength()) {
                editor.getCaretModel().moveToOffset(targetOffset);
            }
        }
    }

    private @Nullable String extractProposedTag(TemplateState state) {
        var range = state.getVariableRange("TAG");
        if (range != null) {
            return editor.getDocument().getText(range);
        }
        var value = state.getVariableValue("TAG");
        if (value != null && !value.getText().isEmpty()) {
            return value.getText();
        }
        return null;
    }

    private boolean hasPayloadCollision(String newTag) {
        Document doc = editor.getDocument();
        int docLength = doc.getTextLength();
        int delta = newTag.length() - originalTag.length();
        int absPayloadStart = elementStartOffset + payloadRelativeStart + delta;
        int absPayloadEnd = elementStartOffset + payloadRelativeEnd + delta;

        if (absPayloadStart < 0 || absPayloadEnd > docLength || absPayloadStart > absPayloadEnd) {
            return false;
        }

        String payload = doc.getText(new TextRange(absPayloadStart, absPayloadEnd));

        String closingCollision = "[" + newTag + "]\"\"\"";
        String openingCollision = "\"\"\"[" + newTag + "]";
        String arrowCollision = "\"\"\"->[" + newTag + "]";

        return payload.contains(closingCollision) ||
               payload.contains(openingCollision) ||
               payload.contains(arrowCollision);
    }

    private void revertToOriginalTag() {
        if (reverted) {
            return;
        }
        reverted = true;

        Document doc = editor.getDocument();
        Runnable mutation = () -> {
            String fullText = doc.getText();
            int docLength = fullText.length();

            // 1. Locate and revert closing delimiter tag first (higher offset in document)
            int searchBoundary = Math.min(docLength, elementStartOffset + payloadRelativeEnd + 300);
            int lastTriple = fullText.lastIndexOf("\"\"\"", searchBoundary);
            if (lastTriple > elementStartOffset) {
                int closeBracket = fullText.lastIndexOf(']', lastTriple);
                if (closeBracket > elementStartOffset) {
                    int openBracket = fullText.lastIndexOf('[', closeBracket);
                    if (openBracket >= 0 && openBracket < closeBracket) {
                        doc.replaceString(openBracket + 1, closeBracket, originalTag);
                    }
                }
            }

            // 2. Locate and revert opening delimiter tag second (lower offset in document)
            String updatedText = doc.getText();
            int openBracket = updatedText.indexOf('[', elementStartOffset);
            if (openBracket >= 0) {
                int openCloseBracket = updatedText.indexOf(']', openBracket);
                if (openCloseBracket > openBracket) {
                    doc.replaceString(openBracket + 1, openCloseBracket, originalTag);
                }
            }

            PsiDocumentManager.getInstance(project).commitDocument(doc);

            if (initialCaretOffset >= 0 && initialCaretOffset <= doc.getTextLength()) {
                editor.getCaretModel().moveToOffset(initialCaretOffset);
            }
        };

        if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
            mutation.run();
        } else {
            WriteCommandAction.runWriteCommandAction(project, mutation);
        }
    }
}
