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

    @SuppressWarnings("removal")
    public void testIconSingletonsLoaded() {
        // 1. Eight Canonical STVN 2.0.0 Icons
        assertNotNull("STVN icon must not be null", StvnIcons.STVN);
        assertNotNull("STVN_DEFS icon must not be null", StvnIcons.STVN_DEFS);
        assertNotNull("STVN_PAYLOAD_INCL icon must not be null", StvnIcons.STVN_PAYLOAD_INCL);
        assertNotNull("STVN_DEFS_INCL icon must not be null", StvnIcons.STVN_DEFS_INCL);
        assertNotNull("STVN_BIN icon must not be null", StvnIcons.STVN_BIN);
        assertNotNull("STVN_BD icon must not be null", StvnIcons.STVN_BD);
        assertNotNull("STVN_IR icon must not be null", StvnIcons.STVN_IR);
        assertNotNull("STVN_CAS icon must not be null", StvnIcons.STVN_CAS);

        // 2. Transitional Deprecated Shims
        assertNotNull("STVN_F icon must not be null", StvnIcons.STVN_F);
        assertNotNull("STVN_INCL icon must not be null", StvnIcons.STVN_INCL);
        assertNotNull("STVN_INCLF icon must not be null", StvnIcons.STVN_INCLF);

        // 3. Pixel Dimension Assertions
        assertTrue(StvnIcons.STVN.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_DEFS.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_DEFS.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_PAYLOAD_INCL.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_PAYLOAD_INCL.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_DEFS_INCL.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_DEFS_INCL.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_BIN.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_BIN.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_BD.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_BD.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_IR.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_IR.getIconHeight() > 0);
        assertTrue(StvnIcons.STVN_CAS.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_CAS.getIconHeight() > 0);

        assertTrue(StvnIcons.STVN_F.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_INCL.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_INCLF.getIconWidth() > 0);
    }

    public void testLegacyFileFieldPurged() {
        var fields = StvnIcons.class.getDeclaredFields();
        var hasFileField = Arrays.stream(fields)
                .anyMatch(f -> f.getName().equals("FILE"));
        assertFalse("Legacy 1.x compatibility shim 'FILE' must be permanently removed from StvnIcons", hasFileField);
    }
}
