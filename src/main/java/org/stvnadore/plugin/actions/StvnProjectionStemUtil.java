package org.stvnadore.plugin.actions;

import org.jspecify.annotations.NullMarked;

import java.util.Optional;

/**
 * Pure-Java filename classification and sibling stem derivation utility for STVN 2.0.0 VOP projections.
 * <p>
 * Maintains zero {@code com.intellij.*} imports so headless Gradle {@code :unitTest} suites can verify
 * 1-stem ({@code foo.<ext>}) and 2-stem ({@code bar.dom.<ext>}) derivation rules under Rule {@code E032}.
 * </p>
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnProjectionStemUtil {

    /** Canonical file suffix for hermetic payload documents ({@code .stvn}). */
    public static final String EXT_STVN = ".stvn";

    /** Canonical file suffix for modular payload documents ({@code .stvn_i}). */
    public static final String EXT_STVN_I = ".stvn_i";

    /** Canonical file suffix for hermetic definitions schemas ({@code .stvn_d}). */
    public static final String EXT_STVN_D = ".stvn_d";

    /** Canonical file suffix for modular definitions schemas ({@code .stvn_di}). */
    public static final String EXT_STVN_DI = ".stvn_di";

    /** Canonical file suffix for binary payload documents ({@code .stvn_b}). */
    public static final String EXT_STVN_B = ".stvn_b";

    /** Canonical file suffix for binary definitions archives ({@code .stvn_bd}). */
    public static final String EXT_STVN_BD = ".stvn_bd";

    /**
     * Enumerates the four textual STVN 2.0.0 compilation unit extensions.
     *
     * @since 2.0.0
     */
    public enum StvnTextExtension {
        /** Hermetic payload document ({@code .stvn}). */
        STVN,
        /** Modular payload include document ({@code .stvn_i}). */
        STVN_I,
        /** Hermetic definitions schema ({@code .stvn_d}). */
        STVN_D,
        /** Modular definitions include schema ({@code .stvn_di}). */
        STVN_DI
    }

    private StvnProjectionStemUtil() {
    }

    /**
     * Classifies {@code fileName} into one of the four textual STVN extensions when matched.
     *
     * @param fileName candidate file name
     * @return the matching {@link StvnTextExtension}, or {@link Optional#empty()} for binary or non-STVN files
     */
    public static Optional<StvnTextExtension> classifyTextualExtension(String fileName) {
        if (fileName.endsWith(EXT_STVN_DI)) {
            return Optional.of(StvnTextExtension.STVN_DI);
        }
        if (fileName.endsWith(EXT_STVN_D)) {
            return Optional.of(StvnTextExtension.STVN_D);
        }
        if (fileName.endsWith(EXT_STVN_I)) {
            return Optional.of(StvnTextExtension.STVN_I);
        }
        if (fileName.endsWith(EXT_STVN)) {
            return Optional.of(StvnTextExtension.STVN);
        }
        return Optional.empty();
    }

    /**
     * Checks whether {@code fileName} carries a textual STVN extension ({@code .stvn}, {@code .stvn_i},
     * {@code .stvn_d}, or {@code .stvn_di}).
     *
     * @param fileName candidate file name
     * @return {@code true} if {@code fileName} is a textual STVN unit
     */
    public static boolean isTextualStvnFileName(String fileName) {
        return classifyTextualExtension(fileName).isPresent();
    }

    /**
     * Checks whether {@code fileName} carries a binary STVN extension ({@code .stvn_b} or {@code .stvn_bd}).
     *
     * @param fileName candidate file name
     * @return {@code true} if {@code fileName} is a binary STVN archive
     */
    public static boolean isBinaryStvnFileName(String fileName) {
        return fileName.endsWith(EXT_STVN_BD) || fileName.endsWith(EXT_STVN_B);
    }

    /**
     * Checks whether {@code fileName} represents a payload-bearing textual STVN unit ({@code .stvn} or {@code .stvn_i}).
     *
     * @param fileName candidate file name
     * @return {@code true} if hermetic {@code .stvn} payload extraction is structurally applicable
     */
    public static boolean supportsPayloadExtraction(String fileName) {
        return classifyTextualExtension(fileName)
            .map(ext -> ext == StvnTextExtension.STVN || ext == StvnTextExtension.STVN_I)
            .orElse(false);
    }

    /**
     * Checks whether exporting a sibling {@code .stvn_d} file would overwrite {@code fileName} in place.
     *
     * @param fileName candidate file name
     * @return {@code true} when {@code fileName} already ends with {@code .stvn_d}
     */
    public static boolean isSchemaSiblingSelfOverwrite(String fileName) {
        return classifyTextualExtension(fileName)
            .map(ext -> ext == StvnTextExtension.STVN_D)
            .orElse(true);
    }

    /**
     * Checks whether exporting a sibling {@code .stvn} file would overwrite {@code fileName} in place
     * or is structurally prohibited.
     *
     * @param fileName candidate file name
     * @return {@code true} unless {@code fileName} ends with {@code .stvn_i}
     */
    public static boolean isPayloadSiblingSelfOverwriteOrUnsupported(String fileName) {
        return classifyTextualExtension(fileName)
            .map(ext -> ext != StvnTextExtension.STVN_I)
            .orElse(true);
    }

    /**
     * Maps 1-stem ({@code foo.<ext>}) and 2-stem ({@code bar.dom.<ext>}) textual STVN filenames to
     * their corresponding hermetic {@code .stvn_d} schema filename under Rule {@code E032}.
     *
     * @param fileName active textual STVN filename
     * @return derived {@code <stem>.stvn_d} filename
     */
    public static String deriveDefsStemName(String fileName) {
        return stripTextualExtension(fileName)
            .map(stem -> stem + EXT_STVN_D)
            .orElse(fileName);
    }

    /**
     * Maps 1-stem ({@code foo.stvn_i}) and 2-stem ({@code bar.dom.stvn_i}) textual STVN filenames to
     * their corresponding hermetic {@code .stvn} payload filename under Rule {@code E032}.
     *
     * @param fileName active textual STVN filename
     * @return derived {@code <stem>.stvn} filename
     */
    public static String derivePayloadStemName(String fileName) {
        return stripTextualExtension(fileName)
            .map(stem -> stem + EXT_STVN)
            .orElse(fileName);
    }

    private static Optional<String> stripTextualExtension(String fileName) {
        if (fileName.endsWith(EXT_STVN_DI)) {
            return Optional.of(fileName.substring(0, fileName.length() - EXT_STVN_DI.length()));
        }
        if (fileName.endsWith(EXT_STVN_D)) {
            return Optional.of(fileName.substring(0, fileName.length() - EXT_STVN_D.length()));
        }
        if (fileName.endsWith(EXT_STVN_I)) {
            return Optional.of(fileName.substring(0, fileName.length() - EXT_STVN_I.length()));
        }
        if (fileName.endsWith(EXT_STVN)) {
            return Optional.of(fileName.substring(0, fileName.length() - EXT_STVN.length()));
        }
        return Optional.empty();
    }
}
