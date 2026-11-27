package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.intention.IntentionAction;
import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Automated hygiene test suite asserting STVN 2.0.0 vocabulary, Rule {@code STR-04} fence delimiter
 * parity, STE intention family names, and {@code plugin.xml} registration completeness across
 * inspection descriptions, intention descriptions/templates, code style/color demo samples, and
 * plugin Markdown documentation.
 */
@NullMarked
public final class StvnInspectionDescriptionHygieneTest extends TestCase {

    private static final List<Pattern> FORBIDDEN_1X_AND_STYLE_PATTERNS = List.of(
        Pattern.compile(":\\b(?:Optional|Variant|Tagged)\\b"),
        Pattern.compile(":\\b(?:DateTimeOffset|DateTimeZoned|DateTimeAudited)\\b"),
        Pattern.compile(":\\bTimeEpoch(?:S|Ms|Us|Ns|[A-Za-z0-9_]+)\\b"),
        Pattern.compile(":\\bUint\\w*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(":Int\\*"),
        Pattern.compile(":String\\*"),
        Pattern.compile(":Float\\*"),
        Pattern.compile(":\\bInt(?:8|16|32|64|128)\\b"),
        Pattern.compile(":\\bFloat(?:32|64|Exact)\\b"),
        Pattern.compile(":\\bStringFixed\\w*"),
        Pattern.compile(":\\bStringNonEmpty\\w*"),
        Pattern.compile(":\\bString(?:64|4096|8192|16777216)\\b"),
        Pattern.compile(":\\b(?:SeqNonEmpty|SetNonEmpty|MapNonEmpty|MapInv)\\w*"),
        Pattern.compile("\\b#unit\\b"),
        Pattern.compile("\"\"\"->"),
        Pattern.compile("(?i)legacy\\s+arrow\\s+syntax"),
        Pattern.compile("(?i)arrow\\s+delimiter"),
        Pattern.compile("(?i)\\bdeprecat[a-z]*\\b")
    );

    private static final Pattern BLOCK_REVERSED_CLOSING_FENCE_PATTERN = Pattern.compile(
        "(?s)\"\"\"\\[([A-Za-z0-9_.-]+)](?:(?!\\[\\1]\"\"\").)*?\"\"\"\\[\\1]"
    );

    private static final Pattern INLINE_CODE_SPAN_PATTERN = Pattern.compile(
        "(?:`([^`\\r\\n]+)`|<code>([^<\\r\\n]+)</code>)"
    );

    private static final Pattern INLINE_REVERSED_FENCE_PATTERN = Pattern.compile(
        "\"\"\"\\[([A-Za-z0-9_.-]+)].*?\"\"\"\\[\\1]"
    );

    private static final Pattern HTML_PRE_BLOCK_PATTERN = Pattern.compile(
        "(?s)<pre>(.*?)</pre>"
    );

    private static final Pattern STVN_FENCED_CODE_BLOCK_PATTERN = Pattern.compile(
        "(?s)```stvn\\R(.*?)```"
    );

    private static final Set<String> APPROVED_STE_ACTION_VERBS = Set.of(
        "Convert",
        "Generate",
        "Add",
        "Initialize",
        "Expand",
        "Collapse",
        "Escape",
        "Unescape"
    );

    public void testInspectionHtmlDescriptionsHygiene() throws IOException {
        var baseDir = findProjectRoot();
        assertNotNull("Project root must be found", baseDir);

        var htmlDir = baseDir.toPath().resolve("src/main/resources/inspectionDescriptions");
        assertTrue("Inspection descriptions directory must exist: " + htmlDir, Files.exists(htmlDir));

        var violations = new ArrayList<String>();

        try (Stream<Path> stream = Files.walk(htmlDir)) {
            var htmlFiles = stream.filter(p -> p.toString().endsWith(".html")).sorted().toList();
            assertEquals("Expected 27 inspection HTML files", 27, htmlFiles.size());

            for (var file : htmlFiles) {
                var content = Files.readString(file, StandardCharsets.UTF_8);
                scanContentAgainstForbiddenPatterns(file.getFileName().toString(), content, violations);
                scanForBareTripleQuotes(file.getFileName().toString(), content, violations);

                if (file.getFileName().toString().equals("StvnMetadataFacet.html")) {
                    if (content.contains("Discrete Integer (<code>:Int</code>):") && content.contains("#maxIncl")
                        && !content.contains("prohibited")) {
                        violations.add(file.getFileName() + " -> lists #maxIncl on discrete :Int without prohibition");
                    }
                    if (content.toLowerCase().contains("metadata facets are strictly prohibited on constant definitions")) {
                        violations.add(file.getFileName() + " -> falsely prohibits metadata facets on #CONST definitions");
                    }
                }
            }
        }

        assertTrue("Found " + violations.size() + " hygiene violations in inspection HTML descriptions:\n"
            + String.join("\n", violations), violations.isEmpty());
    }

    public void testIntentionDescriptionsSamplesAndPluginDocsHygiene() throws IOException {
        var root = findProjectRoot().toPath();
        var violations = new ArrayList<String>();

        // 1. All 33 intentionDescriptions files (.html and .template)
        var intentionDir = root.resolve("src/main/resources/intentionDescriptions");
        assertTrue("Intention descriptions directory must exist: " + intentionDir, Files.exists(intentionDir));
        try (Stream<Path> walk = Files.walk(intentionDir)) {
            var files = walk.filter(Files::isRegularFile).sorted().toList();
            assertEquals("Expected 33 files across 11 intention description directories", 33, files.size());
            for (Path file : files) {
                String relName = root.relativize(file).toString().replace('\\', '/');
                String content = Files.readString(file, StandardCharsets.UTF_8);
                scanContentAgainstForbiddenPatterns(relName, content, violations);
                scanForBareTripleQuotes(relName, content, violations);
            }
        }

        // 2. Demo & preview Java sample providers
        var sampleJavaFiles = List.of(
            root.resolve("src/main/java/org/stvnadore/plugin/StvnColorSettingsPage.java"),
            root.resolve("src/main/java/org/stvnadore/plugin/formatting/StvnLanguageCodeStyleSettingsProvider.java"),
            root.resolve("src/main/java/org/stvnadore/plugin/hints/StvnTypeInlayHintsProvider.java")
        );
        for (Path javaFile : sampleJavaFiles) {
            String relName = root.relativize(javaFile).toString().replace('\\', '/');
            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
            scanContentAgainstForbiddenPatterns(relName, content, violations);
        }

        // 3. User-facing README.md and STVN_IDE_AUTHORING_GUIDE.md (full-file strict scan)
        var strictMarkdownDocs = List.of(
            root.resolve("README.md"),
            root.resolve("docs/STVN_IDE_AUTHORING_GUIDE.md")
        );
        for (Path mdFile : strictMarkdownDocs) {
            assertTrue("Expected Markdown documentation file: " + mdFile, Files.exists(mdFile));
            String relName = root.relativize(mdFile).toString().replace('\\', '/');
            String content = Files.readString(mdFile, StandardCharsets.UTF_8);
            scanContentAgainstForbiddenPatterns(relName, content, violations);
            scanForBareTripleQuotes(relName, content, violations);
            if (relName.endsWith("STVN_IDE_AUTHORING_GUIDE.md") && content.contains("STVN 1.2")) {
                violations.add(relName + " -> contains stale 'STVN 1.2' version reference");
            }
        }

        // 4. STVN_PLUGIN_SPEC.md: scan all ```stvn blocks unconditionally and non-prohibition lines
        Path specFile = root.resolve("docs/architecture/STVN_PLUGIN_SPEC.md");
        assertTrue("Expected STVN_PLUGIN_SPEC.md: " + specFile, Files.exists(specFile));
        String specContent = Files.readString(specFile, StandardCharsets.UTF_8);
        Matcher blockMatcher = STVN_FENCED_CODE_BLOCK_PATTERN.matcher(specContent);
        int blockIdx = 0;
        while (blockMatcher.find()) {
            blockIdx++;
            String block = blockMatcher.group(1);
            scanContentAgainstForbiddenPatterns("STVN_PLUGIN_SPEC.md#stvnBlock" + blockIdx, block, violations);
            scanForBareTripleQuotes("STVN_PLUGIN_SPEC.md#stvnBlock" + blockIdx, block, violations);
        }
        List<String> specLines = Files.readAllLines(specFile, StandardCharsets.UTF_8);
        for (int i = 0; i < specLines.size(); i++) {
            String line = specLines.get(i);
            if (line.contains("Compound Token Elimination")
                || line.contains("MALFORMED_FENCE")
                || line.contains("StvnFencedStringInspection` governs")
                || line.contains("ConvertToFencedStringIntentionAction")
                || line.contains("untagged `\"\"\"...\"\"\"`")) {
                continue;
            }
            scanContentAgainstForbiddenPatterns("STVN_PLUGIN_SPEC.md:" + (i + 1), line, violations);
            scanForBareTripleQuotes("STVN_PLUGIN_SPEC.md:" + (i + 1), line, violations);
        }

        assertTrue("Found " + violations.size() + " hygiene violations across intentions, samples, and docs:\n"
            + String.join("\n", violations), violations.isEmpty());
    }

    public void testIntentionActionsRegistrationFamilyNameAndReadmeInspectionParity() throws Exception {
        Path root = findProjectRoot().toPath();
        Path pluginXmlPath = root.resolve("src/main/resources/META-INF/plugin.xml");
        assertTrue("plugin.xml must exist: " + pluginXmlPath, Files.exists(pluginXmlPath));

        String rawPluginXml = Files.readString(pluginXmlPath, StandardCharsets.UTF_8);
        assertTrue("plugin.xml must declare a non-empty <description> element",
            rawPluginXml.contains("<description><![CDATA["));

        Document xmlDoc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pluginXmlPath.toFile());

        // 1. Verify all 11 <intentionAction> entries, STE family names, and 1:1 intentionDescriptions/ folders
        NodeList intentionNodes = xmlDoc.getElementsByTagName("intentionAction");
        assertEquals("Expected 11 registered <intentionAction> elements in plugin.xml", 11, intentionNodes.getLength());

        Set<String> seenFamilyNames = new HashSet<>();
        for (int i = 0; i < intentionNodes.getLength(); i++) {
            Element el = (Element) intentionNodes.item(i);
            String className = getChildText(el, "className");
            assertFalse("Intention className must not be blank", className.isBlank());

            Class<?> clazz = Class.forName(className);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            assertTrue(className + " must implement IntentionAction", instance instanceof IntentionAction);
            IntentionAction action = (IntentionAction) instance;

            String familyName = action.getFamilyName();
            assertNotNull(className + " familyName must not be null", familyName);
            assertFalse(className + " familyName must not be blank", familyName.isBlank());
            assertFalse(className + " must not use generic 'STVN' familyName", "STVN".equals(familyName.trim()));
            assertEquals(
                className + " must maintain 1:1 getText() == getFamilyName() parity",
                familyName,
                action.getText()
            );

            String firstWord = familyName.trim().split("\\s+")[0];
            assertTrue(
                className + " familyName '" + familyName + "' must start with an approved STE imperative verb "
                    + APPROVED_STE_ACTION_VERBS,
                APPROVED_STE_ACTION_VERBS.contains(firstWord)
            );
            assertTrue(
                "Duplicate getFamilyName() '" + familyName + "' on " + className,
                seenFamilyNames.add(familyName)
            );

            String configuredDir = getChildText(el, "descriptionDirectoryName");
            String dirName = configuredDir.isBlank() ? clazz.getSimpleName() : configuredDir;
            Path descDir = root.resolve("src/main/resources/intentionDescriptions").resolve(dirName);
            assertTrue("Missing intentionDescriptions directory for " + className + ": " + descDir, Files.isDirectory(descDir));

            Path descHtml = descDir.resolve("description.html");
            Path beforeTemplate = descDir.resolve("before.stvn.template");
            Path afterTemplate = descDir.resolve("after.stvn.template");
            assertTrue("Missing description.html in " + descDir, Files.isRegularFile(descHtml));
            assertTrue("Missing before.stvn.template in " + descDir, Files.isRegularFile(beforeTemplate));
            assertTrue("Missing after.stvn.template in " + descDir, Files.isRegularFile(afterTemplate));

            String htmlText = Files.readString(descHtml, StandardCharsets.UTF_8);
            assertTrue(descHtml + " must contain <html> and <body>",
                htmlText.contains("<html>") && htmlText.contains("<body>"));
            assertFalse(afterTemplate + " must not be empty",
                Files.readString(afterTemplate, StandardCharsets.UTF_8).isBlank());
            if (!"StvnBootstrapDocumentIntentionAction".equals(dirName)) {
                assertFalse(beforeTemplate + " must not be empty",
                    Files.readString(beforeTemplate, StandardCharsets.UTF_8).isBlank());
            }
        }

        // 2. Verify README.md documents all 27 <localInspection> shortNames from plugin.xml
        NodeList inspectionNodes = xmlDoc.getElementsByTagName("localInspection");
        assertEquals("Expected 27 <localInspection> entries in plugin.xml", 27, inspectionNodes.getLength());
        String readmeContent = Files.readString(root.resolve("README.md"), StandardCharsets.UTF_8);
        for (int i = 0; i < inspectionNodes.getLength(); i++) {
            Element insp = (Element) inspectionNodes.item(i);
            String shortName = insp.getAttribute("shortName");
            assertTrue("README.md must list inspection shortName '" + shortName + "'",
                readmeContent.contains("`" + shortName + "`"));
        }
    }

