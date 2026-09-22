package org.stvnadore.plugin;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NullMarked;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import org.stvnadore.core.StvnCompiler;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JUnit 5 test verification pulling fixtures directly from the classpath stream.
 */
@NullMarked
public final class StvnParserTest {

    @Test
    public void testFixtureParsing() throws Exception {
        var loader = StvnParserTest.class.getClassLoader();
        try (var stream = loader.getResourceAsStream("valid-syntax/basic_boolean.stvn")) {
            if (stream == null) {
                throw new FileNotFoundException("Fixture 'valid-syntax/basic_boolean.stvn' not found on test classpath");
            }
            var text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var compiled = StvnCompiler.compile(text);
            
            assertTrue(compiled.isPresent(), "Parsed STVN value should be present and valid");
            var val = compiled.get();
            assertNotNull(val, "The compiled StvnValue object must not be null");
        }
    }

    @Test
    public void testDeprecatedFencedStringArrowRejected() {
        String invalidSource = "{\n  :type :String\n  :body \"\"\"->[TAG]\nContent\n[TAG]\"\"\"\n}";
        boolean rejected = false;
        try {
            var compiled = StvnCompiler.compile(invalidSource);
            rejected = compiled.isEmpty();
        } catch (RuntimeException e) {
            rejected = true;
        }
        assertTrue(rejected, "Deprecated directional arrow '->' in fenced string delimiter must be permanently rejected under Rule STR-04");
    }
}
