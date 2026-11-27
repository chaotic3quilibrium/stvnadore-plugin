package org.stvnadore.plugin.settings;

import org.jspecify.annotations.NullMarked;

/**
 * Governs cell text truncation and single-glyph ellipsis ({@code U+2026}) placement inside
 * the {@code STVN Namespace Dependencies} browser table.
 */
@NullMarked
public enum NamespaceEllipsisMode {
    /** Preserves both a leading prefix and a trailing suffix separated by a middle ellipsis ({@code …}). */
    MIDDLE("stvn.settings.browser.ellipsis.middle", "Middle \u2014 Preserve Prefix & Suffix (Default)"),

    /** Preserves only the leading prefix followed by a trailing ellipsis ({@code …}). */
    TRAILING("stvn.settings.browser.ellipsis.trailing", "Trailing \u2014 Preserve Prefix Only");

    private final String bundleKey;
    private final String defaultDisplayName;

    NamespaceEllipsisMode(String bundleKey, String defaultDisplayName) {
        this.bundleKey = bundleKey;
        this.defaultDisplayName = defaultDisplayName;
    }

    /**
     * Returns the {@code StvnBundle} message key for this ellipsis placement mode.
     *
     * @return non-empty localization key
     */
    public String getBundleKey() {
        return bundleKey;
    }

    /**
     * Returns the fallback display name when evaluated outside the IntelliJ bundle runtime.
     *
     * @return human-readable display string
     */
    public String getDefaultDisplayName() {
        return defaultDisplayName;
    }

    @Override
    public String toString() {
        return defaultDisplayName;
    }
}
