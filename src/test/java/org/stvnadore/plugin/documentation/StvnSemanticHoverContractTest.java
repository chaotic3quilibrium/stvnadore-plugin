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

    public void testUnionExemplarUsesOneBasedBranchSelectorAndReservedLhsRendersBuiltInCard() {
        var text = """
            {
              :defs {
                :UnionChoice :Union( :Int :String )
                :Int :String
              }
              :type :UnionChoice
              :body #1 0
            }
            """;
        myFixture.configureByText("union_and_lhs.stvn", text);

        int unionOffset = text.indexOf(":UnionChoice");
        String unionDoc = getDocAtOffset(text, unionOffset + 1);
        assertTrue(
            "Union exemplar must use 1-based selector '#1 0', never 0-based '#0':\n" + unionDoc,
            unionDoc.contains("#1 0") && !unionDoc.contains("#0")
        );

        int lhsIntOffset = text.indexOf(":Int :String") + 1;
        String lhsIntDoc = getDocAtOffset(text, lhsIntOffset);
        assertTrue(
            "Reserved LHS ':Int' must render built-in :Int specification card:\n" + lhsIntDoc,
            lhsIntDoc.contains(":Int") && !lhsIntDoc.contains("#preserveIndent")
        );
    }

    public void testGroundedPoisonedSchemaBodySuspensionHoverContract() {
        var text = """
            {
              :meta {
                #name "grounded_poisoned_schema_body_suspension"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :TrapIntMinExcl { #minExcl 10 #maxExcl 20 } :Int
                :ChildUnsignedTrap { #unsigned } :TrapIntMinExcl
                :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 4096 } :Int
              }
              :type :Tuple(:TrapIntMinExcl :TrapIntMinExcl :BoundedPort)
              :body (15 999 5000)
            }
            """;
        myFixture.configureByText("grounded_poisoned_schema_body_suspension.core_test.stvn_i", text);

        // 1. Hover on :TrapIntMinExcl in :defs: verify VOP Option D banner
        int defsTrapOffset = text.indexOf(":TrapIntMinExcl");
        String defsTrapDoc = getDocAtOffset(text, defsTrapOffset + 1);
        assertTrue(
            "Degraded Schema Warning banner on :TrapIntMinExcl must state underlying base and suspension:\n" + defsTrapDoc,
            defsTrapDoc.contains("Nominal type <code>:TrapIntMinExcl</code> contains constraint errors in <code>:defs</code> (underlying base type: <code>:Int</code>).<br/>")
                && defsTrapDoc.contains("Payload evaluation is suspended.")
        );

        // 2. Hover on poisoned :body slots '15' and '999': verify Zone 1, Zone 2 suspended compliance, omitted Exemplar, and Zone 3 lexical forms
        int bodyIdx = text.indexOf(":body");
        for (String slotLiteral : new String[]{"15", "999"}) {
            int slotOffset = text.indexOf(slotLiteral, bodyIdx);
            String slotDoc = getDocAtOffset(text, slotOffset);
            assertTrue(
                "Zone 1 must preserve Value Type with degraded indicator and (-> :Int) trace:\n" + slotDoc,
                slotDoc.contains("<b>Value Type:</b> \u26A0 :TrapIntMinExcl (-> :Int)")
            );
            assertTrue(
                "Zone 2 must report Compliance Status: Suspended (Degraded Schema):\n" + slotDoc,
                slotDoc.contains("Suspended (Degraded Schema)")
            );
            assertTrue(
                "Zone 2 must explain VOP Option D payload suspension with underlying base type :Int:\n" + slotDoc,
                slotDoc.contains("\u26A0 Suspended (governing schema is degraded \u2014 payload evaluation skipped): "
                    + "Payload compliance evaluation is suspended because governing type <code>:TrapIntMinExcl</code> "
                    + "contains metadata constraint errors in <code>:defs</code> (underlying base type: <code>:Int</code>).")
            );
            assertFalse(
                "Zone 2 must omit Exemplar Value row when compliance is suspended:\n" + slotDoc,
                slotDoc.contains("<b>Exemplar Value:</b>")
            );
            assertTrue(
                "Zone 3 must preserve Valid Lexical & Syntax Forms:\n" + slotDoc,
                slotDoc.contains("<b>Valid Lexical &amp; Syntax Forms:</b>")
            );
        }

        // 3. Hover on healthy sibling slot '5000': verify normal constraint violation and Exemplar Value presence
        int port5000Offset = text.indexOf("5000", bodyIdx);
        String port5000Doc = getDocAtOffset(text, port5000Offset + 1);
        assertFalse(
            "Healthy sibling slot 5000 must NOT report Suspended (Degraded Schema):\n" + port5000Doc,
            port5000Doc.contains("Suspended (Degraded Schema)")
        );
        assertTrue(
            "Healthy sibling slot 5000 must render Exemplar Value row:\n" + port5000Doc,
            port5000Doc.contains("<b>Exemplar Value:</b>")
        );
    }

    public void testSafeguard4DegradedEnumSubsetBodySlotHoverRendersWarningBannerAndSuspendedCompliance() {
        var text = """
            {
              :defs {
                :SettlementState :Enum [ #CREATED #AUTHORIZED #CAPTURED #SETTLED ]
                :TrapConflictFilter { #filterIncl [ #CREATED #AUTHORIZED ] #filterExcl [ #AUTHORIZED ] } :SettlementState
              }
              :type :TrapConflictFilter
              :body #CREATED
            }
            """;
        var file = myFixture.configureByText("safeguard4_degraded_enum_hover.stvn", text);

        int defsTrapOffset = text.indexOf(":TrapConflictFilter");
        String defsTrapDoc = getDocAtOffset(text, defsTrapOffset + 1);
        assertTrue(
            "Degraded :TrapConflictFilter in :defs must display underlying base type ':Enum' without variant list noise:\n" + defsTrapDoc,
            defsTrapDoc.contains("(underlying base type: <code>:Enum</code>)")
                && !defsTrapDoc.contains("underlying base type: <code>:Enum [")
        );

        int bodyIdx = text.indexOf(":body");
        int bodyCreatedOffset = text.indexOf("#CREATED", bodyIdx);
        String customHoverDoc = getDocAtOffset(text, bodyCreatedOffset + 1);
        assertTrue(
            "Custom hover on degraded :body slot #CREATED must render Value Type trace with :TrapConflictFilter -> :SettlementState -> :Enum:\n" + customHoverDoc,
            customHoverDoc.contains("<b>Value Type:</b> \u26A0 :TrapConflictFilter (-> :SettlementState -> :Enum)")
        );
        assertTrue(
            "Custom hover on degraded :body slot #CREATED must report Suspended (Degraded Schema):\n" + customHoverDoc,
            customHoverDoc.contains("Suspended (Degraded Schema)")
        );
        assertTrue(
            "Custom hover on degraded :body slot #CREATED must report clean underlying base ':Enum':\n" + customHoverDoc,
            customHoverDoc.contains("(underlying base type: <code>:Enum</code>)")
        );

        // Simulate live IDE DocumentationManager path where PsiReference.resolve() resolves #CREATED to EnumDef's ValueKeyword
        var bodyLeaf = file.findElementAt(bodyCreatedOffset + 1);
        assertNotNull(bodyLeaf);
        var bodyValKw = com.intellij.psi.util.PsiTreeUtil.getParentOfType(bodyLeaf, org.stvnadore.psi.ValueKeyword.class, false);
        assertNotNull(bodyValKw);
        var ref = bodyValKw.getReference();
        assertNotNull(ref);
        var resolvedEnumVariant = ref.resolve();
        assertNotNull("PsiReference on #CREATED must still resolve to EnumDef ValueKeyword", resolvedEnumVariant);

        String liveHoverDoc = provider.generateDoc(resolvedEnumVariant, bodyLeaf);
        assertNotNull(liveHoverDoc);
        assertTrue(
            "Live mouse hover via resolved EnumDef target + originalElement in :body must also render degraded Value card:\n" + liveHoverDoc,
            liveHoverDoc.contains("Suspended (Degraded Schema)")
                && liveHoverDoc.contains("<b>Value Type:</b> \u26A0 :TrapConflictFilter (-> :SettlementState -> :Enum)")
        );
    }
}
