package org.stvnadore.plugin.formatting;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.TokenType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.TypeEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lossless AST-level canonical root-section reorderer ({@code :meta -> :defs -> :type -> :body})
 * shared by {@link StvnMetadataOrderPreFormatProcessor} ({@code Ctrl+Alt+L}) and
 * {@code ReorderRootSectionsQuickFix} ({@code Alt+Enter} + Intention Preview).
 */
@NullMarked
public final class StvnRootSectionReorderer {

    private StvnRootSectionReorderer() {}

    private record SectionSlice(
        int canonicalRank,
        int authoredIndex,
        String leadingTriviaAndComments,
        String sectionRawText,
        @Nullable String sameLineTrailingComment
    ) {}

    /**
     * Checks whether {@code file} contains two or more unique, structurally complete root sections
     * with zero syntax errors where at least one root section is out of canonical order.
     *
     * @param file target STVN PSI file
     * @return {@code true} if safe lossless canonical reordering applies
     */
    public static boolean canReorderRootSections(@Nullable PsiFile file) {
        return buildReorderPlan(file) != null;
    }

    /**
     * Reorders out-of-order root sections in {@code file} into canonical
     * {@code :meta -> :defs -> :type -> :body} order in place on the AST while migrating
     * contiguous leading {@code //} comments and same-line trailing {@code //} comments.
     *
     * @param project active project
     * @param file target STVN PSI file (physical or Intention Preview copy)
     * @return {@code true} if the AST was modified; {@code false} if already canonical or ineligible
     */
    public static boolean reorderIfNeeded(@NotNull Project project, @Nullable PsiFile file) {
        if (file == null || !file.isValid()) {
            return false;
        }
        String replacementDocText = buildReorderPlan(file);
        if (replacementDocText == null) {
            return false;
        }
        PsiFile dummyFile = PsiFileFactory.getInstance(project)
            .createFileFromText("dummy.stvn", StvnFileType.Payload.INSTANCE, replacementDocText);
        return replaceRootInteriorInPlace(file.getNode(), dummyFile.getNode());
    }

    private static @Nullable String buildReorderPlan(@Nullable PsiFile file) {
        if (file == null || PsiTreeUtil.hasErrorElements(file)) {
            return null;
        }
        ASTNode fileNode = file.getNode();
        if (fileNode == null) {
            return null;
        }
        ASTNode rootLBrace = fileNode.findChildByType(StvnTypes.LBRACE);
        ASTNode rootRBrace = findLastDirectRBrace(fileNode);
        if (rootLBrace == null || rootRBrace == null || rootLBrace == rootRBrace) {
            return null;
        }

        boolean seenMeta = false;
        boolean seenDefs = false;
        boolean seenType = false;
        boolean seenBody = false;
        int maxRank = 0;
        boolean outOfOrder = false;

        String rootOpenSameLineComment = extractSameLineTrailingComment(rootLBrace);
        ASTNode cursor = skipSameLineTrailingCommentNodes(rootLBrace);

        List<SectionSlice> slices = new ArrayList<>(4);
        StringBuilder pendingLeading = new StringBuilder();
        int authoredIdx = 0;

        while (cursor != null && cursor != rootRBrace) {
            var elemType = cursor.getElementType();
            PsiElement psi = cursor.getPsi();
            if (elemType == TokenType.WHITE_SPACE || psi instanceof PsiWhiteSpace) {
                pendingLeading.append(cursor.getText());
                cursor = cursor.getTreeNext();
            } else if (elemType == StvnTypes.COMMENT || psi instanceof PsiComment) {
                pendingLeading.append(cursor.getText());
                cursor = cursor.getTreeNext();
            } else if (psi instanceof MetaEntry || psi instanceof DefsEntry
                    || psi instanceof TypeEntry || psi instanceof BodyEntry) {
                int rank = resolveCompletePrimaryRank(psi);
                if (rank < 0) {
                    return null;
                }
                if ((rank == 1 && seenMeta) || (rank == 2 && seenDefs)
                        || (rank == 3 && seenType) || (rank == 4 && seenBody)) {
                    return null;
                }
                seenMeta |= (rank == 1);
                seenDefs |= (rank == 2);
                seenType |= (rank == 3);
                seenBody |= (rank == 4);
                if (rank < maxRank) {
                    outOfOrder = true;
                }
                maxRank = Math.max(maxRank, rank);

                String trailingComment = extractSameLineTrailingComment(cursor);
                slices.add(new SectionSlice(
                    rank,
                    authoredIdx++,
                    normalizeLeadingSectionTrivia(pendingLeading.toString()),
                    cursor.getText(),
                    trailingComment
                ));
                pendingLeading.setLength(0);
                cursor = skipSameLineTrailingCommentNodes(cursor);
            } else if (psi instanceof PsiErrorElement) {
                return null;
            } else {
                return null;
            }
        }

        if (!outOfOrder || slices.size() < 2) {
            return null;
        }

        String footerCommentsAndIndent = normalizeFooterTrivia(pendingLeading.toString());
        slices.sort(Comparator.comparingInt(SectionSlice::canonicalRank)
            .thenComparingInt(SectionSlice::authoredIndex));

        StringBuilder out = new StringBuilder();
        out.append("{");
        if (rootOpenSameLineComment != null) {
            out.append(rootOpenSameLineComment);
        }
        out.append("\n");
        for (int i = 0; i < slices.size(); i++) {
            SectionSlice slice = slices.get(i);
            String lead = slice.leadingTriviaAndComments();
            if (i == 0 && lead.startsWith("\n")) {
                lead = lead.substring(1);
            }
            out.append(lead);
            out.append(slice.sectionRawText());
            if (slice.sameLineTrailingComment() != null) {
                out.append(slice.sameLineTrailingComment());
            }
            out.append("\n");
        }
        out.append(footerCommentsAndIndent);
        out.append("}");
        return out.toString();
    }

