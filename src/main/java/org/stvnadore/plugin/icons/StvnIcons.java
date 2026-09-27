package org.stvnadore.plugin.icons;

import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
import org.jspecify.annotations.NullMarked;

/**
 * Authoritative static icon repository for STVN file types and UI elements.
 */
@NullMarked
public final class StvnIcons {

  /** Icon for standard STVN payload documents (.stvn). */
  public static final Icon STVN = IconLoader.getIcon("/icons/stvn_file.svg", StvnIcons.class);

  /** Icon for flat STVN payload documents (.stvn_f). */
  public static final Icon STVN_F = IconLoader.getIcon("/icons/stvn_f_file.svg", StvnIcons.class);

  /** Icon for STVN include modules (.stvn_incl). */
  public static final Icon STVN_INCL = IconLoader.getIcon("/icons/stvn_incl_file.svg", StvnIcons.class);

  /** Icon for flat STVN include modules (.stvn_inclf). */
  public static final Icon STVN_INCLF = IconLoader.getIcon("/icons/stvn_inclf_file.svg", StvnIcons.class);

  /** Icon for binary STVN payload documents (.stvn_bin). */
  public static final Icon STVN_BIN = IconLoader.getIcon("/icons/stvn_bin_file.svg", StvnIcons.class);

  /** Icon for STVN Content-Addressed Storage documents (.stvn_cas). */
  public static final Icon STVN_CAS = IconLoader.getIcon("/icons/stvn_cas_file.svg", StvnIcons.class);

  /** Icon for STVN intermediate representation documents (.stvn_ir). */
  public static final Icon STVN_IR = IconLoader.getIcon("/icons/stvn_ir_file.svg", StvnIcons.class);

  private StvnIcons() {
    // Prevent instantiation
  }
}