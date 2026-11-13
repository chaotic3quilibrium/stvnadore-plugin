package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test suite verifying Targets A through E using
 * constructs from execution_policy.trading.stvn_di and order_submission.trading.stvn_i.
 */
@NullMarked
public final class StvnTradingWorkflowIntegrationTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(
            new StvnMetadataFacetInspection(),
            new StvnStringCardinalityInspection(),
            new StvnDiscreteIntervalInspection(),
            new StvnTemporalModeInspection(),
            new StvnUnresolvedTypeInspection()
        );
    }

    /**
     * Target A: Unrecognized facet #milli does not consume #offset,
     * emits ERR_UNRECOGNIZED_METADATA_FACET, and does not hide #offset #zoned mutex.
     */
    public void testTargetA_UnrecognizedFacetRecoveryAndMutualExclusivity() {
        var text = """
            {
              :meta { #name "execution_policy" #kind #DEFS_INCLUDE }
              :defs {
                :AuditMarker { #milli #offset #zoned } :DateTime
              }
            }
            """;
        myFixture.configureByText("execution_policy.trading.stvn_di", text);
        var highlights = myFixture.doHighlighting();

        // 1. Verify ERR_UNRECOGNIZED_METADATA_FACET on #milli
        var unrecErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_UNRECOGNIZED_METADATA_FACET"))
            .toList();
        assertEquals("Expected exactly 1 ERR_UNRECOGNIZED_METADATA_FACET", 1, unrecErrors.size());
        assertEquals(text.indexOf("#milli"), unrecErrors.get(0).getStartOffset());
        assertEquals(text.indexOf("#milli") + 6, unrecErrors.get(0).getEndOffset());

        // 2. Verify MUTUALLY_EXCLUSIVE_BOUNDS pinned to conflicting 2nd mode (#zoned)
        var mutexErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("MUTUALLY_EXCLUSIVE_BOUNDS"))
            .toList();
        assertEquals("Mutual exclusivity must be pinned strictly to #zoned", 1, mutexErrors.size());
        assertEquals(text.indexOf("#zoned"), mutexErrors.get(0).getStartOffset());

        // 3. Verify quick-fix removes #milli cleanly
        int offset = text.indexOf("#milli");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var fixes = myFixture.filterAvailableIntentions("Remove unrecognized facet '#milli'");
        assertEquals(1, fixes.size());
        myFixture.launchAction(fixes.get(0));

        var healed = myFixture.getFile().getText();
        assertTrue("Text must heal to '{ #offset #zoned }':\n" + healed, healed.contains("{ #offset #zoned }"));
    }

    /**
     * Target B: Hovering over #size 32 on :String yields to StvnStringCardinalityInspection
     * with zero duplicate ERR_INVALID_METADATA_FACET.
     */
    public void testTargetB_StringCardinalityDeconfliction() {
        var text = """
            {
              :meta { #name "execution_policy" #kind #DEFS_INCLUDE }
              :defs {
                :AccountReference { #size 32 } :String
              }
            }
            """;
        myFixture.configureByText("account_policy.trading.stvn_di", text);
        var highlights = myFixture.doHighlighting();

        var cardinalityErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_STRING_CARDINALITY_PROHIBITED"))
            .toList();
        assertEquals("Expected exactly 1 ERR_STRING_CARDINALITY_PROHIBITED", 1, cardinalityErrors.size());

        var invalidFacetErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertTrue("ERR_INVALID_METADATA_FACET must yield to cardinality inspection", invalidFacetErrors.isEmpty());

        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var fixes = myFixture.filterAvailableIntentions("Convert '#size' to '#minSize 1 #maxSize <N>'");
        assertEquals(1, fixes.size());
        myFixture.launchAction(fixes.get(0));

        var healed = myFixture.getFile().getText();
        assertTrue(healed.contains("{ #minSize 1 #maxSize 32 } :String"));
    }

    /**
     * Target C: #minIncl 500 #maxIncl 50 on :Int emits BOTH
     * ERR_DISCRETE_BOUND_KIND_PROHIBITED and ERR_INVERTED_RANGE.
     */
    public void testTargetC_DiscreteProhibitedBoundAndInvertedRangeAccumulation() {
        var text = """
            {
              :meta { #name "execution_policy" #kind #DEFS_INCLUDE }
              :defs {
                :LatencyWindow { #minIncl 500 #maxIncl 50 } :Int
              }
            }
            """;
        myFixture.configureByText("latency.trading.stvn_di", text);
        var highlights = myFixture.doHighlighting();

        var boundErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_DISCRETE_BOUND_KIND_PROHIBITED"))
            .toList();
        assertEquals("Expected ERR_DISCRETE_BOUND_KIND_PROHIBITED on #maxIncl 50", 1, boundErrors.size());
        assertEquals(text.indexOf("#maxIncl"), boundErrors.get(0).getStartOffset());

        var rangeErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_INVERTED_RANGE"))
            .toList();
        assertEquals("Expected ERR_INVERTED_RANGE (500 > 50) on #maxIncl 50", 1, rangeErrors.size());
    }

    /**
     * Target D: Mutually exclusive modes in { #ms #offset #zoned } pin strictly
     * to mode tokens; #ms and delimiters have 0 mode mutex errors.
     */
    public void testTargetD_TemporalModePinningIsolation() {
        var text = """
            {
              :meta { #name "execution_policy" #kind #DEFS_INCLUDE }
              :defs {
                :AuditMarker { #ms #offset #zoned } :DateTime
              }
            }
            """;
        myFixture.configureByText("temporal_pinning.trading.stvn_di", text);
        var highlights = myFixture.doHighlighting();

        int msOffset = text.indexOf("#ms");
        int lbrace = text.indexOf('{', text.indexOf(":AuditMarker"));
        int rbrace = text.indexOf('}', lbrace);

        for (var h : highlights) {
            if (h.getDescription() != null && h.getDescription().contains("MUTUALLY_EXCLUSIVE_BOUNDS")) {
                assertFalse("#ms must not carry mode mutual exclusivity", h.getStartOffset() <= msOffset && msOffset < h.getEndOffset());
                assertFalse("Opening brace must not carry mode mutual exclusivity", h.getStartOffset() == lbrace);
                assertFalse("Closing brace must not carry mode mutual exclusivity", h.getEndOffset() == rbrace + 1);
            }
        }
    }

    /**
     * Target E: Cross-file include fault isolation and tuple arity underflow on ')'.
     */
    public void testTargetE_CrossFileIncludeAndTupleArityUnderflow() {
        myFixture.addFileToProject("market_definitions.trading.stvn_d", """
            {
              :meta {
                #name "market_definitions"
                #kind #DEFS
              }
              :defs {
                :Symbol :String
                :PriceOffset :Int
                :TradeTimestamp { #s } :TimeEpoch
              }
            }
            """);

        myFixture.addFileToProject("execution_policy.trading.stvn_di", """
            {
              :meta {
                #name "execution_policy"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :ExecutionPolicy { #exact } :Int
              }
            }
            """);

        var orderText = """
            {
              :meta {
                #name "order_submission"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "market_definitions.trading.stvn_d" ]
                :include [ "execution_policy.trading.stvn_di" ]
              }
              :type :Tuple( :Symbol :PriceOffset :TradeTimestamp )
              :body ( "AAPL" 150 )
            }
            """;
        myFixture.configureByText("order_submission.trading.stvn_i", orderText);
        var highlights = myFixture.doHighlighting();

        // 1. Verify valid types :Symbol, :PriceOffset, :TradeTimestamp have 0 undefined type errors
        int symbolOffset = orderText.indexOf(":Symbol");
        int priceOffset = orderText.indexOf(":PriceOffset");
        int timeOffset = orderText.indexOf(":TradeTimestamp");
        for (var h : highlights) {
            if (h.getSeverity() == HighlightSeverity.ERROR) {
                assertFalse(":Symbol must not be marked undefined", h.getStartOffset() == symbolOffset);
                assertFalse(":PriceOffset must not be marked undefined", h.getStartOffset() == priceOffset);
                assertFalse(":TradeTimestamp must not be marked undefined", h.getStartOffset() == timeOffset);
            }
        }

        // 2. Verify closing ')' carries TUPLE_ARITY_MISMATCH
        int rparenOffset = orderText.lastIndexOf(')');
        var arityErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("TUPLE_ARITY_MISMATCH"))
            .toList();
        assertFalse("Expected TUPLE_ARITY_MISMATCH on closing ')'", arityErrors.isEmpty());
        assertEquals(rparenOffset, arityErrors.get(0).getStartOffset());
        assertEquals(rparenOffset + 1, arityErrors.get(0).getEndOffset());
    }
}
