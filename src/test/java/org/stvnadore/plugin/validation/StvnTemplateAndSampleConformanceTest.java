package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.plugin.StvnColorSettingsPage;
import org.stvnadore.plugin.StvnFileType;
import org.stvnadore.plugin.formatting.StvnLanguageCodeStyleSettingsProvider;
import org.stvnadore.plugin.hints.StvnTypeInlayHintsProvider;
import org.stvnadore.plugin.psi.StvnPsiLiteralEvaluator;
import org.stvnadore.plugin.settings.StvnSettings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Headless IntelliJ Platform conformance test verifying that all {@code intentionDescriptions}
 * templates, {@link StvnColorSettingsPage} demo text, {@link StvnLanguageCodeStyleSettingsProvider}
 * samples, {@link StvnTypeInlayHintsProvider} preview text, and {@link StvnSettings} bootstrap
 * templates parse with zero {@link PsiErrorElement} nodes and zero {@link StvnCompiler} syntax
 * errors, and verifying {@code StvnCreateFencedStringIntention} end-to-end on simple strings.
 */
@NullMarked
public final class StvnTemplateAndSampleConformanceTest extends BasePlatformTestCase {

    private static final String CREATE_FENCED_INTENTION_TEXT = "Convert to Fenced String Block";

    private static final Set<String> SYNTAX_AND_FENCE_ERROR_CODES = Set.of(
        "E001", "E002", "E003", "E004", "E006", "E007", "E010", "E025"
    );

