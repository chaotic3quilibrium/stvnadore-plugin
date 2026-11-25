package org.stvnadore.plugin.browser;

import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.ListTableModel;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Standardized 5-column table model ({@code Name | Source | Upstream Target | Resolved Type | Use Depth})
 * providing multi-column sorting and speed-search support for {@code STVN Namespace Dependencies}.
 */
@NullMarked
public final class StvnNamespaceTableModel extends ListTableModel<StvnNamespaceSymbolEntry> {

    /** Column 0 index ({@code Name}). */
    public static final int COL_NAME = 0;

    /** Column 1 index ({@code Source}). */
    public static final int COL_SOURCE = 1;

    /** Column 2 index ({@code Upstream Target}). */
    public static final int COL_UPSTREAM_TARGET = 2;

    /** Column 3 index ({@code Resolved Type}). */
    public static final int COL_RESOLVED_TYPE = 3;

    /** Column 4 index ({@code Use Depth}). */
    public static final int COL_USE_DEPTH = 4;

    private static final ColumnInfo<StvnNamespaceSymbolEntry, String> NAME_COLUMN =
        new ColumnInfo<>("Name") {
            @Override
            public String valueOf(StvnNamespaceSymbolEntry item) {
                return item.name();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparing(StvnNamespaceSymbolEntry::name, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }
        };

    private static final ColumnInfo<StvnNamespaceSymbolEntry, String> SOURCE_COLUMN =
        new ColumnInfo<>("Source") {
            @Override
            public String valueOf(StvnNamespaceSymbolEntry item) {
                return item.source();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparing(StvnNamespaceSymbolEntry::source, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }
        };

    private static final ColumnInfo<StvnNamespaceSymbolEntry, String> UPSTREAM_TARGET_COLUMN =
        new ColumnInfo<>("Upstream Target") {
            @Override
            public String valueOf(StvnNamespaceSymbolEntry item) {
                return item.upstreamTarget();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparing(StvnNamespaceSymbolEntry::upstreamTarget, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }
        };

    private static final ColumnInfo<StvnNamespaceSymbolEntry, String> RESOLVED_TYPE_COLUMN =
        new ColumnInfo<>("Resolved Type") {
            @Override
            public String valueOf(StvnNamespaceSymbolEntry item) {
                return item.resolvedType();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparing(StvnNamespaceSymbolEntry::resolvedType, String.CASE_INSENSITIVE_ORDER);
            }

            @Override
            public Class<?> getColumnClass() {
                return String.class;
            }
        };

    private static final ColumnInfo<StvnNamespaceSymbolEntry, Integer> DEPTH_COLUMN =
        new ColumnInfo<>("Use Depth") {
            @Override
            public Integer valueOf(StvnNamespaceSymbolEntry item) {
                return item.useDepth();
            }

            @Override
            public @Nullable Comparator<StvnNamespaceSymbolEntry> getComparator() {
                return Comparator.comparingInt(StvnNamespaceSymbolEntry::useDepth);
            }

            @Override
            public Class<?> getColumnClass() {
                return Integer.class;
            }
        };

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final ColumnInfo<StvnNamespaceSymbolEntry, ?>[] COLUMNS = new ColumnInfo[] {
        NAME_COLUMN,
        SOURCE_COLUMN,
        UPSTREAM_TARGET_COLUMN,
        RESOLVED_TYPE_COLUMN,
        DEPTH_COLUMN
    };

    /**
     * Constructs a new {@code StvnNamespaceTableModel} with the supplied symbol rows.
     *
     * @param items initial list of symbol entries
     */
    public StvnNamespaceTableModel(List<StvnNamespaceSymbolEntry> items) {
        super(COLUMNS, items, 0);
    }
}
