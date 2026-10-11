package org.stvnadore.plugin.icons;

import com.intellij.ide.IconProvider;
import com.intellij.openapi.util.Iconable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Dynamic icon provider providing zero-IO, pattern-based presentation overrides
 * for STVN system substrate enclaves (*.stvn_ir.* and *.stvn_cas.*).
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnIconProvider extends IconProvider {

  @Override
  public @Nullable Icon getIcon(@NotNull PsiElement element, @Iconable.IconFlags int flags) {
    if (!(element instanceof PsiFile file)) {
      return null;
    }
    String name = file.getName();
    if (name.contains(".stvn_ir.") || name.endsWith(".stvn_ir")) {
      return StvnIcons.STVN_IR;
    }
    if (name.contains(".stvn_cas.") || name.endsWith(".stvn_cas")) {
      return StvnIcons.STVN_CAS;
    }
    return null;
  }
}