    private static int resolveCompletePrimaryRank(PsiElement section) {
        if (section instanceof MetaEntry meta) {
            MetaBlock block = meta.getMetaBlock();
            if (block == null
                    || block.getNode().findChildByType(StvnTypes.LBRACE) == null
                    || block.getNode().findChildByType(StvnTypes.RBRACE) == null) {
                return -1;
            }
            return 1;
        }
        if (section instanceof DefsEntry defs) {
            if (defs.getNode().findChildByType(StvnTypes.LBRACE) == null
                    || defs.getNode().findChildByType(StvnTypes.RBRACE) == null) {
                return -1;
            }
            return 2;
        }
        if (section instanceof TypeEntry type) {
            if (type.getSchemaType() == null || type.getMetadataMap() != null) {
                return -1;
            }
            return 3;
        }
        if (section instanceof BodyEntry body) {
            if (body.getValue() == null) {
                return -1;
            }
            return 4;
        }
        return -1;
    }

    private static String normalizeLeadingSectionTrivia(String rawTrivia) {
        if (rawTrivia.isEmpty()) {
            return "  ";
        }
        String normalized = rawTrivia.replace("\r\n", "\n");
        if (normalized.startsWith("\n")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceAll("\n{3,}", "\n\n");
        if (normalized.isBlank() && !normalized.contains("\n")) {
            return "  ";
        }
        if (normalized.isEmpty()) {
            return "  ";
        }
        return normalized;
    }

    private static String normalizeFooterTrivia(String rawFooter) {
        if (rawFooter.isEmpty()) {
            return "";
        }
        String normalized = rawFooter.replace("\r\n", "\n");
        if (normalized.startsWith("\n")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return normalized.contains("\n") ? "" : normalized;
        }
        return normalized.endsWith("\n") ? normalized : normalized + "\n";
    }

    private static @Nullable ASTNode findLastDirectRBrace(ASTNode fileNode) {
        ASTNode last = null;
        for (ASTNode c = fileNode.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getElementType() == StvnTypes.RBRACE) {
                last = c;
            }
        }
        return last;
    }

    private static @Nullable String extractSameLineTrailingComment(ASTNode anchor) {
        StringBuilder ws = new StringBuilder();
        for (ASTNode next = anchor.getTreeNext(); next != null; next = next.getTreeNext()) {
            var type = next.getElementType();
            if (type == TokenType.WHITE_SPACE) {
                String text = next.getText();
                if (text.contains("\n")) {
                    return null;
                }
                ws.append(text);
            } else if (type == StvnTypes.COMMENT) {
                String prefix = ws.isEmpty() ? " " : ws.toString();
                return prefix + next.getText().trim();
            } else {
                return null;
            }
        }
        return null;
    }

    private static @Nullable ASTNode skipSameLineTrailingCommentNodes(ASTNode anchor) {
        ASTNode scan = anchor.getTreeNext();
        ASTNode candidateAfterComment = null;
        for (ASTNode cur = scan; cur != null; cur = cur.getTreeNext()) {
            var type = cur.getElementType();
            if (type == TokenType.WHITE_SPACE) {
                if (cur.getText().contains("\n")) {
                    break;
                }
            } else if (type == StvnTypes.COMMENT) {
                candidateAfterComment = cur.getTreeNext();
                break;
            } else {
                break;
            }
        }
        return candidateAfterComment != null ? candidateAfterComment : scan;
    }

    private static boolean replaceRootInteriorInPlace(ASTNode targetFileNode, ASTNode replacementFileNode) {
        ASTNode targetLBrace = targetFileNode.findChildByType(StvnTypes.LBRACE);
        ASTNode targetRBrace = findLastDirectRBrace(targetFileNode);
        ASTNode replLBrace = replacementFileNode.findChildByType(StvnTypes.LBRACE);
        ASTNode replRBrace = findLastDirectRBrace(replacementFileNode);
        if (targetLBrace == null || targetRBrace == null || replLBrace == null || replRBrace == null) {
            return false;
        }
        ASTNode cur = targetLBrace.getTreeNext();
        while (cur != null && cur != targetRBrace) {
            ASTNode next = cur.getTreeNext();
            targetFileNode.removeChild(cur);
            cur = next;
        }
        ASTNode replChild = replLBrace.getTreeNext();
        while (replChild != null && replChild != replRBrace) {
            targetFileNode.addChild(replChild.copyElement(), targetRBrace);
            replChild = replChild.getTreeNext();
        }
        return true;
    }
}
