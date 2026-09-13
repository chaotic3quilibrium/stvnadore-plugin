package org.stvnadore.plugin.validation;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.reference.StvnTypeReference;
import org.stvnadore.psi.TypeKeyword;

import java.util.HashSet;

/**
 * Platform test suite verifying the 5 new local inspections introduced in the defs overhaul:
 * - StvnLhsReservedTypeInspection
 * - StvnConstantRangeInspection
 * - StvnFlatDocumentIncludeInspection
 * - StvnNestedPackageInspection
 * - StvnTrailingSlashInspection
 */
@NullMarked
public final class StvnDefsOverhaulInspectionsTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(
            new StvnLhsReservedTypeInspection(),
            new StvnConstantRangeInspection(),
            new StvnFlatDocumentIncludeInspection(),
            new StvnNestedPackageInspection(),
            new StvnTrailingSlashInspection()
        );
    }

    public void testLhsReservedTypeInspection() {
        myFixture.configureByText("test_lhs_err.stvn",
            """
            {
              :defs {
                :Int32 :String
              }
              :type :String
              :body "hello"
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("cannot be aliased"))
            .toList();
        assertFalse("Expected LHS reserved type inspection error", errors.isEmpty());
        assertTrue(errors.get(0).getDescription().contains("Fundamental type ':Int32' cannot be aliased"));

        myFixture.configureByText("test_lhs_valid.stvn",
            """
            {
              :defs {
                :ValidType :String
              }
              :type :ValidType
              :body "hello"
            }
            """
        );
        var validHighlights = myFixture.doHighlighting();
        var validErrors = validHighlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("cannot be aliased"))
            .toList();
        assertTrue("Expected no LHS reserved type error for valid alias", validErrors.isEmpty());
    }

    public void testConstantRangeInspection() {
        myFixture.configureByText("test_const_overflow.stvn",
            """
            {
              :defs {
                #OVERFLOW :Int8 300
                #VALID :Int8 100
              }
              :type :Int8
              :body 100
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("out of range"))
            .toList();
        assertTrue("Expected constant integer overflow error", errors.size() >= 1);
        assertTrue(errors.stream().anyMatch(e -> e.getDescription().contains("Integer literal 300 out of range for :Int8 [-128, 127]")));
    }

    public void testFlatDocumentIncludeInspection() {
        myFixture.configureByText("test_flat.stvn_f",
            """
            {
              :defs {
                :include [ "foo.stvn_incl" ]
              }
              :type :String
              :body "data"
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Flat document or leaf module"))
            .toList();
        assertEquals("Expected include in flat document to be flagged", 1, errors.size());
        assertTrue(errors.get(0).getDescription().contains("Flat document or leaf module (.stvn_f / .stvn_inclf) cannot contain include statements"));
    }

    public void testNestedPackageInspection() {
        myFixture.configureByText("test_nested_pkg.stvn",
            """
            {
              :defs {
                :package :com/example/outer {
                  :package :inner {
                    :InnerType :String
                  }
                }
              }
              :type :String
              :body "hello"
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Nested packages are prohibited"))
            .toList();
        assertFalse("Expected nested package to be flagged", errors.isEmpty());
        assertTrue(errors.get(0).getDescription().contains("Nested packages are prohibited"));
    }

    public void testTrailingSlashInspection() {
        myFixture.configureByText("test_trailing_slash.stvn",
            """
            {
              :defs {
                :use [ :com/example/util/ ]
              }
              :type :String
              :body "hello"
            }
            """
        );
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Trailing slash prohibited"))
            .toList();
        assertFalse("Expected trailing slash in :use target to be flagged", errors.isEmpty());
        assertTrue(errors.get(0).getDescription().contains("Trailing slash prohibited in :use target"));
    }

    public void testPackageFqniAndPreludeResolution() {
        var file = myFixture.configureByText("test_pkg_resolution.stvn",
            """
            {
              :defs {
                :package :com/example/model {
                  :User :String
                }
                :use [ :com/example/model { #strip } ]
              }
              :type :com/example/model/User
              :body "alice"
            }
            """
        );

        // 1. Resolve FQNI
        var resolvedFqni = StvnTypeReference.resolveTypeInFile(file, ":com/example/model/User", new HashSet<>());
        assertNotNull("Expected :com/example/model/User to resolve to definition", resolvedFqni);
        assertTrue(resolvedFqni instanceof TypeKeyword);

        // 2. Resolve stripped name via :use
        var resolvedStripped = StvnTypeReference.resolveTypeInFile(file, ":User", new HashSet<>());
        assertNotNull("Expected :User to resolve via stripped :use", resolvedStripped);
        assertTrue(resolvedStripped instanceof TypeKeyword);

        // 3. Resolve canonical prelude type
        var resolvedPrelude = StvnTypeReference.resolveTypeInFile(file, ":org/stvnadore/prelude/TimeEpochS", new HashSet<>());
        assertNotNull("Expected canonical prelude type to resolve", resolvedPrelude);
        assertTrue(resolvedPrelude instanceof TypeKeyword);
    }
}
