package org.stvnadore.plugin.icons;

import junit.framework.TestCase;
import org.jspecify.annotations.NullMarked;
import java.util.Arrays;

/**
 * Validates singleton integrity for all seven STVN ecosystem icons
 * and enforces the complete elimination of legacy 1.x icon identifiers.
 */
@NullMarked
public final class StvnIconsTest extends TestCase {

    public void testIconSingletonsLoaded() {
        assertNotNull("STVN icon must not be null", StvnIcons.STVN);
        assertNotNull("STVN_F icon must not be null", StvnIcons.STVN_F);
        assertNotNull("STVN_INCL icon must not be null", StvnIcons.STVN_INCL);
        assertNotNull("STVN_INCLF icon must not be null", StvnIcons.STVN_INCLF);
        assertNotNull("STVN_BIN icon must not be null", StvnIcons.STVN_BIN);
        assertNotNull("STVN_CAS icon must not be null", StvnIcons.STVN_CAS);
        assertNotNull("STVN_IR icon must not be null", StvnIcons.STVN_IR);

        assertTrue(StvnIcons.STVN.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_F.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_INCL.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_INCLF.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_BIN.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_CAS.getIconWidth() > 0);
        assertTrue(StvnIcons.STVN_IR.getIconWidth() > 0);
    }

    public void testLegacyFileFieldPurged() {
        var fields = StvnIcons.class.getDeclaredFields();
        var hasFileField = Arrays.stream(fields)
                .anyMatch(f -> f.getName().equals("FILE"));
        assertFalse("Legacy 1.x compatibility shim 'FILE' must be permanently removed from StvnIcons", hasFileField);
    }
}
