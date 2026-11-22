package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.configurationStore.XmlSerializer;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.annotations.Transient;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.plugin.actions.StvnCanonicalizationGuard;
import org.stvnadore.plugin.actions.StvnCanonicalizationGuard.DestructiveConstruct;
import org.stvnadore.plugin.actions.StvnReplaceBufferCompactIrAction;
import org.stvnadore.plugin.actions.StvnReplaceBufferPrettyIrAction;
import org.stvnadore.plugin.ir.StvnCompiledDocumentCache;
import org.stvnadore.plugin.settings.StvnConfigurable;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.validation.quickfix.StvnIncludeModuleForPackageQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRemoveUnresolvedUsePackageQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnSearchAvailablePackagesQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnUsePackageLocatorPopup;
import org.stvnadore.plugin.validation.quickfix.StvnUsePackageLocatorPopup.LocatorActionMode;
import org.stvnadore.plugin.validation.quickfix.StvnUsePackageLocatorPopup.LocatorContent;
import org.stvnadore.plugin.validation.quickfix.StvnUsePackageLocatorPopup.LocatorTestSelection;
import org.stvnadore.plugin.validation.quickfix.StvnUsePackageLocatorPopup.OriginKind;
import org.stvnadore.plugin.validation.quickfix.StvnUsePackageLocatorPopup.PackageCandidateEntry;
import org.stvnadore.psi.UseTarget;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end IntelliJ Platform verification suite for Target {@code UUPG-01} (Three-Tier Unresolved
 * {@code :use} Package Quick-Fixes, {@code VirtualFile}-Normalized {@code :include} Deduplication,
 * Mouse-Hover + {@code Alt+Enter} Availability, and Interactive {@code StvnUsePackageLocatorPopup})
 * and Target {@code UUPG-02} (Itemized 7-Category {@code StvnCanonicalizationGuard}, EDT Confirmation,
 * {@code Ctrl+Z} Undo Restoration, and {@code confirmDestructiveCanonicalization} Settings Persistence).
 */
@NullMarked
public final class StvnUnresolvedUsePackageAndCanonicalizeGuardTest extends BasePlatformTestCase {

    private static final String STAGE_10A_ROV_UNITS = """
        {
          :meta {
            #name "gauntlet_v9_stage10_base_units"
            #domain "rov_nav"
            #kind #DEFS
          }
          :defs {
            :package :rov/waypoints {
              :WaypointTag { #minSize 3 #maxSize 32 } :String
              #HOME_WAYPOINT :WaypointTag "WP-BASE_00"
            }
            :package :rov/units {
              :DepthMeters { #minIncl 0.0 #maxIncl 12000.0 } :Float
              :HeadingDeg { #unsigned #maxExcl 360 } :Int
              #MAX_ABYSSAL_DEPTH :DepthMeters 10928.0
            }
          }
        }
        """;

    private static final String STAGE_10B_ROV_BRIDGE = """
        {
          :meta {
            #name "gauntlet_v9_stage10_mid_bridge"
            #domain "rov_nav"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "gauntlet_v9_stage10_base_units.rov_nav.stvn_d" ]
            :package :rov/nav {
              :use [
                :rov/waypoints { :WaypointTag :ImportedWaypointTag }
                :rov/units { #strip }
              ]
              :SafeDepthMeters { #maxIncl 11000.0 } :DepthMeters
              :CompassHeading :HeadingDeg
              #ABYSSAL_LIMIT :SafeDepthMeters 10928.0
              :NavFix :Tuple( :ImportedWaypointTag :SafeDepthMeters :CompassHeading )
              #DEEP_TEST_FIX :NavFix ( "WP-TRENCH_01" #ABYSSAL_LIMIT 180 )
            }
          }
        }
        """;

    private static final String HEALED_STAGE_12_TELEMETRY = """
        {
          // Stage 12 header comment preserved during authoring
          :meta {
            #name "gauntlet_v9_stage12_disorder_reordering_and_vop_projections"
            #domain "core_test"
            #kind #BODY
          }
          :defs {
            :package :rov/telemetry {
              :PacketSeq { #unsigned #size 16 #minIncl 1 } :Int
              :TelemetryLabel { #minSize 1 #maxSize 32 } :String
              #INIT_SEQ :PacketSeq 1
            }
            :use [
              :rov/telemetry { #strip } { :TelemetryLabel :LocalTelemetryLabel }
            ]
            :TelemetryFrame :Tuple(
              :PacketSeq
              :LocalTelemetryLabel
            )
          }
          :type :TelemetryFrame
          :body (
            #INIT_SEQ
            "ROV-FRAME-ALPHA"
          )
        }
        """;

