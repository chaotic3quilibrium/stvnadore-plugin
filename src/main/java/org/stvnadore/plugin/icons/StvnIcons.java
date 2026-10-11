package org.stvnadore.plugin.icons;

import com.intellij.openapi.util.IconLoader;
import javax.swing.Icon;
import org.jspecify.annotations.NullMarked;

/**
 * Authoritative static icon repository for STVN file types and UI elements.
 */
@NullMarked
public final class StvnIcons {

  // ============================================================================
  // 1. CANONICAL STVN 2.0.0 FILE TYPE ICONS
  // ============================================================================

  /** Icon for canonical textual STVN payload documents (.stvn). */
  public static final Icon STVN = IconLoader.getIcon("/icons/stvn_file.svg", StvnIcons.class);

  /** Icon for canonical textual definitions schemas (.stvn_d). */
  public static final Icon STVN_DEFS = IconLoader.getIcon("/icons/stvn_defs_file.svg", StvnIcons.class);

  /** Icon for modular textual STVN payload documents (.stvn_i). */
  public static final Icon STVN_PAYLOAD_INCL = IconLoader.getIcon("/icons/stvn_payload_incl_file.svg", StvnIcons.class);

  /** Icon for modular textual definitions schemas (.stvn_di). */
  public static final Icon STVN_DEFS_INCL = IconLoader.getIcon("/icons/stvn_defs_incl_file.svg", StvnIcons.class);

  /** Icon for canonical binary STVN payload documents (.stvn_b). */
  public static final Icon STVN_BIN = IconLoader.getIcon("/icons/stvn_bin_file.svg", StvnIcons.class);

  /** Icon for canonical binary definitions schemas (.stvn_bd). */
  public static final Icon STVN_BD = IconLoader.getIcon("/icons/stvn_bin_defs_file.svg", StvnIcons.class);

  // ============================================================================
  // 2. SYSTEM SUBSTRATE ENCLAVE ICONS (*.stvn_ir.* and *.stvn_cas.*)
  // ============================================================================

  /** Icon for STVN intermediate representation substrate documents (*.stvn_ir.*). */
  public static final Icon STVN_IR = IconLoader.getIcon("/icons/stvn_ir_file.svg", StvnIcons.class);

  /** Icon for STVN Content-Addressed Storage substrate documents (*.stvn_cas.*). */
  public static final Icon STVN_CAS = IconLoader.getIcon("/icons/stvn_cas_file.svg", StvnIcons.class);

  // ============================================================================
  // 3. TRANSITIONAL COMPATIBILITY SHIMS (DEPRECATED FOR REMOVAL)
  // ============================================================================

  /** @deprecated Replaced by canonical {@link #STVN} in STVN 2.0.0. */
  @Deprecated(forRemoval = true)
  public static final Icon STVN_F = STVN;

  /** @deprecated Replaced by modular {@link #STVN_PAYLOAD_INCL} in STVN 2.0.0. */
  @Deprecated(forRemoval = true)
  public static final Icon STVN_INCL = STVN_PAYLOAD_INCL;

  /** @deprecated Replaced by modular {@link #STVN_DEFS_INCL} in STVN 2.0.0. */
  @Deprecated(forRemoval = true)
  public static final Icon STVN_INCLF = STVN_DEFS_INCL;

  private StvnIcons() {
    // Prevent instantiation
  }
}
