package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.TypeKeyword;

import java.util.List;

/**
 * Platform test suite verifying atomic batch qualification across all unresolved Prelude types
 * on prelude_and_temporal.stvn without offset displacement or syntax errors.
 */
@NullMarked
public final class StvnBatchQuickFixTest extends BasePlatformTestCase {

    private static final String PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE = """
        {
          // prelude_and_temporal.stvn
          :defs {
            :EpochSeconds   { #s } :TimeEpoch
            :EpochMillis    { #ms } :TimeEpoch
            :EpochNanos     { #ns } :TimeEpoch
            :OffsetDateTime { #offset } :DateTime
            :ZonedDateTime  { #zoned } :DateTime
            :AuditedDateTime { #audited } :DateTime

            :IdUuid         :org/stvnadore/prelude/Uuid
            :IdUlid         :Ulid
            :HashSha256     :Sha256
            :VersionSemVer  :SemVer
            :ContactEmail   :Email
            :NetworkIPv4    :IPv4
            :NetworkPort    :Port
            :RatioPercent   :Percentage
            :RatioProb      :Probability
            :MoneyAmount    :Currency
            :GeoLat         :Latitude
            :GeoLon         :Longitude
          }

          :type :Tuple(
            :EpochSeconds :EpochMillis :EpochNanos :OffsetDateTime :ZonedDateTime :AuditedDateTime
            :IdUuid :IdUlid :HashSha256 :VersionSemVer :ContactEmail
            :NetworkIPv4 :NetworkPort :RatioPercent :RatioProb
            :MoneyAmount :GeoLat :GeoLon
          )

          :body (
            1773532800
            1773532800000
            1773532800000000000
            "2026-03-15T08:00:00-05:00"
            "2026-03-15T08:00:00[America/Chicago]"
            "2026-03-15T08:00:00-05:00[America/Chicago]"

            "123e4567-e89b-12d3-a456-426614174000"
            "01ARZ3NDEKTSV4RRFFQ69G5FAV"
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            "1.2.3-beta.1"
            "developer@example.com"
            "192.168.1.1"
            8443
            99.95
            0.85
            149.99
            32.7767
            -96.7970
          )
        }
        """;

    private static final List<String> EXPECTED_PRELUDE_TOKENS = List.of(
        ":Ulid",
        ":Sha256",
        ":SemVer",
        ":Email",
        ":IPv4",
        ":Port",
        ":Percentage",
        ":Probability",
        ":Currency",
        ":Latitude",
        ":Longitude"
    );

    /**
     * Verifies that executing the batch quick-fix replaces all 11 unadorned Prelude tokens
     * with their canonical FQNIs in a single pass.
     */
    public void testBatchQualifyAllPreludeTypesInFile() {
        myFixture.enableInspections(new StvnUnresolvedTypeInspection());
        myFixture.configureByText("prelude_and_temporal.stvn", PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE);

        // Position caret at unadorned :IPv4
        var offset = PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var batchActions = myFixture.filterAvailableIntentions("Fix all 'Unresolved type reference inspection' problems in file");
        if (batchActions.isEmpty()) {
            // Check alternative label variant
            batchActions = myFixture.filterAvailableIntentions("Fix all 'Unresolved type' problems in file");
        }
        assertFalse("Batch fix-all intention must be available in file", batchActions.isEmpty());

        // Execute batch fix
        myFixture.launchAction(batchActions.get(0));

        var resultText = myFixture.getFile().getText();

        // Assert all 11 tokens are fully qualified
        for (var token : EXPECTED_PRELUDE_TOKENS) {
            var fqni = ":org/stvnadore/prelude/" + token.substring(1);
            assertTrue("Document must contain qualified FQNI: " + fqni, resultText.contains(fqni));
        }

        // Assert zero syntax errors remain in AST
        var errorElements = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiErrorElement.class);
        assertTrue("AST must contain zero PsiErrorElement nodes after batch fix. Found: " + errorElements, errorElements.isEmpty());

