package org.stvnadore.plugin.validation.quickfix;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.StvnTypes;

import java.io.IOException;
import java.util.List;

/**
 * Quick-fix converting a textual hermetic unit ({@code .stvn} / {@code .stvn_d}) to modular format
 * ({@code .stvn_i} / {@code .stvn_di}) while updating or synthesizing a canonical {@code :meta} section.
 */
@NullMarked
public final class StvnConvertToModularQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement implements PriorityAction {

    private static final String RESERVED_DOMAIN_PREFIX = "stvn_";

    @SafeFieldForPreview
    private final boolean isDefsSchema;

    /**
     * Constructs a modular format conversion quick-fix.
     *
     * @param element offending PSI anchor element (such as {@code IncludeStmt}, {@code UseTarget}, or {@code TypeKeyword})
     * @param isDefsSchema true for .stvn_d to .stvn_di; false for .stvn to .stvn_i
     */
    public StvnConvertToModularQuickFix(PsiElement element, boolean isDefsSchema) {
        super(element);
        this.isDefsSchema = isDefsSchema;
    }

    /**
     * Computes the target modular filename ({@code <stem>.stvn_i} or {@code <stem>.stvn_di}) for a
     * textual hermetic filename, returning {@code null} for binary ({@code .stvn_b}, {@code .stvn_bd})
     * or non-convertible filenames.
     *
     * @param fileName source filename
     * @return modular target filename, or {@code null} when conversion is unsupported
     */
    public static @Nullable String computeTargetFileName(String fileName) {
        if (fileName.endsWith(".stvn_d")) {
            String stem = fileName.substring(0, fileName.length() - 7);
            return stem.isEmpty() ? null : stem + ".stvn_di";
        }
        if (fileName.endsWith(".stvn")) {
            String stem = fileName.substring(0, fileName.length() - 5);
            return stem.isEmpty() ? null : stem + ".stvn_i";
        }
        return null;
    }

