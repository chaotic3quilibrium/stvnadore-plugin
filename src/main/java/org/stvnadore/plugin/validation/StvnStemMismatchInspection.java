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
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnOmitDomainQuickFix;
import org.stvnadore.psi.MetaBlock;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaElement;
import org.stvnadore.psi.MetaEntry;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.StringLiteral;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.Visitor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates that declared #name in the root :meta block matches the file stem slot 0
 * (WARN_DOCUMENT_NAME_MISMATCH), declared #domain matches file stem slot 1
 * (WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME), and user documents do not declare reserved
 * system domains starting with "stvn_" (ERR_RESERVED_DOMAIN_PROHIBITED).
 */
@NullMarked
public final class StvnStemMismatchInspection extends LocalInspectionTool {

    private static final String RESERVED_DOMAIN_PREFIX = "stvn_";
    private static final Pattern POSIX_META_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

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
            public void visitMetaEntry(@NotNull MetaEntry entry) {
                super.visitMetaEntry(entry);
                if (!StvnMetaTemplateUtil.isPrimaryMetaEntry(entry)) {
                    return;
                }
                MetaBlock metaBlock = entry.getMetaBlock();
                if (metaBlock == null || isMetaBlockEmpty(metaBlock)) {
                    return;
                }
                if (hasDomainFacet(metaBlock) || containsInFlightFacetTag(metaBlock, StvnVocabulary.FACET_KW_META_DOMAIN)) {
                    return;
                }

                var file = entry.getContainingFile();
                if (file == null) return;
                var fileName = file.getName();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot < 0) return;
                var ext = fileName.substring(lastDot);
                var stemPart = fileName.substring(0, lastDot);
                var dotParts = stemPart.split("\\.", -1);
                if (dotParts.length == 2 && !dotParts[0].isEmpty() && !dotParts[1].isEmpty()) {
                    String slot0 = dotParts[0];
                    String slot1 = dotParts[1];
                    if (!slot1.startsWith(RESERVED_DOMAIN_PREFIX)) {
                        holder.registerProblem(
                            entry,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Filename stem slot 1 declares domain '" + slot1 + "', but "
                                    + StvnVocabulary.KEYWORD_META + " omits "
                                    + StvnVocabulary.FACET_KW_META_DOMAIN + " \"" + slot1 + "\"",
                                StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_META
                            ),
                            ProblemHighlightType.WARNING,
                            new InsertMetaDomainQuickFix(slot1),
                            new StvnOmitDomainQuickFix(slot0 + ext)
                        );
                    }
                }
            }

            @Override
            public void visitMetaNameFacet(@NotNull MetaNameFacet facet) {
                super.visitMetaNameFacet(facet);
                if (!StvnMetaTemplateUtil.isPrimaryMetaEntry(facet)) {
                    return;
                }
                var file = facet.getContainingFile();
                if (file == null) return;
                var fileName = file.getName();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot < 0) return;
                var ext = fileName.substring(lastDot);
                var stemPart = fileName.substring(0, lastDot);
                var dotParts = stemPart.split("\\.");
                if (dotParts.length == 0) return;
                String stem = dotParts[0];

                StringLiteral stringLit = facet.getStringLiteral();
                if (stringLit == null) return;
                String rawText = stringLit.getText();
                String declared = stripQuotes(rawText);

                if (!stem.equals(declared)) {
                    boolean isValidPosixName = POSIX_META_ID_PATTERN.matcher(declared).matches();
                    boolean hasReservedSlot1 = dotParts.length >= 2 && dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX);
                    List<LocalQuickFix> fixes = new ArrayList<>();
                    fixes.add(new AlignStemNameQuickFix(stem));
                    if (isValidPosixName && !hasReservedSlot1) {
                        var targetFileName = declared;
                        for (int i = 1; i < dotParts.length; i++) {
                            targetFileName += "." + dotParts[i];
                        }
                        targetFileName += ext;
                        fixes.add(new RenameFileToDeclaredNameQuickFix(stringLit, targetFileName));
                    }
                    PsiElement warnAnchor = resolveWarningAnchor(file, stringLit, facet, ! isValidPosixName);
                    holder.registerProblem(
                        warnAnchor,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Declared " + StvnVocabulary.FACET_KW_META_NAME + " '" + declared
                                + "' does not match filename stem slot 0 '" + stem + "'",
                            StvnDiagnosticCodes.WARN_DOCUMENT_NAME_MISMATCH
                        ),
                        ProblemHighlightType.WARNING,
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                }
            }

            @Override
            public void visitMetaDomainFacet(@NotNull MetaDomainFacet facet) {
                super.visitMetaDomainFacet(facet);
                if (!StvnMetaTemplateUtil.isPrimaryMetaEntry(facet)) {
                    return;
                }
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

                boolean isSystemFile = isSystemPreludeOrSubstrateFile(file);
                boolean isReservedDomain = declared.startsWith(RESERVED_DOMAIN_PREFIX);
                boolean isValidPosixDomain = POSIX_META_ID_PATTERN.matcher(declared).matches();
                boolean hasErrorOnStringLit = (isReservedDomain && !isSystemFile) || !isValidPosixDomain;

                if (isReservedDomain && !isSystemFile) {
                    List<LocalQuickFix> e038Fixes = new ArrayList<>();
                    if (dotParts.length >= 2 && !dotParts[1].isEmpty() && !dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX)) {
                        e038Fixes.add(new AlignDomainWithStemQuickFix(dotParts[1]));
                    }
                    e038Fixes.add(new RemoveMetaDomainQuickFix(facet));
                    e038Fixes.add(new StvnChangeReservedDomainQuickFix(stringLit));
                    holder.registerProblem(
                        stringLit,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Reserved system domain prohibited: User document cannot declare reserved system domain '"
                                + declared + "'; domains with prefix '" + RESERVED_DOMAIN_PREFIX
                                + "' are strictly reserved for system artifacts",
                            StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        e038Fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                }

                PsiElement warnAnchor = resolveWarningAnchor(file, stringLit, facet, hasErrorOnStringLit);
                if (dotParts.length == 1) {
                    var targetFileName = dotParts[0] + "." + declared + ext;
                    LocalQuickFix[] fixes = (!isReservedDomain && isValidPosixDomain)
                        ? new LocalQuickFix[]{
                            new IncludeDomainInFilenameQuickFix(stringLit, targetFileName),
                            new RemoveMetaDomainQuickFix(facet)
                        }
                        : new LocalQuickFix[]{
                            new RemoveMetaDomainQuickFix(facet)
                        };
                    holder.registerProblem(
                        warnAnchor,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Declared " + StvnVocabulary.FACET_KW_META_DOMAIN + " '" + declared
                                + "' is omitted from filename stem slot 1",
                            StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
                        ),
                        ProblemHighlightType.WARNING,
                        fixes
                    );
                } else {
                    var stemDomain = dotParts[1];
                    if (!stemDomain.equals(declared)) {
                        var targetFileName = dotParts[0] + "." + declared;
                        for (int i = 2; i < dotParts.length; i++) {
                            targetFileName += "." + dotParts[i];
                        }
                        targetFileName += ext;
                        List<LocalQuickFix> fixes = new ArrayList<>();
                        if (!stemDomain.isEmpty() && !stemDomain.startsWith(RESERVED_DOMAIN_PREFIX)) {
                            fixes.add(new AlignDomainWithStemQuickFix(stemDomain));
                        }
                        if (!isReservedDomain && isValidPosixDomain) {
                            fixes.add(new IncludeDomainInFilenameQuickFix(stringLit, targetFileName));
                        }
                        fixes.add(new RemoveMetaDomainQuickFix(facet));
                        holder.registerProblem(
                            warnAnchor,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Declared " + StvnVocabulary.FACET_KW_META_DOMAIN + " '" + declared
                                    + "' does not match filename stem slot 1 '" + stemDomain + "'",
                                StvnDiagnosticCodes.WARN_DOCUMENT_NAME_MISMATCH
                            ),
                            ProblemHighlightType.WARNING,
                            fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                        );
                    }
                }
            }
        };
    }

    private static PsiElement resolveWarningAnchor(
            @NotNull PsiFile file,
            @NotNull StringLiteral stringLit,
            @NotNull PsiElement facet,
            boolean hasErrorOnStringLit
    ) {
        MetaElement metaEl = PsiTreeUtil.getParentOfType(facet, MetaElement.class, false);
        MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class, false);
        if (metaEl != null && metaBlock != null
                && isMetaElementOutOfOrder(metaBlock, metaEl)
                && StvnDiagnosticAnnotatorMapping.isInspectionActive(file, "StvnMetadataOrder")) {
            return metaBlock;
        }
        return hasErrorOnStringLit ? facet : stringLit;
    }

    private static boolean isMetaElementOutOfOrder(@NotNull MetaBlock metaBlock, @NotNull MetaElement target) {
        int maxRankSeen = -1;
        java.util.Set<Integer> seenTiers = new java.util.HashSet<>();
        for (MetaElement element : metaBlock.getMetaElementList()) {
            int rank = StvnMetadataOrderInspection.getMetaElementRank(element);
            if (rank > 3 || !seenTiers.add(rank)) {
                continue;
            }
            if (rank < maxRankSeen) {
                if (element == target) {
                    return true;
                }
            } else {
                maxRankSeen = rank;
            }
        }
        return false;
    }

    private static boolean isMetaBlockEmpty(@NotNull MetaBlock block) {
        return block.getMetaElementList().isEmpty();
    }

    private static boolean hasDomainFacet(@NotNull MetaBlock block) {
        for (MetaElement el : block.getMetaElementList()) {
            if (el.getMetaDomainFacet() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInFlightFacetTag(@NotNull MetaBlock block, @NotNull String tagText) {
        for (PsiElement child = block.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getText().startsWith(tagText)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSystemPreludeOrSubstrateFile(@NotNull PsiFile file) {
        var name = file.getName();
        if (name.contains(".stvn_core.") || name.contains(".stvn_ir.") || name.contains(".stvn_cas.")) {
            return true;
        }
        var vf = file.getVirtualFile();
        if (vf != null) {
            var path = vf.getPath().replace('\\', '/');
            if (path.contains("/prelude/") || path.contains("/org/stvnadore/prelude/")) {
                return true;
            }
        }
        return false;
    }

    private static String stripQuotes(String s) {
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Quick-fix updating #domain string literal to match the filename stem slot 1.
     */
    public static final class AlignDomainWithStemQuickFix implements LocalQuickFix {
        private final String stemDomain;

        public AlignDomainWithStemQuickFix(String stemDomain) {
            this.stemDomain = stemDomain;
        }

        @Override
        public @NotNull String getName() {
            return "Align " + StvnVocabulary.FACET_KW_META_DOMAIN + " with filename stem slot 1 ('" + stemDomain + "')";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Align #domain with filename stem slot 1";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) return;
            if (!(element instanceof StringLiteral)) {
                MetaDomainFacet domainFacet = element instanceof MetaDomainFacet mdf
                    ? mdf
                    : PsiTreeUtil.findChildOfType(element, MetaDomainFacet.class);
                if (domainFacet != null && domainFacet.getStringLiteral() != null) {
                    element = domainFacet.getStringLiteral();
                }
            }
            Document doc = PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile());
            if (doc != null) {
                var range = element.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), "\"" + stemDomain + "\"");
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }

    /**
     * Quick-fix stripping the reserved 'stvn_' prefix from a #domain string literal.
     */
    public static final class StvnChangeReservedDomainQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {
        public StvnChangeReservedDomainQuickFix(@NotNull PsiElement element) {
            super(element);
        }

        @Override
        public @NotNull String getText() {
            return "Remove reserved 'stvn_' prefix from " + StvnVocabulary.FACET_KW_META_DOMAIN;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Remove reserved 'stvn_' prefix from #domain";
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
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc == null) return;
            String raw = stripQuotes(startElement.getText());
            String stripped = raw.startsWith(RESERVED_DOMAIN_PREFIX)
                ? raw.substring(RESERVED_DOMAIN_PREFIX.length())
                : raw;
            if (stripped.isEmpty()) {
                stripped = "custom_domain";
            }
            var range = startElement.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), "\"" + stripped + "\"");
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        }
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
            if (!(element instanceof StringLiteral)) {
                MetaNameFacet nameFacet = element instanceof MetaNameFacet mnf
                    ? mnf
                    : PsiTreeUtil.findChildOfType(element, MetaNameFacet.class);
                if (nameFacet != null && nameFacet.getStringLiteral() != null) {
                    element = nameFacet.getStringLiteral();
                }
            }
            Document doc = PsiDocumentManager.getInstance(project).getDocument(element.getContainingFile());
            if (doc != null) {
                var range = element.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), "\"" + stem + "\"");
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }

    /**
     * Quick-fix renaming the active file to match declared #name in filename stem slot 0.
     */
    public static final class RenameFileToDeclaredNameQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement {
        private final String targetFileName;

        public RenameFileToDeclaredNameQuickFix(@NotNull PsiElement element, String targetFileName) {
            super(element);
            this.targetFileName = targetFileName;
        }

        @Override
        public @NotNull String getText() {
            return "Rename file to '" + targetFileName + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Rename file to match declared #name";
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
            MetaEntry metaEntry = PsiTreeUtil.getParentOfType(facet, MetaEntry.class);
            MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class);
            PsiElement target = facet.getParent() instanceof MetaElement ? facet.getParent() : facet;
            var prev = target.getPrevSibling();
            if (prev instanceof PsiWhiteSpace) {
                prev.delete();
            }
            target.delete();
            if (metaEntry != null && metaEntry.isValid()) {
                CodeStyleManager.getInstance(project).reformat(metaEntry);
            } else if (metaBlock != null && metaBlock.isValid()) {
                CodeStyleManager.getInstance(project).reformat(metaBlock);
            }
        }
    }

    /**
     * Quick-fix inserting {@code #domain "<slot1>"} into {@code :meta} in canonical 3-tier order
     * (after {@code #name} if present, otherwise before {@code #kind}).
     */
    public static final class InsertMetaDomainQuickFix implements LocalQuickFix {
        private final String domainValue;

        public InsertMetaDomainQuickFix(String domainValue) {
            this.domainValue = domainValue;
        }

        @Override
        public @NotNull String getName() {
            return "Insert '" + StvnVocabulary.FACET_KW_META_DOMAIN + " \"" + domainValue + "\"' in " + StvnVocabulary.KEYWORD_META;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert missing #domain facet in :meta";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            MetaEntry metaEntry = element instanceof MetaEntry me
                ? me
                : PsiTreeUtil.getParentOfType(element, MetaEntry.class, false);
            if (metaEntry == null) {
                return;
            }
            MetaBlock metaBlock = metaEntry.getMetaBlock();
            if (metaBlock == null || hasDomainFacet(metaBlock)) {
                return;
            }

            var dummyFile = PsiFileFactory.getInstance(project).createFileFromText(
                "dummy.stvn",
                StvnFileType.Payload.INSTANCE,
                "{\n  :meta {\n    #domain \"" + domainValue + "\"\n  }\n}\n"
            );
            MetaElement newDomainElement = PsiTreeUtil.findChildOfType(dummyFile, MetaElement.class);
            if (newDomainElement == null) {
                return;
            }

            MetaElement lastNameElement = null;
            MetaElement firstKindElement = null;
            for (MetaElement el : metaBlock.getMetaElementList()) {
                if (el.getMetaNameFacet() != null) {
                    lastNameElement = el;
                } else if (el.getMetaKindFacet() != null && firstKindElement == null) {
                    firstKindElement = el;
                }
            }

            if (lastNameElement != null) {
                metaBlock.addAfter(newDomainElement, lastNameElement);
            } else if (firstKindElement != null) {
                metaBlock.addBefore(newDomainElement, firstKindElement);
            } else {
                PsiElement rbrace = metaBlock.getLastChild();
                if (rbrace != null && rbrace.getNode().getElementType() == StvnTypes.RBRACE) {
                    metaBlock.addBefore(newDomainElement, rbrace);
                } else {
                    metaBlock.add(newDomainElement);
                }
            }
            if (metaEntry.isValid()) {
                CodeStyleManager.getInstance(project).reformat(metaEntry);
            } else if (metaBlock.isValid()) {
                CodeStyleManager.getInstance(project).reformat(metaBlock);
            }
        }
    }
}
