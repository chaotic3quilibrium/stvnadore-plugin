package org.stvnadore.plugin.documentation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.settings.StvnSettings;

/**
 * Platform integration test suite verifying the Four-Locus Temporal {@code UTC Preview} contract
 * across {@code :TimeEpoch} ({@code #s}, {@code #ms}, {@code #us}, {@code #ns}) and
 * {@code :DateTime} ({@code #offset}, {@code #zoned}, {@code #audited}), including multi-hop
 * {@code #CONST} chains, nested containers, implicit sum wrappers, negative floor ticks,
 * 128-bit {@code BigInteger} proleptic Gregorian dates, and DST-gap suppression.
 */
@NullMarked
public final class StvnTemporalConstantAndLiteralHoverTest extends BasePlatformTestCase {

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

    public void testFourLocusTimeEpochHoverAcrossAllScalesAndMultiHopConstChains() {
        var text = """
            {
              :defs {
                :EpochSec { #s } :TimeEpoch
                :EpochMs { #ms } :TimeEpoch
                :EpochUs { #us } :TimeEpoch
                :EpochNs { #ns } :TimeEpoch
                #RELEASE_SEC :EpochSec 1794504600
                #RELEASE_SEC_HEX :EpochSec 0x6AF5_F798
                #CHAINED_SEC :EpochSec #RELEASE_SEC
                #RELEASE_MS :EpochMs 1794504600500
                #RELEASE_US :EpochUs 1794504600123456
                #RELEASE_NS :EpochNs 1794504600123456789
                :PayloadBundle :Tuple(
                  :EpochSec
                  :Seq( :EpochSec )
                  :Map( :String :EpochMs )
                  :Option( :EpochSec )
                  :Either( :String :EpochUs )
                  :Union( :String :EpochNs )
                  :EpochSec
                )
              }
              :type :PayloadBundle
              :body (
                #RELEASE_SEC
                [ #CHAINED_SEC ]
                { [ "ms" #RELEASE_MS ] }
                #RELEASE_SEC_HEX
                #RELEASE_US
                #2 #RELEASE_NS
                1794504600
              )
            }
            """;
        myFixture.configureByText("four_locus_epoch.stvn", text);
        StvnSettings.getInstance(getProject()).getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_24;
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;

        // Locus 1: :defs LHS #RELEASE_SEC & #CHAINED_SEC
        String lhsSecDoc = getDocAtOffset(text, text.indexOf("#RELEASE_SEC :EpochSec") + 1);
        assertTrue("Locus 1 (#RELEASE_SEC) must render Temporal Scale:\n" + lhsSecDoc,
            lhsSecDoc.contains("<b>Temporal Scale:</b> <code>{ #s } :TimeEpoch</code>"));
        assertTrue("Locus 1 (#RELEASE_SEC) must render ISO UTC Preview:\n" + lhsSecDoc,
            lhsSecDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String lhsChainedDoc = getDocAtOffset(text, text.indexOf("#CHAINED_SEC :EpochSec") + 1);
        assertTrue("Locus 1 (#CHAINED_SEC) must resolve multi-hop UTC Preview:\n" + lhsChainedDoc,
            lhsChainedDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));
        assertTrue("Locus 1 (#CHAINED_SEC) must show multi-hop Resolved Value trace:\n" + lhsChainedDoc,
            lhsChainedDoc.contains("<b>Resolved Value:</b> <code>1794504600</code>"));

        // Locus 2: :defs RHS literals (decimal, hex/underscore, fractional #ms/#us/#ns, and chained #RELEASE_SEC)
        String rhsDecDoc = getDocAtOffset(text, text.indexOf("1794504600\n") + 1);
        assertTrue("Locus 2 (:defs RHS literal) must render Constant Value header and :defs locus:\n" + rhsDecDoc,
            rhsDecDoc.contains("<b>Constant Value:</b> <code>1794504600</code>")
                && rhsDecDoc.contains("Constant Definition (<code>:defs</code> clause)")
                && rhsDecDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String rhsHexDoc = getDocAtOffset(text, text.indexOf("0x6AF5_F798") + 2);
        assertTrue("Locus 2 (hex/underscore tick literal) must render identical UTC Preview:\n" + rhsHexDoc,
            rhsHexDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        int chainRhsOffset = text.indexOf("#CHAINED_SEC :EpochSec #RELEASE_SEC") + "#CHAINED_SEC :EpochSec ".length() + 1;
        String rhsChainRefDoc = getDocAtOffset(text, chainRhsOffset);
        assertTrue("Locus 2 (:defs RHS #CONST ref) must show Referenced From #CHAINED_SEC and UTC Preview:\n" + rhsChainRefDoc,
            rhsChainRefDoc.contains("<b>Referenced From:</b> <code>#CHAINED_SEC</code>")
                && rhsChainRefDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String rhsMsDoc = getDocAtOffset(text, text.indexOf("1794504600500") + 1);
        assertTrue("Locus 2 (#ms fractional literal) must trim trailing zeros to .5Z:\n" + rhsMsDoc,
            rhsMsDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00.5Z</code>"));

        String rhsUsDoc = getDocAtOffset(text, text.indexOf("1794504600123456\n") + 1);
        assertTrue("Locus 2 (#us literal) must preserve 6 fractional digits:\n" + rhsUsDoc,
            rhsUsDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00.123456Z</code>"));

        String rhsNsDoc = getDocAtOffset(text, text.indexOf("1794504600123456789") + 1);
        assertTrue("Locus 2 (#ns literal) must preserve 9 fractional digits:\n" + rhsNsDoc,
            rhsNsDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00.123456789Z</code>"));

        // Locus 3: :body #CONST references in Tuple, Seq, Map, implicit Option, implicit Either, and explicit Union
        int bodyIdx = text.indexOf(":body (");
        String bodyTupleRefDoc = getDocAtOffset(text, text.indexOf("#RELEASE_SEC", bodyIdx) + 1);
        assertTrue("Locus 3 (:Tuple slot #CONST) must show Slot Schema and UTC Preview:\n" + bodyTupleRefDoc,
            bodyTupleRefDoc.contains("<b>Slot Schema:</b>")
                && bodyTupleRefDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String bodySeqRefDoc = getDocAtOffset(text, text.indexOf("#CHAINED_SEC", bodyIdx) + 1);
        assertTrue("Locus 3 (:Seq element multi-hop #CONST) must show UTC Preview:\n" + bodySeqRefDoc,
            bodySeqRefDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String bodyMapRefDoc = getDocAtOffset(text, text.indexOf("#RELEASE_MS", bodyIdx) + 1);
        assertTrue("Locus 3 (:Map value #CONST) must show .5Z UTC Preview:\n" + bodyMapRefDoc,
            bodyMapRefDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00.5Z</code>"));

        String bodyOptRefDoc = getDocAtOffset(text, text.indexOf("#RELEASE_SEC_HEX", bodyIdx) + 1);
        assertTrue("Locus 3 (implicit :Option #CONST) must unwrap schema and show UTC Preview:\n" + bodyOptRefDoc,
            bodyOptRefDoc.contains("<b>Slot Schema:</b>")
                && bodyOptRefDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String bodyEitherRefDoc = getDocAtOffset(text, text.indexOf("#RELEASE_US", bodyIdx) + 1);
        assertTrue("Locus 3 (implicit :Either right #CONST) must unwrap schema via matchesSchemaPattern:\n" + bodyEitherRefDoc,
            bodyEitherRefDoc.contains("<b>Slot Schema:</b>")
                && bodyEitherRefDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00.123456Z</code>"));

        String bodyUnionRefDoc = getDocAtOffset(text, text.indexOf("#RELEASE_NS", bodyIdx) + 1);
        assertTrue("Locus 3 (:Union branch #CONST) must show .123456789Z UTC Preview:\n" + bodyUnionRefDoc,
            bodyUnionRefDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00.123456789Z</code>"));

        // Locus 4: :body direct literal
        String bodyDirectDoc = getDocAtOffset(text, text.lastIndexOf("1794504600") + 1);
        assertTrue("Locus 4 (:body direct literal) must show Payload Value, :body locus, and UTC Preview:\n" + bodyDirectDoc,
            bodyDirectDoc.contains("<b>Payload Value:</b> <code>1794504600</code>")
                && bodyDirectDoc.contains("Document Payload Body (<code>:body</code> clause)")
                && bodyDirectDoc.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));
    }

    public void testFourLocusDateTimeHoverAcrossOffsetZonedAndAuditedModes() {
        var text = """
            {
              :defs {
                :OffsetTs { #offset } :DateTime
                :ZonedTs { #zoned } :DateTime
                :AuditedTs { #audited } :DateTime
                #TS_OFFSET :OffsetTs "2026-11-12T17:30:00Z"
                #TS_ZONED :ZonedTs "2026-11-12T11:30:00[America/Chicago]"
                #TS_AUDITED :AuditedTs "2026-11-12T11:30:00-06:00[America/Chicago]"
                #TS_AUDITED_CHAIN :AuditedTs #TS_AUDITED
                :DtBundle :Tuple(
                  :OffsetTs
                  :Option( :ZonedTs )
                  :Either( :Int :AuditedTs )
                  :ZonedTs
                )
              }
              :type :DtBundle
              :body (
                #TS_OFFSET
                #TS_ZONED
                #TS_AUDITED_CHAIN
                "2026-11-12T11:30:00[America/Chicago]"
              )
            }
            """;
        myFixture.configureByText("four_locus_datetime.stvn", text);

        // Locus 1: :defs LHS
        String lhsOffset = getDocAtOffset(text, text.indexOf("#TS_OFFSET :OffsetTs") + 1);
        assertTrue(lhsOffset.contains("<b>Temporal Mode:</b> <code>{ #offset } :DateTime</code>"));
        assertTrue(lhsOffset.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String lhsZoned = getDocAtOffset(text, text.indexOf("#TS_ZONED :ZonedTs") + 1);
        assertTrue(lhsZoned.contains("<b>Temporal Mode:</b> <code>{ #zoned } :DateTime</code>"));
        assertTrue(lhsZoned.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String lhsAudited = getDocAtOffset(text, text.indexOf("#TS_AUDITED_CHAIN :AuditedTs") + 1);
        assertTrue(lhsAudited.contains("<b>Temporal Mode:</b> <code>{ #audited } :DateTime</code>"));
        assertTrue(lhsAudited.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        // Locus 2: :defs RHS string literals
        String rhsZoned = getDocAtOffset(text, text.indexOf("\"2026-11-12T11:30:00[America/Chicago]\"") + 2);
        assertTrue("Locus 2 (:defs RHS #zoned string) must convert America/Chicago to 17:30:00Z:\n" + rhsZoned,
            rhsZoned.contains("<b>Constant Value:</b>")
                && rhsZoned.contains("<b>Temporal Mode:</b> <code>{ #zoned } :DateTime</code>")
                && rhsZoned.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String rhsAudited = getDocAtOffset(text, text.indexOf("\"2026-11-12T11:30:00-06:00[America/Chicago]\"") + 2);
        assertTrue("Locus 2 (:defs RHS #audited string) must convert to 17:30:00Z:\n" + rhsAudited,
            rhsAudited.contains("<b>Temporal Mode:</b> <code>{ #audited } :DateTime</code>")
                && rhsAudited.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        // Locus 3: :body #CONST references (including implicit :Option and implicit :Either)
        int bodyIdx = text.indexOf(":body (");
        String bodyZonedOpt = getDocAtOffset(text, text.indexOf("#TS_ZONED", bodyIdx) + 1);
        assertTrue("Locus 3 (implicit :Option(:ZonedTs) #CONST) must show UTC Preview:\n" + bodyZonedOpt,
            bodyZonedOpt.contains("<b>Slot Schema:</b>")
                && bodyZonedOpt.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        String bodyAuditedEither = getDocAtOffset(text, text.indexOf("#TS_AUDITED_CHAIN", bodyIdx) + 1);
        assertTrue("Locus 3 (implicit :Either(:Int :AuditedTs) chained #CONST) must show UTC Preview:\n" + bodyAuditedEither,
            bodyAuditedEither.contains("<b>Slot Schema:</b>")
                && bodyAuditedEither.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));

        // Locus 4: :body direct string literal
        String bodyDirectZoned = getDocAtOffset(text, text.lastIndexOf("\"2026-11-12T11:30:00[America/Chicago]\"") + 2);
        assertTrue("Locus 4 (:body direct #zoned literal) must show UTC Preview:\n" + bodyDirectZoned,
            bodyDirectZoned.contains("<b>Payload Value:</b>")
                && bodyDirectZoned.contains("<b>UTC Preview:</b> <code>2026-11-12T17:30:00Z</code>"));
    }

    public void testNegativePre1970FloorTicksAnd128BitAstronomicalEpochTicks() {
        var text = """
            {
              :defs {
                :EpochMs { #ms } :TimeEpoch
                :EpochNs { #ns } :TimeEpoch
                #PRE_EPOCH_MS :EpochMs -1
                #PRE_EPOCH_NS :EpochNs -1
                #MAX_I128_NS :EpochNs 170141183460469231731687303715884105727
              }
              :type :Tuple( :EpochMs :EpochNs :EpochNs )
              :body ( -1 -1 170141183460469231731687303715884105727 )
            }
            """;
        myFixture.configureByText("negative_and_128bit_epoch.stvn", text);

        String negMsDoc = getDocAtOffset(text, text.indexOf("#PRE_EPOCH_MS") + 1);
        assertTrue("Negative -1 #ms tick must floor-adjust to 1969-12-31T23:59:59.999Z:\n" + negMsDoc,
            negMsDoc.contains("<b>UTC Preview:</b> <code>1969-12-31T23:59:59.999Z</code>"));

        String negNsDoc = getDocAtOffset(text, text.indexOf("#PRE_EPOCH_NS") + 1);
        assertTrue("Negative -1 #ns tick must floor-adjust to 1969-12-31T23:59:59.999999999Z:\n" + negNsDoc,
            negNsDoc.contains("<b>UTC Preview:</b> <code>1969-12-31T23:59:59.999999999Z</code>"));

        String max128Doc = getDocAtOffset(text, text.lastIndexOf("170141183460469231731687303715884105727") + 2);
        assertTrue("128-bit 2^127-1 #ns tick must format via BigInteger Hinnant math without overflow:\n" + max128Doc,
            max128Doc.contains("<b>UTC Preview:</b> <code>+5391559471918239498981-03-22T13:41:55.884105727Z</code>"));
        assertTrue("128-bit #ns tick must retain Astronomical UTC Time label:\n" + max128Doc,
            max128Doc.contains("Astronomical (> 1 billion years)"));
    }

    public void testMalformedDateTimeAuditedMismatchAndDstGapSuppressUtcPreviewCleanly() {
        var text = """
            {
              :defs {
                :OffsetTs { #offset } :DateTime
                :ZonedTs { #zoned } :DateTime
                :AuditedTs { #audited } :DateTime
                #BAD_OFFSET :OffsetTs "not-a-timestamp"
                #DST_GAP_ZONED :ZonedTs "2026-03-08T02:30:00[America/Chicago]"
                #BAD_AUDITED :AuditedTs "2026-11-12T11:30:00-05:00[America/Chicago]"
              }
              :type :Tuple( :OffsetTs :ZonedTs :AuditedTs )
              :body (
                "not-a-timestamp"
                "2026-03-08T02:30:00[America/Chicago]"
                "2026-11-12T11:30:00-05:00[America/Chicago]"
              )
            }
            """;
        myFixture.configureByText("invalid_and_dst_gap_datetime.stvn", text);

        for (String constName : new String[]{"#BAD_OFFSET", "#DST_GAP_ZONED", "#BAD_AUDITED"}) {
            String lhsDoc = getDocAtOffset(text, text.indexOf(constName) + 1);
            assertFalse("Invalid or DST-gap constant " + constName + " must suppress UTC Preview:\n" + lhsDoc,
                lhsDoc.contains("UTC Preview:"));
        }

        int bodyIdx = text.indexOf(":body (");
        for (String rawLit : new String[]{
            "\"not-a-timestamp\"",
            "\"2026-03-08T02:30:00[America/Chicago]\"",
            "\"2026-11-12T11:30:00-05:00[America/Chicago]\""
        }) {
            String bodyDoc = getDocAtOffset(text, text.indexOf(rawLit, bodyIdx) + 2);
            assertFalse("Invalid or DST-gap body literal " + rawLit + " must suppress UTC Preview:\n" + bodyDoc,
                bodyDoc.contains("UTC Preview:"));
            assertTrue("Invalid or DST-gap body literal must report Violated compliance:\n" + bodyDoc,
                bodyDoc.contains("Violated"));
        }
    }
}
