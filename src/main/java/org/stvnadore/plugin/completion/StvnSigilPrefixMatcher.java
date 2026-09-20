package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.PrefixMatcher;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

/**
 * Enforces prefix sigil discipline for STVN value tags.
 * Raw numeric prefixes (e.g. "1", "2") must never match '#'-prefixed constructor tags
 * unless the author explicitly typed the '#' sigil.
 */
@NullMarked
public final class StvnSigilPrefixMatcher extends PrefixMatcher {

    private final PrefixMatcher delegate;

    public StvnSigilPrefixMatcher(PrefixMatcher delegate) {
        super(delegate.getPrefix());
        this.delegate = delegate;
    }

    @Override
    public boolean prefixMatches(@NotNull String name) {
        var prefix = getPrefix();
        if (name.startsWith("#") && !prefix.startsWith("#")) {
            // Raw integer digit or digit sequence must never match '#'-prefixed tags
            if (prefix.matches("\\d+.*")) {
                return false;
            }
        }
        return delegate.prefixMatches(name);
    }

    @Override
    public @NotNull PrefixMatcher cloneWithPrefix(@NotNull String prefix) {
        return new StvnSigilPrefixMatcher(delegate.cloneWithPrefix(prefix));
    }
}
