package org.stvnadore.plugin.refactoring;

import com.intellij.psi.PsiNamedElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.TypeDefinition;

/**
 * Automated tests verifying Shift+F6 rename refactoring operations,
 * caret anchoring, cross-file propagation, and name validation guards.
 */
@NullMarked
public final class StvnRenameRefactoringTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures/refactoring";
    }

    /**
     * Verifies that renaming a local TypeDefinition in the :defs block
     * updates all downstream references in the :type and :body sections.
     */
    public void testLocalTypeDefinitionRenamePropagatesToReferences() {
        var beforeCode = """
            {
              :defs {
                :AccountHolder<caret> :String
              }
              :type :AccountHolder
              :body "Alice"
            }
            """;
        myFixture.configureByText("local_rename.stvn", beforeCode);
        myFixture.renameElementAtCaret(":CustomerName");

        var expected = """
            {
              :defs {
                :CustomerName :String
              }
              :type :CustomerName
              :body "Alice"
            }
            """;
        myFixture.checkResult(expected);
    }

    /**
     * Verifies that renaming an IncludeMapAlias on its RHS alias keyword
     * propagates to all local references in the consuming file.
     */
    public void testIncludeMapAliasRenamePropagatesToReferences() {
        var moduleContent = """
            {
              :defs {
                :RemoteRecord :Tuple( :Int32 :String )
              }
            }
            """;
        myFixture.addFileToProject("rename_module.stvn_inclf", moduleContent);

        var beforeCode = """
            {
              :defs {
                :include [
                  "rename_module.stvn_inclf" {
                    :RemoteRecord :LocalRecord<caret>
                  }
                ]
              }
              :type :LocalRecord
              :body ( 100 "active" )
            }
            """;
        myFixture.configureByText("include_alias_rename.stvn", beforeCode);
        myFixture.renameElementAtCaret(":RenamedLocalRecord");

        var expected = """
            {
              :defs {
                :include [
                  "rename_module.stvn_inclf" {
                    :RemoteRecord :RenamedLocalRecord
                  }
                ]
              }
              :type :RenamedLocalRecord
              :body ( 100 "active" )
            }
            """;
        myFixture.checkResult(expected);
    }

    /**
     * Verifies that renaming a UseMapAlias inside a scoped :use statement
     * propagates to local references across the document.
     */
    public void testUseMapAliasRenamePropagatesToReferences() {
        var beforeCode = """
            {
              :defs {
                :package :org/stvnadore/finance {
                  :LedgerItem :Tuple( :Int32 :String )
                }
                :use [ :org/stvnadore/finance { :LedgerItem :ItemAlias<caret> } ]
              }
              :type :ItemAlias
              :body ( 42 "Credit" )
            }
            """;
        myFixture.configureByText("use_alias_rename.stvn", beforeCode);
        myFixture.renameElementAtCaret(":RenamedItem");

        var expected = """
            {
              :defs {
                :package :org/stvnadore/finance {
                  :LedgerItem :Tuple( :Int32 :String )
                }
                :use [ :org/stvnadore/finance { :LedgerItem :RenamedItem } ]
              }
              :type :RenamedItem
              :body ( 42 "Credit" )
            }
            """;
        myFixture.checkResult(expected);
    }

    /**
     * Verifies that invoking rename refactoring from a reference site in :type
     * renames the declaration in :defs and updates all reference occurrences.
     */
    public void testRenameFromReferenceSiteRenamesDeclarationAndAllUsages() {
        var beforeCode = """
            {
              :defs {
                :OriginalType :String
              }
              :type :OriginalType<caret>
              :body "Payload"
            }
            """;
        myFixture.configureByText("ref_rename.stvn", beforeCode);
        myFixture.renameElementAtCaret(":NewType");

        var expected = """
            {
              :defs {
                :NewType :String
              }
              :type :NewType
              :body "Payload"
            }
            """;
        myFixture.checkResult(expected);
    }

    /**
     * Verifies cross-file rename refactoring where renaming a type declaration
     * in a modular schema propagates to consumer files including the schema.
     */
    public void testCrossFileRenamePropagatesFromDeclarationToConsumers() {
        var moduleFile = myFixture.addFileToProject("shared_schema.stvn_inclf", """
            {
              :defs {
                :SharedRecord<caret> :Tuple( :Int32 :String )
              }
            }
            """);

        var consumerFile = myFixture.addFileToProject("consumer_payload.stvn", """
            {
              :defs {
                :include [
                  "shared_schema.stvn_inclf"
                ]
              }
              :type :SharedRecord
              :body ( 42 "Data" )
            }
            """);

        myFixture.configureFromExistingVirtualFile(moduleFile.getVirtualFile());
        myFixture.renameElementAtCaret(":RenamedSharedRecord");

        var expectedModule = """
            {
              :defs {
                :RenamedSharedRecord :Tuple( :Int32 :String )
              }
            }
            """;
        assertEquals(expectedModule.trim(), moduleFile.getText().trim());

        var expectedConsumer = """
            {
              :defs {
                :include [
                  "shared_schema.stvn_inclf"
                ]
              }
              :type :RenamedSharedRecord
              :body ( 42 "Data" )
            }
            """;
        assertEquals(expectedConsumer.trim(), consumerFile.getText().trim());
    }

    /**
     * Verifies that StvnNamesValidator strictly enforces identifier syntax rules
     * and rejects reserved section keywords, whitespace, leading digits, and illegal symbols.
     */
    public void testNamesValidatorEnforcesSyntaxAndRejectsReservedKeywords() {
        var validator = new StvnNamesValidator();

        // Valid colon type identifiers
        assertTrue(validator.isIdentifier(":ValidType", getProject()));
        assertTrue(validator.isIdentifier(":org/stvnadore/prelude/CustomType", getProject()));
        assertTrue(validator.isIdentifier(":Account123", getProject()));

        // Valid hash value identifiers
        assertTrue(validator.isIdentifier("#validConst", getProject()));
        assertTrue(validator.isIdentifier("#finance/taxRate", getProject()));
        assertTrue(validator.isIdentifier("#VariantTag", getProject()));

        // Invalid: missing leading symbol prefix
        assertFalse(validator.isIdentifier("MissingPrefix", getProject()));

        // Invalid: leading digit immediately following prefix
        assertFalse(validator.isIdentifier(":123BadDigit", getProject()));
        assertFalse(validator.isIdentifier("#123BadDigit", getProject()));

        // Invalid: whitespace
        assertFalse(validator.isIdentifier(":Has Space", getProject()));
        assertFalse(validator.isIdentifier("#Has Space", getProject()));

        // Invalid: illegal symbols
        assertFalse(validator.isIdentifier(":Has-Hyphen", getProject()));
        assertFalse(validator.isIdentifier(":Bad!Symbol", getProject()));
        assertFalse(validator.isIdentifier("#Bad@Symbol", getProject()));

        // Invalid: reserved section keywords
        assertFalse(validator.isIdentifier(":defs", getProject()));
        assertFalse(validator.isIdentifier(":type", getProject()));
        assertFalse(validator.isIdentifier(":body", getProject()));
        assertFalse(validator.isIdentifier(":package", getProject()));
        assertFalse(validator.isIdentifier(":use", getProject()));
        assertFalse(validator.isIdentifier(":include", getProject()));

        // Keyword checks
        assertTrue(validator.isKeyword(":defs", getProject()));
        assertTrue(validator.isKeyword(":type", getProject()));
        assertTrue(validator.isKeyword(":body", getProject()));
        assertTrue(validator.isKeyword(":package", getProject()));
        assertTrue(validator.isKeyword(":use", getProject()));
        assertTrue(validator.isKeyword(":include", getProject()));
        assertFalse(validator.isKeyword(":ValidType", getProject()));
        assertFalse(validator.isKeyword("#validConst", getProject()));
    }

    /**
     * Verifies that getName() on TypeDefinition and TypeKeyword preserves the leading colon prefix.
     */
    public void testTypeDefinitionGetNamePreservesLeadingColon() {
        var code = """
            {
              :defs {
                :AccountHolder :String
              }
            }
            """;
        var file = myFixture.configureByText("colon_check.stvn", code);
        var typeDef = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        assertNotNull(typeDef);
        assertEquals(":AccountHolder", typeDef.getName());
        assertNotNull(typeDef.getTypeKeyword());
        assertEquals(":AccountHolder", typeDef.getTypeKeyword().getName());
    }

    /**
     * Verifies that getName() on ConstantDefinition and ValueKeyword preserves the leading hash prefix.
     */
    public void testConstantDefinitionGetNamePreservesLeadingHash() {
        var code = """
            {
              :defs {
                #DefaultPort :Int32 8080
              }
            }
            """;
        var file = myFixture.configureByText("hash_check.stvn", code);
        var constDef = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, ConstantDefinition.class);
        assertNotNull(constDef);
        assertEquals("#DefaultPort", constDef.getName());
        assertNotNull(constDef.getValueKeyword());
        assertEquals("#DefaultPort", constDef.getValueKeyword().getName());
    }

    /**
     * Verifies that the initial name retrieved by RenameDialog passes StvnNamesValidator cleanly.
     */
    public void testRenameDialogInitialNamePassesValidationCleanly() {
        var code = """
            {
              :defs {
                :AccountHolder<caret> :String
              }
            }
            """;
        myFixture.configureByText("dialog_init.stvn", code);
        var element = myFixture.getElementAtCaret();
        assertTrue(element instanceof PsiNamedElement);
        var initialName = ((PsiNamedElement) element).getName();
        assertNotNull(initialName);
        assertEquals(":AccountHolder", initialName);

        var validator = new StvnNamesValidator();
        assertTrue("Initial rename string must be a valid identifier", validator.isIdentifier(initialName, getProject()));
    }

    /**
     * Verifies that setName accepts names with or without the leading prefix, canonicalizing safely.
     */
    public void testRenameAcceptsNamesWithOrWithoutPrefix() {
        var code = """
            {
              :defs {
                :OldName<caret> :String
              }
              :type :OldName
              :body "Test"
            }
            """;
        myFixture.configureByText("bare_rename.stvn", code);
        // Supply bare name without leading colon
        myFixture.renameElementAtCaret("NewName");
        var expected = """
            {
              :defs {
                :NewName :String
              }
              :type :NewName
              :body "Test"
            }
            """;
        myFixture.checkResult(expected);
    }
}
