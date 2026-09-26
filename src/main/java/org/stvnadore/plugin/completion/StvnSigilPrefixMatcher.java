package org.stvnadore.plugin.completion;

import com.intellij.codeInsight.completion.PrefixMatcher;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;

import org.stvnadore.core.StvnVocabulary;

/**
 * Enforces prefix sigil discipline for STVN value tags.
 * Raw numeric prefixes (e.g. "1", "2") must never match '#'-prefixed constructor tags
 * unless the author explicitly typed the '#' sigil.
 */
@NullMarked
public final class StvnSigilPrefixMatcher extends PrefixMatcher {

    private final PrefixMatcher delegate;

    /**
     * Constructs a new StvnSigilPrefixMatcher wrapping a delegate matcher.
     *
     * @param delegate the underlying platform prefix matcher
     */
    public StvnSigilPrefixMatcher(PrefixMatcher delegate) {
        super(delegate.getPrefix());
        this.delegate = delegate;
    }

    @Override
    public boolean prefixMatches(@NotNull String name) {
        var prefix = getPrefix();
        if (name.startsWith(StvnVocabulary.SIGIL_VALUE) && !prefix.startsWith(StvnVocabulary.SIGIL_VALUE)) {
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
