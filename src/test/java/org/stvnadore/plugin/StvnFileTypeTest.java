package org.stvnadore.plugin;

import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * Verifies explicit file type associations and icon bindings across all canonical ecosystem extensions.
 */
@NullMarked
public final class StvnFileTypeTest extends BasePlatformTestCase {

    public void testPayloadFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn");
        assertEquals("STVN_PAYLOAD", fileType.getName());
        assertEquals(StvnIcons.STVN, fileType.getIcon());
    }

    public void testDefsFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_d");
        assertEquals("STVN_DEFS", fileType.getName());
        assertEquals(StvnIcons.STVN_D, fileType.getIcon());
    }

    public void testDefsIncludeFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_di");
        assertEquals("STVN_DEFS_INCLUDE", fileType.getName());
        assertEquals(StvnIcons.STVN_DI, fileType.getIcon());
    }

    public void testPayloadIncludeFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_i");
        assertEquals("STVN_PAYLOAD_INCLUDE", fileType.getName());
        assertEquals(StvnIcons.STVN_I, fileType.getIcon());
    }

    public void testBinaryFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_b");
        assertEquals("STVN_BIN", fileType.getName());
        assertEquals(StvnIcons.STVN_B, fileType.getIcon());
        assertTrue(fileType.isBinary());
    }

    public void testBinaryDefsFileTypeAssociation() {
        var fileType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_bd");
        assertEquals("STVN_BINARY_DEFS", fileType.getName());
        assertEquals(StvnIcons.STVN_BD, fileType.getIcon());
        assertTrue(fileType.isBinary());
    }

    public void testLegacyFileTypesPurged() {
        var inclType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_incl");
        assertFalse(inclType instanceof StvnFileType);
        var inclfType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_inclf");
        assertFalse(inclfType instanceof StvnFileType);
        var fType = FileTypeManager.getInstance().getFileTypeByExtension("stvn_f");
        assertFalse(fType instanceof StvnFileType);
    }
}