        // Assert zero syntax error highlights upon re-highlighting
        var highlights = myFixture.doHighlighting();
        var syntaxErrors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("expected"))
            .toList();
        assertTrue("Re-highlighting must produce zero syntax errors. Found: " + syntaxErrors, syntaxErrors.isEmpty());
    }

    /**
     * Verifies that reverse document order replacement prevents coordinate offset drift.
     */
    public void testBatchFixNoOffsetDrift() {
        myFixture.enableInspections(new StvnUnresolvedTypeInspection());
        myFixture.configureByText("prelude_and_temporal.stvn", PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE);

        var offset = PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE.indexOf(":Ulid");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var batchActions = myFixture.filterAvailableIntentions("Fix all 'Unresolved type reference inspection' problems in file");
        if (batchActions.isEmpty()) {
            batchActions = myFixture.filterAvailableIntentions("Fix all 'Unresolved type' problems in file");
        }
        assertFalse("Batch action must be present", batchActions.isEmpty());
        myFixture.launchAction(batchActions.get(0));

        // Verify that :type Tuple correctly references definitions in :defs without offset truncation
        var typeKeywords = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), TypeKeyword.class);
        assertFalse("TypeKeyword nodes must exist", typeKeywords.isEmpty());

        var fileText = myFixture.getFile().getText();
        assertTrue("Header comment must remain intact", fileText.contains("// prelude_and_temporal.stvn"));
        assertTrue(":defs block must remain intact", fileText.contains(":defs {"));
        assertTrue(":type block must remain intact", fileText.contains(":type :Tuple("));
        assertTrue(":body block must remain intact", fileText.contains(":body ("));
    }

    /**
     * Verifies that single-element qualification continues to function independently.
     */
    public void testSingleElementQualificationRemainsFunctional() {
        myFixture.enableInspections(new StvnUnresolvedTypeInspection());
        myFixture.configureByText("prelude_and_temporal.stvn", PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE);

        var offset = PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var actions = myFixture.filterAvailableIntentions("Qualify as ':org/stvnadore/prelude/IPv4'");
        assertFalse("Single-caret qualification must remain available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var text = myFixture.getFile().getText();
        assertTrue("Target token must be qualified with canonical Prelude FQNI", text.contains(":org/stvnadore/prelude/IPv4"));
        assertTrue("NetworkIPv4 identifier must remain intact", text.contains(":NetworkIPv4"));
        // Other tokens must remain unadorned
        assertTrue("Other tokens must remain unmodified", text.contains(":IdUlid         :Ulid"));
    }

    /**
     * Verifies that the intention menu displays an accurate inspection label and never displays 'Annotator'.
     */
    public void testFixAllMenuLabelDisplaysInspectionNameNotAnnotator() {
        myFixture.enableInspections(new StvnUnresolvedTypeInspection());
        myFixture.configureByText("prelude_and_temporal.stvn", PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE);

        var offset = PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE.indexOf(":IPv4");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var allIntentions = myFixture.getAvailableIntentions().stream()
            .map(IntentionAction::getText)
            .toList();

        var annotatorBatchActions = allIntentions.stream()
            .filter(t -> t.contains("'Annotator'"))
            .toList();

        assertTrue("Intention menu must never contain broken 'Annotator' batch action. Found: " + annotatorBatchActions,
            annotatorBatchActions.isEmpty());
    }

    /**
     * Verifies that executing the batch action produces document mutations (Zero Silent No-Op Invariant).
     */
    public void testZeroSilentNoOp() {
        myFixture.enableInspections(new StvnUnresolvedTypeInspection());
        myFixture.configureByText("prelude_and_temporal.stvn", PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE);

        var originalText = myFixture.getFile().getText();
        var offset = PRELUDE_AND_TEMPORAL_UNQUALIFIED_FIXTURE.indexOf(":Port");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        myFixture.doHighlighting();

        var batchActions = myFixture.filterAvailableIntentions("Fix all 'Unresolved type reference inspection' problems in file");
        if (batchActions.isEmpty()) {
            batchActions = myFixture.filterAvailableIntentions("Fix all 'Unresolved type' problems in file");
        }
        assertFalse("Batch action must be found", batchActions.isEmpty());
        myFixture.launchAction(batchActions.get(0));

        var updatedText = myFixture.getFile().getText();
        assertFalse("Executing batch action must modify document text; silent no-op detected", originalText.equals(updatedText));
    }
}
