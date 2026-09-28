package org.stvnadore.plugin.validation;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Automated hygiene test suite asserting that zero references to obsolete 1.x types,
 * compound wildcards, deprecated temporal facets, or ungrounded discrete bounds exist
 * across all inspection HTML descriptions and plugin Java classes.
 */
@NullMarked
public final class StvnInspectionDescriptionHygieneTest extends TestCase {

    private static final List<Pattern> FORBIDDEN_HTML_PATTERNS = List.of(
        Pattern.compile(":\\bUint\\w*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(":Int\\*"),
        Pattern.compile(":String\\*"),
        Pattern.compile(":Float\\*"),
        Pattern.compile(":\\bInt(8|16|32|64|128)\\b"),
        Pattern.compile(":\\bStringFixed\\w*"),
        Pattern.compile(":\\bStringNonEmpty\\w*"),
        Pattern.compile(":\\bString(64|4096|8192|16777216)\\b"),
        Pattern.compile(":\\bMapInv\\w*"),
        Pattern.compile("\\b#unit\\b")
    );

    public void testInspectionHtmlDescriptionsHygiene() throws IOException {
        var baseDir = findProjectRoot();
        assertNotNull("Project root must be found", baseDir);

        var htmlDir = baseDir.toPath().resolve("src/main/resources/inspectionDescriptions");
        assertTrue("Inspection descriptions directory must exist: " + htmlDir, Files.exists(htmlDir));

        var violations = new ArrayList<String>();

        try (Stream<Path> stream = Files.walk(htmlDir)) {
            var htmlFiles = stream.filter(p -> p.toString().endsWith(".html")).toList();
            assertFalse("HTML description files must be present", htmlFiles.isEmpty());

            for (var file : htmlFiles) {
                var content = Files.readString(file);
                for (var pattern : FORBIDDEN_HTML_PATTERNS) {
                    var matcher = pattern.matcher(content);
                    while (matcher.find()) {
                        violations.add(file.getFileName() + " -> matches forbidden pattern '"
                            + pattern.pattern() + "': " + matcher.group());
                    }
                }

                // Assert ungrounded discrete #maxIncl is not listed as permitted
                if (file.getFileName().toString().equals("StvnMetadataFacet.html")) {
                    if (content.contains("Discrete Integer (<code>:Int</code>):") && content.contains("#maxIncl")
                        && !content.contains("prohibited")) {
                        violations.add(file.getFileName() + " -> lists #maxIncl on discrete :Int without prohibition");
                    }
                }
            }
        }

        assertTrue("Found " + violations.size() + " forbidden 1.x references in inspection HTML descriptions:\n"
            + String.join("\n", violations), violations.isEmpty());
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
                // Exclude migration helper handling legacy token conversions
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
                }
            }
        }

        assertTrue("Found " + violations.size() + " forbidden 1.x string literals in Java validation inspections:\n"
            + String.join("\n", violations), violations.isEmpty());
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
