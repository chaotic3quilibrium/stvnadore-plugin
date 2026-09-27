package org.stvnadore.plugin.settings;

import org.jspecify.annotations.NullMarked;

/**
 * Governs the expansion depth and density of nominal type alias inlays.
 */
@NullMarked
public enum AliasExpansionMode {
    /** Hides alias hints completely. */
    NONE("None (Hide alias hints)"),

    /** Shows only the immediate alias symbol. */
    SYMBOL_ONLY("Symbol Only (:LocalPort)"),

    /** Shows the immediate symbol and the first resolved hop. */
    FIRST_HOP("First Hop (:LocalPort -> :Port)"),

    /** Shows the immediate symbol and the terminal base type. */
    ALIAS_TO_BASE("Alias to Base (:LocalPort -> { #size 16 } :Int)"),

    /** Shows the full transitive resolution chain. */
    FULL("Full Transitive Chain");

    private final String displayName;

    AliasExpansionMode(final String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this expansion mode.
     *
     * @return Display name string
     */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
