package org.stvnadore.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jspecify.annotations.NullMarked;
import javax.swing.Icon;
import org.stvnadore.plugin.icons.StvnIcons;

/**
 * Base FileType representation for STVN documents, defining sub-types for
 * payload, standard includes, and flat includes documents.
 */
@NullMarked
public abstract class StvnFileType extends LanguageFileType {

    /**
     * Constructs a base STVN LanguageFileType instance.
     */
    protected StvnFileType() {
        super(StvnLanguage.INSTANCE);
    }

    /**
     * File type representation for standard STVN payload documents (.stvn).
     */
    @NullMarked
    public static final class Payload extends StvnFileType {
        /**
         * Singleton instance representing standard STVN payload documents.
         */
        public static final Payload INSTANCE = new Payload();

        private Payload() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_PAYLOAD";
        }

        @Override
        public String getDisplayName() {
            return "STVN Payload";
        }

        @Override
        public String getDescription() {
            return "STVN Standard Payload Document";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN;
        }
    }

    /**
     * File type representation for canonical STVN definitions schemas (.stvn_d).
     */
    @NullMarked
    public static final class Defs extends StvnFileType {
        /**
         * Singleton instance representing STVN definitions schemas.
         */
        public static final Defs INSTANCE = new Defs();

        private Defs() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_DEFS";
        }

        @Override
        public String getDisplayName() {
            return "STVN Definitions Schema";
        }

        @Override
        public String getDescription() {
            return "STVN Canonical Definitions Schema";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn_d";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN_DEFS;
        }
    }

    /**
     * File type representation for modular STVN definitions schemas (.stvn_di).
     */
    @NullMarked
    public static final class DefsInclude extends StvnFileType {
        /**
         * Singleton instance representing modular STVN definitions schemas.
         */
        public static final DefsInclude INSTANCE = new DefsInclude();

        private DefsInclude() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_DEFS_INCLUDE";
        }

        @Override
        public String getDisplayName() {
            return "STVN Modular Definitions Schema";
        }

        @Override
        public String getDescription() {
            return "STVN Modular Definitions Schema Module";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn_di";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN_DEFS_INCL;
        }
    }

    /**
     * File type representation for modular STVN payload documents (.stvn_i).
     */
    @NullMarked
    public static final class PayloadInclude extends StvnFileType {
        /**
         * Singleton instance representing modular STVN payload documents.
         */
        public static final PayloadInclude INSTANCE = new PayloadInclude();

        private PayloadInclude() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_PAYLOAD_INCLUDE";
        }

        @Override
        public String getDisplayName() {
            return "STVN Modular Payload";
        }

        @Override
        public String getDescription() {
            return "STVN Modular Payload Document";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn_i";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN_PAYLOAD_INCL;
        }
    }

    /**
     * File type representation for transitional STVN includes modules (.stvn_incl).
     */
    @NullMarked
    public static final class Incl extends StvnFileType {
        /**
         * Singleton instance representing standard STVN include modules.
         */
        public static final Incl INSTANCE = new Incl();

        private Incl() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_INCL";
        }

        @Override
        public String getDisplayName() {
            return "STVN Include";
        }

        @Override
        public String getDescription() {
            return "STVN Includes Module";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn_incl";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN_INCL;
        }
    }

    /**
     * File type representation for flat STVN includes modules (.stvn_inclf).
     */
    @NullMarked
    public static final class Inclf extends StvnFileType {
        /**
         * Singleton instance representing flat STVN include modules.
         */
        public static final Inclf INSTANCE = new Inclf();

        private Inclf() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_INCLF";
        }

        @Override
        public String getDisplayName() {
            return "STVN Flat Include";
        }

        @Override
        public String getDescription() {
            return "STVN Flat Includes Module";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn_inclf";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN_INCLF;
        }
    }

    /**
     * File type representation for flat STVN payload documents (.stvn_f).
     */
    @NullMarked
    public static final class FlatPayload extends StvnFileType {
        /**
         * Singleton instance representing flat STVN payload documents.
         */
        public static final FlatPayload INSTANCE = new FlatPayload();

        private FlatPayload() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_FLAT_PAYLOAD";
        }

        @Override
        public String getDisplayName() {
            return "STVN Flat Payload";
        }

        @Override
        public String getDescription() {
            return "STVN Flat Payload Document";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn_f";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN_F;
        }
    }

    /**
     * File type representation for STVN Content-Addressed Storage documents (.stvn_cas).
     */
    @NullMarked
    public static final class Cas extends StvnFileType {
        /**
         * Singleton instance representing STVN CAS documents.
         */
        public static final Cas INSTANCE = new Cas();

        private Cas() {
            super();
        }

        @Override
        public String getName() {
            return "STVN_CAS";
        }

        @Override
        public String getDisplayName() {
            return "STVN CAS Payload";
        }

        @Override
        public String getDescription() {
            return "STVN Content-Addressed Storage Document";
        }

        @Override
        public String getDefaultExtension() {
            return "stvn_cas";
        }

        @Override
        public Icon getIcon() {
            return StvnIcons.STVN_CAS;
        }
    }
}
