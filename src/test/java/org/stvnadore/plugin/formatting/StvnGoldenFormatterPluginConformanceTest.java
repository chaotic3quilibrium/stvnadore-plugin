package org.stvnadore.plugin.formatting;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.core.printer.StvnSourceFormatter;
import org.stvnadore.core.printer.StvnSourceFormatterOptions;
import org.stvnadore.plugin.actions.StvnCopyHermeticPayloadPrettyAction;
import org.stvnadore.plugin.actions.StvnReplaceBufferCompactIrAction;
import org.stvnadore.plugin.actions.StvnReplaceBufferPrettyIrAction;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.validation.StvnReorderMetadataFacetsQuickFix;
import org.stvnadore.psi.MetadataMap;

import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Plugin-level Golden Formatter Conformance Suite verifying all 23 mirrored golden formatter quartets
 * ({@code 92} files across {@code input/}, {@code source_pretty/}, {@code pretty/}, and {@code compact/}),
 * all 4 {@code (schemaNestedWrapPolicy, payloadNestedWrapPolicy)} combinations across both
 * {@code CORE_SOURCE_FORMATTER} and {@code PSI_STRUCTURE_PRESERVING}, and 0-character-diff fixed points
 * on Probes 1, 2, and 3.
 */
@NullMarked
public final class StvnGoldenFormatterPluginConformanceTest extends BasePlatformTestCase {

    private static final Path FORMATTING_ROOT = Paths.get("src", "test", "resources", "shared-fixtures", "formatting");
    private static final Path INPUT_DIR = FORMATTING_ROOT.resolve("input");
    private static final Path SOURCE_PRETTY_DIR = FORMATTING_ROOT.resolve("source_pretty");
    private static final Path PRETTY_DIR = FORMATTING_ROOT.resolve("pretty");
    private static final Path COMPACT_DIR = FORMATTING_ROOT.resolve("compact");

    private static final Pattern FENCED_STRING_PATTERN = Pattern.compile(
        "\"\"\"\\[([A-Za-z0-9_-]+)]\\n[\\s\\S]*?\\[\\1]\"\"\""
    );

