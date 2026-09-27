package org.stvnadore.plugin.util;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import java.util.Optional;

/**
 * Validates the 5-state presentation formatting matrix against multi-hop, parenthesized,
 * and direct primitive type strings.
 */
@NullMarked
public final class StvnTypePresentationUtilTest extends TestCase {

    private static final String MULTI_HOP_CHAIN = ":LocalPort -> :Port -> { #unsigned #size 16 } :Int";
    private static final String DIRECT_TYPE = "{ #unsigned #size 16 } :Int";
    private static final String PARENTHESIZED_CHAIN = ":FinalStatus (-> :IntermediateStatus -> :BaseStatus -> :Enum)";

    public void testMultiHopBareChainExpansionModes() {
        assertEquals(Optional.empty(), StvnTypePresentationUtil.formatAliasChain(MULTI_HOP_CHAIN, AliasExpansionMode.NONE));
        assertEquals(Optional.of(":LocalPort"), StvnTypePresentationUtil.formatAliasChain(MULTI_HOP_CHAIN, AliasExpansionMode.SYMBOL_ONLY));
        assertEquals(Optional.of(":LocalPort -> :Port"), StvnTypePresentationUtil.formatAliasChain(MULTI_HOP_CHAIN, AliasExpansionMode.FIRST_HOP));
        assertEquals(Optional.of(":LocalPort -> { #unsigned #size 16 } :Int"), StvnTypePresentationUtil.formatAliasChain(MULTI_HOP_CHAIN, AliasExpansionMode.ALIAS_TO_BASE));
        assertEquals(Optional.of(MULTI_HOP_CHAIN), StvnTypePresentationUtil.formatAliasChain(MULTI_HOP_CHAIN, AliasExpansionMode.FULL));
    }

    public void testDirectTypeInvariance() {
        assertEquals(Optional.empty(), StvnTypePresentationUtil.formatAliasChain(DIRECT_TYPE, AliasExpansionMode.NONE));
        assertEquals(Optional.of(DIRECT_TYPE), StvnTypePresentationUtil.formatAliasChain(DIRECT_TYPE, AliasExpansionMode.SYMBOL_ONLY));
        assertEquals(Optional.of(DIRECT_TYPE), StvnTypePresentationUtil.formatAliasChain(DIRECT_TYPE, AliasExpansionMode.FIRST_HOP));
        assertEquals(Optional.of(DIRECT_TYPE), StvnTypePresentationUtil.formatAliasChain(DIRECT_TYPE, AliasExpansionMode.ALIAS_TO_BASE));
        assertEquals(Optional.of(DIRECT_TYPE), StvnTypePresentationUtil.formatAliasChain(DIRECT_TYPE, AliasExpansionMode.FULL));
    }

    public void testParenthesizedDerivationChain() {
        assertEquals(Optional.empty(), StvnTypePresentationUtil.formatAliasChain(PARENTHESIZED_CHAIN, AliasExpansionMode.NONE));
        assertEquals(Optional.of(":FinalStatus"), StvnTypePresentationUtil.formatAliasChain(PARENTHESIZED_CHAIN, AliasExpansionMode.SYMBOL_ONLY));
        assertEquals(Optional.of(":FinalStatus (-> :IntermediateStatus)"), StvnTypePresentationUtil.formatAliasChain(PARENTHESIZED_CHAIN, AliasExpansionMode.FIRST_HOP));
        assertEquals(Optional.of(":FinalStatus (-> :Enum)"), StvnTypePresentationUtil.formatAliasChain(PARENTHESIZED_CHAIN, AliasExpansionMode.ALIAS_TO_BASE));
        assertEquals(Optional.of(PARENTHESIZED_CHAIN), StvnTypePresentationUtil.formatAliasChain(PARENTHESIZED_CHAIN, AliasExpansionMode.FULL));
    }
}