    private boolean savedConfirmDestructive = true;
    private boolean savedSuppressWarning = false;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var state = StvnProjectSettings.getInstance(getProject()).getState();
        savedConfirmDestructive = state.confirmDestructiveCanonicalization;
        savedSuppressWarning = state.suppressCanonicalizationWarning;
        state.confirmDestructiveCanonicalization = true;
        state.suppressCanonicalizationWarning = false;
        StvnUsePackageLocatorPopup.setTestSelectionOverride(null);
        StvnCanonicalizationGuard.setConfirmationOverride(null);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            StvnUsePackageLocatorPopup.setTestSelectionOverride(null);
            StvnCanonicalizationGuard.setConfirmationOverride(null);
            var state = StvnProjectSettings.getInstance(getProject()).getState();
            state.confirmDestructiveCanonicalization = savedConfirmDestructive;
            state.suppressCanonicalizationWarning = savedSuppressWarning;
        } finally {
            super.tearDown();
        }
    }

    /**
     * Verifies UUPG-01 Fix 1 + Safeguards 1 and 2:
     * <ul>
     *   <li>1:1 Pass A (0 inspections) and Pass B (26 inspections) {@code E030} highlight and quick-fix parity (`0` duplicates).</li>
     *   <li>Strict {@code PriorityAction} ordering ({@code TOP} &gt; {@code HIGH} &gt; {@code NORMAL}).</li>
     *   <li>Mouse-hover tooltip quick-fix availability while caret rests on {@code :meta} (`0` caret-line blocks).</li>
     *   <li>{@code IntentionPreviewInfo.DIFF} preview and in-place {@code :include} appending + new {@code :include} creation.</li>
     *   <li>{@code ./} {@code VirtualFile}-normalized deduplication, {@code ./} sibling style preservation, and cyclic {@code .stvn_di} guard.</li>
     * </ul>
     */
    public void testUupg01_ModularStage10cFix1AutoIncludePassAPassBParityDotSlashDedupAndCycleGuard() {
        myFixture.addFileToProject(
            "stage10_fix1/gauntlet_v9_stage10_base_units.rov_nav.stvn_d",
            STAGE_10A_ROV_UNITS
        );
        myFixture.addFileToProject(
            "stage10_fix1/gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di",
            STAGE_10B_ROV_BRIDGE
        );

        String stage10cMissingBridgeInclude = """
            {
              :meta {
                #name "gauntlet_v9_stage10_modular_consumer"
                #domain "rov_nav"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [
                  "gauntlet_v9_stage10_base_units.rov_nav.stvn_d"
                ]
                :use [
                  :rov/units { #strip }
                  :rov/waypoints { #strip }
                  :rov/nav { #strip } { :NavFix :BridgeNavFix }
                ]
                :MissionPayload :Tuple( :BridgeNavFix :DepthMeters )
              }
              :type :MissionPayload
              :body ( ( "WP-DOCK_01" 120.5 90 ) 120.5 )
            }
            """;

        // 1. Pass A (0 inspections — default fresh BasePlatformTestCase profile) vs. Pass B (all 26 inspections) parity on modular E030
        InspectionProfileEntry[] all26 = instantiateAll26PluginInspections();
        var file10c = myFixture.addFileToProject(
            "stage10_fix1/gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_i",
            stage10cMissingBridgeInclude
        );
        myFixture.configureFromExistingVirtualFile(file10c.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(file10c.getText().indexOf(":rov/nav") + 3);

        List<HighlightInfo> passAE030 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Unresolved package in :use directive: ':rov/nav'"))
            .toList();
        assertEquals("Pass A must emit exactly 1 E030 highlight on ':rov/nav'", 1, passAE030.size());
        List<String> passAFixes = collectUnresolvedUseIntentionTexts(myFixture.getAvailableIntentions());

        myFixture.enableInspections(all26);
        List<HighlightInfo> passBE030 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Unresolved package in :use directive: ':rov/nav'"))
            .toList();
        assertEquals("Pass B must emit exactly 1 E030 highlight (0 stacked duplicates)", 1, passBE030.size());
        assertEquals(passAE030.get(0).getStartOffset(), passBE030.get(0).getStartOffset());
        assertEquals(passAE030.get(0).getEndOffset(), passBE030.get(0).getEndOffset());

        String expectedFix1 = "Include 'gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di' for package ':rov/nav'";
        String expectedFix2 = "Search available packages for ':rov/nav'...";
        String expectedFix3 = "Remove unresolved package ':rov/nav' from :use";

        List<String> passBFixes = collectUnresolvedUseIntentionTexts(myFixture.getAvailableIntentions());
        assertEquals(
            "Pass A and Pass B must expose identical unresolved :use quick-fixes with 0 duplicates",
            List.of(expectedFix1, expectedFix2, expectedFix3),
            passBFixes
        );
        assertEquals(passAFixes, passBFixes);

        // Verify PriorityAction priorities explicitly
        UseTarget rovNavTarget = findUseTargetByText(file10c, ":rov/nav");
        assertNotNull(rovNavTarget);
        assertEquals(
            PriorityAction.Priority.TOP,
            new StvnIncludeModuleForPackageQuickFix(rovNavTarget, "gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di", ":rov/nav").getPriority()
        );
        assertEquals(
            PriorityAction.Priority.HIGH,
            new StvnSearchAvailablePackagesQuickFix(rovNavTarget, ":rov/nav").getPriority()
        );
        assertEquals(
            PriorityAction.Priority.NORMAL,
            new StvnRemoveUnresolvedUsePackageQuickFix(rovNavTarget, ":rov/nav").getPriority()
        );

        // 2. Safeguard 2: Move caret to :meta line and verify all 3 quick-fixes remain available via getAllQuickFixes()
        myFixture.getEditor().getCaretModel().moveToOffset(file10c.getText().indexOf("#name") + 2);
        List<String> hoverTooltipFixes = collectUnresolvedUseIntentionTexts(myFixture.getAllQuickFixes());
        assertEquals(
            "Mouse-hover tooltip quick-fixes must remain available when caret rests on :meta",
            List.of(expectedFix1, expectedFix2, expectedFix3),
            hoverTooltipFixes
        );

        // 3. IntentionPreviewInfo.DIFF verification + launch Fix 1 (append to existing :include)
        myFixture.getEditor().getCaretModel().moveToOffset(file10c.getText().indexOf(":rov/nav") + 3);
        myFixture.doHighlighting();
        IntentionAction includeAction = myFixture.findSingleIntention(expectedFix1);
        String previewText = myFixture.getIntentionPreviewText(includeAction);
        assertNotNull(previewText);
        assertTrue(
            "DIFF preview must show appended module path",
            previewText.contains("\"gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di\"")
        );
        assertFalse(
            "Preview must not mutate physical buffer",
            file10c.getText().contains("\"gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di\"")
        );

        myFixture.launchAction(includeAction);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(file10c.getText().contains("\"gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di\""));
        var healedSnapshot = StvnCompiledDocumentCache.getSnapshot(file10c);
        assertTrue(
            "Stage 10C must compile with 0 diagnostics after Fix 1 auto-include, got: " + healedSnapshot.diagnostics(),
            healedSnapshot.diagnostics().isEmpty()
        );

        // 4. Safeguard 1: './' VirtualFile-normalized deduplication & './' sibling style preservation
        var dotSlashConsumer = myFixture.addFileToProject(
            "stage10_fix1/dot_slash_consumer.rov_nav.stvn_i",
            """
            {
              :meta {
                #name "dot_slash_consumer"
                #domain "rov_nav"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "./gauntlet_v9_stage10_base_units.rov_nav.stvn_d" ]
                :use [
                  :rov/units { #strip }
                  :rov/nav { #strip }
                ]
              }
              :type :DepthMeters
              :body 42.0
            }
            """
        );
        assertTrue(
            "Module already included via './' must be recognized as already directly included",
            StvnIncludeModuleForPackageQuickFix.hasDirectIncludePath(
                dotSlashConsumer,
                "gauntlet_v9_stage10_base_units.rov_nav.stvn_d"
            )
        );
        myFixture.configureFromExistingVirtualFile(dotSlashConsumer.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(dotSlashConsumer.getText().indexOf(":rov/nav") + 3);
        myFixture.doHighlighting();
        IntentionAction dotSlashIncludeFix = myFixture.findSingleIntention(
            "Include './gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di' for package ':rov/nav'"
        );
        myFixture.launchAction(dotSlashIncludeFix);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(dotSlashConsumer.getText().contains("\"./gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di\""));

        // 5. Fix 1 when primary :defs has NO existing :include statement + cyclic .stvn_di guard
        var noIncludeSchema = myFixture.addFileToProject(
            "stage10_fix1/no_include_consumer.rov_nav.stvn_di",
            """
            {
              :meta {
                #name "no_include_consumer"
                #domain "rov_nav"
                #kind #DEFS_INCLUDE
              }
              :defs {
                // Leading comment inside :defs
                :use [ :rov/units { #strip } ]
                :SubDepth :DepthMeters
              }
            }
            """
        );
        myFixture.configureFromExistingVirtualFile(noIncludeSchema.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(noIncludeSchema.getText().indexOf(":rov/units") + 3);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention(
            "Include 'gauntlet_v9_stage10_base_units.rov_nav.stvn_d' for package ':rov/units'"
        ));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(noIncludeSchema.getText().contains("// Leading comment inside :defs"));
        assertTrue(noIncludeSchema.getText().contains(":include [ \"gauntlet_v9_stage10_base_units.rov_nav.stvn_d\" ]"));
        assertTrue(StvnCompiledDocumentCache.getSnapshot(noIncludeSchema).diagnostics().isEmpty());

        // Cyclic .stvn_di guard: parent includes child; child must NOT be offered parent
        myFixture.addFileToProject(
            "stage10_fix1/cycle_parent.rov_nav.stvn_di",
            """
            {
              :meta { #name "cycle_parent" #domain "rov_nav" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "cycle_child.rov_nav.stvn_di" ]
                :package :rov/parent_only { :ParentId :Int }
              }
            }
            """
        );
        var cycleChild = myFixture.addFileToProject(
            "stage10_fix1/cycle_child.rov_nav.stvn_di",
            """
            {
              :meta { #name "cycle_child" #domain "rov_nav" #kind #DEFS_INCLUDE }
              :defs {
                :use [ :rov/parent_only { #strip } ]
                :ChildId :Int
              }
            }
            """
        );
        myFixture.configureFromExistingVirtualFile(cycleChild.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(cycleChild.getText().indexOf(":rov/parent_only") + 3);
        myFixture.doHighlighting();
        assertTrue(
            "Fix 1 must suppress cycle-inducing .stvn_di modules",
            myFixture.filterAvailableIntentions(
                "Include 'cycle_parent.rov_nav.stvn_di' for package ':rov/parent_only'"
            ).isEmpty()
        );
    }

    /**
     * Verifies UUPG-01 Fix 2 ({@code StvnUsePackageLocatorPopup} &amp; {@code StvnSearchAvailablePackagesQuickFix}):
     * <ul>
     *   <li>Multi-origin indexing across {@code [Prelude]}, {@code [Local :defs]}, {@code [Included: ...]}, and {@code [Workspace: ...]}.</li>
     *   <li>Single-step atomic {@code WriteCommandAction} replacing {@code UseTarget} + appending {@code :include} with {@code 0} offset drift and 1-step {@code Ctrl+Z} Undo.</li>
     *   <li>{@code F4} declaration navigation to workspace modules and Standard Library Prelude.</li>
     *   <li>Hermetic {@code .stvn} ({@code E035}) local package replacement vs. external workspace locate-only navigation.</li>
     *   <li>Live {@code SearchTextField} filtering by exported symbol identifier ({@code "NavFix"}).</li>
     * </ul>
     */
    public void testUupg01_Fix2InteractivePackageLocatorModularAndHermeticSelectAutoIncludeNavigateFilterAndUndo() {
        myFixture.enableInspections(instantiateAll26PluginInspections());
        myFixture.addFileToProject(
            "stage10_fix2/gauntlet_v9_stage10_base_units.rov_nav.stvn_d",
            STAGE_10A_ROV_UNITS
        );
        var file10b = myFixture.addFileToProject(
            "stage10_fix2/gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di",
            STAGE_10B_ROV_BRIDGE
        );

        String stage10cWithMissingPkg = """
            {
              :meta {
                #name "gauntlet_v9_stage10_modular_consumer"
                #domain "rov_nav"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [
                  "gauntlet_v9_stage10_base_units.rov_nav.stvn_d"
                ]
                :package :rov/local_aux {
                  :AuxFlag :Boolean
                }
                :use [
                  :external/missing_pkg { #strip }
                  :rov/units { #strip }
                  :rov/waypoints { #strip }
                ]
                :ConsumerPayload :Tuple( :WaypointTag :DepthMeters )
              }
              :type :ConsumerPayload
              :body ( "WP-ALPHA" 55.0 )
            }
            """;

        var file10c = myFixture.addFileToProject(
            "stage10_fix2/gauntlet_v9_stage10_modular_consumer.rov_nav.stvn_i",
            stage10cWithMissingPkg
        );
        myFixture.configureFromExistingVirtualFile(file10c.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(file10c.getText().indexOf(":external/missing_pkg") + 4);
        myFixture.doHighlighting();

        // 1. Verify multi-origin candidate collection & exported symbol previews
        List<PackageCandidateEntry> candidates = StvnUsePackageLocatorPopup.collectCandidates(
            getProject(),
            file10c,
            ":external/missing_pkg"
        );
        assertTrue(candidates.stream().anyMatch(c ->
            c.originKind() == OriginKind.PRELUDE && ":org/stvnadore/prelude".equals(c.packagePath())));
        assertTrue(candidates.stream().anyMatch(c ->
            c.originKind() == OriginKind.PRELUDE && ":org/stvnadore/prelude/meta".equals(c.packagePath())));
        assertTrue(candidates.stream().anyMatch(c ->
            c.originKind() == OriginKind.LOCAL_DEFS && ":rov/local_aux".equals(c.packagePath())));
        assertTrue(candidates.stream().anyMatch(c ->
            c.originKind() == OriginKind.INCLUDED_MODULE && ":rov/units".equals(c.packagePath())));
        PackageCandidateEntry rovNavCand = candidates.stream()
            .filter(c -> ":rov/nav".equals(c.packagePath()))
            .findFirst()
            .orElse(null);
        assertNotNull("Un-included 10B :rov/nav must be indexed as WORKSPACE_MODULE", rovNavCand);
        assertEquals(OriginKind.WORKSPACE_MODULE, rovNavCand.originKind());
        assertTrue(rovNavCand.canReplaceUseTarget());
        assertEquals(5, rovNavCand.exportedSymbolCount());
        assertTrue(rovNavCand.originLabel().contains("adds :include"));
        assertTrue(rovNavCand.allExportedSymbols().contains(":NavFix"));
        assertTrue(rovNavCand.allExportedSymbols().contains("#DEEP_TEST_FIX"));

        // Verify live SearchTextField filtering by exported symbol name ("NavFix")
        LocatorContent ui = StvnUsePackageLocatorPopup.createLocatorContent(candidates, c -> {}, c -> {});
        assertEquals(candidates.size(), ui.tableModel().getRowCount());
        ui.searchField().setText("NavFix");
        assertEquals("Filtering by exported symbol 'NavFix' must match :rov/nav", 1, ui.tableModel().getRowCount());
        assertEquals(":rov/nav", ui.tableModel().getItem(0).packagePath());
        ui.searchField().setText("");
        assertEquals(candidates.size(), ui.tableModel().getRowCount());

        // Verify HTML intention preview on Search quick-fix
        IntentionAction searchAction = myFixture.findSingleIntention(
            "Search available packages for ':external/missing_pkg'..."
        );
        UseTarget missingTarget = findUseTargetByText(file10c, ":external/missing_pkg");
        assertNotNull(missingTarget);
        IntentionPreviewInfo htmlPreview = new StvnSearchAvailablePackagesQuickFix(missingTarget, ":external/missing_pkg")
            .generatePreview(getProject(), myFixture.getEditor(), file10c);
        assertTrue(htmlPreview instanceof IntentionPreviewInfo.Html);

        // 2. Headless Enter selection: atomic UseTarget replacement + auto-:include + 1-step Ctrl+Z Undo
        StvnUsePackageLocatorPopup.setTestSelectionOverride(
            list -> new LocatorTestSelection(":rov/nav", LocatorActionMode.SELECT_OR_APPLY)
        );
        myFixture.launchAction(searchAction);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        String updated10c = file10c.getText();
        assertFalse("Old unresolved UseTarget must be replaced", updated10c.contains(":external/missing_pkg"));
        assertTrue("Selected ':rov/nav' must replace UseTarget cleanly", updated10c.contains(":rov/nav { #strip }"));
        assertTrue(
            "Declaring workspace module 10B must be auto-added to :include",
            updated10c.contains("\"gauntlet_v9_stage10_mid_bridge.rov_nav.stvn_di\"")
        );
        assertTrue(
            "Updated Stage 10C must compile with 0 errors",
            StvnCompiledDocumentCache.getSnapshot(file10c).diagnostics().isEmpty()
        );

        // Single Ctrl+Z undo restores BOTH UseTarget and :include atomically
        FileEditor editor10c = FileEditorManager.getInstance(getProject()).getSelectedEditor(file10c.getVirtualFile());
        assertNotNull(editor10c);
        assertTrue(UndoManager.getInstance(getProject()).isUndoAvailable(editor10c));
        UndoManager.getInstance(getProject()).undo(editor10c);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals("Single Ctrl+Z Undo must restore original Stage 10C verbatim", stage10cWithMissingPkg, file10c.getText());

        // 3. F4 declaration navigation (to 10B and to Prelude)
        myFixture.getEditor().getCaretModel().moveToOffset(file10c.getText().indexOf(":external/missing_pkg") + 4);
        myFixture.doHighlighting();
        StvnUsePackageLocatorPopup.setTestSelectionOverride(
            list -> new LocatorTestSelection(":rov/nav", LocatorActionMode.NAVIGATE_DECLARATION)
        );
        myFixture.launchAction(myFixture.findSingleIntention("Search available packages for ':external/missing_pkg'..."));
        VirtualFile activeAfterNav = FileEditorManager.getInstance(getProject()).getCurrentFile();
        assertEquals("F4 must open 10B declaring :rov/nav", file10b.getVirtualFile(), activeAfterNav);
        assertEquals("F4 navigation must not mutate Stage 10C buffer", stage10cWithMissingPkg, file10c.getText());

        // 4. Hermetic .stvn (E035): local typo replacement vs. external workspace locate-only
        String hermeticSource = """
            {
              :meta {
                #name "hermetic_locator"
                #kind #BODY
              }
              :defs {
                :package :rov/local_pkg {
                  :LocalReading :Int
                }
                :use [ :rov/local_pkgg { #strip } ]
              }
              :type :Int
              :body 7
            }
            """;
        var hermeticFile = myFixture.addFileToProject("stage10_fix2/hermetic_locator.stvn", hermeticSource);
        myFixture.configureFromExistingVirtualFile(hermeticFile.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(hermeticFile.getText().indexOf(":rov/local_pkgg") + 4);
        myFixture.doHighlighting();

        // External workspace candidate in hermetic unit has canReplaceUseTarget == false -> navigates without mutating buffer
        StvnUsePackageLocatorPopup.setTestSelectionOverride(
            list -> new LocatorTestSelection(":rov/nav", LocatorActionMode.SELECT_OR_APPLY)
        );
        myFixture.launchAction(myFixture.findSingleIntention("Search available packages for ':rov/local_pkgg'..."));
        assertEquals("External workspace selection in hermetic .stvn must not mutate buffer", hermeticSource, hermeticFile.getText());
        assertEquals(file10b.getVirtualFile(), FileEditorManager.getInstance(getProject()).getCurrentFile());

        // Local :defs candidate in hermetic unit replaces typo cleanly
        myFixture.configureFromExistingVirtualFile(hermeticFile.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(hermeticFile.getText().indexOf(":rov/local_pkgg") + 4);
        myFixture.doHighlighting();
        StvnUsePackageLocatorPopup.setTestSelectionOverride(
            list -> new LocatorTestSelection(":rov/local_pkg", LocatorActionMode.SELECT_OR_APPLY)
        );
        myFixture.launchAction(myFixture.findSingleIntention("Search available packages for ':rov/local_pkgg'..."));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(hermeticFile.getText().contains(":use [ :rov/local_pkg { #strip } ]"));
        assertTrue(StvnCompiledDocumentCache.getSnapshot(hermeticFile).diagnostics().isEmpty());
    }

    /**
     * Verifies UUPG-01 Fix 3 ({@code StvnRemoveUnresolvedUsePackageQuickFix}):
     * <ul>
     *   <li>Element-scoped deletion on multi-element {@code :use [...]} preserving sibling elements and same-line {@code //} comments.</li>
     *   <li>Whole-directive deletion on sole-element {@code :use}, omitting empty comment-free {@code :defs} in {@code .stvn_i} while keeping {@code :defs {\n  }} in {@code .stvn_di}.</li>
     *   <li>1:1 Pass A (0 inspections) vs. Pass B (26 inspections) {@code E035} quick-fix parity (`0` duplicates).</li>
     * </ul>
     */
    public void testUupg01_Fix3ElementScopedAndSoleUseRemovalPreservesSiblingsCommentsAndPassAPassBParity() {
        InspectionProfileEntry[] all26 = instantiateAll26PluginInspections();
        myFixture.enableInspections(all26);

        myFixture.addFileToProject(
            "stage10_fix3/gauntlet_v9_stage10_base_units.rov_nav.stvn_d",
            STAGE_10A_ROV_UNITS
        );
        var multiUseFile = myFixture.addFileToProject(
            "stage10_fix3/multi_use_comments.rov_nav.stvn_i",
            """
            {
              :meta {
                #name "multi_use_comments"
                #domain "rov_nav"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "gauntlet_v9_stage10_base_units.rov_nav.stvn_d" ]
                :use [
                  :external/missing_pkg { #strip } // preserve inline comment
                  :rov/units { #strip }
                  :rov/waypoints { #strip }
                ]
              }
              :type :Tuple( :WaypointTag :DepthMeters )
              :body ( "WP-OK" 12.5 )
            }
            """
        );
        myFixture.configureFromExistingVirtualFile(multiUseFile.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(multiUseFile.getText().indexOf(":external/missing_pkg") + 4);
        myFixture.doHighlighting();

        IntentionAction removeElemFix = myFixture.findSingleIntention(
            "Remove unresolved package ':external/missing_pkg' from :use"
        );
        String diffPreview = myFixture.getIntentionPreviewText(removeElemFix);
        assertNotNull(diffPreview);
        assertFalse(diffPreview.contains(":external/missing_pkg"));
        assertTrue("DIFF preview must retain inline // comment", diffPreview.contains("// preserve inline comment"));

        myFixture.launchAction(removeElemFix);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        String afterMultiRemove = multiUseFile.getText();
        assertFalse(afterMultiRemove.contains(":external/missing_pkg"));
        assertTrue("Same-line // comment must survive element-scoped removal", afterMultiRemove.contains("// preserve inline comment"));
        assertTrue(afterMultiRemove.contains(":rov/units { #strip }"));
        assertTrue(afterMultiRemove.contains(":rov/waypoints { #strip }"));
        assertTrue(StvnCompiledDocumentCache.getSnapshot(multiUseFile).diagnostics().isEmpty());

        // Sole-element :use in .stvn_i omits empty comment-free :defs
        var solePayloadInc = myFixture.addFileToProject(
            "stage10_fix3/sole_payload.rov_nav.stvn_i",
            """
            {
              :meta {
                #name "sole_payload"
                #domain "rov_nav"
                #kind #BODY_INCLUDE
              }
              :defs {
                :use [ :external/missing_pkg { #strip } ]
              }
              :type :Int
              :body 100
            }
            """
        );
        myFixture.configureFromExistingVirtualFile(solePayloadInc.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(solePayloadInc.getText().indexOf(":external/missing_pkg") + 4);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Remove unresolved package ':external/missing_pkg' from :use"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertFalse("Empty comment-free :defs must be omitted in .stvn_i", solePayloadInc.getText().contains(":defs"));
        assertTrue(StvnCompiledDocumentCache.getSnapshot(solePayloadInc).diagnostics().isEmpty());

        // Sole-element :use in .stvn_di retains :defs { } shell
        var soleDefsInc = myFixture.addFileToProject(
            "stage10_fix3/sole_defs.rov_nav.stvn_di",
            """
            {
              :meta {
                #name "sole_defs"
                #domain "rov_nav"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :use [ :external/missing_pkg { #strip } ]
              }
            }
            """
        );
        myFixture.configureFromExistingVirtualFile(soleDefsInc.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(soleDefsInc.getText().indexOf(":external/missing_pkg") + 4);
        myFixture.doHighlighting();
        myFixture.launchAction(myFixture.findSingleIntention("Remove unresolved package ':external/missing_pkg' from :use"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue("Definitions unit .stvn_di must retain :defs enclosure", soleDefsInc.getText().contains(":defs {"));

        // Pass A (0 inspections) vs. Pass B (26 inspections) E035 parity in hermetic .stvn
        String hermeticE035Src = """
            {
              :meta {
                #name "e035_parity_unit"
                #kind #BODY
              }
              :defs {
                :use [ :external/unresolvable { #strip } ]
              }
              :type :Int
              :body 1
            }
            """;
        var hermeticE035File = myFixture.addFileToProject("stage10_fix3/e035_parity_unit.stvn", hermeticE035Src);
        myFixture.disableInspections(all26);
        myFixture.configureFromExistingVirtualFile(hermeticE035File.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(hermeticE035File.getText().indexOf(":external/unresolvable") + 4);
        List<HighlightInfo> passAE035 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E035"))
            .toList();
        assertEquals("Pass A must emit 1 E035 highlight", 1, passAE035.size());
        List<String> passAE035Fixes = collectHermeticE035IntentionTexts(myFixture.getAvailableIntentions());

        myFixture.enableInspections(all26);
        List<HighlightInfo> passBE035 = myFixture.doHighlighting().stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("E035"))
            .toList();
        assertEquals("Pass B must emit 1 E035 highlight (0 stacked duplicates)", 1, passBE035.size());
        List<String> passBE035Fixes = collectHermeticE035IntentionTexts(myFixture.getAvailableIntentions());

        List<String> expectedHermeticFixes = List.of(
            "Search available packages for ':external/unresolvable'...",
            "Remove unresolved package ':external/unresolvable' from :use",
            "Convert document to modular format (.stvn_i)"
        );
        assertEquals("Pass B E035 must expose 3 ordered quick-fixes", expectedHermeticFixes, passBE035Fixes);
        assertEquals("Pass A and Pass B E035 quick-fixes must match 1:1", passAE035Fixes, passBE035Fixes);
    }

    /**
     * Verifies UUPG-02 + Safeguard 3:
     * <ul>
     *   <li>Stage 12 exact construct detection ({@code [COMMENTS, PACKAGE_ENCLOSURES, USE_DIRECTIVES, CONSTANT_DEFINITIONS]}, {@code 0} false {@code UNREFERENCED_DEFINITIONS}).</li>
     *   <li>Individual isolation across all 7 {@link DestructiveConstruct} categories plus clean lossless documents.</li>
     *   <li>{@code Cancel} ({@code Boolean.FALSE}) leaves Stage 12 untouched with itemized STE prompt and {@code 0} notifications.</li>
     *   <li>{@code Proceed} ({@code Boolean.TRUE}) canonicalizes Stage 12 to Pretty IR &amp; Compact IR cleanly, and {@code Ctrl+Z} Undo restores Stage 12 verbatim.</li>
     *   <li>{@code confirmDestructiveCanonicalization} XML persistence, {@code @Transient suppressCanonicalizationWarning}, and {@link StvnConfigurable} UI round-trip.</li>
     * </ul>
     */
    public void testUupg02_Stage12DestructiveConstructDetectionItemizedConfirmationCancelProceedUndoAndSettings() throws Exception {
        List<Notification> notifications = new ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(@NotNull Notification notification) {
                notifications.add(notification);
            }
        });

        // 1. Stage 12 exact 4-category detection (0 false UNREFERENCED_DEFINITIONS on :package + :use + UseMapAlias)
        var stage12File = myFixture.addFileToProject(
            "stage12_guard/gauntlet_v9_stage12_disorder_reordering_and_vop_projections.core_test.stvn",
            HEALED_STAGE_12_TELEMETRY
        );
        myFixture.configureFromExistingVirtualFile(stage12File.getVirtualFile());
        assertTrue(
            "Healed Stage 12 must compile with 0 diagnostics",
            StvnCompiledDocumentCache.getSnapshot(stage12File).diagnostics().isEmpty()
        );
        assertFalse(
            "Packaged and UseMapAlias-referenced Stage 12 symbols must NOT be flagged as unreferenced",
            StvnCanonicalizationGuard.hasUnreferencedDefinitions(stage12File)
        );
        assertEquals(
            List.of(
                DestructiveConstruct.COMMENTS,
                DestructiveConstruct.PACKAGE_ENCLOSURES,
                DestructiveConstruct.USE_DIRECTIVES,
                DestructiveConstruct.CONSTANT_DEFINITIONS
            ),
            StvnCanonicalizationGuard.detectDestructiveConstructs(stage12File)
        );

        // 2. Individual construct isolation across all 7 categories + lossless documents
        PsiFile cleanLossless = myFixture.configureByText(
            "lossless_unit.stvn",
            "{ :meta { #name \"lossless_unit\" #kind #BODY } :defs { :Count :Int } :type :Count :body 42 }"
        );
        assertEquals(List.of(), StvnCanonicalizationGuard.detectDestructiveConstructs(cleanLossless));

        PsiFile cleanDefsConst = myFixture.configureByText(
            "lossless_defs_const.stvn_d",
            "{ :meta { #name \"lossless_defs_const\" #kind #DEFS } :defs { :Mode :Enum[ #AUTO #MANUAL ] #DEFAULT_MODE :Mode #AUTO } }"
        );
        assertEquals(List.of(), StvnCanonicalizationGuard.detectDestructiveConstructs(cleanDefsConst));

        PsiFile defsFacetConst = myFixture.configureByText(
            "inlined_defs_facet.stvn_d",
            "{ :meta { #name \"inlined_defs_facet\" #kind #DEFS } :defs { #MAX_P :Int 65535 :Port { #unsigned #size 16 #maxExcl #MAX_P } :Int } }"
        );
        assertEquals(
            List.of(DestructiveConstruct.CONSTANT_DEFINITIONS),
            StvnCanonicalizationGuard.detectDestructiveConstructs(defsFacetConst)
        );

        PsiFile unrefOnly = myFixture.configureByText(
            "unref_only.stvn",
            "{ :meta { #name \"unref_only\" #kind #BODY } :defs { :Used :Int :DeadUnused :String } :type :Used :body 9 }"
        );
        assertEquals(
            List.of(DestructiveConstruct.UNREFERENCED_DEFINITIONS),
            StvnCanonicalizationGuard.detectDestructiveConstructs(unrefOnly)
        );

        PsiFile redundantW003Only = myFixture.configureByText(
            "redundant_w003.stvn",
            "{ :meta { #name \"redundant_w003\" #kind #BODY } :defs { :RedundantInt { #size 32 } :Int } :type :RedundantInt :body 5 }"
        );
        assertEquals(
            List.of(DestructiveConstruct.REDUNDANT_METADATA_FACETS),
            StvnCanonicalizationGuard.detectDestructiveConstructs(redundantW003Only)
        );

        PsiFile commentsOnly = myFixture.configureByText(
            "comments_only.stvn",
            "{ // comment\n :meta { #name \"comments_only\" #kind #BODY } :type :Int :body 1 }"
        );
        assertEquals(
            List.of(DestructiveConstruct.COMMENTS),
            StvnCanonicalizationGuard.detectDestructiveConstructs(commentsOnly)
        );

        PsiFile includeOnly = myFixture.configureByText(
            "include_only.stvn_i",
            "{ :meta { #name \"include_only\" #kind #BODY_INCLUDE } :defs { :include [ \"base.stvn_d\" ] } :type :Int :body 1 }"
        );
        assertEquals(
            List.of(DestructiveConstruct.INCLUDE_DIRECTIVES),
            StvnCanonicalizationGuard.detectDestructiveConstructs(includeOnly)
        );

        PsiFile packageOnly = myFixture.configureByText(
            "package_only.stvn",
            "{ :meta { #name \"package_only\" #kind #BODY } :defs { :package :pkg { :T :Int } } :type :pkg/T :body 1 }"
        );
        assertEquals(
            List.of(DestructiveConstruct.PACKAGE_ENCLOSURES),
            StvnCanonicalizationGuard.detectDestructiveConstructs(packageOnly)
        );

        PsiFile useOnly = myFixture.configureByText(
            "use_only.stvn",
            "{ :meta { #name \"use_only\" #kind #BODY } :defs { :use [ :org/stvnadore/prelude { #strip } ] } :type :Int :body 1 }"
        );
        assertEquals(
            List.of(DestructiveConstruct.USE_DIRECTIVES),
            StvnCanonicalizationGuard.detectDestructiveConstructs(useOnly)
        );

        // 3. Cancel (Boolean.FALSE) on Stage 12 Pretty IR and Compact IR -> itemized message, untouched buffer, 0 notifications
        myFixture.configureFromExistingVirtualFile(stage12File.getVirtualFile());
        Document stage12Doc = FileDocumentManager.getInstance().getDocument(stage12File.getVirtualFile());
        assertNotNull(stage12Doc);
        notifications.clear();

        StvnCanonicalizationGuard.setConfirmationOverride(Boolean.FALSE);
        invokeInPlaceAction(new StvnReplaceBufferPrettyIrAction(), stage12File);
        String prettyPrompt = StvnCanonicalizationGuard.getLastPromptedMessage();
        assertNotNull("Confirmation prompt must be generated for Stage 12 Pretty IR", prettyPrompt);
        assertTrue(prettyPrompt.contains("pretty IR"));
        assertTrue(prettyPrompt.contains("single-line // comments"));
        assertTrue(prettyPrompt.contains(":package enclosures"));
        assertTrue(prettyPrompt.contains(":use directives"));
        assertTrue(prettyPrompt.contains("#CONST definitions (inlined into values and facets)"));
        assertTrue(prettyPrompt.contains("Ctrl+Z (Undo)"));
        assertEquals("Cancelling Pretty IR canonicalization must leave buffer untouched", HEALED_STAGE_12_TELEMETRY, stage12Doc.getText());
        assertTrue("Cancelling must emit 0 notifications", notifications.isEmpty());

        invokeInPlaceAction(new StvnReplaceBufferCompactIrAction(), stage12File);
        String compactPrompt = StvnCanonicalizationGuard.getLastPromptedMessage();
        assertNotNull(compactPrompt);
        assertTrue(compactPrompt.contains("compact IR"));
        assertEquals("Cancelling Compact IR canonicalization must leave buffer untouched", HEALED_STAGE_12_TELEMETRY, stage12Doc.getText());
        assertTrue("Cancelling Compact IR must emit 0 notifications", notifications.isEmpty());

        // 4. Proceed (Boolean.TRUE) on Stage 12 Pretty IR + Ctrl+Z Undo restoration
        StvnCanonicalizationGuard.setConfirmationOverride(Boolean.TRUE);
        invokeInPlaceAction(new StvnReplaceBufferPrettyIrAction(), stage12File);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        String canonicalPretty = stage12Doc.getText();
        assertFalse("Canonical Pretty IR must remove // comments", canonicalPretty.contains("// Stage 12 header comment"));
        assertFalse("Canonical Pretty IR must desugar :package", canonicalPretty.contains(":package :rov/telemetry"));
        assertFalse("Canonical Pretty IR must desugar :use", canonicalPretty.contains(":use ["));
        assertFalse("Canonical Pretty IR must inline #INIT_SEQ", canonicalPretty.contains("#INIT_SEQ"));
        assertCompilesZeroErrorsAndZeroWarnings(
            canonicalPretty,
            "gauntlet_v9_stage12_disorder_reordering_and_vop_projections.core_test.stvn"
        );

        FileEditor stage12Editor = FileEditorManager.getInstance(getProject()).getSelectedEditor(stage12File.getVirtualFile());
        assertNotNull(stage12Editor);
        assertTrue(UndoManager.getInstance(getProject()).isUndoAvailable(stage12Editor));
        UndoManager.getInstance(getProject()).undo(stage12Editor);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(
            "Ctrl+Z Undo after Pretty IR canonicalization must restore Stage 12 verbatim",
            HEALED_STAGE_12_TELEMETRY,
            stage12Doc.getText()
        );

        // Proceed on Stage 12 Compact IR + Ctrl+Z Undo restoration
        invokeInPlaceAction(new StvnReplaceBufferCompactIrAction(), stage12File);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        String canonicalCompact = stage12Doc.getText();
        assertFalse(canonicalCompact.contains("\n"));
        assertCompilesZeroErrorsAndZeroWarnings(
            canonicalCompact,
            "gauntlet_v9_stage12_disorder_reordering_and_vop_projections.core_test.stvn"
        );

        UndoManager.getInstance(getProject()).undo(stage12Editor);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(
            "Ctrl+Z Undo after Compact IR canonicalization must restore Stage 12 verbatim",
            HEALED_STAGE_12_TELEMETRY,
            stage12Doc.getText()
        );

        // 5. Safeguard 3: XML persistence (@Transient legacy latch) & StvnConfigurable UI round-trip
        Field legacyField = StvnProjectSettings.State.class.getField("suppressCanonicalizationWarning");
        assertNotNull(
            "Legacy suppressCanonicalizationWarning must carry @Transient so stale .idea/stvn_settings.xml latches never silence prompts",
            legacyField.getAnnotation(Transient.class)
        );
        var sampleState = new StvnProjectSettings.State();
        sampleState.confirmDestructiveCanonicalization = false;
        sampleState.suppressCanonicalizationWarning = true;
        Element serialized = XmlSerializer.serialize(sampleState);
        assertNotNull(serialized);
        List<String> serializedOptionNames = serialized.getChildren("option").stream()
            .map(opt -> opt.getAttributeValue("name"))
            .toList();
        assertTrue(
            "confirmDestructiveCanonicalization must serialize to XML",
            serializedOptionNames.contains("confirmDestructiveCanonicalization")
        );
        assertFalse(
            "Transient suppressCanonicalizationWarning must NOT serialize to XML",
            serializedOptionNames.contains("suppressCanonicalizationWarning")
        );

        var configurable = new StvnConfigurable(getProject());
        try {
            JComponent panel = configurable.createComponent();
            assertNotNull(panel);
            JCheckBox guardCheckbox = findCheckBoxByText(
                panel,
                "Prompt before in-place canonicalization removes authoring constructs"
            );
            assertNotNull("StvnConfigurable must expose canonicalization confirmation checkbox", guardCheckbox);
            assertTrue(guardCheckbox.isSelected());
            assertFalse(configurable.isModified());

            guardCheckbox.setSelected(false);
            assertTrue(configurable.isModified());
            configurable.apply();
            assertFalse(StvnProjectSettings.getInstance(getProject()).getState().confirmDestructiveCanonicalization);

            StvnProjectSettings.getInstance(getProject()).getState().confirmDestructiveCanonicalization = true;
            configurable.reset();
            assertTrue(guardCheckbox.isSelected());
        } finally {
            configurable.disposeUIResources();
        }
    }

    private void invokeInPlaceAction(AnAction action, PsiFile psiFile) {
        var dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.EDITOR, myFixture.getEditor())
            .add(CommonDataKeys.PSI_FILE, psiFile)
            .add(CommonDataKeys.VIRTUAL_FILE, psiFile.getVirtualFile())
            .build();
        AnActionEventWrapper.perform(action, dataContext);
    }

    private static final class AnActionEventWrapper {
        static void perform(AnAction action, com.intellij.openapi.actionSystem.DataContext context) {
            var updateEvent = TestActionEvent.createTestEvent(action, context);
            action.update(updateEvent);
            assertTrue("Action must be enabled: " + action.getClass().getSimpleName(), updateEvent.getPresentation().isEnabled());
            action.actionPerformed(TestActionEvent.createTestEvent(action, context));
        }
    }

    private static void assertCompilesZeroErrorsAndZeroWarnings(String source, String fileName) {
        StvnCompilationResult<StvnDocument> res = StvnCompiler.compileToResult(
            source,
            fileName,
            StvnParserConfig.DEFAULT
        );
        assertTrue("Expected 0 compiler diagnostics for " + fileName + ", got: " + res.diagnostics(), res.diagnostics().isEmpty());
    }

    private static List<String> collectUnresolvedUseIntentionTexts(List<IntentionAction> actions) {
        return actions.stream()
            .map(IntentionAction::getText)
            .filter(t -> t.startsWith("Include '")
                || t.startsWith("Search available packages for '")
                || t.startsWith("Remove unresolved package '"))
            .toList();
    }

    private static List<String> collectHermeticE035IntentionTexts(List<IntentionAction> actions) {
        return actions.stream()
            .map(IntentionAction::getText)
            .filter(t -> t.startsWith("Search available packages for '")
                || t.startsWith("Remove unresolved package '")
                || t.startsWith("Convert document to modular format"))
            .toList();
    }

    private static UseTarget findUseTargetByText(PsiFile file, String expectedText) {
        for (UseTarget ut : PsiTreeUtil.findChildrenOfType(file, UseTarget.class)) {
            if (expectedText.equals(ut.getText().trim())) {
                return ut;
            }
        }
        return null;
    }

    private static JCheckBox findCheckBoxByText(Container root, String text) {
        for (Component comp : root.getComponents()) {
            if (comp instanceof JCheckBox cb && text.equals(cb.getText())) {
                return cb;
            }
            if (comp instanceof Container child) {
                JCheckBox found = findCheckBoxByText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static InspectionProfileEntry[] instantiateAll26PluginInspections() {
        return new InspectionProfileEntry[] {
            new StvnVariantStyleInspection(),
            new StvnBooleanValidityInspection(),
            new StvnMapStructuralInspection(),
            new StvnDegradedSchemaInspection(),
            new StvnDegenerateCompositeInspection(),
            new StvnEnumSubsetInspection(),
            new StvnFencedStringInspection(),
            new StvnLhsReservedTypeInspection(),
            new StvnConstantRangeInspection(),
            new StvnFlatDocumentIncludeInspection(),
            new StvnNestedPackageInspection(),
            new StvnTrailingSlashInspection(),
            new StvnStringCapacityInspection(),
            new StvnExcessiveExactFloatPrecisionInspection(),
            new StvnMetadataFacetInspection(),
            new StvnStringCardinalityInspection(),
            new StvnDiscreteIntervalInspection(),
            new StvnTemporalModeInspection(),
            new StvnMetadataOrderInspection(),
            new StvnRedundantFacetOverrideInspection(),
            new StvnUnresolvedTypeInspection(),
            new StvnTabCharacterInspection(),
            new StvnMissingMetaInspection(),
            new StvnStemMismatchInspection(),
            new StvnSuspiciousEpochMagnitudeInspection(),
            new StvnFloatPrecisionLossInspection()
        };
    }
}
