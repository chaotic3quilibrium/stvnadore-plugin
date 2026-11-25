package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
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
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnStringCharacterPolicy;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.StvnTypes;
import org.stvnadore.psi.Visitor;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspects double-quoted simple string literals ({@code "..."}) across all STVN sections for
 * hazardous control, bidirectional, or invisible format escapes and ambiguous shorthand {@code \r}
 * escapes.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnStringEscapeScrutinyInspection extends LocalInspectionTool {

    /**
     * Canonical quick-fix label for replacing a single shorthand {@code \r} escape with explicit {@code \u005Cu000D}.
     */
    public static final String REPLACE_SINGLE_CR_QUICK_FIX_NAME =
        "Replace '\\r' with explicit '\\u000D'";

    /**
     * Canonical quick-fix label for replacing all shorthand {@code \r} escapes in a string literal with {@code \u005Cu000D}.
     */
    public static final String REPLACE_ALL_CR_IN_LITERAL_QUICK_FIX_NAME =
        "Replace all shorthand '\\r' escapes in string literal with '\\u000D'";

    /**
     * Persistent option controlling whether shorthand {@code \r} escapes emit a scrutiny warning.
     */
    public boolean warnOnShorthandCrEscape = true;

    /**
     * Constructs a new {@link StvnStringEscapeScrutinyInspection}.
     */
    public StvnStringEscapeScrutinyInspection() {
    }

    @Override
    public @NotNull String getShortName() {
        return "StvnStringEscapeScrutiny";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Hazardous or ambiguous string escape sequence";
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
    public @Nullable JComponent createOptionsPanel() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        var crCheckBox = new JCheckBox(
            "Warn on shorthand \\r escape (use explicit \\u000D when intentional)",
            warnOnShorthandCrEscape
        );
        crCheckBox.addActionListener(e -> warnOnShorthandCrEscape = crCheckBox.isSelected());
        crCheckBox.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(crCheckBox);
        return panel;
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new Visitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                super.visitElement(element);
                var node = element.getNode();
                if (node == null || node.getElementType() != StvnTypes.LITERAL_STRING_SIMPLE) {
                    return;
                }
                if (StvnMetaTemplateUtil.isInSecondaryRootSection(element)) {
                    return;
                }
                inspectSimpleStringLeaf(element, holder);
            }
        };
    }

    private void inspectSimpleStringLeaf(PsiElement leaf, ProblemsHolder holder) {
        String text = leaf.getText();
        int len = text.length();
        if (len < 2 || text.charAt(0) != '"') {
            return;
        }
        int contentEnd = hasUnescapedClosingQuote(text) ? len - 1 : len;
        List<Integer> shorthandCrOffsets = warnOnShorthandCrEscape
            ? collectShorthandCrOffsets(text, contentEnd)
            : List.of();
        boolean offerBatchFix = shorthandCrOffsets.size() >= 2;

        for (int i = 1; i < contentEnd; ) {
            if (text.charAt(i) != '\\') {
                i++;
                continue;
            }
            if (i + 1 >= contentEnd) {
                break;
            }
            char esc = text.charAt(i + 1);
            switch (esc) {
                case '"', '\\', '/', 'n', 't' -> i += 2;
                case 'b', 'f' -> {
                    int cp = (esc == 'b') ? '\b' : '\f';
                    registerHazardousEscapeWarning(leaf, holder, i, i + 2, text.substring(i, i + 2), cp);
                    i += 2;
                }
                case 'r' -> {
                    if (warnOnShorthandCrEscape) {
                        String scalarId = StvnStringCharacterPolicy.formatUnicodeScalarId('\r');
                        String charName = StvnStringCharacterPolicy.resolveUnicodeCharacterName('\r');
                        String message = StvnDiagnosticFormatter.formatPlugin(
                            "Shorthand escape '\\r' (" + scalarId + " " + charName
                                + ") cannot be represented verbatim in fenced strings; use explicit '\\u000D' when intentional",
                            StvnDiagnosticCodes.WARN_SHORTHAND_CR_ESCAPE
                        );
                        LocalQuickFix[] fixes = offerBatchFix
                            ? new LocalQuickFix[]{
                                new ReplaceShorthandCrWithExplicitUnicodeQuickFix(i),
                                new ReplaceAllShorthandCrInLiteralQuickFix()
                            }
                            : new LocalQuickFix[]{
                                new ReplaceShorthandCrWithExplicitUnicodeQuickFix(i)
                            };
                        holder.registerProblem(
                            leaf,
                            message,
                            ProblemHighlightType.WARNING,
                            new TextRange(i, i + 2),
                            fixes
                        );
                    }
                    i += 2;
                }
                case 'u' -> {
                    int unit1 = parseFourHexDigits(text, i + 2, contentEnd);
                    if (unit1 < 0) {
                        i = advancePastMalformedUnicodeEscape(text, i, contentEnd);
                        continue;
                    }
                    if (Character.isHighSurrogate((char) unit1)) {
                        if (i + 12 <= contentEnd
                                && text.charAt(i + 6) == '\\'
                                && text.charAt(i + 7) == 'u') {
                            int unit2 = parseFourHexDigits(text, i + 8, contentEnd);
                            if (unit2 >= 0 && Character.isLowSurrogate((char) unit2)) {
                                int cp = Character.toCodePoint((char) unit1, (char) unit2);
                                if (StvnStringCharacterPolicy.isHazardousEscapedCodePoint(cp)) {
                                    registerHazardousEscapeWarning(
                                        leaf, holder, i, i + 12, text.substring(i, i + 12), cp
                                    );
                                }
                                i += 12;
                                continue;
                            }
                        }
                        // Unpaired high surrogate escape is owned by Core E001
                        i += 6;
                    } else if (Character.isLowSurrogate((char) unit1)) {
                        // Unpaired low surrogate escape is owned by Core E001
                        i += 6;
                    } else {
                        if (StvnStringCharacterPolicy.isHazardousEscapedCodePoint(unit1)) {
                            registerHazardousEscapeWarning(
                                leaf, holder, i, i + 6, text.substring(i, i + 6), unit1
                            );
                        }
                        i += 6;
                    }
                }
                default -> i += 2;
            }
        }
    }

    private static void registerHazardousEscapeWarning(
            PsiElement leaf,
            ProblemsHolder holder,
            int relStart,
            int relEnd,
            String rawEscape,
            int codePoint
    ) {
        String scalarId = StvnStringCharacterPolicy.formatUnicodeScalarId(codePoint);
        String charName = StvnStringCharacterPolicy.resolveUnicodeCharacterName(codePoint);
        String message = StvnDiagnosticFormatter.formatPlugin(
            "Hazardous or invisible string escape '" + rawEscape + "' (" + scalarId + " " + charName
                + "); verify that this control, bidirectional, or format scalar is intentional",
            StvnDiagnosticCodes.WARN_HAZARDOUS_STRING_ESCAPE
        );
        holder.registerProblem(
            leaf,
            message,
            ProblemHighlightType.WARNING,
            new TextRange(relStart, relEnd)
        );
    }

    private static List<Integer> collectShorthandCrOffsets(String text, int contentEnd) {
        List<Integer> offsets = new ArrayList<>();
        for (int i = 1; i < contentEnd; ) {
            if (text.charAt(i) != '\\') {
                i++;
                continue;
            }
            if (i + 1 >= contentEnd) {
                break;
            }
            char esc = text.charAt(i + 1);
            if (esc == 'r') {
                offsets.add(i);
                i += 2;
            } else if (esc == 'u') {
                int unit = parseFourHexDigits(text, i + 2, contentEnd);
                i = (unit >= 0) ? (i + 6) : advancePastMalformedUnicodeEscape(text, i, contentEnd);
            } else {
                i += 2;
            }
        }
        return offsets;
    }

    static boolean hasUnescapedClosingQuote(String text) {
        int len = text.length();
        if (len < 2 || text.charAt(len - 1) != '"') {
            return false;
        }
        int backslashes = 0;
        for (int k = len - 2; k >= 1 && text.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        return (backslashes & 1) == 0;
    }

    static int parseFourHexDigits(String text, int hexStart, int limit) {
        if (hexStart + 4 > limit) {
            return -1;
        }
        int value = 0;
        for (int k = 0; k < 4; k++) {
            int digit = Character.digit(text.charAt(hexStart + k), 16);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private static int advancePastMalformedUnicodeEscape(String text, int backslashPos, int limit) {
        int cursor = backslashPos + 2;
        int scanned = 0;
        while (cursor < limit && scanned < 4 && Character.digit(text.charAt(cursor), 16) >= 0) {
            cursor++;
            scanned++;
        }
        return cursor;
    }

    private static void replaceLeafLiteralText(Project project, PsiElement element, String updatedLiteralText) {
        if (IntentionPreviewUtils.isPreviewElement(element) && element instanceof LeafPsiElement leaf) {
            leaf.replaceWithText(updatedLiteralText);
            return;
        }
        PsiFile file = element.getContainingFile();
        Document doc = (file != null) ? PsiDocumentManager.getInstance(project).getDocument(file) : null;
        if (doc != null) {
            TextRange range = element.getTextRange();
            doc.replaceString(range.getStartOffset(), range.getEndOffset(), updatedLiteralText);
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        } else if (element instanceof LeafPsiElement leaf) {
            leaf.replaceWithText(updatedLiteralText);
        }
    }

    /**
     * Quick-fix replacing a single shorthand {@code \r} escape at {@code relStart} with explicit {@code \u005Cu000D}.
     */
    public static final class ReplaceShorthandCrWithExplicitUnicodeQuickFix implements LocalQuickFix, PriorityAction {

        @SafeFieldForPreview
        private final int relStart;

        /**
         * Constructs a quick-fix anchored to {@code relStart} within the string literal leaf.
         *
         * @param relStart 0-based relative start offset of {@code \r} inside the string token
         */
        public ReplaceShorthandCrWithExplicitUnicodeQuickFix(int relStart) {
            this.relStart = relStart;
        }

        @Override
        public @NotNull String getName() {
            return REPLACE_SINGLE_CR_QUICK_FIX_NAME;
        }

        @Override
        public @NotNull String getFamilyName() {
            return REPLACE_SINGLE_CR_QUICK_FIX_NAME;
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
            String text = element.getText();
            if (relStart < 1 || relStart + 2 > text.length() || !text.startsWith("\\r", relStart)) {
                return;
            }
            String updated = text.substring(0, relStart)
                + StvnStringCharacterPolicy.formatRecommendedSimpleEscape('\r')
                + text.substring(relStart + 2);
            replaceLeafLiteralText(project, element, updated);
        }
    }

    /**
     * Batch quick-fix replacing every shorthand {@code \r} escape inside the target string literal with {@code \u005Cu000D}.
     */
    public static final class ReplaceAllShorthandCrInLiteralQuickFix implements LocalQuickFix, PriorityAction {

        /**
         * Constructs a batch literal quick-fix.
         */
        public ReplaceAllShorthandCrInLiteralQuickFix() {
        }

        @Override
        public @NotNull String getName() {
            return REPLACE_ALL_CR_IN_LITERAL_QUICK_FIX_NAME;
        }

        @Override
        public @NotNull String getFamilyName() {
            return REPLACE_ALL_CR_IN_LITERAL_QUICK_FIX_NAME;
        }

        @Override
        public @NotNull Priority getPriority() {
            return Priority.HIGH;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            String text = element.getText();
            int len = text.length();
            if (len < 2 || text.charAt(0) != '"') {
                return;
            }
            int contentEnd = hasUnescapedClosingQuote(text) ? len - 1 : len;
            List<Integer> offsets = collectShorthandCrOffsets(text, contentEnd);
            if (offsets.isEmpty()) {
                return;
            }
            String replacement = StvnStringCharacterPolicy.formatRecommendedSimpleEscape('\r');
            StringBuilder sb = new StringBuilder(len + offsets.size() * 4);
            int cursor = 0;
            for (int off : offsets) {
                sb.append(text, cursor, off);
                sb.append(replacement);
                cursor = off + 2;
            }
            sb.append(text, cursor, len);
            replaceLeafLiteralText(project, element, sb.toString());
        }
    }
}