    /**
     * Zero exemptions: PSI_STRUCTURE_PRESERVING matches source_pretty/ byte-for-byte across all 23 golden quartets.
     */
    private static final Set<String> PSI_VS_SOURCE_PRETTY_EXEMPTIONS = Set.of();

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyFileToProject(
            "formatting/input/08_schema_defs_module.core_fmt.stvn_d",
            "formatting/input/08_schema_defs_module.core_fmt.stvn_d"
        );
        myFixture.copyFileToProject(
            "syntax/valid/modules/network_primitives.stvn_d",
            "syntax/valid/modules/network_primitives.stvn_d"
        );
    }

    private com.intellij.psi.PsiFile configureFormattingBuffer(String fileName, String content) {
        if (!"20_multiline_pure_include_and_comments.core_fmt.stvn_i".equals(fileName)) {
            return myFixture.configureByText(fileName, content);
        }
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        String relPath = "formatting/input/" + fileName;
        var existingVf = myFixture.findFileInTempDir(relPath);
        if (existingVf != null) {
            var doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(existingVf);
            if (doc != null) {
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    doc.setText(content);
                    com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
                });
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(doc);
            }
            myFixture.configureFromExistingVirtualFile(existingVf);
            return myFixture.getFile();
        }
        var psi = myFixture.addFileToProject(relPath, content);
        myFixture.configureFromExistingVirtualFile(psi.getVirtualFile());
        return psi;
    }

    public void testMirroredGoldenFormattingInventoryParity() throws IOException {
        assertTrue("Missing mirrored input directory: " + INPUT_DIR, Files.isDirectory(INPUT_DIR));
        assertTrue("Missing mirrored source_pretty directory: " + SOURCE_PRETTY_DIR, Files.isDirectory(SOURCE_PRETTY_DIR));
        assertTrue("Missing mirrored pretty directory: " + PRETTY_DIR, Files.isDirectory(PRETTY_DIR));
        assertTrue("Missing mirrored compact directory: " + COMPACT_DIR, Files.isDirectory(COMPACT_DIR));

        Set<String> inputNames = listRegularFileNames(INPUT_DIR);
        Set<String> sourcePrettyNames = listRegularFileNames(SOURCE_PRETTY_DIR);
        Set<String> prettyNames = listRegularFileNames(PRETTY_DIR);
        Set<String> compactNames = listRegularFileNames(COMPACT_DIR);

        assertEquals("Expected 24 mirrored golden formatter quartets", 24, inputNames.size());
        assertEquals("input/ and source_pretty/ inventories must match", inputNames, sourcePrettyNames);
        assertEquals("input/ and pretty/ inventories must match", inputNames, prettyNames);
        assertEquals("input/ and compact/ inventories must match", inputNames, compactNames);
    }

    public void testCoreDelegatedActionsAndReformatCodeMatchGoldenQuartets() throws IOException {
        var settings = StvnSettings.getInstance(getProject());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        var prevSchemaWrap = settings.getState().schemaNestedWrapPolicy;
        var prevPayloadWrap = settings.getState().payloadNestedWrapPolicy;
        boolean prevSuppress = projSettings.getState().suppressCanonicalizationWarning;

        try {
            projSettings.getState().suppressCanonicalizationWarning = true;
            settings.getState().schemaNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
            settings.getState().payloadNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;

            for (Path inputPath : listSortedFiles(INPUT_DIR)) {
                String fileName = inputPath.getFileName().toString();
                String inputSource = Files.readString(inputPath, StandardCharsets.UTF_8);
                String expectedSourcePretty = Files.readString(SOURCE_PRETTY_DIR.resolve(fileName), StandardCharsets.UTF_8);
                String expectedPretty = Files.readString(PRETTY_DIR.resolve(fileName), StandardCharsets.UTF_8);
                String expectedCompact = Files.readString(COMPACT_DIR.resolve(fileName), StandardCharsets.UTF_8);

                var prettyActionFile = configureFormattingBuffer(fileName, inputSource);
                var prettyAction = new StvnReplaceBufferPrettyIrAction();
                var prettyCtx = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, getProject())
                    .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                    .add(CommonDataKeys.PSI_FILE, prettyActionFile)
                    .add(CommonDataKeys.VIRTUAL_FILE, prettyActionFile.getVirtualFile())
                    .build();
                prettyAction.actionPerformed(TestActionEvent.createTestEvent(prettyAction, prettyCtx));
                assertEquals("StvnReplaceBufferPrettyIrAction mismatch for " + fileName,
                    expectedPretty, myFixture.getEditor().getDocument().getText() + "\n");

                if ("17_pretty_printer_fenced_string_bias_and_roundtrip_guards.core_fmt.stvn".equals(fileName)
                        || "18_nested_sum_tag_capture_soundness.core_fmt.stvn".equals(fileName)) {
                    var copyPrettyFile = configureFormattingBuffer(fileName, inputSource);
                    var copyPrettyAction = new StvnCopyHermeticPayloadPrettyAction();
                    var copyPrettyCtx = SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, getProject())
                        .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                        .add(CommonDataKeys.PSI_FILE, copyPrettyFile)
                        .add(CommonDataKeys.VIRTUAL_FILE, copyPrettyFile.getVirtualFile())
                        .build();
                    copyPrettyAction.actionPerformed(TestActionEvent.createTestEvent(copyPrettyAction, copyPrettyCtx));
                    String copiedText = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
                    assertNotNull("Clipboard must contain canonical pretty-printed " + fileName, copiedText);
                    assertEquals("StvnCopyHermeticPayloadPrettyAction mismatch for " + fileName,
                        expectedPretty, copiedText.endsWith("\n") ? copiedText : copiedText + "\n");
                }

                if ("14_comment_preserving_meta_and_facet_reordering.core_fmt.stvn_i".equals(fileName)
                        || "19_multiline_multi_target_use.core_fmt.stvn_i".equals(fileName)
                        || "20_multiline_pure_include_and_comments.core_fmt.stvn_i".equals(fileName)) {
                    var copyModFile = configureFormattingBuffer(fileName, inputSource);
                    var copyModAction = new StvnCopyHermeticPayloadPrettyAction();
                    var copyModCtx = SimpleDataContext.builder()
                        .add(CommonDataKeys.PROJECT, getProject())
                        .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                        .add(CommonDataKeys.PSI_FILE, copyModFile)
                        .add(CommonDataKeys.VIRTUAL_FILE, copyModFile.getVirtualFile())
                        .build();
                    copyModAction.actionPerformed(TestActionEvent.createTestEvent(copyModAction, copyModCtx));
                    String copiedHermetic = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
                    assertNotNull("Clipboard must contain hermetic #BODY projection for " + fileName, copiedHermetic);
                    assertEquals("StvnCopyHermeticPayloadPrettyAction must normalize #BODY_INCLUDE to #BODY for " + fileName,
                        expectedPretty.replace("#kind #BODY_INCLUDE", "#kind #BODY"),
                        copiedHermetic.endsWith("\n") ? copiedHermetic : copiedHermetic + "\n");
                }

                var compactActionFile = configureFormattingBuffer(fileName, inputSource);
                var compactAction = new StvnReplaceBufferCompactIrAction();
                var compactCtx = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, getProject())
                    .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                    .add(CommonDataKeys.PSI_FILE, compactActionFile)
                    .add(CommonDataKeys.VIRTUAL_FILE, compactActionFile.getVirtualFile())
                    .build();
                compactAction.actionPerformed(TestActionEvent.createTestEvent(compactAction, compactCtx));
                assertEquals("StvnReplaceBufferCompactIrAction mismatch for " + fileName,
                    expectedCompact, myFixture.getEditor().getDocument().getText() + "\n");

                settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;

                var sourceFormatFile = configureFormattingBuffer(fileName, inputSource);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(sourceFormatFile);
                });
                assertEquals("Default CORE_SOURCE_FORMATTER Ctrl+Alt+L mismatch from input/" + fileName,
                    expectedSourcePretty, sourceFormatFile.getText());

                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(sourceFormatFile);
                });
                assertEquals("Default CORE_SOURCE_FORMATTER Ctrl+Alt+L must be idempotent on source_pretty/" + fileName,
                    expectedSourcePretty, sourceFormatFile.getText());

                var prettyFixedPointFile = configureFormattingBuffer(fileName, expectedPretty);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(prettyFixedPointFile);
                });
                assertEquals("Default CORE_SOURCE_FORMATTER Ctrl+Alt+L must preserve pretty/" + fileName + " as a fixed point",
                    expectedPretty, prettyFixedPointFile.getText());

                settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_PRETTY_CANONICAL;

                var fromInputFile = configureFormattingBuffer(fileName, inputSource);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(fromInputFile);
                });
                assertEquals("Core-delegated Ctrl+Alt+L mismatch from input/" + fileName,
                    expectedPretty, fromInputFile.getText());

                var fromCompactFile = configureFormattingBuffer(fileName, expectedCompact);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(fromCompactFile);
                });
                assertEquals("Core-delegated Ctrl+Alt+L mismatch from compact/" + fileName,
                    expectedPretty, fromCompactFile.getText());

                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(fromCompactFile);
                });
                assertEquals("Core-delegated Ctrl+Alt+L must be idempotent on pretty/" + fileName,
                    expectedPretty, fromCompactFile.getText());
            }
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
            settings.getState().schemaNestedWrapPolicy = prevSchemaWrap;
            settings.getState().payloadNestedWrapPolicy = prevPayloadWrap;
            projSettings.getState().suppressCanonicalizationWarning = prevSuppress;
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        }
    }

    public void testPsiStructurePreservingFormatterGoldenPrettyIdempotencyAndInputEquivalence() throws IOException {
        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        var prevSchemaWrap = settings.getState().schemaNestedWrapPolicy;
        var prevPayloadWrap = settings.getState().payloadNestedWrapPolicy;

        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            settings.getState().schemaNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
            settings.getState().payloadNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;

            for (Path prettyPath : listSortedFiles(PRETTY_DIR)) {
                String fileName = prettyPath.getFileName().toString();
                Path inputPath = INPUT_DIR.resolve(fileName);
                Path sourcePrettyPath = SOURCE_PRETTY_DIR.resolve(fileName);
                String expectedPretty = Files.readString(prettyPath, StandardCharsets.UTF_8);
                String expectedSourcePretty = Files.readString(sourcePrettyPath, StandardCharsets.UTF_8);
                String inputSource = Files.readString(inputPath, StandardCharsets.UTF_8);

                var sourcePrettyPsiFile = configureFormattingBuffer(fileName, expectedSourcePretty);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(sourcePrettyPsiFile);
                });
                String reformattedSourcePretty = sourcePrettyPsiFile.getText();
                assertEquals("PSI formatter must preserve golden source_pretty/" + fileName + " byte-for-byte",
                    expectedSourcePretty, reformattedSourcePretty);
                assertZeroStructuralTabsOutsideFencedBodies(reformattedSourcePretty, "PSI source_pretty/" + fileName);

                var prettyPsiFile = configureFormattingBuffer(fileName, expectedPretty);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(prettyPsiFile);
                });
                String reformattedPretty = prettyPsiFile.getText();
                assertEquals("PSI formatter must preserve golden pretty/" + fileName + " byte-for-byte",
                    expectedPretty, reformattedPretty);
                assertZeroStructuralTabsOutsideFencedBodies(reformattedPretty, "PSI pretty/" + fileName);

                var inputPsiFile = configureFormattingBuffer(fileName, inputSource);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(inputPsiFile);
                });
                String passOne = inputPsiFile.getText();
                assertZeroStructuralTabsOutsideFencedBodies(passOne, "PSI input/" + fileName);

                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(inputPsiFile);
                });
                String passTwo = inputPsiFile.getText();
                assertEquals("PSI formatter must be strictly idempotent on input/" + fileName, passOne, passTwo);

                assertTrue("PSI_VS_SOURCE_PRETTY_EXEMPTIONS must be empty", PSI_VS_SOURCE_PRETTY_EXEMPTIONS.isEmpty());
                if (!PSI_VS_SOURCE_PRETTY_EXEMPTIONS.contains(fileName)) {
                    assertEquals("PSI formatter must match golden source_pretty/" + fileName + " byte-for-byte",
                        expectedSourcePretty, passTwo);
                }

                StvnDocument expectedDoc = compileClean(expectedPretty, prettyPath);
                StvnDocument psiFormattedDoc = compileClean(passTwo, inputPath);
                assertEquals("Canonical preimage must match golden pretty after PSI reformat of input/" + fileName,
                    StvnCompiler.toCanonicalString(expectedDoc),
                    StvnCompiler.toCanonicalString(psiFormattedDoc));
            }
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
            settings.getState().schemaNestedWrapPolicy = prevSchemaWrap;
            settings.getState().payloadNestedWrapPolicy = prevPayloadWrap;
        }
    }

    public void testAll23QuartetsAcrossAll4WrapPolicyCombinationsDualEngineParity() throws IOException {
        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        var prevSchemaWrap = settings.getState().schemaNestedWrapPolicy;
        var prevPayloadWrap = settings.getState().payloadNestedWrapPolicy;

        try {
            List<Path> inputPaths = listSortedFiles(INPUT_DIR);
            assertEquals("Expected 24 golden input files", 24, inputPaths.size());

            for (NestedContainerWrapPolicy schemaPolicy : NestedContainerWrapPolicy.values()) {
                for (NestedContainerWrapPolicy payloadPolicy : NestedContainerWrapPolicy.values()) {
                    settings.getState().schemaNestedWrapPolicy = schemaPolicy;
                    settings.getState().payloadNestedWrapPolicy = payloadPolicy;
                    StvnSourceFormatterOptions opts = settings.toCoreFormatterOptions();

                    for (Path inputPath : inputPaths) {
                        String fileName = inputPath.getFileName().toString();
                        String inputSource = Files.readString(inputPath, StandardCharsets.UTF_8);
                        boolean trailingNl = inputSource.endsWith("\n");
                        String expected = StvnSourceFormatter.format(inputSource, opts) + (trailingNl ? "\n" : "");
                        String label = fileName + " [" + schemaPolicy + ", " + payloadPolicy + "]";

                        settings.getState().reformatDelegationMode =
                            StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
                        var coreFile = configureFormattingBuffer(fileName, inputSource);
                        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                            CodeStyleManager.getInstance(getProject()).reformat(coreFile);
                        });
                        assertEquals("CORE_SOURCE_FORMATTER mismatch for " + label, expected, coreFile.getText());
                        assertNull(
                            "RAW_PRE_FORMAT_SNAPSHOT_KEY must be cleared in finally after CORE_SOURCE_FORMATTER",
                            coreFile.getUserData(StvnMetadataOrderPreFormatProcessor.RAW_PRE_FORMAT_SNAPSHOT_KEY)
                        );

                        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                            CodeStyleManager.getInstance(getProject()).reformat(coreFile);
                        });
                        assertEquals("CORE_SOURCE_FORMATTER Pass 2 idempotency mismatch for " + label,
                            expected, coreFile.getText());

                        settings.getState().reformatDelegationMode =
                            StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
                        var psiFile = configureFormattingBuffer(fileName, inputSource);
                        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
                        });
                        assertEquals("PSI_STRUCTURE_PRESERVING mismatch for " + label, expected, psiFile.getText());

                        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                            CodeStyleManager.getInstance(getProject()).reformat(psiFile);
                        });
                        assertEquals("PSI_STRUCTURE_PRESERVING Pass 2 idempotency mismatch for " + label,
                            expected, psiFile.getText());
                    }
                }
            }
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
            settings.getState().schemaNestedWrapPolicy = prevSchemaWrap;
            settings.getState().payloadNestedWrapPolicy = prevPayloadWrap;
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        }
    }

    public void testProbes1To3ZeroCharacterDiffFixedPointAndQuickFixColumnParity() throws IOException {
        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        var prevSchemaWrap = settings.getState().schemaNestedWrapPolicy;
        var prevPayloadWrap = settings.getState().payloadNestedWrapPolicy;

        List<String> probeNames = List.of(
            "fmt_probe_01_type_alias_alignment.core_test.stvn",
            "fmt_probe_02_constants_meta_use_and_maps.core_test.stvn",
            "fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn"
        );

        try {
            settings.getState().schemaNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;
            settings.getState().payloadNestedWrapPolicy = NestedContainerWrapPolicy.PRESERVE_AUTHORED;

            for (String probeName : probeNames) {
                String canonicalProbe = Files.readString(SOURCE_PRETTY_DIR.resolve(probeName), StandardCharsets.UTF_8);

                settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
                var coreFile = configureFormattingBuffer(probeName, canonicalProbe);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(coreFile);
                });
                assertEquals("CORE_SOURCE_FORMATTER must produce 0 character diffs on canonical " + probeName,
                    canonicalProbe, coreFile.getText());

                settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
                var psiFile = configureFormattingBuffer(probeName, canonicalProbe);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(psiFile);
                });
                assertEquals("PSI_STRUCTURE_PRESERVING must produce 0 character diffs on canonical " + probeName,
                    canonicalProbe, psiFile.getText());
            }

            // Verify standalone Alt+Enter StvnReorderMetadataFacetsQuickFix on Probe 3 preserves target & 1-space peer // columns
            String probe3Input = Files.readString(
                INPUT_DIR.resolve("fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn"),
                StandardCharsets.UTF_8
            );
            var qfFile = myFixture.configureByText("fmt_probe_03_multiline_facets_and_tabular_body.core_test.stvn", probe3Input);
            MetadataMap packetHeaderMeta = PsiTreeUtil.findChildrenOfType(qfFile, MetadataMap.class)
                .stream()
                .findFirst()
                .orElseThrow();
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                StvnReorderMetadataFacetsQuickFix.reorderMetadataMap(getProject(), packetHeaderMeta);
            });
            String expectedReorderedMap = """
                {
                      #minSize         4                  // lower length bound
                      #maxSize         24                 // upper length bound
                      #minIncl         "PKT-0000"         // lexical floor
                      #maxIncl         "PKT-ZZZZ"         // lexical ceiling
                      #preserveIndent  #TRUE              // verbatim margin
                      #regex           "^PKT-[A-Z0-9-]+$" // identifier grammar
                    }""";
            assertEquals("StvnReorderMetadataFacetsQuickFix must preserve target and 1-space peer // columns",
                expectedReorderedMap, packetHeaderMeta.getText());
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
            settings.getState().schemaNestedWrapPolicy = prevSchemaWrap;
            settings.getState().payloadNestedWrapPolicy = prevPayloadWrap;
        }
    }

    public void testPreludeSourceFormattingByteForByteParity() throws IOException {
        Path preludePath = Paths.get("src", "test", "resources", "shared-fixtures", "syntax", "valid", "modules", "prelude.stvn_core.stvn_d");
        assertTrue("Missing mirrored prelude.stvn_core.stvn_d: " + preludePath, Files.isRegularFile(preludePath));
        String preludeSource = Files.readString(preludePath, StandardCharsets.UTF_8);

        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var coreFile = myFixture.configureByText("prelude.stvn_core.stvn_d", preludeSource);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFile);
            });
            assertEquals("CORE_SOURCE_FORMATTER must preserve prelude.stvn_core.stvn_d byte-for-byte",
                preludeSource, coreFile.getText());

            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            var psiFile = myFixture.configureByText("prelude.stvn_core.stvn_d", preludeSource);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(psiFile);
            });
            assertEquals("PSI_STRUCTURE_PRESERVING must preserve prelude.stvn_core.stvn_d byte-for-byte",
                preludeSource, psiFile.getText());
            assertZeroStructuralTabsOutsideFencedBodies(psiFile.getText(), "PSI prelude.stvn_core.stvn_d");
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
        }
    }

    public void testReverseOrderedMutexFacetsNeverReorderInPsiOrCoreSourceFormatter() {
        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        String input = """
            {
              :defs {
                :RevScale { #s #ms } :TimeEpoch
                :RevMode { #audited #offset } :DateTime
              }
              :type :Int
              :body 1
            }
            """;
        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;
            var coreFile = myFixture.configureByText("rev_mutex_core.stvn", input);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(coreFile);
            });
            String coreFormatted = coreFile.getText();
            assertTrue("Core source formatter must preserve '{ #s #ms }' order", coreFormatted.contains("{ #s #ms }"));
            assertTrue("Core source formatter must preserve '{ #audited #offset }' order", coreFormatted.contains("{ #audited #offset }"));

            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            var psiFile = myFixture.configureByText("rev_mutex_psi.stvn", input);
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                CodeStyleManager.getInstance(getProject()).reformat(psiFile);
            });
            assertEquals("PSI_STRUCTURE_PRESERVING and CORE_SOURCE_FORMATTER must match byte-for-byte on reverse mutex pairs",
                coreFormatted, psiFile.getText());
        } finally {
            settings.getState().reformatDelegationMode = prevMode;
        }
    }

    private static StvnDocument compileClean(String source, Path referencePath) {
        StvnCompilationResult<StvnDocument> result = StvnCompiler.compileToResult(
            source,
            referencePath.toString(),
            StvnParserConfig.DEFAULT
        );
        assertFalse("Unexpected compile errors in " + referencePath + ": " + result.diagnostics(), result.hasErrors());
        return result.document().orElseThrow();
    }

    private static List<Path> listSortedFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
    }

    private static Set<String> listRegularFileNames(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());
        }
    }

    private static void assertZeroStructuralTabsOutsideFencedBodies(String text, String label) {
        String stripped = FENCED_STRING_PATTERN.matcher(text).replaceAll("\"\"\"[$1]\\n[$1]\"\"\"");
        assertFalse("Structural tab character ('\\t') detected outside fenced string body in " + label + ":\n" + stripped,
            stripped.contains("\t"));
    }
}
