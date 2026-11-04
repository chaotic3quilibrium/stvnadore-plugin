package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Platform integration test suite verifying smart temporal mode conversions, epoch timestamp
 * conversions, scalar bound clamping, and exemplar replacement quick-fixes in {@code :body} and
 * {@code #CONST} declarations.
 */
@NullMarked
public final class StvnPayloadValueQuickFixTest extends BasePlatformTestCase {

    public void testDateTimeZoneInOffsetSmartConversionAndCurrentExemplarInDeepTuple() {
        var text = """
            {
              :defs {
                :Port { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } :Int
                :DeepFaultIsolationProbe :Union(
                  :String
                  :Tuple(
                    :Port
                    { #offset } :DateTime
                    { #unsigned #size 8 #maxExcl 100 } :Int
                  )
                )
                :FaultEnvelope :Option( :Either( :String :DeepFaultIsolationProbe ) )
              }
              :type :FaultEnvelope
              :body #Some #Right ( #2 ( 8080 "2026-11-03T20:45:00<caret>[America/Chicago]" 50 ) )
            }
            """;
        myFixture.configureByText("datetime_offset_fix.stvn", text);
        myFixture.doHighlighting();

        List<IntentionAction> intentions = myFixture.getAllQuickFixes();
        String expectedConvertLabel = "Convert to #offset :DateTime '\"2026-11-03T20:45:00-06:00\"'";
        IntentionAction convertFix = intentions.stream()
            .filter(a -> expectedConvertLabel.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected smart conversion quick-fix '" + expectedConvertLabel + "', found: "
            + intentions.stream().map(IntentionAction::getText).toList(), convertFix);

        boolean hasCurrentExemplar = intentions.stream()
            .anyMatch(a -> "Replace with current #offset :DateTime".equals(a.getText()));
        assertTrue("Expected 'Replace with current #offset :DateTime' quick-fix", hasCurrentExemplar);

        myFixture.launchAction(convertFix);
        assertTrue(
            "Document must contain converted #offset literal '\"2026-11-03T20:45:00-06:00\"'",
            myFixture.getEditor().getDocument().getText().contains("\"2026-11-03T20:45:00-06:00\"")
        );
    }

    public void testDateTimeOffsetInZonedAndAuditedMismatchSmartConversions() {
        var zonedText = """
            {
              :defs {
                :ZonedTs { #zoned } :DateTime
              }
              :type :ZonedTs
              :body "2026-11-03T20:45:00-06:00<caret>[America/Chicago]"
            }
            """;
        myFixture.configureByText("datetime_zoned_fix.stvn", zonedText);
        myFixture.doHighlighting();

        String expectedZonedLabel = "Convert to #zoned :DateTime '\"2026-11-03T20:45:00[America/Chicago]\"'";
        IntentionAction zonedFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> expectedZonedLabel.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected '" + expectedZonedLabel + "'", zonedFix);
        myFixture.launchAction(zonedFix);
        assertTrue(
            myFixture.getEditor().getDocument().getText().contains("\"2026-11-03T20:45:00[America/Chicago]\"")
        );

        var auditedText = """
            {
              :defs {
                :AuditedTs { #audited } :DateTime
              }
              :type :AuditedTs
              :body "2026-11-03T20:45:00-04:00<caret>[America/Chicago]"
            }
            """;
        myFixture.configureByText("datetime_audited_fix.stvn", auditedText);
        myFixture.doHighlighting();

        String expectedAuditedLabel = "Convert to #audited :DateTime '\"2026-11-03T20:45:00-06:00[America/Chicago]\"'";
        IntentionAction auditedFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> expectedAuditedLabel.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected '" + expectedAuditedLabel + "'", auditedFix);
        myFixture.launchAction(auditedFix);
        assertTrue(
            myFixture.getEditor().getDocument().getText().contains("\"2026-11-03T20:45:00-06:00[America/Chicago]\"")
        );
    }

    public void testTimeEpochStringToTicksConversionAndLiveExemplarInDeepTuple() {
        var isoEpochText = """
            {
              :defs {
                :NanoTs { #ns } :TimeEpoch
              }
              :type :NanoTs
              :body "2026-11-03T20:45:00<caret>Z"
            }
            """;
        myFixture.configureByText("epoch_iso_convert.stvn", isoEpochText);
        myFixture.doHighlighting();

        String expectedEpochConvert = "Convert timestamp to #ns :TimeEpoch '1793738700000000000'";
        IntentionAction convertEpochFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> expectedEpochConvert.equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected '" + expectedEpochConvert + "', found: "
            + myFixture.getAllQuickFixes().stream().map(IntentionAction::getText).toList(), convertEpochFix);
        myFixture.launchAction(convertEpochFix);
        assertTrue(
            myFixture.getEditor().getDocument().getText().contains(":body 1793738700000000000")
        );

        var unparseableEpochText = """
            {
              :defs {
                :DeepTelemetryMatrix :Union(
                  :String
                  :Tuple(
                    { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
                    { #ns } :TimeEpoch
                    { #maxSize 4096 } :Set( :Tuple( { #unsigned #size 8 } :Int :Boolean ) )
                  )
                )
                :Envelope :Option( :Either( :String :DeepTelemetryMatrix ) )
              }
              :type :Envelope
              :body #Some #Right #2 ( 999 "not-an-<caret>epoch" [ (7 #TRUE) ] )
            }
            """;
        myFixture.configureByText("epoch_live_exemplar.stvn", unparseableEpochText);
        myFixture.doHighlighting();

        IntentionAction replaceEpochFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Replace with current #ns :TimeEpoch".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected 'Replace with current #ns :TimeEpoch'", replaceEpochFix);
        myFixture.launchAction(replaceEpochFix);
        assertFalse(
            "Unparseable string literal must be replaced with integer epoch ticks",
            myFixture.getEditor().getDocument().getText().contains("\"not-an-epoch\"")
        );
    }

    public void testIntegerBoundClampingAndExemplarReplacement() {
        var maxClampText = """
            {
              :defs {
                :BoundedPort { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
              }
              :type :BoundedPort
              :body 99<caret>99
            }
            """;
        myFixture.configureByText("int_max_clamp.stvn", maxClampText);
        myFixture.doHighlighting();

        IntentionAction maxClampFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Clamp value to maximum bound '999'".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected \"Clamp value to maximum bound '999'\", found: "
            + myFixture.getAllQuickFixes().stream().map(IntentionAction::getText).toList(), maxClampFix);
        myFixture.launchAction(maxClampFix);
        assertTrue(myFixture.getEditor().getDocument().getText().contains(":body 999"));

        var minClampText = """
            {
              :defs {
                :BoundedPort { #unsigned #size 16 #minIncl 1 #maxExcl 1000 } :Int
              }
              :type :BoundedPort
              :body -<caret>5
            }
            """;
        myFixture.configureByText("int_min_clamp.stvn", minClampText);
        myFixture.doHighlighting();

        IntentionAction minClampFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Clamp value to minimum bound '1'".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected \"Clamp value to minimum bound '1'\"", minClampFix);
        myFixture.launchAction(minClampFix);
        assertTrue(myFixture.getEditor().getDocument().getText().contains(":body 1"));

        var constOverflowText = """
            {
              :defs {
                #MAX_BYTE { #unsigned #size 8 #maxExcl 100 } :Int 2<caret>50
              }
              :type :Int
              :body 0
            }
            """;
        myFixture.configureByText("const_clamp.stvn", constOverflowText);
        myFixture.enableInspections(new StvnConstantRangeInspection());
        myFixture.doHighlighting();

        IntentionAction constClampFix = myFixture.getAllQuickFixes().stream()
            .filter(a -> "Clamp value to maximum bound '99'".equals(a.getText()))
            .findFirst()
            .orElse(null);
        assertNotNull("Expected \"Clamp value to maximum bound '99'\" on #CONST", constClampFix);
        myFixture.launchAction(constClampFix);
        assertTrue(myFixture.getEditor().getDocument().getText().contains("#MAX_BYTE { #unsigned #size 8 #maxExcl 100 } :Int 99"));
    }
}
