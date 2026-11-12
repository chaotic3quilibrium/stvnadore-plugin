package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
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
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
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
                        ASTNode kwMetaNode = entry.getNode().findChildByType(StvnTypes.KW_META);
                        PsiElement kwMetaAnchor = kwMetaNode != null ? kwMetaNode.getPsi() : entry;
                        holder.registerProblem(
                            kwMetaAnchor,
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
                    fixes.add(new AlignStemNameQuickFix(stringLit, stem));
                    if (isValidPosixName && !hasReservedSlot1) {
                        var targetFileName = declared;
                        for (int i = 1; i < dotParts.length; i++) {
                            targetFileName += "." + dotParts[i];
                        }
                        targetFileName += ext;
                        fixes.add(new RenameFileToDeclaredNameQuickFix(stringLit, targetFileName));
                    }
                    registerFacetIsolatedWarning(
                        holder,
                        file,
                        facet,
                        stringLit,
                        !isValidPosixName,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Declared " + StvnVocabulary.FACET_KW_META_NAME + " '" + declared
                                + "' does not match filename stem slot 0 '" + stem + "'",
                            StvnDiagnosticCodes.WARN_DOCUMENT_NAME_MISMATCH
                        ),
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
                boolean emitReservedDomainError = isReservedDomain && !isSystemFile;
                boolean hasErrorOnStringLit = emitReservedDomainError || !isValidPosixDomain;

                if (emitReservedDomainError) {
                    List<LocalQuickFix> e038Fixes = new ArrayList<>();
                    if (dotParts.length >= 2 && !dotParts[1].isEmpty() && !dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX)) {
                        e038Fixes.add(new AlignDomainWithStemQuickFix(stringLit, dotParts[1], false));
                    }
                    e038Fixes.add(new StvnChangeReservedDomainQuickFix(stringLit));
                    if (dotParts.length == 1) {
                        e038Fixes.add(new RemoveMetaDomainQuickFix(facet));
                    }
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

                if (dotParts.length == 1) {
                    var targetFileName = dotParts[0] + "." + declared + ext;
                    List<LocalQuickFix> fixes = new ArrayList<>();
                    if (!emitReservedDomainError) {
                        if (!isReservedDomain && isValidPosixDomain) {
                            fixes.add(new IncludeDomainInFilenameQuickFix(stringLit, targetFileName));
                        }
                        fixes.add(new RemoveMetaDomainQuickFix(facet));
                    } else {
                        fixes.add(new RemoveMetaDomainQuickFix(facet, true, stringLit));
                    }
                    registerFacetIsolatedWarning(
                        holder,
                        file,
                        facet,
                        stringLit,
                        hasErrorOnStringLit,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Declared " + StvnVocabulary.FACET_KW_META_DOMAIN + " '" + declared
                                + "' is omitted from filename stem slot 1",
                            StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
                        ),
                        fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
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
                            fixes.add(new AlignDomainWithStemQuickFix(stringLit, stemDomain, emitReservedDomainError));
                        }
                        if (!isReservedDomain && isValidPosixDomain) {
                            fixes.add(new IncludeDomainInFilenameQuickFix(stringLit, targetFileName));
                        }
                        registerFacetIsolatedWarning(
                            holder,
                            file,
                            facet,
                            stringLit,
                            hasErrorOnStringLit,
                            StvnDiagnosticFormatter.formatPlugin(
                                "Declared " + StvnVocabulary.FACET_KW_META_DOMAIN + " '" + declared
                                    + "' does not match filename stem slot 1 '" + stemDomain + "'",
                                StvnDiagnosticCodes.WARN_DOCUMENT_NAME_MISMATCH
                            ),
                            fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                        );
                    }
                }
            }
        };
    }

    /**
     * Registers a facet-scoped warning strictly isolated to {@code stringLit} or {@code facet} on a single line.
     * When {@code StvnMetadataOrderInspection} emits an active {@code E008} error across the enclosing
     * {@link MetaElement}, expands the warning sub-range by a single adjacent horizontal whitespace character
     * inside {@link MetaBlock} so IntelliJ {@code HighlightInfoUpdaterImpl.removeWarningsInsideErrors} retains
     * the warning without highlighting {@code MetaBlock}, comments, or sibling facets.
     */
    private static void registerFacetIsolatedWarning(
            @NotNull ProblemsHolder holder,
            @NotNull PsiFile file,
            @NotNull PsiElement facet,
            @NotNull StringLiteral stringLit,
            boolean hasErrorOnStringLit,
            @NotNull String message,
            LocalQuickFix @NotNull [] fixes
    ) {
        MetaElement metaEl = PsiTreeUtil.getParentOfType(facet, MetaElement.class, false);
        MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class, false);
        if (metaEl != null && metaBlock != null
                && isMetaElementOutOfOrder(metaBlock, metaEl)
                && StvnDiagnosticAnnotatorMapping.isInspectionActive(file, "StvnMetadataOrder")) {
            TextRange blockRange = metaBlock.getTextRange();
            TextRange facetRange = facet.getTextRange();
            if (blockRange != null && facetRange != null) {
                CharSequence docChars = file.getViewProvider().getContents();
                int wStart = facetRange.getStartOffset();
                int wEnd = facetRange.getEndOffset();
                if (wStart > blockRange.getStartOffset() + 1
                        && (docChars.charAt(wStart - 1) == ' ' || docChars.charAt(wStart - 1) == '\t')) {
                    wStart--;
                } else if (wEnd < blockRange.getEndOffset() - 1
                        && (docChars.charAt(wEnd) == ' ' || docChars.charAt(wEnd) == '\t')) {
                    wEnd++;
                } else if (wStart > blockRange.getStartOffset() + 1) {
                    wStart--;
                }
                TextRange relRange = new TextRange(
                    wStart - blockRange.getStartOffset(),
                    wEnd - blockRange.getStartOffset()
                );
                holder.registerProblem(holder.getManager().createProblemDescriptor(
                    metaBlock,
                    relRange,
                    message,
                    ProblemHighlightType.WARNING,
                    holder.isOnTheFly(),
                    fixes
                ));
                return;
            }
        }
        PsiElement anchor = hasErrorOnStringLit ? facet : stringLit;
        holder.registerProblem(anchor, message, ProblemHighlightType.WARNING, fixes);
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
     * Updates the {@code #domain} string literal to match filename stem slot 1.
     */
    public static final class AlignDomainWithStemQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String stemDomain;
        @SafeFieldForPreview
        private final boolean suppressInsideLiteralWhenReserved;

        public AlignDomainWithStemQuickFix(
                @NotNull PsiElement stringLit,
                String stemDomain,
                boolean suppressInsideLiteralWhenReserved
        ) {
            super(stringLit);
            this.stemDomain = stemDomain;
            this.suppressInsideLiteralWhenReserved = suppressInsideLiteralWhenReserved;
        }

        @Override
        public @NotNull String getText() {
            return "Align '" + StvnVocabulary.FACET_KW_META_DOMAIN + "' with filename stem slot 1 ('" + stemDomain + "')";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Align '" + StvnVocabulary.FACET_KW_META_DOMAIN + "' with filename stem slot 1";
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
            if (!startElement.isValid()) {
                return false;
            }
            if (suppressInsideLiteralWhenReserved && editor != null) {
                TextRange litRange = startElement.getTextRange();
                if (litRange != null && litRange.containsOffset(editor.getCaretModel().getOffset())) {
                    return false;
                }
            }
            return true;
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
            if (doc != null) {
                var range = startElement.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), "\"" + stemDomain + "\"");
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }

    /**
     * Removes the reserved {@code stvn_} prefix from a {@code #domain} string literal.
     */
    public static final class StvnChangeReservedDomainQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        public StvnChangeReservedDomainQuickFix(@NotNull PsiElement element) {
            super(element);
        }

        @Override
        public @NotNull String getText() {
            return "Remove reserved '" + RESERVED_DOMAIN_PREFIX + "' prefix from '"
                + StvnVocabulary.FACET_KW_META_DOMAIN + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return getText();
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
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
     * Updates the {@code #name} string literal to match filename stem slot 0.
     */
    public static final class AlignStemNameQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String stem;

        public AlignStemNameQuickFix(@NotNull PsiElement stringLit, String stem) {
            super(stringLit);
            this.stem = stem;
        }

        @Override
        public @NotNull String getText() {
            return "Align '" + StvnVocabulary.FACET_KW_META_NAME + "' with filename stem slot 0 ('" + stem + "')";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Align '" + StvnVocabulary.FACET_KW_META_NAME + "' with filename stem slot 0";
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
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
            if (doc != null) {
                var range = startElement.getTextRange();
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), "\"" + stem + "\"");
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }
        }
    }

    /**
     * Renames the active file to match declared {@code #name} in filename stem slot 0.
     */
    public static final class RenameFileToDeclaredNameQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
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
            return "Rename file to match declared " + StvnVocabulary.FACET_KW_META_NAME;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            return renamePreview(file, targetFileName);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            PsiElement element = previewDescriptor.getPsiElement();
            return renamePreview(element != null ? element.getContainingFile() : null, targetFileName);
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
     * Renames the active file to include the declared domain in filename stem slot 1.
     */
    public static final class IncludeDomainInFilenameQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
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
        public @NotNull Priority getPriority() {
            return Priority.TOP;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            return renamePreview(file, targetFileName);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            PsiElement element = previewDescriptor.getPsiElement();
            return renamePreview(element != null ? element.getContainingFile() : null, targetFileName);
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
     * Removes the {@code #domain} facet from the root {@code :meta} block on 1-stem documents.
     */
    public static final class RemoveMetaDomainQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final boolean suppressInsideLiteralWhenReserved;
        @SafeFieldForPreview
        private final @Nullable SmartPsiElementPointer<PsiElement> literalPointer;

        public RemoveMetaDomainQuickFix(@NotNull PsiElement element) {
            this(element, false, null);
        }

        public RemoveMetaDomainQuickFix(
                @NotNull PsiElement element,
                boolean suppressInsideLiteralWhenReserved,
                @Nullable PsiElement stringLit
        ) {
            super(element);
            this.suppressInsideLiteralWhenReserved = suppressInsideLiteralWhenReserved;
            this.literalPointer = stringLit != null && stringLit.isValid()
                ? SmartPointerManager.getInstance(stringLit.getProject()).createSmartPsiElementPointer(stringLit)
                : null;
        }

        @Override
        public @NotNull String getText() {
            return "Remove '" + StvnVocabulary.FACET_KW_META_DOMAIN + "' facet";
        }

        @Override
        public @NotNull String getFamilyName() {
            return getText();
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
            if (!startElement.isValid()) {
                return false;
            }
            if (suppressInsideLiteralWhenReserved && editor != null && literalPointer != null) {
                PsiElement lit = literalPointer.getElement();
                if (lit != null && lit.getTextRange() != null
                        && lit.getTextRange().containsOffset(editor.getCaretModel().getOffset())) {
                    return false;
                }
            }
            return true;
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
     * Inserts {@code #domain "<slot1>"} into {@code :meta} in canonical 3-tier order
     * (after {@code #name} if present, otherwise before {@code #kind}).
     */
    public static final class InsertMetaDomainQuickFix implements LocalQuickFix, PriorityAction {
        @SafeFieldForPreview
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
            return "Insert missing " + StvnVocabulary.FACET_KW_META_DOMAIN + " facet in " + StvnVocabulary.KEYWORD_META;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.TOP;
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

    private static @NotNull IntentionPreviewInfo renamePreview(@Nullable PsiFile file, String targetFileName) {
        if (file == null) {
            return IntentionPreviewInfo.EMPTY;
        }
        VirtualFile vFile = file.getOriginalFile().getVirtualFile();
        if (vFile == null) {
            vFile = file.getVirtualFile();
        }
        return vFile != null ? IntentionPreviewInfo.rename(vFile, targetFileName) : IntentionPreviewInfo.EMPTY;
    }
}
