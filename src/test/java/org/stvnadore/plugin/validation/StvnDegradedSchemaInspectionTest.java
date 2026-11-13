package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class StvnDegradedSchemaInspectionTest extends BasePlatformTestCase {

    public void testInspectionDisabledByDefault() {
        myFixture.enableInspections(new StvnDegradedSchemaInspection());
        myFixture.configureByText("degraded_default.stvn",
            "{\n" +
            "  :defs {\n" +
            "    :BrokenRegex { #regex \"[\" } :String\n" +
            "  }\n" +
            "  :type :BrokenRegex\n" +
            "  :body \"payload_value\"\n" +
            "}"
        );

        var highlights = myFixture.doHighlighting();
        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        assertEquals("Zero weak warnings expected when inspection toggle is false", 0, weakWarnings.size());
    }

    public void testInspectionHighlightsPayloadWhenEnabled() {
        var inspection = new StvnDegradedSchemaInspection();
        inspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(inspection);

        myFixture.configureByText("degraded_enabled.stvn",
            "{\n" +
            "  :defs {\n" +
            "    :BrokenRegex { #regex \"[\" } :String\n" +
            "  }\n" +
            "  :type :BrokenRegex\n" +
            "  :body \"payload_value\"\n" +
            "}"
        );

        var highlights = myFixture.doHighlighting();
        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        assertEquals("Exactly 1 weak warning expected on body payload when enabled", 1, weakWarnings.size());
        assertTrue(weakWarnings.get(0).getDescription().contains(
            "Value bound to degraded schema ':BrokenRegex' (underlying base ':String' \u2014 payload evaluation is suspended)"
        ));
        
        var text = myFixture.getEditor().getDocument().getText();
        var bodyOffset = text.indexOf("\"payload_value\"");
        assertEquals(bodyOffset, weakWarnings.get(0).getStartOffset());
    }

    public void testDegradedSchemaInspectionStrictTargetAttributionUhohPayloads() {
        var inspection = new StvnDegradedSchemaInspection();
        inspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(inspection);

        var content = """
            {
              :defs {
                :FloatExactA { #exact } :Float
                :Float32A { #size 32 } :Float
                :FloatExactAToExact { #exact } :FloatExactA
                :FloatExactATo32 { #size 32 } :FloatExactA
              }
              :type :Tuple( :FloatExactA :FloatExactAToExact :FloatExactATo32 )
              :body (
                0.1
                1.23
                -4.56
              )
            }
            """;
        myFixture.configureByText("uhoh_inspection.stvn", content);
        var highlights = myFixture.doHighlighting();

        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        // Must produce exactly 2 weak warnings: for 1.23 and -4.56
        assertEquals("Must produce exactly 2 weak warnings on degraded payload literals", 2, weakWarnings.size());

        // Assert 0.1 has ZERO weak warnings
        var text = myFixture.getEditor().getDocument().getText();
        int offset01 = text.indexOf("0.1");
        int end01 = offset01 + "0.1".length();
        boolean hasWarningOn01 = weakWarnings.stream()
            .anyMatch(w -> w.getStartOffset() >= offset01 && w.getEndOffset() <= end01);
        assertFalse("Clean ancestor literal 0.1 must receive zero degraded schema warnings", hasWarningOn01);

        // Assert 1.23 has degraded schema warning for :FloatExactAToExact
        int offset123 = text.indexOf("1.23");
        var warning123 = weakWarnings.stream()
            .filter(w -> w.getStartOffset() == offset123)
            .findFirst()
            .orElse(null);
        assertNotNull("Literal 1.23 must receive degraded warning", warning123);
        assertNotNull(warning123.getDescription());
        assertTrue(warning123.getDescription().contains(
            "Value bound to degraded schema ':FloatExactAToExact' (underlying base ':Float' \u2014 payload evaluation is suspended)"
        ));

        // Assert -4.56 has degraded schema warning for :FloatExactATo32
        int offsetNeg456 = text.indexOf("-4.56");
        var warningNeg456 = weakWarnings.stream()
            .filter(w -> w.getStartOffset() == offsetNeg456)
            .findFirst()
            .orElse(null);
        assertNotNull("Literal -4.56 must receive degraded warning", warningNeg456);
        assertNotNull(warningNeg456.getDescription());
        assertTrue(warningNeg456.getDescription().contains(
            "Value bound to degraded schema ':FloatExactATo32' (underlying base ':Float' \u2014 payload evaluation is suspended)"
        ));
    }

    public void testStage12UnguardedTupleCycleSlotSuspensionAndFaultIsolation() {
        var inspection = new StvnDegradedSchemaInspection();
        inspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(inspection);

        var content = """
            {
              :meta {
                #name "inv_stage12_circular_type_body_suspension_and_fault_isolation"
                #kind #BODY_INCLUDE
              }
              :defs {
                :CycleAlpha :CycleBeta
                :CycleBeta :CycleAlpha
                :DerivedFromCycle :CycleAlpha
                :NodeWeight { #unsigned #size 16 } :Int
                :UnguardedTupleCycle :Tuple(:NodeWeight :UnguardedTupleCycle)
                :TreeLabel { #minSize 1 #maxSize 32 } :String
                :TreeNode :Tuple(
                  :TreeLabel
                  :Seq(:TreeNode)
                )
                :BoundedPort { #unsigned #size 16 #minIncl 1024 #maxExcl 65536 } :Int
                :Stage12Envelope :Tuple(
                  :CycleAlpha
                  :DerivedFromCycle
                  :UnguardedTupleCycle
                  :TreeNode
                  :BoundedPort
                )
              }
              :type :Stage12Envelope
              :body (
                "ungrounded-slot-suspended"
                999
                ( 1 "bad" )
                ( "root" [ ( "child-ok" [] ) ( "" [ ( "grandchild" [] ) ] ) ] )
                80
              )
            }
            """;
        myFixture.configureByText("inv_stage12_circular_type_body_suspension_and_fault_isolation.stvn_i", content);
        var highlights = myFixture.doHighlighting();

        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Value bound to degraded schema"))
            .toList();

        assertEquals(
            "Expected degraded warnings only on the 3 outer ungrounded slots, never on inner '1' or '\"bad\"'",
            3,
            weakWarnings.size()
        );

        var text = myFixture.getEditor().getDocument().getText();
        int tupleSlotOffset = text.indexOf("( 1 \"bad\" )");
        var tupleWarning = weakWarnings.stream()
            .filter(w -> w.getStartOffset() == tupleSlotOffset)
            .findFirst()
            .orElse(null);
        assertNotNull("Outer '( 1 \"bad\" )' must receive degraded schema warning", tupleWarning);
        assertTrue(
            tupleWarning.getDescription().contains(
                "Value bound to degraded schema ':UnguardedTupleCycle' (payload evaluation is suspended for ungrounded schema)"
            )
        );

        int innerBadOffset = text.indexOf("\"bad\"");
        boolean hasInnerWarning = weakWarnings.stream().anyMatch(w -> w.getStartOffset() == innerBadOffset);
        assertFalse("Inner element '\"bad\"' must NOT receive a cascading degraded warning", hasInnerWarning);
    }

    public void testGroundedPoisonedSchemaBodySuspensionWeakWarnings() {
        var inspection = new StvnDegradedSchemaInspection();
        inspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(inspection);

        var content = """
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
        myFixture.configureByText("grounded_poisoned_schema_body_suspension.core_test.stvn_i", content);
        var highlights = myFixture.doHighlighting();

        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        assertEquals("Expected exactly 2 weak warnings on poisoned slots 15 and 999", 2, weakWarnings.size());
        for (var warn : weakWarnings) {
            assertTrue(
                "Expected Option D grounded suspension wording, got: " + warn.getDescription(),
                warn.getDescription().contains(
                    "Value bound to degraded schema ':TrapIntMinExcl' (underlying base ':Int' \u2014 payload evaluation is suspended)"
                )
            );
        }
    }

    public void testPoisonedEnumSubsetConflictFilterReportsCleanEnumBaseTypeWithoutVariantNoise() {
        var inspection = new StvnDegradedSchemaInspection();
        inspection.highlightBodyValuesBoundToDegradedSchemas = true;
        myFixture.enableInspections(inspection);

        var content = """
            {
              :defs {
                :SettlementState :Enum [ #CREATED #AUTHORIZED #CAPTURED #SETTLED ]
                :TrapConflictFilter { #filterIncl [ #CREATED ] #filterExcl [ #SETTLED ] } :SettlementState
              }
              :type :TrapConflictFilter
              :body #CREATED
            }
            """;
        myFixture.configureByText("degraded_enum_conflict_filter.stvn", content);
        var highlights = myFixture.doHighlighting();

        var weakWarnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WEAK_WARNING)
            .toList();

        assertEquals("Expected strictly 1 PW006 weak warning on #CREATED bound to degraded :TrapConflictFilter", 1, weakWarnings.size());
        var desc = weakWarnings.get(0).getDescription();
        assertNotNull(desc);
        assertTrue(
            "PW006 must report clean underlying base ':Enum' without raw variant list noise, got: " + desc,
            desc.contains("Value bound to degraded schema ':TrapConflictFilter' (underlying base ':Enum' \u2014 payload evaluation is suspended)")
        );
        assertFalse("PW006 must not leak raw enum variant list into underlying base label: " + desc, desc.contains("[ #CREATED"));
    }
}
