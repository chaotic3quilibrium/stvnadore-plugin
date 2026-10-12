package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnFile;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaKindValue;
import org.stvnadore.psi.Visitor;

/**
 * Validates presence of root :meta blocks on definitions schemas (.stvn_d, .stvn_di, .stvn_bd)
 * and modular payload documents (.stvn_i, .stvn_b), verifying that #kind matches the document
 * file extension (ERR_DOCUMENT_KIND_MISMATCH).
 */
@NullMarked
public final class StvnMissingMetaInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnMissingMetaInspection instance.
     */
    public StvnMissingMetaInspection() {}

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
            @Override
            public void visitFile(@NotNull PsiFile file) {
                super.visitFile(file);
                if (!(file instanceof StvnFile)) {
                    return;
                }
                var fileName = file.getName();
                if (!StvnMetaTemplateUtil.isMetaMandatory(fileName)) {
                    return;
                }

                var metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
                if (metaEntry == null) {
                    String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);
                    String message;
                    if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd") || fileName.endsWith(".stvn_di")) {
                        message = StvnDiagnosticFormatter.formatPlugin(
                            "Definitions schema requires root :meta block",
                            StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                        );
                    } else {
                        message = StvnDiagnosticFormatter.formatPlugin(
                            "Payload include document requires root :meta block",
                            StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                        );
                    }
                    PsiElement anchor = StvnMetaTemplateUtil.findRootOpeningBrace(file);
                    var targetElement = anchor != null ? anchor : file;
                    holder.registerProblem(
                        targetElement,
                        message,
                        ProblemHighlightType.GENERIC_ERROR,
                        new InsertMissingMetaBlockQuickFix(expectedKind)
                    );
                } else {
                    var kindFacet = PsiTreeUtil.findChildOfType(metaEntry, MetaKindFacet.class);
                    if (kindFacet == null) {
                        String expectedKind = StvnMetaTemplateUtil.resolveExpectedKind(fileName);
                        String message = StvnDiagnosticFormatter.formatPlugin(
                            "Root :meta block requires #kind facet matching file extension",
                            StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
                        );
                        MetaBlock metaBlock = PsiTreeUtil.findChildOfType(metaEntry, MetaBlock.class);
                        PsiElement targetElement = metaBlock != null ? metaBlock : metaEntry;
                        holder.registerProblem(
                            targetElement,
                            message,
                            ProblemHighlightType.GENERIC_ERROR,
                            new InsertMissingMetaKindFacetQuickFix(expectedKind)
                        );
                    }
                }
            }

            @Override
            public void visitMetaKindFacet(@NotNull MetaKindFacet facet) {
                super.visitMetaKindFacet(facet);
                var file = facet.getContainingFile();
                if (file == null) return;
                var fileName = file.getName();
                String expected = null;
                if (fileName.endsWith(".stvn_d") || fileName.endsWith(".stvn_bd")) {
                    expected = "#DEFS";
                } else if (fileName.endsWith(".stvn_di")) {
                    expected = "#DEFS_INCLUDE";
                } else if (fileName.endsWith(".stvn_i")) {
                    expected = "#BODY_INCLUDE";
                } else if (fileName.endsWith(".stvn") || fileName.endsWith(".stvn_b")) {
                    expected = "#BODY";
                }

                if (expected != null) {
                    var kindValue = facet.getMetaKindValue();
                    String actual = kindValue != null ? kindValue.getText().trim() : "";
                    if (!expected.equals(actual)) {
                        holder.registerProblem(
                            facet,
                            StvnDiagnosticFormatter.formatPlugin("Document kind mismatch: expected " + expected + " for file extension", StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH),
                            ProblemHighlightType.GENERIC_ERROR,
                            new UpdateMetaKindQuickFix(expected)
                        );
                    }
                }
            }
        };
    }

    /**
     * Quick-fix inserting a canonical :meta block into a definitions schema.
     */
    public static final class InsertMissingMetaBlockQuickFix implements LocalQuickFix {
        private final String kind;

        public InsertMissingMetaBlockQuickFix(String kind) {
            this.kind = kind;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert missing root :meta block";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            PsiFile file = element.getContainingFile();
            if (file == null) return;
            StvnMetaTemplateUtil.insertCanonicalMetaBlock(project, file, null);
        }
    }

    /**
     * Quick-fix inserting missing #kind facet into existing :meta block.
     */
    public static final class InsertMissingMetaKindFacetQuickFix implements LocalQuickFix {
        private final String expectedKind;

        public InsertMissingMetaKindFacetQuickFix(String expectedKind) {
            this.expectedKind = expectedKind;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert missing #kind facet";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            PsiFile file = element.getContainingFile();
            if (file == null) return;
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc == null) return;

            int offset = element.getTextRange().getStartOffset() + 1;
            doc.insertString(offset, "\n    #kind " + expectedKind);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(element);
        }
    }

    /**
     * Quick-fix updating #kind literal to match active file extension.
     */
    public static final class UpdateMetaKindQuickFix implements LocalQuickFix {
        private final String expectedKind;

        public UpdateMetaKindQuickFix(String expectedKind) {
            this.expectedKind = expectedKind;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Update #kind to match file extension";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (!(element instanceof MetaKindFacet)) return;
            var kindFacet = (MetaKindFacet) element;
            var kindValue = kindFacet.getMetaKindValue();
            if (kindValue == null) return;
            Document doc = PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile());
            if (doc != null) {
                var range = kindValue.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), expectedKind);
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }
}
