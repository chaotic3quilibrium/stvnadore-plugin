package org.stvnadore.plugin.refactoring;

import com.intellij.navigation.NavigationItem;
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
        myFixture.renameElementAtCaret("CustomerName");

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
        myFixture.renameElementAtCaret("RenamedLocalRecord");

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
        myFixture.renameElementAtCaret("RenamedItem");

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
        myFixture.renameElementAtCaret("NewType");

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
        myFixture.renameElementAtCaret("RenamedSharedRecord");

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

        // Valid bare type identifiers
        assertTrue(validator.isIdentifier("ValidType", getProject()));
        assertTrue(validator.isIdentifier("org/stvnadore/prelude/CustomType", getProject()));
        assertTrue(validator.isIdentifier("Account123", getProject()));

        // Valid bare constant identifiers
        assertTrue(validator.isIdentifier("validConst", getProject()));
        assertTrue(validator.isIdentifier("finance/taxRate", getProject()));
        assertTrue(validator.isIdentifier("VariantTag", getProject()));

        // Invalid: sigil prefix must be rejected by validator
        assertFalse(validator.isIdentifier(":ValidType", getProject()));
        assertFalse(validator.isIdentifier("#validConst", getProject()));

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
        assertFalse(validator.isIdentifier("defs", getProject()));
        assertFalse(validator.isIdentifier("type", getProject()));
        assertFalse(validator.isIdentifier("body", getProject()));
        assertFalse(validator.isIdentifier("package", getProject()));
        assertFalse(validator.isIdentifier("use", getProject()));
        assertFalse(validator.isIdentifier("include", getProject()));
        assertFalse(validator.isIdentifier(":defs", getProject()));
        assertFalse(validator.isIdentifier(":type", getProject()));

        // Keyword checks
        assertTrue(validator.isKeyword("defs", getProject()));
        assertTrue(validator.isKeyword("type", getProject()));
        assertTrue(validator.isKeyword(":defs", getProject()));
        assertTrue(validator.isKeyword(":type", getProject()));
        assertFalse(validator.isKeyword("ValidType", getProject()));
        assertFalse(validator.isKeyword("validConst", getProject()));
    }

    /**
     * Verifies that getName() returns bare identifiers while FindUsagesProvider and ItemPresentation project canonical sigils.
     */
    public void testTypeDefinitionGetNameReturnsBareIdentifierAndProjectsCanonicalSigil() {
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
        assertEquals("AccountHolder", typeDef.getName());
        assertNotNull(typeDef.getTypeKeyword());
        assertEquals("AccountHolder", typeDef.getTypeKeyword().getName());
        var provider = new org.stvnadore.plugin.findusages.StvnFindUsagesProvider();
        assertEquals(":AccountHolder", provider.getDescriptiveName(typeDef));
        assertEquals(":AccountHolder", provider.getNodeText(typeDef, false));
        assertTrue(typeDef instanceof NavigationItem);
        var typePresentation = ((NavigationItem) typeDef).getPresentation();
        assertNotNull(typePresentation);
        assertEquals(":AccountHolder", typePresentation.getPresentableText());
    }

    /**
     * Verifies that getName() returns bare identifiers while FindUsagesProvider and ItemPresentation project canonical sigils.
     */
    public void testConstantDefinitionGetNameReturnsBareIdentifierAndProjectsCanonicalSigil() {
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
        assertEquals("DefaultPort", constDef.getName());
        assertNotNull(constDef.getValueKeyword());
        assertEquals("DefaultPort", constDef.getValueKeyword().getName());
        var provider = new org.stvnadore.plugin.findusages.StvnFindUsagesProvider();
        assertEquals("#DefaultPort", provider.getDescriptiveName(constDef));
        assertEquals("#DefaultPort", provider.getNodeText(constDef, false));
        assertTrue(constDef instanceof NavigationItem);
        var constPresentation = ((NavigationItem) constDef).getPresentation();
        assertNotNull(constPresentation);
        assertEquals("#DefaultPort", constPresentation.getPresentableText());
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
        assertEquals("AccountHolder", initialName);

        var validator = new StvnNamesValidator();
        assertTrue("Initial rename string must be a valid identifier", validator.isIdentifier(initialName, getProject()));
    }

    /**
     * Verifies that StvnRenameInputValidator strictly requires bare identifiers, rejects sigil prefixes
     * with immediate error messages, and disables the Refactor action.
     */
    public void testRenameInputValidatorEnforcesBareIdentifiersAndRejectsSigils() {
        var validator = new StvnRenameInputValidator();
        var code = """
            {
              :defs {
                :MyType :String
                #MyConst :Int32 42
              }
            }
            """;
        var file = myFixture.configureByText("validator_test.stvn", code);
        var typeDef = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        var constDef = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, ConstantDefinition.class);
        assertNotNull(typeDef);
        assertNotNull(constDef);

        var context = new com.intellij.util.ProcessingContext();
        assertTrue(validator.getPattern().accepts(typeDef, context));
        assertTrue(validator.getPattern().accepts(constDef, context));

        // Registry lookup for TypeDefinition: '#' sigil yields explicit error message
        var typeErrorFn = com.intellij.refactoring.rename.RenameInputValidatorRegistry.getInputErrorValidator(typeDef);
        assertNotNull(typeErrorFn);
        assertEquals(StvnRenameInputValidator.PREFIX_ERROR_MESSAGE, typeErrorFn.fun("#InvalidConst"));
        assertEquals(StvnRenameInputValidator.PREFIX_ERROR_MESSAGE, typeErrorFn.fun(":InvalidType"));

        // Registry lookup for ConstantDefinition: ':' sigil yields explicit error message
        var constErrorFn = com.intellij.refactoring.rename.RenameInputValidatorRegistry.getInputErrorValidator(constDef);
        assertNotNull(constErrorFn);
        assertEquals(StvnRenameInputValidator.PREFIX_ERROR_MESSAGE, constErrorFn.fun(":InvalidType"));
        assertEquals(StvnRenameInputValidator.PREFIX_ERROR_MESSAGE, constErrorFn.fun("#InvalidConst"));

        // Direct validator invocation
        assertEquals(StvnRenameInputValidator.PREFIX_ERROR_MESSAGE, validator.getErrorMessage("#InvalidConst", typeDef, getProject()));
        assertEquals(StvnRenameInputValidator.PREFIX_ERROR_MESSAGE, validator.getErrorMessage(":InvalidType", constDef, getProject()));
        assertFalse(validator.isInputValid(":InvalidType", typeDef, context));
        assertFalse(validator.isInputValid("#InvalidConst", constDef, context));

        // Valid bare names return null error and true validity
        assertNull(validator.getErrorMessage("ValidBareType", typeDef, getProject()));
        assertTrue(validator.isInputValid("ValidBareType", typeDef, context));
        assertNull(validator.getErrorMessage("ValidBareConst", constDef, getProject()));
        assertTrue(validator.isInputValid("ValidBareConst", constDef, context));
    }

    /**
     * Verifies that programmatic invocation with any prefixed name throws IncorrectOperationException,
     * confirming the strict fail-closed perimeter.
     */
    public void testProgrammaticRenameWithPrefixedNameThrowsIncorrectOperationException() {
        var beforeCode = """
            {
              :defs {
                :AccountHolder<caret> :String
                #DefaultPort :Int32 8080
              }
              :type :AccountHolder
              :body "Alice"
            }
            """;
        myFixture.configureByText("conflicting_sigil.stvn", beforeCode);

        // Attempting to rename TypeDefinition with constant sigil '#' throws IncorrectOperationException
        try {
            myFixture.renameElementAtCaret("#ConflictingConstName");
            fail("Expected IncorrectOperationException when renaming type with '#' prefix");
        } catch (Throwable e) {
            var ioe = (e instanceof com.intellij.util.IncorrectOperationException i) ? i
                : (e.getCause() instanceof com.intellij.util.IncorrectOperationException i ? i : null);
            assertNotNull("Expected IncorrectOperationException or wrapped cause, but got: " + e, ioe);
            assertTrue(ioe.getMessage().contains("Identifier must be a bare name without ':' or '#' prefix: #ConflictingConstName"));
        }

        // Attempting to rename TypeDefinition with type sigil ':' also throws IncorrectOperationException
        try {
            myFixture.renameElementAtCaret(":ConflictingTypeName");
            fail("Expected IncorrectOperationException when renaming type with ':' prefix");
        } catch (Throwable e) {
            var ioe = (e instanceof com.intellij.util.IncorrectOperationException i) ? i
                : (e.getCause() instanceof com.intellij.util.IncorrectOperationException i ? i : null);
            assertNotNull("Expected IncorrectOperationException or wrapped cause, but got: " + e, ioe);
            assertTrue(ioe.getMessage().contains("Identifier must be a bare name without ':' or '#' prefix: :ConflictingTypeName"));
        }

        // Navigate caret to ConstantDefinition
        var file = myFixture.getFile();
        var constDef = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, ConstantDefinition.class);
        assertNotNull(constDef);
        myFixture.getEditor().getCaretModel().moveToOffset(constDef.getTextOffset());

        // Attempting to rename ConstantDefinition with type sigil ':' throws IncorrectOperationException
        try {
            myFixture.renameElementAtCaret(":ConflictingTypeName");
            fail("Expected IncorrectOperationException when renaming constant with ':' prefix");
        } catch (Throwable e) {
            var ioe = (e instanceof com.intellij.util.IncorrectOperationException i) ? i
                : (e.getCause() instanceof com.intellij.util.IncorrectOperationException i ? i : null);
            assertNotNull("Expected IncorrectOperationException or wrapped cause, but got: " + e, ioe);
            assertTrue(ioe.getMessage().contains("Identifier must be a bare name without ':' or '#' prefix: :ConflictingTypeName"));
        }

        // Attempting to rename ConstantDefinition with constant sigil '#' also throws IncorrectOperationException
        try {
            myFixture.renameElementAtCaret("#ConflictingConstName");
            fail("Expected IncorrectOperationException when renaming constant with '#' prefix");
        } catch (Throwable e) {
            var ioe = (e instanceof com.intellij.util.IncorrectOperationException i) ? i
                : (e.getCause() instanceof com.intellij.util.IncorrectOperationException i ? i : null);
            assertNotNull("Expected IncorrectOperationException or wrapped cause, but got: " + e, ioe);
            assertTrue(ioe.getMessage().contains("Identifier must be a bare name without ':' or '#' prefix: #ConflictingConstName"));
        }
    }
}
