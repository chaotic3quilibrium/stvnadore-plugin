package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.psi.StvnElementFactory;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.StvnTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Intention quick-fix that reorders {@code :meta} entries into canonical
 * {@code #name -> #domain -> #kind} order while preserving per-line trailing {@code //} comments.
 */
@NullMarked
public final class StvnReorderMetaEntriesQuickFix implements LocalQuickFix, IntentionAction {

    private final @Nullable MetaBlock metaBlock;

    /**
     * Constructs a reordering quick-fix without a pre-bound PSI reference.
     */
    public StvnReorderMetaEntriesQuickFix() {
        this.metaBlock = null;
    }

    /**
     * Constructs a reordering quick-fix for the specified {@code :meta} block.
     *
     * @param metaBlock the {@code :meta} block containing unordered entries
     */
    public StvnReorderMetaEntriesQuickFix(@Nullable MetaBlock metaBlock) {
        this.metaBlock = metaBlock;
    }

    @Override
    public @NotNull String getText() {
        return getFamilyName();
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Reorder ':meta' entries to canonical order (#name, #domain, #kind)";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        return metaBlock != null && metaBlock.isValid();
    }

    @Override
    public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
        if (metaBlock != null && metaBlock.isValid()) {
            reorderMetaBlock(project, metaBlock);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement targetElement = descriptor.getPsiElement();
        MetaBlock targetBlock = PsiTreeUtil.getParentOfType(targetElement, MetaBlock.class, false);
        if (targetBlock == null && metaBlock != null && metaBlock.isValid()) {
            targetBlock = metaBlock;
        }
        if (targetBlock != null && targetBlock.isValid()) {
            reorderMetaBlock(project, targetBlock);
        }
    }

    private record EntryWithComment(
        MetaElement element,
        int rank,
        String elementText,
        @Nullable String sameLineComment
    ) {}

    /**
     * Sorts the entries of the given {@code :meta} block in-place into canonical
     * {@code #name -> #domain -> #kind} order while preserving trailing {@code //} comments.
     *
     * @param project the active project
     * @param block the {@code :meta} block to reorder
     */
    public static void reorderMetaBlock(@NotNull Project project, @NotNull MetaBlock block) {
        List<MetaElement> elements = block.getMetaElementList();
        if (elements.size() <= 1) {
            return;
        }

        List<EntryWithComment> items = new ArrayList<>(elements.size());
        boolean hasComments = false;
        for (MetaElement el : elements) {
            String trailingComment = extractSameLineTrailingComment(el.getNode());
            if (trailingComment != null) {
                hasComments = true;
            }
            items.add(new EntryWithComment(
                el,
                StvnMetadataOrderInspection.getMetaElementRank(el),
                el.getText().trim(),
                trailingComment
            ));
        }

        items.sort(Comparator.comparingInt(EntryWithComment::rank));

        String rawBlockText = block.getText();
        boolean multiLine = rawBlockText.contains("\n") || hasComments;
        String entryIndent = detectEntryIndent(rawBlockText, "    ");
        String closingIndent = detectClosingBraceIndent(rawBlockText, "  ");
        List<String> standaloneLeadingComments = extractStandaloneCommentsBeforeFirstEntry(block.getNode());

        StringBuilder sb = new StringBuilder(multiLine ? "{\n" : "{");
        if (multiLine) {
            for (String leadComment : standaloneLeadingComments) {
                sb.append(entryIndent).append(leadComment).append("\n");
            }
        }
        for (EntryWithComment item : items) {
            if (multiLine) {
                sb.append(entryIndent).append(item.elementText());
                if (item.sameLineComment() != null) {
                    sb.append(item.sameLineComment());
                }
                sb.append("\n");
            } else {
                sb.append(" ").append(item.elementText());
            }
        }
        sb.append(multiLine ? closingIndent + "}" : " }");

        MetaBlock replacement = StvnElementFactory.createMetaBlock(project, sb.toString());
        block.replace(replacement);
    }

    static @Nullable String extractSameLineTrailingComment(ASTNode entryNode) {
        StringBuilder wsBeforeComment = new StringBuilder();
        for (ASTNode next = entryNode.getTreeNext(); next != null; next = next.getTreeNext()) {
            var type = next.getElementType();
            if (type == TokenType.WHITE_SPACE) {
                String text = next.getText();
                if (text.contains("\n")) {
                    return null;
                }
                wsBeforeComment.append(text);
            } else if (type == StvnTypes.COMMENT) {
                String prefix = wsBeforeComment.isEmpty() ? " " : wsBeforeComment.toString();
                return prefix + next.getText().trim();
            } else {
                return null;
            }
        }
        return null;
    }

    static List<String> extractStandaloneCommentsBeforeFirstEntry(ASTNode blockNode) {
        List<String> comments = new ArrayList<>();
        for (ASTNode child = blockNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            var type = child.getElementType();
            if (type == StvnTypes.META_ELEMENT || type == StvnTypes.METADATA_ENTRY) {
                break;
            }
            if (type == StvnTypes.COMMENT) {
                comments.add(child.getText().trim());
            }
        }
        return comments;
    }

    static String detectEntryIndent(String blockText, String fallback) {
        String[] lines = blockText.split("\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("}") && !trimmed.startsWith("//")) {
                int firstNonWs = 0;
                while (firstNonWs < line.length() && (line.charAt(firstNonWs) == ' ' || line.charAt(firstNonWs) == '\t')) {
                    firstNonWs++;
                }
                return line.substring(0, firstNonWs);
            }
        }
        return fallback;
    }

    static String detectClosingBraceIndent(String blockText, String fallback) {
        int lastNewline = blockText.lastIndexOf('\n');
        if (lastNewline >= 0) {
            String lastLine = blockText.substring(lastNewline + 1);
            int braceIdx = lastLine.indexOf('}');
            if (braceIdx >= 0) {
                return lastLine.substring(0, braceIdx);
            }
        }
        return fallback;
    }
}
