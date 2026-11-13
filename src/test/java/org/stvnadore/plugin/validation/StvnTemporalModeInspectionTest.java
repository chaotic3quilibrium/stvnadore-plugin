package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Automated platform test suite validating {@link StvnTemporalModeInspection}.
 * Verifies temporal mode requirements, missing facet diagnostics, bare scale flags,
 * and mutual exclusivity governance.
 */
@NullMarked
public final class StvnTemporalModeInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnTemporalModeInspection()});
    }

    public void testBareTimeEpochRequiresUnitFacetAndQuickFixApplies() {
        var text = """
            {
              :defs {
                :CreationEpoch :TimeEpoch
              }
              :type :CreationEpoch
              :body 1700000000
            }
            """;
        myFixture.configureByText("bare_epoch.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires exactly one scale facet"))
            .toList();
        assertEquals("Expected 1 missing temporal facet error on bare :TimeEpoch", 1, errors.size());
        int offset = text.indexOf(":TimeEpoch");
        assertEquals("Error must pin strictly to :TimeEpoch AtomicType token", offset, errors.get(0).getStartOffset());
        assertEquals(offset + ":TimeEpoch".length(), errors.get(0).getEndOffset());

        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Add temporal facet '#ms'");
        assertFalse("Expected quick-fix to add '#ms' to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #ms in converted text:\n" + result, result.contains("#ms"));
    }

    public void testBareDateTimeRequiresModeFacetAndQuickFixApplies() {
        var text = """
            {
              :defs {
                :EventTimestamp { #equatable #TRUE } :DateTime
              }
              :type :EventTimestamp
              :body "2026-09-21T12:00:00Z"
            }
            """;
        myFixture.configureByText("bare_datetime.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("requires exactly one mode facet"))
            .toList();
        assertEquals("Expected 1 missing temporal facet error on :DateTime", 1, errors.size());

        int offset = text.indexOf(":DateTime");
        assertEquals("Error must pin strictly to :DateTime AtomicType token (excluding preceding MetadataMap)",
            offset, errors.get(0).getStartOffset());
        assertEquals(offset + ":DateTime".length(), errors.get(0).getEndOffset());

        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Add temporal facet '#offset'");
        assertFalse("Expected quick-fix to add '#offset' to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected #offset in converted text", result.contains("#offset"));
    }

    public void testConflictingTemporalModesTriggersError() {
        var text = """
            {
              :defs {
                :ConflictedTime { #offset #zoned } :DateTime
              }
              :type :ConflictedTime
              :body "2026-09-21T12:00:00+00:00[UTC]"
            }
            """;
        myFixture.configureByText("conflicted_temporal.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("mutually exclusive"))
            .toList();
        assertFalse("Expected mutually exclusive temporal modes error", errors.isEmpty());
    }

    public void testConflictingTemporalScalesTriggersError() {
        var text = """
            {
              :defs {
                :ConflictedEpoch { #s #ms } :TimeEpoch
              }
              :type :ConflictedEpoch
              :body 1700000000
            }
            """;
        myFixture.configureByText("conflicted_scales.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("mutually exclusive"))
            .toList();
        assertFalse("Expected mutually exclusive temporal scales error on #s #ms", errors.isEmpty());
    }

    public void testCompliantTemporalDeclarationsProduceZeroErrors() {
        var text = """
            {
              :defs {
                :CompliantEpoch { #s } :TimeEpoch
                :CompliantMillis { #ms } :TimeEpoch
                :CompliantNanos { #ns } :TimeEpoch
                :CompliantZoned { #zoned } :DateTime
                :CompliantAudited { #audited } :DateTime
              }
              :type :CompliantEpoch
              :body 1700000000
            }
            """;
        myFixture.configureByText("compliant_temporal.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && (h.getDescription().contains("requires") || h.getDescription().contains("mutually exclusive")))
            .toList();
        assertTrue("Compliant temporal definitions must produce zero errors", errors.isEmpty());
    }

    public void testAuditedDualTsInapplicableAndMutuallyExclusiveQuickFixes() {
        myFixture.enableInspections(new InspectionProfileEntry[]{
            new StvnMetadataFacetInspection(),
            new StvnTemporalModeInspection()
        });
        var text = """
            {
              :defs {
                :AuditedDualTs { #us #offset #zoned #audited } :DateTime
              }
              :type :AuditedDualTs
              :body "2026-09-21T12:00:00+00:00[UTC]"
            }
            """;
        myFixture.configureByText("audited_dual.stvn", text);
        var highlights = myFixture.doHighlighting();

        var usErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> "#us".equals(h.getText())
                && h.getDescription() != null
                && h.getDescription().contains("ERR_INVALID_METADATA_FACET"))
            .toList();
        assertEquals("Expected inapplicable #us error on :DateTime", 1, usErrors.size());

        var mutexErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null
                && h.getDescription().contains("MUTUALLY_EXCLUSIVE_BOUNDS"))
            .toList();
        assertEquals("Expected 2 mutually exclusive mode errors pinned strictly to i >= 1 (#zoned, #audited)", 2, mutexErrors.size());

        // 0. At #offset (i == 0), verify Alt+Enter intentions are still available via INFORMATION problem
        int offsetModeIdx = text.indexOf("#offset");
        myFixture.getEditor().getCaretModel().moveToOffset(offsetModeIdx + 2);
        assertFalse("Expected 'Keep only #offset' quick-fix on first mode facet",
            myFixture.filterAvailableIntentions("Keep only '#offset' (remove '#zoned', '#audited')").isEmpty());
        assertFalse("Expected 'Remove conflicting facet #offset' quick-fix on first mode facet",
            myFixture.filterAvailableIntentions("Remove conflicting facet '#offset'").isEmpty());

        // 1. At #audited, verify KeepOnlyTemporalFacetQuickFix and RemoveRedundantFacetQuickFix ("Remove conflicting facet") are offered
        int auditedOffset = text.indexOf("#audited");
        myFixture.getEditor().getCaretModel().moveToOffset(auditedOffset + 2);
        var keepAuditedActions = myFixture.filterAvailableIntentions("Keep only '#audited' (remove '#offset', '#zoned')");
        assertFalse("Expected 'Keep only #audited' quick-fix", keepAuditedActions.isEmpty());
        var removeAuditedActions = myFixture.filterAvailableIntentions("Remove conflicting facet '#audited'");
        assertFalse("Expected 'Remove conflicting facet #audited' quick-fix", removeAuditedActions.isEmpty());
        myFixture.launchAction(keepAuditedActions.get(0));

        // 2. At #us, invoke Remove inapplicable facet '#us'
        var afterKeepText = myFixture.getEditor().getDocument().getText();
        int usOffset = afterKeepText.indexOf("#us");
        myFixture.getEditor().getCaretModel().moveToOffset(usOffset + 1);
        myFixture.doHighlighting();
        var removeUsActions = myFixture.filterAvailableIntentions("Remove inapplicable facet '#us'");
        assertFalse("Expected 'Remove inapplicable facet #us' quick-fix", removeUsActions.isEmpty());
        myFixture.launchAction(removeUsActions.get(0));

        var finalResult = myFixture.getEditor().getDocument().getText();
        assertTrue("Expected healed definition ':AuditedDualTs { #audited } :DateTime', got:\n" + finalResult,
            finalResult.contains(":AuditedDualTs { #audited } :DateTime"));
    }

    public void testConflictingTemporalScalesOffersKeepOnlyAndRemoveQuickFixes() {
        var text = """
            {
              :defs {
                :ConflictedEpoch { #s #ms #us } :TimeEpoch
              }
              :type :ConflictedEpoch
              :body 1700000000
            }
            """;
        myFixture.configureByText("conflicted_scales_fix.stvn", text);
        myFixture.doHighlighting();

        int msOffset = text.indexOf("#ms");
        myFixture.getEditor().getCaretModel().moveToOffset(msOffset + 1);
        var keepMsActions = myFixture.filterAvailableIntentions("Keep only '#ms' (remove '#s', '#us')");
        assertFalse("Expected 'Keep only #ms' quick-fix", keepMsActions.isEmpty());
        var removeMsActions = myFixture.filterAvailableIntentions("Remove conflicting facet '#ms'");
        assertFalse("Expected 'Remove conflicting facet #ms' quick-fix", removeMsActions.isEmpty());
        myFixture.launchAction(keepMsActions.get(0));

        var finalResult = myFixture.getEditor().getDocument().getText();
        assertTrue("Expected healed definition ':ConflictedEpoch { #ms } :TimeEpoch', got:\n" + finalResult,
            finalResult.contains(":ConflictedEpoch { #ms } :TimeEpoch"));
    }

    public void testAllThreeDateTimeModeQuickFixesAndAllFourTimeEpochScaleQuickFixesOffered() {
        var dtText = """
            {
              :defs {
                :AuditClock { #equatable #TRUE } :Date<caret>Time
              }
              :type :Int
              :body 1
            }
            """;
        myFixture.configureByText("all_dt_modes.stvn", dtText);
        myFixture.doHighlighting();

        assertFalse("Expected '#offset' quick-fix", myFixture.filterAvailableIntentions("Add temporal facet '#offset'").isEmpty());
        assertFalse("Expected '#zoned' quick-fix", myFixture.filterAvailableIntentions("Add temporal facet '#zoned'").isEmpty());
        var auditedFixes = myFixture.filterAvailableIntentions("Add temporal facet '#audited'");
        assertFalse("Expected '#audited' quick-fix", auditedFixes.isEmpty());
        myFixture.launchAction(auditedFixes.get(0));
        assertTrue("Adding #audited to existing { #equatable #TRUE } must sort to canonical 5-tier order { #audited #equatable #TRUE }:\n"
            + myFixture.getEditor().getDocument().getText(),
            myFixture.getEditor().getDocument().getText().contains(":AuditClock { #audited #equatable #TRUE } :DateTime"));

        var epText = """
            {
              :defs {
                :RawTicks :Time<caret>Epoch
              }
              :type :Int
              :body 1
            }
            """;
        myFixture.configureByText("all_epoch_scales.stvn", epText);
        myFixture.doHighlighting();

        assertFalse("Expected '#ms' quick-fix", myFixture.filterAvailableIntentions("Add temporal facet '#ms'").isEmpty());
        assertFalse("Expected '#s' quick-fix", myFixture.filterAvailableIntentions("Add temporal facet '#s'").isEmpty());
        assertFalse("Expected '#us' quick-fix", myFixture.filterAvailableIntentions("Add temporal facet '#us'").isEmpty());
        var nsFixes = myFixture.filterAvailableIntentions("Add temporal facet '#ns'");
        assertFalse("Expected '#ns' quick-fix", nsFixes.isEmpty());
        myFixture.launchAction(nsFixes.get(0));
        assertTrue("Expected #ns to be added to :RawTicks",
            myFixture.getEditor().getDocument().getText().contains("{ #ns } :TimeEpoch"));
    }
}
