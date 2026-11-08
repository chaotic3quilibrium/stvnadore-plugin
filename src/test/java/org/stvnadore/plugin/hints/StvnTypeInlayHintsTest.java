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

    public void testStage1EnvelopeInlayHintsRenderOnConstraintViolatingSlotAfterPoisonedSibling() {
        var text = """
            {
              :meta {
                #name "gauntlet_v4_stage1_meta_identity_and_section_boundaries"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :ServiceName { #minSize 1 #maxSize 64 } :String
                :RetryCount { #unsigned #size 8 #minIncl 0 #maxExcl 10 } :Int
                :StageFlag :Boolean
                :RedundantSignedInt { #size 32 } :Int
                :PoisonedStringSize { #size 16 } :String

                #DEFAULT_SERVICE :ServiceName "stvn-gauntlet-v4"
                #DEFAULT_RETRIES :RetryCount 3

                :Stage1Envelope :Tuple(
                  :ServiceName
                  :RetryCount
                  :StageFlag
                  :RedundantSignedInt
                  :PoisonedStringSize
                  :RetryCount
                )
              }
              :type :Stage1Envelope
              :body (
                #DEFAULT_SERVICE
                #DEFAULT_RETRIES
                #T
                -42
                "ignored_by_pass3"
                25
              )
            }
            """;
        var file = myFixture.configureByText(
            "gauntlet_v4_stage1_meta_identity_and_section_boundaries.core_test.stvn_i",
            text
        );

        var tuple = PsiTreeUtil.findChildOfType(file, TupleLiteral.class);
        assertNotNull("TupleLiteral must not be null", tuple);
        var values = tuple.getValueList();
        assertEquals("Expected 6 tuple elements", 6, values.size());

        assertEquals(":ServiceName (-> :String)", StvnTypeResolver.resolveValueType(values.get(0)));
        assertEquals(":RetryCount (-> :Int)", StvnTypeResolver.resolveValueType(values.get(1)));
        assertEquals(":StageFlag (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(2)));
        assertEquals(":RedundantSignedInt (-> :Int)", StvnTypeResolver.resolveValueType(values.get(3)));
        assertEquals("\u26A0 :PoisonedStringSize (-> :String)", StvnTypeResolver.resolveValueType(values.get(4)));
        assertTrue(
            "Slot 5 (:PoisonedStringSize) must be marked degraded",
            StvnTypeResolver.isDegradedNominalAlias(file, ":PoisonedStringSize")
        );

        // Slot 6 ('25', failing #maxExcl 10 with E012) must still resolve its healthy :RetryCount (-> :Int) inlay type
        var slot6Val = values.get(5);
        assertFalse(
            "Slot 6 (:RetryCount) must NOT be marked degraded despite following Slot 5 (:PoisonedStringSize)",
            StvnTypeResolver.isDegradedNominalAlias(file, ":RetryCount")
        );
        var slot6CoreVal = StvnTypeResolver.resolveCoreValue(slot6Val);
        assertTrue(
            "Slot 6 ('25') StvnErrorValue must be recoverable for inlay hint projection",
            slot6CoreVal instanceof org.stvnadore.core.ir.StvnValue.StvnErrorValue errVal
                && StvnTypeResolver.isRecoverableCoreError(errVal, slot6Val)
        );
        assertEquals(":RetryCount (-> :Int)", StvnTypeResolver.resolveValueType(slot6Val));
    }

    public void testStage3FloatSlotsFailingBoundsAndPrecisionConstraintsRenderInlayHints() {
        var text = """
            {
              :meta {
                #name "gauntlet_v4_stage3_continuous_vs_exact_floats_and_ieee754"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :Float32Explicit { #size 32 } :Float
                :UnitIntervalFloat32 { #size 32 #minIncl 0.0 #maxExcl 1.0 } :Float
                :ExactDecimal6 { #exact #maxSize 6 } :Float
                :BoundedExactCurrency { #exact #maxSize 12 #minIncl 0.00 #maxExcl 1000000.00 } :Float

                :Stage3Envelope :Tuple(
                  :UnitIntervalFloat32
                  :Float32Explicit
                  :ExactDecimal6
                  :BoundedExactCurrency
                )
              }
              :type :Stage3Envelope
              :body (
                1.0
                3.50e+38
                123456.789012
                1000000.00
              )
            }
            """;
        var file = myFixture.configureByText(
            "gauntlet_v4_stage3_continuous_vs_exact_floats_and_ieee754.core_test.stvn_i",
            text
        );

        var tuple = PsiTreeUtil.findChildOfType(file, TupleLiteral.class);
        assertNotNull("TupleLiteral must not be null", tuple);
        var values = tuple.getValueList();
        assertEquals("Expected 4 tuple elements", 4, values.size());

        String[] expectedTypes = {
            ":UnitIntervalFloat32 (-> :Float)",
            ":Float32Explicit (-> :Float)",
            ":ExactDecimal6 (-> :Float)",
            ":BoundedExactCurrency (-> :Float)"
        };

        for (int i = 0; i < values.size(); i++) {
            var val = values.get(i);
            var coreVal = StvnTypeResolver.resolveCoreValue(val);
            assertTrue(
                "Slot " + i + " ('" + val.getText() + "') must be a recoverable StvnErrorValue",
                coreVal instanceof org.stvnadore.core.ir.StvnValue.StvnErrorValue errVal
                    && StvnTypeResolver.isRecoverableCoreError(errVal, val)
            );
            assertEquals(
                "Slot " + i + " ('" + val.getText() + "') must retain its nominal inlay hint",
                expectedTypes[i],
                StvnTypeResolver.resolveValueType(val)
            );
        }
    }
}
