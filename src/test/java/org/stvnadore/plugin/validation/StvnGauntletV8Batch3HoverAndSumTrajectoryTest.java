package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.presentation.InlayPresentation;
import com.intellij.codeInsight.hints.presentation.RootInlayPresentation;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.hints.StvnInlayHintsCollector;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.Value;
import org.stvnadore.psi.ValueKeyword;

import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end platform integration test suite for Gauntlet v8 Batch 3 ({@code B3-01}, {@code B3-02},
 * Safeguards 1–3, and {@code Stage 8 Line 98} explicit union string case conversion).
 */
@NullMarked
public final class StvnGauntletV8Batch3HoverAndSumTrajectoryTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider docProvider = new StvnDocumentationProvider();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = true;
        StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;
        StvnSettings.getInstance(getProject()).getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_24;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = true;
            StvnSettings.getInstance(getProject()).getState().showEpochMagnitudeWarnings = true;
            StvnSettings.getInstance(getProject()).getState().timeEpochHourFormat = StvnSettings.TimeEpochHourFormat.HOUR_24;
        } finally {
            super.tearDown();
        }
    }

    private String getHoverDocAtOffset(int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = docProvider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Documentation target at offset " + offset + " must not be null", docElem);
        var doc = docProvider.generateDoc(docElem, elem);
        assertNotNull("Generated HTML doc at offset " + offset + " must not be null", doc);
        return doc;
    }

    private void assertCompilesWithZeroErrors(String text, String fileName) {
        var res = StvnCompiler.compileToResult(text, fileName, StvnParserConfig.DEFAULT);
        List<StvnDiagnostic> errors = res.diagnostics().stream()
            .filter(d -> d.severity() == StvnDiagnostic.DiagnosticSeverity.ERROR)
            .toList();
        assertTrue("Expected 0 Core compilation errors in " + fileName + ", got: " + errors + "\nSource:\n" + text,
            errors.isEmpty());
    }

    private List<String> collectInlayStrings() {
        var file = myFixture.getFile();
        var collector = new StvnInlayHintsCollector(myFixture.getEditor());
        var collected = new ArrayList<String>();
        collector.collect(file, myFixture.getEditor(), new InlayHintsSink() {
            @Override
            public void addInlineElement(int offset, boolean relatesToPrecedingText, @NotNull InlayPresentation presentation, boolean placeAtTheEndOfLine) {
                collected.add(offset + ":" + presentation.toString());
            }

            @Override
            public void addBlockElement(int offset, boolean relatesToPrecedingText, boolean showAbove, int priority, @NotNull InlayPresentation presentation) {
                collected.add(offset + ":" + presentation.toString());
            }

            @Override
            public void addInlineElement(int offset, @NotNull RootInlayPresentation<?> presentation, @NotNull com.intellij.codeInsight.hints.HorizontalConstraints constraints) {
            }

            @Override
            public void addBlockElement(int logicalLine, boolean showAbove, @NotNull RootInlayPresentation<?> presentation, @NotNull com.intellij.codeInsight.hints.BlockConstraints constraints) {
            }
        });
        return collected;
    }

    /**
     * B3-01 (Part 1): Verifies :body #ENUM variant hover Slot Schema and Compliance Status parity
     * across healthy #OPEN, multi-hop sum #OPEN, subset-excluded #CLOSED (E012), brand/type mismatch
     * #OPEN in :OtherEnum and :SubstationPort (E028), Safeguard 2 homonymous packaged :Status enums,
     * undeclared #MELTDOWN, and strict :defs non-injection isolation.
     */
    public void testB301BodyEnumVariantHoverSlotSchemaComplianceHomonymousPackagesAndDefsIsolation() {
        String source = """
            {
              :meta {
                #name "b301_enum_hover"
                #kind #BODY
              }
              :defs {
                :package :pkg/alpha {
                  :Status :Enum[#OK #ERR]
                }
                :package :pkg/beta {
                  :Status :Enum[#OK #WARN]
                }
                :SubstationPort { #unsigned #size 16 #minIncl 1024 #maxExcl 49152 } :Int
                :ValveState :Enum[#CLOSED #OPEN #THROTTLED #BYPASS]
                :ActiveValveState { #filterExcl [#CLOSED] } :ValveState
                :OptPhase :Option(:ActiveValveState)
                :OtherEnum :Enum[#IDLE #RUN]
                :GridFeedId { #minSize 4 #maxSize 32 #regex "^FEED-[A-Z]+-[0-9]{2}$" } :String
                #DEFAULT_FEED_ID :GridFeedId "FEED-NORTH-01"
                :TelemetryMatrix :Tuple(
                  :SubstationPort
                  :ActiveValveState
                  :OptPhase
                  :ActiveValveState
                  :OtherEnum
                  :SubstationPort
                  :ActiveValveState
                  :pkg/beta/Status
                )
              }
              :type :TelemetryMatrix
              :body (
                8080
                #OPEN
                #THROTTLED
                #CLOSED
                #OPEN
                #BYPASS
                #MELTDOWN
                #ERR
              )
            }
            """;
        var file = myFixture.configureByText("b301_enum_hover.stvn", source);
        int bodyIdx = source.indexOf(":body (");

        // 1. Healthy #OPEN on :ActiveValveState preserves Enum Variant header and adds Slot Schema + Satisfied
        int validOpenOffset = source.indexOf("#OPEN\n    #THROTTLED", bodyIdx) + 1;
        String validOpenDoc = getHoverDocAtOffset(validOpenOffset);
        assertTrue("Expected Enum Variant: #OPEN:\n" + validOpenDoc,
            validOpenDoc.contains("<b>Enum Variant:</b> #OPEN"));
        assertTrue("Expected Declared In: :ValveState:\n" + validOpenDoc,
            validOpenDoc.contains("<b>Declared In:</b> <code>:ValveState</code>"));
        assertTrue("Expected Variant Index: 1:\n" + validOpenDoc,
            validOpenDoc.contains("<b>Variant Index:</b> 1"));
        assertTrue("Expected Slot Schema: :ActiveValveState:\n" + validOpenDoc,
            validOpenDoc.contains("<nobr><b>Slot Schema:</b> <code>:ActiveValveState</code></nobr>"));
        assertTrue("Expected Compliance Status: Satisfied:\n" + validOpenDoc,
            validOpenDoc.contains("<b>Compliance Status:</b>") && validOpenDoc.contains(">Satisfied</b>"));
        assertTrue("Expected concise STE variant permission prose:\n" + validOpenDoc,
            validOpenDoc.contains("Variant <code>#OPEN</code> is permitted in <code>:ActiveValveState</code>"));

        // 2. Healthy #THROTTLED inside :OptPhase renders sum trajectory on Slot Schema + Satisfied
        int optPhaseOffset = source.indexOf("#THROTTLED", bodyIdx) + 1;
        String optPhaseDoc = getHoverDocAtOffset(optPhaseOffset);
        assertTrue("Expected Slot Schema with inferred sum trajectory:\n" + optPhaseDoc,
            optPhaseDoc.contains("<nobr><b>Slot Schema:</b> <code>:OptPhase [#Some] (-&gt; :ActiveValveState -&gt; :ValveState -&gt; :Enum)</code></nobr>"));
        assertTrue("Expected Satisfied on :OptPhase #THROTTLED:\n" + optPhaseDoc,
            optPhaseDoc.contains(">Satisfied</b>"));

        // 3. Subset-excluded #CLOSED on :ActiveValveState (E012) -> In Violation + Exemplar Value #OPEN
        int closedOffset = source.indexOf("#CLOSED", bodyIdx) + 1;
        String closedDoc = getHoverDocAtOffset(closedOffset);
        assertTrue("Expected Enum Variant: #CLOSED:\n" + closedDoc,
            closedDoc.contains("<b>Enum Variant:</b> #CLOSED"));
        assertTrue("Expected Slot Schema: :ActiveValveState:\n" + closedDoc,
            closedDoc.contains("<nobr><b>Slot Schema:</b> <code>:ActiveValveState</code></nobr>"));
        assertTrue("Expected In Violation on excluded #CLOSED:\n" + closedDoc,
            closedDoc.contains(">In Violation</b>"));
        assertTrue("Expected Exemplar Value: #OPEN on excluded #CLOSED:\n" + closedDoc,
            closedDoc.contains("<b>Exemplar Value:</b> <code>#OPEN</code>"));

        // 4. Incompatible enum #OPEN placed in :OtherEnum (E028) -> In Violation + Exemplar Value #IDLE
        int wrongEnumOffset = source.indexOf("#OPEN\n    #BYPASS", bodyIdx) + 1;
        String wrongEnumDoc = getHoverDocAtOffset(wrongEnumOffset);
        assertTrue("Expected Slot Schema: :OtherEnum:\n" + wrongEnumDoc,
            wrongEnumDoc.contains("<nobr><b>Slot Schema:</b> <code>:OtherEnum</code></nobr>"));
        assertTrue("Expected In Violation on wrong-enum #OPEN:\n" + wrongEnumDoc,
            wrongEnumDoc.contains(">In Violation</b>"));
        assertTrue("Expected Exemplar Value: #IDLE:\n" + wrongEnumDoc,
            wrongEnumDoc.contains("<b>Exemplar Value:</b> <code>#IDLE</code>"));

        // 5. Enum #BYPASS placed in :SubstationPort (:Int) slot -> In Violation + Exemplar Value 1024
        int wrongIntSlotOffset = source.indexOf("#BYPASS", bodyIdx) + 1;
        String wrongIntDoc = getHoverDocAtOffset(wrongIntSlotOffset);
        assertTrue("Expected Slot Schema: :SubstationPort:\n" + wrongIntDoc,
            wrongIntDoc.contains("<nobr><b>Slot Schema:</b> <code>:SubstationPort</code></nobr>"));
        assertTrue("Expected In Violation on #BYPASS in :SubstationPort slot:\n" + wrongIntDoc,
            wrongIntDoc.contains(">In Violation</b>"));
        assertTrue("Expected Exemplar Value: 1024:\n" + wrongIntDoc,
            wrongIntDoc.contains("<b>Exemplar Value:</b> <code>1024</code>"));

        // 6. Undeclared #MELTDOWN in :body (PsiReference.resolve() == null) -> Payload Value card + In Violation + #OPEN
        int meltdownOffset = source.indexOf("#MELTDOWN", bodyIdx) + 1;
        var meltdownLeaf = file.findElementAt(meltdownOffset);
        var meltdownKw = PsiTreeUtil.getParentOfType(meltdownLeaf, ValueKeyword.class, false);
        assertNotNull(meltdownKw);
        assertNull("Undeclared #MELTDOWN reference must resolve to null",
            meltdownKw.getReference() != null ? meltdownKw.getReference().resolve() : null);
        String meltdownDoc = getHoverDocAtOffset(meltdownOffset);
        assertTrue("Undeclared #MELTDOWN must render Payload Value card:\n" + meltdownDoc,
            meltdownDoc.contains("<b>Payload Value:</b> <code>#MELTDOWN</code>"));
        assertTrue("Undeclared #MELTDOWN must report In Violation:\n" + meltdownDoc,
            meltdownDoc.contains(">In Violation</b>"));
        assertTrue("Undeclared #MELTDOWN must include Exemplar Value #OPEN:\n" + meltdownDoc,
            meltdownDoc.contains("<b>Exemplar Value:</b> <code>#OPEN</code>"));

        // 7. Safeguard 2: #ERR from :pkg/alpha/Status placed in :pkg/beta/Status slot must NEVER false-match homonymous :Status
        int homonymousErrOffset = source.indexOf("#ERR", bodyIdx) + 1;
        String homonymousDoc = getHoverDocAtOffset(homonymousErrOffset);
        assertTrue("Homonymous :pkg/alpha/Status #ERR in :pkg/beta/Status slot must report In Violation:\n" + homonymousDoc,
            homonymousDoc.contains(">In Violation</b>"));

        // 8. :defs isolation: hovering EnumDef and VariantList inside :defs NEVER injects Slot Schema or Compliance Status
        int defsEnumDefOffset = source.indexOf("#OPEN #THROTTLED #BYPASS") + 1;
        String defsEnumDoc = getHoverDocAtOffset(defsEnumDefOffset);
        assertFalse(":defs EnumDef hover must NEVER contain Slot Schema:\n" + defsEnumDoc,
            defsEnumDoc.contains("Slot Schema:"));
        assertFalse(":defs EnumDef hover must NEVER contain Compliance Status:\n" + defsEnumDoc,
            defsEnumDoc.contains("Compliance Status:"));

        int defsVariantListOffset = source.indexOf("[#CLOSED]") + 2;
        String defsFilterDoc = getHoverDocAtOffset(defsVariantListOffset);
        assertFalse(":defs VariantList hover must NEVER contain Slot Schema:\n" + defsFilterDoc,
            defsFilterDoc.contains("Slot Schema:"));
        assertFalse(":defs VariantList hover must NEVER contain Compliance Status:\n" + defsFilterDoc,
            defsFilterDoc.contains("Compliance Status:"));
    }

    /**
     * B3-01 (Part 2) & Safeguard 1: Verifies :body #CONST reference hover (direct, :use-renamed, temporal,
     * multi-hop sum slot, E027 nominal brand mismatch, E012 bound violation), Safeguard 1 :include sourcePath
     * filtering, and strict :defs non-injection isolation.
     */
    public void testB301BodyConstReferenceDirectUseRenamedTemporalBrandMismatchAndIncludeSourcePathFilter() {
        // Safeguard 1 setup: included module with a deliberate error near early offsets that could collide with active file offsets
        myFixture.addFileToProject(
            "broken_include.stvn_di",
            """
            {
              :meta {
                #name "broken_include"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :RemoteSharedFeed { #minSize 4 #maxSize 32 #regex "^FEED-[A-Z]+-[0-9]{2}$" } :String
                #BAD_INC_CONST :RemoteSharedFeed "not_valid_in_included_module"
              }
            }
            """
        );

        String modularBody = """
            {
              :meta {
                #name "b301_const_hover"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "broken_include.stvn_di" ]
                :package :acme/grid {
                  :WaypointCode { #minSize 4 #maxSize 32 #regex "^WP-[A-Z0-9-]+$" } :String
                  #ORIGIN_WP :WaypointCode "WP-NORTH-01"
                }
                :use [ :acme/grid { #ORIGIN_WP #LOCAL_ORIGIN_WP } ]
                :SubstationPort { #unsigned #size 16 #minIncl 1024 #maxExcl 49152 } :Int
                :GridFeedId { #minSize 4 #maxSize 32 #regex "^FEED-[A-Z]+-[0-9]{2}$" } :String
                :PrimaryBusId { #minSize 4 #maxSize 16 #regex "^BUS-[0-9]{2}$" } :String
                :BackupBusId { #minSize 4 #maxSize 16 #regex "^BUS-[0-9]{2}$" } :String
                :EpochMs { #ms } :TimeEpoch
                :DeepFeed :Option(:Either(:SubstationPort :GridFeedId))
                #DEFAULT_FEED_ID :GridFeedId "FEED-NORTH-01"
                #PRIMARY_BUS_01 :PrimaryBusId "BUS-01"
                #WIDE_UNBRANDED_PORT :Int 60000
                #SUSPICIOUS_EPOCH_MS :EpochMs -1
                :ConstEnvelope :Tuple(
                  :GridFeedId
                  :acme/grid/WaypointCode
                  :EpochMs
                  :DeepFeed
                  :BackupBusId
                  :SubstationPort
                )
              }
              :type :ConstEnvelope
              :body (
                #DEFAULT_FEED_ID
                #LOCAL_ORIGIN_WP
                #SUSPICIOUS_EPOCH_MS
                #DEFAULT_FEED_ID
                #PRIMARY_BUS_01
                #WIDE_UNBRANDED_PORT
              )
            }
            """;
        myFixture.configureByText("b301_const_hover.stvn_i", modularBody);
        int bodyIdx = modularBody.indexOf(":body (");

        // 1. Direct #DEFAULT_FEED_ID in :body -> preserves all ConstantDefinition rows + Satisfied
        int directFeedOffset = modularBody.indexOf("#DEFAULT_FEED_ID\n    #LOCAL_ORIGIN_WP", bodyIdx) + 1;
        String directFeedDoc = getHoverDocAtOffset(directFeedOffset);
        assertTrue("Expected Typed Constant: #DEFAULT_FEED_ID:\n" + directFeedDoc,
            directFeedDoc.contains("<b>Typed Constant:</b> <code>#DEFAULT_FEED_ID</code>"));
        assertTrue("Expected Type: :GridFeedId:\n" + directFeedDoc,
            directFeedDoc.contains("<nobr><b>Type:</b> <code>:GridFeedId</code></nobr>"));
        assertTrue("Expected Slot Schema: :GridFeedId:\n" + directFeedDoc,
            directFeedDoc.contains("<nobr><b>Slot Schema:</b> <code>:GridFeedId</code></nobr>"));
        assertTrue("Expected Value: \"FEED-NORTH-01\":\n" + directFeedDoc,
            directFeedDoc.contains("<b>Value:</b> \"FEED-NORTH-01\""));
        assertTrue("Expected Compliance Status: Satisfied:\n" + directFeedDoc,
            directFeedDoc.contains("<b>Compliance Status:</b>") && directFeedDoc.contains(">Satisfied</b>"));

        // 2. :use-renamed #LOCAL_ORIGIN_WP in :body -> Renamed Reference + Source Namespace + Typed Constant + Slot Schema + Satisfied
        int renamedOffset = modularBody.indexOf("#LOCAL_ORIGIN_WP", bodyIdx) + 1;
        String renamedDoc = getHoverDocAtOffset(renamedOffset);
        assertTrue("Expected Renamed Reference header:\n" + renamedDoc,
            renamedDoc.contains("<b>Renamed Reference:</b> #LOCAL_ORIGIN_WP &rarr; #acme/grid/ORIGIN_WP"));
        assertTrue("Expected Source Namespace :acme/grid:\n" + renamedDoc,
            renamedDoc.contains("<b>Source Namespace:</b> :acme/grid"));
        assertTrue("Expected Slot Schema :acme/grid/WaypointCode:\n" + renamedDoc,
            renamedDoc.contains("<nobr><b>Slot Schema:</b> <code>:acme/grid/WaypointCode</code></nobr>"));
        assertTrue("Expected Compliance Status: Satisfied on :use-renamed constant:\n" + renamedDoc,
            renamedDoc.contains(">Satisfied</b>"));

        // 3. Temporal #SUSPICIOUS_EPOCH_MS in :body -> Temporal Scale, UTC Preview, UTC Time, Warning Banner, AND Satisfied
        int epochOffset = modularBody.indexOf("#SUSPICIOUS_EPOCH_MS", bodyIdx) + 1;
        String epochDoc = getHoverDocAtOffset(epochOffset);
        assertTrue("Expected Temporal Scale:\n" + epochDoc,
            epochDoc.contains("<nobr><b>Temporal Scale:</b> <code>{ #ms } :TimeEpoch</code></nobr>"));
        assertTrue("Expected UTC Preview:\n" + epochDoc,
            epochDoc.contains("<b>UTC Preview:</b> <code>1969-12-31T23:59:59.999Z</code>"));
        assertTrue("Expected UTC Time:\n" + epochDoc,
            epochDoc.contains("<b>UTC Time:</b>"));
        assertTrue("Expected Suspicious Epoch Magnitude Warning:\n" + epochDoc,
            epochDoc.contains("Suspicious Epoch Magnitude Warning"));
        assertTrue("Expected Compliance Status: Satisfied:\n" + epochDoc,
            epochDoc.contains(">Satisfied</b>"));

        // 4. #DEFAULT_FEED_ID inside multi-hop sum :DeepFeed -> Slot Schema displays multi-hop sum trajectory + Satisfied
        int deepConstOffset = modularBody.indexOf("#DEFAULT_FEED_ID\n    #PRIMARY_BUS_01", bodyIdx) + 1;
        String deepConstDoc = getHoverDocAtOffset(deepConstOffset);
        assertTrue("Expected multi-hop sum trajectory on Slot Schema:\n" + deepConstDoc,
            deepConstDoc.contains("<nobr><b>Slot Schema:</b> <code>:DeepFeed [#Some] [#Right] (-&gt; :GridFeedId -&gt; :String)</code></nobr>"));
        assertTrue("Expected Satisfied on #DEFAULT_FEED_ID in :DeepFeed:\n" + deepConstDoc,
            deepConstDoc.contains(">Satisfied</b>"));

        // 5. E027 opaque nominal brand mismatch (#PRIMARY_BUS_01 on :BackupBusId) -> In Violation + exact E027 prose + Exemplar
        int brandErrOffset = modularBody.indexOf("#PRIMARY_BUS_01", bodyIdx) + 1;
        String brandErrDoc = getHoverDocAtOffset(brandErrOffset);
        assertTrue("Expected In Violation on E027 brand mismatch:\n" + brandErrDoc,
            brandErrDoc.contains(">In Violation</b>"));
        assertTrue("Expected exact E027 Incompatible nominal type message:\n" + brandErrDoc,
            brandErrDoc.contains("Incompatible nominal type: constant '#PRIMARY_BUS_01' of nominal type ':PrimaryBusId' cannot be assigned to target nominal type ':BackupBusId'"));
        assertTrue("Expected verified Exemplar Value \"BUS-00\":\n" + brandErrDoc,
            brandErrDoc.contains("<b>Exemplar Value:</b> <code>\"BUS-00\"</code>"));

        // 6. E012 bound violation (#WIDE_UNBRANDED_PORT 60000 on :SubstationPort) -> In Violation + Exemplar Value 1024
        int boundErrOffset = modularBody.indexOf("#WIDE_UNBRANDED_PORT", bodyIdx) + 1;
        String boundErrDoc = getHoverDocAtOffset(boundErrOffset);
        assertTrue("Expected In Violation on E012 bound violation:\n" + boundErrDoc,
            boundErrDoc.contains(">In Violation</b>"));
        assertTrue("Expected Exemplar Value 1024:\n" + boundErrDoc,
            boundErrDoc.contains("<b>Exemplar Value:</b> <code>1024</code>"));

        // 7. :defs isolation: ConstantDefinition LHS, RHS, and UseMapAlias in :defs NEVER inject :body Slot Schema or :body Compliance Status
        int defsConstLhsOffset = modularBody.indexOf("#DEFAULT_FEED_ID :GridFeedId") + 1;
        String defsLhsDoc = getHoverDocAtOffset(defsConstLhsOffset);
        assertFalse(":defs ConstantDefinition LHS must NEVER contain Slot Schema:\n" + defsLhsDoc,
            defsLhsDoc.contains("Slot Schema:"));
        assertFalse(":defs ConstantDefinition LHS must NEVER contain Compliance Status:\n" + defsLhsDoc,
            defsLhsDoc.contains("Compliance Status:"));

        int defsUseAliasOffset = modularBody.indexOf("#LOCAL_ORIGIN_WP } ]") + 1;
        String defsUseDoc = getHoverDocAtOffset(defsUseAliasOffset);
        assertFalse(":defs UseMapAlias must NEVER contain Slot Schema:\n" + defsUseDoc,
            defsUseDoc.contains("Slot Schema:"));
        assertFalse(":defs UseMapAlias must NEVER contain Compliance Status:\n" + defsUseDoc,
            defsUseDoc.contains("Compliance Status:"));
    }

    /**
     * B3-02 (Part 1): Verifies multi-hop coproduct trajectory unwrapping across StvnInlayHintsCollector
     * and StvnDocumentationProvider (Value Type, Governing Schema, Compliance Status) for:
     * 1. All-implicit 3-hop anonymous nested sums (:DeepTrajectory)
     * 2. Nominal intermediate coproduct aliases (:OuterOpt -> :MidEither -> :LeafUnion)
     * 3. Mixed explicit + implicit (#Some "ACK-DEEP-07", #Left -42 on nominal and anonymous sums, and #Some #Right #2 "ACK-DEEP-07")
     */
    public void testB302MultiHopCoproductTrajectoryAcrossInlayHintsAndHover() {
        String source = """
            {
              :meta {
                #name "b302_trajectories"
                #kind #BODY
              }
              :defs {
                :AckToken { #minSize 4 #maxSize 32 #regex "^ACK-[A-Z0-9-]+$" } :String
                :CommandFaultCode { #size 16 #minIncl -999 #maxExcl 1000 } :Int
                :DeepTrajectory :Option(:Either(:CommandFaultCode :Union(:Boolean :AckToken :Float)))
                :LeafUnion :Union(:Boolean :AckToken :Float)
                :MidEither :Either(:CommandFaultCode :LeafUnion)
                :OuterOpt :Option(:MidEither)
                :OptEither :Option(:Either(:CommandFaultCode :AckToken))
                :Matrix :Tuple(
                  :DeepTrajectory
                  :OuterOpt
                  :DeepTrajectory
                  :OptEither
                  :Option(:Either(:CommandFaultCode :AckToken))
                  :DeepTrajectory
                )
              }
              :type :Matrix
              :body (
                "ACK-DEEP-07"
                "ACK-NOM-08"
                #Some "ACK-MIX-09"
                #Left -42
                #Left -77
                #Some #Right #2 "ACK-EXP-10"
              )
            }
            """;
        var file = myFixture.configureByText("b302_trajectories.stvn", source);
        var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
        assertNotNull(bodyEntry);
        var tuple = PsiTreeUtil.findChildOfType(bodyEntry, TupleLiteral.class);
        assertNotNull(tuple);
        List<Value> slots = tuple.getValueList();
        assertEquals(6, slots.size());

        // Verify resolveValueType & Inlay Hints in Long Form
        assertEquals(":DeepTrajectory [#Some] [#Right] [#2] (-> :AckToken -> :String)",
            StvnTypeResolver.resolveValueType(slots.get(0)));
        assertEquals(":OuterOpt [#Some] (-> :MidEither [#Right] (-> :LeafUnion [#2] (-> :AckToken -> :String)))",
            StvnTypeResolver.resolveValueType(slots.get(1)));
        assertEquals(":DeepTrajectory #Some [#Right] [#2] (-> :AckToken -> :String)",
            StvnTypeResolver.resolveValueType(slots.get(2)));
        assertEquals(":OptEither [#Some] #Left (-> :CommandFaultCode -> :Int)",
            StvnTypeResolver.resolveValueType(slots.get(3)));
        assertEquals(":Option( :Either( :CommandFaultCode :AckToken ) ) [#Some] #Left",
            StvnTypeResolver.resolveValueType(slots.get(4)));
        assertEquals(":DeepTrajectory #Some #Right #2 (-> :AckToken -> :String)",
            StvnTypeResolver.resolveValueType(slots.get(5)));

        List<String> inlaysLong = collectInlayStrings();
        assertTrue("Inlay collector must emit full 3-hop :DeepTrajectory badge, got: " + inlaysLong,
            inlaysLong.stream().anyMatch(s -> s.endsWith(":DeepTrajectory [#Some] [#Right] [#2] (-> :AckToken -> :String)")));
        assertTrue("Inlay collector must emit full nominal-intermediate :OuterOpt badge, got: " + inlaysLong,
            inlaysLong.stream().anyMatch(s -> s.endsWith(":OuterOpt [#Some] (-> :MidEither [#Right] (-> :LeafUnion [#2] (-> :AckToken -> :String)))")));

        // Verify Short Form (#S / #R / #L) parity
        StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = false;
        assertEquals(":DeepTrajectory [#S] [#R] [#2] (-> :AckToken -> :String)",
            StvnTypeResolver.resolveValueType(slots.get(0)));
        StvnSettings.getInstance(getProject()).getState().useLongFormSumTypes = true;

        // Verify Hover cards across all 6 slots (both outer tag and inner payload literal!)
        int bodyIdx = source.indexOf(":body (");

        String slot0Doc = getHoverDocAtOffset(source.indexOf("\"ACK-DEEP-07\"", bodyIdx) + 2);
        assertTrue("Slot 0 Value Type must show all 3 hops + (:AckToken -> :String):\n" + slot0Doc,
            slot0Doc.contains("<b>Value Type:</b> :DeepTrajectory [#Some] [#Right] [#2] (-> :AckToken -> :String)"));
        assertTrue("Slot 0 Governing Schema must show all 3 hops + (:AckToken -> :String):\n" + slot0Doc,
            slot0Doc.contains("<b>Governing Schema:</b> <code>:DeepTrajectory [#Some] [#Right] [#2] (-> :AckToken -> :String)</code>"));
        assertTrue("Slot 0 Compliance Status must be Satisfied:\n" + slot0Doc,
            slot0Doc.contains(">Satisfied</b>"));

        String slot2InnerDoc = getHoverDocAtOffset(source.indexOf("\"ACK-MIX-09\"", bodyIdx) + 2);
        assertTrue("Slot 2 inner string hover must preserve :DeepTrajectory #Some [#Right] [#2] (-> :AckToken -> :String):\n" + slot2InnerDoc,
            slot2InnerDoc.contains(":DeepTrajectory #Some [#Right] [#2] (-> :AckToken -> :String)"));
        assertTrue("Slot 2 inner string hover must report Satisfied (never false-positive Type mismatch!):\n" + slot2InnerDoc,
            slot2InnerDoc.contains(">Satisfied</b>"));

        String slot4OuterDoc = getHoverDocAtOffset(source.indexOf("#Left -77", bodyIdx) + 1);
        assertTrue("Slot 4 anonymous :Option(:Either(...)) Governing Schema must unwrap leaf (-> :CommandFaultCode -> :Int):\n" + slot4OuterDoc,
            slot4OuterDoc.contains("<b>Governing Schema:</b> <code>:Option( :Either( :CommandFaultCode :AckToken ) ) [#Some] #Left (-> :CommandFaultCode -> :Int)</code>"));
        assertTrue("Slot 4 hover must report Satisfied:\n" + slot4OuterDoc,
            slot4OuterDoc.contains(">Satisfied</b>"));

        String slot4InnerDoc = getHoverDocAtOffset(source.indexOf("-77", bodyIdx) + 1);
        assertTrue("Slot 4 inner -77 Value Type must resolve to :CommandFaultCode (-> :Int):\n" + slot4InnerDoc,
            slot4InnerDoc.contains("<b>Value Type:</b> :CommandFaultCode (-> :Int)"));

        String slot5InnerDoc = getHoverDocAtOffset(source.indexOf("\"ACK-EXP-10\"", bodyIdx) + 2);
        assertTrue("Slot 5 all-explicit inner hover Governing Schema must show #Some #Right #2 (-> :AckToken -> :String):\n" + slot5InnerDoc,
            slot5InnerDoc.contains(":DeepTrajectory #Some #Right #2 (-> :AckToken -> :String)"));
        assertTrue("Slot 5 all-explicit inner hover must report Satisfied:\n" + slot5InnerDoc,
            slot5InnerDoc.contains(">Satisfied</b>"));
    }

    /**
     * B3-02 (Part 2): Verifies clickable inlay hint prefix materialization soundness across steps 0, 1, and 2,
     * including overlapping union branches in :Either where inserting only '#2 ' would trigger E024!
     */
    public void testB302ClickableInlayPrefixMaterializationProducesZeroE024CompileErrors() {
        String template = """
            {
              :meta {
                #name "b302_click"
                #kind #BODY
              }
              :defs {
                :AckToken { #minSize 4 #maxSize 32 #regex "^ACK-[A-Z0-9-]+$" } :String
                :OverlappingLeft :Union(:String :AckToken)
                :DisjointRight :Union(:Boolean :AckToken :Float)
                :AmbiguousOuter :Option(:Either(:OverlappingLeft :DisjointRight))
              }
              :type :AmbiguousOuter
              :body %s
            }
            """;

        // Confirm why prefix materialization is mandatory: bare '#2 "ACK-DEEP-07"' triggers Core E024!
        var bareStep2Result = StvnCompiler.compileToResult(
            template.formatted("#2 \"ACK-DEEP-07\""),
            "b302_bare.stvn",
            StvnParserConfig.DEFAULT
        );
        assertTrue("Sanity check: bare '#2 \"ACK-DEEP-07\"' on :AmbiguousOuter triggers Core E024 ambiguity",
            bareStep2Result.diagnostics().stream().anyMatch(d -> "E024".equals(d.canonicalCode().orElse(""))));

        // Clicking step 0 ([#Some]), step 1 ([#Right]), or step 2 ([#2]) on all-implicit "ACK-DEEP-07"
        String[] expectedMaterialized = {
            "#Some \"ACK-DEEP-07\"",
            "#Some #Right \"ACK-DEEP-07\"",
            "#Some #Right #2 \"ACK-DEEP-07\""
        };
        for (int stepIdx = 0; stepIdx < expectedMaterialized.length; stepIdx++) {
            String initial = template.formatted("\"ACK-DEEP-07\"");
            var file = myFixture.configureByText("b302_click_" + stepIdx + ".stvn", initial);
            assertCompilesWithZeroErrors(file.getText(), "b302_click_" + stepIdx + ".stvn");

            var bodyEntry = PsiTreeUtil.findChildOfType(file, BodyEntry.class);
            assertNotNull(bodyEntry);
            var bodyVal = bodyEntry.getValue();
            assertNotNull(bodyVal);

            assertTrue("Expected step " + stepIdx + " materialization to succeed",
                StvnInlayHintsCollector.materializeInferredSumStep(bodyVal, stepIdx));
            String updated = myFixture.getFile().getText();
            assertTrue("Expected ':body " + expectedMaterialized[stepIdx] + "', got:\n" + updated,
                updated.contains(":body " + expectedMaterialized[stepIdx]));
            assertCompilesWithZeroErrors(updated, "b302_click_" + stepIdx + ".stvn");
        }

        // Mixed '#Right "ACK-DEEP-07"': clicking step 2 ([#2]) materializes '#Some ' at outer offset and '#2 ' at inner offset!
        var mixedFile = myFixture.configureByText("b302_mixed_click.stvn", template.formatted("#Right \"ACK-DEEP-07\""));
        var mixedBody = PsiTreeUtil.findChildOfType(mixedFile, BodyEntry.class);
        assertNotNull(mixedBody);
        assertNotNull(mixedBody.getValue());
        assertTrue(StvnInlayHintsCollector.materializeInferredSumStep(mixedBody.getValue(), 2));
        String mixedUpdated = myFixture.getFile().getText();
        assertTrue("Expected ':body #Some #Right #2 \"ACK-DEEP-07\"', got:\n" + mixedUpdated,
            mixedUpdated.contains(":body #Some #Right #2 \"ACK-DEEP-07\""));
        assertCompilesWithZeroErrors(mixedUpdated, "b302_mixed_click.stvn");
    }

    /**
     * Regression Verification Target (Stage 8 Line 98): On :FourWayUnion :Union(:CommandFaultCode :Boolean :Float :AckToken),
     * '#4 "ack-bad-lowercase"' offers 'Convert string to uppercase '"ACK-BAD-LOWERCASE"'' (Priority.TOP),
     * whereas '#4 "bad_lowercase"' suppresses 'Convert string to uppercase' and offers only verified
     * 'Replace with exemplar value '"ACK-A"''.
     */
    public void testStage8Line98ExplicitUnionStringUppercaseQuickFixAndInvalidSuppression() {
        String source = """
            {
              :meta {
                #name "stage8_line98_union_case"
                #kind #BODY
              }
              :defs {
                :CommandFaultCode { #size 16 #minIncl -999 #maxExcl 1000 } :Int
                :AckToken { #minSize 4 #maxSize 32 #regex "^ACK-[A-Z0-9-]+$" } :String
                :FourWayUnion :Union(:CommandFaultCode :Boolean :Float :AckToken)
              }
              :type :Tuple(:FourWayUnion :FourWayUnion)
              :body (
                #4 "ack-bad-lowercase"
                #4 "bad_lowercase"
              )
            }
            """;
        myFixture.configureByText("stage8_line98_union_case.stvn", source);
        myFixture.doHighlighting();

        // Slot 0: #4 "ack-bad-lowercase" -> Convert string to uppercase '"ACK-BAD-LOWERCASE"' at Priority.TOP
        myFixture.getEditor().getCaretModel().moveToOffset(source.indexOf("\"ack-bad-lowercase\"") + 2);
        List<IntentionAction> slot0Intentions = myFixture.getAvailableIntentions();
        List<String> slot0Labels = slot0Intentions.stream().map(IntentionAction::getText).toList();
        String expectedUpperLabel = "Convert string to uppercase '\"ACK-BAD-LOWERCASE\"'";
        String expectedExemplarLabel = "Replace with exemplar value '\"ACK-A\"'";
        assertTrue("Expected '" + expectedUpperLabel + "' in: " + slot0Labels,
            slot0Labels.contains(expectedUpperLabel));
        assertTrue("Expected '" + expectedExemplarLabel + "' in: " + slot0Labels,
            slot0Labels.contains(expectedExemplarLabel));
        assertTrue("Uppercase fix must rank ahead of exemplar fix: " + slot0Labels,
            slot0Labels.indexOf(expectedUpperLabel) < slot0Labels.indexOf(expectedExemplarLabel));

        IntentionAction upperAction = myFixture.findSingleIntention(expectedUpperLabel);
        if (upperAction instanceof PriorityAction prio) {
            assertEquals(PriorityAction.Priority.TOP, prio.getPriority());
        }
        assertNotNull(myFixture.getIntentionPreviewText(upperAction));

        // Slot 1: #4 "bad_lowercase" -> suppresses Convert string to uppercase, offers verified '"ACK-A"'
        myFixture.getEditor().getCaretModel().moveToOffset(source.indexOf("\"bad_lowercase\"") + 2);
        List<String> slot1Labels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertFalse("Must suppress 'Convert string to uppercase' on '\"bad_lowercase\"': " + slot1Labels,
            slot1Labels.stream().anyMatch(l -> l.startsWith("Convert string to uppercase")));
        assertTrue("Must offer verified exemplar '\"ACK-A\"' on '\"bad_lowercase\"': " + slot1Labels,
            slot1Labels.contains(expectedExemplarLabel));

        // Apply both fixes and verify 0 compilation errors!
        myFixture.launchAction(myFixture.findSingleIntention(expectedExemplarLabel));
        myFixture.getEditor().getCaretModel().moveToOffset(myFixture.getFile().getText().indexOf("\"ack-bad-lowercase\"") + 2);
        myFixture.launchAction(myFixture.findSingleIntention(expectedUpperLabel));
        assertCompilesWithZeroErrors(myFixture.getFile().getText(), "stage8_line98_union_case.stvn");
    }
}
