package org.stvnadore.plugin.util;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.AliasExpansionMode;

/**
 * Utility for formatting and truncating nominal alias derivation chains
 * based on the active {@link AliasExpansionMode}.
 */
@NullMarked
public final class StvnTypePresentationUtil {

    private StvnTypePresentationUtil() {}

    /**
     * Resolves the formatted alias derivation string based on user configuration.
     *
     * @param fullChain The complete resolved chain (e.g. ":LocalPort -> :Port -> { #unsigned #size 16 } :Int")
     * @param mode The active expansion mode
     * @return Empty if mode is NONE; formatted string otherwise
     */
    public static Optional<String> formatAliasChain(
            final String fullChain,
            final AliasExpansionMode mode) {

        if (mode == AliasExpansionMode.NONE) {
            return Optional.empty();
        }

        if (mode == AliasExpansionMode.FULL) {
            return Optional.of(fullChain);
        }

        // Case 1: Parenthesized derivation syntax, e.g. "prefix:Head [tag] (-> hop1 -> ... -> base)"
        final int parenArrowIndex = fullChain.indexOf("(-> ");
        if (parenArrowIndex >= 0 && fullChain.endsWith(")")) {
            final String prefixAndHead = fullChain.substring(0, parenArrowIndex).trim();
            final String innerChain = fullChain.substring(parenArrowIndex + 4, fullChain.length() - 1).trim();
            final List<String> hops = Arrays.stream(innerChain.split("->"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            if (hops.isEmpty()) {
                return Optional.of(prefixAndHead);
            }

            return switch (mode) {
                case SYMBOL_ONLY -> Optional.of(prefixAndHead);
                case FIRST_HOP -> Optional.of(prefixAndHead + " (-> " + hops.getFirst() + ")");
                case ALIAS_TO_BASE -> Optional.of(prefixAndHead + " (-> " + hops.getLast() + ")");
                case NONE, FULL -> Optional.of(fullChain);
            };
        }

        // Case 2: Bare arrow chain, e.g. ":LocalPort -> :Port -> { #unsigned #size 16 } :Int"
        final List<String> segments = Arrays.stream(fullChain.split("->"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (segments.size() <= 1) {
            // Direct type lacking aliases (e.g. "{ #unsigned #size 16 } :Int")
            return Optional.of(fullChain);
        }

        final String head = segments.getFirst();
        final String base = segments.getLast();

        return switch (mode) {
            case SYMBOL_ONLY -> Optional.of(head);
            case FIRST_HOP -> Optional.of(head + " -> " + segments.get(1));
            case ALIAS_TO_BASE -> Optional.of(head + " -> " + base);
            case NONE, FULL -> Optional.of(fullChain);
        };
    }
}
