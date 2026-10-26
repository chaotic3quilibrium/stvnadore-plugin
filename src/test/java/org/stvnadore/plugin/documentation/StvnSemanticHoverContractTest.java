package org.stvnadore.plugin.documentation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test suite verifying the Three-Zone Semantic Hover Contract
 * across :defs, :type, and :body clauses.
 */
@NullMarked
public final class StvnSemanticHoverContractTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider provider = new StvnDocumentationProvider();

    private String getDocAtOffset(String text, int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Documentation element at offset " + offset + " must not be null", docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Generated documentation at offset " + offset + " must not be null", doc);
        return doc;
    }

    public void testRhsBaseTypeInDefsDisplaysEnclosingAliasAndDefaults() {
        var text = """
            {
              :defs {
                :Count { #unsigned #size 16 #minIncl 1 } :Int
              }
              :type :Count
              :body 1
            }
            """;
        myFixture.configureByText("rhs_base.stvn", text);
        var offset = text.indexOf(":Int");
        var doc = getDocAtOffset(text, offset);

        // Zone 1: Enclosing Alias Identity
        assertTrue("Zone 1 must declare context for :Count", doc.contains("<b>Declared For:</b> Type Alias <code>:Count</code>"));
        assertTrue("Zone 1 must identify base type :Int", doc.contains("<b>Base Type:</b> <code>:Int</code>"));

        // Zone 2: Effective Constraints & Traits
        assertTrue("Zone 2 must display #unsigned facet", doc.contains("<code>#unsigned</code>"));
        assertTrue("Zone 2 must display #size facet", doc.contains("<code>#size</code>"));
        assertTrue("Zone 2 must display default #equatable trait", doc.contains("<code>#equatable</code>"));

        // Zone 3: Authoring Capabilities (Unset options)
        assertTrue("Zone 3 must list unset options", doc.contains("Configurable Options (Unset):"));
        assertTrue("Zone 3 must list #maxExcl as unset", doc.contains("<code>#maxExcl</code>"));
    }

    public void testLhsTypeAliasInDefsDisplaysUnsetConfigurableOptions() {
        var text = """
            {
              :defs {
                :Int32 { #size 32 } :Int
              }
              :type :Int32
              :body 42
            }
            """;
        myFixture.configureByText("lhs_alias.stvn", text);
        var offset = text.indexOf(":Int32");
        var doc = getDocAtOffset(text, offset);

        // Zone 1: Type Alias Identity
        assertTrue("Zone 1 must display Type Alias header", doc.contains("<b>Type Alias:</b> :Int32"));

        // Zone 2: Facets Table
        assertTrue("Zone 2 must render Effective Facets table", doc.contains("Effective Facets &amp; Traits:"));
        assertTrue("Zone 2 must list active #size override", doc.contains("Active Override"));

        // Zone 3: Unset Options
        assertTrue("Zone 3 must list unset options", doc.contains("Configurable Options (Unset):"));
        assertTrue("Zone 3 must list #unsigned as unset", doc.contains("<code>#unsigned</code>"));
        assertTrue("Zone 3 must list #minIncl as unset", doc.contains("<code>#minIncl</code>"));
        assertTrue("Zone 3 must list #maxExcl as unset", doc.contains("<code>#maxExcl</code>"));
    }

    public void testTupleConstructorDisplaysPositionalSlotBreakdown() {
        var text = """
            {
              :defs {
                :Count { #unsigned #size 16 } :Int
                :Note :String
                :Pair :Tuple( :Count :Note )
              }
              :type :Pair
              :body ( 1 "text" )
            }
            """;
        myFixture.configureByText("tuple_slots.stvn", text);
        var offset = text.indexOf(":Tuple");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Must display Product Type Constructor header", doc.contains("Product Type Constructor:</b> :Tuple( ... )"));
        assertTrue("Must display Positional Arity 2", doc.contains("<b>Positional Arity:</b> 2"));
        assertTrue("Must render Slot 0 row", doc.contains("<code>(0)</code>"));
        assertTrue("Must render Slot 0 schema :Count", doc.contains("<code>:Count</code>"));
        assertTrue("Must render Slot 1 row", doc.contains("<code>(1)</code>"));
        assertTrue("Must render Slot 1 schema :Note", doc.contains("<code>:Note</code>"));
    }

    public void testRootPayloadSchemaInTypeDisplaysStructuralTraceAndPayloadRules() {
        var text = """
            {
              :defs {
                :Count { #unsigned #size 16 } :Int
                :Items :Seq( :Count )
              }
              :type :Items
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("root_schema.stvn", text);
        var offset = text.lastIndexOf(":Items");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Zone 1 must declare Document Root Payload Schema", doc.contains("Document Root Payload Schema:"));
        assertTrue("Zone 1 must project structural trace", doc.contains("Structural Resolution Trace:"));
        assertTrue("Zone 3 must state body payload authoring rules", doc.contains("Document Payload Authoring Rules:"));
    }

    public void testPayloadValueInBodyDisplaysGoverningConstraintsAndSatisfiedStatus() {
        var text = """
            {
              :defs {
                :Count { #unsigned #size 16 #minIncl 1 } :Int
              }
              :type :Count
              :body 1
            }
            """;
        myFixture.configureByText("payload_sat.stvn", text);
        var offset = text.lastIndexOf("1");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Zone 1 must identify Locus as :body", doc.contains("Document Payload Body (<code>:body</code> clause)"));
        assertTrue("Zone 2 must badge Satisfied", doc.contains("Satisfied"));
        assertTrue("Zone 2 must state interval [1, 65536)", doc.contains("[1, 65536)"));
        assertTrue("Zone 3 must list hex form 0x0001", doc.contains("0x0001"));
        assertTrue("Zone 3 must list binary form", doc.contains("0b0000000000000001"));
    }

    public void testPayloadValueInBodyDisplaysViolationStatusWhenOutOfRange() {
        var text = """
            {
              :defs {
                :Count { #unsigned #size 16 #minIncl 1 #maxExcl 100 } :Int
              }
              :type :Count
              :body 0
            }
            """;
        myFixture.configureByText("payload_viol.stvn", text);
        var offset = text.lastIndexOf("0");
        var doc = getDocAtOffset(text, offset);

        assertTrue("Zone 2 must badge In Violation", doc.contains("In Violation"));
        assertTrue("Zone 2 must explain minimum bound violation", doc.contains("below minimum allowable bound 1"));
    }

    public void testHoverLatencyStrictlyWithin25msBudget() {
        var text = """
            {
              :defs {
                :Count { #unsigned #size 16 #minIncl 1 } :Int
                :Items :Seq( :Count )
              }
              :type :Items
              :body [ 1 2 3 ]
            }
            """;
        myFixture.configureByText("latency_check.stvn", text);
        var offset = text.lastIndexOf("1");

        // Warm up JIT and CachedValuesManager
        getDocAtOffset(text, offset);

        long start = System.nanoTime();
        var doc = getDocAtOffset(text, offset);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotNull("Generated doc must not be null", doc);
        assertTrue("Hover latency must be strictly under 25ms, got " + elapsedMs + "ms", elapsedMs < 25);
    }
}
