package org.stvnadore.plugin.icons;

import com.intellij.openapi.util.Iconable;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Validates dynamic icon provider overrides for STVN substrate enclaves
 * and confirms zero-mutation fallback for standard user documents.
 */
@NullMarked
public final class StvnIconProviderTest extends BasePlatformTestCase {

  private final StvnIconProvider provider = new StvnIconProvider();

  public void testIrSubstrateEnclavePatternReturnsIrIcon() {
    var file1 = myFixture.configureByText("stvn_ir_substrate.stvn_ir.stvn_d", "{ :defs {} }");
    var file2 = myFixture.configureByText("snapshot.stvn_ir.stvn_i", "{ :type :Int :body 1 }");
    var file3 = myFixture.configureByText("raw.stvn_ir", "ir dump content");

    assertEquals(StvnIcons.STVN_IR, provider.getIcon(file1, Iconable.ICON_FLAG_VISIBILITY));
    assertEquals(StvnIcons.STVN_IR, provider.getIcon(file2, Iconable.ICON_FLAG_VISIBILITY));
    assertEquals(StvnIcons.STVN_IR, provider.getIcon(file3, Iconable.ICON_FLAG_VISIBILITY));
  }

  public void testCasSubstrateEnclavePatternReturnsCasIcon() {
    var file1 = myFixture.configureByText("envelope.stvn_cas.stvn", "{ :type :Int :body 1 }");
    var file2 = myFixture.configureByText("contract.stvn_cas.stvn_d", "{ :defs {} }");
    var file3 = myFixture.configureByText("blob.stvn_cas", "{ :body 1 }");

    assertEquals(StvnIcons.STVN_CAS, provider.getIcon(file1, Iconable.ICON_FLAG_VISIBILITY));
    assertEquals(StvnIcons.STVN_CAS, provider.getIcon(file2, Iconable.ICON_FLAG_VISIBILITY));
    assertEquals(StvnIcons.STVN_CAS, provider.getIcon(file3, Iconable.ICON_FLAG_VISIBILITY));
  }

  public void testStandardUserDocumentsReturnNullForFallback() {
    var file1 = myFixture.configureByText("main.stvn", "{ :type :Int :body 1 }");
    var file2 = myFixture.configureByText("schema.stvn_d", "{ :defs {} }");
    var file3 = myFixture.configureByText("fragment.stvn_i", "{ :type :Int :body 1 }");
    var file4 = myFixture.configureByText("sub_schema.stvn_di", "{ :defs {} }");

    assertNull("Standard .stvn document must return null to allow FileType fallback", provider.getIcon(file1, 0));
    assertNull("Standard .stvn_d schema must return null to allow FileType fallback", provider.getIcon(file2, 0));
    assertNull("Standard .stvn_i fragment must return null to allow FileType fallback", provider.getIcon(file3, 0));
    assertNull("Standard .stvn_di schema must return null to allow FileType fallback", provider.getIcon(file4, 0));
  }

  public void testUnrelatedFileWithSubstrNameDoesNotMatchEnclave() {
    var file = myFixture.configureByText("stvn_iron_ore.stvn", "{ :type :Int :body 1 }");
    assertNull("File lacking dot boundaries must not trigger enclave override", provider.getIcon(file, 0));
  }
}
