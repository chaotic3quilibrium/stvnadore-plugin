package org.stvnadore.plugin;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnVocabulary;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Full-repository vocabulary hygiene gate for {@code ij_stvnadore_plugin}.
 * <p>
 * Enforces TC-VOCAB-01, TC-HYGIENE-01, and TC-HYGIENE-02 from the STVN 2.0.0 alignment specification.
 */
@NullMarked
public final class StvnVocabularyHygieneTest extends TestCase {

    private static final Set<String> FORBIDDEN_EXACT_STRING_LITERALS = Set.of(
        // Base scalar & composite types
        ":Boolean", ":Int", ":Float", ":String", ":TimeEpoch", ":DateTime",
        ":Seq", ":Set", ":Map", ":Tuple", ":Option", ":Either", ":Union", ":Enum", ":MapEntry",
        // Document section & modular keywords
        ":meta", ":defs", ":type", ":body", ":include", ":package", ":use",
        // Metadata facet keywords (all 7 tiers + :meta facets)
        "#unsigned", "#exact", "#invertible", "#preserveIndent",
        "#offset", "#zoned", "#audited",
        "#equatable", "#comparable",
        "#s", "#ms", "#us", "#ns",
        "#size", "#minSize", "#maxSize",
        "#minIncl", "#minExcl", "#maxExcl", "#maxIncl",
        "#regex", "#filterIncl", "#filterExcl", "#strip",
        "#name", "#domain", "#kind",
        // Document kind variants
        "#BODY", "#DEFS", "#BODY_INCLUDE", "#DEFS_INCLUDE",
        // Boolean & algebraic sum value keywords
        "#TRUE", "#FALSE", "#T", "#F",
        "#Some", "#None", "#S", "#N",
        "#Left", "#Right", "#L", "#R",
        // IEEE-754 special floating-point literals
        "NaN", "+Infinity", "-Infinity", "-0.0",
        // Dual-track prefix sigils
        ":", "#"
    );

    private static final Pattern INVENTED_TYPE_LITERAL_PATTERN = Pattern.compile(
        ":(?:Value|Any|Record|Product|MapEntry)\\b"
    );

    private static final Pattern PURGED_1X_TEMPORAL_TYPE_PATTERN = Pattern.compile(
        ":(?:DateTimeOffset|DateTimeZoned|DateTimeAudited|EpochSeconds|EpochMillis|EpochMicros|EpochNanos)\\b"
    );

    private static final Pattern DOUBLE_QUOTED_STRING_PATTERN = Pattern.compile(
        "\"((?:[^\"\\\\]|\\\\.)*)\""
    );

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

    /**
     * TC-HYGIENE-01: Scans the entire {@code src/main/java/org/stvnadore/plugin/} tree to assert
     * zero raw STVN {@code :Type}, {@code :section}, {@code #facet}, {@code #kind}, {@code #boolean},
     * {@code #sum}, sigil, or IEEE-754 string literals bypassing {@link StvnVocabulary}.
     *
     * @throws IOException if source files cannot be read
     */
    public void testZeroRawVocabularyStringLiterals() throws IOException {
        var mainJavaRoot = findProjectRoot().toPath().resolve("src/main/java/org/stvnadore/plugin");
        assertTrue("Main Java source root must exist: " + mainJavaRoot, Files.exists(mainJavaRoot));

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(mainJavaRoot)) {
            List<Path> javaFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .sorted()
                .toList();

            for (Path file : javaFiles) {
                scanFileForExactVocabularyLiterals(file, violations);
            }
        }

        assertTrue(
            "Discovered hardcoded STVN vocabulary string literals bypassing StvnVocabulary:\n"
                + String.join("\n", violations),
            violations.isEmpty()
        );
    }

    /**
     * TC-HYGIENE-02: Scans the entire {@code src/main/java/org/stvnadore/plugin/} tree to assert
     * zero invented ({@code :Value}, {@code :Any}, {@code :Record}, {@code :Product}, {@code :MapEntry})
     * or leaked 1.x temporal type literals.
     *
     * @throws IOException if source files cannot be read
     */
    public void testZeroInventedOrPurged1xTypeLiterals() throws IOException {
        var mainJavaRoot = findProjectRoot().toPath().resolve("src/main/java/org/stvnadore/plugin");
        assertTrue("Main Java source root must exist: " + mainJavaRoot, Files.exists(mainJavaRoot));

        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(mainJavaRoot)) {
            List<Path> javaFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .sorted()
                .toList();

            for (Path file : javaFiles) {
                scanFileForInventedAndPurgedTypeLiterals(file, violations);
            }
        }

        assertTrue(
            "Discovered invented or purged 1.x STVN type literals in src/main/java:\n"
                + String.join("\n", violations),
            violations.isEmpty()
        );
    }

    private static void scanFileForExactVocabularyLiterals(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean inBlockComment = false;
        boolean inTextBlock = false;

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmed = rawLine.trim();

            if (trimmed.contains("\"\"\"")) {
                long tripleQuotes = countOccurrences(trimmed, "\"\"\"");
                if (tripleQuotes % 2 == 1) {
                    inTextBlock = !inTextBlock;
                }
                continue;
            }
            if (inTextBlock) {
                continue;
            }
            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }

            String codePortion = stripLineCommentOutsideStrings(rawLine);
            Matcher m = DOUBLE_QUOTED_STRING_PATTERN.matcher(codePortion);
            while (m.find()) {
                String literalValue = m.group(1);
                if (FORBIDDEN_EXACT_STRING_LITERALS.contains(literalValue)) {
                    violations.add(file + ":" + (i + 1) + " -> hardcoded literal \"" + literalValue + "\" in: " + trimmed);
                }
            }
        }
    }

    private static void scanFileForInventedAndPurgedTypeLiterals(Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        boolean inBlockComment = false;

        for (int i = 0; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            String trimmed = rawLine.trim();

            if (inBlockComment) {
                if (trimmed.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                continue;
            }

            String codePortion = stripLineCommentOutsideStrings(rawLine);
            Matcher m = DOUBLE_QUOTED_STRING_PATTERN.matcher(codePortion);
            while (m.find()) {
                String literalContent = m.group(1);
                Matcher inventedMatcher = INVENTED_TYPE_LITERAL_PATTERN.matcher(literalContent);
                if (inventedMatcher.find()) {
                    violations.add(file + ":" + (i + 1) + " -> invented type literal '"
                        + inventedMatcher.group() + "' in: " + trimmed);
                }
                Matcher purgedMatcher = PURGED_1X_TEMPORAL_TYPE_PATTERN.matcher(literalContent);
                if (purgedMatcher.find()) {
                    violations.add(file + ":" + (i + 1) + " -> purged 1.x temporal type literal '"
                        + purgedMatcher.group() + "' in: " + trimmed);
                }
            }
        }
    }

    private static String stripLineCommentOutsideStrings(String line) {
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && inQuotes) {
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (!inQuotes && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static long countOccurrences(String text, String sub) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
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
