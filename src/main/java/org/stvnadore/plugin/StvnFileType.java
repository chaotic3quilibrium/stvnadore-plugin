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

    protected StvnFileType() {
        super(StvnLanguage.INSTANCE);
    }

    /**
     * File type representation for standard STVN payload documents (.stvn).
     */
    @NullMarked
    public static final class Payload extends StvnFileType {
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
            return StvnIcons.FILE;
        }
    }

    /**
     * File type representation for standard STVN includes modules (.stvn_incl).
     */
    @NullMarked
    public static final class Incl extends StvnFileType {
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
            return StvnIcons.FILE;
        }
    }

    /**
     * File type representation for flat STVN includes modules (.stvn_inclf).
     */
    @NullMarked
    public static final class Inclf extends StvnFileType {
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
            return StvnIcons.FILE;
        }
    }
}
