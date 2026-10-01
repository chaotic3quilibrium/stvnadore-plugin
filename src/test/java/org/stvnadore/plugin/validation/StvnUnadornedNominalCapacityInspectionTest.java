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
        myFixture.enableInspections(new InspectionProfileEntry[]{inspection});
    }

    public void testUnadornedNominalWarningsAcrossTypes() {
        myFixture.configureByText("unadorned_all.stvn_inclf",
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

        assertEquals("Expected exactly 4 unadorned nominal warnings (String, Seq, Set, Map)", 4, warnings.size());
        assertTrue(warnings.get(0).getDescription().contains("Nominal string type ':String'"));
        assertTrue(warnings.get(1).getDescription().contains("Nominal collection type ':Seq'"));
        assertTrue(warnings.get(2).getDescription().contains("Nominal collection type ':Set'"));
        assertTrue(warnings.get(3).getDescription().contains("Nominal collection type ':Map'"));
    }

    public void testAllSixIntentionsAvailableOnSeq() {
        var text = """
            {
              :defs {
                :Target :Seq( :Int )
              }
            }
            """;
        myFixture.configureByText("seq_intentions.stvn_inclf", text);
        int offset = text.indexOf(":Seq");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        for (var fixName : EXPECTED_INTENTIONS) {
            var actions = myFixture.filterAvailableIntentions(fixName);
            assertFalse("Expected intention to be available: '" + fixName + "'", actions.isEmpty());
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
        myFixture.configureByText("fix1_string.stvn_inclf", text);
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
        myFixture.configureByText("fix1_seq.stvn_inclf", text);
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
        myFixture.configureByText("fix2_set.stvn_inclf", text);
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
        myFixture.configureByText("fix3_map.stvn_inclf", text);
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
        myFixture.configureByText("fix4_seq.stvn_inclf", text);
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
        myFixture.configureByText("fix5_set.stvn_inclf", text);
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
        myFixture.configureByText("fix6_map.stvn_inclf", text);
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
        myFixture.configureByText("comment_preserve.stvn_inclf", text);
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
        myFixture.configureByText("error_suppress_seq.stvn_inclf", text);
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
        myFixture.configureByText("compliant_all.stvn_inclf",
            """
            {
              :defs {
                :CompliantStr { #maxSize 4096 } :String
                :CompliantSeq { #minSize 0 #maxSize 4096 } :Seq( :Int )
                :CompliantSet { #minSize 1 #maxSize 4096 } :Set( :Int )
                :CompliantMap { #maxSize 1024 } :Map( :String :Int )
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
        myFixture.configureByText("nested_args.stvn_inclf",
            """
            {
              :defs {
                :NestedMap :Map( :String :Int )
              }
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var warnings = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("unadorned"))
            .toList();

        assertEquals("Only top-level :Map should produce warning, not inner :String parameter", 1, warnings.size());
        assertTrue(warnings.get(0).getDescription().contains("Nominal collection type ':Map'"));
    }
}
