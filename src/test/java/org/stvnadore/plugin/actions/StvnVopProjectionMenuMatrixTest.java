package org.stvnadore.plugin.actions;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.StvnSchemaFlattener;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.reference.StvnPreludeBridge;
import org.stvnadore.plugin.settings.StvnProjectSettings;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive platform integration test suite verifying the 3-Submenu STVN VOP Projection
 * and Canonicalization Menu Matrix ({@code STVN >} and {@code BuildMenu}), {@link ActionUpdateThread#BGT}
 * state-space enablement and self-overwrite guards, Safeguards 1–3, Pure-IR {@code #kind #BODY}
 * payload projection, empty-{@code :defs} protection, atomic EDT VFS sibling exports, and undoable
 * in-place buffer canonicalization across {@code EditorPopupMenu} and {@code ProjectViewPopupMenu}.
 */
@NullMarked
public final class StvnVopProjectionMenuMatrixTest extends BasePlatformTestCase {

    private static final String DIAMOND_10A = """
        {
          :meta {
            #name "vop_stage10a"
            #domain "core_test"
            #kind #DEFS
          }
          :defs {
            :DishAzimuth { #minIncl 0 #maxExcl 360 } :Int
            :package :satcom/uplink {
              :StationCode { #minSize 3 #maxSize 16 #regex "^STN-[A-Z0-9]+$" } :String
              #DEFAULT_AZIMUTH :DishAzimuth 180
            }
          }
        }
        """;

    private static final String DIAMOND_10B = """
        {
          :meta {
            #name "vop_stage10b"
            #domain "core_test"
            #kind #DEFS_INCLUDE
          }
          :defs {
            :include [ "vop_stage10a.core_test.stvn_d" ]
            :package :satcom/relay {
              :use [ :satcom/uplink { #strip } ]
              :RelayHop :Tuple( :StationCode :DishAzimuth )
            }
          }
        }
        """;

    private static final String DIAMOND_10C = """
        {
          :meta {
            #name "vop_stage10c"
            #domain "core_test"
            #kind #BODY_INCLUDE
          }
          :defs {
            // Comment preserved in authoring source buffer
            :include [
              "vop_stage10a.core_test.stvn_d"
              "vop_stage10b.core_test.stvn_di"
            ]
            :use [ :satcom/uplink { #strip } ]
            :use [ :satcom/relay { #strip } ]
            :UnusedDeadType { #size 32 } :Int
            :TelemetryFrame :Tuple( :RelayHop :Boolean )
          }
          :type :TelemetryFrame
          :body ( ( "STN-MADRID" #DEFAULT_AZIMUTH ) #TRUE )
        }
        """;

    public void testPopupHierarchySeparatorsBuildMenuAndSafeguard1StvnDLabels() {
        ActionManager am = ActionManager.getInstance();
        var sampleFile = myFixture.addFileToProject(
            "probe.core_test.stvn_i",
            "{ :meta { #name \"probe\" #domain \"core_test\" #kind #BODY_INCLUDE } :defs { :A :Int } :type :A :body 1 }"
        );
        var dataCtx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, sampleFile.getVirtualFile())
            .add(CommonDataKeys.PSI_FILE, sampleFile)
            .build();

        DefaultActionGroup rootPopup = (DefaultActionGroup) am.getAction("org.stvnadore.plugin.actions.StvnEditorPopupMenu");
        assertNotNull(rootPopup);
        AnAction[] rootChildren = rootPopup.getChildren(TestActionEvent.createTestEvent(rootPopup, dataCtx));
        assertEquals(7, rootChildren.length);
        assertEquals(am.getAction("org.stvnadore.plugin.browser.StvnBrowseNamespaceAction"), rootChildren[0]);
        assertTrue(rootChildren[1] instanceof Separator);

        DefaultActionGroup sub1 = (DefaultActionGroup) am.getAction("org.stvnadore.plugin.actions.StvnExtractSchemaMenuGroup");
        DefaultActionGroup sub2 = (DefaultActionGroup) am.getAction("org.stvnadore.plugin.actions.StvnExtractPayloadMenuGroup");
        DefaultActionGroup sub3 = (DefaultActionGroup) am.getAction("org.stvnadore.plugin.actions.StvnCanonicalizeInPlaceMenuGroup");
        assertNotNull(sub1);
        assertNotNull(sub2);
        assertNotNull(sub3);
        assertEquals(sub1, rootChildren[2]);
        assertTrue(rootChildren[3] instanceof Separator);
        assertEquals(sub2, rootChildren[4]);
        assertTrue(rootChildren[5] instanceof Separator);
        assertEquals(sub3, rootChildren[6]);

        AnAction[] sub1Children = sub1.getChildren(TestActionEvent.createTestEvent(sub1, dataCtx));
        assertEquals(5, sub1Children.length);
        assertTrue(sub1Children[0] instanceof StvnCopyHermeticSchemaPrettyAction);
        assertTrue(sub1Children[1] instanceof StvnCopyHermeticSchemaCompactCasAction);
        assertTrue(sub1Children[2] instanceof Separator);
        assertTrue(sub1Children[3] instanceof StvnExportHermeticSchemaPrettyAction);
        assertTrue(sub1Children[4] instanceof StvnExportHermeticSchemaCompactCasAction);

        AnAction[] sub2Children = sub2.getChildren(TestActionEvent.createTestEvent(sub2, dataCtx));
        assertEquals(5, sub2Children.length);
        assertTrue(sub2Children[0] instanceof StvnCopyHermeticPayloadPrettyAction);
        assertTrue(sub2Children[1] instanceof StvnCopyHermeticPayloadCompactAction);
        assertTrue(sub2Children[2] instanceof Separator);
        assertTrue(sub2Children[3] instanceof StvnExportHermeticPayloadPrettyAction);
        assertTrue(sub2Children[4] instanceof StvnExportHermeticPayloadCompactAction);

        AnAction[] sub3Children = sub3.getChildren(TestActionEvent.createTestEvent(sub3, dataCtx));
        assertEquals(2, sub3Children.length);
        assertTrue(sub3Children[0] instanceof StvnReplaceBufferPrettyIrAction);
        assertTrue(sub3Children[1] instanceof StvnReplaceBufferCompactIrAction);

        ActionGroup buildMenu = (ActionGroup) am.getAction("BuildMenu");
        AnAction buildExport = am.getAction("org.stvnadore.plugin.actions.StvnBuildExportSchemaCompactCasAction");
        assertNotNull(buildMenu);
        assertNotNull(buildExport);
        assertTrue(Arrays.asList(buildMenu.getChildren(TestActionEvent.createTestEvent())).contains(buildExport));

        // Safeguard 1: Verify exact ".stvn_d" preservation (never ".stvnd" stripped by TextWithMnemonic)
        assertExactNonMnemonicLabel(
            sub1,
            dataCtx,
            "Extract Hermetic Schema (.stvn_d \u2014 #DEFS Only)"
        );
        assertExactNonMnemonicLabel(
            am.getAction("org.stvnadore.plugin.actions.StvnExportHermeticSchemaPrettyAction"),
            dataCtx,
            "Export Sibling .stvn_d File (Pretty)"
        );
        assertExactNonMnemonicLabel(
            am.getAction("org.stvnadore.plugin.actions.StvnExportHermeticSchemaCompactCasAction"),
            dataCtx,
            "Export Sibling .stvn_d File (Compact CAS)"
        );
        assertExactNonMnemonicLabel(
            buildExport,
            dataCtx,
            "Export Hermetic STVN Schema (.stvn_d, Compact CAS)"
        );
    }

    public void testFourExtensionBinaryNonStvnAndReadOnlyPreludeEnablementMatrix() throws Exception {
        var fDi = myFixture.addFileToProject("mod.stvn_di", "{ :meta { #name \"mod\" #kind #DEFS_INCLUDE } :defs { :A :Int } }").getVirtualFile();
        var fD = myFixture.addFileToProject("flat.stvn_d", "{ :meta { #name \"flat\" #kind #DEFS } :defs { :A :Int } }").getVirtualFile();
        var fI = myFixture.addFileToProject("app.stvn_i", "{ :meta { #name \"app\" #kind #BODY_INCLUDE } :defs { :A :Int } :type :A :body 1 }").getVirtualFile();
        var fStvn = myFixture.addFileToProject("app_flat.stvn", "{ :defs { :A :Int } :type :A :body 1 }").getVirtualFile();
        var fBin = myFixture.addFileToProject("wire.stvn_b", "BIN").getVirtualFile();
        var fBinD = myFixture.addFileToProject("wire_defs.stvn_bd", "BIN").getVirtualFile();
        var fTxt = myFixture.addFileToProject("notes.txt", "plain").getVirtualFile();
        var preludeVf = StvnPreludeBridge.getPreludeVirtualFile(getProject());

        var rootGroup = new StvnEditorPopupMenuGroup();
        var sub1Group = new StvnExtractSchemaMenuGroup();
        var sub2Group = new StvnExtractPayloadMenuGroup();
        var sub3Group = new StvnCanonicalizeInPlaceMenuGroup();

        var s1ClipPretty = new StvnCopyHermeticSchemaPrettyAction();
        var s1ClipCas = new StvnCopyHermeticSchemaCompactCasAction();
        var s1ExpPretty = new StvnExportHermeticSchemaPrettyAction();
        var s1ExpCas = new StvnExportHermeticSchemaCompactCasAction();
        var buildExpCas = new StvnBuildExportSchemaCompactCasAction();

        var s2ClipPretty = new StvnCopyHermeticPayloadPrettyAction();
        var s2ClipCompact = new StvnCopyHermeticPayloadCompactAction();
        var s2ExpPretty = new StvnExportHermeticPayloadPrettyAction();
        var s2ExpCompact = new StvnExportHermeticPayloadCompactAction();

        var s3Pretty = new StvnReplaceBufferPrettyIrAction();
        var s3Compact = new StvnReplaceBufferCompactIrAction();

        // 1. Non-STVN, directory, and Binary STVN (.stvn_b, .stvn_bd): hidden
        for (VirtualFile nonTextual : List.of(fBin, fBinD, fTxt, fStvn.getParent())) {
            assertPresentation(rootGroup, nonTextual, false, false);
            assertPresentation(s1ClipPretty, nonTextual, false, false);
            assertPresentation(s2ClipPretty, nonTextual, false, false);
            assertPresentation(s3Pretty, nonTextual, false, false);
            assertPresentation(buildExpCas, nonTextual, false, false);
        }

        // 2. .stvn_di (#DEFS_INCLUDE)
        assertPresentation(rootGroup, fDi, true, true);
        assertPresentation(sub1Group, fDi, true, true);
        assertPresentation(s1ClipPretty, fDi, true, true);
        assertPresentation(s1ClipCas, fDi, true, true);
        assertPresentation(s1ExpPretty, fDi, true, true);
        assertPresentation(s1ExpCas, fDi, true, true);
        assertPresentation(buildExpCas, fDi, true, true);
        assertPresentation(sub2Group, fDi, true, false);
        assertPresentation(s2ClipPretty, fDi, true, false);
        assertPresentation(s2ClipCompact, fDi, true, false);
        assertPresentation(s2ExpPretty, fDi, true, false);
        assertPresentation(s2ExpCompact, fDi, true, false);
        assertPresentation(sub3Group, fDi, true, true);
        assertPresentation(s3Pretty, fDi, true, true);
        assertPresentation(s3Compact, fDi, true, true);

        // 3. .stvn_d (#DEFS) — Self-Overwrite Guard disables Submenu 1 & BuildMenu sibling .stvn_d exports
        assertPresentation(rootGroup, fD, true, true);
        assertPresentation(sub1Group, fD, true, true);
        assertPresentation(s1ClipPretty, fD, true, true);
        assertPresentation(s1ClipCas, fD, true, true);
        assertPresentation(s1ExpPretty, fD, true, false);
        assertPresentation(s1ExpCas, fD, true, false);
        assertPresentation(buildExpCas, fD, true, false);
        assertPresentation(sub2Group, fD, true, false);
        assertPresentation(s2ClipPretty, fD, true, false);
        assertPresentation(s2ExpPretty, fD, true, false);
        assertPresentation(sub3Group, fD, true, true);
        assertPresentation(s3Pretty, fD, true, true);
        assertPresentation(s3Compact, fD, true, true);

        // 4. .stvn_i (#BODY_INCLUDE) — All 10 actions + BuildMenu enabled
        assertPresentation(rootGroup, fI, true, true);
        assertPresentation(sub1Group, fI, true, true);
        assertPresentation(s1ClipPretty, fI, true, true);
        assertPresentation(s1ClipCas, fI, true, true);
        assertPresentation(s1ExpPretty, fI, true, true);
        assertPresentation(s1ExpCas, fI, true, true);
        assertPresentation(buildExpCas, fI, true, true);
        assertPresentation(sub2Group, fI, true, true);
        assertPresentation(s2ClipPretty, fI, true, true);
        assertPresentation(s2ClipCompact, fI, true, true);
        assertPresentation(s2ExpPretty, fI, true, true);
        assertPresentation(s2ExpCompact, fI, true, true);
        assertPresentation(sub3Group, fI, true, true);
        assertPresentation(s3Pretty, fI, true, true);
        assertPresentation(s3Compact, fI, true, true);

        // 5. .stvn (#BODY) — Self-Overwrite Guard disables Submenu 2 sibling .stvn exports
        assertPresentation(rootGroup, fStvn, true, true);
        assertPresentation(sub1Group, fStvn, true, true);
        assertPresentation(s1ClipPretty, fStvn, true, true);
        assertPresentation(s1ClipCas, fStvn, true, true);
        assertPresentation(s1ExpPretty, fStvn, true, true);
        assertPresentation(s1ExpCas, fStvn, true, true);
        assertPresentation(buildExpCas, fStvn, true, true);
        assertPresentation(sub2Group, fStvn, true, true);
        assertPresentation(s2ClipPretty, fStvn, true, true);
        assertPresentation(s2ClipCompact, fStvn, true, true);
        assertPresentation(s2ExpPretty, fStvn, true, false);
        assertPresentation(s2ExpCompact, fStvn, true, false);
        assertPresentation(sub3Group, fStvn, true, true);
        assertPresentation(s3Pretty, fStvn, true, true);
        assertPresentation(s3Compact, fStvn, true, true);

        // 6. Synthetic Prelude LightVirtualFile (prelude.stvn_core.stvn_d)
        assertPresentation(rootGroup, preludeVf, true, true);
        assertPresentation(s1ClipPretty, preludeVf, true, true);
        assertPresentation(s1ClipCas, preludeVf, true, true);
        assertPresentation(s1ExpPretty, preludeVf, true, false);
        assertPresentation(s2ClipPretty, preludeVf, true, false);
        assertPresentation(sub3Group, preludeVf, true, false);
        assertPresentation(s3Pretty, preludeVf, true, false);
        assertPresentation(s3Compact, preludeVf, true, false);

        // 7. Read-only .stvn_i inside a writable folder: Clipboard & Sibling Export enabled, In-Place disabled
        var roInWritableDir = myFixture.addFileToProject(
            "readonly_mod.stvn_i",
            "{ :meta { #name \"readonly_mod\" #kind #BODY_INCLUDE } :defs { :Id :Int } :type :Id :body 5 }"
        ).getVirtualFile();
        WriteAction.run(() -> roInWritableDir.setWritable(false));
        try {
            assertPresentation(s1ClipPretty, roInWritableDir, true, true);
            assertPresentation(s2ClipPretty, roInWritableDir, true, true);
            assertPresentation(s1ExpPretty, roInWritableDir, true, true);
            assertPresentation(s2ExpPretty, roInWritableDir, true, true);
            assertPresentation(sub3Group, roInWritableDir, true, false);
            assertPresentation(s3Pretty, roInWritableDir, true, false);
            assertPresentation(s3Compact, roInWritableDir, true, false);
        } finally {
            WriteAction.run(() -> roInWritableDir.setWritable(true));
        }

        // 8. Parentless read-only LightVirtualFile (.stvn_i): Clipboard enabled & functional, Export & In-Place disabled
        var roLightI = new LightVirtualFile(
            "mem_only.stvn_i",
            StvnFileType.PayloadInclude.INSTANCE,
            "{ :meta { #name \"mem_only\" #kind #BODY_INCLUDE } :defs { :Code :Int } :type :Code :body 9 }"
        );
        roLightI.setWritable(false);
        assertPresentation(s1ClipPretty, roLightI, true, true);
        assertPresentation(s2ClipPretty, roLightI, true, true);
        assertPresentation(s1ExpPretty, roLightI, true, false);
        assertPresentation(s2ExpPretty, roLightI, true, false);
        assertPresentation(s3Pretty, roLightI, true, false);

        invokeAction(s2ClipCompact, roLightI, null, false);
        String lightClip = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertNotNull(lightClip);
        assertTrue(lightClip.contains("#kind #BODY"));
    }

    public void testSubmenu1SchemaExtractionAllFourActionsAndBuildMenuAcrossOneStemTwoStemAndDiamondIncludes() throws Exception {
        myFixture.addFileToProject("diamond/vop_stage10a.core_test.stvn_d", DIAMOND_10A);
        myFixture.addFileToProject("diamond/vop_stage10b.core_test.stvn_di", DIAMOND_10B);
        var psi10c = myFixture.addFileToProject("diamond/vop_stage10c.core_test.stvn_i", DIAMOND_10C);
        myFixture.configureFromExistingVirtualFile(psi10c.getVirtualFile());

        String unsaved10c = DIAMOND_10C.replace(":TelemetryFrame", ":LiveTelemetryFrame");
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
            myFixture.getEditor().getDocument().setText(unsaved10c)
        );

        Map<String, String> wsMap = StvnVopProjectionService.collectWorkspaceSources(getProject(), psi10c.getVirtualFile());
        String expectedCas = StvnSchemaFlattener.flatten(
            wsMap,
            StvnSchemaFlattener.normalizePath(psi10c.getVirtualFile().getPath())
        );

        // 1.1 Copy to Clipboard (Pretty)
        invokeAction(new StvnCopyHermeticSchemaPrettyAction(), psi10c.getVirtualFile(), psi10c, true);
        assertEquals(unsaved10c, myFixture.getEditor().getDocument().getText());
        String clipPretty = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertNotNull(clipPretty);
        assertTrue(clipPretty.contains("#kind #DEFS"));
        assertTrue(clipPretty.contains(":LiveTelemetryFrame"));
        assertFalse(clipPretty.contains(":body"));
        assertEquals(clipPretty, StvnSourceFormatter.format(clipPretty));
        assertCompilesZeroErrorsAndZeroWarnings(clipPretty, "vop_stage10c.core_test.stvn_d");

        // 1.2 Copy to Clipboard (Compact CAS)
        invokeAction(new StvnCopyHermeticSchemaCompactCasAction(), psi10c.getVirtualFile(), psi10c, true);
        String clipCas = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertEquals(expectedCas, clipCas);
        assertCompilesZeroErrorsAndZeroWarnings(clipCas, "vop_stage10c.core_test.stvn_d");

        // 1.4 Export Sibling .stvn_d File (Pretty)
        invokeAction(new StvnExportHermeticSchemaPrettyAction(), psi10c.getVirtualFile(), psi10c, true);
        VirtualFile outDefs = psi10c.getVirtualFile().getParent().findChild("vop_stage10c.core_test.stvn_d");
        assertNotNull(outDefs);
        assertTrue(ProjectFileIndex.getInstance(getProject()).isInContent(outDefs));
        String diskPretty = new String(outDefs.contentsToByteArray(), StandardCharsets.UTF_8);
        assertFalse(diskPretty.contains("\r"));
        assertEquals(clipPretty, diskPretty);

        // 1.5 Export Sibling .stvn_d File (Compact CAS) & BuildMenu action
        invokeAction(new StvnExportHermeticSchemaCompactCasAction(), psi10c.getVirtualFile(), psi10c, true);
        String diskCas = new String(outDefs.contentsToByteArray(), StandardCharsets.UTF_8);
        assertEquals(expectedCas, diskCas);

        invokeAction(new StvnBuildExportSchemaCompactCasAction(), psi10c.getVirtualFile(), psi10c, true);
        assertEquals(expectedCas, new String(outDefs.contentsToByteArray(), StandardCharsets.UTF_8));

        // Audit #2: :meta-less 1-stem <name>.stvn -> <name>.stvn_d extraction
        var metaLessStvn = myFixture.addFileToProject(
            "metaless_order.stvn",
            "{ :defs { :OrderId { #unsigned } :Int } :type :OrderId :body 42 }"
        );
        invokeAction(new StvnExportHermeticSchemaPrettyAction(), metaLessStvn.getVirtualFile(), metaLessStvn, false);
        VirtualFile metaLessDefs = metaLessStvn.getVirtualFile().getParent().findChild("metaless_order.stvn_d");
        assertNotNull(metaLessDefs);
        String metaLessPretty = new String(metaLessDefs.contentsToByteArray(), StandardCharsets.UTF_8);
        assertTrue(metaLessPretty.contains("#kind #DEFS"));
        assertEquals(metaLessPretty, StvnSourceFormatter.format(metaLessPretty));
        assertCompilesZeroErrorsAndZeroWarnings(metaLessPretty, "metaless_order.stvn_d");
    }

    public void testSubmenu1EmptyDefsPayloadAbortsWithoutWritingClipboardOrDiskAndEmitsWarning() throws Exception {
        List<Notification> captured = new ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(@NotNull Notification notification) {
                captured.add(notification);
            }
        });

        CopyPasteManager.getInstance().setContents(new StringSelection("SENTINEL_CLIPBOARD"));
        var primitiveOnly = myFixture.addFileToProject("primitive_only.stvn", "{ :type :Int :body 42 }");

        invokeAction(new StvnCopyHermeticSchemaPrettyAction(), primitiveOnly.getVirtualFile(), primitiveOnly, false);
        invokeAction(new StvnCopyHermeticSchemaCompactCasAction(), primitiveOnly.getVirtualFile(), primitiveOnly, false);
        invokeAction(new StvnExportHermeticSchemaPrettyAction(), primitiveOnly.getVirtualFile(), primitiveOnly, false);
        invokeAction(new StvnExportHermeticSchemaCompactCasAction(), primitiveOnly.getVirtualFile(), primitiveOnly, false);

        assertEquals("SENTINEL_CLIPBOARD", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));
        assertNull(primitiveOnly.getVirtualFile().getParent().findChild("primitive_only.stvn_d"));
        assertEquals(4, captured.size());
        for (Notification notif : captured) {
            assertEquals(NotificationType.WARNING, notif.getType());
            assertEquals("STVN Hermetic Schema Extraction Skipped", notif.getTitle());
        }
    }

    public void testSubmenu2PayloadExtractionProjectsBodyKindOnStvnIAndCompilesZeroErrors() throws Exception {
        myFixture.addFileToProject("payload_mod/vop_stage10a.core_test.stvn_d", DIAMOND_10A);
        myFixture.addFileToProject("payload_mod/vop_stage10b.core_test.stvn_di", DIAMOND_10B);
        var psi10c = myFixture.addFileToProject("payload_mod/vop_stage10c.core_test.stvn_i", DIAMOND_10C);
        myFixture.configureFromExistingVirtualFile(psi10c.getVirtualFile());

        // 2.1 Copy to Clipboard (Pretty)
        invokeAction(new StvnCopyHermeticPayloadPrettyAction(), psi10c.getVirtualFile(), psi10c, true);
        assertEquals("Authoring .stvn_i buffer with comments must remain untouched",
            DIAMOND_10C, myFixture.getEditor().getDocument().getText());
        String clipPretty = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertNotNull(clipPretty);
        assertTrue("Must normalize #kind to #BODY:\n" + clipPretty, clipPretty.contains("#kind #BODY"));
        assertFalse("Must never leak #kind #BODY_INCLUDE:\n" + clipPretty, clipPretty.contains("#BODY_INCLUDE"));
        assertFalse("Must prune unreachable :UnusedDeadType:\n" + clipPretty, clipPretty.contains(":UnusedDeadType"));
        assertFalse("Must inline #DEFAULT_AZIMUTH:\n" + clipPretty, clipPretty.contains("#DEFAULT_AZIMUTH"));
        assertTrue("Must contain inlined azimuth value 180:\n" + clipPretty, clipPretty.contains("180"));
        assertEquals(clipPretty, StvnSourceFormatter.format(clipPretty));
        assertCompilesZeroErrorsAndZeroWarnings(clipPretty, "vop_stage10c.core_test.stvn");

        // 2.2 Copy to Clipboard (Compact)
        invokeAction(new StvnCopyHermeticPayloadCompactAction(), psi10c.getVirtualFile(), psi10c, true);
        String clipCompact = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        assertNotNull(clipCompact);
        assertTrue(clipCompact.contains("#kind #BODY"));
        assertFalse(clipCompact.contains("#BODY_INCLUDE"));
        assertTrue(clipCompact.contains("#T"));
        assertCompilesZeroErrorsAndZeroWarnings(clipCompact, "vop_stage10c.core_test.stvn");

        // 2.4 Export Sibling .stvn File (Pretty)
        invokeAction(new StvnExportHermeticPayloadPrettyAction(), psi10c.getVirtualFile(), psi10c, true);
        VirtualFile outPayload = psi10c.getVirtualFile().getParent().findChild("vop_stage10c.core_test.stvn");
        assertNotNull(outPayload);
        assertTrue(ProjectFileIndex.getInstance(getProject()).isInContent(outPayload));
        String diskPretty = new String(outPayload.contentsToByteArray(), StandardCharsets.UTF_8);
        assertFalse(diskPretty.contains("\r"));
        assertEquals(clipPretty, diskPretty);

        // 2.5 Export Sibling .stvn File (Compact)
        invokeAction(new StvnExportHermeticPayloadCompactAction(), psi10c.getVirtualFile(), psi10c, true);
        String diskCompact = new String(outPayload.contentsToByteArray(), StandardCharsets.UTF_8);
        assertEquals(clipCompact, diskCompact);
    }

    public void testSubmenu3InPlaceBufferReplacementPreservesKindSupportsProjectViewWithoutEditorSafeguard2AndUndo() {
        var projSettings = StvnProjectSettings.getInstance(getProject());
        boolean prevSuppress = projSettings.getState().suppressCanonicalizationWarning;
        projSettings.getState().suppressCanonicalizationWarning = true;

        List<Notification> captured = new ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(@NotNull Notification notification) {
                captured.add(notification);
            }
        });

        try {
            // 1. Multi-file .stvn_i via ProjectViewPopupMenu WITHOUT open Editor (CommonDataKeys.EDITOR omitted)
            myFixture.addFileToProject("inplace/vop_stage10a.core_test.stvn_d", DIAMOND_10A);
            myFixture.addFileToProject("inplace/vop_stage10b.core_test.stvn_di", DIAMOND_10B);
            var psi10c = myFixture.addFileToProject("inplace/vop_stage10c.core_test.stvn_i", DIAMOND_10C);

            var projectViewOnlyCtx = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.VIRTUAL_FILE, psi10c.getVirtualFile())
                .build();
            var prettyInPlace = new StvnReplaceBufferPrettyIrAction();
            prettyInPlace.actionPerformed(TestActionEvent.createTestEvent(prettyInPlace, projectViewOnlyCtx));

            Document doc10c = FileDocumentManager.getInstance().getDocument(psi10c.getVirtualFile());
            assertNotNull(doc10c);
            String replacedPretty = doc10c.getText();
            assertTrue("In-place replacement on .stvn_i must preserve #kind #BODY_INCLUDE:\n" + replacedPretty,
                replacedPretty.contains("#kind #BODY_INCLUDE"));
            assertFalse(replacedPretty.contains("// Comment preserved"));
            assertCompilesZeroErrorsAndZeroWarnings(replacedPretty, "vop_stage10c.core_test.stvn_i");

            // Verify Undo (Ctrl+Z) restores original commented .stvn_i source
            FileEditor[] editors = FileEditorManager.getInstance(getProject()).openFile(psi10c.getVirtualFile(), true);
            assertTrue(editors.length > 0);
            assertTrue(UndoManager.getInstance(getProject()).isUndoAvailable(editors[0]));
            UndoManager.getInstance(getProject()).undo(editors[0]);
            assertEquals(DIAMOND_10C, doc10c.getText());

            // 2. Multi-file .stvn_di in-place Compact IR preserves #kind #DEFS_INCLUDE
            var psi10b = myFixture.addFileToProject("inplace2/vop_stage10a.core_test.stvn_d", DIAMOND_10A);
            assertNotNull(psi10b);
            var midDi = myFixture.addFileToProject("inplace2/vop_stage10b.core_test.stvn_di", DIAMOND_10B);
            invokeAction(new StvnReplaceBufferCompactIrAction(), midDi.getVirtualFile(), midDi, false);
            Document docMid = FileDocumentManager.getInstance().getDocument(midDi.getVirtualFile());
            assertNotNull(docMid);
            assertTrue(docMid.getText().contains("#kind #DEFS_INCLUDE"));
            assertCompilesZeroErrorsAndZeroWarnings(docMid.getText(), "vop_stage10b.core_test.stvn_di");

            // 3. Safeguard 2: .stvn_di containing ONLY Prelude :use desugars to empty :defs {} -> aborts cleanly
            captured.clear();
            String useOnlyDiSource = """
                {
                  :meta {
                    #name "use_only"
                    #kind #DEFS_INCLUDE
                  }
                  :defs {
                    :use [ :org/stvnadore/prelude { #strip } ]
                  }
                }
                """;
            var useOnlyDi = myFixture.addFileToProject("use_only.stvn_di", useOnlyDiSource);
            invokeAction(new StvnReplaceBufferPrettyIrAction(), useOnlyDi.getVirtualFile(), useOnlyDi, false);
            Document useOnlyDoc = FileDocumentManager.getInstance().getDocument(useOnlyDi.getVirtualFile());
            assertNotNull(useOnlyDoc);
            assertEquals("Safeguard 2 must prevent mutating .stvn_di into invalid empty :defs {}",
                useOnlyDiSource, useOnlyDoc.getText());
            assertEquals(1, captured.size());
            assertEquals(NotificationType.WARNING, captured.get(0).getType());
            assertEquals("STVN In-Place Canonicalization Skipped", captured.get(0).getTitle());
        } finally {
            projSettings.getState().suppressCanonicalizationWarning = prevSuppress;
        }
    }

    public void testCompilationErrorsEmitBalloonsWithoutMutatingClipboardDiskOrBuffer() {
        var projSettings = StvnProjectSettings.getInstance(getProject());
        boolean prevSuppress = projSettings.getState().suppressCanonicalizationWarning;
        projSettings.getState().suppressCanonicalizationWarning = true;

        List<Notification> captured = new ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(@NotNull Notification notification) {
                captured.add(notification);
            }
        });

        try {
            CopyPasteManager.getInstance().setContents(new StringSelection("ERROR_SENTINEL"));
            String invalidPayload = "{ :defs { :Age { #minIncl 0 } :Int } :type :Age :body -5 }";
            var brokenFile = myFixture.configureByText("broken_payload.stvn", invalidPayload);

            invokeAction(new StvnCopyHermeticPayloadPrettyAction(), brokenFile.getVirtualFile(), brokenFile, true);
            invokeAction(new StvnReplaceBufferCompactIrAction(), brokenFile.getVirtualFile(), brokenFile, true);

            assertEquals("ERROR_SENTINEL", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));
            assertEquals(invalidPayload, myFixture.getEditor().getDocument().getText());
            assertEquals(2, captured.size());
            for (Notification notif : captured) {
                assertEquals(NotificationType.ERROR, notif.getType());
                assertEquals("STVN Projection Failed", notif.getTitle());
            }
        } finally {
            projSettings.getState().suppressCanonicalizationWarning = prevSuppress;
        }
    }

    private void assertExactNonMnemonicLabel(
            @NotNull AnAction action,
            com.intellij.openapi.actionSystem.DataContext dataCtx,
            String expectedExactText
    ) {
        assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread());
        String templateText = action.getTemplatePresentation().getText();
        assertEquals(expectedExactText, templateText);
        assertTrue(templateText.contains(".stvn_d"));
        assertFalse(templateText.contains(".stvnd"));

        var event = TestActionEvent.createTestEvent(action, dataCtx);
        action.update(event);
        String updatedText = event.getPresentation().getText();
        assertEquals(expectedExactText, updatedText);
        assertTrue(updatedText.contains(".stvn_d"));
        assertFalse(updatedText.contains(".stvnd"));
    }

    private void assertPresentation(
            AnAction action,
            VirtualFile vf,
            boolean expectedVisible,
            boolean expectedEnabled
    ) {
        assertEquals(ActionUpdateThread.BGT, action.getActionUpdateThread());
        var ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, vf)
            .build();
        var event = TestActionEvent.createTestEvent(action, ctx);
        action.update(event);
        assertEquals("Visibility mismatch for " + action.getClass().getSimpleName() + " on " + vf.getName(),
            expectedVisible, event.getPresentation().isVisible());
        assertEquals("Enablement mismatch for " + action.getClass().getSimpleName() + " on " + vf.getName(),
            expectedEnabled, event.getPresentation().isEnabled());
    }

    private void invokeAction(
            AnAction action,
            VirtualFile vf,
            @Nullable PsiFile psiFile,
            boolean includeEditor
    ) {
        var builder = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, vf);
        if (psiFile != null) {
            builder.add(CommonDataKeys.PSI_FILE, psiFile);
        }
        if (includeEditor) {
            builder.add(CommonDataKeys.EDITOR, myFixture.getEditor());
        }
        action.actionPerformed(TestActionEvent.createTestEvent(action, builder.build()));
    }

    private static void assertCompilesZeroErrorsAndZeroWarnings(String content, String fileName) {
        StvnCompilationResult<StvnDocument> res = StvnCompiler.compileToResult(
            content,
            fileName,
            StvnParserConfig.DEFAULT
        );
        assertFalse("Expected zero compile errors for " + fileName + ":\n" + res.diagnostics() + "\nContent:\n" + content,
            res.hasErrors());
        assertTrue("Expected zero compile warnings for " + fileName + ":\n" + res.diagnostics(),
            res.diagnostics().isEmpty());
    }
}
