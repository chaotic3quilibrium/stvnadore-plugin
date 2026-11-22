package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.RenameFileExtensionToKindQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnFileRenameExecutor;
import org.stvnadore.plugin.validation.quickfix.StvnMatchMetaFileRenameQuickFix;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.Visitor;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates presence of root {@code :meta} blocks on definitions schemas ({@code .stvn_d}, {@code .stvn_di}, {@code .stvn_bd})
 * and modular payload documents ({@code .stvn_i}, {@code .stvn_b}), verifying that {@code #kind} matches the document
 * file extension ({@code ERR_DOCUMENT_KIND_MISMATCH}).
 */
@NullMarked
public final class StvnMissingMetaInspection extends LocalInspectionTool {

    /**
     * Constructs a new {@link StvnMissingMetaInspection} instance.
     */
    public StvnMissingMetaInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnMissingMeta";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Missing root metadata block inspection";
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "STVN";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            private boolean rootProblemRegistered = false;

            @Override
            public void visitFile(@NotNull PsiFile file) {
                super.visitFile(file);
                if (file instanceof StvnFile && !rootProblemRegistered) {
                    checkRootMeta(file, holder);
                }
            }

            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);
                if (!rootProblemRegistered && "{".equals(element.getText())) {
                    var file = element.getContainingFile();
                    if (file instanceof StvnFile && element == StvnMetaTemplateUtil.findRootOpeningBrace(file)) {
                        checkRootMeta(file, holder);
                    }
                }
            }

            private void checkRootMeta(PsiFile file, ProblemsHolder holder) {
                if (rootProblemRegistered) {
                    return;
                }
                if (file.getTextLength() == 0 || file.getText().isBlank()) {
                    return;
                }
                var fileName = file.getName();
                if (!StvnMetaTemplateUtil.isMetaExtensionMandatory(fileName)) {
                    return;
                }

                var metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
                if (metaEntry == null && !StvnMetaTemplateUtil.hasMetaHeader(file)) {
                    rootProblemRegistered = true;
                    PsiElement anchor = StvnMetaTemplateUtil.findRootOpeningBrace(file);
                    String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);
                    String message;
                    if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd") || fileName.endsWith(".stvn_di")) {
                        message = StvnDiagnosticFormatter.formatPlugin(
                            "Definitions schema requires root " + StvnVocabulary.KEYWORD_META + " block",
                            StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                        );
                    } else {
                        message = StvnDiagnosticFormatter.formatPlugin(
                            "Payload include document requires root " + StvnVocabulary.KEYWORD_META + " block",
                            StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                        );
                    }
                    if (anchor != null && anchor.getTextRange() != null && !anchor.getTextRange().isEmpty()) {
                        holder.registerProblem(
                            anchor,
                            message,
                            new InsertMissingMetaBlockQuickFix(expectedKind)
                        );
                    } else if (file.getTextLength() > 0) {
                        int textLen = file.getTextLength();
                        holder.registerProblem(
                            file,
                            new TextRange(0, Math.min(1, textLen)),
                            message,
                            new InsertMissingMetaBlockQuickFix(expectedKind)
                        );
                    }
                }
            }

            @Override
            public void visitMetaEntry(@NotNull MetaEntry metaEntry) {
                super.visitMetaEntry(metaEntry);
                if (!StvnMetaTemplateUtil.isPrimaryMetaEntry(metaEntry)) {
                    return;
                }
                MetaBlock metaBlock = metaEntry.getMetaBlock();
                if (metaBlock == null || metaBlock.getMetaElementList().isEmpty()) {
                    return;
                }
                var file = metaEntry.getContainingFile();
                if (file == null) {
                    return;
                }
                var fileName = file.getName();
                if (!StvnMetaTemplateUtil.isMetaExtensionMandatory(fileName)) {
                    return;
                }

                var kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
                if (kindFacet == null && !containsInFlightFacetTag(metaBlock, StvnVocabulary.FACET_KW_META_KIND)) {
                    String ext = StvnMetaTemplateUtil.resolveFileExtensionOrDefault(fileName, "");
                    String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);
                    String message = StvnDiagnosticFormatter.formatPlugin(
                        "Root '" + StvnVocabulary.KEYWORD_META + "' section requires "
                            + StvnVocabulary.FACET_KW_META_KIND + " facet matching file extension '" + ext
                            + "' (expected '" + expectedKind + "')",
                        StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                    );
                    ASTNode kwMetaNode = metaEntry.getNode().findChildByType(StvnTypes.KW_META);
                    PsiElement anchor = kwMetaNode != null ? kwMetaNode.getPsi() : metaBlock;
                    if (anchor.getTextRange() != null && !anchor.getTextRange().isEmpty()) {
                        holder.registerProblem(
                            anchor,
                            message,
                            ProblemHighlightType.GENERIC_ERROR,
                            new InsertMissingMetaKindFacetQuickFix(anchor, expectedKind)
                        );
                    }
                }
            }

            @Override
            public void visitMetaKindFacet(@NotNull MetaKindFacet facet) {
                super.visitMetaKindFacet(facet);
                if (!StvnMetaTemplateUtil.isPrimaryMetaEntry(facet)) {
                    return;
                }
                var file = facet.getContainingFile();
                if (file == null) {
                    return;
                }
                var fileName = file.getName();
                String expected = null;
                if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd")) {
                    expected = StvnVocabulary.KIND_KW_DEFS;
                } else if (fileName.endsWith(".stvn_di")) {
                    expected = StvnVocabulary.KIND_KW_DEFS_INCLUDE;
                } else if (fileName.endsWith(".stvn_i")) {
                    expected = StvnVocabulary.KIND_KW_BODY_INCLUDE;
                } else if (fileName.endsWith(".stvn") || fileName.endsWith(".stvn_b")) {
                    expected = StvnVocabulary.KIND_KW_BODY;
                }

                if (expected != null) {
                    var kindValue = facet.getMetaKindValue();
                    if (kindValue == null) {
                        return;
                    }
                    String actual = kindValue.getText().trim();
                    if (!expected.equals(actual)) {
                        if (facet.getTextRange() != null && !facet.getTextRange().isEmpty()) {
                            String ext = StvnMetaTemplateUtil.resolveFileExtensionOrDefault(fileName, "");
                            List<LocalQuickFix> fixes = new ArrayList<>();
                            var metaSummary = StvnFileRenameExecutor.resolveMetaIdentitySummary(file);
                            if (metaSummary != null && metaSummary.mismatchedComponentCount() >= 2) {
                                fixes.add(new StvnMatchMetaFileRenameQuickFix(facet, metaSummary.targetFileName()));
                            }
                            var extFix = RenameFileExtensionToKindQuickFix.createIfApplicable(file, facet);
                            if (extFix != null) {
                                fixes.add(extFix);
                            }
                            fixes.add(new UpdateMetaKindQuickFix(facet, expected));
                            holder.registerProblem(
                                facet,
                                StvnDiagnosticFormatter.formatPlugin(
                                    "Document kind '" + actual + "' does not match file extension '" + ext
                                        + "' (expected '" + expected + "')",
                                    StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                                ),
                                ProblemHighlightType.GENERIC_ERROR,
                                fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                            );
                        }
                    }
                }
            }
        };
    }

    private static boolean containsInFlightFacetTag(@NotNull MetaBlock metaBlock, @NotNull String tagText) {
        for (MetaElement element : metaBlock.getMetaElementList()) {
            var unknownFacet = element.getMetaUnknownFacet();
            if (unknownFacet != null && tagText.equals(unknownFacet.getValueKeyword().getText().trim())) {
                return true;
            }
        }
        for (PsiElement child = metaBlock.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (hasExactFacetTagPrefix(child.getText().trim(), tagText)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExactFacetTagPrefix(@NotNull String text, @NotNull String tagText) {
        if (!text.startsWith(tagText)) {
            return false;
        }
        if (text.length() == tagText.length()) {
            return true;
        }
        char boundary = text.charAt(tagText.length());
        return !Character.isLetterOrDigit(boundary) && boundary != '_';
    }

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file,
            @NotNull InspectionManager manager,
            boolean isOnTheFly
    ) {
        if (!(file instanceof StvnFile) || !StvnMetaTemplateUtil.isMetaExtensionMandatory(file.getName())) {
            return null;
        }
        if (isOnTheFly) {
            return null;
        }
        var metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
        if (metaEntry == null && !StvnMetaTemplateUtil.hasMetaHeader(file)) {
            String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(file.getName());
            String message = (file.getName().endsWith(".stvn_d") || file.getName().endsWith(".stvn_bd") || file.getName().endsWith(".stvn_di"))
                ? StvnDiagnosticFormatter.formatPlugin(
                    "Definitions schema requires root " + StvnVocabulary.KEYWORD_META + " block",
                    StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                )
                : StvnDiagnosticFormatter.formatPlugin(
                    "Payload include document requires root " + StvnVocabulary.KEYWORD_META + " block",
                    StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                );
            PsiElement anchor = StvnMetaTemplateUtil.findRootOpeningBrace(file);
            PsiElement target = anchor != null ? anchor : file;
            return new ProblemDescriptor[]{
                manager.createProblemDescriptor(
                    target,
                    message,
                    new InsertMissingMetaBlockQuickFix(expectedKind),
                    ProblemHighlightType.GENERIC_ERROR,
                    isOnTheFly
                )
            };
        }
        return null;
    }

    /**
     * Quick-fix inserting a canonical {@code :meta} block into a definitions schema or modular payload.
     */
    public static final class InsertMissingMetaBlockQuickFix implements LocalQuickFix, IntentionAction {

        /**
         * Constructs a canonical {@code :meta} block insertion quick-fix.
         *
         * @param expectedKind expected document {@code #kind} keyword
         */
        public InsertMissingMetaBlockQuickFix(String expectedKind) {
            // Expected kind is resolved dynamically from the target file in StvnMetaTemplateUtil
        }

        @Override
        public @NotNull String getText() {
            return getFamilyName();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert missing root " + StvnVocabulary.KEYWORD_META + " block";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            return true;
        }

        @Override
        public void invoke(@NotNull Project project, @Nullable Editor editor, @Nullable PsiFile file) {
            if (file != null) {
                StvnMetaTemplateUtil.insertCanonicalMetaBlock(project, file, editor);
            }
        }

        @Override
        public boolean startInWriteAction() {
            return true;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            PsiFile file = element.getContainingFile();
            if (file == null && element instanceof PsiFile pf) {
                file = pf;
            }
            if (file == null) {
                return;
            }
            StvnMetaTemplateUtil.insertCanonicalMetaBlock(project, file, null);
        }
    }

    /**
     * Quick-fix inserting a missing {@code #kind} facet into an existing {@code :meta} block.
     */
    public static final class InsertMissingMetaKindFacetQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String expectedKind;

        /**
         * Constructs a {@code #kind} insertion quick-fix anchored to {@code anchor}.
         *
         * @param anchor       owning {@code :meta} keyword or {@link MetaBlock}
         * @param expectedKind expected {@code #kind} keyword for the file extension
         */
        public InsertMissingMetaKindFacetQuickFix(@NotNull PsiElement anchor, String expectedKind) {
            super(anchor);
            this.expectedKind = expectedKind;
        }

        @Override
        public @NotNull String getText() {
            return getFamilyName();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert missing " + StvnVocabulary.FACET_KW_META_KIND + " facet";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public boolean isAvailable(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            return startElement.isValid();
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc == null) {
                return;
            }

            MetaEntry metaEntry = startElement instanceof MetaEntry me
                ? me
                : PsiTreeUtil.getParentOfType(startElement, MetaEntry.class, false);
            MetaBlock metaBlock = startElement instanceof MetaBlock mb
                ? mb
                : PsiTreeUtil.getParentOfType(startElement, MetaBlock.class, false);
            if (metaBlock == null && metaEntry != null) {
                metaBlock = metaEntry.getMetaBlock();
            }
            if (metaBlock == null || metaBlock.getTextRange() == null) {
                return;
            }
            ASTNode rbrace = metaBlock.getNode().findChildByType(StvnTypes.RBRACE);
            int insertOffset = rbrace != null ? rbrace.getStartOffset() : metaBlock.getTextRange().getEndOffset() - 1;
            doc.insertString(insertOffset, "\n    " + StvnVocabulary.FACET_KW_META_KIND + " " + expectedKind + "\n  ");
            PsiDocumentManager.getInstance(project).commitDocument(doc);
            if (metaEntry != null && metaEntry.isValid()) {
                CodeStyleManager.getInstance(project).reformat(metaEntry);
            } else if (metaBlock.isValid()) {
                CodeStyleManager.getInstance(project).reformat(metaBlock);
            }
        }
    }

    /**
     * Quick-fix ({@link PriorityAction.Priority#NORMAL}) updating the {@code #kind} value keyword
     * to match the active file extension.
     */
    public static final class UpdateMetaKindQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String expectedKind;

        /**
         * Constructs a {@code #kind} alignment quick-fix anchored to {@code kindFacet}.
         *
         * @param kindFacet    mismatching {@link MetaKindFacet}
         * @param expectedKind expected {@code #kind} keyword for the current file extension
         */
        public UpdateMetaKindQuickFix(@NotNull MetaKindFacet kindFacet, String expectedKind) {
            super(kindFacet);
            this.expectedKind = expectedKind;
        }

        @Override
        public @NotNull String getText() {
            return getFamilyName();
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Update " + StvnVocabulary.FACET_KW_META_KIND + " to match file extension";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.NORMAL;
        }

        @Override
        public boolean isAvailable(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            return startElement.isValid() && StvnFileRenameExecutor.isCaretWithinFacet(editor, startElement);
        }

        @Override
        public void invoke(
                @NotNull Project project,
                @NotNull PsiFile file,
                @Nullable Editor editor,
                @NotNull PsiElement startElement,
                @NotNull PsiElement endElement
        ) {
            MetaKindFacet kindFacet = startElement instanceof MetaKindFacet mkf
                ? mkf
                : PsiTreeUtil.getParentOfType(startElement, MetaKindFacet.class, false);
            if (kindFacet == null) {
                return;
            }
            var kindValue = kindFacet.getMetaKindValue();
            if (kindValue == null) {
                return;
            }
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc != null) {
                var range = kindValue.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), expectedKind);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }
}
