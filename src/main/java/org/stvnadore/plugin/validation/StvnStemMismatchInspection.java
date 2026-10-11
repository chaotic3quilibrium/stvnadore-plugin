package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.Visitor;

/**
 * Validates that declared #name in the root :meta block matches the file stem slot 0
 * (WARN_DOCUMENT_NAME_MISMATCH) and declared #domain matches file stem slot 1
 * (WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME).
 */
@NullMarked
public final class StvnStemMismatchInspection extends LocalInspectionTool {

    /**
     * Constructs a new StvnStemMismatchInspection instance.
     */
    public StvnStemMismatchInspection() {}

    @Override
    public @NotNull String getShortName() {
        return "StvnStemMismatch";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Document stem mismatch inspection";
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
            public void visitMetaNameFacet(@NotNull MetaNameFacet facet) {
                super.visitMetaNameFacet(facet);
                var file = facet.getContainingFile();
                if (file == null) return;
                var fileName = file.getName();
                String[] parts = fileName.split("\\.");
                if (parts.length == 0) return;
                String stem = parts[0];

                StringLiteral stringLit = facet.getStringLiteral();
                if (stringLit == null) return;
                String rawText = stringLit.getText();
                String declared = stripQuotes(rawText);

                if (!stem.equals(declared)) {
                    holder.registerProblem(
                        stringLit,
                        "Document #name '" + declared + "' does not match file stem '" + stem + "' (WARN_DOCUMENT_NAME_MISMATCH).",
                        ProblemHighlightType.WARNING,
                        new AlignStemNameQuickFix(stem)
                    );
                }
            }

            @Override
            public void visitMetaDomainFacet(@NotNull MetaDomainFacet facet) {
                super.visitMetaDomainFacet(facet);
                var file = facet.getContainingFile();
                if (file == null) return;
                var fileName = file.getName();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot < 0) return;
                var ext = fileName.substring(lastDot);
                var stemPart = fileName.substring(0, lastDot);
                var dotParts = stemPart.split("\\.");
                if (dotParts.length == 0) return;

                var stringLit = facet.getStringLiteral();
                if (stringLit == null) return;
                var declared = stripQuotes(stringLit.getText());

                if (dotParts.length == 1) {
                    var targetFileName = dotParts[0] + "." + declared + ext;
                    holder.registerProblem(
                        stringLit,
                        "Declared #domain '" + declared + "' is omitted from filename stem slot 1 (WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME).",
                        ProblemHighlightType.WARNING,
                        new IncludeDomainInFilenameQuickFix(stringLit, targetFileName),
                        new RemoveMetaDomainQuickFix(facet)
                    );
                } else {
                    var stemDomain = dotParts[1];
                    if (!stemDomain.equals(declared)) {
                        var targetFileName = dotParts[0] + "." + declared;
                        for (int i = 2; i < dotParts.length; i++) {
                            targetFileName += "." + dotParts[i];
                        }
                        targetFileName += ext;
                        holder.registerProblem(
                            stringLit,
                            "Declared #domain '" + declared + "' does not match filename stem slot 1 '" + stemDomain + "' (WARN_DOCUMENT_NAME_MISMATCH).",
                            ProblemHighlightType.WARNING,
                            new IncludeDomainInFilenameQuickFix(stringLit, targetFileName),
                            new RemoveMetaDomainQuickFix(facet)
                        );
                    }
                }
            }
        };
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Quick-fix updating #name string literal to match the file stem.
     */
    public static final class AlignStemNameQuickFix implements LocalQuickFix {
        private final String stem;

        public AlignStemNameQuickFix(String stem) {
            this.stem = stem;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Align #name with file stem";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            Document doc = PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile());
            if (doc != null) {
                var range = element.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), "\"" + stem + "\"");
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }

    /**
     * Quick-fix renaming the active file to include the declared domain in filename stem slot 1.
     */
    public static final class IncludeDomainInFilenameQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {
        private final String targetFileName;

        public IncludeDomainInFilenameQuickFix(@NotNull PsiElement element, String targetFileName) {
            super(element);
            this.targetFileName = targetFileName;
        }

        @Override
        public @NotNull String getText() {
            return "Rename file to '" + targetFileName + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Rename file to include domain in stem slot 1";
        }

        @Override
        public boolean isAvailable(@NotNull Project project,
                                   @NotNull PsiFile file,
                                   @NotNull PsiElement startElement,
                                   @NotNull PsiElement endElement) {
            return startElement.isValid();
        }

        @Override
        public void invoke(@NotNull Project project,
                           @NotNull PsiFile file,
                           @Nullable Editor editor,
                           @NotNull PsiElement startElement,
                           @NotNull PsiElement endElement) {
            try {
                file.setName(targetFileName);
            } catch (com.intellij.util.IncorrectOperationException ignored) {
            }
        }
    }

    /**
     * Quick-fix removing the optional #domain facet from the root :meta block.
     */
    public static final class RemoveMetaDomainQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {
        public RemoveMetaDomainQuickFix(@NotNull PsiElement element) {
            super(element);
        }

        @Override
        public @NotNull String getText() {
            return "Remove optional #domain attribute";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove optional #domain attribute";
        }

        @Override
        public boolean isAvailable(@NotNull Project project,
                                   @NotNull PsiFile file,
                                   @NotNull PsiElement startElement,
                                   @NotNull PsiElement endElement) {
            return startElement.isValid();
        }

        @Override
        public void invoke(@NotNull Project project,
                           @NotNull PsiFile file,
                           @Nullable Editor editor,
                           @NotNull PsiElement startElement,
                           @NotNull PsiElement endElement) {
            MetaDomainFacet facet = startElement instanceof MetaDomainFacet
                ? (MetaDomainFacet) startElement
                : PsiTreeUtil.getParentOfType(startElement, MetaDomainFacet.class, false);
            if (facet == null) {
                facet = PsiTreeUtil.findChildOfType(file, MetaDomainFacet.class);
            }
            if (facet == null) return;
            PsiElement target = facet.getParent() instanceof MetaElement ? facet.getParent() : facet;
            var prev = target.getPrevSibling();
            if (prev instanceof PsiWhiteSpace) {
                prev.delete();
            }
            target.delete();
        }
    }
}
