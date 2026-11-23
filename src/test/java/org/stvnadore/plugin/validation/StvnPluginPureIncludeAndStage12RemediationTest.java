package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl;
import com.intellij.codeInsight.daemon.impl.AnnotationSessionImpl;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompilationResult;
import com.intellij.psi.PsiDocumentManager;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.plugin.actions.PublishSchemaAction;
import org.stvnadore.plugin.actions.StvnCanonicalizationGuard;
import org.stvnadore.plugin.actions.StvnExportHermeticSchemaPrettyAction;
import org.stvnadore.plugin.actions.StvnVopProjectionService;
import org.stvnadore.plugin.documentation.StvnDocumentationProvider;
import org.stvnadore.plugin.validation.quickfix.RenameFileExtensionToKindQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnFileRenameExecutor;
import org.stvnadore.plugin.validation.quickfix.StvnMatchMetaFileRenameQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnOmitDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRenameDomainQuickFix;
import org.stvnadore.psi.BodyEntry;
import org.stvnadore.psi.DefsEntry;
import org.stvnadore.psi.MetaDomainFacet;
import org.stvnadore.psi.MetaKindFacet;
import org.stvnadore.psi.MetaNameFacet;
import org.stvnadore.psi.TypeDefinition;
import org.stvnadore.psi.TypeEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive IntelliJ Platform regression suite covering {@code PPII-01} (Pure {@code :include}
 * Grammar-Kit homology, excised {@code Include*} PSI classes, {@code E002} contract parity, and
 * Stage 10 cross-file {@code :package} + {@code :use} hover parity), {@code PPII-02} (Stage 12
 * atomic {@code :meta} file rename, {@code #kind} extension rename, {@code 0} {@code "Remove"}
 * intentions on {@code #kind}, mouse-hover / {@code getAllQuickFixes()} safety, single-line
 * {@code :meta} isolation, and collision error feedback), and {@code PPII-03} ({@code ACT-01}..{@code ACT-04}
 * menu and publish action hardening).
 */
@NullMarked
public final class StvnPluginPureIncludeAndStage12RemediationTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            StvnVopProjectionService.setOverwriteConfirmationOverride(null);
            StvnFileRenameExecutor.getAndClearLastReportedRenameError();
        } finally {
            super.tearDown();
        }
    }

    /**
     * PPII-01 & Safeguard 1: Verifies that excised Include* PSI and mixin classes are purged from
     * the classpath, prohibited ':include [... { ... }]' blocks emit the exact upstream E002 message
     * across all 3 spans in include_prohibited_options_and_alias_blocks.core_test.stvn_i, and balanced
     * stvn.bnf recovery keeps downstream ':defs', ':type', and ':body' PSI intact.
     */
    public void testPureIncludeExcisionUpstreamE002ContractParityAndBalancedDefsRecovery() throws Exception {
        for (String excisedClassName : List.of(
            "org.stvnadore.psi.IncludeMapAlias",
            "org.stvnadore.psi.IncludeAliasBlock",
            "org.stvnadore.psi.IncludeOptionsBlock",
            "org.stvnadore.psi.IncludeOption",
            "org.stvnadore.plugin.psi.impl.StvnIncludeMapAliasMixin"
        )) {
            try {
                Class.forName(excisedClassName);
                fail("Excised legacy class must not exist on classpath: " + excisedClassName);
            } catch (ClassNotFoundException expected) {
                // Expected under Pure :include Ingestion Design 3
            }
        }

        Path contractPath = Paths.get(
            getTestDataPath(),
            "syntax/invalid/modules/include_prohibited_options_and_alias_blocks.contract.stvn_i"
        );
        assertTrue("Mirrored contract fixture must exist: " + contractPath, Files.isRegularFile(contractPath));
        String contractText = Files.readString(contractPath, StandardCharsets.UTF_8);
        String expectedUpstreamCoreMessage =
            "STVN Syntax Error: ':include' accepts only module path string literals; use ':package' and ':use' for '#strip' or symbol aliasing";
        assertTrue("Upstream contract must contain exact E002 message", contractText.contains(expectedUpstreamCoreMessage));

        myFixture.copyFileToProject(
            "syntax/valid/modules/stage10a_deep_telemetry.core_test.stvn_d",
            "syntax/valid/modules/stage10a_deep_telemetry.core_test.stvn_d"
        );
        myFixture.copyFileToProject(
            "syntax/valid/modules/network_primitives.stvn_d",
            "syntax/valid/modules/network_primitives.stvn_d"
        );
        myFixture.copyFileToProject(
            "syntax/valid/modules/stage10_brand_provider.core_test.stvn_d",
            "syntax/valid/modules/stage10_brand_provider.core_test.stvn_d"
        );
        PsiFile psiFile = myFixture.configureByFile(
            "syntax/invalid/modules/include_prohibited_options_and_alias_blocks.core_test.stvn_i"
        );

        // Verify balanced Grammar-Kit PSI recovery: :LocalHealthyType, :type, and :body remain intact
        DefsEntry defsEntry = PsiTreeUtil.findChildOfType(psiFile, DefsEntry.class);
        TypeEntry typeEntry = PsiTreeUtil.findChildOfType(psiFile, TypeEntry.class);
        BodyEntry bodyEntry = PsiTreeUtil.findChildOfType(psiFile, BodyEntry.class);
        assertNotNull(":defs section must not collapse", defsEntry);
        assertNotNull(":type section must remain intact after prohibited :include blocks", typeEntry);
        assertNotNull(":body section must remain intact after prohibited :include blocks", bodyEntry);
        var localDefs = PsiTreeUtil.findChildrenOfType(defsEntry, TypeDefinition.class);
        assertTrue(
            "Downstream :LocalHealthyType inside :defs must survive :include recovery",
            localDefs.stream().anyMatch(td -> td.getTypeKeyword() != null
                && ":LocalHealthyType".equals(td.getTypeKeyword().getText()))
        );

        enableAllRegisteredStvnInspections();
        var highlights = myFixture.doHighlighting();
        String expectedFormattedAnnotation = expectedUpstreamCoreMessage + " [stvn-core:E002-STVN_SYNTAX_ERROR]";
        var matchingE002 = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> expectedFormattedAnnotation.equals(h.getDescription()))
            .toList();
        assertEquals(
            "Expected all 3 prohibited :include blocks ({ #strip }, { :Port :LocalPort }, {}) to emit exact upstream E002, found: " + highlights,
            3,
            matchingE002.size()
        );
    }

    /**
     * PPII-01: Verifies Stage 10 cross-file ':package' + ':use' hover parity on
     * stage10c_diamond_mission.core_test.stvn_i, stage10_cross_file_package_brand_mismatch.core_test.stvn_i,
     * and when Pass 3 payload lowering is suspended due to an unrelated syntax error in ':body'
     * (never falsely emitting 'Suspended (Ungrounded Schema)').
     */
    public void testStage10CrossFilePackageAndUseHoverParityNormalBrandMismatchAndPass3Suspended() {
        myFixture.copyFileToProject(
            "syntax/valid/modules/stage10a_deep_telemetry.core_test.stvn_d",
            "syntax/valid/modules/stage10a_deep_telemetry.core_test.stvn_d"
        );
        myFixture.copyFileToProject(
            "syntax/valid/modules/stage10b_nav_bridge.core_test.stvn_di",
            "syntax/valid/modules/stage10b_nav_bridge.core_test.stvn_di"
        );
        myFixture.copyFileToProject(
            "syntax/valid/modules/stage10_brand_provider.core_test.stvn_d",
            "syntax/valid/modules/stage10_brand_provider.core_test.stvn_d"
        );

        // 1. Healthy diamond Stage 10C hover
        PsiFile diamondFile = myFixture.configureByFile("syntax/valid/modules/stage10c_diamond_mission.core_test.stvn_i");
        String diamondText = diamondFile.getText();
        String docDeepFix = generateHoverDocAt(diamondFile, diamondText.indexOf("#LOCAL_DEEP_FIX", diamondText.indexOf(":body")));
        assertNotNull(docDeepFix);
        assertFalse("Valid cross-file :use constant hover must never report Ungrounded Schema:\n" + docDeepFix,
            docDeepFix.contains("Ungrounded Schema"));
        assertTrue("Diamond hover must show Satisfied compliance:\n" + docDeepFix,
            docDeepFix.contains("Satisfied"));

        // 2. Stage 10 cross-file package brand mismatch hover (E027)
        PsiFile mismatchFile = myFixture.configureByFile(
            "syntax/invalid/modules/stage10_cross_file_package_brand_mismatch.core_test.stvn_i"
        );
        String mismatchText = mismatchFile.getText();
        String docBrandMismatch = generateHoverDocAt(mismatchFile, mismatchText.indexOf("#ALPHA_CH", mismatchText.indexOf(":body")));
        assertNotNull(docBrandMismatch);
        assertFalse("Cross-file packaged :BetaId must never falsely report Ungrounded Schema:\n" + docBrandMismatch,
            docBrandMismatch.contains("Ungrounded Schema"));
        assertTrue("Cross-file brand mismatch hover must report violation:\n" + docBrandMismatch,
            docBrandMismatch.contains("In Violation") || docBrandMismatch.contains("ERR_INCOMPATIBLE_NOMINAL_TYPE"));

        // 3. Pass 3 suspended mode on cross-file :package + :use schema (syntax error elsewhere in :body)
        String suspendedSource = """
            {
              :meta {
                #name "stage10_suspended_probe"
                #domain "core_test"
                #kind #BODY_INCLUDE
              }
              :defs {
                :include [ "stage10_brand_provider.core_test.stvn_d" ]
                :use [
                  :com/acme/alpha {
                    :ChannelId :AlphaId
                    #ALPHA_CHANNEL #ALPHA_CH
                  }
                ]
              }
              :type :Tuple( :AlphaId :Int )
              :body ( #ALPHA_CH :broken_token )
            }
            """;
        PsiFile suspendedPsi = myFixture.addFileToProject(
            "syntax/valid/modules/stage10_suspended_probe.core_test.stvn_i",
            suspendedSource
        );
        myFixture.configureFromExistingVirtualFile(suspendedPsi.getVirtualFile());
        String suspendedDoc = generateHoverDocAt(
            suspendedPsi,
            suspendedSource.indexOf("#ALPHA_CH", suspendedSource.indexOf(":body"))
        );
        assertNotNull(suspendedDoc);
        assertFalse(
            "Pass-3-suspended cross-file :use schema must resolve via PSI and NEVER emit 'Suspended (Ungrounded Schema)':\n" + suspendedDoc,
            suspendedDoc.contains("Ungrounded Schema")
        );
    }

    /**
     * PPII-02 & Safeguard 2: Verifies Stage 12 'scratch.stvn' atomic ':meta' file rename (Priority.TOP),
     * '#kind' file-extension rename (Priority.HIGH), 'Update #kind' (Priority.NORMAL), ZERO '"Remove"'
     * intentions at '#kind #DEFS' and '#name', mouse-hover / getAllQuickFixes() availability when the
     * editor caret rests on line 1, and Pass A (0 inspections) == Pass B (26 inspections) parity.
     */
    public void testStage12ScratchStvnAtomicMetaRenameKindExtensionRenameZeroRemoveAndHoverSafety() throws Exception {
        String scratchSource = """
            {
              :meta {
                #name "stage12_test"
                #domain "core_test"
                #kind #DEFS
              }
              :defs {
                :Port { #unsigned #size 16 } :Int
              }
            }
            """;

        // Pass A (0 inspections active): explicitly disable StvnMissingMeta and StvnStemMismatch in profile
        myFixture.enableInspections(new StvnMissingMetaInspection(), new StvnStemMismatchInspection());
        myFixture.disableInspections(new StvnMissingMetaInspection(), new StvnStemMismatchInspection());
        PsiFile passAFile = myFixture.configureByText("scratch.stvn", scratchSource);
        var annotator = new StvnExternalAnnotator();
        var passAAnnotations = computeExternalAnnotations(annotator, passAFile);
        var kindErrorPassA = passAAnnotations.stream()
            .filter(a -> a.getMessage() != null && a.getMessage().contains("ERR_DOCUMENT_KIND_MISMATCH"))
            .findFirst();
        assertTrue("Pass A must emit E031 on #kind #DEFS", kindErrorPassA.isPresent());
        var passAKindFixLabels = kindErrorPassA.get().getQuickFixes().stream()
            .map(qf -> qf.quickFix.getText())
            .toList();
        assertTrue("Pass A must attach atomic match :meta fix: " + passAKindFixLabels,
            passAKindFixLabels.contains("Rename file to 'stage12_test.core_test.stvn_d' (match :meta)"));
        assertTrue("Pass A must attach #kind extension rename fix: " + passAKindFixLabels,
            passAKindFixLabels.contains("Rename file extension to '.stvn_d' (rename to 'scratch.stvn_d')"));
        assertTrue("Pass A must attach Update #kind fix: " + passAKindFixLabels,
            passAKindFixLabels.contains("Update '#kind' to match file extension"));

        // Pass B (all 26 local inspections enabled)
        enableAllRegisteredStvnInspections();
        PsiFile scratchFile = myFixture.addFileToProject("stage12_dir/scratch.stvn", scratchSource);
        myFixture.configureFromExistingVirtualFile(scratchFile.getVirtualFile());
        myFixture.doHighlighting();

        // Safeguard 2.1: Keep caret on line 1 (offset 0, outside :meta) and verify getAllQuickFixes() / mouse-hover safety
        myFixture.getEditor().getCaretModel().moveToOffset(0);
        List<String> allQuickFixLabelsAtLine1 = myFixture.getAllQuickFixes().stream()
            .map(IntentionAction::getText)
            .toList();
        assertTrue(
            "Mouse-hover / getAllQuickFixes() with caret on line 1 must include atomic match :meta rename, found: " + allQuickFixLabelsAtLine1,
            allQuickFixLabelsAtLine1.contains("Rename file to 'stage12_test.core_test.stvn_d' (match :meta)")
        );
        assertTrue(
            "Mouse-hover / getAllQuickFixes() with caret on line 1 must include #kind extension rename, found: " + allQuickFixLabelsAtLine1,
            allQuickFixLabelsAtLine1.contains("Rename file extension to '.stvn_d' (rename to 'scratch.stvn_d')")
        );

        // Move caret to '#kind #DEFS': verify priority order, zero duplicates, and Safeguard 2.2 ZERO "Remove" intentions
        int kindOffset = scratchSource.indexOf("#kind #DEFS") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(kindOffset);
        List<IntentionAction> kindIntentions = myFixture.getAvailableIntentions();
        List<String> kindLabels = kindIntentions.stream().map(IntentionAction::getText).toList();

        String matchMetaLabel = "Rename file to 'stage12_test.core_test.stvn_d' (match :meta)";
        String extRenameLabel = "Rename file extension to '.stvn_d' (rename to 'scratch.stvn_d')";
        String updateKindLabel = "Update '#kind' to match file extension";

        assertEquals("Atomic match :meta quick-fix must appear strictly once: " + kindLabels,
            1, kindLabels.stream().filter(matchMetaLabel::equals).count());
        assertEquals("Extension rename quick-fix must appear strictly once: " + kindLabels,
            1, kindLabels.stream().filter(extRenameLabel::equals).count());
        assertEquals("Update #kind quick-fix must appear strictly once: " + kindLabels,
            1, kindLabels.stream().filter(updateKindLabel::equals).count());

        int matchIdx = kindLabels.indexOf(matchMetaLabel);
        int extIdx = kindLabels.indexOf(extRenameLabel);
        int updateIdx = kindLabels.indexOf(updateKindLabel);
        assertTrue("Priority order on #kind must be TOP (match :meta) < HIGH (extension) < NORMAL (Update #kind): " + kindLabels,
            matchIdx >= 0 && matchIdx < extIdx && extIdx < updateIdx);

        assertTrue(
            "Safeguard 2.2: Zero 'Remove' intentions may appear on '#kind #DEFS', found: " + kindLabels,
            kindLabels.stream().noneMatch(label -> label.contains("Remove"))
        );

        // Verify '#name' also offers atomic match :meta FIRST and zero 'Remove' intentions
        int nameOffset = scratchSource.indexOf("#name \"stage12_test\"") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(nameOffset);
        List<String> nameLabels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();
        assertTrue("#name must offer atomic match :meta FIRST: " + nameLabels,
            nameLabels.indexOf(matchMetaLabel) >= 0
                && nameLabels.indexOf(matchMetaLabel) < nameLabels.indexOf("Align '#name' with filename stem slot 0 ('scratch')"));
        assertTrue("Zero 'Remove' intentions may appear on primary '#name': " + nameLabels,
            nameLabels.stream().noneMatch(label -> label.contains("Remove")));

        // Verify Intention Preview safety (does not rename physical file and returns non-empty rename card)
        myFixture.getEditor().getCaretModel().moveToOffset(kindOffset);
        IntentionAction matchMetaAction = myFixture.filterAvailableIntentions(matchMetaLabel).get(0);
        myFixture.getIntentionPreviewText(matchMetaAction);
        assertEquals("scratch.stvn", scratchFile.getVirtualFile().getName());
        var matchMetaPreview = matchMetaAction.generatePreview(getProject(), myFixture.getEditor(), scratchFile);
        assertNotNull(matchMetaPreview);
        assertNotSame(com.intellij.codeInsight.intention.preview.IntentionPreviewInfo.EMPTY, matchMetaPreview);

        // Execute atomic match :meta rename -> 'stage12_test.core_test.stvn_d' with 0 errors and 0 warnings
        myFixture.launchAction(matchMetaAction);
        assertEquals("stage12_test.core_test.stvn_d", scratchFile.getVirtualFile().getName());
        StvnCompilationResult<StvnDocument> compiledAfterAtomicRename = StvnCompiler.compileToResult(
            scratchFile.getText(),
            scratchFile.getVirtualFile().getName(),
            StvnParserConfig.DEFAULT
        );
        assertFalse("Renamed file must compile with 0 errors: " + compiledAfterAtomicRename.diagnostics(),
            compiledAfterAtomicRename.hasErrors());
        assertTrue("Renamed file must compile with 0 warnings: " + compiledAfterAtomicRename.diagnostics(),
            compiledAfterAtomicRename.diagnostics().isEmpty());

        // Also verify RenameFileExtensionToKindQuickFix renames 'scratch2.stvn' -> 'scratch2.stvn_d'
        PsiFile scratch2 = myFixture.addFileToProject("stage12_ext/scratch2.stvn", scratchSource);
        myFixture.configureFromExistingVirtualFile(scratch2.getVirtualFile());
        myFixture.doHighlighting();
        myFixture.getEditor().getCaretModel().moveToOffset(kindOffset);
        var extActions = myFixture.filterAvailableIntentions("Rename file extension to '.stvn_d' (rename to 'scratch2.stvn_d')");
        assertEquals(1, extActions.size());
        myFixture.launchAction(extActions.get(0));
        assertEquals("scratch2.stvn_d", scratch2.getVirtualFile().getName());
    }

    /**
     * Safeguard 2: Verifies single-line ':meta { #name "inline_target" #domain "core_test" #kind #DEFS }'
     * isolates intentions strictly to the facet under the caret (zero '#domain' Remove bleed onto '#kind #DEFS').
     */
    public void testSingleLineMetaBlockIsolatesCrossFacetIntentionsAndPreventsRemoveBleed() {
        enableAllRegisteredStvnInspections();
        String inlineMetaSource = """
            {
              :meta { #name "inline_target" #domain "core_test" #kind #DEFS }
              :defs {
                :Id :Int
              }
            }
            """;
        PsiFile inlineFile = myFixture.addFileToProject("inline_dir/scratch_inline.stvn", inlineMetaSource);
        myFixture.configureFromExistingVirtualFile(inlineFile.getVirtualFile());
        myFixture.doHighlighting();

        int kindCaret = inlineMetaSource.indexOf("#kind #DEFS") + 3;
        myFixture.getEditor().getCaretModel().moveToOffset(kindCaret);
        List<String> kindLabels = myFixture.getAvailableIntentions().stream().map(IntentionAction::getText).toList();

        assertTrue("Single-line #kind must offer atomic match :meta fix: " + kindLabels,
            kindLabels.contains("Rename file to 'inline_target.core_test.stvn_d' (match :meta)"));
        assertTrue("Single-line #kind must offer extension rename fix: " + kindLabels,
            kindLabels.contains("Rename file extension to '.stvn_d' (rename to 'scratch_inline.stvn_d')"));
        assertTrue("Zero 'Remove' intentions may bleed from single-line #domain onto #kind: " + kindLabels,
            kindLabels.stream().noneMatch(l -> l.contains("Remove")));
        assertTrue("Zero #name Align fixes may bleed onto single-line #kind: " + kindLabels,
            kindLabels.stream().noneMatch(l -> l.contains("Align '#name'")));
        assertEquals("Atomic match :meta fix must not duplicate on single-line :meta: " + kindLabels,
            1,
            kindLabels.stream().filter("Rename file to 'inline_target.core_test.stvn_d' (match :meta)"::equals).count());
    }

    /**
     * PPII-02: Verifies explicit sibling collision error feedback and atomic buffer rollback protection
     * across all 6 file-renaming quick-fixes.
     */
    public void testAllSixFileRenameQuickFixesReportCollisionErrorWithoutMutatingFileOrBuffer() {
        List<Notification> notifications = new ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(@NotNull Notification notification) {
                notifications.add(notification);
            }
        });

        String sourceText = """
            {
              :meta {
                #name "target_doc"
                #domain "core_test"
                #kind #DEFS
              }
              :defs {
                :Item :Int
              }
            }
            """;
        PsiFile psiFile = myFixture.addFileToProject("collisions/scratch.stvn", sourceText);
        myFixture.configureFromExistingVirtualFile(psiFile.getVirtualFile());

        // Pre-create colliding sibling targets for all 4 facet-level rename fixes
        myFixture.addFileToProject("collisions/target_doc.core_test.stvn_d", "{ :meta { #name \"target_doc\" #domain \"core_test\" #kind #DEFS } :defs { :X :Int } }");
        myFixture.addFileToProject("collisions/scratch.stvn_d", "{ :meta { #name \"scratch\" #kind #DEFS } :defs { :X :Int } }");
        myFixture.addFileToProject("collisions/target_doc.stvn", "{ :type :Int :body 1 }");
        myFixture.addFileToProject("collisions/scratch.core_test.stvn", "{ :type :Int :body 1 }");

        MetaNameFacet nameFacet = PsiTreeUtil.findChildOfType(psiFile, MetaNameFacet.class);
        MetaDomainFacet domainFacet = PsiTreeUtil.findChildOfType(psiFile, MetaDomainFacet.class);
        MetaKindFacet kindFacet = PsiTreeUtil.findChildOfType(psiFile, MetaKindFacet.class);
        assertNotNull(nameFacet);
        assertNotNull(domainFacet);
        assertNotNull(kindFacet);
        assertNotNull(nameFacet.getStringLiteral());
        assertNotNull(domainFacet.getStringLiteral());

        // 1. StvnMatchMetaFileRenameQuickFix
        StvnFileRenameExecutor.getAndClearLastReportedRenameError();
        new StvnMatchMetaFileRenameQuickFix(kindFacet, "target_doc.core_test.stvn_d")
            .invoke(getProject(), psiFile, myFixture.getEditor(), kindFacet, kindFacet);
        assertEquals("scratch.stvn", psiFile.getVirtualFile().getName());
        assertCollisionRecorded("scratch.stvn", "target_doc.core_test.stvn_d");

        // 2. RenameFileExtensionToKindQuickFix
        var extFix = RenameFileExtensionToKindQuickFix.createIfApplicable(psiFile, kindFacet);
        assertNotNull(extFix);
        extFix.invoke(getProject(), psiFile, myFixture.getEditor(), kindFacet, kindFacet);
        assertEquals("scratch.stvn", psiFile.getVirtualFile().getName());
        assertCollisionRecorded("scratch.stvn", "scratch.stvn_d");

        // 3. RenameFileToDeclaredNameQuickFix
        PsiElement nameLit = nameFacet.getStringLiteral();
        new StvnStemMismatchInspection.RenameFileToDeclaredNameQuickFix(nameLit, "target_doc.stvn")
            .invoke(getProject(), psiFile, myFixture.getEditor(), nameLit, nameLit);
        assertEquals("scratch.stvn", psiFile.getVirtualFile().getName());
        assertCollisionRecorded("scratch.stvn", "target_doc.stvn");

        // 4. IncludeDomainInFilenameQuickFix
        PsiElement domainLit = domainFacet.getStringLiteral();
        new StvnStemMismatchInspection.IncludeDomainInFilenameQuickFix(domainLit, "scratch.core_test.stvn")
            .invoke(getProject(), psiFile, myFixture.getEditor(), domainLit, domainLit);
        assertEquals("scratch.stvn", psiFile.getVirtualFile().getName());
        assertCollisionRecorded("scratch.stvn", "scratch.core_test.stvn");

        // 5 & 6. StvnOmitDomainQuickFix and StvnRenameDomainQuickFix (verify buffer #domain is NOT mutated on collision!)
        String reservedText = """
            {
              :meta {
                #name "reserved_probe"
                #domain "stvn_core"
                #kind #DEFS
              }
              :defs {
                :Item :Int
              }
            }
            """;
        PsiFile reservedFile = myFixture.addFileToProject("collisions2/reserved_probe.stvn_core.stvn_d", reservedText);
        myFixture.addFileToProject("collisions2/reserved_probe.stvn_d", "{ :meta { #name \"reserved_probe\" #kind #DEFS } :defs { :X :Int } }");
        myFixture.addFileToProject("collisions2/reserved_probe.domain.stvn_d", "{ :meta { #name \"reserved_probe\" #domain \"domain\" #kind #DEFS } :defs { :X :Int } }");

        var omitFix = StvnOmitDomainQuickFix.create(reservedFile);
        assertNotNull(omitFix);
        omitFix.invoke(getProject(), null, reservedFile);
        assertEquals("reserved_probe.stvn_core.stvn_d", reservedFile.getVirtualFile().getName());
        assertTrue("Buffer #domain must remain untouched when StvnOmitDomainQuickFix aborts on collision",
            reservedFile.getText().contains("#domain \"stvn_core\""));
        assertCollisionRecorded("reserved_probe.stvn_core.stvn_d", "reserved_probe.stvn_d");

        var renameDomainFix = StvnRenameDomainQuickFix.create(reservedFile);
        assertNotNull(renameDomainFix);
        renameDomainFix.invoke(getProject(), null, reservedFile);
        assertEquals("reserved_probe.stvn_core.stvn_d", reservedFile.getVirtualFile().getName());
        assertTrue("Buffer #domain must remain untouched when StvnRenameDomainQuickFix aborts on collision",
            reservedFile.getText().contains("#domain \"stvn_core\""));
        assertCollisionRecorded("reserved_probe.stvn_core.stvn_d", "reserved_probe.domain.stvn_d");

        assertEquals("All 6 blocked renames must emit STVN File Rename Failed warning notifications",
            6,
            notifications.stream()
                .filter(n -> n.getType() == NotificationType.WARNING && "STVN File Rename Failed".equals(n.getTitle()))
                .count());
    }

    /**
     * PPII-03 (ACT-01 & ACT-02): Verifies non-EDT thread safety of workspace collection, sibling
     * overwrite confirmation cancel/proceed behavior, and UndoManager Ctrl+Z rollback on sibling export.
     */
    public void testMenuActionsBackgroundReadSafetySiblingOverwriteGuardAndUndoRollback() throws Exception {
        String modSourceV1 = """
            {
              :meta {
                #name "export_probe"
                #domain "core_test"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :OrderCount { #unsigned #size 32 } :Int
              }
            }
            """;
        PsiFile modFile = myFixture.addFileToProject("actions_dir/export_probe.core_test.stvn_di", modSourceV1);

        // ACT-01: Verify collectWorkspaceSources runs cleanly from a non-EDT worker thread without outer ReadAction
        Map<String, String> collectedOffEdt = CompletableFuture.supplyAsync(() -> {
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed());
            return StvnVopProjectionService.collectWorkspaceSources(getProject(), modFile.getVirtualFile());
        }).get(5, TimeUnit.SECONDS);
        assertFalse("Workspace source map collected off EDT must not be empty", collectedOffEdt.isEmpty());

        // Export initial sibling .stvn_d
        AnAction exportPretty = new StvnExportHermeticSchemaPrettyAction();
        var ctx = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, modFile.getVirtualFile())
            .add(CommonDataKeys.PSI_FILE, modFile)
            .build();
        exportPretty.actionPerformed(TestActionEvent.createTestEvent(exportPretty, ctx));

        VirtualFile siblingDefs = modFile.getVirtualFile().getParent().findChild("export_probe.core_test.stvn_d");
        assertNotNull("Initial sibling .stvn_d must be created", siblingDefs);
        String v1Exported = new String(siblingDefs.contentsToByteArray(), StandardCharsets.UTF_8);
        assertTrue(v1Exported.contains(":OrderCount"));

        // Update source buffer to V2
        String modSourceV2 = modSourceV1.replace(":OrderCount", ":UpdatedOrderCount");
        Document modDoc = FileDocumentManager.getInstance().getDocument(modFile.getVirtualFile());
        assertNotNull(modDoc);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            modDoc.setText(modSourceV2);
            PsiDocumentManager.getInstance(getProject()).commitDocument(modDoc);
        });

        // ACT-02a: Cancel sibling overwrite -> existing v1Exported must remain byte-for-byte unchanged
        StvnVopProjectionService.setOverwriteConfirmationOverride(Boolean.FALSE);
        exportPretty.actionPerformed(TestActionEvent.createTestEvent(exportPretty, ctx));
        assertEquals("Cancelling overwrite prompt must leave existing sibling file untouched",
            v1Exported,
            new String(siblingDefs.contentsToByteArray(), StandardCharsets.UTF_8));

        // ACT-02b: Confirm sibling overwrite -> overwrites inside undoable WriteCommandAction and supports Ctrl+Z Undo
        FileEditor[] siblingEditors = FileEditorManager.getInstance(getProject()).openFile(siblingDefs, true);
        assertTrue(siblingEditors.length > 0);
        Document siblingDoc = FileDocumentManager.getInstance().getDocument(siblingDefs);
        assertNotNull(siblingDoc);

        StvnVopProjectionService.setOverwriteConfirmationOverride(Boolean.TRUE);
        exportPretty.actionPerformed(TestActionEvent.createTestEvent(exportPretty, ctx));
        assertTrue("Confirmed overwrite must update sibling file to V2",
            siblingDoc.getText().contains(":UpdatedOrderCount"));

        assertTrue("Sibling overwrite must register an undoable command on the sibling editor",
            UndoManager.getInstance(getProject()).isUndoAvailable(siblingEditors[0]));
        UndoManager.getInstance(getProject()).undo(siblingEditors[0]);
        assertEquals("Ctrl+Z Undo must restore V1 sibling schema content", v1Exported, siblingDoc.getText());
    }

    /**
     * PPII-03 (ACT-03): Verifies StvnCanonicalizationGuard traces '#CONST' references inside
     * MetadataValue constraint facets across ':defs' and ':type' without false unreferenced-definition flags.
     */
    public void testCanonicalizationGuardRecognizesMetadataValueConstantReferences() {
        PsiFile referencedInDefsFacet = myFixture.configureByText(
            "guard_facet_ref.stvn",
            """
            {
              :defs {
                #MAX_PORT :Int 65535
                :BoundedPort { #unsigned #size 16 #maxExcl #MAX_PORT } :Int
              }
              :type :BoundedPort
              :body 8080
            }
            """
        );
        assertFalse(
            "#MAX_PORT referenced in :BoundedPort MetadataValue must NOT be flagged as unreferenced",
            StvnCanonicalizationGuard.hasUnreferencedDefinitions(referencedInDefsFacet)
        );

        PsiFile referencedInRootTypeFacet = myFixture.configureByText(
            "guard_root_type_facet.stvn",
            """
            {
              :defs {
                #MIN_SCORE :Int 10
              }
              :type { #minIncl #MIN_SCORE } :Int
              :body 25
            }
            """
        );
        assertFalse(
            "#MIN_SCORE referenced in root :type MetadataValue must NOT be flagged as unreferenced",
            StvnCanonicalizationGuard.hasUnreferencedDefinitions(referencedInRootTypeFacet)
        );

        PsiFile unreferencedDeadConst = myFixture.configureByText(
            "guard_dead_const.stvn",
            """
            {
              :defs {
                #MAX_PORT :Int 65535
                #DEAD_UNUSED :Int 42
                :BoundedPort { #unsigned #size 16 #maxExcl #MAX_PORT } :Int
              }
              :type :BoundedPort
              :body 8080
            }
            """
        );
        assertTrue(
            "Truly unreferenced #DEAD_UNUSED constant must still be detected",
            StvnCanonicalizationGuard.hasUnreferencedDefinitions(unreferencedDeadConst)
        );
    }

    /**
     * PPII-03 (ACT-04): Verifies PublishSchemaAction flattens workspace '.stvn_di' modules, never
     * splices synthetic '#domain "default"', and blocks invalid schemas locally via StvnCompiler
     * before network dispatch.
     */
    public void testPublishSchemaActionValidatesWithoutDefaultDomainSplicingAndBlocksInvalidSchemas() {
        List<Notification> captured = new ArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override
            public void notify(@NotNull Notification notification) {
                captured.add(notification);
            }
        });

        // 1. Valid 1-stem .stvn_d without #domain: must pass validation WITHOUT splicing '#domain "default"'
        PsiFile validSingleStem = myFixture.addFileToProject(
            "publish_dir/orders.stvn_d",
            """
            {
              :meta {
                #name "orders"
                #kind #DEFS
              }
              :defs {
                :OrderId { #unsigned #size 64 } :Int
              }
            }
            """
        );
        String validatedSingleStem = PublishSchemaAction.prepareValidatedSchemaForPublish(
            getProject(),
            validSingleStem.getVirtualFile()
        );
        assertNotNull("Valid 1-stem .stvn_d must pass pre-flight validation", validatedSingleStem);
        assertFalse("PublishSchemaAction must never splice synthetic '#domain \"default\"'",
            validatedSingleStem.contains("\"default\""));

        // 2. Valid modular .stvn_di with cross-file :include + :package + :use
        myFixture.addFileToProject(
            "publish_mod/base_types.stvn_d",
            """
            {
              :meta {
                #name "base_types"
                #kind #DEFS
              }
              :defs {
                :package :com/acme/base {
                  :TenantId { #minSize 2 #maxSize 32 } :String
                }
              }
            }
            """
        );
        PsiFile validModular = myFixture.addFileToProject(
            "publish_mod/gateway_schema.stvn_di",
            """
            {
              :meta {
                #name "gateway_schema"
                #kind #DEFS_INCLUDE
              }
              :defs {
                :include [ "base_types.stvn_d" ]
                :use [ :com/acme/base { #strip } ]
                :GatewayEnvelope :Tuple( :TenantId :Boolean )
              }
            }
            """
        );
        String flattenedForPublish = PublishSchemaAction.prepareValidatedSchemaForPublish(
            getProject(),
            validModular.getVirtualFile()
        );
        assertNotNull("Valid modular .stvn_di must flatten and validate cleanly", flattenedForPublish);
        assertTrue(flattenedForPublish.contains("#kind #DEFS"));
        assertFalse(flattenedForPublish.contains("\"default\""));

        // 3. Invalid .stvn_d missing :meta -> blocked locally with compilation error notification (no #domain "default" splice!)
        captured.clear();
        PsiFile missingMetaSchema = myFixture.addFileToProject(
            "publish_invalid/missing_meta.stvn_d",
            """
            {
              :defs {
                :BrokenId :Int
              }
            }
            """
        );
        String rejected = PublishSchemaAction.prepareValidatedSchemaForPublish(
            getProject(),
            missingMetaSchema.getVirtualFile()
        );
        assertNull("Schema missing mandatory :meta must be rejected pre-flight", rejected);
        assertEquals("Pre-flight rejection must emit compilation error notification", 1, captured.size());
        assertEquals(NotificationType.ERROR, captured.get(0).getType());
    }

    private void assertCollisionRecorded(String sourceName, String targetName) {
        String err = StvnFileRenameExecutor.getAndClearLastReportedRenameError();
        assertNotNull("Expected collision error message for " + sourceName + " -> " + targetName, err);
        assertTrue(
            "Collision message must name source and target files, got: " + err,
            err.contains("Cannot rename '" + sourceName + "' to '" + targetName + "': a file with that name already exists")
        );
    }

    private void enableAllRegisteredStvnInspections() {
        var tools = new ArrayList<LocalInspectionTool>();
        var seen = new HashSet<String>();
        for (LocalInspectionEP ep : LocalInspectionEP.LOCAL_INSPECTION.getExtensionList()) {
            if ("STVN".equalsIgnoreCase(ep.language) && ep.implementationClass != null && seen.add(ep.implementationClass)) {
                var instance = ep.instantiateTool();
                if (instance instanceof LocalInspectionTool localTool) {
                    tools.add(localTool);
                }
            }
        }
        assertEquals("Expected all 26 registered STVN local inspections", 26, tools.size());
        myFixture.enableInspections(tools.toArray(LocalInspectionTool[]::new));
    }

    private String generateHoverDocAt(PsiFile file, int offset) {
        var provider = new StvnDocumentationProvider();
        PsiElement leaf = file.findElementAt(offset);
        assertNotNull("Expected PSI leaf at offset " + offset, leaf);
        PsiElement custom = provider.getCustomDocumentationElement(myFixture.getEditor(), file, leaf, offset);
        PsiElement target = custom != null ? custom : leaf;
        String doc = provider.generateDoc(target, leaf);
        assertNotNull("Expected non-null hover documentation at offset " + offset, doc);
        return doc;
    }

    private static List<Annotation> computeExternalAnnotations(StvnExternalAnnotator annotator, PsiFile file)
            throws Exception {
        var collected = annotator.collectInformation(file);
        assertNotNull(collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull(result);
        return ApplicationManager.getApplication().executeOnPooledThread(() ->
            ReadAction.compute(() ->
                AnnotationSessionImpl.computeWithSession(file, false, annotator, holder -> {
                    ((AnnotationHolderImpl) holder).applyExternalAnnotatorWithContext(file, result);
                    return (AnnotationHolderImpl) holder;
                })
            )
        ).get(10, TimeUnit.SECONDS);
    }
}