    /**
     * Checks whether {@code file} is a textual hermetic {@code .stvn} or {@code .stvn_d} unit whose
     * target modular filename does not already exist in the same directory.
     *
     * @param file candidate PSI file, or {@code null}
     * @return {@code true} when modular conversion is valid and collision-free
     */
    public static boolean canConvertToModular(@Nullable PsiFile file) {
        if (file == null || !file.isValid()) {
            return false;
        }
        PsiFile originalFile = file.getOriginalFile();
        String oldName = originalFile.getName();
        String targetFileName = computeTargetFileName(oldName);
        if (targetFileName == null) {
            return false;
        }
        VirtualFile vf = originalFile.getVirtualFile();
        if (vf == null) {
            vf = file.getVirtualFile();
        }
        if (vf != null) {
            if (!vf.isValid()) {
                return false;
            }
            VirtualFile parent = vf.getParent();
            if (parent != null && parent.findChild(targetFileName) != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.LOW;
    }

    @Override
    public @NotNull String getText() {
        return isDefsSchema
            ? "Convert schema to modular format (.stvn_di)"
            : "Convert document to modular format (.stvn_i)";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "STVN Convert to Modular Format";
    }

    @Override
    public boolean isAvailable(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        if (!startElement.isValid() || !canConvertToModular(file)) {
            return false;
        }
        String oldName = file.getOriginalFile().getName();
        return isDefsSchema ? oldName.endsWith(".stvn_d") : oldName.endsWith(".stvn");
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file
    ) {
        return buildModularPreview(file);
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project,
            @NotNull ProblemDescriptor previewDescriptor
    ) {
        PsiElement element = previewDescriptor.getPsiElement();
        return buildModularPreview(element != null ? element.getContainingFile() : null);
    }

    private @NotNull IntentionPreviewInfo buildModularPreview(@Nullable PsiFile previewFile) {
        if (previewFile == null || !previewFile.isValid()) {
            return IntentionPreviewInfo.EMPTY;
        }
        PsiFile originalFile = previewFile.getOriginalFile();
        String oldName = originalFile.getName();
        if ((isDefsSchema && !oldName.endsWith(".stvn_d"))
            || (!isDefsSchema && !oldName.endsWith(".stvn"))
            || !canConvertToModular(originalFile)) {
            return IntentionPreviewInfo.EMPTY;
        }
        String newName = computeTargetFileName(oldName);
        if (newName == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        String beforeText = previewFile.getText();
        TextPatch patch = computeModularMetaPatch(previewFile, oldName, isDefsSchema);
        if (patch != null) {
            String afterText = beforeText.substring(0, patch.startOffset())
                + patch.replacement()
                + beforeText.substring(patch.endOffset());
            if (!beforeText.equals(afterText)) {
                return new IntentionPreviewInfo.CustomDiff(
                    previewFile.getFileType(),
                    oldName + " -> " + newName,
                    beforeText,
                    afterText
                );
            }
        }
        VirtualFile vFile = originalFile.getVirtualFile();
        if (vFile == null) {
            vFile = previewFile.getVirtualFile();
        }
        return vFile != null ? IntentionPreviewInfo.rename(vFile, newName) : IntentionPreviewInfo.EMPTY;
    }

    @Override
    public void invoke(
            @NotNull Project project,
            @NotNull PsiFile file,
            @Nullable Editor editor,
            @NotNull PsiElement startElement,
            @NotNull PsiElement endElement
    ) {
        VirtualFile vf = file.getVirtualFile();
        if (vf == null || !vf.isValid() || !canConvertToModular(file)) {
            return;
        }
        String oldName = file.getName();
        if ((isDefsSchema && !oldName.endsWith(".stvn_d")) || (!isDefsSchema && !oldName.endsWith(".stvn"))) {
            return;
        }
        String newName = computeTargetFileName(oldName);
        if (newName == null) {
            return;
        }

        applyModularMetaUpdate(project, file, oldName, isDefsSchema);

        try {
            vf.rename(this, newName);
        } catch (IOException e) {
            return;
        }
    }

    private record TextPatch(int startOffset, int endOffset, String replacement) {}

    private static void applyModularMetaUpdate(
            Project project,
            PsiFile file,
            String oldName,
            boolean isDefsSchema
    ) {
        Document doc = file.getViewProvider().getDocument();
        if (doc == null) {
            doc = PsiDocumentManager.getInstance(project).getDocument(file);
        }
        if (doc == null) {
            return;
        }
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        if (file.isPhysical()) {
            docManager.doPostponedOperationsAndUnblockDocument(doc);
        }
        docManager.commitDocument(doc);

        TextPatch patch = computeModularMetaPatch(file, oldName, isDefsSchema);
        if (patch == null) {
            return;
        }
        if (patch.startOffset() == patch.endOffset()) {
            doc.insertString(patch.startOffset(), patch.replacement());
        } else {
            doc.replaceString(patch.startOffset(), patch.endOffset(), patch.replacement());
        }
        docManager.commitDocument(doc);
    }

    private static @Nullable TextPatch computeModularMetaPatch(
            PsiFile file,
            String oldName,
            boolean isDefsSchema
    ) {
        String targetKind = isDefsSchema
            ? StvnVocabulary.KIND_KW_DEFS_INCLUDE
            : StvnVocabulary.KIND_KW_BODY_INCLUDE;
        CharSequence chars = file.getViewProvider().getContents();

        MetaEntry primaryMeta = findPrimaryMetaEntry(file);
        if (primaryMeta != null) {
            MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(primaryMeta, MetaKindFacet.class);
            if (kindFacet != null) {
                MetaKindValue kindValue = kindFacet.getMetaKindValue();
                if (kindValue != null) {
                    return new TextPatch(
                        kindValue.getTextRange().getStartOffset(),
                        kindValue.getTextRange().getEndOffset(),
                        targetKind
                    );
                }
                return new TextPatch(
                    kindFacet.getTextRange().getStartOffset(),
                    kindFacet.getTextRange().getEndOffset(),
                    StvnVocabulary.FACET_KW_META_KIND + " " + targetKind
                );
            }

            MetaBlock metaBlock = primaryMeta.getMetaBlock();
            if (metaBlock != null) {
                ASTNode lBrace = metaBlock.getNode().findChildByType(StvnTypes.LBRACE);
                ASTNode rBrace = metaBlock.getNode().findChildByType(StvnTypes.RBRACE);
                if (lBrace != null && rBrace != null) {
                    List<MetaElement> elements = metaBlock.getMetaElementList();
                    boolean hasComments = PsiTreeUtil.findChildOfType(metaBlock, PsiComment.class) != null;
                    String metaIndent = detectLineIndent(chars, primaryMeta.getTextRange().getStartOffset(), "  ");
                    String facetIndent = detectFirstMetaElementIndent(chars, elements, metaIndent + "  ");
                    if (elements.isEmpty() && !hasComments) {
                        String interior = buildSynthesizedMetaInterior(oldName, targetKind, facetIndent, metaIndent);
                        return new TextPatch(lBrace.getStartOffset() + 1, rBrace.getStartOffset(), interior);
                    }
                    if (metaBlock.getText().indexOf('\n') >= 0) {
                        int rBraceOffset = rBrace.getStartOffset();
                        int rBraceLineStart = rBraceOffset;
                        while (rBraceLineStart > 0
                            && (chars.charAt(rBraceLineStart - 1) == ' ' || chars.charAt(rBraceLineStart - 1) == '\t')) {
                            rBraceLineStart--;
                        }
                        if (rBraceLineStart == 0 || chars.charAt(rBraceLineStart - 1) == '\n') {
                            return new TextPatch(
                                rBraceLineStart,
                                rBraceLineStart,
                                facetIndent + StvnVocabulary.FACET_KW_META_KIND + " " + targetKind + "\n"
                            );
                        }
                        return new TextPatch(
                            rBraceOffset,
                            rBraceOffset,
                            "\n" + facetIndent + StvnVocabulary.FACET_KW_META_KIND + " " + targetKind + "\n" + metaIndent
                        );
                    }
                    int rBraceOffset = rBrace.getStartOffset();
                    String prefix = rBraceOffset > 0 && chars.charAt(rBraceOffset - 1) == ' ' ? "" : " ";
                    return new TextPatch(
                        rBraceOffset,
                        rBraceOffset,
                        prefix + StvnVocabulary.FACET_KW_META_KIND + " " + targetKind + " "
                    );
                }
            }
            return null;
        }

        PsiElement rootLBrace = StvnMetaTemplateUtil.findRootOpeningBrace(file);
        if (rootLBrace == null || !StvnVocabulary.DELIM_OPEN_BRACE.equals(rootLBrace.getText())) {
            return null;
        }
        String synthesizedMeta = buildSynthesizedMetaSection(oldName, targetKind);
        int afterBrace = rootLBrace.getTextRange().getEndOffset();
        int scan = afterBrace;
        while (scan < chars.length() && (chars.charAt(scan) == ' ' || chars.charAt(scan) == '\t')) {
            scan++;
        }
        if (scan + 1 < chars.length() && chars.charAt(scan) == '/' && chars.charAt(scan + 1) == '/') {
            while (scan < chars.length() && chars.charAt(scan) != '\n' && chars.charAt(scan) != '\r') {
                scan++;
            }
        }
        if (scan < chars.length() && (chars.charAt(scan) == '\n' || chars.charAt(scan) == '\r')) {
            int nextLineStart = (chars.charAt(scan) == '\r' && scan + 1 < chars.length() && chars.charAt(scan + 1) == '\n')
                ? scan + 2
                : scan + 1;
            return new TextPatch(nextLineStart, nextLineStart, synthesizedMeta);
        }
        return new TextPatch(afterBrace, afterBrace, "\n" + synthesizedMeta);
    }

    private static @Nullable MetaEntry findPrimaryMetaEntry(PsiFile file) {
        for (MetaEntry metaEntry : PsiTreeUtil.findChildrenOfType(file, MetaEntry.class)) {
            if (!StvnMetaTemplateUtil.isInSecondaryRootSection(metaEntry)) {
                return metaEntry;
            }
        }
        return null;
    }

    private static String detectLineIndent(CharSequence chars, int offset, String fallback) {
        int lineStart = offset;
        while (lineStart > 0 && (chars.charAt(lineStart - 1) == ' ' || chars.charAt(lineStart - 1) == '\t')) {
            lineStart--;
        }
        if (lineStart == 0 || chars.charAt(lineStart - 1) == '\n') {
            return chars.subSequence(lineStart, offset).toString();
        }
        return fallback;
    }

    private static String detectFirstMetaElementIndent(
            CharSequence chars,
            List<MetaElement> elements,
            String fallback
    ) {
        if (elements.isEmpty()) {
            return fallback;
        }
        return detectLineIndent(chars, elements.get(0).getTextRange().getStartOffset(), fallback);
    }

    private static String buildSynthesizedMetaInterior(
            String oldFileName,
            String targetKind,
            String facetIndent,
            String closingIndent
    ) {
        int lastDot = oldFileName.lastIndexOf('.');
        String stemPart = lastDot > 0 ? oldFileName.substring(0, lastDot) : oldFileName;
        String[] dotParts = stemPart.split("\\.", -1);
        String slot0 = dotParts.length > 0 && !dotParts[0].isEmpty() ? dotParts[0] : "document";
        String slot1 = (dotParts.length >= 2 && !dotParts[1].isEmpty() && !dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX))
            ? dotParts[1]
            : null;

        StringBuilder sb = new StringBuilder();
        sb.append("\n")
            .append(facetIndent)
            .append(StvnVocabulary.FACET_KW_META_NAME)
            .append(" \"")
            .append(slot0)
            .append("\"\n");
        if (slot1 != null) {
            sb.append(facetIndent)
                .append(StvnVocabulary.FACET_KW_META_DOMAIN)
                .append(" \"")
                .append(slot1)
                .append("\"\n");
        }
        sb.append(facetIndent)
            .append(StvnVocabulary.FACET_KW_META_KIND)
            .append(" ")
            .append(targetKind)
            .append("\n")
            .append(closingIndent);
        return sb.toString();
    }

    private static String buildSynthesizedMetaSection(String oldFileName, String targetKind) {
        return "  " + StvnVocabulary.KEYWORD_META + " " + StvnVocabulary.DELIM_OPEN_BRACE
            + buildSynthesizedMetaInterior(oldFileName, targetKind, "    ", "  ")
            + StvnVocabulary.DELIM_CLOSE_BRACE + "\n";
    }
}