    public void testAllIntentionTemplatesParseWithZeroPsiAndSyntaxErrors() throws IOException {
        Path root = findProjectRoot().toPath();
        Path intentionRoot = root.resolve("src/main/resources/intentionDescriptions");
        assertTrue("Intention descriptions root must exist: " + intentionRoot, Files.isDirectory(intentionRoot));

        List<Path> templateFiles;
        try (Stream<Path> walk = Files.walk(intentionRoot)) {
            templateFiles = walk
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".stvn.template"))
                .sorted()
                .toList();
        }
        assertEquals("Expected 16 .stvn.template files across 8 intentions", 16, templateFiles.size());

        for (Path templatePath : templateFiles) {
            String relPath = root.relativize(templatePath).toString().replace('\\', '/');
            String raw = Files.readString(templatePath, StandardCharsets.UTF_8);
            if (relPath.endsWith("StvnBootstrapDocumentIntentionAction/before.stvn.template")) {
                assertTrue("Bootstrap before.stvn.template models an empty file", raw.isBlank());
                continue;
            }
            assertFalse(relPath + " must not be blank", raw.isBlank());

            String clean = raw.replace("<spot>", "").replace("</spot>", "");
            PsiFile psiFile = PsiFileFactory.getInstance(getProject())
                .createFileFromText("template_check.stvn", StvnFileType.Payload.INSTANCE, clean);
            var psiErrors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement.class);
            assertTrue(relPath + " contains PsiErrorElements: " + psiErrors, psiErrors.isEmpty());

            String unitName = clean.contains(":type") ? "sample.domain.stvn" : "sample.domain.stvn_d";
            var compileResult = StvnCompiler.compileToResult(clean, unitName);
            var syntaxErrors = compileResult.diagnostics().stream()
                .filter(d -> SYNTAX_AND_FENCE_ERROR_CODES.contains(d.canonicalCode()))
                .toList();
            assertTrue(
                relPath + " contains StvnCompiler syntax or fence errors: " + syntaxErrors,
                syntaxErrors.isEmpty()
            );
        }
    }

    public void testColorSchemeCodeStyleInlayPreviewAndBootstrapTemplatesCompileCleanly() throws IOException {
        // 1. StvnColorSettingsPage demo text (strip XML highlight markers)
        String rawDemo = new StvnColorSettingsPage().getDemoText();
        String strippedDemo = rawDemo.replaceAll("</?[a-z_]+>", "");
        assertZeroPsiAndCompilerErrors("StvnColorSettingsPage.getDemoText()", strippedDemo, "service_gateway.network.stvn");

        // 2. StvnLanguageCodeStyleSettingsProvider samples
        var codeStyleProvider = new StvnLanguageCodeStyleSettingsProvider();
        String indentSample = codeStyleProvider.getCodeSample(LanguageCodeStyleSettingsProvider.SettingsType.INDENT_SETTINGS);
        String spacesSample = codeStyleProvider.getCodeSample(LanguageCodeStyleSettingsProvider.SettingsType.SPACING_SETTINGS);
        String blankLinesSample = codeStyleProvider.getCodeSample(LanguageCodeStyleSettingsProvider.SettingsType.BLANK_LINES_SETTINGS);
        assertNotNull(indentSample);
        assertNotNull(spacesSample);
        assertNotNull(blankLinesSample);
        assertZeroPsiAndCompilerErrors("SAMPLE_INDENT", indentSample, "indent_showcase.layout.stvn");
        assertZeroPsiAndCompilerErrors("SAMPLE_SPACES", spacesSample, "spaces_showcase.layout.stvn");
        assertZeroPsiAndCompilerErrors("SAMPLE_BLANK_LINES", blankLinesSample, "blank_lines_showcase.layout.stvn");

        // 3. StvnTypeInlayHintsProvider preview text
        String inlayPreview = new StvnTypeInlayHintsProvider().getPreviewText();
        assertNotNull(inlayPreview);
        assertZeroPsiAndCompilerErrors("StvnTypeInlayHintsProvider.getPreviewText()", inlayPreview, "inlay_preview.stvn");

        // 4. StvnSettings 4 default bootstrap templates (hermetic + modular include units)
        Path tempDir = Files.createTempDirectory("stvn_bootstrap_template_verify");
        try {
            String stubModelsDi = """
                {
                  :meta {
                    #name "models"
                    #domain "domain"
                    #kind #DEFS_INCLUDE
                  }
                  :defs {
                    :SharedModelId :Int
                  }
                }
                """;
            String stubPrimitivesD = """
                {
                  :meta {
                    #name "primitives"
                    #domain "common"
                    #kind #DEFS
                  }
                  :defs {
                    :CommonFlag :Boolean
                  }
                }
                """;
            Files.writeString(tempDir.resolve("models.domain.stvn_di"), stubModelsDi, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("primitives.common.stvn_d"), stubPrimitivesD, StandardCharsets.UTF_8);

            assertZeroPsiAndCompilerErrors(
                "DEFAULT_TEMPLATE_STVN",
                StvnSettings.State.DEFAULT_TEMPLATE_STVN,
                tempDir.resolve("payload.domain.stvn").toString()
            );
            assertZeroPsiAndCompilerErrors(
                "DEFAULT_TEMPLATE_STVN_I",
                StvnSettings.State.DEFAULT_TEMPLATE_STVN_I,
                tempDir.resolve("payload.domain.stvn_i").toString()
            );
            assertZeroPsiAndCompilerErrors(
                "DEFAULT_TEMPLATE_STVN_D",
                StvnSettings.State.DEFAULT_TEMPLATE_STVN_D,
                tempDir.resolve("schema.domain.stvn_d").toString()
            );
            assertZeroPsiAndCompilerErrors(
                "DEFAULT_TEMPLATE_STVN_DI",
                StvnSettings.State.DEFAULT_TEMPLATE_STVN_DI,
                tempDir.resolve("module.domain.stvn_di").toString()
            );
        } finally {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    public void testCreateFencedStringIntentionConvertsSimpleStringsWithRoundTripSoundness() {
        String source = """
            {
              :meta {
                #name   "fenced_simple_conversion"
                #domain "core"
                #kind   #BODY
              }
              :defs {
                :QueryText     { #minSize 1 #maxSize 512 } :String
                :VerbatimText  { #minSize 1 #maxSize 512 #preserveIndent #TRUE } :String
                :AuditInstant  { #offset } :DateTime
                #ROUTE_REGEX   :QueryText "^/api/v[0-9]+/status$"
                #COLLIDE_NOTE  :QueryText "prefix [RAW]\\"\\"\\" suffix"
                #LEADING_TRIM  :QueryText "  leading_spaces_would_strip"
                #CR_REJECTED   :QueryText "line1\\r\\nline2"
                :Envelope      :Tuple(:QueryText :QueryText :VerbatimText :AuditInstant)
              }
              :type :Envelope
              :body (
                "SELECT id, status FROM manifold_events WHERE active = 1;"
                "line1\\nline2\\t\\"quoted\\"\\\\path\\u0041"
                "  verbatim_leading\\n\\tsecond_line"
                "2026-11-24T18:00:00Z"
              )
            }
            """;

        PsiFile psiFile = myFixture.configureByText("fenced_simple_conversion.core.stvn", source);

        // 1. Escape-free simple SQL string: verify IntentionPreviewInfo.DIFF + conversion to """[RAW]...[RAW]"""
        int sqlOffset = psiFile.getText().indexOf("SELECT id, status") + 4;
        myFixture.getEditor().getCaretModel().moveToOffset(sqlOffset);
        List<IntentionAction> sqlActions = myFixture.filterAvailableIntentions(CREATE_FENCED_INTENTION_TEXT);
        assertEquals("Expected 'Convert to Fenced String Block' on escape-free simple string", 1, sqlActions.size());
        assertEquals(
            "Convert string literal to fenced string block",
            sqlActions.get(0).getFamilyName()
        );
        String preview = myFixture.getIntentionPreviewText(sqlActions.get(0));
        assertNotNull("Expected non-null IntentionPreviewInfo.DIFF target text", preview);
        assertTrue("Preview must contain [RAW] opening fence:\n" + preview, preview.contains("\"\"\"[RAW]"));
        assertTrue("Preview must not mutate live document",
            myFixture.getEditor().getDocument().getText().contains("\"SELECT id, status"));

        myFixture.launchAction(sqlActions.get(0));
        assertTrue(
            "Converted SQL string must emit canonical [RAW] block:\n" + myFixture.getFile().getText(),
            myFixture.getFile().getText().contains(
                "\"\"\"[RAW]\n      SELECT id, status FROM manifold_events WHERE active = 1;[RAW]\"\"\""
            )
        );

        // 2. Escaped simple string with \n, \t, \", \\, and \u0041 -> exact round-trip decoding parity
        String expectedEscapedDecoded = "line1\nline2\t\"quoted\"\\pathA";
        int escapedOffset = myFixture.getFile().getText().indexOf("line1\\nline2") + 3;
        myFixture.getEditor().getCaretModel().moveToOffset(escapedOffset);
        IntentionAction escapedAction = myFixture.findSingleIntention(CREATE_FENCED_INTENTION_TEXT);
        myFixture.launchAction(escapedAction);
        assertTrue(
            myFixture.getFile().getText().contains(
                "\"\"\"[RAW]\n      line1\n      line2\t\"quoted\"\\pathA[RAW]\"\"\""
            )
        );
        assertEquals(
            expectedEscapedDecoded,
            StvnPsiLiteralEvaluator.decodeStringLiteral(
                "\"\"\"[RAW]\n      line1\n      line2\t\"quoted\"\\pathA[RAW]\"\"\"",
                false
            ).orElseThrow()
        );

        // 3. #preserveIndent #TRUE retains leading spaces with 0-margin body
        int verbatimOffset = myFixture.getFile().getText().indexOf("verbatim_leading") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(verbatimOffset);
        IntentionAction verbatimAction = myFixture.findSingleIntention(CREATE_FENCED_INTENTION_TEXT);
        myFixture.launchAction(verbatimAction);
        assertTrue(
            myFixture.getFile().getText().contains(
                "\"\"\"[RAW]\n  verbatim_leading\n\tsecond_line[RAW]\"\"\""
            )
        );

        // 4. *REGEX constant selects [REGEX] even without escape triggers
        int regexOffset = myFixture.getFile().getText().indexOf("/api/v[0-9]+") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(regexOffset);
        IntentionAction regexAction = myFixture.findSingleIntention(CREATE_FENCED_INTENTION_TEXT);
        myFixture.launchAction(regexAction);
        assertTrue(
            myFixture.getFile().getText().contains(
                "#ROUTE_REGEX   :QueryText \"\"\"[REGEX]\n      ^/api/v[0-9]+/status$[REGEX]\"\"\""
            )
        );

        // 5. Payload containing [RAW]""" escalates automatically to [RAW_1]
        int collideOffset = myFixture.getFile().getText().indexOf("prefix [RAW]") + 2;
        myFixture.getEditor().getCaretModel().moveToOffset(collideOffset);
        IntentionAction collideAction = myFixture.findSingleIntention(CREATE_FENCED_INTENTION_TEXT);
        myFixture.launchAction(collideAction);
        assertTrue(
            myFixture.getFile().getText().contains(
                "\"\"\"[RAW_1]\n      prefix [RAW]\"\"\" suffix[RAW_1]\"\"\""
            )
        );

        // 6. Suppression guards: :meta, :DateTime, #preserveIndent #FALSE leading-space loss, and \r control char
        String finalText = myFixture.getFile().getText();
        int[] suppressedOffsets = new int[]{
            finalText.indexOf("\"fenced_simple_conversion\"") + 2,
            finalText.indexOf("\"2026-11-24T18:00:00Z\"") + 3,
            finalText.indexOf("leading_spaces_would_strip") + 2,
            finalText.indexOf("line1\\r\\nline2") + 2
        };
        for (int suppressedOffset : suppressedOffsets) {
            myFixture.getEditor().getCaretModel().moveToOffset(suppressedOffset);
            assertTrue(
                "Intention must be suppressed at offset " + suppressedOffset,
                myFixture.filterAvailableIntentions(CREATE_FENCED_INTENTION_TEXT).isEmpty()
            );
        }

        // Verify converted document compiles with zero errors
        var compileRes = StvnCompiler.compileToResult(finalText, "fenced_simple_conversion.core.stvn");
        assertFalse(
            "Converted document must compile with zero errors: " + compileRes.diagnostics(),
            compileRes.hasErrors()
        );
    }

    private void assertZeroPsiAndCompilerErrors(String label, String content, String filePath) {
        PsiFile psiFile = PsiFileFactory.getInstance(getProject())
            .createFileFromText("verify_sample.stvn", StvnFileType.Payload.INSTANCE, content);
        var psiErrors = PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement.class);
        assertTrue(label + " must contain zero PsiErrorElements: " + psiErrors, psiErrors.isEmpty());

        var result = StvnCompiler.compileToResult(content, filePath);
        assertFalse(
            label + " must compile with zero StvnCompiler errors, got: " + result.diagnostics(),
            result.hasErrors()
        );
    }

    private static File findProjectRoot() {
        File current = new File(".").getAbsoluteFile();
        while (current != null) {
            if (new File(current, "build.gradle.kts").exists() && new File(current, "src/main/resources").exists()) {
                return current;
            }
            current = current.getParentFile();
        }
        return new File(".");
    }
}
