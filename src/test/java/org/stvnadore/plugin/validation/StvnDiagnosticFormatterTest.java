package org.stvnadore.plugin.validation;

import org.junit.Test;
import org.stvnadore.core.StvnDiagnostic;
import org.stvnadore.core.StvnDiagnostic.DiagnosticSeverity;
import org.stvnadore.core.validation.DiagnosticBag;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests validating {@link StvnDiagnosticFormatter} attribution formatting,
 * {@link StvnDiagnosticCodes} 1:1 {@link DiagnosticBag} oracle parity, and inspection suffix totality.
 */
public final class StvnDiagnosticFormatterTest {

    @Test
    public void testFormatPluginAppendsNominalTag() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Declared #domain 'place_holder' is omitted from filename stem slot 1",
            StvnDiagnosticCodes.WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME
        );
        assertEquals(
            "Declared #domain 'place_holder' is omitted from filename stem slot 1 [stvn-plugin:W002-WARN_DOCUMENT_DOMAIN_OMITTED_IN_FILENAME]",
            result
        );
    }

    @Test
    public void testFormatCoreAppendsNominalTag() {
        var result = StvnDiagnosticFormatter.formatCore(
            "Missing mandatory :meta block",
            "ERR_DOCUMENT_KIND_MISMATCH"
        );
        assertEquals(
            "Missing mandatory :meta block [stvn-core:E031-ERR_DOCUMENT_KIND_MISMATCH]",
            result
        );
    }

    @Test
    public void testFormatCoreWithExplicitCanonicalCode() {
        var result = StvnDiagnosticFormatter.formatCore(
            "Underscore separators are prohibited in numeric literals: '1_000'",
            "E002",
            "ERR_INVALID_NUMERIC_LITERAL"
        );
        assertEquals(
            "Underscore separators are prohibited in numeric literals: '1_000' [stvn-core:E002-ERR_INVALID_NUMERIC_LITERAL]",
            result
        );
    }

    @Test
    public void testFormatPluginAdvisoryWarningPrefix() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Exact decimal float precision 50 exceeds recommended threshold 38",
            StvnDiagnosticCodes.WARN_EXCESSIVE_EXACT_PRECISION
        );
        assertEquals(
            "Exact decimal float precision 50 exceeds recommended threshold 38 [stvn-plugin:PW005-WARN_EXCESSIVE_EXACT_PRECISION]",
            result
        );
    }

    @Test
    public void testFormatStripsTrailingPeriod() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Definitions schema requires root :meta block.",
            StvnDiagnosticCodes.ERR_DOCUMENT_KIND_MISMATCH
        );
        assertEquals(
            "Definitions schema requires root :meta block [stvn-plugin:E031-ERR_DOCUMENT_KIND_MISMATCH]",
            result
        );
    }

    @Test
    public void testFormatStripsTrailingPeriodWithWhitespace() {
        var result = StvnDiagnosticFormatter.formatPlugin(
            "Tab characters are forbidden under Rule STR-01.  ",
            StvnDiagnosticCodes.ERR_TAB_CHARACTER_FORBIDDEN
        );
        assertEquals(
            "Tab characters are forbidden under Rule STR-01 [stvn-plugin:E001-ERR_TAB_CHARACTER_FORBIDDEN]",
            result
        );
    }

    @Test
    public void testFormatIdempotentGuard() {
        var formatted = "Already formatted message [stvn-plugin:E001-ERR_FOO]";
        var result = StvnDiagnosticFormatter.formatPlugin(formatted, "ERR_BAR");
        assertEquals(formatted, result);

        var coreFormatted = "Already core formatted [stvn-core:E001-ERR_CORE]";
        var coreResult = StvnDiagnosticFormatter.formatCore(coreFormatted, "ERR_ANOTHER");
        assertEquals(coreFormatted, coreResult);
    }

    @Test
    public void testFormatNullAndBlank() {
        assertEquals("[stvn-plugin:E002-ERR_CODE]", StvnDiagnosticFormatter.formatPlugin(null, "ERR_CODE"));
        assertEquals("[stvn-plugin:E002-ERR_CODE]", StvnDiagnosticFormatter.formatPlugin("   ", "ERR_CODE"));
        assertEquals("[stvn-core:E002-ERR_CODE]", StvnDiagnosticFormatter.formatCore(null, "ERR_CODE"));
    }

    @Test
    public void testAllStvnDiagnosticCodesMatchCanonicalPrefixedPatternAndCoreOracle() throws IllegalAccessException {
        Field[] fields = StvnDiagnosticCodes.class.getDeclaredFields();
        int stringConstantCount = 0;
        List<String> failures = new ArrayList<>();

        for (Field field : fields) {
            int mods = field.getModifiers();
            if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || !Modifier.isFinal(mods)
                    || field.getType() != String.class) {
                continue;
            }
            stringConstantCount++;
            String value = (String) field.get(null);
            if (!StvnDiagnosticCodes.PREFIXED_CODE_PATTERN.matcher(value).matches()) {
                failures.add("Field " + field.getName() + " = '" + value + "' does not match PREFIXED_CODE_PATTERN");
                continue;
            }
            int dashIdx = value.indexOf('-');
            String prefix = value.substring(0, dashIdx);
            String bareSymbolic = value.substring(dashIdx + 1);
            Optional<String> coreCanonical = DiagnosticBag.resolveCanonicalCode(bareSymbolic);
            if (coreCanonical.isPresent() && !coreCanonical.get().equals(prefix)) {
                failures.add(
                    "Field " + field.getName() + " = '" + value + "' disagrees with DiagnosticBag.resolveCanonicalCode('"
                        + bareSymbolic + "') = '" + coreCanonical.get() + "'"
                );
            }
            assertEquals(
                "resolveCanonicalPrefix must agree with constant prefix on " + field.getName(),
                prefix,
                StvnDiagnosticCodes.resolveCanonicalPrefix(bareSymbolic)
            );
        }

        assertTrue("Expected at least 80 String constants on StvnDiagnosticCodes, found " + stringConstantCount,
            stringConstantCount >= 80);
        assertTrue("100% of StvnDiagnosticCodes constants must match <CODE>-<SYMBOLIC_ID> and Core oracle:\n"
            + String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void testZeroInspectionsPassBareDiagnosticBagConstantsToFormatPlugin() throws IOException {
        Path validationDir = Path.of("src", "main", "java", "org", "stvnadore", "plugin", "validation");
        assertTrue("Validation source directory must exist: " + validationDir.toAbsolutePath(), Files.isDirectory(validationDir));

        Pattern bareBagFormatCall = Pattern.compile("formatPlugin\\s*\\([\\s\\S]{0,400}?DiagnosticBag\\.");
        List<String> violations = new ArrayList<>();
        int inspectionCount = 0;

        try (Stream<Path> paths = Files.list(validationDir)) {
            for (Path file : paths.filter(p -> p.getFileName().toString().endsWith("Inspection.java")).toList()) {
                if (!file.getFileName().toString().startsWith("Abstract")) {
                    inspectionCount++;
                }
                String src = Files.readString(file);
                if (bareBagFormatCall.matcher(src).find()) {
                    violations.add(file.getFileName() + " still passes bare DiagnosticBag.* to formatPlugin");
                }
            }
        }

        assertEquals("Expected all 30 local inspection classes", 30, inspectionCount);
        assertTrue("Zero inspections may pass bare DiagnosticBag.* to formatPlugin:\n"
            + String.join("\n", violations), violations.isEmpty());
    }

    @Test
    public void testFormatCoreDiagnosticRecordAndMalformedSuffixNormalization() {
        var diag = new StvnDiagnostic(
            "Union variant tag '#3' exceeds branch count (2)",
            DiagnosticSeverity.ERROR,
            28,
            73,
            1218,
            1220,
            null,
            Optional.of(DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW),
            Optional.of("E028"),
            Optional.of(DiagnosticBag.ERR_UNION_BRANCH_OVERFLOW),
            Optional.of("stage8.stvn")
        );
        assertEquals(
            "Union variant tag '#3' exceeds branch count (2) [stvn-core:E028-UNION_BRANCH_OVERFLOW]",
            StvnDiagnosticFormatter.formatCore(diag)
        );

        var legacyPartial = "Definitions schema requires root :meta block [stvn-core:ERR_DOCUMENT_KIND_MISMATCH]";
        assertEquals(
            "Definitions schema requires root :meta block [stvn-core:E031-ERR_DOCUMENT_KIND_MISMATCH]",
            StvnDiagnosticFormatter.formatCore(legacyPartial, "E031", DiagnosticBag.ERR_DOCUMENT_KIND_MISMATCH)
        );
    }
}
