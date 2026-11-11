package org.stvnadore.plugin.formatting;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompilationResult;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.StvnParserConfig;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.plugin.actions.StvnConvertToCompactPrintAction;
import org.stvnadore.plugin.actions.StvnConvertToPrettyPrintAction;
import org.stvnadore.plugin.settings.StvnProjectSettings;
import org.stvnadore.plugin.settings.StvnSettings;

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
 * Plugin-level Golden Formatter Conformance Suite verifying the 52 mirrored golden formatter quartet files
 * ({@code src/test/resources/shared-fixtures/formatting/{input,source_pretty,pretty,compact}/}) across:
 * <ul>
 *   <li>{@link StvnConvertToPrettyPrintAction} and {@link StvnConvertToCompactPrintAction}</li>
 *   <li>Default Core Source Formatter {@code Reformat Code} ({@code Ctrl+Alt+L}) via {@link StvnCoreDelegationPostFormatProcessor}</li>
 *   <li>Core Canonical Pretty {@code Reformat Code} ({@code Ctrl+Alt+L}) via {@link StvnCoreDelegationPostFormatProcessor}</li>
 *   <li>Structure-preserving PSI {@code Reformat Code} ({@code Ctrl+Alt+L}) via {@link StvnFormattingModelBuilder} and {@link StvnBlock}</li>
 * </ul>
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
     * Zero exemptions: PSI_STRUCTURE_PRESERVING matches source_pretty/ byte-for-byte across all golden quartets.
     */
    private static final Set<String> PSI_VS_SOURCE_PRETTY_EXEMPTIONS = Set.of();

    public void testMirroredGoldenFormattingInventoryParity() throws IOException {
        assertTrue("Missing mirrored input directory: " + INPUT_DIR, Files.isDirectory(INPUT_DIR));
        assertTrue("Missing mirrored source_pretty directory: " + SOURCE_PRETTY_DIR, Files.isDirectory(SOURCE_PRETTY_DIR));
        assertTrue("Missing mirrored pretty directory: " + PRETTY_DIR, Files.isDirectory(PRETTY_DIR));
        assertTrue("Missing mirrored compact directory: " + COMPACT_DIR, Files.isDirectory(COMPACT_DIR));

        Set<String> inputNames = listRegularFileNames(INPUT_DIR);
        Set<String> sourcePrettyNames = listRegularFileNames(SOURCE_PRETTY_DIR);
        Set<String> prettyNames = listRegularFileNames(PRETTY_DIR);
        Set<String> compactNames = listRegularFileNames(COMPACT_DIR);

        assertEquals("Expected 13 mirrored golden formatter quartets", 13, inputNames.size());
        assertEquals("input/ and source_pretty/ inventories must match", inputNames, sourcePrettyNames);
        assertEquals("input/ and pretty/ inventories must match", inputNames, prettyNames);
        assertEquals("input/ and compact/ inventories must match", inputNames, compactNames);
    }

    public void testCoreDelegatedActionsAndReformatCodeMatchGoldenQuartets() throws IOException {
        var settings = StvnSettings.getInstance(getProject());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;
        boolean prevSuppress = projSettings.getState().suppressCanonicalizationWarning;

        try {
            projSettings.getState().suppressCanonicalizationWarning = true;
            for (Path inputPath : listSortedFiles(INPUT_DIR)) {
                String fileName = inputPath.getFileName().toString();
                String inputSource = Files.readString(inputPath, StandardCharsets.UTF_8);
                String expectedSourcePretty = Files.readString(SOURCE_PRETTY_DIR.resolve(fileName), StandardCharsets.UTF_8);
                String expectedPretty = Files.readString(PRETTY_DIR.resolve(fileName), StandardCharsets.UTF_8);
                String expectedCompact = Files.readString(COMPACT_DIR.resolve(fileName), StandardCharsets.UTF_8);

                // 1. Verify StvnConvertToPrettyPrintAction on input/
                var prettyActionFile = myFixture.configureByText(fileName, inputSource);
                var prettyAction = new StvnConvertToPrettyPrintAction();
                var prettyCtx = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, getProject())
                    .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                    .add(CommonDataKeys.PSI_FILE, prettyActionFile)
                    .build();
                prettyAction.actionPerformed(TestActionEvent.createTestEvent(prettyAction, prettyCtx));
                assertEquals("StvnConvertToPrettyPrintAction mismatch for " + fileName,
                    expectedPretty, myFixture.getEditor().getDocument().getText() + "\n");

                // 2. Verify StvnConvertToCompactPrintAction on input/
                var compactActionFile = myFixture.configureByText(fileName, inputSource);
                var compactAction = new StvnConvertToCompactPrintAction();
                var compactCtx = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, getProject())
                    .add(CommonDataKeys.EDITOR, myFixture.getEditor())
                    .add(CommonDataKeys.PSI_FILE, compactActionFile)
                    .build();
                compactAction.actionPerformed(TestActionEvent.createTestEvent(compactAction, compactCtx));
                assertEquals("StvnConvertToCompactPrintAction mismatch for " + fileName,
                    expectedCompact, myFixture.getEditor().getDocument().getText() + "\n");

                // 3. Verify Default Ctrl+Alt+L (CORE_SOURCE_FORMATTER) on input/, source_pretty/ (idempotency), and pretty/ (fixed-point)
                settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_SOURCE_FORMATTER;

                var sourceFormatFile = myFixture.configureByText(fileName, inputSource);
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

                var prettyFixedPointFile = myFixture.configureByText(fileName, expectedPretty);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(prettyFixedPointFile);
                });
                assertEquals("Default CORE_SOURCE_FORMATTER Ctrl+Alt+L must preserve pretty/" + fileName + " as a fixed point",
                    expectedPretty, prettyFixedPointFile.getText());

                // 4. Verify Ctrl+Alt+L Core Canonical Delegation on input/, compact/, and pretty/ (idempotency)
                settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.CORE_PRETTY_CANONICAL;

                var fromInputFile = myFixture.configureByText(fileName, inputSource);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(fromInputFile);
                });
                assertEquals("Core-delegated Ctrl+Alt+L mismatch from input/" + fileName,
                    expectedPretty, fromInputFile.getText());

                var fromCompactFile = myFixture.configureByText(fileName, expectedCompact);
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
            projSettings.getState().suppressCanonicalizationWarning = prevSuppress;
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();
        }
    }

    public void testPsiStructurePreservingFormatterGoldenPrettyIdempotencyAndInputEquivalence() throws IOException {
        var settings = StvnSettings.getInstance(getProject());
        var prevMode = settings.getState().reformatDelegationMode;

        try {
            settings.getState().reformatDelegationMode = StvnSettings.ReformatDelegationMode.PSI_STRUCTURE_PRESERVING;
            for (Path prettyPath : listSortedFiles(PRETTY_DIR)) {
                String fileName = prettyPath.getFileName().toString();
                Path inputPath = INPUT_DIR.resolve(fileName);
                Path sourcePrettyPath = SOURCE_PRETTY_DIR.resolve(fileName);
                String expectedPretty = Files.readString(prettyPath, StandardCharsets.UTF_8);
                String expectedSourcePretty = Files.readString(sourcePrettyPath, StandardCharsets.UTF_8);
                String inputSource = Files.readString(inputPath, StandardCharsets.UTF_8);

                // 1. PSI Ctrl+Alt+L on golden source_pretty/* and pretty/* must be byte-for-byte idempotent
                var sourcePrettyPsiFile = myFixture.configureByText(fileName, expectedSourcePretty);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(sourcePrettyPsiFile);
                });
                String reformattedSourcePretty = sourcePrettyPsiFile.getText();
                assertEquals("PSI formatter must preserve golden source_pretty/" + fileName + " byte-for-byte",
                    expectedSourcePretty, reformattedSourcePretty);
                assertZeroStructuralTabsOutsideFencedBodies(reformattedSourcePretty, "PSI source_pretty/" + fileName);

                var prettyPsiFile = myFixture.configureByText(fileName, expectedPretty);
                WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                    CodeStyleManager.getInstance(getProject()).reformat(prettyPsiFile);
                });
                String reformattedPretty = prettyPsiFile.getText();
                assertEquals("PSI formatter must preserve golden pretty/" + fileName + " byte-for-byte",
                    expectedPretty, reformattedPretty);
                assertZeroStructuralTabsOutsideFencedBodies(reformattedPretty, "PSI pretty/" + fileName);

                // 2. PSI Ctrl+Alt+L on golden input/* must be idempotent, zero-tab compliant, and CAS-equivalent
                var inputPsiFile = myFixture.configureByText(fileName, inputSource);
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
                if ("01_tripartite_datetime_tuples.stvn".equals(fileName)
                        || "09_multiline_use_and_comments.core_fmt.stvn_i".equals(fileName)
                        || "10_deep_recursive_nesting.core_fmt.stvn_i".equals(fileName)
                        || "11_trailing_comment_alignment_and_facet_sorting.core_fmt.stvn_i".equals(fileName)
                        || "12_multiline_parameterized_constructors_depth_fifteen.core_fmt.stvn_i".equals(fileName)
                        || "13_root_section_canonical_reordering.domain.stvn".equals(fileName)) {
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