    public void testJavaInspectionStringsHygiene() throws IOException {
        var baseDir = findProjectRoot();
        assertNotNull("Project root must be found", baseDir);

        var validationDir = baseDir.toPath().resolve("src/main/java/org/stvnadore/plugin/validation");
        assertTrue("Validation source directory must exist: " + validationDir, Files.exists(validationDir));

        var violations = new ArrayList<String>();

        try (Stream<Path> stream = Files.walk(validationDir)) {
            var javaFiles = stream.filter(p -> p.getFileName().toString().endsWith("Inspection.java")).toList();
            for (var file : javaFiles) {
                if (file.getFileName().toString().equals("StvnStringCapacityInspection.java")) {
                    continue;
                }

                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    var line = lines.get(i);
                    var trimmed = line.trim();
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        continue;
                    }
                    if (line.contains("\":Uint\"") || line.contains("\":Int32\"") || line.contains("\":StringFixed\"")) {
                        violations.add(file.getFileName() + ":" + (i + 1) + " -> forbidden legacy literal in: " + trimmed);
                    }
                    if (line.contains("\"\"\"->")) {
                        violations.add(file.getFileName() + ":" + (i + 1) + " -> forbidden legacy arrow literal in: " + trimmed);
                    }
                    if (line.toLowerCase().contains("deprecated") && !line.contains("@SuppressWarnings")) {
                        violations.add(file.getFileName() + ":" + (i + 1) + " -> forbidden deprecation reference in: " + trimmed);
                    }
                }
            }
        }

        assertTrue("Found " + violations.size() + " forbidden 1.x string literals in Java validation inspections:\n"
            + String.join("\n", violations), violations.isEmpty());
    }

    private static void scanContentAgainstForbiddenPatterns(String sourceLabel, String content, List<String> violations) {
        for (Pattern pattern : FORBIDDEN_1X_AND_STYLE_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                violations.add(sourceLabel + " -> matches forbidden pattern '"
                    + pattern.pattern() + "': " + matcher.group());
            }
        }
        scanForReversedClosingFences(sourceLabel, content, violations);
    }

    private static void scanForReversedClosingFences(String sourceLabel, String content, List<String> violations) {
        Matcher inlineSpanMatcher = INLINE_CODE_SPAN_PATTERN.matcher(content);
        while (inlineSpanMatcher.find()) {
            String span = inlineSpanMatcher.group(1) != null ? inlineSpanMatcher.group(1) : inlineSpanMatcher.group(2);
            if (span != null && INLINE_REVERSED_FENCE_PATTERN.matcher(span).find()) {
                violations.add(sourceLabel + " -> inline code span contains reversed closing fence: " + span);
            }
        }
        if (sourceLabel.endsWith(".template") || sourceLabel.contains("#stvnBlock")) {
            Matcher blockMatcher = BLOCK_REVERSED_CLOSING_FENCE_PATTERN.matcher(content);
            if (blockMatcher.find()) {
                violations.add(sourceLabel + " -> STVN block contains reversed closing fence: " + blockMatcher.group());
            }
        } else if (sourceLabel.endsWith(".html")) {
            Matcher preMatcher = HTML_PRE_BLOCK_PATTERN.matcher(content);
            while (preMatcher.find()) {
                Matcher revMatcher = BLOCK_REVERSED_CLOSING_FENCE_PATTERN.matcher(preMatcher.group(1));
                if (revMatcher.find()) {
                    violations.add(sourceLabel + " -> <pre> block contains reversed closing fence: " + revMatcher.group());
                }
            }
        } else if (sourceLabel.endsWith(".md")) {
            Matcher mdBlockMatcher = STVN_FENCED_CODE_BLOCK_PATTERN.matcher(content);
            while (mdBlockMatcher.find()) {
                Matcher revMatcher = BLOCK_REVERSED_CLOSING_FENCE_PATTERN.matcher(mdBlockMatcher.group(1));
                if (revMatcher.find()) {
                    violations.add(sourceLabel + " -> ```stvn block contains reversed closing fence: " + revMatcher.group());
                }
            }
        }
    }

    private static void scanForBareTripleQuotes(String sourceLabel, String content, List<String> violations) {
        int idx = 0;
        while ((idx = content.indexOf("\"\"\"", idx)) != -1) {
            boolean validOpening = (idx + 3 < content.length()) && content.charAt(idx + 3) == '[';
            boolean validClosing = (idx > 0) && content.charAt(idx - 1) == ']';
            if (!validOpening && !validClosing) {
                int snippetStart = Math.max(0, idx - 16);
                int snippetEnd = Math.min(content.length(), idx + 19);
                String snippet = content.substring(snippetStart, snippetEnd).replace("\n", "\\n").replace("\r", "");
                violations.add(sourceLabel + " -> bare triple-quote delimiter outside Rule STR-04: '" + snippet + "'");
            }
            idx += 3;
        }
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0) {
            return "";
        }
        return children.item(0).getTextContent().trim();
    }

    private static File findProjectRoot() {
        var current = new File(".").getAbsoluteFile();
        while (current != null) {
            if (new File(current, "build.gradle.kts").exists() && new File(current, "src/main/resources").exists()) {
                return current;
            }
            current = current.getParentFile();
        }
        return new File(".");
    }
}
