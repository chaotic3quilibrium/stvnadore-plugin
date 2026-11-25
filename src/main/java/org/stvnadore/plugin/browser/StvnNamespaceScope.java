package org.stvnadore.plugin.browser;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnVocabulary;
import org.stvnadore.plugin.util.StvnMetaTemplateUtil;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.TypeEntry;

/**
 * Filter scope for the STVN interactive namespace dependency browser.
 */
@NullMarked
public enum StvnNamespaceScope {

    /** Displays all declared and imported symbols in root {@code :defs} scope. */
    DEFS(StvnVocabulary.KEYWORD_DEFS, "All In-Scope :defs Symbols"),

    /** Displays transitive symbols reachable from the root {@code :type} schema contract. */
    TYPE(StvnVocabulary.KEYWORD_TYPE, "Root :type Schema Closure"),

    /** Displays transitive symbols reachable from {@code :type} and {@code :body} payload values. */
    BODY(StvnVocabulary.KEYWORD_BODY, "Payload & :type Instantiated Closure");

    private final String sectionKeyword;
    private final String displayTitle;

    StvnNamespaceScope(String sectionKeyword, String displayTitle) {
        this.sectionKeyword = sectionKeyword;
        this.displayTitle = displayTitle;
    }

    /**
     * Returns the section header token string.
     *
     * @return section keyword (for example, {@code :defs})
     */
    public String getSectionKeyword() {
        return sectionKeyword;
    }

    /**
     * Returns the human-readable title of this scope.
     *
     * @return display title string
     */
    public String getDisplayTitle() {
        return displayTitle;
    }

    /**
     * Resolves the enclosing primary {@link StvnNamespaceScope} from a PSI element,
     * falling back to {@link #DEFS} when outside primary {@code :type} or {@code :body} blocks.
     *
     * @param element the PSI element at the caret position
     * @return the resolved namespace scope
     */
    public static StvnNamespaceScope resolveFromElement(@Nullable PsiElement element) {
        var current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof DefsEntry && !StvnMetaTemplateUtil.isInSecondaryRootSection(current)) {
                return DEFS;
            }
            if (current instanceof TypeEntry && !StvnMetaTemplateUtil.isInSecondaryRootSection(current)) {
                return TYPE;
            }
            if (current instanceof BodyEntry && !StvnMetaTemplateUtil.isInSecondaryRootSection(current)) {
                return BODY;
            }
            current = current.getParent();
        }
        return DEFS;
    }
}
