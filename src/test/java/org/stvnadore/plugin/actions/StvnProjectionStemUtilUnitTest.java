package org.stvnadore.plugin.actions;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.stvnadore.plugin.actions.StvnProjectionStemUtil.StvnTextExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java headless {@code :unitTest} suite verifying {@link StvnProjectionStemUtil}
 * extension classification, self-overwrite guards, and 1-stem / 2-stem Rule {@code E032}
 * filename derivation without IntelliJ Platform SDK dependencies.
 */
@NullMarked
public class StvnProjectionStemUtilUnitTest {

    @Test
    void testClassifyTextualAndBinaryExtensions() {
        assertEquals(Optional.of(StvnTextExtension.STVN), StvnProjectionStemUtil.classifyTextualExtension("order.stvn"));
        assertEquals(Optional.of(StvnTextExtension.STVN_I), StvnProjectionStemUtil.classifyTextualExtension("order.billing.stvn_i"));
        assertEquals(Optional.of(StvnTextExtension.STVN_D), StvnProjectionStemUtil.classifyTextualExtension("order.billing.stvn_d"));
        assertEquals(Optional.of(StvnTextExtension.STVN_DI), StvnProjectionStemUtil.classifyTextualExtension("order.billing.stvn_di"));
        assertEquals(Optional.empty(), StvnProjectionStemUtil.classifyTextualExtension("frame.stvn_b"));
        assertEquals(Optional.empty(), StvnProjectionStemUtil.classifyTextualExtension("frame.stvn_bd"));
        assertEquals(Optional.empty(), StvnProjectionStemUtil.classifyTextualExtension("snapshot.stvn_ir"));
        assertEquals(Optional.empty(), StvnProjectionStemUtil.classifyTextualExtension("README.md"));

        assertTrue(StvnProjectionStemUtil.isTextualStvnFileName("order.stvn"));
        assertTrue(StvnProjectionStemUtil.isTextualStvnFileName("order.stvn_i"));
        assertTrue(StvnProjectionStemUtil.isTextualStvnFileName("order.stvn_d"));
        assertTrue(StvnProjectionStemUtil.isTextualStvnFileName("order.stvn_di"));
        assertFalse(StvnProjectionStemUtil.isTextualStvnFileName("frame.stvn_b"));
        assertFalse(StvnProjectionStemUtil.isTextualStvnFileName("frame.stvn_bd"));

        assertTrue(StvnProjectionStemUtil.isBinaryStvnFileName("frame.stvn_b"));
        assertTrue(StvnProjectionStemUtil.isBinaryStvnFileName("frame.stvn_bd"));
        assertFalse(StvnProjectionStemUtil.isBinaryStvnFileName("order.stvn_d"));
    }

    @Test
    void testPayloadSupportAndSelfOverwriteGuards() {
        assertTrue(StvnProjectionStemUtil.supportsPayloadExtraction("app.stvn"));
        assertTrue(StvnProjectionStemUtil.supportsPayloadExtraction("app.core_test.stvn_i"));
        assertFalse(StvnProjectionStemUtil.supportsPayloadExtraction("schema.stvn_d"));
        assertFalse(StvnProjectionStemUtil.supportsPayloadExtraction("schema.stvn_di"));

        // Schema sibling export self-overwrite guard: true ONLY on .stvn_d (and non-STVN)
        assertTrue(StvnProjectionStemUtil.isSchemaSiblingSelfOverwrite("schema.stvn_d"));
        assertFalse(StvnProjectionStemUtil.isSchemaSiblingSelfOverwrite("schema.stvn_di"));
        assertFalse(StvnProjectionStemUtil.isSchemaSiblingSelfOverwrite("app.stvn_i"));
        assertFalse(StvnProjectionStemUtil.isSchemaSiblingSelfOverwrite("app.stvn"));

        // Payload sibling export self-overwrite / unsupported guard: false ONLY on .stvn_i
        assertFalse(StvnProjectionStemUtil.isPayloadSiblingSelfOverwriteOrUnsupported("app.stvn_i"));
        assertTrue(StvnProjectionStemUtil.isPayloadSiblingSelfOverwriteOrUnsupported("app.stvn"));
        assertTrue(StvnProjectionStemUtil.isPayloadSiblingSelfOverwriteOrUnsupported("schema.stvn_d"));
        assertTrue(StvnProjectionStemUtil.isPayloadSiblingSelfOverwriteOrUnsupported("schema.stvn_di"));
    }

    @Test
    void testOneStemAndTwoStemDefsAndPayloadDerivation() {
        assertEquals("foo.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("foo.stvn"));
        assertEquals("foo.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("foo.stvn_i"));
        assertEquals("foo.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("foo.stvn_di"));
        assertEquals("foo.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("foo.stvn_d"));
        assertEquals("bar.dom.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("bar.dom.stvn"));
        assertEquals("bar.dom.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("bar.dom.stvn_i"));
        assertEquals("bar.dom.stvn_d", StvnProjectionStemUtil.deriveDefsStemName("bar.dom.stvn_di"));

        assertEquals("foo.stvn", StvnProjectionStemUtil.derivePayloadStemName("foo.stvn_i"));
        assertEquals("foo.stvn", StvnProjectionStemUtil.derivePayloadStemName("foo.stvn"));
        assertEquals("bar.dom.stvn", StvnProjectionStemUtil.derivePayloadStemName("bar.dom.stvn_i"));
        assertEquals(
            "gauntlet_v8_stage10c_diamond_app_payload.core_test.stvn",
            StvnProjectionStemUtil.derivePayloadStemName(
                "gauntlet_v8_stage10c_diamond_app_payload.core_test.stvn_i"
            )
        );
    }
}
