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

    private static final List<String> FORBIDDEN_CORE_COUPLING_SYMBOLS = List.of(
        "org.antlr.v4.runtime",
        "org.stvnadore.core.parser",
        "org.stvnadore.core.ast",
        "org.stvnadore.core.lowering",
        "org.stvnadore.core.printer.internal",
        "org.stvnadore.core.validation.StvnTypeResolver",
        "org.stvnadore.core.ir.StvnLiteralParser",
        "org.stvnadore.core.ir.VariantStep",
        "PRELUDE_STVN_INCLF",
        "stvnInclfContent",
        "DEFAULT_UNBOUNDED_STRING_CAPACITY",
        "StvnStringCapacityUtils"
    );

    private static final List<String> FORBIDDEN_1X_REMNANTS = List.of(
        "StvnProduct",
        ":MapEntry",
        "KW_MAP_ENTRY",
        "#strict",
        "#deprecated",
        "#nonEmpty",
        ".stvn_inclf",
        ".stvn_incl",
        ".stvn_bin",
        ".stvn_f",
        "ERR_UNFENCED_BLOCK_STRING_PROHIBITED"
    );

    private static final List<String> FORBIDDEN_RAW_ANTLR_JARGON_IN_MAIN = List.of(
        "token recognition error",
        "LexerNoViableAltException",
        "no viable alternative",
        "mismatched input",
        "extraneous input"
    );

    /**
     * HYGIENE-PLUGIN-02: Verifies zero 1.x tokens/extensions across workspace and zero raw ANTLR jargon in src/main.
     *
     * @throws IOException if workspace files cannot be read
     */
    public void testZeroOneXRemnantsAndZeroCompilerJargon() throws IOException {
        var root = findProjectRoot().toPath();
        var violations = new ArrayList<String>();
        var scanRoots = List.of(
            root.resolve("src/main"),
            root.resolve("src/test/java"),
            root.resolve("build.gradle.kts"),
            root.resolve("README.md"),
            root.resolve("docs")
        );
        for (var path : scanRoots) {
            if (!Files.exists(path)) {
                continue;
            }
            try (Stream<Path> walk = Files.isDirectory(path) ? Files.walk(path) : Stream.of(path)) {
                for (var file : walk.filter(Files::isRegularFile).toList()) {
                    if (file.getFileName().toString().equals("StvnVocabularyHygieneTest.java")) {
                        continue;
                    }
                    String fileStr = file.toString().replace('\\', '/');
                    if (!fileStr.endsWith(".java") && !fileStr.endsWith(".kts") && !fileStr.endsWith(".xml")
                        && !fileStr.endsWith(".bnf") && !fileStr.endsWith(".flex") && !fileStr.endsWith(".md")) {
                        continue;
                    }
                    var lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (String banned : FORBIDDEN_1X_REMNANTS) {
                            if (line.contains(banned)) {
                                violations.add(fileStr + ":" + (i + 1) + " contains 1.x remnant '" + banned + "'");
                            }
                        }
                        if (line.matches(".*:Product(?![A-Za-z0-9_]).*")) {
                            violations.add(fileStr + ":" + (i + 1) + " contains 1.x ':Product' token");
                        }
                        if (line.matches(".*\\.stvn_cas(?![.A-Za-z0-9_]).*")) {
                            violations.add(fileStr + ":" + (i + 1) + " contains bare 1.x '.stvn_cas' extension");
                        }
                        if (fileStr.contains("/src/main/")) {
                            for (String jargon : FORBIDDEN_RAW_ANTLR_JARGON_IN_MAIN) {
                                if (line.contains(jargon)) {
                                    violations.add(fileStr + ":" + (i + 1) + " contains raw parser jargon '" + jargon + "'");
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("HYGIENE-PLUGIN-02 violations:\n" + String.join("\n", violations), violations.isEmpty());
    }

    /**
     * HYGIENE-PLUGIN-03: Verifies zero ANTLR CST, core.parser, core.ast, core.lowering, or core StvnTypeResolver coupling.
     *
     * @throws IOException if Java source files cannot be read
     */
    public void testZeroAntlrCstAndInternalCoreCoupling() throws IOException {
        var root = findProjectRoot().toPath().resolve("src");
        var violations = new ArrayList<String>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (var file : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("StvnVocabularyHygieneTest.java")) {
                    continue;
                }
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    for (String sym : FORBIDDEN_CORE_COUPLING_SYMBOLS) {
                        if (line.contains(sym)) {
                            violations.add(file + ":" + (i + 1) + " references forbidden symbol '" + sym + "'");
                        }
                    }
                }
            }
        }
        assertTrue("HYGIENE-PLUGIN-03 violations:\n" + String.join("\n", violations), violations.isEmpty());
    }

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

        // Section 13 Compiler IR Substrate Identifiers
        assertEquals(":org/stvnadore/ir/IrTraitConstraints", StvnVocabulary.IR_TYPE_TRAIT_CONSTRAINTS);
        assertEquals(":org/stvnadore/ir/IrRegisteredSchema", StvnVocabulary.IR_TYPE_REGISTERED_SCHEMA);
        assertEquals(":org/stvnadore/ir/IrTypeRegistry", StvnVocabulary.IR_TYPE_TYPE_REGISTRY);
        assertEquals(":org/stvnadore/ir/IrSnapshotDocument", StvnVocabulary.IR_TYPE_SNAPSHOT_DOCUMENT);
        assertEquals(":org/stvnadore/ir/IrValue", StvnVocabulary.IR_TYPE_VALUE);
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
