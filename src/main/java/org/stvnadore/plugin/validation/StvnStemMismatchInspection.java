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
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.plugin.validation.quickfix.StvnFileRenameExecutor;
import org.stvnadore.plugin.validation.quickfix.StvnMatchMetaFileRenameQuickFix;
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
 * Validates that declared {@code #name} in the root {@code :meta} block matches filename stem slot 0
 * ({@code WARN_DOCUMENT_NAME_MISMATCH}), declared {@code #domain} matches filename stem slot 1
 * ({@code WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME}), and user documents do not declare reserved
 * system domains starting with {@code "stvn_"} ({@code ERR_RESERVED_DOMAIN_PROHIBITED}).
 */
@NullMarked
public final class StvnStemMismatchInspection extends LocalInspectionTool {

    private static final String RESERVED_DOMAIN_PREFIX = "stvn_";
    private static final Pattern POSIX_META_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /**
     * Constructs a new {@link StvnStemMismatchInspection} instance.
     */
    public StvnStemMismatchInspection() {
    }

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
                if (file == null) {
                    return;
                }
                var fileName = file.getName();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot < 0) {
                    return;
                }
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
                            new InsertMetaDomainQuickFix(kwMetaAnchor, slot1),
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
                if (!isFirstNameFacetInBlock(facet)) {
                    return;
                }
                var file = facet.getContainingFile();
                if (file == null) {
                    return;
                }
                var fileName = file.getName();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot < 0) {
                    return;
                }
                var ext = fileName.substring(lastDot);
                var stemPart = fileName.substring(0, lastDot);
                var dotParts = stemPart.split("\\.");
                if (dotParts.length == 0) {
                    return;
                }
                String stem = dotParts[0];

                StringLiteral stringLit = facet.getStringLiteral();
                if (stringLit == null || stringLit.getNode().findChildByType(StvnTypes.MALFORMED_FENCE) != null) {
                    return;
                }
                var decodedNameOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(stringLit.getText(), true);
                if (decodedNameOpt.isEmpty()) {
                    return;
                }
                String declared = decodedNameOpt.get();
                if (!POSIX_META_ID_PATTERN.matcher(declared).matches()) {
                    return;
                }

                if (!stem.equals(declared)) {
                    boolean hasReservedSlot1 = dotParts.length >= 2 && dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX);
                    var metaSummary = StvnFileRenameExecutor.resolveMetaIdentitySummary(file);
                    boolean hasAtomicMetaFix = metaSummary != null && metaSummary.mismatchedComponentCount() >= 2;

                    List<LocalQuickFix> fixes = new ArrayList<>();
                    if (hasAtomicMetaFix) {
                        fixes.add(new StvnMatchMetaFileRenameQuickFix(facet, metaSummary.targetFileName()));
                    }
                    fixes.add(new AlignStemNameQuickFix(
                        stringLit,
                        stem,
                        hasAtomicMetaFix ? PriorityAction.Priority.HIGH : PriorityAction.Priority.TOP
                    ));
                    if (!hasReservedSlot1) {
                        var targetFileName = declared;
                        for (int i = 1; i < dotParts.length; i++) {
                            targetFileName += "." + dotParts[i];
                        }
                        targetFileName += ext;
                        fixes.add(new RenameFileToDeclaredNameQuickFix(
                            stringLit,
                            targetFileName,
                            hasAtomicMetaFix ? PriorityAction.Priority.NORMAL : PriorityAction.Priority.HIGH
                        ));
                    }
                    registerFacetIsolatedWarning(
                        holder,
                        file,
                        facet,
                        stringLit,
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
                if (file == null) {
                    return;
                }
                var fileName = file.getName();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot < 0) {
                    return;
                }
                var ext = fileName.substring(lastDot);
                var stemPart = fileName.substring(0, lastDot);
                var dotParts = stemPart.split("\\.");
                if (dotParts.length == 0) {
                    return;
                }

                var stringLit = facet.getStringLiteral();
                if (stringLit == null || stringLit.getNode().findChildByType(StvnTypes.MALFORMED_FENCE) != null) {
                    return;
                }
                var decodedDomainOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(stringLit.getText(), true);
                if (decodedDomainOpt.isEmpty()) {
                    return;
                }
                var declared = decodedDomainOpt.get();
                if (!POSIX_META_ID_PATTERN.matcher(declared).matches()) {
                    return;
                }

                boolean isSystemFile = isSystemPreludeOrSubstrateFile(file);
                boolean isReservedDomain = declared.startsWith(RESERVED_DOMAIN_PREFIX);
                boolean emitReservedDomainError = isReservedDomain && !isSystemFile;

                if (emitReservedDomainError) {
                    List<LocalQuickFix> e038Fixes = new ArrayList<>();
                    if (dotParts.length == 2
                            && !dotParts[1].isEmpty()
                            && !dotParts[1].startsWith(RESERVED_DOMAIN_PREFIX)
                            && POSIX_META_ID_PATTERN.matcher(dotParts[1]).matches()) {
                        e038Fixes.add(new AlignDomainWithStemQuickFix(stringLit, dotParts[1], PriorityAction.Priority.TOP));
                    }
                    e038Fixes.add(new StvnChangeReservedDomainQuickFix(stringLit));
                    if (dotParts.length == 1) {
                        e038Fixes.add(new RemoveMetaDomainQuickFix(facet));
                    }
                    LocalQuickFix[] finalE038Fixes = StvnMetadataOrderInspection.appendReorderFixIfDisordered(
                        facet,
                        e038Fixes.toArray(LocalQuickFix.EMPTY_ARRAY)
                    );
                    holder.registerProblem(
                        stringLit,
                        StvnDiagnosticFormatter.formatPlugin(
                            "Reserved system domain prohibited: User document cannot declare reserved system domain '"
                                + declared + "'; domains with prefix '" + RESERVED_DOMAIN_PREFIX
                                + "' are strictly reserved for system artifacts",
                            StvnDiagnosticCodes.ERR_RESERVED_DOMAIN_PROHIBITED
                        ),
                        ProblemHighlightType.GENERIC_ERROR,
                        finalE038Fixes
                    );
                    int fStart = facet.getTextRange().getStartOffset();
                    int sStart = stringLit.getTextRange().getStartOffset();
                    if (isOnTheFly && sStart > fStart) {
                        holder.registerProblem(holder.getManager().createProblemDescriptor(
                            facet,
                            new TextRange(0, Math.max(1, sStart - 1 - fStart)),
                            "",
                            ProblemHighlightType.INFORMATION,
                            isOnTheFly,
                            finalE038Fixes
                        ));
                    }
                    return;
                }

                if (!isFirstDomainFacetInBlock(facet)) {
                    return;
                }

                var metaSummary = StvnFileRenameExecutor.resolveMetaIdentitySummary(file);
                boolean hasAtomicMetaFix = metaSummary != null && metaSummary.mismatchedComponentCount() >= 2;

                if (dotParts.length == 1) {
                    var targetFileName = dotParts[0] + "." + declared + ext;
                    List<LocalQuickFix> fixes = new ArrayList<>();
                    if (hasAtomicMetaFix) {
                        fixes.add(new StvnMatchMetaFileRenameQuickFix(facet, metaSummary.targetFileName()));
                    }
                    if (!isReservedDomain) {
                        fixes.add(new IncludeDomainInFilenameQuickFix(
                            stringLit,
                            targetFileName,
                            hasAtomicMetaFix ? PriorityAction.Priority.HIGH : PriorityAction.Priority.TOP
                        ));
                    }
                    fixes.add(new RemoveMetaDomainQuickFix(facet));
                    registerFacetIsolatedWarning(
                        holder,
                        file,
                        facet,
                        stringLit,
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
                        if (hasAtomicMetaFix) {
                            fixes.add(new StvnMatchMetaFileRenameQuickFix(facet, metaSummary.targetFileName()));
                        }
                        if (!stemDomain.isEmpty()
                                && !stemDomain.startsWith(RESERVED_DOMAIN_PREFIX)
                                && POSIX_META_ID_PATTERN.matcher(stemDomain).matches()) {
                            fixes.add(new AlignDomainWithStemQuickFix(
                                stringLit,
                                stemDomain,
                                hasAtomicMetaFix ? PriorityAction.Priority.HIGH : PriorityAction.Priority.TOP
                            ));
                        }
                        if (!isReservedDomain) {
                            fixes.add(new IncludeDomainInFilenameQuickFix(
                                stringLit,
                                targetFileName,
                                hasAtomicMetaFix ? PriorityAction.Priority.NORMAL : PriorityAction.Priority.HIGH
                            ));
                        }
                        registerFacetIsolatedWarning(
                            holder,
                            file,
                            facet,
                            stringLit,
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
     * Registers a facet-scoped warning strictly isolated to {@code stringLit} on a single line.
     * When an out-of-order {@code E008} error spans the enclosing {@link MetaElement}, expands the warning
     * sub-range by a single adjacent horizontal whitespace character inside {@link MetaBlock} so IntelliJ
     * {@code HighlightInfoUpdaterImpl.removeWarningsInsideErrors} retains the warning without highlighting
     * {@code MetaBlock}, comments, or sibling facets.
     */
    private static void registerFacetIsolatedWarning(
            @NotNull ProblemsHolder holder,
            @NotNull PsiFile file,
            @NotNull PsiElement facet,
            @NotNull StringLiteral stringLit,
            @NotNull String message,
            LocalQuickFix @NotNull [] fixes
    ) {
        MetaElement metaEl = PsiTreeUtil.getParentOfType(facet, MetaElement.class, false);
        MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class, false);
        if (metaEl != null && metaBlock != null && isMetaElementOutOfOrder(metaBlock, metaEl)) {
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
        holder.registerProblem(stringLit, message, ProblemHighlightType.WARNING, fixes);
        if (holder.isOnTheFly()) {
            PsiElement kwToken = facet.getFirstChild();
            if (kwToken != null && kwToken != stringLit
                    && kwToken.getTextRange().getEndOffset() < stringLit.getTextRange().getStartOffset()) {
                holder.registerProblem(kwToken, "", ProblemHighlightType.INFORMATION, fixes);
            }
        }
    }

    private static boolean isFirstNameFacetInBlock(@NotNull MetaNameFacet facet) {
        MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class, false);
        if (metaBlock == null) {
            return true;
        }
        for (MetaElement el : metaBlock.getMetaElementList()) {
            if (el.getMetaNameFacet() != null) {
                return el.getMetaNameFacet() == facet;
            }
        }
        return true;
    }

    private static boolean isFirstDomainFacetInBlock(@NotNull MetaDomainFacet facet) {
        MetaBlock metaBlock = PsiTreeUtil.getParentOfType(facet, MetaBlock.class, false);
        if (metaBlock == null) {
            return true;
        }
        for (MetaElement el : metaBlock.getMetaElementList()) {
            if (el.getMetaDomainFacet() != null) {
                return el.getMetaDomainFacet() == facet;
            }
        }
        return true;
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

    static boolean isSystemPreludeOrSubstrateFile(@NotNull PsiFile file) {
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
        private final Priority priority;

        /**
         * Constructs a {@link Priority#TOP} domain-alignment quick-fix.
         *
         * @param stringLit  target {@code #domain} string literal
         * @param stemDomain filename stem slot 1 domain
         */
        public AlignDomainWithStemQuickFix(
                @NotNull PsiElement stringLit,
                String stemDomain
        ) {
            this(stringLit, stemDomain, Priority.TOP);
        }

        /**
         * Constructs a domain-alignment quick-fix with explicit priority.
         *
         * @param stringLit  target {@code #domain} string literal
         * @param stemDomain filename stem slot 1 domain
         * @param priority   explicit intention menu priority
         */
        public AlignDomainWithStemQuickFix(
                @NotNull PsiElement stringLit,
                String stemDomain,
                Priority priority
        ) {
            super(stringLit);
            this.stemDomain = stemDomain;
            this.priority = priority;
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
            return priority;
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

        /**
         * Constructs a reserved-domain prefix removal quick-fix.
         *
         * @param element target {@code #domain} string literal
         */
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
            Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc == null) {
                return;
            }
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
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a {@link Priority#TOP} stem-name alignment quick-fix.
         *
         * @param stringLit target {@code #name} string literal
         * @param stem      filename stem slot 0
         */
        public AlignStemNameQuickFix(@NotNull PsiElement stringLit, String stem) {
            this(stringLit, stem, Priority.TOP);
        }

        /**
         * Constructs a stem-name alignment quick-fix with explicit priority.
         *
         * @param stringLit target {@code #name} string literal
         * @param stem      filename stem slot 0
         * @param priority  explicit intention menu priority
         */
        public AlignStemNameQuickFix(@NotNull PsiElement stringLit, String stem, Priority priority) {
            super(stringLit);
            this.stem = stem;
            this.priority = priority;
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
            return priority;
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
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a {@link Priority#HIGH} file-rename quick-fix matching {@code #name}.
         *
         * @param element        target {@code #name} string literal
         * @param targetFileName preview target filename
         */
        public RenameFileToDeclaredNameQuickFix(@NotNull PsiElement element, String targetFileName) {
            this(element, targetFileName, Priority.HIGH);
        }

        /**
         * Constructs a file-rename quick-fix matching {@code #name} with explicit priority.
         *
         * @param element        target {@code #name} string literal
         * @param targetFileName preview target filename
         * @param priority       explicit intention menu priority
         */
        public RenameFileToDeclaredNameQuickFix(
                @NotNull PsiElement element,
                String targetFileName,
                Priority priority
        ) {
            super(element);
            this.targetFileName = targetFileName;
            this.priority = priority;
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
            return priority;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            return StvnFileRenameExecutor.buildRenamePreview(file, targetFileName);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            PsiElement element = previewDescriptor.getPsiElement();
            return StvnFileRenameExecutor.buildRenamePreview(
                element != null ? element.getContainingFile() : null,
                targetFileName
            );
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
            String liveTarget = computeLiveTargetFileName(file, startElement);
            if (liveTarget == null) {
                liveTarget = targetFileName;
            }
            StvnFileRenameExecutor.executeRenameWithCollisionFeedback(
                project,
                editor,
                file,
                liveTarget,
                getText(),
                null
            );
        }

        private static @Nullable String computeLiveTargetFileName(PsiFile file, PsiElement startElement) {
            var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(startElement.getText(), true);
            if (decodedOpt.isEmpty() || !POSIX_META_ID_PATTERN.matcher(decodedOpt.get()).matches()) {
                return null;
            }
            String fileName = file.getName();
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot <= 0) {
                return null;
            }
            String ext = fileName.substring(lastDot);
            String[] dotParts = fileName.substring(0, lastDot).split("\\.");
            StringBuilder sb = new StringBuilder(decodedOpt.get());
            for (int i = 1; i < dotParts.length; i++) {
                sb.append('.').append(dotParts[i]);
            }
            sb.append(ext);
            return sb.toString();
        }
    }

    /**
     * Renames the active file to include the declared {@code #domain} in filename stem slot 1.
     */
    public static final class IncludeDomainInFilenameQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String targetFileName;
        @SafeFieldForPreview
        private final Priority priority;

        /**
         * Constructs a {@link Priority#TOP} domain-stem file-rename quick-fix.
         *
         * @param element        target {@code #domain} string literal
         * @param targetFileName preview target filename
         */
        public IncludeDomainInFilenameQuickFix(@NotNull PsiElement element, String targetFileName) {
            this(element, targetFileName, Priority.TOP);
        }

        /**
         * Constructs a domain-stem file-rename quick-fix with explicit priority.
         *
         * @param element        target {@code #domain} string literal
         * @param targetFileName preview target filename
         * @param priority       explicit intention menu priority
         */
        public IncludeDomainInFilenameQuickFix(
                @NotNull PsiElement element,
                String targetFileName,
                Priority priority
        ) {
            super(element);
            this.targetFileName = targetFileName;
            this.priority = priority;
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
            return priority;
        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull Editor editor,
                @NotNull PsiFile file
        ) {
            return StvnFileRenameExecutor.buildRenamePreview(file, targetFileName);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project,
                @NotNull ProblemDescriptor previewDescriptor
        ) {
            PsiElement element = previewDescriptor.getPsiElement();
            return StvnFileRenameExecutor.buildRenamePreview(
                element != null ? element.getContainingFile() : null,
                targetFileName
            );
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
            String liveTarget = computeLiveTargetFileName(file, startElement);
            if (liveTarget == null) {
                liveTarget = targetFileName;
            }
            StvnFileRenameExecutor.executeRenameWithCollisionFeedback(
                project,
                editor,
                file,
                liveTarget,
                getText(),
                null
            );
        }

        private static @Nullable String computeLiveTargetFileName(PsiFile file, PsiElement startElement) {
            var decodedOpt = StvnPsiLiteralEvaluator.decodeStringLiteral(startElement.getText(), true);
            if (decodedOpt.isEmpty() || !POSIX_META_ID_PATTERN.matcher(decodedOpt.get()).matches()) {
                return null;
            }
            String fileName = file.getName();
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot <= 0) {
                return null;
            }
            String ext = fileName.substring(lastDot);
            String[] dotParts = fileName.substring(0, lastDot).split("\\.");
            if (dotParts.length == 0 || dotParts[0].isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder(dotParts[0]).append('.').append(decodedOpt.get());
            for (int i = 2; i < dotParts.length; i++) {
                sb.append('.').append(dotParts[i]);
            }
            sb.append(ext);
            return sb.toString();
        }
    }

    /**
     * Removes the {@code #domain} facet from the root {@code :meta} block on 1-stem documents.
     */
    public static final class RemoveMetaDomainQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {

        /**
         * Constructs a {@code #domain} removal quick-fix.
         *
         * @param element target {@link MetaDomainFacet}
         */
        public RemoveMetaDomainQuickFix(@NotNull PsiElement element) {
            super(element);
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
            MetaDomainFacet facet = startElement instanceof MetaDomainFacet mdf
                ? mdf
                : PsiTreeUtil.getParentOfType(startElement, MetaDomainFacet.class, false);
            if (facet == null) {
                facet = PsiTreeUtil.findChildOfType(file, MetaDomainFacet.class);
            }
            if (facet == null) {
                return;
            }
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
    public static final class InsertMetaDomainQuickFix extends LocalQuickFixAndIntentionActionOnPsiElement
            implements PriorityAction {
        @SafeFieldForPreview
        private final String domainValue;

        /**
         * Constructs a canonical {@code #domain} insertion quick-fix anchored to {@code anchor}.
         *
         * @param anchor      owning {@code :meta} keyword or {@link MetaEntry}
         * @param domainValue domain identifier from filename stem slot 1
         */
        public InsertMetaDomainQuickFix(@NotNull PsiElement anchor, String domainValue) {
            super(anchor);
            this.domainValue = domainValue;
        }

        @Override
        public @NotNull String getText() {
            return "Insert '" + StvnVocabulary.FACET_KW_META_DOMAIN + " \"" + domainValue + "\"' in "
                + StvnVocabulary.KEYWORD_META;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert missing " + StvnVocabulary.FACET_KW_META_DOMAIN + " facet in "
                + StvnVocabulary.KEYWORD_META;
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
            MetaEntry metaEntry = startElement instanceof MetaEntry me
                ? me
                : PsiTreeUtil.getParentOfType(startElement, MetaEntry.class, false);
            if (metaEntry == null) {
                metaEntry = PsiTreeUtil.findChildOfType(file, MetaEntry.class);
            }
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
                "{\n  " + StvnVocabulary.KEYWORD_META + " {\n    "
                    + StvnVocabulary.FACET_KW_META_DOMAIN + " \"" + domainValue + "\"\n  }\n}\n"
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
