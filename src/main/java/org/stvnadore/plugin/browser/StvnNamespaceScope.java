package org.stvnadore.plugin.browser;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.TypeEntry;

/**
 * Filter scope for the STVN interactive namespace dependency browser.
 */
@NullMarked
public enum StvnNamespaceScope {

    /** Displays all declared, aliased, and imported symbols in document scope. */
    DEFS(":defs", "All Document Symbols (:defs Scope)"),

    /** Displays symbols referenced and instantiated by the root schema contract. */
    TYPE(":type", "Schema Contract Symbols (:type Scope)"),

    /** Displays symbols referenced and instantiated by payload data values. */
    BODY(":body", "Payload Instantiated Symbols (:body Scope)");

    private final String sectionKeyword;
    private final String displayTitle;

    /**
     * Constructs a new StvnNamespaceScope with keyword and display title.
     *
     * @param sectionKeyword section header token string
     * @param displayTitle human-readable title of this scope
     */
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
     * Resolves the enclosing {@link StvnNamespaceScope} from a PSI element,
     * falling back to {@link #DEFS} when outside section blocks.
     *
     * @param element the PSI element at the caret position
     * @return the resolved namespace scope
     */
    public static StvnNamespaceScope resolveFromElement(@Nullable PsiElement element) {
        var current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof DefsEntry) {
                return DEFS;
            }
            if (current instanceof TypeEntry) {
                return TYPE;
            }
            if (current instanceof BodyEntry) {
                return BODY;
            }
            current = current.getParent();
        }
        return DEFS;
    }
}
