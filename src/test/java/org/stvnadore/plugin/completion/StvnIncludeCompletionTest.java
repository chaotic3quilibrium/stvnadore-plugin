package org.stvnadore.plugin.completion;

import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.IncludeStmt;
import org.stvnadore.psi.StringLiteral;

/**
 * Platform test suite verifying relative include path completion, quote wrapping,
 * directory traversal, self-exclusion, and file rename refactoring.
 */
@NullMarked
public final class StvnIncludeCompletionTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    /**
     * Verifies that inside quotes (:include [ "<caret>" ]), completion proposes sibling schema files
     * and strictly excludes payload files (.stvn, .stvn_i) and the active file itself.
     */
    public void testInsideQuotesSiblingSchemaCompletion() {
        myFixture.addFileToProject(
            "events.stvn_di",
            "{\n  :meta { #name \"events\" #domain \"test\" #kind #DEFS_INCLUDE }\n  :defs { :Event :Int }\n}"
        );
        myFixture.addFileToProject(
            "models.stvn_d",
            "{\n  :meta { #name \"models\" #domain \"test\" #kind #DEFS }\n  :defs { :Model :String }\n}"
        );
        myFixture.addFileToProject("payload.stvn", "{\n  :type :Int\n  :body 1\n}");
        myFixture.addFileToProject(
            "modular_payload.stvn_i",
            "{\n  :meta { #name \"p\" #domain \"d\" #kind #BODY_INCLUDE }\n  :type :Int\n  :body 1\n}"
        );

        myFixture.configureByText(
            "active.stvn_di",
            """
            {
              :meta { #name "active" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "<caret>" ]
              }
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must not be null", lookupStrings);

        assertTrue("Must propose sibling modular schema events.stvn_di", lookupStrings.contains("events.stvn_di"));
        assertTrue("Must propose sibling hermetic schema models.stvn_d", lookupStrings.contains("models.stvn_d"));
        assertFalse("Must filter out flat payload document payload.stvn", lookupStrings.contains("payload.stvn"));
        assertFalse("Must filter out modular payload modular_payload.stvn_i", lookupStrings.contains("modular_payload.stvn_i"));
        assertFalse("Must strictly exclude active containing file active.stvn_di (Self-Exclusion Invariant)", lookupStrings.contains("active.stvn_di"));
    }

    /**
     * Verifies that in bare brackets without quotes (:include [ <caret> ]), selecting
     * a candidate file automatically encloses the inserted path in double quotes.
     */
    public void testBareBracketCompletionInsertsQuotes() {
        myFixture.addFileToProject(
            "events.stvn_di",
            "{\n  :meta { #name \"events\" #domain \"test\" #kind #DEFS_INCLUDE }\n  :defs { :Event :Int }\n}"
        );
        myFixture.addFileToProject(
            "models.stvn_d",
            "{\n  :meta { #name \"models\" #domain \"test\" #kind #DEFS }\n  :defs { :Model :String }\n}"
        );

        myFixture.configureByText(
            "active.stvn_di",
            """
            {
              :meta { #name "active" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ <caret> ]
              }
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must not be null", lookupStrings);
        assertTrue("Must propose events.stvn_di in bare brackets", lookupStrings.contains("events.stvn_di"));

        var lookup = myFixture.getLookup();
        if (lookup != null) {
            for (var item : lookup.getItems()) {
                if ("events.stvn_di".equals(item.getLookupString())) {
                    lookup.setCurrentItem(item);
                    break;
                }
            }
        }

        myFixture.type('\n');
        myFixture.checkResult(
            """
            {
              :meta { #name "active" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "events.stvn_di" ]
              }
            }
            """
        );
    }

    /**
     * Verifies prefix matching and quote wrapping in bare brackets (:include [ ev<caret> ]).
     */
    public void testBareBracketPrefixMatching() {
        myFixture.addFileToProject(
            "events.stvn_di",
            "{\n  :meta { #name \"events\" #domain \"test\" #kind #DEFS_INCLUDE }\n  :defs { :Event :Int }\n}"
        );
        myFixture.addFileToProject(
            "event_types.stvn_di",
            "{\n  :meta { #name \"event_types\" #domain \"test\" #kind #DEFS_INCLUDE }\n  :defs { :EventType :String }\n}"
        );
        myFixture.addFileToProject(
            "models.stvn_d",
            "{\n  :meta { #name \"models\" #domain \"test\" #kind #DEFS }\n  :defs { :Model :String }\n}"
        );

        myFixture.configureByText(
            "active.stvn_di",
            """
            {
              :meta { #name "active" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ ev<caret> ]
              }
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must not be null", lookupStrings);
        assertTrue("Must match events.stvn_di with prefix 'ev'", lookupStrings.contains("events.stvn_di"));
        assertTrue("Must match event_types.stvn_di with prefix 'ev'", lookupStrings.contains("event_types.stvn_di"));
        assertFalse("Must not match models.stvn_d with prefix 'ev'", lookupStrings.contains("models.stvn_d"));

        var lookup = myFixture.getLookup();
        if (lookup != null) {
            for (var item : lookup.getItems()) {
                if ("events.stvn_di".equals(item.getLookupString())) {
                    lookup.setCurrentItem(item);
                    break;
                }
            }
        }

        myFixture.type('\n');
        myFixture.checkResult(
            """
            {
              :meta { #name "active" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "events.stvn_di" ]
              }
            }
            """
        );
    }

    /**
     * Verifies parent directory traversal and subdirectory navigation (../../shared/schemas/network.stvn_di).
     */
    public void testRelativeParentPathNavigation() {
        var targetFile = myFixture.addFileToProject(
            "shared/schemas/network.stvn_di",
            "{\n  :meta { #name \"network\" #domain \"test\" #kind #DEFS_INCLUDE }\n  :defs { :Port :Int }\n}"
        );

        var activeFile = myFixture.addFileToProject(
            "services/auth/auth_service.stvn_di",
            """
            {
              :meta { #name "auth" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "../../shared/schemas/network.stvn_di" ]
              }
            }
            """
        );

        myFixture.openFileInEditor(activeFile.getVirtualFile());
        var includeStmt = PsiTreeUtil.findChildOfType(activeFile, IncludeStmt.class);
        assertNotNull("IncludeStmt element must exist", includeStmt);

        var stringLit = PsiTreeUtil.findChildOfType(includeStmt, StringLiteral.class);
        assertNotNull("StringLiteral element must exist", stringLit);

        var references = stringLit.getReferences();
        assertTrue("Must generate multiple references for path segments", references.length >= 3);

        var terminalReference = references[references.length - 1];
        var resolved = terminalReference.resolve();
        assertNotNull("Terminal reference must resolve to physical file", resolved);
        assertTrue("Resolved target must be PsiFileSystemItem", resolved instanceof PsiFileSystemItem);
        assertEquals(targetFile.getVirtualFile(), ((PsiFileSystemItem) resolved).getVirtualFile());
    }

    /**
     * Verifies that standard build and VCS directories are strictly excluded from completion.
     */
    public void testIgnoredDirectoriesExclusion() {
        myFixture.addFileToProject("target/generated.stvn_di", "{ :defs { :Gen :Int } }");
        myFixture.addFileToProject("build/out.stvn_di", "{ :defs { :Out :Int } }");
        myFixture.addFileToProject(".git/config.stvn_di", "{ :defs { :Git :Int } }");

        myFixture.configureByText(
            "active.stvn_di",
            """
            {
              :meta { #name "active" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "<caret>" ]
              }
            }
            """
        );

        myFixture.completeBasic();
        var lookupStrings = myFixture.getLookupElementStrings();
        assertNotNull("Lookup strings must not be null", lookupStrings);

        assertFalse("Must exclude target/ directory", lookupStrings.contains("target/"));
        assertFalse("Must exclude build/ directory", lookupStrings.contains("build/"));
        assertFalse("Must exclude .git/ directory", lookupStrings.contains(".git/"));
    }

    /**
     * Verifies that renaming an included schema file via Rename refactoring
     * updates the referencing include string literal automatically.
     */
    public void testRenameRefactoringUpdatesIncludeString() {
        var targetFile = myFixture.addFileToProject(
            "legacy_models.stvn_di",
            "{\n  :meta { #name \"legacy\" #domain \"test\" #kind #DEFS_INCLUDE }\n  :defs { :Old :Int }\n}"
        );

        myFixture.configureByText(
            "consumer.stvn_di",
            """
            {
              :meta { #name "consumer" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "legacy_models.stvn_di" ]
              }
            }
            """
        );

        myFixture.renameElement(targetFile, "modern_models.stvn_di");
        myFixture.checkResult(
            """
            {
              :meta { #name "consumer" #domain "test" #kind #DEFS_INCLUDE }
              :defs {
                :include [ "modern_models.stvn_di" ]
              }
            }
            """
        );
    }
}
