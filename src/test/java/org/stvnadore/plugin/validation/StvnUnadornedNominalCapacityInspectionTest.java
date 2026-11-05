package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Dedicated platform test suite validating 6 structural capacity Quick-Fixes
 * across unadorned :String, :Seq, :Set, and :Map nominal declarations.
 */
@NullMarked
public final class StvnUnadornedNominalCapacityInspectionTest extends BasePlatformTestCase {

    private StvnStringCapacityInspection inspection = new StvnStringCapacityInspection();

    private static final List<String> EXPECTED_INTENTIONS = List.of(
        "Set nominal capacity to 4096",
        "Set nominal capacity to 0..4096 (allow empty)",
        "Set nominal capacity to 1..4096 (non-empty)",
        "Set nominal capacity to 16777216 (default allocation cap)",
        "Set nominal capacity to 0..16777216 (allow empty)",
        "Set nominal capacity to 1..16777216 (non-empty)"
    );

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        inspection = new StvnStringCapacityInspection();
        myFixture.enableInspections(new InspectionProfileEntry[]{inspection, new StvnMetadataFacetInspection()});
    }

    public void testUnadornedNominalWarningsAcrossTypes() {
        myFixture.configureByText("unadorned_all.stvn",
            """
            {
              :defs {
                :StrAlias :String
                :SeqAlias :Seq( :Int )
                :SetAlias :Set( :Int )
                :MapAlias :Map( :String :Int )
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();

        assertEquals("Expected 5 unadorned nominal warnings (StrAlias, SeqAlias, SetAlias, MapAlias, and inner :String key)", 5, warnings.size());
        assertTrue(warnings.get(0).getDescription().contains("Nominal string type ':StrAlias'"));
        assertTrue("String alias must emit WARN_NOMINAL_STRING_UNADORNED tag",
            warnings.get(0).getDescription().contains("[stvn-plugin:PW002-WARN_NOMINAL_STRING_UNADORNED]"));

        assertTrue(warnings.get(1).getDescription().contains("Nominal collection type ':SeqAlias'"));
        assertTrue("Seq alias must emit WARN_NOMINAL_COLLECTION_UNADORNED tag",
            warnings.get(1).getDescription().contains("[stvn-plugin:PW001-WARN_NOMINAL_COLLECTION_UNADORNED]"));

        assertTrue(warnings.get(2).getDescription().contains("Nominal collection type ':SetAlias'"));
        assertTrue("Set alias must emit WARN_NOMINAL_COLLECTION_UNADORNED tag",
            warnings.get(2).getDescription().contains("[stvn-plugin:PW001-WARN_NOMINAL_COLLECTION_UNADORNED]"));

        assertTrue(warnings.get(3).getDescription().contains("Nominal collection type ':MapAlias'"));
        assertTrue("Map alias must emit WARN_NOMINAL_COLLECTION_UNADORNED tag",
            warnings.get(3).getDescription().contains("[stvn-plugin:PW001-WARN_NOMINAL_COLLECTION_UNADORNED]"));

        assertTrue(warnings.get(4).getDescription().contains("Nominal string type ':String'"));
        assertTrue("Inner Map key :String must emit WARN_NOMINAL_STRING_UNADORNED tag",
            warnings.get(4).getDescription().contains("[stvn-plugin:PW002-WARN_NOMINAL_STRING_UNADORNED]"));
    }

    public void testAllSixIntentionsAvailableOnSeq() {
        var text = """
            {
              :defs {
                :Target :Seq( :Int )
              }
            }
            """;
        myFixture.configureByText("seq_intentions.stvn", text);
        int offset = text.indexOf(":Seq");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity");
        assertEquals("Expected exactly 6 capacity intentions on :Seq", 6, actions.size());
        for (int i = 0; i < EXPECTED_INTENTIONS.size(); i++) {
            assertEquals("Intention at index " + i + " must match canonical sequence",
                EXPECTED_INTENTIONS.get(i), actions.get(i).getText());
        }
    }

    public void testCanonicalIntentionOrderAcrossAllNominalTypes() {
        String[] nominalTargets = {":String", ":Seq", ":Set", ":Map"};
        String fileContent = """
            {
              :defs {
                :T1 :String
                :T2 :Seq( :Int )
                :T3 :Set( :Int )
                :T4 :Map( :String :Int )
              }
            }
            """;
        myFixture.configureByText("all_nominal_order.stvn", fileContent);
        myFixture.doHighlighting();

        for (String target : nominalTargets) {
            int offset = fileContent.indexOf(target);
            myFixture.getEditor().getCaretModel().moveToOffset(offset);

            var actions = myFixture.filterAvailableIntentions("Set nominal capacity");
            assertEquals("Expected 6 capacity intentions for target " + target, 6, actions.size());
            assertEquals("Index 0 must strictly be primary action for target " + target,
                "Set nominal capacity to 4096", actions.get(0).getText());
            for (int i = 0; i < EXPECTED_INTENTIONS.size(); i++) {
                assertEquals("Intention at index " + i + " must match canonical sequence for target " + target,
                    EXPECTED_INTENTIONS.get(i), actions.get(i).getText());
            }
        }
    }

    public void testFix1ApplicationOnString() {
        var text = """
            {
              :defs {
                :MyString :String
              }
            }
            """;
        myFixture.configureByText("fix1_string.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":String"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :MyString { #maxSize 4096 } :String
              }
            }
            """);
    }

    public void testFix1ApplicationOnSeq() {
        var text = """
            {
              :defs {
                :MySeq :Seq( :Int )
              }
            }
            """;
        myFixture.configureByText("fix1_seq.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Seq"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :MySeq { #maxSize 4096 } :Seq( :Int )
              }
            }
            """);
    }

    public void testFix2ApplicationOnSet() {
        var text = """
            {
              :defs {
                :MySet :Set( :Int )
              }
            }
            """;
        myFixture.configureByText("fix2_set.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Set"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 0..4096 (allow empty)");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :MySet { #minSize 0 #maxSize 4096 } :Set( :Int )
              }
            }
            """);
    }

    public void testFix3ApplicationOnMap() {
        var text = """
            {
              :defs {
                :MyMap :Map( :String :Int )
              }
            }
            """;
        myFixture.configureByText("fix3_map.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Map"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 1..4096 (non-empty)");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :MyMap { #minSize 1 #maxSize 4096 } :Map( :String :Int )
              }
            }
            """);
    }

    public void testFix4ApplicationOnSeq() {
        var text = """
            {
              :defs {
                :MySeq :Seq( :Int )
              }
            }
            """;
        myFixture.configureByText("fix4_seq.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Seq"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 16777216 (default allocation cap)");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :MySeq { #maxSize 16777216 } :Seq( :Int )
              }
            }
            """);
    }

    public void testFix5ApplicationOnSet() {
        var text = """
            {
              :defs {
                :MySet :Set( :Int )
              }
            }
            """;
        myFixture.configureByText("fix5_set.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Set"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 0..16777216 (allow empty)");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :MySet { #minSize 0 #maxSize 16777216 } :Set( :Int )
              }
            }
            """);
    }

    public void testFix6ApplicationOnMap() {
        var text = """
            {
              :defs {
                :MyMap :Map( :String :Int )
              }
            }
            """;
        myFixture.configureByText("fix6_map.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Map"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 1..16777216 (non-empty)");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :MyMap { #minSize 1 #maxSize 16777216 } :Map( :String :Int )
              }
            }
            """);
    }

    public void testCommentAndFormattingPreservation() {
        var text = """
            {
              :defs {
                // Leading comment
                :Target
                  :Seq( :Int ) // Trailing comment
              }
            }
            """;
        myFixture.configureByText("comment_preserve.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Seq"));
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
        assertFalse(actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                // Leading comment
                :Target
                  { #maxSize 4096 } :Seq( :Int ) // Trailing comment
              }
            }
            """);
    }

    public void testSuppressionOfFixes4To6UnderErrorSeverity() {
        inspection.configuredSeverity = "ERROR";

        var text = """
            {
              :defs {
                :Target :Seq( :Int )
              }
            }
            """;
        myFixture.configureByText("error_suppress_seq.stvn", text);
        myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(":Seq"));
        myFixture.doHighlighting();

        var primaryActions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
        assertFalse("Fix 1 must remain available under ERROR severity", primaryActions.isEmpty());

        var fix2Actions = myFixture.filterAvailableIntentions("Set nominal capacity to 0..4096 (allow empty)");
        assertFalse("Fix 2 must remain available under ERROR severity", fix2Actions.isEmpty());

        var fix3Actions = myFixture.filterAvailableIntentions("Set nominal capacity to 1..4096 (non-empty)");
        assertFalse("Fix 3 must remain available under ERROR severity", fix3Actions.isEmpty());

        var fix4Actions = myFixture.filterAvailableIntentions("Set nominal capacity to 16777216 (default allocation cap)");
        assertTrue("Fix 4 must be suppressed under ERROR severity", fix4Actions.isEmpty());

        var fix5Actions = myFixture.filterAvailableIntentions("Set nominal capacity to 0..16777216 (allow empty)");
        assertTrue("Fix 5 must be suppressed under ERROR severity", fix5Actions.isEmpty());

        var fix6Actions = myFixture.filterAvailableIntentions("Set nominal capacity to 1..16777216 (non-empty)");
        assertTrue("Fix 6 must be suppressed under ERROR severity", fix6Actions.isEmpty());
    }

    public void testCompliantTypesProduceZeroWarnings() {
        myFixture.configureByText("compliant_all.stvn",
            """
            {
              :defs {
                :CompliantStr { #maxSize 4096 } :String
                :CompliantSeq { #minSize 0 #maxSize 4096 } :Seq( :Int )
                :CompliantSet { #minSize 1 #maxSize 4096 } :Set( :Int )
                :CompliantMap { #maxSize 1024 } :Map( { #maxSize 64 } :String :Int )
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("unadorned") || h.getDescription().contains("exceeding configured threshold")))
            .toList();
        assertTrue("Compliant nominal types must produce zero warnings", warnings.isEmpty());
    }

    public void testNestedTypeArgumentsDoNotProduceDuplicateWarnings() {
        var text = """
            {
              :defs {
                :NestedMap :Map( :String :Int )
              }
            }
            """;
        myFixture.configureByText("nested_args.stvn", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();

        assertEquals("Top-level :NestedMap and inner :String key must each produce 1 distinct warning", 2, warnings.size());
        assertTrue(warnings.get(0).getDescription().contains("Nominal collection type ':NestedMap'"));
        assertTrue(warnings.get(1).getDescription().contains("Nominal string type ':String'"));
        int strOffset = text.indexOf(":String");
        assertEquals("Inner :String warning must anchor strictly to :String", strOffset, warnings.get(1).getStartOffset());
        assertEquals("Inner :String warning end offset must match :String length", strOffset + ":String".length(), warnings.get(1).getEndOffset());
    }

    public void testCompositeInspectionZeroErrorsAfterFixApplicationAcrossAllTypes() {
        String[] targets = {":String", ":Seq", ":Set", ":Map"};
        String fileTemplate = """
            {
              :defs {
                :Target %s
              }
            }
            """;

        String[] typeConstructors = {
            ":String",
            ":Seq( :Int )",
            ":Set( :Int )",
            ":Map( { #maxSize 64 } :String :Int )"
        };

        for (int t = 0; t < targets.length; t++) {
            String constructor = typeConstructors[t];
            for (int fixIdx = 0; fixIdx < EXPECTED_INTENTIONS.size(); fixIdx++) {
                String actionTitle = EXPECTED_INTENTIONS.get(fixIdx);
                String fileContent = fileTemplate.formatted(constructor);
                myFixture.configureByText("composite_" + t + "_" + fixIdx + ".stvn", fileContent);
                myFixture.getEditor().getCaretModel().moveToOffset(fileContent.indexOf(targets[t]));
                myFixture.doHighlighting();

                var actions = myFixture.filterAvailableIntentions(actionTitle);
                assertFalse("Action '" + actionTitle + "' must be available for " + constructor, actions.isEmpty());
                myFixture.launchAction(actions.get(0));

                var highlights = myFixture.doHighlighting();
                var errors = highlights.stream()
                    .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                    .filter(h -> h.getDescription() != null && !h.getDescription().contains("WARN_REDUNDANT_METADATA_FACET"))
                    .toList();
                assertTrue("Applying '" + actionTitle + "' to " + constructor + " must generate code passing StvnMetadataFacetInspection with zero errors. Found: " + errors,
                    errors.isEmpty());

                var warnings = highlights.stream()
                    .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
                    .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
                    .toList();
                assertTrue("Applying '" + actionTitle + "' to " + constructor + " must resolve capacity warning. Found: " + warnings,
                    warnings.isEmpty());
            }
        }
    }

    public void testOverThresholdCapacityReplacementOnMap() {
        var text = """
            {
              :defs {
                :JsonObject { #maxSize 16777216 } :Map( :String :Int )
              }
            }
            """;
        myFixture.configureByText("over_threshold_map.stvn", text);
        int offset = text.indexOf(":Map");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
        assertFalse("Expected Fix 1 to be available for over-threshold :Map", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :JsonObject { #maxSize 4096 } :Map( :String :Int )
              }
            }
            """);

        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        assertTrue("Replacement must produce zero errors. Found: " + errors, errors.isEmpty());
    }

    public void testOverThresholdWithMixedFacetsPreserved() {
        var text = """
            {
              :defs {
                :BadDuplicateMap { #invertible #maxSize 16777216 } :Map( :String :Int )
              }
            }
            """;
        myFixture.configureByText("mixed_facets_map.stvn", text);
        int offset = text.indexOf(":Map");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 0..4096 (allow empty)");
        assertFalse("Expected Fix 2 to be available for mixed-facet :Map", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :BadDuplicateMap { #invertible #minSize 0 #maxSize 4096 } :Map( :String :Int )
              }
            }
            """);
    }

    public void testInlineStringInsideTupleEmitsUnadornedWarningAndQuickFix() {
        var text = """
            {
              :defs {
                :use [ :org/stvnadore/prelude/meta { :StvnPosixId64 :StvnPosixId64 } ]
                :use [ :org/stvnadore/prelude { :Port :Port } ]
                :Values :Tuple(
                  :StvnPosixId64
                  :Port
                  :String
                )
              }
            }
            """;
        myFixture.configureByText("use_nested.json.stvn_i", text);
        int stringOffset = text.indexOf(":String");
        myFixture.getEditor().getCaretModel().moveToOffset(stringOffset);
        var highlights = myFixture.doHighlighting();

        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("WARN_NOMINAL_STRING_UNADORNED"))
            .toList();
        assertEquals("Expected exactly 1 unadorned warning on inline :String inside :Tuple", 1, warnings.size());
        assertEquals("Highlight start offset must match inline :String", stringOffset, warnings.get(0).getStartOffset());

        var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
        assertFalse("Expected 'Set nominal capacity to 4096' quick-fix on inline :String", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        myFixture.checkResult("""
            {
              :defs {
                :use [ :org/stvnadore/prelude/meta { :StvnPosixId64 :StvnPosixId64 } ]
                :use [ :org/stvnadore/prelude { :Port :Port } ]
                :Values :Tuple(
                  :StvnPosixId64
                  :Port
                  { #maxSize 4096 } :String
                )
              }
            }
            """);
    }

    public void testDeepTelemetryMatrixArbitraryDepthWarningsTokenAnchorsAndSequentialQuickFixes() {
        var text = """
            {
              :defs {
                :use [ :org/stvnadore/prelude { :Port :Port } ]
                :DeepTelemetryMatrix :Tuple(
                  :String
                  :Seq(:Tuple(
                    :Port
                    :Map(:String :Tuple(
                      :Boolean
                      :Union(
                        :Port
                        :Tuple(
                          { #offset } :DateTime
                          :Seq(:Tuple(
                            :String
                            :Option(:Either(:String :Union(
                              :Boolean
                              :Tuple(
                                { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
                                { #ns } :TimeEpoch
                                :Set(:Tuple(
                                  { #unsigned #size 8 } :Int
                                  :Boolean
                                ))
                              )
                            )))
                          ))
                        )
                      )
                    ))
                  ))
                )
              }
            }
            """;
        myFixture.configureByText("gauntlet_depth_seven.core_test.stvn_i", text);
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();

        assertEquals("Expected 8 unadorned warnings across depths d=2, 4, 5, 8, 10, 12, 14", 8, warnings.size());

        String[] expectedTokens = {
            ":String", // d=2 Slot 0
            ":Seq",    // d=2 Slot 1
            ":Map",    // d=4
            ":String", // d=5 (key of :Map)
            ":Seq",    // d=8
            ":String", // d=10
            ":String", // d=12 (left branch of :Either)
            ":Set"     // d=14
        };
        String[] expectedCodes = {
            "WARN_NOMINAL_STRING_UNADORNED",
            "WARN_NOMINAL_COLLECTION_UNADORNED",
            "WARN_NOMINAL_COLLECTION_UNADORNED",
            "WARN_NOMINAL_STRING_UNADORNED",
            "WARN_NOMINAL_COLLECTION_UNADORNED",
            "WARN_NOMINAL_STRING_UNADORNED",
            "WARN_NOMINAL_STRING_UNADORNED",
            "WARN_NOMINAL_COLLECTION_UNADORNED"
        };
        String[] expectedUnits = {
            "16777216 characters",
            "16777216 elements",
            "16777216 entries",
            "16777216 characters",
            "16777216 elements",
            "16777216 characters",
            "16777216 characters",
            "16777216 elements"
        };

        for (int i = 0; i < expectedTokens.length; i++) {
            var warning = warnings.get(i);
            String expectedToken = expectedTokens[i];
            String highlightedText = text.substring(warning.getStartOffset(), warning.getEndOffset());
            assertEquals("Highlight range at index " + i + " must pin strictly to token '" + expectedToken + "'",
                expectedToken, highlightedText);
            assertFalse("Highlight range must never span multiple lines", highlightedText.contains("\n"));
            assertTrue("Warning at index " + i + " must contain code " + expectedCodes[i],
                warning.getDescription().contains(expectedCodes[i]));
            assertTrue("Warning at index " + i + " must cite clean type '" + expectedToken + "'",
                warning.getDescription().contains("type '" + expectedToken + "' is unadorned"));
            assertTrue("Warning at index " + i + " must report accurate units '" + expectedUnits[i] + "'",
                warning.getDescription().contains(expectedUnits[i]));
        }

        // Sequentially invoke 'Set nominal capacity to 4096' on all 8 unadorned nodes from shallowest to deepest
        for (int step = 0; step < 8; step++) {
            var currentWarnings = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
                .toList();
            assertEquals("Expected " + (8 - step) + " remaining unadorned warnings before step " + step,
                8 - step, currentWarnings.size());

            myFixture.getEditor().getCaretModel().moveToOffset(currentWarnings.get(0).getStartOffset());
            myFixture.doHighlighting();
            var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
            assertFalse("Expected 'Set nominal capacity to 4096' quick-fix at step " + step, actions.isEmpty());
            myFixture.launchAction(actions.get(0));
        }

        myFixture.checkResult("""
            {
              :defs {
                :use [ :org/stvnadore/prelude { :Port :Port } ]
                :DeepTelemetryMatrix :Tuple(
                  { #maxSize 4096 } :String
                  { #maxSize 4096 } :Seq(:Tuple(
                    :Port
                    { #maxSize 4096 } :Map({ #maxSize 4096 } :String :Tuple(
                      :Boolean
                      :Union(
                        :Port
                        :Tuple(
                          { #offset } :DateTime
                          { #maxSize 4096 } :Seq(:Tuple(
                            { #maxSize 4096 } :String
                            :Option(:Either({ #maxSize 4096 } :String :Union(
                              :Boolean
                              :Tuple(
                                { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
                                { #ns } :TimeEpoch
                                { #maxSize 4096 } :Set(:Tuple(
                                  { #unsigned #size 8 } :Int
                                  :Boolean
                                ))
                              )
                            )))
                          ))
                        )
                      )
                    ))
                  ))
                )
              }
            }
            """);

        var remainingWarnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();
        assertTrue("Expected 0 remaining unadorned warnings after fixing all depths, found: " + remainingWarnings,
            remainingWarnings.isEmpty());
    }

    public void testDeepFaultIsolationProbeTokenScopedHighlightAndQuickFixes() {
        var text = """
            {
              :defs {
                :DeepFaultIsolationProbe :Tuple(
                  :Seq(:Tuple(
                    :String
                    :Set(:Tuple(
                      { #unsigned #size 8 } :Int
                      :Map(:String :Boolean)
                    ))
                  ))
                )
              }
            }
            """;
        myFixture.configureByText("deep_fault_isolation_probe.stvn_i", text);
        var warnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();

        assertEquals("Expected 5 unadorned warnings across :DeepFaultIsolationProbe (:Seq, :String, :Set, :Map, :String)", 5, warnings.size());
        assertEquals(":Seq", text.substring(warnings.get(0).getStartOffset(), warnings.get(0).getEndOffset()));
        assertTrue("Outer :Seq wrapping inner :Map must report 'elements', not 'entries'",
            warnings.get(0).getDescription().contains("16777216 elements"));

        for (int step = 0; step < 5; step++) {
            var currentWarnings = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
                .toList();
            myFixture.getEditor().getCaretModel().moveToOffset(currentWarnings.get(0).getStartOffset());
            myFixture.doHighlighting();
            var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
            assertFalse("Expected quick-fix at step " + step, actions.isEmpty());
            myFixture.launchAction(actions.get(0));
        }

        myFixture.checkResult("""
            {
              :defs {
                :DeepFaultIsolationProbe :Tuple(
                  { #maxSize 4096 } :Seq(:Tuple(
                    { #maxSize 4096 } :String
                    { #maxSize 4096 } :Set(:Tuple(
                      { #unsigned #size 8 } :Int
                      { #maxSize 4096 } :Map({ #maxSize 4096 } :String :Boolean)
                    ))
                  ))
                )
              }
            }
            """);
    }

    public void testConstantDefinitionUnadornedCapacityWarningsAndQuickFixes() {
        var text = """
            {
              :defs {
                #UNADORNED_CONST :Seq(:String) [ "alpha" ]
              }
            }
            """;
        myFixture.configureByText("const_def_unadorned.stvn_di", text);
        var warnings = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();

        assertEquals("Expected 2 unadorned warnings on #UNADORNED_CONST (:Seq and inner :String)", 2, warnings.size());
        assertEquals(":Seq", text.substring(warnings.get(0).getStartOffset(), warnings.get(0).getEndOffset()));
        assertTrue(warnings.get(0).getDescription().contains("WARN_NOMINAL_COLLECTION_UNADORNED"));
        assertEquals(":String", text.substring(warnings.get(1).getStartOffset(), warnings.get(1).getEndOffset()));
        assertTrue(warnings.get(1).getDescription().contains("WARN_NOMINAL_STRING_UNADORNED"));

        for (int step = 0; step < 2; step++) {
            var currentWarnings = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING)
                .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
                .toList();
            myFixture.getEditor().getCaretModel().moveToOffset(currentWarnings.get(0).getStartOffset());
            myFixture.doHighlighting();
            var actions = myFixture.filterAvailableIntentions("Set nominal capacity to 4096");
            assertFalse("Expected quick-fix on #UNADORNED_CONST at step " + step, actions.isEmpty());
            myFixture.launchAction(actions.get(0));
        }

        myFixture.checkResult("""
            {
              :defs {
                #UNADORNED_CONST { #maxSize 4096 } :Seq({ #maxSize 4096 } :String) [ "alpha" ]
              }
            }
            """);
    }
}
