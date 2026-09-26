package org.stvnadore.plugin;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Static hygiene test suite enforcing the strict prohibition of raw typic string literals
 * across completion contributors, type resolvers, and inspections.
 * <p>
 * Enforces TC-VOCAB-01 from the STVN 2.0.0 alignment specification.
 */
@NullMarked
public final class StvnVocabularyHygieneTest extends TestCase {

    private static final Set<String> FORBIDDEN_TYPIC_LITERALS = Set.of(
        "\":Int\"",
        "\":Float\"",
        "\":String\"",
        "\":Tuple\"",
        "\":Option\"",
        "\":Either\"",
        "\":Union\"",
        "\":Seq\"",
        "\":Set\"",
        "\":Map\""
    );

    /**
     * Verifies that all central vocabulary constants on {@link StvnVocabulary} are non-empty.
     */
    public void testVocabularyConstantsIntegrity() {
        assertFalse(StvnVocabulary.TYPE_INT.isEmpty());
        assertFalse(StvnVocabulary.TYPE_FLOAT.isEmpty());
        assertFalse(StvnVocabulary.TYPE_STRING.isEmpty());
        assertFalse(StvnVocabulary.TYPE_BOOLEAN.isEmpty());
        assertFalse(StvnVocabulary.TYPE_TUPLE.isEmpty());
        assertFalse(StvnVocabulary.TYPE_OPTION.isEmpty());
        assertFalse(StvnVocabulary.TYPE_EITHER.isEmpty());
        assertFalse(StvnVocabulary.TYPE_UNION.isEmpty());
        assertFalse(StvnVocabulary.TYPE_ENUM.isEmpty());
        assertFalse(StvnVocabulary.TYPE_SEQ.isEmpty());
        assertFalse(StvnVocabulary.TYPE_SET.isEmpty());
        assertFalse(StvnVocabulary.TYPE_MAP.isEmpty());
        assertFalse(StvnVocabulary.SIGIL_VALUE.isEmpty());
        assertFalse(StvnVocabulary.SIGIL_TYPIC.isEmpty());
    }

    /**
     * Scans completion contributors and validation inspections to assert zero hardcoded typic literals.
     *
     * @throws IOException if source files cannot be read
     */
    public void testCompletionAndValidationTokenHygiene() throws IOException {
        var baseDir = findProjectRoot();
        assertNotNull("Project root directory could not be located", baseDir);

        var targetDirs = List.of(
            baseDir.toPath().resolve("src/main/java/org/stvnadore/plugin/completion"),
            baseDir.toPath().resolve("src/main/java/org/stvnadore/plugin/validation")
        );

        var violations = new ArrayList<String>();

        for (var dir : targetDirs) {
            assertTrue("Target source directory must exist: " + dir, Files.exists(dir));
            try (Stream<Path> stream = Files.walk(dir)) {
                var javaFiles = stream.filter(p -> p.toString().endsWith(".java")).toList();
                for (var file : javaFiles) {
                    var lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        var line = lines.get(i);
                        var trimmed = line.trim();
                        // Ignore comment lines
                        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                            continue;
                        }
                        for (var forbidden : FORBIDDEN_TYPIC_LITERALS) {
                            if (line.contains(forbidden)) {
                                violations.add(file.getFileName() + ":" + (i + 1) + " -> " + forbidden + " in: " + trimmed);
                            }
                        }
                    }
                }
            }
        }

        assertTrue("Found " + violations.size() + " forbidden raw typic literal occurrences. Must use StvnVocabulary constants:\n"
            + String.join("\n", violations), violations.isEmpty());
    }

    private static File findProjectRoot() {
        var current = new File(".").getAbsoluteFile();
        while (current != null) {
            if (new File(current, "build.gradle.kts").exists() && new File(current, "src/main/java").exists()) {
                return current;
            }
            current = current.getParentFile();
        }
        return new File(".");
    }
}
