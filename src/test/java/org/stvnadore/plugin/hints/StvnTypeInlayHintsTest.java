package org.stvnadore.plugin.hints;

import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.AliasExpansionMode;
import org.stvnadore.plugin.util.StvnTypePresentationUtil;
import org.stvnadore.psi.TupleLiteral;

/**
 * Platform integration test suite verifying that inlay type hints and hover popups
 * across all 7 temporal variations render distinct scale and mode qualifiers.
 */
@NullMarked
public final class StvnTypeInlayHintsTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider docProvider = new StvnDocumentationProvider();

    public void testTupleAllSevenTemporalInlayHints() {
        var text = """
            {
              :type :Tuple(
                { #ns } :TimeEpoch
                { #us } :TimeEpoch
                { #ms } :TimeEpoch
                { #s } :TimeEpoch
                { #offset } :DateTime
                { #zoned } :DateTime
                { #audited } :DateTime
              )
              :body (
                1704067200000000000
                1704067200000000
                1704067200000
                1704067200
                "2026-03-15T08:00:00-05:00"
                "2026-03-15T08:00:00[America/Chicago]"
                "2026-03-15T08:00:00-05:00[America/Chicago]"
              )
            }
            """;
        var file = myFixture.configureByText("seven_temporals.stvn", text);

        var tuple = PsiTreeUtil.findChildOfType(file, TupleLiteral.class);
        assertNotNull("TupleLiteral must not be null", tuple);
        var values = tuple.getValueList();
        assertEquals("Expected 7 tuple elements", 7, values.size());

        String[] expectedTypes = {
            "{ #ns } :TimeEpoch",
            "{ #us } :TimeEpoch",
            "{ #ms } :TimeEpoch",
            "{ #s } :TimeEpoch",
            "{ #offset } :DateTime",
            "{ #zoned } :DateTime",
            "{ #audited } :DateTime"
        };

        for (int i = 0; i < 7; i++) {
            var val = values.get(i);
            var resolvedType = StvnTypeResolver.resolveValueType(val);
            assertEquals("Inferred type for element " + i + " must include temporal qualifier",
                expectedTypes[i], resolvedType);

            // Verify inlay hint presentation formatter under FULL mode
            var formattedHint = StvnTypePresentationUtil.formatAliasChain(resolvedType, AliasExpansionMode.FULL);
            assertTrue("Formatted hint must be present", formattedHint.isPresent());
            assertEquals("Inlay badge must match qualified type", expectedTypes[i], formattedHint.get());

            // Verify hover documentation popup
            var leaf = file.findElementAt(val.getTextOffset());
            assertNotNull("Leaf element must not be null", leaf);
            var docElem = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, val.getTextOffset());
            assertNotNull("Doc element must not be null", docElem);
            var hoverDoc = docProvider.generateDoc(docElem, leaf);
            assertNotNull("Hover doc must not be null", hoverDoc);
            assertTrue("Hover doc must contain qualified Value Type: " + expectedTypes[i],
                hoverDoc.contains("<b>Value Type:</b> " + expectedTypes[i]));
        }
    }

    public void testNominalTemporalAliasInlayHintsAndHover() {
        var text = """
            {
              :defs {
                :TimestampNs { #ns } :TimeEpoch
                :EventOffset { #offset } :DateTime
              }
              :type :Tuple( :TimestampNs :EventOffset )
              :body (
                1704067200000000000
                "2026-03-15T08:00:00-05:00"
              )
            }
            """;
        var file = myFixture.configureByText("nominal_temporals.stvn", text);

        var tuple = PsiTreeUtil.findChildOfType(file, TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(2, values.size());

        // 1. :TimestampNs element
        var epochVal = values.get(0);
        var epochType = StvnTypeResolver.resolveValueType(epochVal);
        assertEquals(":TimestampNs (-> { #ns } :TimeEpoch)", epochType);

        var epochLeaf = file.findElementAt(epochVal.getTextOffset());
        assertNotNull(epochLeaf);
        var epochDocElem = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, epochLeaf, epochVal.getTextOffset());
        assertNotNull(epochDocElem);
        var epochDoc = docProvider.generateDoc(epochDocElem, epochLeaf);
        assertNotNull(epochDoc);
        assertTrue(epochDoc.contains("<b>Value Type:</b> :TimestampNs (-> { #ns } :TimeEpoch)"));

        // 2. :EventOffset element
        var dtVal = values.get(1);
        var dtType = StvnTypeResolver.resolveValueType(dtVal);
        assertEquals(":EventOffset (-> { #offset } :DateTime)", dtType);

        var dtLeaf = file.findElementAt(dtVal.getTextOffset());
        assertNotNull(dtLeaf);
        var dtDocElem = docProvider.getCustomDocumentationElement(myFixture.getEditor(), file, dtLeaf, dtVal.getTextOffset());
        assertNotNull(dtDocElem);
        var dtDoc = docProvider.generateDoc(dtDocElem, dtLeaf);
        assertNotNull(dtDoc);
        assertTrue(dtDoc.contains("<b>Value Type:</b> :EventOffset (-> { #offset } :DateTime)"));
    }
}
