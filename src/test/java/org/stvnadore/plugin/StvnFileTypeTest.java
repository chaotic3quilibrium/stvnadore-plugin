package org.stvnadore.plugin;

import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * Verifies explicit file type associations and icon bindings across all seven ecosystem extensions.
 */
@NullMarked
public final class StvnFileTypeTest extends BasePlatformTestCase {

    public void testPayloadFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn");
        assertEquals("STVN_PAYLOAD", fileType.getName());
        assertEquals(StvnIcons.STVN, fileType.getIcon());
    }

    public void testFlatPayloadFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_f");
        assertEquals("STVN_FLAT_PAYLOAD", fileType.getName());
        assertEquals(StvnIcons.STVN_F, fileType.getIcon());
    }

    public void testIncludeFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_incl");
        assertEquals("STVN_INCL", fileType.getName());
        assertEquals(StvnIcons.STVN_INCL, fileType.getIcon());
    }

    public void testFlatIncludeFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_inclf");
        assertEquals("STVN_INCLF", fileType.getName());
        assertEquals(StvnIcons.STVN_INCLF, fileType.getIcon());
    }

    public void testCasFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_cas");
        assertEquals("STVN_CAS", fileType.getName());
        assertEquals(StvnIcons.STVN_CAS, fileType.getIcon());
    }

    public void testBinaryFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_bin");
        assertEquals("STVN_BIN", fileType.getName());
        assertEquals(StvnIcons.STVN_BIN, fileType.getIcon());
        assertTrue(fileType.isBinary());
    }

    public void testIrFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_ir");
        assertEquals("STVN_IR", fileType.getName());
        assertEquals(StvnIcons.STVN_IR, fileType.getIcon());
        assertFalse(fileType.isBinary());
    }
}
