package org.stvnadore.plugin.icons;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import java.util.Arrays;

/**
 * Validates singleton integrity for all canonical and transitional STVN ecosystem icons
 * and enforces the complete elimination of legacy 1.x icon identifiers.
 */
@NullMarked
public final class StvnIconsTest extends TestCase {

    public void testIconSingletonsLoaded() {
        // 1. Eight Canonical STVN 2.0.0 Icons
        assertNotNull("STVN icon must not be null", StvnIcons.STVN);
        assertNotNull("STVN_D icon must not be null", StvnIcons.STVN_D);
        assertNotNull("STVN_I icon must not be null", StvnIcons.STVN_I);
        assertNotNull("STVN_DI icon must not be null", StvnIcons.STVN_DI);
        assertNotNull("STVN_B icon must not be null", StvnIcons.STVN_B);
        assertNotNull("STVN_BD icon must not be null", StvnIcons.STVN_BD);
        assertNotNull("STVN_IR icon must not be null", StvnIcons.STVN_IR);
        assertNotNull("STVN_CAS icon must not be null", StvnIcons.STVN_CAS);

        // 2. Pixel Dimension Assertions
        assertTrue(StvnIcons.STVN.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_D.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_D.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_I.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_I.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_DI.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_DI.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_B.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_B.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_BD.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_BD.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_IR.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_IR.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_CAS.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_CAS.getIconHeight() > 0);
    }

    public void testLegacyFileFieldPurged() {
        var fields = StvnIcons.class.getDeclaredFields();
        var fieldNames = Arrays.stream(fields).map(java.lang.reflect.Field::getName).toList();
        assertFalse("Legacy 1.x compatibility shim 'FILE' must be permanently removed from StvnIcons", fieldNames.contains("FILE"));
        assertFalse("Legacy 1.x compatibility shim 'STVN_F' must be permanently removed from StvnIcons", fieldNames.contains("STVN_F"));
        assertFalse("Legacy 1.x compatibility shim 'STVN_INCL' must be permanently removed from StvnIcons", fieldNames.contains("STVN_INCL"));
        assertFalse("Legacy 1.x compatibility shim 'STVN_INCLF' must be permanently removed from StvnIcons", fieldNames.contains("STVN_INCLF"));
        assertFalse("Legacy 1.x compatibility shim 'STVN_BIN' must be permanently removed from StvnIcons", fieldNames.contains("STVN_BIN"));
    }
}
