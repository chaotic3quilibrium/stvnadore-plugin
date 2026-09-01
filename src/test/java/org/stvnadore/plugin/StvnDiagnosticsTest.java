package org.stvnadore.plugin;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.stvnadore.plugin.actions.StvnSchemaSkeletonIntentionAction;
import org.stvnadore.plugin.validation.StvnMapAutoHealerQuickFix;
import org.stvnadore.psi.ListLiteral;
import org.stvnadore.psi.MapLiteral;
import org.stvnadore.psi.TupleLiteral;
import org.stvnadore.psi.TypeKeyword;
import org.stvnadore.psi.ValueKeyword;
import org.stvnadore.psi.Value;
import org.stvnadore.plugin.reference.StvnTypeResolver;
import org.stvnadore.plugin.settings.StvnSettings;
import org.stvnadore.plugin.settings.StvnProjectSettings;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Platform integration test suite verifying syntax errors, compiler diagnostics,
 * and semantic annotations against shared test fixtures.
 */
@NullMarked
public final class StvnDiagnosticsTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/shared-fixtures";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("", "");
        setUseLongFormSumTypes(false);
    }

    @Override
    protected void tearDown() throws Exception {
        setUseLongFormSumTypes(false);
        super.tearDown();
    }

    public void testInvalidSyntaxFixtures() throws Exception {
        var invalidDir = Paths.get(getTestDataPath(), "invalid-syntax");
        if (!Files.exists(invalidDir)) {
            fail("Invalid fixtures directory does not exist: " + invalidDir.toAbsolutePath());
        }

        try (var stream = Files.walk(invalidDir)) {
            var fixtureFiles = stream
                .filter(p -> (p.toString().endsWith(".stvn") || p.toString().endsWith(".stvn_inclf")) && !p.toString().endsWith(".contract.stvn"))
                .toList();

            for (var fixturePath : fixtureFiles) {
                var fileName = fixturePath.getFileName().toString();
                var baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                var contractPath = fixturePath.resolveSibling(baseName + ".contract.stvn");
                var jsonPath = fixturePath.resolveSibling(baseName + ".json");

                String expectedSubstring = null;

                var categoryStr = "";
                if (Files.exists(contractPath)) {
                    var contractContent = Files.readString(contractPath);
                    var compileOpt = org.stvnadore.core.StvnCompiler.compile(contractContent, contractPath.toAbsolutePath().toString(), org.stvnadore.core.StvnParserConfig.DEFAULT);
                    assertTrue("Contract failed to compile for " + fileName, compileOpt.isPresent());
                    var contractVal = compileOpt.get();
                    assertTrue("Contract must be a Tuple for " + fileName, contractVal instanceof org.stvnadore.core.ir.StvnValue.StvnTuple);
                    var tuple = (org.stvnadore.core.ir.StvnValue.StvnTuple) contractVal;
                    assertTrue("Contract tuple must have at least 2 elements for " + fileName, tuple.elements().size() >= 2);
                    var catVal = tuple.elements().get(0);
                    if (catVal instanceof org.stvnadore.core.ir.StvnValue.StvnEnum enumVal) {
                        categoryStr = enumVal.keyword();
                    }
                    var msgVal = tuple.elements().get(1);
                    assertTrue("Contract message must be String for " + fileName, msgVal instanceof org.stvnadore.core.ir.StvnValue.StvnString);
                    expectedSubstring = ((org.stvnadore.core.ir.StvnValue.StvnString) msgVal).value();
                } else if (Files.exists(jsonPath)) {
                    var jsonContent = Files.readString(jsonPath);
                    expectedSubstring = extractJsonField(jsonContent, "errorMessageSubstring");
                }

                if (expectedSubstring == null) {
                    continue;
                }

                // Load and configure the file in the IntelliJ test workspace
                var relativePath = "invalid-syntax/" + fileName;
                myFixture.configureByFile(relativePath);

                // Run highlighting pass
                var highlights = myFixture.doHighlighting();
                var found = false;
                var foundMessages = new ArrayList<String>();
                for (var info : highlights) {
                    if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                        var description = info.getDescription();
                        foundMessages.add(description);
                        if (description != null) {
                            if (description.contains(expectedSubstring)) {
                                found = true;
                                break;
                            }
                            if (expectedSubstring.startsWith("STVN Syntax Error") || categoryStr.contains("SYNTAX_ERROR")) {
                                if (description.contains("expected") || description.contains("got") || description.contains("Syntax Error") || description.contains("Mismatched") || description.contains("mismatched")) {
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }
                }

                assertTrue("Expected error matching '" + expectedSubstring + "' not found in " + fileName + 
                        ". Found errors: " + foundMessages, found);
            }
        }
    }

    public void testValidSyntaxFixtures() throws Exception {
        var validDir = Paths.get(getTestDataPath(), "valid-syntax");
        if (!Files.exists(validDir)) {
            return;
        }

        try (var stream = Files.walk(validDir)) {
            var stvnFiles = stream.filter(p -> p.toString().endsWith(".stvn")).toList();
            for (var stvnPath : stvnFiles) {
                var baseName = stvnPath.getFileName().toString();
                var relativePath = "valid-syntax/" + baseName;
                myFixture.configureByFile(relativePath);

                var highlights = myFixture.doHighlighting();
                for (var info : highlights) {
                    if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                        fail("Unexpected error in valid file " + baseName + ": " + info.getDescription());
                    }
                }
            }
        }
    }

    private static @Nullable String extractJsonField(String json, String fieldName) {
        var pattern = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"((?:[^\\\\\"]|\\\\.)*)\"");
        var matcher = pattern.matcher(json);
        if (matcher.find()) {
            var value = matcher.group(1);
            return value.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
        }
        return null;
    }

    public void testConstantReferenceNavigation() {
        myFixture.configureByFile("valid-syntax/typed_constants.stvn");
        myFixture.doHighlighting();

        var text = myFixture.getEditor().getDocument().getText();
        var bodyIdx = text.indexOf(":body");
        var constUsageIdx = text.indexOf("#MAX_RETRY", bodyIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(constUsageIdx);

        var constRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Constant reference for #MAX_RETRY not found", constRef);
        var resolvedConst = constRef.resolve();
        assertNotNull("Could not resolve constant reference for #MAX_RETRY", resolvedConst);
        assertTrue("Resolved constant is not a ValueKeyword", resolvedConst instanceof ValueKeyword);
        assertEquals("#MAX_RETRY", resolvedConst.getText());
        assertTrue("Parent is not ConstantDefinition", resolvedConst.getParent() instanceof org.stvnadore.psi.ConstantDefinition);
    }

    public void testUhohEightAryCollisionTupleDiagnosticsAndInlays() {
        var psiFile = myFixture.configureByText(
            "uhoh_test.stvn",
            """
            {
              :defs {
                #Some  :Uint5 1
                #None  :Uint5 2
                #Left  :Uint5 3
                #Right :Uint5 4
                #TRUE  :Uint5 5
                #FALSE :Uint5 6
                #True  :Uint5 7
                #False :Uint5 8
              }
              :type :Tuple(
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
              )
              :body (
                #Some
                #None
                #Left
                #Right
                #TRUE
                #FALSE
                #True
                #False
              )
            }
            """
        );

        // 1. Assert 0 error highlights
        var highlights = myFixture.doHighlighting();
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                fail("Unexpected error in uhoh_test.stvn: " + info.getDescription());
            }
        }

        // 2. Assert navigation on #Some, #Left, #TRUE
        var text = psiFile.getText();
        var bodyIdx = text.indexOf(":body");

        var someIdx = text.indexOf("#Some", bodyIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(someIdx);
        var someRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for #Some in body must exist", someRef);
        var resolvedSome = someRef.resolve();
        assertNotNull("Could not resolve #Some constant reference", resolvedSome);
        assertTrue("Resolved parent must be ConstantDefinition", resolvedSome.getParent() instanceof org.stvnadore.psi.ConstantDefinition);
        assertEquals("#Some", resolvedSome.getText());

        var leftIdx = text.indexOf("#Left", bodyIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(leftIdx);
        var leftRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for #Left in body must exist", leftRef);
        var resolvedLeft = leftRef.resolve();
        assertNotNull("Could not resolve #Left constant reference", resolvedLeft);
        assertTrue("Resolved parent must be ConstantDefinition", resolvedLeft.getParent() instanceof org.stvnadore.psi.ConstantDefinition);
        assertEquals("#Left", resolvedLeft.getText());

        var trueIdx = text.indexOf("#TRUE", bodyIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(trueIdx);
        var trueRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for #TRUE in body must exist", trueRef);
        var resolvedTrue = trueRef.resolve();
        assertNotNull("Could not resolve #TRUE constant reference", resolvedTrue);
        assertTrue("Resolved parent must be ConstantDefinition", resolvedTrue.getParent() instanceof org.stvnadore.psi.ConstantDefinition);
        assertEquals("#TRUE", resolvedTrue.getText());
    }

    public void testMixedEnumVsConstantNavigation() {
        var psiFile = myFixture.configureByText(
            "mixed_nav.stvn",
            """
            {
              :defs {
                :Mode :Enum [ #Left #Right ]
                #Left :Uint7 99
              }
              :type :Tuple( :Mode :Uint7 )
              :body (
                #Left
                #Left
              )
            }
            """
        );

        myFixture.doHighlighting();
        var text = psiFile.getText();
        var bodyIdx = text.indexOf(":body");

        var firstLeftIdx = text.indexOf("#Left", bodyIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(firstLeftIdx);
        var firstRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for first #Left must exist", firstRef);
        var resolvedFirst = firstRef.resolve();
        assertNotNull("Could not resolve first #Left reference", resolvedFirst);
        assertTrue("Resolved first #Left parent must be EnumDef", resolvedFirst.getParent() instanceof org.stvnadore.psi.EnumDef);

        var secondLeftIdx = text.indexOf("#Left", firstLeftIdx + 5);
        myFixture.getEditor().getCaretModel().moveToOffset(secondLeftIdx);
        var secondRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Reference for second #Left must exist", secondRef);
        var resolvedSecond = secondRef.resolve();
        assertNotNull("Could not resolve second #Left reference", resolvedSecond);
        assertTrue("Resolved second #Left parent must be ConstantDefinition", resolvedSecond.getParent() instanceof org.stvnadore.psi.ConstantDefinition);
    }

    public void testCrossModuleNavigation() {
        // Configure the main file in the test project
        myFixture.configureByFile("valid-syntax/include_legal.stvn");
        myFixture.doHighlighting(); // Force initialization of reference contributors

        // 1. Verify include path resolution
        var includeOffset = myFixture.getEditor().getDocument().getText().indexOf("module_a.stvn_incl");
        myFixture.getEditor().getCaretModel().moveToOffset(includeOffset);
        var includeRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Include reference not found", includeRef);
        var resolvedInclude = includeRef.resolve();
        assertNotNull("Could not resolve include reference", resolvedInclude);
        assertTrue("Resolved element is not a PsiFile", resolvedInclude instanceof com.intellij.psi.PsiFile);
        assertEquals("module_a.stvn_incl", ((com.intellij.psi.PsiFile) resolvedInclude).getName());

        // 2. Verify type reference resolution (:TypeA)
        var typeAOffset = myFixture.getEditor().getDocument().getText().indexOf(":TypeA");
        myFixture.getEditor().getCaretModel().moveToOffset(typeAOffset);
        var typeARef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference for :TypeA not found", typeARef);
        var resolvedTypeA = typeARef.resolve();
        assertNotNull("Could not resolve type reference for :TypeA", resolvedTypeA);
        assertTrue("Resolved type is not a TypeKeyword", resolvedTypeA instanceof TypeKeyword);
        assertEquals("module_a.stvn_incl", resolvedTypeA.getContainingFile().getName());

        // 3. Verify aliased type reference resolution (:ConflictTypeB resolves locally first)
        var text = myFixture.getEditor().getDocument().getText();
        var typeTupleOffset = text.indexOf(":type :Tuple");
        var conflictBOffset = text.indexOf(":ConflictTypeB", typeTupleOffset);
        myFixture.getEditor().getCaretModel().moveToOffset(conflictBOffset);
        var conflictBRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference for :ConflictTypeB not found", conflictBRef);
        var resolvedConflictB = conflictBRef.resolve();
        assertNotNull("Could not resolve type reference for :ConflictTypeB", resolvedConflictB);
        assertTrue("Resolved type is not a TypeKeyword", resolvedConflictB instanceof TypeKeyword);
        // :ConflictTypeB (usage) must resolve to the local RHS keyword definition point in include_legal.stvn
        assertEquals("include_legal.stvn", resolvedConflictB.getContainingFile().getName());
        assertTrue("Resolved type's parent is not IncludeMapAlias", resolvedConflictB.getParent() instanceof org.stvnadore.psi.IncludeMapAlias);

        // 4. Verify alias first keyword reference resolution (:ConflictType inside mapping resolves remotely)
        var conflictMappingOffset = text.indexOf(":ConflictType ");
        myFixture.getEditor().getCaretModel().moveToOffset(conflictMappingOffset);
        var mappingRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Mapping reference not found", mappingRef);
        var resolvedMapping = mappingRef.resolve();
        assertNotNull("Could not resolve mapping reference", resolvedMapping);
        assertTrue("Resolved mapping is not a TypeKeyword", resolvedMapping instanceof TypeKeyword);
        assertEquals("module_b.stvn_incl", resolvedMapping.getContainingFile().getName());
        assertEquals(":ConflictType", resolvedMapping.getText());

        // 5. Verify declaration isolation: RHS keyword of an include_map_alias should not return any references
        var aliasRhsOffset = text.indexOf(":ConflictTypeB");
        myFixture.getEditor().getCaretModel().moveToOffset(aliasRhsOffset);
        var aliasRhsRef = myFixture.getReferenceAtCaretPosition();
        assertNull("RHS keyword should not have a reference (declaration isolation)", aliasRhsRef);

        // 6. Verify asymmetric eviction navigation using include_asymmetric_eviction.stvn
        myFixture.configureByFile("valid-syntax/include_asymmetric_eviction.stvn");
        myFixture.doHighlighting();
        var textAsym = myFixture.getEditor().getDocument().getText();

        // 6a. :AliasedType (usage) resolves locally to RHS keyword `:AliasedType`
        var asymTupleOffset = textAsym.indexOf(":type :Tuple");
        var aliasedTypeOffset = textAsym.indexOf(":AliasedType", asymTupleOffset);
        myFixture.getEditor().getCaretModel().moveToOffset(aliasedTypeOffset);
        var aliasedTypeRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference for :AliasedType not found", aliasedTypeRef);
        var resolvedAliasedType = aliasedTypeRef.resolve();
        assertNotNull("Could not resolve :AliasedType", resolvedAliasedType);
        assertEquals("include_asymmetric_eviction.stvn", resolvedAliasedType.getContainingFile().getName());
        assertTrue("Parent should be IncludeMapAlias", resolvedAliasedType.getParent() instanceof org.stvnadore.psi.IncludeMapAlias);

        // 6b. LHS keyword `:ConflictType` in mapping resolves to remote definition in module_asym_a.stvn_incl
        var asymLhsOffset = textAsym.indexOf(":ConflictType ");
        myFixture.getEditor().getCaretModel().moveToOffset(asymLhsOffset);
        var asymLhsRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("LHS mapping reference not found", asymLhsRef);
        var resolvedAsymLhs = asymLhsRef.resolve();
        assertNotNull("Could not resolve LHS mapping reference", resolvedAsymLhs);
        assertEquals("module_asym_a.stvn_incl", resolvedAsymLhs.getContainingFile().getName());
        assertEquals(":ConflictType", resolvedAsymLhs.getText());

        // 6c. :ConflictType (usage in :Tuple) resolves to remote definition in module_asym_b.stvn_incl (eviction wins)
        var asymUsageOffset = textAsym.indexOf(":ConflictType", asymTupleOffset);
        myFixture.getEditor().getCaretModel().moveToOffset(asymUsageOffset);
        var asymUsageRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference for :ConflictType usage not found", asymUsageRef);
        var resolvedAsymUsage = asymUsageRef.resolve();
        assertNotNull("Could not resolve :ConflictType usage", resolvedAsymUsage);
        assertEquals("module_asym_b.stvn_incl", resolvedAsymUsage.getContainingFile().getName());
        assertEquals(":ConflictType", resolvedAsymUsage.getText());

        // 7. Verify dual eviction null resolution outcome using include_dual_eviction_unresolved.stvn
        myFixture.configureByFile("invalid-syntax/include_dual_eviction_unresolved.stvn");
        myFixture.doHighlighting();
        var textDual = myFixture.getEditor().getDocument().getText();

        // :ConflictType (usage in :type) should resolve to null (evicted / colliding)
        var typeEntryOffset = textDual.indexOf(":type :ConflictType");
        var dualUsageOffset = textDual.indexOf(":ConflictType", typeEntryOffset);
        myFixture.getEditor().getCaretModel().moveToOffset(dualUsageOffset);
        var dualUsageRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference for colliding :ConflictType usage not found", dualUsageRef);
        var resolvedDualUsage = dualUsageRef.resolve();
        assertNull("Colliding usage should resolve to null", resolvedDualUsage);
    }

    public void testFindUsagesAcrossModules() {
        myFixture.configureByFile("valid-syntax/include_legal.stvn");
        myFixture.doHighlighting();

        var text = myFixture.getEditor().getDocument().getText();

        // 1. Verify usage search for ':TypeA' defined in module_a.stvn_incl
        var typeAOffset = text.indexOf(":TypeA");
        myFixture.getEditor().getCaretModel().moveToOffset(typeAOffset);
        var typeARef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference for :TypeA not found", typeARef);
        var resolvedTypeA = typeARef.resolve();
        assertNotNull("Could not resolve :TypeA", resolvedTypeA);

        var typeAUsages = myFixture.findUsages(resolvedTypeA);
        assertEquals("Expected exactly 1 usage for :TypeA", 1, typeAUsages.size());

        // 2. Verify usage search for ':TypeB' defined in module_b.stvn_incl
        var typeBOffset = text.indexOf(":TypeB");
        myFixture.getEditor().getCaretModel().moveToOffset(typeBOffset);
        var typeBRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference for :TypeB not found", typeBRef);
        var resolvedTypeB = typeBRef.resolve();
        assertNotNull("Could not resolve :TypeB", resolvedTypeB);

        var typeBUsages = myFixture.findUsages(resolvedTypeB);
        assertEquals("Expected exactly 1 usage for :TypeB", 1, typeBUsages.size());

        // 3. Verify usage search for ':ConflictTypeB' (introduced via local RHS alias)
        var conflictBOffset = text.indexOf(":ConflictTypeB"); // Point to the RHS mapping introduction
        myFixture.getEditor().getCaretModel().moveToOffset(conflictBOffset);
        var declarationElement = myFixture.getElementAtCaret();
        assertNotNull("Declaration element not found at offset", declarationElement);

        var conflictBUsages = myFixture.findUsages(declarationElement);
        assertEquals("Expected exactly 1 usage for :ConflictTypeB", 1, conflictBUsages.size());
    }

    public void testTypeInlayHints() {
      //intentional use of :Uid before it is defined, demonstrating eventual resolution (not forced order resolution)
      myFixture.configureByText(
            "dummy.stvn",
            """
                {
                  :defs {
                    :LId :UId
                    :UId :String
                  }
                  :type :Tuple( :LId :Int32 )
                  :body ( "user123"<hint text=":LId (-> :UId -> :String)"/> 42<hint text=":Int32"/> )<hint text=":Tuple( :LId :Int32 )"/>
                }
                """);
        myFixture.testInlays(
                inlay -> {
                  var renderer = inlay.getRenderer();
                  try {
                    var getPresentationList = renderer.getClass().getMethod("getPresentationList");
                    var presentationList = getPresentationList.invoke(renderer);
                    if (presentationList != null) {
                      var getEntries = presentationList.getClass().getMethod("getEntries");
                      var entries = (Object[]) getEntries.invoke(presentationList);
                      if (entries != null && entries.length > 0) {
                        var sb = new StringBuilder();
                        for (var entry : entries) {
                          var getText = entry.getClass().getMethod("getText");
                          var text = (String) getText.invoke(entry);
                          if (text != null) {
                            sb.append(text);
                          }
                        }
                        return sb.toString();
                      }
                    }
                  } catch (Exception e) {
                    // Fallback
                  }
                  return renderer.toString();
                },
                inlay -> true
        );
    }

    public void testCrossModuleTypeInlayHints() {
        myFixture.configureByText(
            "include_legal_hints.stvn",
            """
                {
                  :defs {
                    :include [
                      "shared-fixtures/valid-syntax/module_a.stvn_incl"
                      "shared-fixtures/valid-syntax/module_b.stvn_incl" {
                        :ConflictType :ConflictTypeB
                      }
                    ]
                  }
                  :type :Tuple( :TypeA :TypeB :ConflictType :ConflictTypeB )
                  :body ( 42<hint text=":TypeA (-> :Int32)"/> "hello"<hint text=":TypeB (-> :String)"/> 100<hint text=":ConflictType (-> :Int32)"/> "world"<hint text=":ConflictTypeB (-> :ConflictType -> :String)"/> )<hint text=":Tuple( :TypeA :TypeB :ConflictType :ConflictTypeB )"/>
                }
                """);
        myFixture.testInlays(
                inlay -> {
                  var renderer = inlay.getRenderer();
                  try {
                    var getPresentationList = renderer.getClass().getMethod("getPresentationList");
                    var presentationList = getPresentationList.invoke(renderer);
                    if (presentationList != null) {
                      var getEntries = presentationList.getClass().getMethod("getEntries");
                      var entries = (Object[]) getEntries.invoke(presentationList);
                      if (entries != null && entries.length > 0) {
                        var sb = new StringBuilder();
                        for (var entry : entries) {
                          var getText = entry.getClass().getMethod("getText");
                          var text = (String) getText.invoke(entry);
                          if (text != null) {
                            sb.append(text);
                          }
                        }
                        return sb.toString();
                      }
                    }
                  } catch (Exception e) {
                    // Fallback
                  }
                  return renderer.toString();
                },
                inlay -> true
        );
    }

    private void runInlayVerification() {
        myFixture.testInlays(
                inlay -> {
                  var renderer = inlay.getRenderer();
                  try {
                    var getPresentationList = renderer.getClass().getMethod("getPresentationList");
                    var presentationList = getPresentationList.invoke(renderer);
                    if (presentationList != null) {
                      var getEntries = presentationList.getClass().getMethod("getEntries");
                      var entries = (Object[]) getEntries.invoke(presentationList);
                      if (entries != null && entries.length > 0) {
                        var sb = new StringBuilder();
                        for (var entry : entries) {
                          var getText = entry.getClass().getMethod("getText");
                          var text = (String) getText.invoke(entry);
                          if (text != null) {
                            sb.append(text);
                          }
                        }
                        return sb.toString();
                      }
                    }
                  } catch (Exception e) {
                    // Fallback to legacy PresentationRenderer
                    try {
                      var getPresentation = renderer.getClass().getMethod("getPresentation");
                      var presentation = getPresentation.invoke(renderer);
                      if (presentation != null) {
                        var getText = presentation.getClass().getMethod("getText");
                        var text = (String) getText.invoke(presentation);
                        if (text != null) {
                          return text;
                        }
                      }
                    } catch (Exception ex) {
                      // Fallback
                    }
                  }
                  return renderer.toString();
                },
                inlay -> true
        );
    }

    private void setUseLongFormSumTypes(boolean value) {
        var settings = StvnSettings.getInstance(getProject());
        settings.getState().useLongFormSumTypes = value;
    }

    public void testEitherTypeInlayHints() {
        // 1. Assert short form settings (false)
        setUseLongFormSumTypes(false);
        myFixture.configureByText(
            "either_hints_short.stvn",
            """
                {
                  :type :Tuple( :Either( :Int32 :String ) :Either( :Boolean :Float64 ) :Either( :String :Int32 ) )
                  :body ( #Left -105<hint text=":Either( :Int32 :String ) #L"/> #R 3.14<hint text=":Either( :Boolean :Float64 ) #R"/> 42<hint text=":Either( :String :Int32 ) [#R]"/> )<hint text=":Tuple( :Either( :Int32 :String ) :Either( :Boolean :Float64 ) :Either( :String :Int32 ) )"/>
                }
                """);
        runInlayVerification();

        // 2. Assert long form settings (true)
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "either_hints_long.stvn",
            """
                {
                  :type :Tuple( :Either( :Int32 :String ) :Either( :Boolean :Float64 ) :Either( :String :Int32 ) )
                  :body ( #Left -105<hint text=":Either( :Int32 :String ) #Left"/> #R 3.14<hint text=":Either( :Boolean :Float64 ) #Right"/> 42<hint text=":Either( :String :Int32 ) [#Right]"/> )<hint text=":Tuple( :Either( :Int32 :String ) :Either( :Boolean :Float64 ) :Either( :String :Int32 ) )"/>
                }
                """);
        runInlayVerification();
    }

    public void testOptionAndUnionTypeInlayHints() {
        // 1. Assert short form settings (false)
        setUseLongFormSumTypes(false);
        myFixture.configureByText(
            "option_union_hints_short.stvn",
            """
                {
                  :defs {
                    :TypeA :Int32
                    :TypeB :String
                  }
                  :type :Tuple( :Option( :Int32 ) :Option( :String ) :Union( :TypeA :TypeB ) :Union( :TypeA :TypeB ) )
                  :body ( 42<hint text=":Option( :Int32 ) [#S]"/> #None<hint text=":Option( :String ) #N"/> 100<hint text=":Union( :TypeA :TypeB ) [#1]"/> "world"<hint text=":Union( :TypeA :TypeB ) [#2]"/> )<hint text=":Tuple( :Option( :Int32 ) :Option( :String ) :Union( :TypeA :TypeB ) :Union( :TypeA :TypeB ) )"/>
                }
                """);
        runInlayVerification();

        // 2. Assert long form settings (true)
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "option_union_hints_long.stvn",
            """
                {
                  :defs {
                    :TypeA :Int32
                    :TypeB :String
                  }
                  :type :Tuple( :Option( :Int32 ) :Option( :String ) :Union( :TypeA :TypeB ) :Union( :TypeA :TypeB ) )
                  :body ( 42<hint text=":Option( :Int32 ) [#Some]"/> #None<hint text=":Option( :String ) #None"/> 100<hint text=":Union( :TypeA :TypeB ) [#1]"/> "world"<hint text=":Union( :TypeA :TypeB ) [#2]"/> )<hint text=":Tuple( :Option( :Int32 ) :Option( :String ) :Union( :TypeA :TypeB ) :Union( :TypeA :TypeB ) )"/>
                }
                """);
        runInlayVerification();
    }

    public void testHoverDocumentationForTypeAlias() {
        myFixture.configureByText("test.stvn",
            "{\n" +
            "  :defs {\n" +
            "    :Payload :Seq( :Int32 )\n" +
            "  }\n" +
            "  :type :Payload\n" +
            "  :body [ 1 2 3 ]\n" +
            "}"
        );
        
        var text = myFixture.getEditor().getDocument().getText();
        var offset = text.lastIndexOf(":Payload");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        
        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference not found", ref);
        var resolved = ref.resolve();
        assertNotNull("Could not resolve reference", resolved);
        
        var originalElement = myFixture.getFile().findElementAt(offset);
        assertNotNull(originalElement);
        
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var doc = provider.generateDoc(resolved, originalElement);
        assertNotNull("HTML documentation is null", doc);
        assertTrue("HTML does not contain Type Alias", doc.contains("Type Alias:</b> :Payload"));
        assertTrue("HTML does not contain Underlying Structure", doc.contains("Underlying Structure:</b> :Seq( :Int32 )"));
        
        var quickInfo = provider.getQuickNavigateInfo(resolved, originalElement);
        assertNotNull("Quick navigate info is null", quickInfo);
        assertEquals("Type Alias: :Payload", quickInfo);
    }

    public void testHoverDocumentationForValueToken() {
        myFixture.configureByText("test.stvn",
            "{\n" +
            "  :type :Seq( :Int32 )\n" +
            "  :body [ 42 ]\n" +
            "}"
        );
        
        var text = myFixture.getEditor().getDocument().getText();
        var offset = text.indexOf("42");
        var element = myFixture.getFile().findElementAt(offset);
        assertNotNull(element);
        
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var doc = provider.generateDoc(element, element);
        assertNotNull("HTML documentation is null", doc);
        assertTrue("HTML does not contain Value Type", doc.contains("Value Type:</b> :Int32"));
        assertTrue("HTML does not contain Expression", doc.contains("Expression:</b> 42"));
        
        var quickInfo = provider.getQuickNavigateInfo(element, element);
        assertNotNull("Quick navigate info is null", quickInfo);
        assertEquals("Value Type: :Int32", quickInfo);
    }

    public void testCustomDocumentationElementForBodyValue() {
        myFixture.configureByText("test.stvn",
            "{\n" +
            "  :type :Int32\n" +
            "  :body 42\n" +
            "}"
        );

        var text = myFixture.getEditor().getDocument().getText();
        var offset = text.indexOf("42");
        var element = myFixture.getFile().findElementAt(offset);
        assertNotNull(element);

        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var customElement = provider.getCustomDocumentationElement(
            myFixture.getEditor(),
            myFixture.getFile(),
            element,
            offset
        );

        assertNotNull("Custom documentation element is null", customElement);
        assertTrue("Custom documentation element is not an instance of Value", customElement instanceof Value);
        assertEquals("42", customElement.getText());
    }

    public void testHoverDocumentationWithSuffixes() {
        myFixture.configureByText(
            "suffix_hover_test.stvn",
            """
            {
              :defs {
                :TypeA :Int32
                :TypeB :String
              }
              :type :Tuple( :Either( :Int32 :String ) :Union( :TypeA :TypeB ) )
              :body ( #Left -105 100 )
            }
            """
        );

        var text = myFixture.getEditor().getDocument().getText();
        var eitherOffset = text.indexOf("#Left");
        var unionOffset = text.indexOf("100");
        var eitherElement = myFixture.getFile().findElementAt(eitherOffset);
        var unionElement = myFixture.getFile().findElementAt(unionOffset);
        assertNotNull(eitherElement);
        assertNotNull(unionElement);

        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        // 1. Validate with useLongFormSumTypes = false (Short Form)
        setUseLongFormSumTypes(false);
        var docEitherShort = provider.generateDoc(eitherElement, eitherElement);
        var docUnionShort = provider.generateDoc(unionElement, unionElement);

        assertNotNull(docEitherShort);
        assertTrue("Expected Either short suffix: " + docEitherShort, docEitherShort.contains("Value Type:</b> :Either( :Int32 :String ) #L"));
        assertNotNull(docUnionShort);
        assertTrue("Expected Union branch suffix: " + docUnionShort, docUnionShort.contains("Value Type:</b> :Union( :TypeA :TypeB ) [#1]"));

        // 2. Validate with useLongFormSumTypes = true (Long Form)
        setUseLongFormSumTypes(true);
        var docEitherLong = provider.generateDoc(eitherElement, eitherElement);
        var docUnionLong = provider.generateDoc(unionElement, unionElement);

        assertNotNull(docEitherLong);
        assertTrue("Expected Either long suffix: " + docEitherLong, docEitherLong.contains("Value Type:</b> :Either( :Int32 :String ) #Left"));
        assertNotNull(docUnionLong);
        assertTrue("Expected Union branch suffix to remain identical: " + docUnionLong, docUnionLong.contains("Value Type:</b> :Union( :TypeA :TypeB ) [#1]"));
    }

    public void testIconResourceLoading() {
        var icon = org.stvnadore.plugin.icons.StvnIcons.FILE;
        assertNotNull("STVN icon resource handle must not be null", icon);
        assertTrue("STVN icon width must be greater than zero", icon.getIconWidth() > 0);
        assertTrue("STVN icon height must be greater than zero", icon.getIconHeight() > 0);
    }

    public void testNonEmptyConstraintTargeting() {
        myFixture.configureByText(
            "nonempty_torture.stvn",
            """
            {
              :type :Tuple(
                :SeqNonEmpty( :Int32 )
                :SetNonEmpty( :Int32 )
                :MapNonEmpty( :Int32 :String )
                :MapInvNonEmpty( :Int32 :String )
              )
              :body ( [] [] {} {} )
            }
            """
        );

        var highlights = myFixture.doHighlighting();
        var errorCount = 0;
        var text = myFixture.getEditor().getDocument().getText();

        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var description = info.getDescription();
                if (description != null && description.contains("is marked as non-empty but contains no elements")) {
                    errorCount++;
                    var startOffset = info.getStartOffset();
                    var endOffset = info.getEndOffset();
                    var matchedText = text.substring(startOffset, endOffset);
                    
                    // Assert the range matches the empty array or empty map token specifically, NOT the parent tuple bounds.
                    assertTrue("Offending range must be either '[]' or '{}', got: " + matchedText,
                            matchedText.equals("[]") || matchedText.equals("{}"));
                }
            }
        }
        assertEquals("Expected exactly 4 non-empty violations (SeqNonEmpty, SetNonEmpty, MapNonEmpty, MapInvNonEmpty)", 4, errorCount);
    }

    private void assertRootInlayHints(String code) {
        myFixture.configureByText("dummy.stvn", code);
        myFixture.testInlays(
            inlay -> {
              var renderer = inlay.getRenderer();
              try {
                var getPresentationList = renderer.getClass().getMethod("getPresentationList");
                var presentationList = getPresentationList.invoke(renderer);
                if (presentationList != null) {
                  var getEntries = presentationList.getClass().getMethod("getEntries");
                  var entries = (Object[]) getEntries.invoke(presentationList);
                  if (entries != null && entries.length > 0) {
                    var sb = new StringBuilder();
                    for (var entry : entries) {
                      var getText = entry.getClass().getMethod("getText");
                      var text = (String) getText.invoke(entry);
                      if (text != null) {
                        sb.append(text);
                      }
                    }
                    return sb.toString();
                  }
                }
              } catch (Exception e) {
                // Fallback
              }
              return renderer.toString();
            },
            inlay -> true
        );
    }

    public void testUniversalRootTypeInlayHints() {
        // 1. Root Tuple
        assertRootInlayHints("""
            {
              :type :Tuple( :Int32 :String )
              :body ( 42<hint text=":Int32"/> "hello"<hint text=":String"/> )<hint text=":Tuple( :Int32 :String )"/>
            }
            """);

        // 2. Root Primitive
        assertRootInlayHints("""
            {
              :type :Int32
              :body 100<hint text=":Int32"/>
            }
            """);

        // 3. Root Open Collection (Seq)
        assertRootInlayHints("""
            {
              :type :Seq( :Int32 )
              :body [ 1<hint text=":Int32"/> 2<hint text=":Int32"/> 3<hint text=":Int32"/> ]<hint text=":Seq( :Int32 )"/>
            }
            """);

        // 4. Root Custom Type Alias
        assertRootInlayHints("""
            {
              :defs {
                :MyAlias :Tuple( :Int32 :String )
              }
              :type :MyAlias
              :body ( 42<hint text=":Int32"/> "hello"<hint text=":String"/> )<hint text=":MyAlias"/>
            }
            """);
    }

    public void testMapDuplicateKeyValidation() {
        myFixture.configureByText(
            "map_duplicate_key_torture.stvn",
            """
            {
              :type :Tuple(
                      :MapNonEmpty(:Uuid :String)
                      :MapInvNonEmpty(:String :Uint))
              :body (
                {
                  ["12345678-1234-1234-1234-123456789012" "A"]
                  ["12345678-1234-1234-1234-123456789012" "B"]
                }
                {
                  ["1" 2]
                  ["1" 3]
                }
              )
            }
            """
        );

        var highlights = myFixture.doHighlighting();
        var errorCount = 0;
        var text = myFixture.getEditor().getDocument().getText();

        var foundUuidError = false;
        var foundOneError = false;

        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var description = info.getDescription();
                if (description != null && description.contains("Duplicate map key detected")) {
                    errorCount++;
                    var startOffset = info.getStartOffset();
                    var endOffset = info.getEndOffset();
                    var matchedText = text.substring(startOffset, endOffset);
                    System.out.println("Duplicate Key Violation range: " + startOffset + "-" + endOffset + " text: " + matchedText);
                    
                    if (description.equals("Duplicate map key detected: '12345678-1234-1234-1234-123456789012'")) {
                        foundUuidError = true;
                        assertEquals("\"12345678-1234-1234-1234-123456789012\"", matchedText);
                    } else if (description.equals("Duplicate map key detected: '1'")) {
                        foundOneError = true;
                        assertEquals("\"1\"", matchedText);
                    }
                }
            }
        }
        assertTrue("Expected duplicate map key error for UUID", foundUuidError);
        assertTrue("Expected duplicate map key error for '1'", foundOneError);
        assertEquals("Expected exactly 2 duplicate key violations", 2, errorCount);
    }

    public void testNestedSumTypeInference() {
        setUseLongFormSumTypes(true);
        var cleanText = """
                {
                  :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
                  :body [
                    // =========================================================================
                    // EXPLICITLY TAGGED PERMUTATIONS (The baseline syntax paths)
                    // =========================================================================

                    #None                                              //Explicit case 1
                    #Some #Left "Explicit Outer Left"                  //Explicit case 2
                    #Some #Right #None                                 //Explicit case 3
                    #Some #Right #Some #Left "Explicit Inner Left"     //Explicit case 4
                    #Some #Right #Some #Right 3.14159                  //Explicit case 5

                    // =========================================================================
                    // VALID SPEC-COMPLIANT INFERENCE PATHWAYS (Asymmetric Happy-Paths)
                    // =========================================================================

                    // Path A: Partially Inferred Inner Empty - above Explicit case 3
                    #Right #None

                    // Path B: Total Fall-Through Inference (The Only Deep Leaf Path Allowed) - Explicit 5
                    2.71828
                  ]
                }
                """;
        myFixture.configureByText("sum_type_torture_clean.stvn", cleanText);
        var highlights = myFixture.doHighlighting();
        var errors = new ArrayList<HighlightInfo>();
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                errors.add(info);
            }
        }
        assertEquals("Expected no highlighting errors, but found: " + errors, 0, errors.size());

        var hintText = """
                {
                  :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
                  :body [
                    // =========================================================================
                    // EXPLICITLY TAGGED PERMUTATIONS (The baseline syntax paths)
                    // =========================================================================

                    #None<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) #None"/>                                              //Explicit case 1
                    #Some #Left "Explicit Outer Left"<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) #Some #Left"/>                  //Explicit case 2
                    #Some #Right #None<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) #Some #Right #None"/>                                 //Explicit case 3
                    #Some #Right #Some #Left "Explicit Inner Left"<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) #Some #Right #Some #Left"/>     //Explicit case 4
                    #Some #Right #Some #Right 3.14159<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) #Some #Right #Some #Right"/>                  //Explicit case 5

                    // =========================================================================
                    // VALID SPEC-COMPLIANT INFERENCE PATHWAYS (Asymmetric Happy-Paths)
                    // =========================================================================

                    // Path A: Partially Inferred Inner Empty - above Explicit case 3
                    #Right #None<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) [#Some] #Right #None"/>

                    // Path B: Total Fall-Through Inference (The Only Deep Leaf Path Allowed) - Explicit 5
                    2.71828<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) [#Some] [#Right] [#Some] [#Right]"/>
                  ]<hint text=":Seq( :Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) )"/>
                }
                """;
        myFixture.configureByText("sum_type_torture_hints.stvn", hintText);
        runInlayVerification();
    }

    public void testInterleavedSumTypeInference() {
        setUseLongFormSumTypes(true);
        var cleanText = """
                {
                  :type :Seq(:Option(:Either(:String :Seq(:Option(:Either(:String :Float))))))
                  :body [
                    [ 2.71828 ]
                  ]
                }
                """;
        myFixture.configureByText("sum_type_interleaved_clean.stvn", cleanText);
        var highlights = myFixture.doHighlighting();
        var errors = new ArrayList<HighlightInfo>();
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                errors.add(info);
            }
        }
        assertEquals("Expected no highlighting errors, but found: " + errors, 0, errors.size());

        var hintText = """
                {
                  :type :Seq(:Option(:Either(:String :Seq(:Option(:Either(:String :Float))))))
                  :body [
                    [ 2.71828<hint text=":Option( :Either( :String :Float ) ) [#Some] [#Right]"/> ]<hint text=":Option( :Either( :String :Seq( :Option( :Either( :String :Float ) ) ) ) ) [#Some] [#Right]"/>
                  ]<hint text=":Seq( :Option( :Either( :String :Seq( :Option( :Either( :String :Float ) ) ) ) ) )"/>
                }
                """;
        myFixture.configureByText("sum_type_interleaved_hints.stvn", hintText);
        runInlayVerification();
    }

    public void testVariantStyleInspection() {
        myFixture.enableInspections(new org.stvnadore.plugin.validation.StvnVariantStyleInspection());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        projSettings.getState().enableRedundantTagInspection = true;
        projSettings.getState().enableFormDiscrepancyInspection = true;
        projSettings.getState().preferImpliedSumTypes = true;

        // 1. Warning A (Redundant Tag Option #Some) and quick-fix
        var text1 = """
                {
                  :type :Option(:Int32)
                  :body #Some 123
                }
                """;
        myFixture.configureByText("redundant_option.stvn", text1);
        var caretOffset1 = text1.indexOf("#Some");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset1);
        myFixture.doHighlighting();
        var actions1 = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertFalse("Expected Remove redundant tag quick-fix to be available", actions1.isEmpty());
        myFixture.launchAction(actions1.get(0));
        myFixture.checkResult("""
                {
                  :type :Option(:Int32)
                  :body 123
                }
                """);

        // 2. Warning A (Redundant Tag Either #Right) and quick-fix
        var text2 = """
                {
                  :type :Either(:Int32 :String)
                  :body #Right "hello"
                }
                """;
        myFixture.configureByText("redundant_either.stvn", text2);
        var caretOffset2 = text2.indexOf("#Right");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset2);
        myFixture.doHighlighting();
        var actions2 = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertFalse("Expected Remove redundant tag quick-fix to be available", actions2.isEmpty());
        myFixture.launchAction(actions2.get(0));
        myFixture.checkResult("""
                {
                  :type :Either(:Int32 :String)
                  :body "hello"
                }
                """);

        // 3. Warning B (Form Discrepancy short to long) and quick-fix
        setUseLongFormSumTypes(true);
        var text3 = """
                {
                  :type :Option(:Int32)
                  :body #S 123
                }
                """;
        myFixture.configureByText("form_option_to_long.stvn", text3);
        var caretOffset3 = text3.indexOf("#S");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset3);
        myFixture.doHighlighting();
        var actions3 = myFixture.filterAvailableIntentions("Change tag to #Some");
        assertFalse("Expected Change tag to #Some quick-fix to be available", actions3.isEmpty());
        myFixture.launchAction(actions3.get(0));
        myFixture.checkResult("""
                {
                  :type :Option(:Int32)
                  :body #Some 123
                }
                """);

        // 4. Warning B (Form Discrepancy long to short) and quick-fix
        setUseLongFormSumTypes(false);
        var text4 = """
                {
                  :type :Option(:Int32)
                  :body #Some 123
                }
                """;
        myFixture.configureByText("form_option_to_short.stvn", text4);
        var caretOffset4 = text4.indexOf("#Some");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset4);
        myFixture.doHighlighting();
        var actions4 = myFixture.filterAvailableIntentions("Change tag to #S");
        assertFalse("Expected Change tag to #S quick-fix to be available", actions4.isEmpty());
        myFixture.launchAction(actions4.get(0));
        myFixture.checkResult("""
                {
                  :type :Option(:Int32)
                  :body #S 123
                }
                """);

        // 5. Non-redundant nested option (avoiding ambiguity)
        var text5 = """
                {
                  :type :Option(:Option(:Int32))
                  :body #Some #None
                }
                """;
        myFixture.configureByText("non_redundant_nested.stvn", text5);
        var caretOffset5 = text5.indexOf("#Some");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset5);
        myFixture.doHighlighting();
        var actions5 = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected no Remove redundant tag quick-fix for nested #None", actions5.isEmpty());

        // 6. Non-redundant ambiguous either (avoiding ambiguity)
        var text6 = """
                {
                  :type :Either(:Int32 :Int32)
                  :body #Right 123
                }
                """;
        myFixture.configureByText("non_redundant_either.stvn", text6);
        var caretOffset6 = text6.indexOf("#Right");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset6);
        myFixture.doHighlighting();
        var actions6 = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected no Remove redundant tag quick-fix for ambiguous either", actions6.isEmpty());

        // 7. Boolean form discrepancy: short to long
        setUseLongFormSumTypes(true);
        var text7 = """
                {
                  :type :Boolean
                  :body #T
                }
                """;
        myFixture.configureByText("form_boolean_true_to_long.stvn", text7);
        var caretOffset7 = text7.indexOf("#T");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset7);
        myFixture.doHighlighting();
        var actions7 = myFixture.filterAvailableIntentions("Change tag to #TRUE");
        assertFalse("Expected Change tag to #TRUE quick-fix to be available", actions7.isEmpty());
        myFixture.launchAction(actions7.get(0));
        myFixture.checkResult("""
                {
                  :type :Boolean
                  :body #TRUE
                }
                """);

        var text8 = """
                {
                  :type :Boolean
                  :body #F
                }
                """;
        myFixture.configureByText("form_boolean_false_to_long.stvn", text8);
        var caretOffset8 = text8.indexOf("#F");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset8);
        myFixture.doHighlighting();
        var actions8 = myFixture.filterAvailableIntentions("Change tag to #FALSE");
        assertFalse("Expected Change tag to #FALSE quick-fix to be available", actions8.isEmpty());
        myFixture.launchAction(actions8.get(0));
        myFixture.checkResult("""
                {
                  :type :Boolean
                  :body #FALSE
                }
                """);

        // 8. Boolean form discrepancy: long to short
        setUseLongFormSumTypes(false);
        var text9 = """
                {
                  :type :Boolean
                  :body #TRUE
                }
                """;
        myFixture.configureByText("form_boolean_true_to_short.stvn", text9);
        var caretOffset9 = text9.indexOf("#TRUE");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset9);
        myFixture.doHighlighting();
        var actions9 = myFixture.filterAvailableIntentions("Change tag to #T");
        assertFalse("Expected Change tag to #T quick-fix to be available", actions9.isEmpty());
        myFixture.launchAction(actions9.get(0));
        myFixture.checkResult("""
                {
                  :type :Boolean
                  :body #T
                }
                """);

        var text10 = """
                {
                  :type :Boolean
                  :body #FALSE
                }
                """;
        myFixture.configureByText("form_boolean_false_to_short.stvn", text10);
        var caretOffset10 = text10.indexOf("#FALSE");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset10);
        myFixture.doHighlighting();
        var actions10 = myFixture.filterAvailableIntentions("Change tag to #F");
        assertFalse("Expected Change tag to #F quick-fix to be available", actions10.isEmpty());
        myFixture.launchAction(actions10.get(0));
        myFixture.checkResult("""
                {
                  :type :Boolean
                  :body #F
                }
                """);

        // 9. Verify Boolean is exempt from Warning A (redundancy checks)
        var text11 = """
                {
                  :type :Boolean
                  :body #TRUE
                }
                """;
        myFixture.configureByText("boolean_no_redundant.stvn", text11);
        var caretOffset11 = text11.indexOf("#TRUE");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset11);
        myFixture.doHighlighting();
        var actions11 = myFixture.filterAvailableIntentions("Remove redundant tag");
        assertTrue("Expected no Remove redundant tag quick-fix for Boolean", actions11.isEmpty());
    }

    public void testBooleanValidityInspection() {
        myFixture.enableInspections(new org.stvnadore.plugin.validation.StvnBooleanValidityInspection());
        setUseLongFormSumTypes(true);

        // 1. Invalid Casing Highlighting (#False in :Boolean context)
        var text1 = """
                {
                  :type :Boolean
                  :body #False
                }
                """;
        myFixture.configureByText("invalid_boolean_casing.stvn", text1);
        var highlights1 = myFixture.doHighlighting();
        var foundError1 = false;
        for (var info : highlights1) {
            var desc = info.getDescription();
            if (desc != null && desc.contains("Invalid boolean literal casing: '#False'")) {
                foundError1 = true;
                break;
            }
        }
        assertTrue("Expected invalid casing error to be highlighted on #False", foundError1);

        // 2. Quick-Fix Resolution - Long Form
        setUseLongFormSumTypes(true);
        var caretOffset1 = text1.indexOf("#False");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset1);
        var actions1 = myFixture.filterAvailableIntentions("Change tag to #FALSE");
        assertFalse("Expected Change tag to #FALSE quick-fix to be available", actions1.isEmpty());
        myFixture.launchAction(actions1.get(0));
        myFixture.checkResult("""
                {
                  :type :Boolean
                  :body #FALSE
                }
                """);

        // 3. Quick-Fix Resolution - Short Form
        var text2 = """
                {
                  :type :Boolean
                  :body #False
                }
                """;
        setUseLongFormSumTypes(false);
        myFixture.configureByText("invalid_boolean_casing_short.stvn", text2);
        var caretOffset2 = text2.indexOf("#False");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffset2);
        myFixture.doHighlighting();
        var actions2 = myFixture.filterAvailableIntentions("Change tag to #F");
        assertFalse("Expected Change tag to #F quick-fix to be available", actions2.isEmpty());
        myFixture.launchAction(actions2.get(0));
        myFixture.checkResult("""
                {
                  :type :Boolean
                  :body #F
                }
                """);

        // 4. Valid Case: No Flagging
        var text3 = """
                {
                  :type :Boolean
                  :body #TRUE
                }
                """;
        myFixture.configureByText("valid_boolean.stvn", text3);
        var highlights3 = myFixture.doHighlighting();
        var foundError3 = false;
        for (var info : highlights3) {
            var desc = info.getDescription();
            if (desc != null && desc.contains("Invalid boolean literal casing")) {
                foundError3 = true;
            }
        }
        assertFalse("Expected no invalid boolean casing errors for #TRUE", foundError3);

        // 5. Enum Exclusion
        var text4 = """
                {
                  :defs {
                    type MyEnum :Enum [ #False #True ]
                  }
                  :type MyEnum
                  :body #False
                }
                """;
        myFixture.configureByText("enum_exclusion.stvn", text4);
        var highlights4 = myFixture.doHighlighting();
        var foundError4 = false;
        for (var info : highlights4) {
            var desc = info.getDescription();
            if (desc != null && desc.contains("Invalid boolean literal casing")) {
                foundError4 = true;
            }
        }
        assertFalse("Expected no invalid boolean casing errors for Enum tag #False", foundError4);

        // 6. Inlay Hints Verification when Boolean Error is present
        var text5 = """
                {
                  :type :Tuple(:Boolean :Int32)
                  :body (
                    #False
                    42<hint text=":Int32"/>
                  )<hint text=":Tuple( :Boolean :Int32 )"/>
                }
                """;
        myFixture.configureByText("boolean_error_inlay.stvn", text5);
        runInlayVerification();

        // 7. Explicit Option Context Casing Error (#Some #t in :Option(:Boolean))
        var text7 = """
                {
                  :type :Option(:Boolean)
                  :body #Some #t
                }
                """;
        myFixture.configureByText("explicit_option_boolean_error.stvn", text7);
        var highlights7 = myFixture.doHighlighting();
        var foundError7 = false;
        for (var info : highlights7) {
            var desc = info.getDescription();
            if (desc != null && desc.contains("Invalid boolean literal casing: '#t'")) {
                foundError7 = true;
                break;
            }
        }
        assertTrue("Expected invalid casing error on explicit option nested #t", foundError7);

        // 8. Implicit Option Context Casing Error (#t in :Option(:Boolean))
        var text8 = """
                {
                  :type :Option(:Boolean)
                  :body #t
                }
                """;
        myFixture.configureByText("implicit_option_boolean_error.stvn", text8);
        var highlights8 = myFixture.doHighlighting();
        var foundError8 = false;
        for (var info : highlights8) {
            var desc = info.getDescription();
            if (desc != null && desc.contains("Invalid boolean literal casing: '#t'")) {
                foundError8 = true;
                break;
            }
        }
        assertTrue("Expected invalid casing error on implicit option nested #t", foundError8);

        // 9. Nested Trajectory Context matching image_f1bf3b.png
        var text9 = """
                {
                  :defs {
                    :Payload :SeqNonEmpty(
                      :Tuple(
                        :Option(:Boolean)
                        :Pagoda
                        :Either(:Boolean :Float64)
                        :Either(:Int32 :Float64)
                      )
                    )
                    :Pagoda :Either( :Int32 :StringNonEmpty )
                  }
                  :type :Tuple( :String :Payload )
                  :body (
                    "test"
                    [
                      (
                        #S #t
                        #Left -105
                        #R 3.14
                        42.0
                      )
                      (
                        #False
                        "non-empty value"
                        #R 3.14
                        #Left 42
                      )
                    ]
                  )
                }
                """;
        myFixture.configureByText("nested_trajectory_boolean_error.stvn", text9);
        var highlights9 = myFixture.doHighlighting();
        var foundTError = false;
        var foundFalseError = false;
        for (var info : highlights9) {
            var desc = info.getDescription();
            if (desc != null) {
                if (desc.contains("Invalid boolean literal casing: '#t'")) {
                    foundTError = true;
                }
                if (desc.contains("Invalid boolean literal casing: '#False'")) {
                    foundFalseError = true;
                }
            }
        }
        assertTrue("Expected invalid casing error on nested #t", foundTError);
        assertTrue("Expected invalid casing error on nested #False", foundFalseError);

        for (var info : highlights9) {
            var desc = info.getDescription();
            if (desc != null && (desc.contains("Invalid boolean literal casing: '#t'") || desc.contains("Invalid boolean literal casing: '#False'"))) {
                assertEquals("Should be ERROR severity", HighlightSeverity.ERROR, info.getSeverity());
            }
        }
    }

    public void testBooleanValidityInspection_StrictWhitelist() {
        myFixture.enableInspections(new org.stvnadore.plugin.validation.StvnBooleanValidityInspection());

        // 1. Test completely invalid boolean literal casing (#TRUEx and #xxxxx)
        var text = """
                {
                  :type :Tuple(:Boolean :Boolean)
                  :body (
                    #TRUEx
                    #xxxxx
                  )
                }
                """;
        myFixture.configureByText("strict_whitelist_boolean_invalid.stvn", text);
        var highlights = myFixture.doHighlighting();
        
        var foundTruexError = false;
        var foundXxxxxError = false;
        for (var info : highlights) {
            var desc = info.getDescription();
            if (desc != null) {
                if (desc.contains("Value '#TRUEx' is not a valid boolean literal. Expected exactly #TRUE, #T, #FALSE, or #F.")) {
                    foundTruexError = true;
                }
                if (desc.contains("Value '#xxxxx' is not a valid boolean literal. Expected exactly #TRUE, #T, #FALSE, or #F.")) {
                    foundXxxxxError = true;
                }
            }
        }
        assertTrue("Expected error on #TRUEx", foundTruexError);
        assertTrue("Expected error on #xxxxx", foundXxxxxError);

        // 2. Verify toggle-aware dual-choice quick-fixes (Long Form)
        setUseLongFormSumTypes(true);
        myFixture.configureByText("strict_whitelist_boolean_invalid_long.stvn", text);
        var offsetTruex = text.indexOf("#TRUEx");
        myFixture.getEditor().getCaretModel().moveToOffset(offsetTruex);
        myFixture.doHighlighting();
        var actionsLong = myFixture.filterAvailableIntentions("Change to ");
        var actionNamesLong = actionsLong.stream().map(a -> a.getText()).toList();
        assertTrue("Expected 'Change to #TRUE' to be available", actionNamesLong.contains("Change to #TRUE"));
        assertTrue("Expected 'Change to #FALSE' to be available", actionNamesLong.contains("Change to #FALSE"));

        // Apply Change to #TRUE
        var trueAction = actionsLong.stream().filter(a -> a.getText().equals("Change to #TRUE")).findFirst().get();
        myFixture.launchAction(trueAction);
        myFixture.checkResult("""
                {
                  :type :Tuple(:Boolean :Boolean)
                  :body (
                    #TRUE
                    #xxxxx
                  )
                }
                """);

        // 3. Verify toggle-aware dual-choice quick-fixes (Short Form)
        var textShort = """
                {
                  :type :Tuple(:Boolean :Boolean)
                  :body (
                    #TRUE
                    #xxxxx
                  )
                }
                """;
        setUseLongFormSumTypes(false);
        myFixture.configureByText("strict_whitelist_boolean_invalid_short.stvn", textShort);
        var offsetXxxxx = textShort.indexOf("#xxxxx");
        myFixture.getEditor().getCaretModel().moveToOffset(offsetXxxxx);
        myFixture.doHighlighting();
        var actionsShort = myFixture.filterAvailableIntentions("Change to ");
        var actionNamesShort = actionsShort.stream().map(a -> a.getText()).toList();
        assertTrue("Expected 'Change to #T' to be available", actionNamesShort.contains("Change to #T"));
        assertTrue("Expected 'Change to #F' to be available", actionNamesShort.contains("Change to #F"));

        // Apply Change to #F
        var falseAction = actionsShort.stream().filter(a -> a.getText().equals("Change to #F")).findFirst().get();
        myFixture.launchAction(falseAction);
        myFixture.checkResult("""
                {
                  :type :Tuple(:Boolean :Boolean)
                  :body (
                    #TRUE
                    #F
                  )
                }
                """);
    }

    public void testWorkspaceCycleDetection() {
        var fileA = myFixture.addFileToProject("fileA.stvn", """
                {
                  :defs {
                    :include [
                      "fileB.stvn_incl"
                    ]
                  }
                  :type :Int32
                  :body 42
                }
                """);
        var fileB = myFixture.addFileToProject("fileB.stvn_incl", """
                {
                  :defs {
                    :include [
                      "fileA.stvn"
                    ]
                  }
                }
                """);
        
        java.util.Map<String, String> workspaceMap = new java.util.HashMap<>();
        workspaceMap.put(org.stvnadore.core.StvnSchemaFlattener.normalizePath(fileA.getVirtualFile().getPath()), fileA.getText());
        workspaceMap.put(org.stvnadore.core.StvnSchemaFlattener.normalizePath(fileB.getVirtualFile().getPath()), fileB.getText());
        
        String entryPointPath = org.stvnadore.core.StvnSchemaFlattener.normalizePath(fileA.getVirtualFile().getPath());
        
        try {
            org.stvnadore.core.StvnSchemaFlattener.flatten(workspaceMap, entryPointPath);
            fail("Expected CyclicDependencyException to be thrown");
        } catch (org.stvnadore.core.validation.CyclicDependencyException ex) {
            java.util.List<String> canonicalPaths = ex.getOffendingIncludePathsCanonical();
            assertTrue("Expected canonical paths in cycle to contain fileA",
                    canonicalPaths.stream().anyMatch(p -> p.endsWith("fileA.stvn")));
            assertTrue("Expected canonical paths in cycle to contain fileB",
                    canonicalPaths.stream().anyMatch(p -> p.endsWith("fileB.stvn_incl")));
        }
    }

    public void testTrack5IdentityDependentCollections() {
        myFixture.configureByText(
            "identity_collections_invalid.stvn",
            "{\n" +
            "  :defs {\n" +
            "    :MyFloatSet :Set( :Float32 )\n" +
            "  }\n" +
            "  :type :MyFloatSet\n" +
            "  :body []\n" +
            "}\n"
        );
        var highlights = myFixture.doHighlighting();
        var found = false;
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null && desc.contains("Set elements require types to be #equatable #TRUE")) {
                    found = true;
                    var start = info.getStartOffset();
                    var end = info.getEndOffset();
                    var matchedText = myFixture.getEditor().getDocument().getText().substring(start, end);
                    assertEquals(":Float32", matchedText);
                }
            }
        }
        assertTrue("Expected equatability error highlighted on :Float32", found);
    }

    public void testTrack6NominalConstraints() {
        myFixture.configureByText(
            "nominal_constraints_invalid.stvn",
            "{\n" +
            "  :defs {\n" +
            "    :MyInt { #minIncl 10 #minExcl 20 } :Int32\n" +
            "  }\n" +
            "  :type :MyInt\n" +
            "  :body 15\n" +
            "}\n"
        );
        var highlights = myFixture.doHighlighting();
        var found = false;
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null && desc.contains("Constraint violation (:MyInt): #minIncl and #minExcl are mutually exclusive")) {
                    found = true;
                    var start = info.getStartOffset();
                    var end = info.getEndOffset();
                    var matchedText = myFixture.getEditor().getDocument().getText().substring(start, end);
                    assertEquals("{ #minIncl 10 #minExcl 20 }", matchedText);
                }
            }
        }
        assertTrue("Expected constraint error highlighted on { #minIncl 10 #minExcl 20 }", found);
    }

    public void testTrack7TypeSuffixSizing() {
        myFixture.configureByText(
            "suffix_sizing_invalid.stvn",
            "{\n" +
            "  :type :String0\n" +
            "  :body \"\"\n" +
            "}\n"
        );
        var highlights = myFixture.doHighlighting();
        var found = false;
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null && desc.contains("Constraint violation: Type suffix dimensions must be strictly positive (N >= 1)")) {
                    found = true;
                    var start = info.getStartOffset();
                    var end = info.getEndOffset();
                    var matchedText = myFixture.getEditor().getDocument().getText().substring(start, end);
                    assertEquals("0", matchedText);
                }
            }
        }
        assertTrue("Expected type suffix error highlighted on 0", found);
    }

    public void testTrack7SuffixSizingFallback() {
        var fileText = "{\n  :defs {\n    :BadSuffix :String0\n  }\n  :type :Int\n  :body 1\n}";
        var psiFile = myFixture.configureByText("suffix_error.stvn", fileText);
        var highlights = myFixture.doHighlighting();
        
        var targetHighlight = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Constraint violation: Type suffix"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected Track 7 type suffix sizing error highlights missing"));
            
        var expectedStartIndex = fileText.indexOf(":String0") + ":String".length();
        var expectedEndIndex = fileText.indexOf(":String0") + ":String0".length();
        
        assertEquals("Track 7 fallback must highlight only the invalid trailing suffix index", 
            expectedStartIndex, targetHighlight.getStartOffset());
        assertEquals("Track 7 fallback boundary mismatch", 
            expectedEndIndex, targetHighlight.getEndOffset());
    }

    public void testTracks6NominalConstraintsFallback() {
        var fileText = "{\n  :defs {\n    :BrokenRegex { #regex \"[\" } :String\n  }\n  :type :Set(:BrokenRegex)\n  :body [ \"test\" ]\n}";
        var psiFile = myFixture.configureByText("nominal_error.stvn", fileText);
        var highlights = myFixture.doHighlighting();
        
        var targetHighlight = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Constraint violation (:BrokenRegex)"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected Track 6 nominal constraint validation error highlights missing"));
            
        var expectedStartIndex = fileText.indexOf("#regex");
        var expectedEndIndex = fileText.indexOf("}", expectedStartIndex);
        
        assertTrue("Track 6 fallback range must encapsulate the specific offending metadata map entry",
            targetHighlight.getStartOffset() >= expectedStartIndex && targetHighlight.getEndOffset() <= expectedEndIndex);
    }

    public void testTrack5IdentityDependentCollectionsFallback() {
        var fileText = "{\n  :type :Set(:Float32)\n  :body [ 1.1 2.2 ]\n}";
        var psiFile = myFixture.configureByText("collection_identity_error.stvn", fileText);
        var highlights = myFixture.doHighlighting();
        
        var targetHighlight = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("Set elements require types to be #equatable #TRUE"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected Track 5 identity-dependent collection error highlights missing"));
            
        var expectedStartIndex = fileText.indexOf(":Float32");
        var expectedEndIndex = expectedStartIndex + ":Float32".length();
        
        assertEquals("Track 5 fallback must target the precise non-equatable component schema layout within the type parameter",
            expectedStartIndex, targetHighlight.getStartOffset());
        assertEquals("Track 5 fallback boundary mismatch",
            expectedEndIndex, targetHighlight.getEndOffset());
    }

    public void testChooseByNameContributor() {
        myFixture.configureByFile("valid-syntax/typed_constants.stvn");
        myFixture.doHighlighting();

        var contributor = new org.stvnadore.plugin.reference.StvnChooseByNameContributor();
        var scope = com.intellij.psi.search.GlobalSearchScope.projectScope(getProject());

        var names = new ArrayList<String>();
        contributor.processNames(names::add, scope, null);
        assertTrue("Expected MAX_RETRY in symbol names", names.contains("MAX_RETRY"));
        assertTrue("Expected API_HOST in symbol names", names.contains("API_HOST"));

        var elements = new ArrayList<com.intellij.navigation.NavigationItem>();
        var params = com.intellij.util.indexing.FindSymbolParameters.wrap("MAX_RETRY", scope);
        contributor.processElementsWithName("MAX_RETRY", elements::add, params);
        assertFalse("Expected to find MAX_RETRY navigation item", elements.isEmpty());
        assertEquals("#MAX_RETRY", ((com.intellij.psi.PsiElement) elements.get(0)).getText());
    }

    public void testFindUsagesConstants() {
        myFixture.configureByFile("valid-syntax/typed_constants.stvn");
        myFixture.doHighlighting();

        var text = myFixture.getEditor().getDocument().getText();
        var defIdx = text.indexOf("#MAX_RETRY");
        myFixture.getEditor().getCaretModel().moveToOffset(defIdx);

        var usages = myFixture.findUsages(myFixture.getElementAtCaret());
        assertFalse("Expected usages of #MAX_RETRY in body", usages.isEmpty());
    }

    public void testConstantHoverDocumentationProvider() {
        myFixture.configureByFile("valid-syntax/typed_constants.stvn");
        myFixture.doHighlighting();

        var text = myFixture.getEditor().getDocument().getText();
        var constOffset = text.indexOf("#MAX_RETRY");
        myFixture.getEditor().getCaretModel().moveToOffset(constOffset);

        var docProvider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var elementAtCaret = myFixture.getElementAtCaret();
        var doc = docProvider.generateDoc(elementAtCaret, null);
        assertNotNull("Expected documentation for #MAX_RETRY", doc);
        assertTrue("Expected documentation to contain #MAX_RETRY definition", doc.contains("#MAX_RETRY"));
        assertTrue("Expected documentation to contain Typed Constant", doc.contains("Typed Constant"));
    }

    public void testInlayHintsWithTrailingComments() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "trailing_comments.stvn",
            """
                {
                  :defs {
                    :OptText :Option( :String ) // trailing comment on defs alias
                  }
                  :type :Tuple( :OptText :Int32 ) // trailing comment on root type
                  :body (
                    "hello"<hint text=":OptText [#Some] (-> :String)"/>
                    42<hint text=":Int32"/>
                  )<hint text=":Tuple( :OptText :Int32 )"/>
                }
                """);
        runInlayVerification();
    }

    public void testInlayHintsWithMultiLineNestedComments() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "nested_comments.stvn",
            """
                {
                  :defs {
                    :OptText :Option( // multi-line comment before inner
                      :String // comment on inner type
                    ) // comment on closing paren
                    :Config :Tuple(
                      :OptText // first parameter
                      :Int32   // second parameter
                    )
                  }
                  :type :Tuple(
                    :Config // nested config tuple
                    :Map( :String :Int32 ) // map parameter
                  )
                  :body (
                    (
                      #None<hint text=":OptText #None"/>
                      100<hint text=":Int32"/>
                    )<hint text=":Config"/>
                    {
                      [ "key"<hint text=":String"/> 200<hint text=":Int32"/> ]
                    }<hint text=":Map( :String :Int32 )"/>
                  )<hint text=":Tuple( :Config :Map( :String :Int32 ) )"/>
                }
                """);
        runInlayVerification();
    }

    public void testHoverDocumentationWithCommentsInDefs() {
        myFixture.configureByText("hover_comments.stvn",
            """
                {
                  :defs {
                    :CommentedAlias :Seq( :Int32 ) // comment on seq alias
                    #CONST_VAL :Int32 // comment on constant type
                    42
                  }
                  :type :CommentedAlias
                  :body [ 1 2 3 ]
                }
                """
        );

        var text = myFixture.getEditor().getDocument().getText();
        var offset = text.lastIndexOf(":CommentedAlias");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);

        var ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("Type reference not found", ref);
        var resolved = ref.resolve();
        assertNotNull("Could not resolve reference", resolved);

        var originalElement = myFixture.getFile().findElementAt(offset);
        assertNotNull(originalElement);

        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var doc = provider.generateDoc(resolved, originalElement);
        assertNotNull("HTML documentation is null", doc);
        assertTrue("HTML must contain Type Alias", doc.contains("Type Alias:</b> :CommentedAlias"));
        assertTrue("HTML must contain clean Underlying Structure without comments",
                doc.contains("Underlying Structure:</b> :Seq( :Int32 )"));
        assertFalse("HTML must not leak comment text", doc.contains("// comment on seq alias"));

        var quickInfo = provider.getQuickNavigateInfo(resolved, originalElement);
        assertNotNull("Quick navigate info is null", quickInfo);
        assertEquals("Type Alias: :CommentedAlias", quickInfo);
    }

    public void testUnionBranchTypeMismatchRangeTargeting() {
        var code = """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple( :DisjointUnion )
              :body (
                #1 1024.0
              )
            }
            """;
        myFixture.configureByText("union_mismatch.stvn", code);
        var highlights = myFixture.doHighlighting();
        var documentText = myFixture.getEditor().getDocument().getText();

        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        assertEquals("Expected exactly 1 error highlight for union branch type mismatch", 1, errorHighlights.size());

        var info = errorHighlights.get(0);
        assertTrue("Error message must indicate integer/float type mismatch",
            info.getDescription() != null && info.getDescription().contains("Type mismatch: Expected integer, got float"));

        var start = info.getStartOffset();
        var end = info.getEndOffset();
        var matchedText = documentText.substring(start, end);

        assertEquals("Error highlight must target exactly '1024.0'", "1024.0", matchedText);

        // Defs & Type Immunity Invariant Assertions
        var defsIndex = documentText.indexOf(":defs");
        var typeIndex = documentText.indexOf(":type");
        var bodyIndex = documentText.indexOf(":body");

        assertTrue("Highlight must not cover root opening brace", start > 0);
        assertTrue("Highlight must start inside :body", start >= bodyIndex);
        assertFalse("Highlight must not overlap :defs", start <= defsIndex && end >= defsIndex);
        assertFalse("Highlight must not overlap :type", start <= typeIndex && end >= typeIndex);
    }

    public void testTupleElementTypeMismatchRangeTargeting() {
        var code = """
            {
              :type :Tuple( :Int32 :String )
              :body (
                42
                100
              )
            }
            """;
        myFixture.configureByText("tuple_elem_mismatch.stvn", code);
        var highlights = myFixture.doHighlighting();
        var documentText = myFixture.getEditor().getDocument().getText();

        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        assertEquals("Expected exactly 1 error highlight for tuple element mismatch", 1, errorHighlights.size());
        var info = errorHighlights.get(0);
        var matchedText = documentText.substring(info.getStartOffset(), info.getEndOffset());
        assertEquals("Error highlight must target exactly '100'", "100", matchedText);

        var bodyIndex = documentText.indexOf(":body");
        assertTrue("Highlight must reside inside :body", info.getStartOffset() >= bodyIndex);
    }

    public void testCollectionElementTypeMismatchRangeTargeting() {
        var code = """
            {
              :type :Seq( :Int32 )
              :body [
                10
                "invalid_string"
                30
              ]
            }
            """;
        myFixture.configureByText("seq_elem_mismatch.stvn", code);
        var highlights = myFixture.doHighlighting();
        var documentText = myFixture.getEditor().getDocument().getText();

        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        assertEquals("Expected exactly 1 error highlight for sequence element mismatch", 1, errorHighlights.size());
        var info = errorHighlights.get(0);
        var matchedText = documentText.substring(info.getStartOffset(), info.getEndOffset());
        assertEquals("Error highlight must target exactly '\"invalid_string\"'", "\"invalid_string\"", matchedText);
    }

    public void testMapEntryTypeMismatchRangeTargeting() {
        var code = """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "key1" 100 ]
                [ "key2" #TRUE ]
              }
            }
            """;
        myFixture.configureByText("map_mismatch.stvn", code);
        var highlights = myFixture.doHighlighting();
        var documentText = myFixture.getEditor().getDocument().getText();

        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        assertEquals("Expected exactly 1 error highlight for map value mismatch", 1, errorHighlights.size());
        var info = errorHighlights.get(0);
        var matchedText = documentText.substring(info.getStartOffset(), info.getEndOffset());
        assertEquals("Error highlight must target exactly '#TRUE'", "#TRUE", matchedText);
    }

    public void testUnlocatedDiagnosticAstFallbackTargeting() {
        var code = """
            {
              :defs {
                :TargetType :Int32
              }
              :type :TargetType
              :body 99.99
            }
            """;
        myFixture.configureByText("fallback_targeting.stvn", code);
        var highlights = myFixture.doHighlighting();
        var documentText = myFixture.getEditor().getDocument().getText();

        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        assertEquals(1, errorHighlights.size());
        var info = errorHighlights.get(0);

        var start = info.getStartOffset();
        var end = info.getEndOffset();
        var matchedText = documentText.substring(start, end);

        assertEquals("Error highlight must target exactly '99.99'", "99.99", matchedText);
        assertTrue("Start offset must be inside body", start >= documentText.indexOf(":body"));
    }

    public void testInferredOptionNominalAliasInlayHints() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "inferred_option_nominal.stvn",
            """
                {
                  :defs {
                    :OptText :Option( :String )
                  }
                  :type :Tuple( :OptText :Int32 )
                  :body (
                    "hello"<hint text=":OptText [#Some] (-> :String)"/>
                    42<hint text=":Int32"/>
                  )<hint text=":Tuple( :OptText :Int32 )"/>
                }
                """);
        runInlayVerification();
    }

    public void testExplicitVsInferredOptionVariantInlayHints() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "explicit_vs_inferred_option.stvn",
            """
                {
                  :defs {
                    :OptText :Option( :String )
                  }
                  :type :Tuple( :OptText :OptText :OptText )
                  :body (
                    #Some "explicit long"<hint text=":OptText #Some (-> :String)"/>
                    #S "explicit short"<hint text=":OptText #Some (-> :String)"/>
                    "implicit"<hint text=":OptText [#Some] (-> :String)"/>
                  )<hint text=":Tuple( :OptText :OptText :OptText )"/>
                }
                """);
        runInlayVerification();
    }

    public void testInferredEitherNominalAliasInlayHints() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "inferred_either_nominal.stvn",
            """
                {
                  :defs {
                    :Disjoint :Either( :Int32 :String )
                  }
                  :type :Tuple( :Disjoint :Disjoint )
                  :body (
                    #Left 100<hint text=":Disjoint #Left (-> :Int32)"/>
                    "implicit right"<hint text=":Disjoint [#Right] (-> :String)"/>
                  )<hint text=":Tuple( :Disjoint :Disjoint )"/>
                }
                """);
        runInlayVerification();
    }

    public void testInferredUnionBranchInlayHints() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "inferred_union_nominal.stvn",
            """
                {
                  :defs {
                    :MultiBranch :Union( :Int32 :String :Boolean )
                  }
                  :type :Tuple( :MultiBranch :MultiBranch :MultiBranch :MultiBranch )
                  :body (
                    42<hint text=":MultiBranch [#1] (-> :Int32)"/>
                    #1 100<hint text=":MultiBranch #1 (-> :Int32)"/>
                    "hello"<hint text=":MultiBranch [#2] (-> :String)"/>
                    #3 #TRUE<hint text=":MultiBranch #3 (-> :Boolean)"/>
                  )<hint text=":Tuple( :MultiBranch :MultiBranch :MultiBranch :MultiBranch )"/>
                }
                """);
        runInlayVerification();
    }

    public void testMultiElementTupleDelimiterSpacing() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "tuple_delimiter_spacing.stvn",
            """
                {
                  :defs {
                    :OptText :Option(:String)
                  }
                  :type :Tuple(:OptText :OptText :OptText)
                  :body (
                    "A"<hint text=":OptText [#Some] (-> :String)"/>
                    "B"<hint text=":OptText [#Some] (-> :String)"/>
                    "C"<hint text=":OptText [#Some] (-> :String)"/>
                  )<hint text=":Tuple( :OptText :OptText :OptText )"/>
                }
                """);
        runInlayVerification();
    }

    public void testShortFormInferredSumInlayHints() {
        setUseLongFormSumTypes(false);
        myFixture.configureByText(
            "short_form_inferred_sum.stvn",
            """
                {
                  :defs {
                    :OptText :Option( :String )
                    :Disjoint :Either( :Int32 :String )
                  }
                  :type :Tuple( :OptText :Disjoint :OptText )
                  :body (
                    "hello"<hint text=":OptText [#S] (-> :String)"/>
                    "right"<hint text=":Disjoint [#R] (-> :String)"/>
                    #Some "hello"<hint text=":OptText #S (-> :String)"/>
                  )<hint text=":Tuple( :OptText :Disjoint :OptText )"/>
                }
                """);
        runInlayVerification();
    }

    public void testInferredUnionBranchWithBooleanPayloads() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "inferred_union_boolean.stvn",
            """
                {
                  :defs {
                    :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
                  }
                  :type :Tuple( :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion )
                  :body (
                    #FALSE<hint text=":DisjointUnion [#2] (-> :Boolean)"/>
                    #TRUE<hint text=":DisjointUnion [#2] (-> :Boolean)"/>
                    #F<hint text=":DisjointUnion [#2] (-> :Boolean)"/>
                    #T<hint text=":DisjointUnion [#2] (-> :Boolean)"/>
                  )<hint text=":Tuple( :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion )"/>
                }
                """);
        runInlayVerification();
    }

    public void testInferredUnionBranchWithEnumVariantPayloads() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "inferred_union_enum.stvn",
            """
                {
                  :defs {
                    :DocStatus :Enum [ #Draft #Active #Archived ]
                    :ContentUnion :Union( :Int32 :DocStatus :String )
                  }
                  :type :Tuple( :ContentUnion :ContentUnion :ContentUnion )
                  :body (
                    100<hint text=":ContentUnion [#1] (-> :Int32)"/>
                    #Active<hint text=":ContentUnion [#2] (-> :DocStatus -> :Enum)"/>
                    "metadata"<hint text=":ContentUnion [#3] (-> :String)"/>
                  )<hint text=":Tuple( :ContentUnion :ContentUnion :ContentUnion )"/>
                }
                """);
        runInlayVerification();
    }

    public void testExplicitVsInferredUnionTagDiscrimination() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "explicit_vs_inferred_union.stvn",
            """
                {
                  :defs {
                    :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
                  }
                  :type :Tuple( :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion )
                  :body (
                    1024<hint text=":DisjointUnion [#1] (-> :Int32)"/>
                    #1 1024<hint text=":DisjointUnion #1 (-> :Int32)"/>
                    #FALSE<hint text=":DisjointUnion [#2] (-> :Boolean)"/>
                    #2 #FALSE<hint text=":DisjointUnion #2 (-> :Boolean)"/>
                    3.1415<hint text=":DisjointUnion [#3] (-> :Float64)"/>
                    #4 "hello"<hint text=":DisjointUnion #4 (-> :String)"/>
                  )<hint text=":Tuple( :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion )"/>
                }
                """);
        runInlayVerification();
    }

    public void testUnionTrajectoryInlayHintsWithUhohFixture() {
        setUseLongFormSumTypes(true);
        myFixture.addFileToProject(
            "network_primitives.stvn_inclf",
            """
            {
              :defs {
                :BitFlag        :Uint1
                :UnixPermission :Uint3
                :Port           { #minIncl 1 #maxIncl 65535 } :Uint16
                :HostName       { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :Protocol       :Enum [ #HTTP #HTTPS #TCP #UDP ]
              }
            }
            """);
        myFixture.configureByText(
            "uhoh_trajectory.stvn",
            """
            {
              :defs {
                :include ["network_primitives.stvn_inclf" { :HostName :RemoteHost }]
                :IPv4 { #regex "^(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}$" } :StringFixed15
                :RouteTable :MapInv( :RemoteHost :IpAddress )
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }

              :type :Tuple(
                :RouteTable
              )

              :body (
                {
                  [
                    "auth.internal"<hint text=":RemoteHost (-> :HostName -> :StringNonEmpty64)"/>
                    #1 "10.0.0.1"<hint text=":IpAddress #1 (-> :IPv4 -> :StringFixed15)"/>
                  ]
                  [
                    "db.internal"<hint text=":RemoteHost (-> :HostName -> :StringNonEmpty64)"/>
                    #2 "100.000.000.002"<hint text=":IpAddress #2 (-> :StringFixed15)"/>
                  ]
                }<hint text=":RouteTable"/>
              )<hint text=":Tuple( :RouteTable )"/>
            }
            """);
        runInlayVerification();
    }

    public void testOptionTrajectoryOverMultiHopAliasChain() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "option_alias_chain.stvn",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :RemoteHost :HostName
                :HostOption :Option( :RemoteHost )
              }
              :type :Tuple( :HostOption :HostOption :HostOption )
              :body (
                "auth.internal"<hint text=":HostOption [#Some] (-> :RemoteHost -> :HostName -> :StringNonEmpty64)"/>
                #Some "auth.internal"<hint text=":HostOption #Some (-> :RemoteHost -> :HostName -> :StringNonEmpty64)"/>
                #None<hint text=":HostOption #None"/>
              )<hint text=":Tuple( :HostOption :HostOption :HostOption )"/>
            }
            """);
        runInlayVerification();
    }

    public void testEitherTrajectoryOverMultiHopAliasChain() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "either_alias_chain.stvn",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :RemoteHost :HostName
                :IPv4 { #regex "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$" } :StringNonEmpty64
                :IpAddress :Union( :IPv4 :StringFixed15 )
                :Endpoint :Either( :RemoteHost :IpAddress )
              }
              :type :Tuple( :Endpoint :Endpoint :Endpoint )
              :body (
                #Left "auth.internal"<hint text=":Endpoint #Left (-> :RemoteHost -> :HostName -> :StringNonEmpty64)"/>
                #Right #1 "10.0.0.1"<hint text=":Endpoint #Right (-> :IpAddress #1 (-> :IPv4 -> :StringNonEmpty64))"/>
                #Right #2 "100.000.000.002"<hint text=":Endpoint #Right (-> :IpAddress #2 (-> :StringFixed15))"/>
              )<hint text=":Tuple( :Endpoint :Endpoint :Endpoint )"/>
            }
            """);
        runInlayVerification();
    }

    public void testNestedSumTypeSequenceInlays() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "nested_seq_union.stvn",
            """
            {
              :defs {
                :IPv4 { #regex "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$" } :StringNonEmpty64
                :IpAddress :Union( :IPv4 :StringFixed15 )
                :RouteSeq :Seq( :IpAddress )
              }
              :type :RouteSeq
              :body [
                #1 "10.0.0.1"<hint text=":IpAddress #1 (-> :IPv4 -> :StringNonEmpty64)"/>
                #1 "192.168.1.1"<hint text=":IpAddress #1 (-> :IPv4 -> :StringNonEmpty64)"/>
                #2 "100.000.000.002"<hint text=":IpAddress #2 (-> :StringFixed15)"/>
              ]<hint text=":RouteSeq"/>
            }
            """);
        runInlayVerification();
    }

    public void testAnonymousUnionInferredTagDiscrimination() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "anonymous_union_hints.stvn",
            """
                {
                  :type :Tuple( :Union( :Int32 :Boolean ) :Union( :Int32 :Boolean ) )
                  :body (
                    #FALSE<hint text=":Boolean"/>
                    #2 #FALSE<hint text=":Union( :Int32 :Boolean ) #2"/>
                  )<hint text=":Tuple( :Union( :Int32 :Boolean ) :Union( :Int32 :Boolean ) )"/>
                }
                """);
        runInlayVerification();
    }

    public void testFlatListToMapAutoHealerQuickFix() {
        myFixture.configureByText(
            "flat_map.stvn",
            """
            {
              :type :Map( :String :Int32 )
              :body [ "alpha" 100 "beta" 200 "gamma" 300 ]
            }
            """);

        var listLiteral = PsiTreeUtil.findChildOfType(myFixture.getFile(), ListLiteral.class);
        assertNotNull("ListLiteral must be parsed in editor AST", listLiteral);

        var quickFix = new StvnMapAutoHealerQuickFix(listLiteral);
        assertTrue("QuickFix must be available on flat list literal",
                   quickFix.isAvailable(getProject(), myFixture.getFile(), listLiteral, listLiteral));

        myFixture.launchAction(quickFix);

        myFixture.checkResult(
            """
            {
              :type :Map( :String :Int32 )
              :body { [ "alpha" 100 ] [ "beta" 200 ] [ "gamma" 300 ] }
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Repaired map must have zero compilation errors", hasErrors);
    }

    public void testOddNumberedFlatListHealerRecovery() {
        myFixture.configureByText(
            "odd_flat_map.stvn",
            """
            {
              :type :Map( :String :Int32 )
              :body [ "alpha" 100 "danglingKey" ]
            }
            """);

        var listLiteral = PsiTreeUtil.findChildOfType(myFixture.getFile(), ListLiteral.class);
        assertNotNull("ListLiteral must exist", listLiteral);

        var quickFix = new StvnMapAutoHealerQuickFix(listLiteral);
        myFixture.launchAction(quickFix);

        myFixture.checkResult(
            """
            {
              :type :Map( :String :Int32 )
              :body { [ "alpha" 100 ] } "danglingKey"
            }
            """);
    }

    public void testNestedExpressionsFlatListAutoHealer() {
        myFixture.configureByText(
            "nested_map.stvn",
            """
            {
              :defs {
                :Coordinate :Tuple( :Float64 :Float64 )
              }
              :type :Map( :String :Coordinate )
              :body [ "sf" ( 37.7749 -122.4194 ) "nyc" ( 40.7128 -74.0060 ) ]
            }
            """);

        var listLiteral = PsiTreeUtil.findChildOfType(myFixture.getFile(), ListLiteral.class);
        assertNotNull("ListLiteral must exist", listLiteral);

        var quickFix = new StvnMapAutoHealerQuickFix(listLiteral);
        myFixture.launchAction(quickFix);

        myFixture.checkResult(
            """
            {
              :defs {
                :Coordinate :Tuple( :Float64 :Float64 )
              }
              :type :Map( :String :Coordinate )
              :body { [ "sf" ( 37.7749 -122.4194 ) ] [ "nyc" ( 40.7128 -74.0060 ) ] }
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Repaired nested coordinate map must have zero compilation errors", hasErrors);
    }

    public void testMultiLineIndentedFlatListAutoHealer() {
        myFixture.configureByText(
            "multiline_map.stvn",
            """
            {
              :type :Map( :String :Int32 )
              :body [
                "alpha" 1
                "beta" 2
              ]
            }
            """);

        var listLiteral = PsiTreeUtil.findChildOfType(myFixture.getFile(), ListLiteral.class);
        assertNotNull(listLiteral);

        var quickFix = new StvnMapAutoHealerQuickFix(listLiteral);
        myFixture.launchAction(quickFix);

        myFixture.checkResult(
            """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "alpha" 1 ]
                [ "beta" 2 ]
              }
            }
            """);
    }

    public void testInvertedMapFlatListAutoHealer() {
        myFixture.configureByText(
            "map_inv.stvn",
            """
            {
              :type :MapInv( :String :Int32 )
              :body [ "a" 1 "b" 2 ]
            }
            """);

        var listLiteral = PsiTreeUtil.findChildOfType(myFixture.getFile(), ListLiteral.class);
        assertNotNull(listLiteral);

        var quickFix = new StvnMapAutoHealerQuickFix(listLiteral);
        myFixture.launchAction(quickFix);

        myFixture.checkResult(
            """
            {
              :type :MapInv( :String :Int32 )
              :body { [ "a" 1 ] [ "b" 2 ] }
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Repaired inverted map must have zero compilation errors", hasErrors);
    }

    public void testMapSchemaSkeletonGeneration() {
        myFixture.configureByText(
            "map_skeleton.stvn",
            """
            {
              :type :Map( :String :Int32 )
              :body <caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertTrue("Intention must be available on empty :body",
                   intention.isAvailable(getProject(), myFixture.getEditor(), element));

        myFixture.launchAction(intention);

        myFixture.checkResult(
            """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "placeholder" 0 ]
              }
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Scaffolded map must pass compiler type checks with zero errors", hasErrors);
    }

    public void testTupleSchemaSkeletonGeneration() {
        myFixture.configureByText(
            "tuple_skeleton.stvn",
            """
            {
              :type :Tuple( :String :Float64 :Boolean )
              :body <caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertTrue(intention.isAvailable(getProject(), myFixture.getEditor(), element));

        myFixture.launchAction(intention);

        myFixture.checkResult(
            """
            {
              :type :Tuple( :String :Float64 :Boolean )
              :body (
                "placeholder"
                0.0
                #FALSE
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Scaffolded tuple must pass compiler checks with zero errors", hasErrors);
    }

    public void testUnionSchemaSkeletonGeneration() {
        myFixture.configureByText(
            "union_skeleton.stvn",
            """
            {
              :type :Union( :Int32 :String )
              :body <caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertTrue(intention.isAvailable(getProject(), myFixture.getEditor(), element));

        myFixture.launchAction(intention);

        myFixture.checkResult(
            """
            {
              :type :Union( :Int32 :String )
              :body #1 0
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Scaffolded union default branch must compile with zero errors", hasErrors);
    }

    public void testNestedCompoundSchemaSkeletonGeneration() {
        myFixture.configureByText(
            "nested_skeleton.stvn",
            """
            {
              :defs {
                :Coord :Tuple( :Float64 :Float64 )
              }
              :type :Map( :String :Coord )
              :body <caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertTrue(intention.isAvailable(getProject(), myFixture.getEditor(), element));

        myFixture.launchAction(intention);

        myFixture.checkResult(
            """
            {
              :defs {
                :Coord :Tuple( :Float64 :Float64 )
              }
              :type :Map( :String :Coord )
              :body {
                [ "placeholder" (
                  0.0
                  0.0
                ) ]
              }
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Scaffolded nested coordinate map must compile with zero errors", hasErrors);
    }

    public void testOptionAndEitherSchemaSkeletonGeneration() {
        myFixture.configureByText(
            "sum_skeleton.stvn",
            """
            {
              :type :Tuple( :Option( :Int32 ) :Either( :String :Boolean ) )
              :body <caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertTrue(intention.isAvailable(getProject(), myFixture.getEditor(), element));

        myFixture.launchAction(intention);

        myFixture.checkResult(
            """
            {
              :type :Tuple( :Option( :Int32 ) :Either( :String :Boolean ) )
              :body (
                #Some 0
                #Right #FALSE
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Scaffolded sum types must compile with zero errors", hasErrors);
    }

    public void testEnumSchemaSkeletonGeneration() {
        myFixture.configureByText(
            "enum_skeleton.stvn",
            """
            {
              :type :Enum [ #Active #Suspended #Archived ]
              :body <caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertTrue(intention.isAvailable(getProject(), myFixture.getEditor(), element));

        myFixture.launchAction(intention);

        myFixture.checkResult(
            """
            {
              :type :Enum [ #Active #Suspended #Archived ]
              :body #Active
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Scaffolded enum must default to first variant tag with zero errors", hasErrors);
    }

    public void testIntentionUnavailableWhenBodyPopulated() {
        myFixture.configureByText(
            "populated_body.stvn",
            """
            {
              :type :Int32
              :body 42<caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertFalse("Intention must not be available when body already has valid content",
                    intention.isAvailable(getProject(), myFixture.getEditor(), element));
    }

    public void testDateTimeOffsetHighlightingAndInlays() {
        // 1. Valid :DateTimeOffset with Inlay Hints
        var validCode = """
            {
              :type :Seq( :DateTimeOffset )
              :body [ "2026-03-06T15:53:08Z"<hint text=":DateTimeOffset"/> "2026-03-06T15:53:08-06:00"<hint text=":DateTimeOffset"/> ]<hint text=":Seq( :DateTimeOffset )"/>
            }
            """;
        assertRootInlayHints(validCode);

        // 2. Invalid Zone Brackets Rejection & Precise Sub-Token Error Range
        var invalidCode = """
            {
              :type :Seq( :DateTimeOffset )
              :body [ "2026-03-15T08:00:00-05:00[America/Chicago]" ]
            }
            """;
        myFixture.configureByText("datetime_offset_invalid.stvn", invalidCode);
        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals(1, errorHighlights.size());
        var error = errorHighlights.get(0);
        assertTrue(error.getDescription() != null && error.getDescription().contains("Time zone brackets [...] are prohibited in :DateTimeOffset"));
        
        var documentText = myFixture.getEditor().getDocument().getText();
        var targetText = documentText.substring(error.getStartOffset(), error.getEndOffset());
        assertEquals("\"2026-03-15T08:00:00-05:00[America/Chicago]\"", targetText);
    }

    public void testDateTimeZonedHighlightingAndInlays() {
        // 1. Valid :DateTimeZoned with Inlay Hints
        var validCode = """
            {
              :type :Seq( :DateTimeZoned )
              :body [ "2026-03-15T08:00:00[America/Chicago]"<hint text=":DateTimeZoned"/> "2026-08-18T18:30:00[Europe/London]"<hint text=":DateTimeZoned"/> ]<hint text=":Seq( :DateTimeZoned )"/>
            }
            """;
        assertRootInlayHints(validCode);

        // 2. Explicit Offset Rejection
        var invalidOffsetCode = """
            {
              :type :Seq( :DateTimeZoned )
              :body [ "2026-03-15T08:00:00-05:00[America/Chicago]" ]
            }
            """;
        myFixture.configureByText("datetime_zoned_invalid_offset.stvn", invalidOffsetCode);
        var highlights = myFixture.doHighlighting();
        var error = highlights.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).findFirst().orElseThrow();
        assertTrue(error.getDescription() != null && error.getDescription().contains("Explicit offsets"));

        // 3. DST Spring-Forward Gap Detection
        var gapCode = """
            {
              :type :Seq( :DateTimeZoned )
              :body [ "2026-03-08T02:30:00[America/Chicago]" ]
            }
            """;
        myFixture.configureByText("datetime_zoned_gap.stvn", gapCode);
        var gapHighlights = myFixture.doHighlighting();
        var gapError = gapHighlights.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).findFirst().orElseThrow();
        assertTrue(gapError.getDescription() != null && gapError.getDescription().contains("falls into a DST spring-forward gap"));
    }

    public void testDateTimeAuditedHighlightingAndInlays() {
        // 1. Valid :DateTimeAudited with Inlay Hints
        var validCode = """
            {
              :type :Seq( :DateTimeAudited )
              :body [ "2026-03-15T08:00:00-05:00[America/Chicago]"<hint text=":DateTimeAudited"/> "2026-01-15T08:00:00-06:00[America/Chicago]"<hint text=":DateTimeAudited"/> ]<hint text=":Seq( :DateTimeAudited )"/>
            }
            """;
        assertRootInlayHints(validCode);

        // 2. Missing Zone Rejection
        var missingZoneCode = """
            {
              :type :Seq( :DateTimeAudited )
              :body [ "2026-03-15T08:00:00-05:00" ]
            }
            """;
        myFixture.configureByText("datetime_audited_missing_zone.stvn", missingZoneCode);
        var missingZoneHighlights = myFixture.doHighlighting();
        var missingZoneError = missingZoneHighlights.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).findFirst().orElseThrow();
        assertTrue(missingZoneError.getDescription() != null && missingZoneError.getDescription().contains("Mandates both an explicit UTC offset and an IANA zone ID"));

        // 3. Contradictory Offset Rejection
        var contradictoryCode = """
            {
              :type :Seq( :DateTimeAudited )
              :body [ "2026-03-15T08:00:00-07:00[America/Chicago]" ]
            }
            """;
        myFixture.configureByText("datetime_audited_contradictory.stvn", contradictoryCode);
        var contradictoryHighlights = myFixture.doHighlighting();
        var contradictoryError = contradictoryHighlights.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).findFirst().orElseThrow();
        assertTrue(contradictoryError.getDescription() != null && contradictoryError.getDescription().contains("Contradictory offset in :DateTimeAudited literal"));
        
        var documentText = myFixture.getEditor().getDocument().getText();
        var targetText = documentText.substring(contradictoryError.getStartOffset(), contradictoryError.getEndOffset());
        assertEquals("\"2026-03-15T08:00:00-07:00[America/Chicago]\"", targetText);
    }

    public void testDateTimeScaffoldingTemplates() {
        myFixture.configureByText(
            "temporal_skeleton.stvn",
            """
            {
              :type :Tuple( :DateTimeOffset :DateTimeZoned :DateTimeAudited )
              :body <caret>
            }
            """);

        var intention = new StvnSchemaSkeletonIntentionAction();
        var element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull(element);
        assertTrue(intention.isAvailable(getProject(), myFixture.getEditor(), element));

        myFixture.launchAction(intention);

        myFixture.checkResult(
            """
            {
              :type :Tuple( :DateTimeOffset :DateTimeZoned :DateTimeAudited )
              :body (
                "2026-08-18T18:00:00-05:00"
                "2026-08-18T18:00:00[America/Chicago]"
                "2026-08-18T18:00:00-05:00[America/Chicago]"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
        assertFalse("Scaffolded temporal tripartite types must compile with zero errors", hasErrors);
    }

    public void testHoverDocumentationForBuiltInTemporalTypes() {
        myFixture.configureByText("temporal_hover.stvn",
            """
            {
              :type :Tuple( :DateTimeOffset :DateTimeZoned :DateTimeAudited )
              :body (
                "2026-08-18T18:00:00-05:00"
                "2026-08-18T18:00:00[America/Chicago]"
                "2026-08-18T18:00:00-05:00[America/Chicago]"
              )
            }
            """
        );
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var text = myFixture.getEditor().getDocument().getText();

        // 1. :DateTimeOffset
        var offset1 = text.indexOf(":DateTimeOffset");
        var elem1 = myFixture.getFile().findElementAt(offset1);
        assertNotNull(elem1);
        var doc1 = provider.generateDoc(elem1, elem1);
        assertNotNull(doc1);
        assertTrue(doc1.contains("Primitive Type:</b> :DateTimeOffset"));
        var quick1 = provider.getQuickNavigateInfo(elem1, elem1);
        assertNotNull(quick1);
        assertTrue(quick1.contains(":DateTimeOffset"));

        // 2. :DateTimeZoned
        var offset2 = text.indexOf(":DateTimeZoned");
        var elem2 = myFixture.getFile().findElementAt(offset2);
        assertNotNull(elem2);
        var doc2 = provider.generateDoc(elem2, elem2);
        assertNotNull(doc2);
        assertTrue(doc2.contains("Primitive Type:</b> :DateTimeZoned"));

        // 3. :DateTimeAudited
        var offset3 = text.indexOf(":DateTimeAudited");
        var elem3 = myFixture.getFile().findElementAt(offset3);
        assertNotNull(elem3);
        var doc3 = provider.generateDoc(elem3, elem3);
        assertNotNull(doc3);
        assertTrue(doc3.contains("Primitive Type:</b> :DateTimeAudited"));
    }

    public void testMultiErrorSequenceHighlightingAndInlayResilience() {
        var code = """
            {
              :type :Seq( :Int32 )
              :body [
                10
                20.5
                30
                "invalid_string"
                50
              ]
            }
            """;
        myFixture.configureByText("multi_error_seq.stvn", code);

        // 1. Assert exactly 2 concurrent ERROR highlights
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected exactly 2 concurrent errors in sequence", 2, errors.size());

        // 2. Verify Error #1 (Float literal)
        var err1 = errors.get(0);
        assertTrue(err1.getDescription() != null && err1.getDescription().contains("Expected integer, got float"));
        var doc = myFixture.getEditor().getDocument().getText();
        assertEquals("20.5", doc.substring(err1.getStartOffset(), err1.getEndOffset()));

        // 3. Verify Error #2 (String literal)
        var err2 = errors.get(1);
        assertTrue(err2.getDescription() != null && err2.getDescription().contains("Expected integer, got string"));
        assertEquals("\"invalid_string\"", doc.substring(err2.getStartOffset(), err2.getEndOffset()));

        // 4. Verify Inlay Hints render on valid sibling elements
        var annotatedCode = """
            {
              :type :Seq( :Int32 )
              :body [
                10<hint text=":Int32"/>
                20.5
                30<hint text=":Int32"/>
                "invalid_string"
                50<hint text=":Int32"/>
              ]<hint text=":Seq( :Int32 )"/>
            }
            """;
        myFixture.configureByText("multi_error_seq_hints.stvn", annotatedCode);
        runInlayVerification();
    }

    public void testMultiErrorTuplePositionalSlots() {
        var code = """
            {
              :type :Tuple( :Int32 :String :Boolean :Float64 )
              :body (
                "not_an_int"
                "valid_string"
                12345
                3.14159
              )
            }
            """;
        myFixture.configureByText("multi_error_tuple.stvn", code);

        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected exactly 2 concurrent errors across tuple slots", 2, errors.size());

        var doc = myFixture.getEditor().getDocument().getText();

        // Slot 0 error check
        var err0 = errors.get(0);
        assertTrue(err0.getDescription() != null && err0.getDescription().contains("Expected integer, got string"));
        assertEquals("\"not_an_int\"", doc.substring(err0.getStartOffset(), err0.getEndOffset()));

        // Slot 2 error check
        var err2 = errors.get(1);
        assertTrue(err2.getDescription() != null && err2.getDescription().contains("Expected boolean, got integer"));
        assertEquals("12345", doc.substring(err2.getStartOffset(), err2.getEndOffset()));

        // Verify Inlay Hints on valid slots 1 and 3
        var annotatedCode = """
            {
              :type :Tuple( :Int32 :String :Boolean :Float64 )
              :body (
                "not_an_int"
                "valid_string"<hint text=":String"/>
                12345
                3.14159<hint text=":Float64"/>
              )<hint text=":Tuple( :Int32 :String :Boolean :Float64 )"/>
            }
            """;
        myFixture.configureByText("multi_error_tuple_hints.stvn", annotatedCode);
        runInlayVerification();
    }

    public void testMultiErrorMapDuplicateKeysAndInvalidValues() {
        var code = """
            {
              :type :Map( :String :Int32 )
              :body {
                [ "alpha" 100 ]
                [ "beta"  "not_an_int" ]
                [ "alpha" 300 ]
              }
            }
            """;
        myFixture.configureByText("multi_error_map.stvn", code);

        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected duplicate key error and type mismatch error simultaneously", 2, errors.size());

        var doc = myFixture.getEditor().getDocument().getText();
        var hasDuplicateKeyError = errors.stream()
            .anyMatch(h -> h.getDescription() != null && h.getDescription().contains("Duplicate map key detected") &&
                           doc.substring(h.getStartOffset(), h.getEndOffset()).equals("\"alpha\""));
        var hasTypeMismatchError = errors.stream()
            .anyMatch(h -> h.getDescription() != null && h.getDescription().contains("Expected integer, got string") &&
                           doc.substring(h.getStartOffset(), h.getEndOffset()).equals("\"not_an_int\""));

        assertTrue("Duplicate key must be highlighted", hasDuplicateKeyError);
        assertTrue("Type mismatch must be highlighted", hasTypeMismatchError);
    }

    public void testMultiErrorDiagnosticThresholdLimiting() {
        var sb = new StringBuilder("{\n  :type :Seq( :Int32 )\n  :body [\n");
        for (int i = 0; i < 110; i++) {
            sb.append("    \"string_error_").append(i).append("\"\n");
        }
        sb.append("  ]\n}\n");

        myFixture.configureByText("threshold_test.stvn", sb.toString());
        var highlights = myFixture.doHighlighting();

        var warnings = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.WARNING))
            .toList();
        assertTrue("Expected threshold truncation warning", 
            warnings.stream().anyMatch(w -> w.getDescription() != null && w.getDescription().contains("Diagnostic threshold limit")));
    }

    public void testHoverDocumentationForTypeReferencesInMapInv() {
        myFixture.addFileToProject(
            "network_primitives.stvn_inclf",
            """
            {
              :defs {
                :BitFlag        :Uint1
                :UnixPermission :Uint3
                :Port           { #minIncl 1 #maxIncl 65535 } :Uint16
                :HostName       { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
                :Protocol       :Enum [ #HTTP #HTTPS #TCP #UDP ]
              }
            }
            """);
        myFixture.configureByText(
            "uhoh.stvn",
            """
            {
              :defs {
                :include ["network_primitives.stvn_inclf" { :HostName :RemoteHost }]
                :IPv4 { #regex "^(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}$" } :StringFixed15
                :RouteTable :MapInv( :RemoteHost :IpAddress )
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }

              :type :Tuple(
                :RouteTable
              )

              :body (
                {
                  [ "auth.internal" #1 "10.0.0.1" ]
                  [ "db.internal" #2 "100.000.000.002" ]
                }
              )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var routeTableDefIdx = text.indexOf(":RouteTable :MapInv(");
        assertTrue(routeTableDefIdx != -1);

        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        // 1. Hover over :RemoteHost on line 5 (inside :RouteTable :MapInv( :RemoteHost :IpAddress ))
        var remoteHostOffset = text.indexOf(":RemoteHost", routeTableDefIdx);
        var remoteHostElem = myFixture.getFile().findElementAt(remoteHostOffset);
        assertNotNull(remoteHostElem);
        var customDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), remoteHostElem, remoteHostOffset);
        assertNotNull("Custom doc element for :RemoteHost in :MapInv is null", customDocElem);
        var remoteHostDoc = provider.generateDoc(customDocElem, remoteHostElem);
        assertNotNull("Hover documentation for :RemoteHost in :MapInv is null", remoteHostDoc);
        assertTrue("Doc must contain Type Alias", remoteHostDoc.contains("Type Alias:</b> :RemoteHost"));
        assertTrue("Doc must contain imported source", remoteHostDoc.contains("network_primitives.stvn_inclf"));
        assertTrue("Doc must contain resolution trajectory", remoteHostDoc.contains(":RemoteHost &rarr; :HostName &rarr; :StringNonEmpty64"));
        assertTrue("Doc must contain underlying structure", remoteHostDoc.contains("Underlying Structure:</b> :StringNonEmpty64"));

        // 2. Hover over :IpAddress on line 5 (inside :RouteTable :MapInv( :RemoteHost :IpAddress ))
        var ipAddressOffset = text.indexOf(":IpAddress", routeTableDefIdx);
        var ipAddressElem = myFixture.getFile().findElementAt(ipAddressOffset);
        assertNotNull(ipAddressElem);
        var ipCustomDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), ipAddressElem, ipAddressOffset);
        assertNotNull("Custom doc element for :IpAddress in :MapInv is null", ipCustomDocElem);
        var ipAddressDoc = provider.generateDoc(ipCustomDocElem, ipAddressElem);
        assertNotNull("Hover documentation for :IpAddress in :MapInv is null", ipAddressDoc);
        assertTrue("Doc must contain Type Alias", ipAddressDoc.contains("Type Alias:</b> :IpAddress"));
        assertTrue("Doc must contain underlying structure", ipAddressDoc.contains("Underlying Structure:</b> :Union( :IPv4 :StringFixed15 )"));
    }

    public void testHoverDocumentationForTypeReferencesInUnion() {
        myFixture.addFileToProject(
            "network_primitives.stvn_inclf",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
              }
            }
            """);
        myFixture.configureByText(
            "uhoh.stvn",
            """
            {
              :defs {
                :include ["network_primitives.stvn_inclf" { :HostName :RemoteHost }]
                :IPv4 { #regex "^(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}$" } :StringFixed15
                :RouteTable :MapInv( :RemoteHost :IpAddress )
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }

              :type :Tuple(
                :RouteTable
              )

              :body (
                {
                  [ "auth.internal" #1 "10.0.0.1" ]
                }
              )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var unionDefIdx = text.indexOf(":IpAddress :Union(");
        assertTrue(unionDefIdx != -1);

        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        // Hover over :IPv4 on line 6 (inside :IpAddress :Union( :IPv4 :StringFixed15 ))
        var ipv4Offset = text.indexOf(":IPv4", unionDefIdx);
        var ipv4Elem = myFixture.getFile().findElementAt(ipv4Offset);
        assertNotNull(ipv4Elem);
        var customDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), ipv4Elem, ipv4Offset);
        assertNotNull(customDocElem);
        var ipv4Doc = provider.generateDoc(customDocElem, ipv4Elem);
        assertNotNull("Hover documentation for :IPv4 in :Union is null", ipv4Doc);
        assertTrue("Doc must contain Type Alias", ipv4Doc.contains("Type Alias:</b> :IPv4"));
        assertTrue("Doc must contain underlying structure", ipv4Doc.contains("Underlying Structure:</b> :StringFixed15"));
    }

    public void testHoverDocumentationForTypeReferencesInTuple() {
        myFixture.addFileToProject(
            "network_primitives.stvn_inclf",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
              }
            }
            """);
        myFixture.configureByText(
            "uhoh.stvn",
            """
            {
              :defs {
                :include ["network_primitives.stvn_inclf" { :HostName :RemoteHost }]
                :IPv4 { #regex "^(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}$" } :StringFixed15
                :RouteTable :MapInv( :RemoteHost :IpAddress )
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }

              :type :Tuple(
                :RouteTable
              )

              :body (
                {
                  [ "auth.internal" #1 "10.0.0.1" ]
                }
              )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var typeTupleIdx = text.indexOf(":type :Tuple(");
        assertTrue(typeTupleIdx != -1);

        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        // Hover over :RouteTable on line 10 (inside :type :Tuple( :RouteTable ))
        var routeTableOffset = text.indexOf(":RouteTable", typeTupleIdx);
        var routeTableElem = myFixture.getFile().findElementAt(routeTableOffset);
        assertNotNull(routeTableElem);
        var customDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), routeTableElem, routeTableOffset);
        assertNotNull(customDocElem);
        var routeTableDoc = provider.generateDoc(customDocElem, routeTableElem);
        assertNotNull("Hover documentation for :RouteTable in :Tuple is null", routeTableDoc);
        assertTrue("Doc must contain Type Alias", routeTableDoc.contains("Type Alias:</b> :RouteTable"));
        assertTrue("Doc must contain underlying structure", routeTableDoc.contains("Underlying Structure:</b> :MapInv( :RemoteHost :IpAddress )"));
    }

    public void testGoToDefinitionForCompositeSchemaArguments() {
        myFixture.addFileToProject(
            "network_primitives.stvn_inclf",
            """
            {
              :defs {
                :HostName { #regex "^[a-zA-Z0-9.-]+$" } :StringNonEmpty64
              }
            }
            """);
        myFixture.configureByText(
            "uhoh.stvn",
            """
            {
              :defs {
                :include ["network_primitives.stvn_inclf" { :HostName :RemoteHost }]
                :IPv4 { #regex "^(?:[0-9]{1,3}\\\\.){3}[0-9]{1,3}$" } :StringFixed15
                :RouteTable :MapInv( :RemoteHost :IpAddress )
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }

              :type :Tuple(
                :RouteTable
              )

              :body (
                {
                  [ "auth.internal" #1 "10.0.0.1" ]
                }
              )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();

        // 1. Navigation on :RemoteHost inside :MapInv(...) -> navigates to :include binding
        var routeTableDefIdx = text.indexOf(":RouteTable :MapInv(");
        var remoteHostOffset = text.indexOf(":RemoteHost", routeTableDefIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(remoteHostOffset);
        var remoteRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("PsiReference not found for :RemoteHost in :MapInv", remoteRef);
        var resolvedRemote = remoteRef.resolve();
        assertNotNull("Could not resolve :RemoteHost in :MapInv", resolvedRemote);
        assertTrue("Resolved must be TypeKeyword", resolvedRemote instanceof org.stvnadore.psi.TypeKeyword);
        assertEquals(":RemoteHost", resolvedRemote.getText());
        assertTrue("Resolved parent must be IncludeMapAlias", resolvedRemote.getParent() instanceof org.stvnadore.psi.IncludeMapAlias);

        // 2. Navigation on :IPv4 inside :Union(...) -> navigates to :defs declaration
        var unionDefIdx = text.indexOf(":IpAddress :Union(");
        var ipv4Offset = text.indexOf(":IPv4", unionDefIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(ipv4Offset);
        var ipv4Ref = myFixture.getReferenceAtCaretPosition();
        assertNotNull("PsiReference not found for :IPv4 in :Union", ipv4Ref);
        var resolvedIpv4 = ipv4Ref.resolve();
        assertNotNull("Could not resolve :IPv4 in :Union", resolvedIpv4);
        assertEquals(":IPv4", resolvedIpv4.getText());
        assertTrue("Resolved parent must be TypeDefinition", resolvedIpv4.getParent() instanceof org.stvnadore.psi.TypeDefinition);

        // 3. Navigation on :RouteTable inside :type :Tuple(...) -> navigates to :defs declaration
        var typeTupleIdx = text.indexOf(":type :Tuple(");
        var routeTableOffset = text.indexOf(":RouteTable", typeTupleIdx);
        myFixture.getEditor().getCaretModel().moveToOffset(routeTableOffset);
        var routeTableRef = myFixture.getReferenceAtCaretPosition();
        assertNotNull("PsiReference not found for :RouteTable in :Tuple", routeTableRef);
        var resolvedRouteTable = routeTableRef.resolve();
        assertNotNull("Could not resolve :RouteTable in :Tuple", resolvedRouteTable);
        assertEquals(":RouteTable", resolvedRouteTable.getText());
        assertTrue("Resolved parent must be TypeDefinition", resolvedRouteTable.getParent() instanceof org.stvnadore.psi.TypeDefinition);
    }

    public void testHoverDocumentationForBuiltInPrimitivesAndConstructors() {
        myFixture.configureByText(
            "primitives_constructors.stvn",
            """
            {
              :defs {
                :IPv4 :StringFixed15
                :IpAddress :Union( :IPv4 :StringFixed15 )
              }
              :type :Tuple( :IpAddress :MapInv( :String :Int32 ) )
              :body ( #1 "10.0.0.1" { [ "a" 1 ] } )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        // 1. Hover on :StringFixed15 (primitive)
        var strFixedOffset = text.indexOf(":StringFixed15");
        var strFixedElem = myFixture.getFile().findElementAt(strFixedOffset);
        assertNotNull(strFixedElem);
        var strFixedDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), strFixedElem, strFixedOffset);
        assertNotNull(strFixedDocElem);
        var strFixedDoc = provider.generateDoc(strFixedDocElem, strFixedElem);
        assertNotNull("Hover documentation for :StringFixed15 is null", strFixedDoc);
        assertTrue(strFixedDoc.contains("Built-in String Type:</b> :StringFixed15"));
        assertTrue(strFixedDoc.contains("15 characters"));

        // 2. Hover on :Tuple (product constructor)
        var tupleOffset = text.indexOf(":Tuple(");
        var tupleElem = myFixture.getFile().findElementAt(tupleOffset);
        assertNotNull(tupleElem);
        var tupleDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), tupleElem, tupleOffset);
        assertNotNull(tupleDocElem);
        var tupleDoc = provider.generateDoc(tupleDocElem, tupleElem);
        assertNotNull("Hover documentation for :Tuple is null", tupleDoc);
        assertTrue(tupleDoc.contains("Product Type Constructor:</b> :Tuple"));

        // 3. Hover on :MapInv (collection constructor)
        var mapInvOffset = text.indexOf(":MapInv(");
        var mapInvElem = myFixture.getFile().findElementAt(mapInvOffset);
        assertNotNull(mapInvElem);
        var mapInvDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), mapInvElem, mapInvOffset);
        assertNotNull(mapInvDocElem);
        var mapInvDoc = provider.generateDoc(mapInvDocElem, mapInvElem);
        assertNotNull("Hover documentation for :MapInv is null", mapInvDoc);
        assertTrue(mapInvDoc.contains("Collection Constructor:</b> :MapInv"));
    }

    public void testPreludeHoverDocumentation() {
        myFixture.configureByText(
            "prelude_hover_test.stvn",
            """
            {
              :defs {
                :UserIp :IPv4
                :UserId :Uuid
                :SessionId :Ulid
                :Digest :Sha256
                :AppVersion :SemVer
                :Contact :Email
                :HttpPort :Port
                :CompletionRate :Percentage
                :RiskScore :Probability
                :Price :Currency
                :GeoLat :Latitude
                :GeoLon :Longitude
              }
              :type :Tuple(
                :UserIp
                :UserId
                :SessionId
                :Digest
                :AppVersion
                :Contact
                :HttpPort
                :CompletionRate
                :RiskScore
                :Price
                :GeoLat
                :GeoLon
              )
              :body (
                "127.0.0.1"
                "123e4567-e89b-12d3-a456-426614174000"
                "01ARZ3NDEKTSV4RRFFQ69G5FAV"
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                "1.0.0"
                "developer@stvnadore.org"
                8080
                99.5
                0.75
                19.99
                37.7749
                -122.4194
              )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        // 1. Assert :IPv4 Hover
        var ipv4Offset = text.indexOf(":IPv4");
        var ipv4Elem = myFixture.getFile().findElementAt(ipv4Offset);
        assertNotNull(ipv4Elem);
        var ipv4DocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), ipv4Elem, ipv4Offset);
        assertNotNull(ipv4DocElem);
        var ipv4Doc = provider.generateDoc(ipv4DocElem, ipv4Elem);
        assertNotNull("Hover doc for :IPv4 must not be null", ipv4Doc);
        assertTrue(ipv4Doc.contains("Standard Library Prelude:</b> :IPv4"));
        assertTrue(ipv4Doc.contains("dotted-quad IPv4 internet protocol address"));
        assertTrue(ipv4Doc.contains("0.0.0.0"));

        // 2. Assert :Uuid Hover
        var uuidOffset = text.indexOf(":Uuid");
        var uuidElem = myFixture.getFile().findElementAt(uuidOffset);
        assertNotNull(uuidElem);
        var uuidDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), uuidElem, uuidOffset);
        assertNotNull(uuidDocElem);
        var uuidDoc = provider.generateDoc(uuidDocElem, uuidElem);
        assertNotNull("Hover doc for :Uuid must not be null", uuidDoc);
        assertTrue(uuidDoc.contains("Standard Library Prelude:</b> :Uuid"));
        assertTrue(uuidDoc.contains("RFC 4122 Universally Unique Identifier"));

        // 3. Assert :Ulid Hover
        var ulidOffset = text.indexOf(":Ulid");
        var ulidElem = myFixture.getFile().findElementAt(ulidOffset);
        assertNotNull(ulidElem);
        var ulidDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), ulidElem, ulidOffset);
        assertNotNull(ulidDocElem);
        var ulidDoc = provider.generateDoc(ulidDocElem, ulidElem);
        assertNotNull("Hover doc for :Ulid must not be null", ulidDoc);
        assertTrue(ulidDoc.contains("Standard Library Prelude:</b> :Ulid"));
        assertTrue(ulidDoc.contains("Crockford's Base32"));

        // 4. Assert :Sha256 Hover
        var sha256Offset = text.indexOf(":Sha256");
        var sha256Elem = myFixture.getFile().findElementAt(sha256Offset);
        assertNotNull(sha256Elem);
        var sha256DocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), sha256Elem, sha256Offset);
        assertNotNull(sha256DocElem);
        var sha256Doc = provider.generateDoc(sha256DocElem, sha256Elem);
        assertNotNull("Hover doc for :Sha256 must not be null", sha256Doc);
        assertTrue(sha256Doc.contains("Standard Library Prelude:</b> :Sha256"));
        assertTrue(sha256Doc.contains("SHA-256 cryptographic hash (64 hex characters)"));
        assertTrue(sha256Doc.contains(":StringFixed64"));
        assertTrue(sha256Doc.contains("^[0-9a-fA-F]{64}$"));

        // 5. Assert :SemVer Hover
        var semVerOffset = text.indexOf(":SemVer");
        var semVerElem = myFixture.getFile().findElementAt(semVerOffset);
        assertNotNull(semVerElem);
        var semVerDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), semVerElem, semVerOffset);
        assertNotNull(semVerDocElem);
        var semVerDoc = provider.generateDoc(semVerDocElem, semVerElem);
        assertNotNull("Hover doc for :SemVer must not be null", semVerDoc);
        assertTrue(semVerDoc.contains("Standard Library Prelude:</b> :SemVer"));
        assertTrue(semVerDoc.contains("Semantic Versioning 2.0.0"));

        // 6. Assert :Email Hover
        var emailOffset = text.indexOf(":Email");
        var emailElem = myFixture.getFile().findElementAt(emailOffset);
        assertNotNull(emailElem);
        var emailDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), emailElem, emailOffset);
        assertNotNull(emailDocElem);
        var emailDoc = provider.generateDoc(emailDocElem, emailElem);
        assertNotNull("Hover doc for :Email must not be null", emailDoc);
        assertTrue(emailDoc.contains("Standard Library Prelude:</b> :Email"));
        assertTrue(emailDoc.contains("RFC 5322 electronic mail address"));

        // 7. Assert :Port Hover
        var portOffset = text.indexOf(":Port");
        var portElem = myFixture.getFile().findElementAt(portOffset);
        assertNotNull(portElem);
        var portDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), portElem, portOffset);
        assertNotNull(portDocElem);
        var portDoc = provider.generateDoc(portDocElem, portElem);
        assertNotNull("Hover doc for :Port must not be null", portDoc);
        assertTrue(portDoc.contains("Standard Library Prelude:</b> :Port"));
        assertTrue(portDoc.contains("65535"));

        // 8. Assert :Percentage Hover
        var percOffset = text.indexOf(":Percentage");
        var percElem = myFixture.getFile().findElementAt(percOffset);
        assertNotNull(percElem);
        var percDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), percElem, percOffset);
        assertNotNull(percDocElem);
        var percDoc = provider.generateDoc(percDocElem, percElem);
        assertNotNull("Hover doc for :Percentage must not be null", percDoc);
        assertTrue(percDoc.contains("Standard Library Prelude:</b> :Percentage"));
        assertTrue(percDoc.contains("100.0"));

        // 9. Assert :Probability Hover
        var probOffset = text.indexOf(":Probability");
        var probElem = myFixture.getFile().findElementAt(probOffset);
        assertNotNull(probElem);
        var probDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), probElem, probOffset);
        assertNotNull(probDocElem);
        var probDoc = provider.generateDoc(probDocElem, probElem);
        assertNotNull("Hover doc for :Probability must not be null", probDoc);
        assertTrue(probDoc.contains("Standard Library Prelude:</b> :Probability"));
        assertTrue(probDoc.contains("1.0"));

        // 10. Assert :Currency Hover
        var currencyOffset = text.indexOf(":Currency");
        var currencyElem = myFixture.getFile().findElementAt(currencyOffset);
        assertNotNull(currencyElem);
        var currencyDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), currencyElem, currencyOffset);
        assertNotNull(currencyDocElem);
        var currencyDoc = provider.generateDoc(currencyDocElem, currencyElem);
        assertNotNull("Hover doc for :Currency must not be null", currencyDoc);
        assertTrue(currencyDoc.contains("Standard Library Prelude:</b> :Currency"));
        assertTrue(currencyDoc.contains("arbitrary-precision exact decimal currency amount"));

        // 11. Assert :Latitude Hover
        var latOffset = text.indexOf(":Latitude");
        var latElem = myFixture.getFile().findElementAt(latOffset);
        assertNotNull(latElem);
        var latDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), latElem, latOffset);
        assertNotNull(latDocElem);
        var latDoc = provider.generateDoc(latDocElem, latElem);
        assertNotNull("Hover doc for :Latitude must not be null", latDoc);
        assertTrue(latDoc.contains("Standard Library Prelude:</b> :Latitude"));
        assertTrue(latDoc.contains("-90.0"));

        // 12. Assert :Longitude Hover
        var lonOffset = text.indexOf(":Longitude");
        var lonElem = myFixture.getFile().findElementAt(lonOffset);
        assertNotNull(lonElem);
        var lonDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), lonElem, lonOffset);
        assertNotNull(lonDocElem);
        var lonDoc = provider.generateDoc(lonDocElem, lonElem);
        assertNotNull("Hover doc for :Longitude must not be null", lonDoc);
        assertTrue(lonDoc.contains("Standard Library Prelude:</b> :Longitude"));
        assertTrue(lonDoc.contains("-180.0"));
    }

    public void testUndefinedTypeInSchemaHighlightsTypeSection() {
        myFixture.configureByText(
            "undefined_type_in_type_section.stvn",
            """
            {
              :defs {
                :RemoteHost :StringFixed15
                :IpAddress :StringFixed15
                :RouteTable :MapInv( :RemoteHost :IpAddress )
              }

              :type :Tuple(
                :RouteTabl
              )

              :body (
                {
                  [ "auth.internal" "10.0.0.1" ]
                }
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var text = myFixture.getEditor().getDocument().getText();
        var typeIdx = text.indexOf(":type");
        var routeTablOffset = text.indexOf(":RouteTabl", typeIdx);
        var routeTablEnd = routeTablOffset + ":RouteTabl".length();

        var bodyIdx = text.indexOf(":body");

        var foundTargetError = false;
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null && desc.contains("Undefined type: :RouteTabl")) {
                    assertEquals("Error start offset must match :RouteTabl token", routeTablOffset, info.getStartOffset());
                    assertEquals("Error end offset must match :RouteTabl token", routeTablEnd, info.getEndOffset());
                    foundTargetError = true;
                }
                // Assert no errors intersect the :body section
                assertTrue("Error must not be painted in :body section! Found at offset: " + info.getStartOffset(),
                           info.getStartOffset() < bodyIdx);
            }
        }

        assertTrue("Expected 'Undefined type: :RouteTabl' annotation on :RouteTabl token not found", foundTargetError);
    }

    public void testUndefinedTypeInDefsHighlightsOffendingKeyword() {
        myFixture.configureByText(
            "undefined_type_in_defs.stvn",
            """
            {
              :defs {
                :RemoteHost :StringFixed15
                :RouteTable :MapInv( :RemoteHost :UndefinedTarget )
              }

              :type :Tuple( :RouteTable )

              :body (
                {
                  [ "auth.internal" "10.0.0.1" ]
                }
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var text = myFixture.getEditor().getDocument().getText();
        var undefinedOffset = text.indexOf(":UndefinedTarget");
        var undefinedEnd = undefinedOffset + ":UndefinedTarget".length();

        var bodyIdx = text.indexOf(":body");

        var foundTargetError = false;
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null && desc.contains("Undefined type: :UndefinedTarget")) {
                    assertEquals("Error start offset must match :UndefinedTarget token in :defs", undefinedOffset, info.getStartOffset());
                    assertEquals("Error end offset must match :UndefinedTarget token in :defs", undefinedEnd, info.getEndOffset());
                    foundTargetError = true;
                }
                assertTrue("Error must not be painted in :body section! Found at offset: " + info.getStartOffset(),
                           info.getStartOffset() < bodyIdx);
            }
        }

        assertTrue("Expected 'Undefined type: :UndefinedTarget' annotation on RHS of :defs not found", foundTargetError);
    }

    public void testSha256ValidLiteralAndInlayHints() {
        myFixture.configureByText(
            "sha256_valid.stvn",
            """
            {
              :defs {
                :Digest :Sha256
              }
              :type :Tuple( :Digest )
              :body (
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        for (var info : highlights) {
            assertFalse("Expected 0 ERROR annotations for valid :Sha256 literal, but found: " + info.getDescription(),
                        info.getSeverity().equals(HighlightSeverity.ERROR));
        }

        var tuple = PsiTreeUtil.findChildOfType(myFixture.getFile(), org.stvnadore.psi.TupleLiteral.class);
        assertNotNull(tuple);
        var tupleValues = tuple.getValueList();
        assertFalse(tupleValues.isEmpty());
        var elementValue = tupleValues.get(0);
        var resolvedType = org.stvnadore.plugin.reference.StvnTypeResolver.resolveValueType(elementValue);
        assertEquals(":Digest (-> :Sha256)", resolvedType);
    }

    public void testSha256InvalidLengthDiagnostic() {
        myFixture.configureByText(
            "sha256_invalid_length.stvn",
            """
            {
              :defs {
                :Digest :Sha256
              }
              :type :Tuple( :Digest )
              :body (
                "da39a3ee5e6b4b0d3255bfef95601890afd80709"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var foundError = false;
        var text = myFixture.getEditor().getDocument().getText();
        var litOffset = text.indexOf("\"da39a3ee5e6b4b0d3255bfef95601890afd80709\"");

        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null) {
                    foundError = true;
                    assertEquals("Error start offset must target invalid string literal", litOffset, info.getStartOffset());
                }
            }
        }
        assertTrue("Expected error diagnostic for 40-character legacy digest under :Sha256", foundError);
    }

    public void testSha256NonHexCharacterDiagnostic() {
        myFixture.configureByText(
            "sha256_non_hex.stvn",
            """
            {
              :defs {
                :Digest :Sha256
              }
              :type :Tuple( :Digest )
              :body (
                "ga7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var foundError = false;
        var text = myFixture.getEditor().getDocument().getText();
        var litOffset = text.indexOf("\"ga7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"");

        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null) {
                    foundError = true;
                    assertEquals("Error start offset must target invalid string literal", litOffset, info.getStartOffset());
                }
            }
        }
        assertTrue("Expected error diagnostic for non-hex character in :Sha256 literal", foundError);
    }

    public void testUhohEightAryTupleInlayHintPlacements() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "uhoh_inlays.stvn",
            """
            {
              :defs {
                #Some  :Uint5 1
                #None  :Uint5 2
                #Left  :Uint5 3
                #Right :Uint5 4
                #TRUE  :Uint5 5
                #FALSE :Uint5 6
                #True  :Uint5 7
                #False :Uint5 8
              }

              :type :Tuple(
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
                :Uint5
              )

              :body (
                #Some<hint text=":Uint5"/>
                #None<hint text=":Uint5"/>
                #Left<hint text=":Uint5"/>
                #Right<hint text=":Uint5"/>
                #TRUE<hint text=":Uint5"/>
                #FALSE<hint text=":Uint5"/>
                #True<hint text=":Uint5"/>
                #False<hint text=":Uint5"/>
              )<hint text=":Tuple( :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 )"/>
            }
            """);
        runInlayVerification();
    }

    public void testTrueSumContainerSuppressesInnerBadges() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "true_sum_suppression.stvn",
            """
            {
              :defs {
                :OptInt :Option( :Int32 )
                :Disjoint :Either( :String :Int32 )
              }
              :type :Tuple( :OptInt :Disjoint )
              :body (
                #Some 42<hint text=":OptInt #Some (-> :Int32)"/>
                #Right 100<hint text=":Disjoint #Right (-> :Int32)"/>
              )<hint text=":Tuple( :OptInt :Disjoint )"/>
            }
            """);
        runInlayVerification();
    }

    public void testUnspooledProductWithNestedTrueSum() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "unspooled_with_sum.stvn",
            """
            {
              :defs {
                #Some :Uint5 1
                :OptText :Option( :String )
              }
              :type :Tuple( :Uint5 :OptText )
              :body (
                #Some<hint text=":Uint5"/>
                #Some "payload"<hint text=":OptText #Some (-> :String)"/>
              )<hint text=":Tuple( :Uint5 :OptText )"/>
            }
            """);
        runInlayVerification();
    }

    public void testDeeplyNestedUnspooledEitherStructure() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "nested_either_unspool.stvn",
            """
            {
              :defs {
                #Left :Uint5 10
                #Right :Uint5 20
              }
              :type :Tuple( :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 )
              :body (
                #Left<hint text=":Uint5"/>
                #Right<hint text=":Uint5"/>
                #Left<hint text=":Uint5"/>
                #Right<hint text=":Uint5"/>
                30<hint text=":Uint5"/>
              )<hint text=":Tuple( :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 )"/>
            }
            """);
        runInlayVerification();
    }

    public void testMixedEnumVsConstantTupleInlays() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "mixed_enum_const_inlays.stvn",
            """
            {
              :defs {
                :Mode :Enum [ #Left #Right ]
                #Left :Uint7 99
              }
              :type :Tuple( :Mode :Uint7 )
              :body (
                #Left<hint text=":Mode (-> :Enum)"/>
                #Left<hint text=":Uint7"/>
              )<hint text=":Tuple( :Mode :Uint7 )"/>
            }
            """);
        runInlayVerification();
    }

    public void testUhohEnumAndConstantBooleanWarningsSuppression() {
        myFixture.enableInspections(
            new org.stvnadore.plugin.validation.StvnVariantStyleInspection(),
            new org.stvnadore.plugin.validation.StvnBooleanValidityInspection()
        );
        var projSettings = StvnProjectSettings.getInstance(getProject());
        projSettings.getState().enableRedundantTagInspection = true;
        projSettings.getState().enableFormDiscrepancyInspection = true;
        projSettings.getState().preferImpliedSumTypes = true;

        // 1. useLongFormSumTypes = true (Long Form)
        setUseLongFormSumTypes(true);
        var psiFile = myFixture.configureByText(
            "uhoh_guarded.stvn",
            """
            {
              :defs {
                :ReservedKeywordValue :Enum[ #None #N #Some #S #Left #L #Right #R #TRUE #T #FALSE #F]
                #None  :Uint5 1
                #N     :Uint5 2
                #Some  :Uint5 3
                #S     :Uint5 4
                #Left  :Uint5 5
                #L     :Uint5 6
                #Right :Uint5 7
                #R     :Uint5 8
                #TRUE  :Uint5 9
                #T     :Uint5 10
                #FALSE :Uint5 11
                #F     :Uint5 12
              }

              :type :Tuple(
                :ReservedKeywordValue :ReservedKeywordValue :ReservedKeywordValue
                :ReservedKeywordValue :ReservedKeywordValue :ReservedKeywordValue
                :ReservedKeywordValue :ReservedKeywordValue :ReservedKeywordValue
                :ReservedKeywordValue :ReservedKeywordValue :ReservedKeywordValue
                :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5
                :Uint5 :Uint5 :Uint5 :Uint5 :Uint5 :Uint5
              )

              :body (
                #None
                #N
                #Some
                #S
                #Left
                #L
                #Right
                #R
                #TRUE
                #T
                #FALSE
                #F
                #None
                #N
                #Some
                #S
                #Left
                #L
                #Right
                #R
                #TRUE
                #T
                #FALSE
                #F
              )
            }
            """
        );

        var highlights = myFixture.doHighlighting();
        for (var info : highlights) {
            var desc = info.getDescription();
            if (desc != null && (desc.contains("Use long-form tag") || desc.contains("Use short-form tag") || desc.contains("boolean literal"))) {
                fail("Spurious inspection warning found in uhoh.stvn: " + desc + " at offset " + info.getStartOffset());
            }
        }

        // 2. useLongFormSumTypes = false (Short Form)
        setUseLongFormSumTypes(false);
        myFixture.configureByText("uhoh_guarded_short.stvn", psiFile.getText());
        var highlightsShort = myFixture.doHighlighting();
        for (var info : highlightsShort) {
            var desc = info.getDescription();
            if (desc != null && (desc.contains("Use long-form tag") || desc.contains("Use short-form tag") || desc.contains("boolean literal"))) {
                fail("Spurious inspection warning found in uhoh.stvn with short form: " + desc + " at offset " + info.getStartOffset());
            }
        }
    }

    public void testLegitimateBooleanFormDiscrepancyGuards() {
        myFixture.enableInspections(new org.stvnadore.plugin.validation.StvnVariantStyleInspection());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        projSettings.getState().enableFormDiscrepancyInspection = true;

        // 1. Legitimate :Boolean short to long (#T -> #TRUE)
        setUseLongFormSumTypes(true);
        var textT = """
            {
              :type :Boolean
              :body #T
            }
            """;
        myFixture.configureByText("legit_bool_t.stvn", textT);
        var caretOffsetT = textT.indexOf("#T");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffsetT);
        myFixture.doHighlighting();
        var actionsT = myFixture.filterAvailableIntentions("Change tag to #TRUE");
        assertFalse("Expected Change tag to #TRUE quick-fix to be available for :Boolean", actionsT.isEmpty());
        myFixture.launchAction(actionsT.get(0));
        myFixture.checkResult("""
            {
              :type :Boolean
              :body #TRUE
            }
            """);

        // 2. Legitimate :Boolean short to long (#F -> #FALSE)
        var textF = """
            {
              :type :Boolean
              :body #F
            }
            """;
        myFixture.configureByText("legit_bool_f.stvn", textF);
        var caretOffsetF = textF.indexOf("#F");
        myFixture.getEditor().getCaretModel().moveToOffset(caretOffsetF);
        myFixture.doHighlighting();
        var actionsF = myFixture.filterAvailableIntentions("Change tag to #FALSE");
        assertFalse("Expected Change tag to #FALSE quick-fix to be available for :Boolean", actionsF.isEmpty());
        myFixture.launchAction(actionsF.get(0));
        myFixture.checkResult("""
            {
              :type :Boolean
              :body #FALSE
            }
            """);

        // 3. Legitimate :Option(:Boolean) nested boolean (#Some #T -> #Some #TRUE)
        myFixture.configureByText(
            "legit_opt_bool.stvn",
            """
            {
              :type :Option(:Boolean)
              :body #Some #T
            }
            """
        );
        var textOpt = myFixture.getEditor().getDocument().getText();
        myFixture.getEditor().getCaretModel().moveToOffset(textOpt.indexOf("#T"));
        myFixture.doHighlighting();
        var actionsOptT = myFixture.filterAvailableIntentions("Change tag to #TRUE");
        assertFalse("Expected Change tag to #TRUE quick-fix to be available inside :Option(:Boolean)", actionsOptT.isEmpty());
        myFixture.launchAction(actionsOptT.get(0));
        myFixture.checkResult("""
            {
              :type :Option(:Boolean)
              :body #Some #TRUE
            }
            """);
    }

    public void testAlgebraicSumTagIsolationInEnumsAndConstants() {
        myFixture.enableInspections(new org.stvnadore.plugin.validation.StvnVariantStyleInspection());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        projSettings.getState().enableRedundantTagInspection = true;
        projSettings.getState().enableFormDiscrepancyInspection = true;
        projSettings.getState().preferImpliedSumTypes = true;
        setUseLongFormSumTypes(true);

        var text = """
            {
              :defs {
                :TagEnum :Enum [ #S #N #L #R ]
                #S :Int32 100
                #N :Int32 200
              }
              :type :Tuple( :TagEnum :TagEnum :Int32 :Int32 )
              :body (
                #S
                #N
                #S
                #N
              )
            }
            """;
        myFixture.configureByText("algebraic_sum_isolation.stvn", text);
        var highlights = myFixture.doHighlighting();

        for (var info : highlights) {
            var desc = info.getDescription();
            if (desc != null && (desc.contains("Use long-form tag") || desc.contains("Redundant variant tag"))) {
                fail("Spurious sum-type inspection triggered on enum/constant tag: " + desc);
            }
        }
    }

    public void testNominalEnumMultiHopInlayTrajectory() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "enum_multihop.stvn",
            """
            {
              :defs {
                :BaseStatus :Enum [ #Pending #Success #Failure ]
                :IntermediateStatus :BaseStatus
                :FinalStatus :IntermediateStatus
              }
              :type :Tuple( :FinalStatus :IntermediateStatus :BaseStatus )
              :body (
                #Pending<hint text=":FinalStatus (-> :IntermediateStatus -> :BaseStatus -> :Enum)"/>
                #Success<hint text=":IntermediateStatus (-> :BaseStatus -> :Enum)"/>
                #Failure<hint text=":BaseStatus (-> :Enum)"/>
              )<hint text=":Tuple( :FinalStatus :IntermediateStatus :BaseStatus )"/>
            }
            """);
        runInlayVerification();
    }

    public void testAnonymousEnumInlayFormatting() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "anon_enum.stvn",
            """
            {
              :type :Tuple( :Enum [ #A #B ] )
              :body (
                #A<hint text=":Enum"/>
              )<hint text=":Tuple( :Enum [ #A #B ] )"/>
            }
            """);
        runInlayVerification();
    }

    public void testEnumAliasStructuralMetricHover() {
        myFixture.configureByText(
            "enum_metric_hover.stvn",
            """
            {
              :defs {
                :ReservedKeywordValue :Enum [
                  #KW_DEFS
                  #KW_TYPE
                  #KW_BODY
                  #KW_INCLUDE
                  #KW_TUPLE
                  #KW_MAP
                  #KW_MAP_INV
                  #KW_SEQ
                  #KW_SET
                  #KW_OPTION
                  #KW_EITHER
                  #KW_UNION
                ]
              }
              :type :Tuple( :ReservedKeywordValue )
              :body ( #KW_DEFS )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        // 1. Hover on declaration site in :defs
        var defOffset = text.indexOf(":ReservedKeywordValue");
        var defElem = myFixture.getFile().findElementAt(defOffset);
        assertNotNull(defElem);
        var defDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), defElem, defOffset);
        assertNotNull(defDocElem);
        var defDoc = provider.generateDoc(defDocElem, defElem);
        assertNotNull("Hover documentation for :ReservedKeywordValue definition must not be null", defDoc);
        assertTrue("Expected 'Variant Count:</b> 12' in hover card", defDoc.contains("Variant Count:</b> 12"));
        assertTrue(defDoc.contains("Type Alias:</b> :ReservedKeywordValue"));

        // 2. Hover on usage site in :type
        var usageOffset = text.lastIndexOf(":ReservedKeywordValue");
        var usageElem = myFixture.getFile().findElementAt(usageOffset);
        assertNotNull(usageElem);
        var usageDocElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), usageElem, usageOffset);
        assertNotNull(usageDocElem);
        var usageDoc = provider.generateDoc(usageDocElem, usageElem);
        assertNotNull("Hover documentation for :ReservedKeywordValue usage site must not be null", usageDoc);
        assertTrue("Expected 'Variant Count:</b> 12' in usage site hover card", usageDoc.contains("Variant Count:</b> 12"));
    }

    public void testTupleAliasStructuralMetricHover() {
        myFixture.configureByText(
            "tuple_metric_hover.stvn",
            """
            {
              :defs {
                :ClusterMetrics8 :Tuple(
                  :Int32
                  :Int32
                  :Float64
                  :Float64
                  :StringFixed15
                  :Boolean
                  :TimeEpochMs
                  :Uuid
                )
              }
              :type :Tuple( :ClusterMetrics8 )
              :body (
                ( 1 2 3.14 0.5 "host-node-01" #TRUE 1755532800000 "123e4567-e89b-12d3-a456-426614174000" )
              )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        var offset = text.indexOf(":ClusterMetrics8");
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull(docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Hover documentation for :ClusterMetrics8 must not be null", doc);
        assertTrue("Expected 'Arity:</b> 8' in tuple hover card", doc.contains("Arity:</b> 8"));
        assertTrue(doc.contains("Type Alias:</b> :ClusterMetrics8"));
    }

    public void testUnionAliasStructuralMetricHover() {
        myFixture.configureByText(
            "union_metric_hover.stvn",
            """
            {
              :defs {
                :NetworkPayload3 :Union(
                  :IPv4
                  :StringFixed15
                  :Int32
                )
              }
              :type :Tuple( :NetworkPayload3 )
              :body ( #1 "127.0.0.1" )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        var offset = text.indexOf(":NetworkPayload3");
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull(docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Hover documentation for :NetworkPayload3 must not be null", doc);
        assertTrue("Expected 'Branch Count:</b> 3' in union hover card", doc.contains("Branch Count:</b> 3"));
        assertTrue(doc.contains("Type Alias:</b> :NetworkPayload3"));
    }

    public void testMultiHopAliasChainMetricHover() {
        myFixture.configureByText(
            "multihop_metric_hover.stvn",
            """
            {
              :defs {
                :BaseEnum :Enum [ #Alpha #Beta #Gamma #Delta ]
                :IntermediateEnum :BaseEnum
                :FinalStatus :IntermediateEnum
              }
              :type :Tuple( :FinalStatus )
              :body ( #Alpha )
            }
            """);

        var text = myFixture.getEditor().getDocument().getText();
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();

        var offset = text.indexOf(":FinalStatus");
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull(elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull(docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Hover documentation for :FinalStatus must not be null", doc);
        assertTrue("Expected 'Variant Count:</b> 4' for multi-hop enum alias", doc.contains("Variant Count:</b> 4"));
        assertTrue(doc.contains("Resolution Path:</b> :FinalStatus &rarr; :IntermediateEnum &rarr; :BaseEnum &rarr; :Enum"));
    }

    public void testUhohUntaggedUnionBooleanInTupleZeroErrors() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "uhoh_rule_c.stvn",
            """
            {
              // uhoh.stvn
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }

              :type :Tuple(
                :DisjointUnion
              )

              :body (
                #TRUE
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var errorMessages = new ArrayList<String>();
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                errorMessages.add(info.getDescription());
            }
        }
        assertTrue("Expected 0 errors on uhoh.stvn with Rule C inference, but found: " + errorMessages, errorMessages.isEmpty());

        var tuple = PsiTreeUtil.findChildOfType(myFixture.getFile(), TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(1, values.size());
        assertEquals(":DisjointUnion [#2] (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(0)));
    }

    public void testUntaggedUnionAllPrimitivePayloadsInTuple() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "all_primitives_union.stvn",
            """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple( :DisjointUnion :DisjointUnion :DisjointUnion :DisjointUnion )
              :body (
                42
                #TRUE
                3.14159
                "production"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var errorMessages = new ArrayList<String>();
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                errorMessages.add(info.getDescription());
            }
        }
        assertTrue("Expected 0 errors on all-primitive union tuple, but found: " + errorMessages, errorMessages.isEmpty());

        var tuple = PsiTreeUtil.findChildOfType(myFixture.getFile(), TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(4, values.size());

        assertEquals(":DisjointUnion [#1] (-> :Int32)", StvnTypeResolver.resolveValueType(values.get(0)));
        assertEquals(":DisjointUnion [#2] (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(1)));
        assertEquals(":DisjointUnion [#3] (-> :Float64)", StvnTypeResolver.resolveValueType(values.get(2)));
        assertEquals(":DisjointUnion [#4] (-> :String)", StvnTypeResolver.resolveValueType(values.get(3)));
    }

    public void testMixedTaggedAndUntaggedUnionInTuple() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "mixed_tagged_untagged_union.stvn",
            """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple( :DisjointUnion :DisjointUnion )
              :body (
                #1 42
                #TRUE
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var errorMessages = new ArrayList<String>();
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                errorMessages.add(info.getDescription());
            }
        }
        assertTrue("Expected 0 errors on mixed tagged and untagged union tuple, but found: " + errorMessages, errorMessages.isEmpty());

        var tuple = PsiTreeUtil.findChildOfType(myFixture.getFile(), TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(2, values.size());

        assertEquals(":DisjointUnion #1 (-> :Int32)", StvnTypeResolver.resolveValueType(values.get(0)));
        assertEquals(":DisjointUnion [#2] (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(1)));
    }

    public void testAmbiguousUnionMatchGeneratesError() {
        myFixture.configureByText(
            "ambiguous_union.stvn",
            """
            {
              :defs {
                :AmbiguousUnion :Union( :Int32 :Uint32 )
              }
              :type :Tuple( :AmbiguousUnion )
              :body (
                100
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var foundAmbiguityError = false;
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                var desc = info.getDescription();
                if (desc != null && desc.contains("Ambiguous implicit resolution")) {
                    foundAmbiguityError = true;
                    var text = myFixture.getEditor().getDocument().getText();
                    var tokenIdx = text.indexOf("100");
                    assertTrue("Error must clamp to ambiguous token offset", info.getStartOffset() >= tokenIdx);
                }
            }
        }
        assertTrue("Expected ambiguity collision error for untagged value matching multiple union branches", foundAmbiguityError);
    }

    public void testBoundedStringPayloadZeroDiagnostics() {
        myFixture.configureByText(
            "uhoh.stvn",
            """
            {
              :defs {
                :BoundedText :String64
              }
              :type :Tuple(
                :String64
                :BoundedText
              )
              :body (
                "37-character-string-payload-sample-01"
                "37-character-string-payload-sample-02"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertTrue("Expected 0 error highlights on valid 37-character :String64 payloads, but found: " 
            + errorHighlights.stream().map(HighlightInfo::getDescription).toList(), 
            errorHighlights.isEmpty());
    }

    public void testBoundedStringExceedsMaxLengthClampedError() {
        myFixture.configureByText(
            "bounded_string_overflow.stvn",
            """
            {
              :type :Tuple( :String64 )
              :body (
                "01234567890123456789012345678901234567890123456789012345678901234"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected exactly 1 error highlight for string exceeding maximum length 64", 1, errorHighlights.size());

        var error = errorHighlights.get(0);
        assertNotNull(error.getDescription());
        assertTrue("Error message must indicate string length exceeded maximum",
            error.getDescription().contains("String length exceeds maximum length of 64 characters, got 65"));

        var documentText = myFixture.getEditor().getDocument().getText();
        var targetText = documentText.substring(error.getStartOffset(), error.getEndOffset());
        assertEquals("\"01234567890123456789012345678901234567890123456789012345678901234\"", targetText);
    }

    public void testFixedStringLengthRejection() {
        // 1. Under-length (37 characters) rejected by :StringFixed64
        myFixture.configureByText(
            "fixed_string_underlength.stvn",
            """
            {
              :type :Tuple( :StringFixed64 )
              :body (
                "37-character-string-payload-sample-01"
              )
            }
            """);

        var underHighlights = myFixture.doHighlighting();
        var underErrors = underHighlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals(1, underErrors.size());
        assertTrue(underErrors.get(0).getDescription() != null &&
            underErrors.get(0).getDescription().contains("Fixed string must be exactly 64 characters long, got 37"));

        // 2. Over-length (65 characters) rejected by :StringFixed64
        myFixture.configureByText(
            "fixed_string_overlength.stvn",
            """
            {
              :type :Tuple( :StringFixed64 )
              :body (
                "01234567890123456789012345678901234567890123456789012345678901234"
              )
            }
            """);

        var overHighlights = myFixture.doHighlighting();
        var overErrors = overHighlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals(1, overErrors.size());
        assertTrue(overErrors.get(0).getDescription() != null &&
            overErrors.get(0).getDescription().contains("Fixed string must be exactly 64 characters long, got 65"));
    }

    public void testBoundedStringInlayHintsAndHoverDocumentation() {
        myFixture.configureByText(
            "bounded_string_inlays.stvn",
            """
            {
              :defs {
                :BoundedText :String64
              }
              :type :Tuple( :BoundedText )
              :body (
                "short text"
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(myFixture.getFile(), TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(1, values.size());

        // Verify Inlay Hint Trajectory Label
        var resolvedType = StvnTypeResolver.resolveValueType(values.get(0));
        assertEquals(":BoundedText (-> :String64)", resolvedType);

        // Verify Quick Documentation for :String64
        var text = myFixture.getEditor().getDocument().getText();
        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var str64Offset = text.indexOf(":String64");
        var str64Elem = myFixture.getFile().findElementAt(str64Offset);
        assertNotNull(str64Elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), str64Elem, str64Offset);
        assertNotNull(docElem);
        var doc = provider.generateDoc(docElem, str64Elem);
        assertNotNull("Hover doc for :String64 must not be null", doc);
        assertTrue(doc.contains("max-bounded UTF-8 string"));
        assertTrue(doc.contains("0 &le; len &le; 64"));

        // Verify Quick Navigate Info for :String64
        var quickInfo = provider.getQuickNavigateInfo(docElem, str64Elem);
        assertNotNull(quickInfo);
        assertTrue(quickInfo.contains("Max-Bounded String"));
    }

    public void testUhohAllSumCombinationsZeroErrorsAndInlays() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "uhoh_all_sums.stvn",
            """
            {
              :defs {
                :OptBool   :Option( :Boolean )
                :Disjoint  :Either( :Int32 :Boolean )
              }
              :type :Tuple(
                :OptBool
                :OptBool
                :Disjoint
                :Disjoint
              )
              :body (
                #Some #TRUE
                #S #TRUE
                #Right #FALSE
                #FALSE
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        var errorMessages = new ArrayList<String>();
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                errorMessages.add(info.getDescription());
            }
        }
        assertTrue("Expected 0 errors on uhoh sum combinations, but found: " + errorMessages, errorMessages.isEmpty());

        var tuple = PsiTreeUtil.findChildOfType(myFixture.getFile(), TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(4, values.size());

        // 1. Explicit Long Option: unbracketed #Some
        assertEquals(":OptBool #Some (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(0)));

        // 2. Explicit Short Option: unbracketed #Some (or #S)
        assertEquals(":OptBool #Some (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(1)));

        // 3. Explicit Long Either: unbracketed #Right
        assertEquals(":Disjoint #Right (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(2)));

        // 4. Implied Either (Rule B): bracketed [#Right]
        assertEquals(":Disjoint [#Right] (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(3)));
    }

    public void testShortFormExplicitVsInferredInlays() {
        setUseLongFormSumTypes(false);
        myFixture.configureByText(
            "short_explicit_vs_inferred.stvn",
            """
            {
              :defs {
                :OptBool :Option( :Boolean )
              }
              :type :Tuple( :OptBool :OptBool )
              :body (
                #S #TRUE
                #TRUE
              )
            }
            """);

        var tuple = PsiTreeUtil.findChildOfType(myFixture.getFile(), TupleLiteral.class);
        assertNotNull(tuple);
        var values = tuple.getValueList();
        assertEquals(2, values.size());

        // Explicit short form must NOT have square brackets
        assertEquals(":OptBool #S (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(0)));

        // Inferred Rule A must have square brackets
        assertEquals(":OptBool [#S] (-> :Boolean)", StvnTypeResolver.resolveValueType(values.get(1)));
    }

    public void testTupleCollectionErrorClampingToOffendingChildToken() {
        myFixture.configureByText(
            "tuple_clamped_error.stvn",
            """
            {
              :type :Tuple( :Int32 :Boolean :String )
              :body (
                100
                "invalid_boolean_string"
                "valid_string"
              )
            }
            """);

        var highlights = myFixture.doHighlighting();
        com.intellij.codeInsight.daemon.impl.HighlightInfo targetError = null;
        for (var info : highlights) {
            if (info.getSeverity().equals(HighlightSeverity.ERROR)) {
                targetError = info;
                break;
            }
        }
        assertNotNull("Expected type mismatch error", targetError);

        var text = myFixture.getEditor().getDocument().getText();
        var bodyIndex = text.indexOf(":body");
        var tupleStart = text.indexOf("(", bodyIndex);
        var tupleEnd = text.indexOf(")", bodyIndex) + 1;
        var badTokenStart = text.indexOf("\"invalid_boolean_string\"");
        var badTokenEnd = badTokenStart + "\"invalid_boolean_string\"".length();

        // Verify error does NOT encompass tuple delimiters
        assertTrue("Error must not start at tuple opening '('", targetError.getStartOffset() > tupleStart);
        assertTrue("Error must not end at tuple closing ')'", targetError.getEndOffset() < tupleEnd);

        // Verify error clamps strictly to offending child token
        assertEquals("Error start offset must match bad token start", badTokenStart, targetError.getStartOffset());
        assertEquals("Error end offset must match bad token end", badTokenEnd, targetError.getEndOffset());
    }

    public void testUnreferencedBrokenRegexInDefsEagerHighlighting() {
        var code = """
            {
              :defs {
                :BrokenRegex { #regex "[" } :String
              }
              :type :String
              :body "valid_payload"
            }
            """;
        myFixture.configureByText("uhoh_regex.stvn", code);

        // 1. Assert exactly 1 ERROR highlight across the entire document
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected exactly 1 eager error highlight in :defs", 1, errors.size());

        // 2. Assert error message content
        var err = errors.get(0);
        assertNotNull(err.getDescription());
        assertTrue("Error message must indicate invalid regex pattern",
            err.getDescription().contains("Invalid regex pattern: ["));

        // 3. Assert exact coordinate range clamped strictly to "["
        var docText = myFixture.getEditor().getDocument().getText();
        var highlightedText = docText.substring(err.getStartOffset(), err.getEndOffset());
        assertEquals("Squiggly must be clamped strictly to the invalid regex string token", "\"[\"", highlightedText);

        // 4. Assert Inlay Hint renders on valid body payload
        var annotatedCode = """
            {
              :defs {
                :BrokenRegex { #regex "[" } :String
              }
              :type :String
              :body "valid_payload"<hint text=":String"/>
            }
            """;
        myFixture.configureByText("uhoh_regex_hints.stvn", annotatedCode);
        runInlayVerification();
    }

    public void testMultipleSimultaneousDefsViolationsConcurrentHighlighting() {
        var code = """
            {
              :defs {
                :BrokenRegex      { #regex "[" } :String
                :InvertedRange    { #minIncl 100 #maxIncl 10 } :Int32
                :CapacityOverflow { #minIncl 500 } :Int8
              }
              :type :Int32
              :body 42
            }
            """;
        myFixture.configureByText("multi_defs_error.stvn", code);

        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected exactly 3 concurrent errors across :defs entries", 3, errors.size());

        var docText = myFixture.getEditor().getDocument().getText();

        // Verify Error #1: Broken Regex
        var hasRegexError = errors.stream().anyMatch(e ->
            e.getDescription() != null &&
            e.getDescription().contains("Invalid regex pattern") &&
            docText.substring(e.getStartOffset(), e.getEndOffset()).equals("\"[\"")
        );
        assertTrue("Must highlight broken regex token '['", hasRegexError);

        // Verify Error #2: Inverted Range
        var hasInvertedRangeError = errors.stream().anyMatch(e ->
            e.getDescription() != null &&
            e.getDescription().contains("effective range is invalid") &&
            docText.substring(e.getStartOffset(), e.getEndOffset()).equals("{ #minIncl 100 #maxIncl 10 }")
        );
        assertTrue("Must highlight inverted range metadata map", hasInvertedRangeError);

        // Verify Error #3: Capacity Overflow
        var hasCapacityError = errors.stream().anyMatch(e ->
            e.getDescription() != null &&
            e.getDescription().contains("out of bounds for physical capacity") &&
            docText.substring(e.getStartOffset(), e.getEndOffset()).equals("500")
        );
        assertTrue("Must highlight capacity overflow literal '500'", hasCapacityError);
    }

    public void testModularHeaderMultiDiagnosticHighlighting() {
        var headerCode = """
            {
              :defs {
                :include [ "sub_module.stvn_inclf" ]
                :MutuallyExclusive { #minIncl 10 #minExcl 10 } :Int32
                :IncompatibleMeta  { #preserveIndent #TRUE } :Int32
              }
            }
            """;
        myFixture.configureByText("header_multi_error.stvn_inclf", headerCode);

        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertTrue("Expected at least 3 concurrent errors in modular header", errors.size() >= 3);

        // 1. Leaf Module Include Restriction
        assertTrue("Must report leaf module include violation", errors.stream().anyMatch(e ->
            e.getDescription() != null && e.getDescription().contains("Leaf module (.stvn_inclf) cannot contain include statements")
        ));

        // 2. Mutually Exclusive Bounds
        assertTrue("Must report mutually exclusive bounds", errors.stream().anyMatch(e ->
            e.getDescription() != null && e.getDescription().contains("are mutually exclusive")
        ));

        // 3. Incompatible Metadata on Int32
        assertTrue("Must report incompatible metadata", errors.stream().anyMatch(e ->
            e.getDescription() != null && e.getDescription().contains("preserveIndent is not allowed on :Int32")
        ));
    }

    public void testDownstreamCascadeSuppressionOnBrokenTypeDefInType() {
        var code = """
            {
              :defs {
                :BrokenType { #regex "[" } :String
              }
              :type :BrokenType
              :body "hello"
            }
            """;
        myFixture.configureByText("cascade_suppression.stvn", code);

        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        // Assert that the only error highlighted is the root definition error on "["
        var docText = myFixture.getEditor().getDocument().getText();
        var bodyIdx = docText.indexOf(":body");

        for (var err : errors) {
            assertTrue("Error highlight must be within :defs, not inside :body",
                err.getEndOffset() <= bodyIdx);
        }

        assertTrue("Must contain invalid regex error on definition", errors.stream().anyMatch(e ->
            e.getDescription() != null && e.getDescription().contains("Invalid regex pattern: [")
        ));
    }

    public void testTypeAnchorWarningOnDegradedSchema() {
        var code = """
            {
              :defs {
                :BrokenRegex { #regex "[" } :String
              }
              :type :BrokenRegex
              :body "payload_value"
            }
            """;
        myFixture.configureByText("degraded_anchor.stvn", code);

        var highlights = myFixture.doHighlighting();

        // 1. Assert exactly 1 ERROR in :defs for the invalid regex
        var defsErrors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();
        assertEquals("Expected exactly 1 ERROR in :defs", 1, defsErrors.size());
        assertNotNull(defsErrors.get(0).getDescription());
        assertTrue(defsErrors.get(0).getDescription().contains("Invalid regex pattern"));

        // 2. Assert exactly 1 WARNING on the :type declaration
        var typeWarnings = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.WARNING))
            .toList();
        assertEquals("Expected exactly 1 WARNING on :type", 1, typeWarnings.size());
        assertNotNull(typeWarnings.get(0).getDescription());
        assertTrue(typeWarnings.get(0).getDescription().contains("Operating on fallback base"));

        var text = myFixture.getEditor().getDocument().getText();
        var typeOffset = text.lastIndexOf(":BrokenRegex");
        assertEquals(typeOffset, typeWarnings.get(0).getStartOffset());

        // 3. Assert ZERO warnings/errors on the :body value
        var bodyOffset = text.indexOf("\"payload_value\"");
        var bodyHighlights = highlights.stream()
            .filter(h -> h.getStartOffset() >= bodyOffset && h.getEndOffset() <= bodyOffset + "\"payload_value\"".length())
            .toList();
        assertTrue("Body payload must have zero annotations by default", bodyHighlights.isEmpty());
    }

    public void testInlayHintDegradedPrefix() {
        var annotatedCode = """
            {
              :defs {
                :BrokenRegex { #regex "[" } :String
              }
              :type :BrokenRegex
              :body "hello_world"<hint text="⚠ :BrokenRegex (-> :String)"/>
            }
            """;
        myFixture.configureByText("degraded_inlay.stvn", annotatedCode);
        runInlayVerification();
    }

    public void testQuickDocDegradedWarningBanner() {
        var code = """
            {
              :defs {
                :BrokenRegex { #regex "[" } :String
              }
              :type :BrokenRegex
              :body "payload_value"
            }
            """;
        myFixture.configureByText("degraded_doc.stvn", code);

        var text = myFixture.getEditor().getDocument().getText();
        var typeOffset = text.lastIndexOf(":BrokenRegex");
        var element = myFixture.getFile().findElementAt(typeOffset);
        assertNotNull(element);

        var provider = new org.stvnadore.plugin.documentation.StvnDocumentationProvider();
        var target = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), element, typeOffset);
        assertNotNull(target);

        var doc = provider.generateDoc(target, element);
        assertNotNull("Documentation must not be null", doc);
        assertTrue("Must contain warning banner icon/header", doc.contains("Degraded Schema Warning"));
        assertTrue("Must contain fallback base description", doc.contains("fallback base"));
        assertTrue("Must contain alias name", doc.contains(":BrokenRegex"));
    }

    public void testDeepNestedSumInferenceTrajectoryInlaysWithUhohFixture() {
        setUseLongFormSumTypes(true);
        myFixture.configureByText(
            "uhoh_nested_sums.stvn",
            """
            {
              :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
              :body [
                #Some #Right #Some #Right 1.234<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) #Some #Right #Some #Right"/>
                #Right #Some #Right 1.234<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) [#Some] #Right #Some #Right"/>
                #Some #Right 1.234<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) #Some #Right [#Some] [#Right]"/>
                #Right 1.234<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) [#Some] #Right [#Some] [#Right]"/>
                1.234<hint text=":Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) [#Some] [#Right] [#Some] [#Right]"/>
              ]<hint text=":Seq( :Option( :Either( :String :Option( :Either( :String :Float ) ) ) ) )"/>
            }
            """);
        runInlayVerification();
    }

    public void testZeroFalsePositiveErrorsOnDeepNestedSumFixture() {
        myFixture.configureByText(
            "uhoh_no_errors.stvn",
            """
            {
              :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
              :body [
                #Some #Right #Some #Right 1.234
                #Right #Some #Right 1.234
                #Some #Right 1.234
                #Right 1.234
                1.234
              ]
            }
            """);
        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        assertEquals("Expected zero error highlights across all 5 nested sum lines", 0, errorHighlights.size());
    }

    public void testWolfTheProblemSolverSynchronization() {
        var invalidText = """
            {
              :defs {
                :BrokenRegex { #regex "[" } :String
              }
              :type :BrokenRegex
              :body "hello"
            }
            """;
        var psiFile = myFixture.configureByText("broken_regex_test.stvn", invalidText);
        var virtualFile = psiFile.getVirtualFile();
        assertNotNull("VirtualFile must not be null", virtualFile);

        var wolf = WolfTheProblemSolver.getInstance(getProject());
        if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
            mockWolf.setDelegate(com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl.createTestInstance(getProject()));
        }

        try {
            // 1. Run highlighting on invalid document -> DaemonCodeAnalyzer emits ERROR -> StvnExternalAnnotator reports to Wolf
            var highlights = myFixture.doHighlighting();
            var hasErrors = highlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
            assertTrue("Highlighting must emit ERROR for invalid regex constraint", hasErrors);
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue("WolfTheProblemSolver must report broken_regex_test.stvn as problem file",
                    wolf.isProblemFile(virtualFile));

            // 2. Fix document with valid regex constraint -> Commit document
            var validText = """
                {
                  :defs {
                    :ValidRegex { #regex "^[a-z]+$" } :String
                  }
                  :type :ValidRegex
                  :body "hello"
                }
                """;
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                myFixture.getEditor().getDocument().setText(validText);
            });
            com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

            // 3. Re-run highlighting -> Zero errors -> StvnExternalAnnotator clears Wolf problem file
            var cleanHighlights = myFixture.doHighlighting();
            var cleanErrors = cleanHighlights.stream().anyMatch(h -> h.getSeverity().equals(HighlightSeverity.ERROR));
            assertFalse("Highlighting must emit zero ERRORs for valid document", cleanErrors);
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertFalse("WolfTheProblemSolver must clear broken_regex_test.stvn after fix",
                    wolf.isProblemFile(virtualFile));
            var fileStatus = com.intellij.openapi.vcs.FileStatusManager.getInstance(getProject()).getStatus(virtualFile);
            assertNotNull("FileStatusManager must return non-null status", fileStatus);
            assertEquals("FileStatus must be NOT_CHANGED after fix", com.intellij.openapi.vcs.FileStatus.NOT_CHANGED, fileStatus);
        } finally {
            if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
                mockWolf.resetDelegate();
            }
        }
    }

    public void testMultiPassAnnotatorWolfProblemClearing() {
        var invalidText = """
            {
              :defs {
                :BrokenRegex { #regex "[" } :String
              }
              :type :BrokenRegex
              :body "hello"
            }
            """;
        var psiFile = myFixture.configureByText("multi_pass_wolf_test.stvn", invalidText);
        var virtualFile = psiFile.getVirtualFile();
        assertNotNull("VirtualFile must not be null", virtualFile);

        var wolf = WolfTheProblemSolver.getInstance(getProject());
        if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
            mockWolf.setDelegate(com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl.createTestInstance(getProject()));
        }

        try {
            // Pass 1: Highlight invalid regex syntax -> Error detected, Wolf reports problem file
            var highlights1 = myFixture.doHighlighting();
            var errors1 = highlights1.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).toList();
            assertFalse("Pass 1 must detect compile error", errors1.isEmpty());
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue("WolfTheProblemSolver must report multi_pass_wolf_test.stvn as problem file on Pass 1",
                    wolf.isProblemFile(virtualFile));
            var status1 = com.intellij.openapi.vcs.FileStatusManager.getInstance(getProject()).getStatus(virtualFile);
            assertNotNull("FileStatusManager must return non-null status on Pass 1", status1);

            // Pass 2: Repair document with valid syntax -> Re-highlight -> Wolf clears problem file
            var validText = """
                {
                  :defs {
                    :ValidRegex { #regex "^[a-z]+$" } :String
                  }
                  :type :ValidRegex
                  :body "hello"
                }
                """;
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                myFixture.getEditor().getDocument().setText(validText);
            });
            com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

            var highlights2 = myFixture.doHighlighting();
            var errors2 = highlights2.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).toList();
            assertEquals("Pass 2 must detect zero compile errors", 0, errors2.size());
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertFalse("WolfTheProblemSolver must clear problem file on Pass 2",
                    wolf.isProblemFile(virtualFile));
            var status2 = com.intellij.openapi.vcs.FileStatusManager.getInstance(getProject()).getStatus(virtualFile);
            assertNotNull("FileStatusManager must return non-null status on Pass 2", status2);
            assertEquals("FileStatus must be NOT_CHANGED on Pass 2", com.intellij.openapi.vcs.FileStatus.NOT_CHANGED, status2);

            // Pass 3: Introduce duplicate map key error -> Wolf reports problem file again
            var reBrokenText = """
                {
                  :type :Map( :String :Int32 )
                  :body {
                    [ "key" 1 ]
                    [ "key" 2 ]
                  }
                }
                """;
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                myFixture.getEditor().getDocument().setText(reBrokenText);
            });
            com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

            var highlights3 = myFixture.doHighlighting();
            var errors3 = highlights3.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).toList();
            assertFalse("Pass 3 must detect duplicate key error", errors3.isEmpty());
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue("WolfTheProblemSolver must report multi_pass_wolf_test.stvn as problem file on Pass 3",
                    wolf.isProblemFile(virtualFile));
            var status3 = com.intellij.openapi.vcs.FileStatusManager.getInstance(getProject()).getStatus(virtualFile);
            assertNotNull("FileStatusManager must return non-null status on Pass 3", status3);

            // Pass 4: Fix duplicate map key -> Wolf clears problem file again
            var reFixedText = """
                {
                  :type :Map( :String :Int32 )
                  :body {
                    [ "key1" 1 ]
                    [ "key2" 2 ]
                  }
                }
                """;
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                myFixture.getEditor().getDocument().setText(reFixedText);
            });
            com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

            var highlights4 = myFixture.doHighlighting();
            var errors4 = highlights4.stream().filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR)).toList();
            assertEquals("Pass 4 must detect zero compile errors", 0, errors4.size());
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertFalse("WolfTheProblemSolver must clear problem file on Pass 4",
                    wolf.isProblemFile(virtualFile));
            var status4 = com.intellij.openapi.vcs.FileStatusManager.getInstance(getProject()).getStatus(virtualFile);
            assertNotNull("FileStatusManager must return non-null status on Pass 4", status4);
            assertEquals("FileStatus must be NOT_CHANGED on Pass 4", com.intellij.openapi.vcs.FileStatus.NOT_CHANGED, status4);
        } finally {
            if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
                mockWolf.resetDelegate();
            }
        }
    }

    public void testDisposedProjectAndInvalidVirtualFileResilience() {
        var annotator = new org.stvnadore.plugin.validation.StvnExternalAnnotator();
        var dummyFile = com.intellij.psi.PsiFileFactory.getInstance(getProject())
            .createFileFromText("in_memory.stvn", org.stvnadore.plugin.StvnFileType.Payload.INSTANCE, "{ :defs {} :type :String :body \"test\" }");
        assertNull("In-memory PSI file without VirtualFile should return null virtual file", dummyFile.getVirtualFile());

        var collected = annotator.collectInformation(dummyFile);
        assertNull("collectInformation should return null for in-memory PSI file", collected);

        var collectedWithEditor = annotator.collectInformation(dummyFile, null, false);
        assertNull("collectInformation with editor should return null for in-memory PSI file", collectedWithEditor);

        var info = new org.stvnadore.plugin.validation.StvnExternalAnnotator.CollectedInfo("{ :defs {} :type :String :body \"test\" }", "temp://dummy.stvn", dummyFile.getVirtualFile());
        var res = annotator.doAnnotate(info);
        assertNotNull("doAnnotate must return non-null AnnotationResult", res);
    }

    public void testDisjointUnionTupleFixtureWolfSynchronization() {
        var fixtureText = """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple(
                :DisjointUnion
              )
              :body (
                #TRUE
              )
            }
            """;

        var psiFile = myFixture.configureByText("temp.stvn", fixtureText);
        var virtualFile = psiFile.getVirtualFile();
        assertNotNull("VirtualFile must not be null for temp.stvn", virtualFile);

        var wolf = WolfTheProblemSolver.getInstance(getProject());
        if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
            mockWolf.setDelegate(com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl.createTestInstance(getProject()));
        }

        try {
            var highlights = myFixture.doHighlighting();
            var errorHighlights = highlights.stream()
                .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
                .toList();

            assertEquals("temp.stvn must emit zero ERROR highlights in editor buffer", 0, errorHighlights.size());

            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            assertFalse("WolfTheProblemSolver must report isProblemFile == false for valid temp.stvn",
                    wolf.isProblemFile(virtualFile));

            var fileStatus = com.intellij.openapi.vcs.FileStatusManager.getInstance(getProject()).getStatus(virtualFile);
            assertNotNull("FileStatusManager must return non-null status", fileStatus);
            assertEquals("FileStatus must be NOT_CHANGED for temp.stvn",
                    com.intellij.openapi.vcs.FileStatus.NOT_CHANGED, fileStatus);
        } finally {
            if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
                mockWolf.resetDelegate();
            }
        }
    }

    public void testSyntheticUnlocatedErrorEditorBufferHighlighting() {
        var textWithRealError = """
            {
              :defs {
                :DisjointUnion :Union( :Int32 :Boolean :Float64 :String )
              }
              :type :Tuple(
                :DisjointUnion
              )
              :body (
                [ 1 2 3 ]
              )
            }
            """;

        var psiFile = myFixture.configureByText("real_error_test.stvn", textWithRealError);
        var virtualFile = psiFile.getVirtualFile();
        assertNotNull("VirtualFile must not be null", virtualFile);

        var wolf = WolfTheProblemSolver.getInstance(getProject());
        if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
            mockWolf.setDelegate(com.intellij.codeInsight.daemon.impl.WolfTheProblemSolverImpl.createTestInstance(getProject()));
        }

        try {
            var highlights = myFixture.doHighlighting();
            var errorHighlights = highlights.stream()
                .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
                .toList();

            assertFalse("Real type mismatch must produce visible ERROR highlight in editor buffer",
                    errorHighlights.isEmpty());

            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            assertTrue("WolfTheProblemSolver must report real_error_test.stvn as problem file",
                    wolf.isProblemFile(virtualFile));
        } finally {
            if (wolf instanceof com.intellij.codeInsight.daemon.impl.MockWolfTheProblemSolver mockWolf) {
                mockWolf.resetDelegate();
            }
        }
    }

    public void testEmptyCompositeSyntaxErrorsAndRangeExpansion() {
        var fixtureText = """
            {
              :defs {
                :Enum0 :Enum[]
                :Tuple0 :Tuple()
                :Union0 :Union()
                :Seq0 :Seq()
                :Set0 :Set()
                :Map0 :Map()
              }
              :type :Union(:Int32 :Enum[])
              :body 123
            }
            """;

        var psiFile = myFixture.configureByText("uhoh_empty_composites.stvn", fixtureText);
        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        // 1. Count Assertion: Exactly 7 errors (1 per defective line: 3, 4, 5, 6, 7, 8, 10; 0 duplicate rows)
        assertEquals("Must produce exactly 7 error highlights for uhoh_empty_composites.stvn (no duplicates). Found: " + errorHighlights,
                7, errorHighlights.size());

        // 2. Deduplication Assertion: 0 Grammar-Kit default error descriptions
        for (var h : errorHighlights) {
            var desc = h.getDescription();
            assertNotNull(desc);
            assertFalse("Highlight contains raw Grammar-Kit expected token description: " + desc,
                    desc.contains("<schema type>") || desc.contains("<value keyword>") || desc.contains("expected, got"));
        }

        var text = psiFile.getText();

        // 3. Line 3: :Enum0 :Enum[] -> exact range of ':Enum[]'
        var enum0Offset = text.indexOf(":Enum[]");
        var enum0End = enum0Offset + ":Enum[]".length();
        var enum0Highlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == enum0Offset && h.getEndOffset() == enum0End)
            .findFirst();
        assertTrue("Expected :Enum[] to be highlighted from offset " + enum0Offset + " to " + enum0End + 
                   ". Found highlights: " + errorHighlights, enum0Highlight.isPresent());
        assertTrue("Message must mention empty enum variant list",
                   enum0Highlight.get().getDescription().contains("empty enum variant list: :Enum[] requires at least one value keyword variant"));

        // 4. Line 4: :Tuple0 :Tuple() -> exact range of ':Tuple()'
        var tuple0Offset = text.indexOf(":Tuple()");
        var tuple0End = tuple0Offset + ":Tuple()".length();
        var tuple0Highlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == tuple0Offset && h.getEndOffset() == tuple0End)
            .findFirst();
        assertTrue("Expected :Tuple() to be highlighted from offset " + tuple0Offset + " to " + tuple0End + 
                   ". Found highlights: " + errorHighlights, tuple0Highlight.isPresent());
        assertTrue("Message must mention empty composite argument list for :Tuple()",
                   tuple0Highlight.get().getDescription().contains("empty composite argument list: :Tuple() requires at least one schema type"));

        // 5. Line 5: :Union0 :Union() -> exact range of ':Union()'
        var union0Offset = text.indexOf(":Union()");
        var union0End = union0Offset + ":Union()".length();
        var union0Highlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == union0Offset && h.getEndOffset() == union0End)
            .findFirst();
        assertTrue("Expected :Union() to be highlighted from offset " + union0Offset + " to " + union0End + 
                   ". Found highlights: " + errorHighlights, union0Highlight.isPresent());
        assertTrue("Message must mention empty composite argument list for :Union()",
                   union0Highlight.get().getDescription().contains("empty composite argument list: :Union() requires at least one schema type"));

        // 6. Line 6: :Seq0 :Seq() -> exact range of ':Seq()'
        var seq0Offset = text.indexOf(":Seq()");
        var seq0End = seq0Offset + ":Seq()".length();
        var seq0Highlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == seq0Offset && h.getEndOffset() == seq0End)
            .findFirst();
        assertTrue("Expected :Seq() to be highlighted from offset " + seq0Offset + " to " + seq0End + 
                   ". Found highlights: " + errorHighlights, seq0Highlight.isPresent());
        assertTrue("Message must mention empty composite argument list for collection",
                   seq0Highlight.get().getDescription().contains("empty composite argument list: collection requires schema type argument"));

        // 7. Line 7: :Set0 :Set() -> exact range of ':Set()' & diagnostic uniformity with :Seq()
        var set0Offset = text.indexOf(":Set()");
        var set0End = set0Offset + ":Set()".length();
        var set0Highlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == set0Offset && h.getEndOffset() == set0End)
            .findFirst();
        assertTrue("Expected :Set() to be highlighted from offset " + set0Offset + " to " + set0End + 
                   ". Found highlights: " + errorHighlights, set0Highlight.isPresent());
        assertTrue("Message for :Set() must match :Seq() canonical message",
                   set0Highlight.get().getDescription().contains("empty composite argument list: collection requires schema type argument"));

        // 8. Line 8: :Map0 :Map() -> exact range of ':Map()'
        var map0Offset = text.indexOf(":Map()");
        var map0End = map0Offset + ":Map()".length();
        var map0Highlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == map0Offset && h.getEndOffset() == map0End)
            .findFirst();
        assertTrue("Expected :Map() to be highlighted from offset " + map0Offset + " to " + map0End + 
                   ". Found highlights: " + errorHighlights, map0Highlight.isPresent());
        assertTrue("Message must mention insufficient composite arguments for :Map()",
                   map0Highlight.get().getDescription().contains("insufficient composite arguments: expected 2 schema type arguments"));

        // 9. Line 10: :type :Union(:Int32 :Enum[]) -> exact range of nested ':Enum[]'
        var typeOffset = text.indexOf(":type");
        var nestedEnumOffset = text.indexOf(":Enum[]", typeOffset);
        var nestedEnumEnd = nestedEnumOffset + ":Enum[]".length();
        var nestedEnumHighlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == nestedEnumOffset && h.getEndOffset() == nestedEnumEnd)
            .findFirst();
        assertTrue("Expected nested :Enum[] in :type to be highlighted from offset " + nestedEnumOffset + " to " + nestedEnumEnd + 
                   ". Found highlights: " + errorHighlights, nestedEnumHighlight.isPresent());
        assertTrue("Message for nested :Enum[] must mention empty enum variant list",
                   nestedEnumHighlight.get().getDescription().contains("empty enum variant list: :Enum[] requires at least one value keyword variant"));
    }

    public void testWhitespacePaddedEmptyCompositeRangeExpansion() {
        var fixtureText = """
            {
              :defs {
                :EnumPad :Enum [   ]
                :TuplePad :Tuple (   )
                :UnionPad :Union (   )
              }
              :type :TuplePad
              :body ()
            }
            """;

        var psiFile = myFixture.configureByText("padded_empty_composites.stvn", fixtureText);
        var highlights = myFixture.doHighlighting();
        var errorHighlights = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        var text = psiFile.getText();

        // 1. :EnumPad :Enum [   ]
        var enumPadOffset = text.indexOf(":Enum [   ]");
        var enumPadEnd = enumPadOffset + ":Enum [   ]".length();
        var enumPadHighlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == enumPadOffset && h.getEndOffset() == enumPadEnd)
            .findFirst();
        assertTrue("Expected ':Enum [   ]' to be highlighted across full construct. Found: " + errorHighlights, enumPadHighlight.isPresent());

        // 2. :TuplePad :Tuple (   )
        var tuplePadOffset = text.indexOf(":Tuple (   )");
        var tuplePadEnd = tuplePadOffset + ":Tuple (   )".length();
        var tuplePadHighlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == tuplePadOffset && h.getEndOffset() == tuplePadEnd)
            .findFirst();
        assertTrue("Expected ':Tuple (   )' to be highlighted across full construct. Found: " + errorHighlights, tuplePadHighlight.isPresent());

        // 3. :UnionPad :Union (   )
        var unionPadOffset = text.indexOf(":Union (   )");
        var unionPadEnd = unionPadOffset + ":Union (   )".length();
        var unionPadHighlight = errorHighlights.stream()
            .filter(h -> h.getStartOffset() == unionPadOffset && h.getEndOffset() == unionPadEnd)
            .findFirst();
        assertTrue("Expected ':Union (   )' to be highlighted across full construct. Found: " + errorHighlights, unionPadHighlight.isPresent());
    }

    public void testUnionDisjointnessInspectionVerification() {
        myFixture.enableInspections(new org.stvnadore.plugin.validation.StvnVariantStyleInspection());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        if (projSettings != null) {
            projSettings.getState().enableRedundantTagInspection = true;
            projSettings.getState().preferImpliedSumTypes = true;
        }

        var text = """
                {
                  :defs {
                    :DisjointUnion :Union( :Int32 :String :Boolean )
                    :OverlappingUnion :Union( :Int32 :Uint32 )
                  }
                  :type :Tuple( :DisjointUnion :OverlappingUnion )
                  :body (
                    #1 100
                    #1 200
                  )
                }
                """;
        myFixture.configureByText("union_disjointness_integration.stvn", text);
        var highlights = myFixture.doHighlighting();

        var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        assertEquals("Expected 0 compilation errors", 0, errors.size());

        var redundant = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.WARNING && "Redundant variant tag".equals(h.getDescription()))
            .toList();
        assertEquals("Expected exactly 1 warning on element 0 (#1 100) and 0 warnings on element 1 (#1 200)", 1, redundant.size());
    }

    public void testEitherAmbiguityDiagnosticVerification() {
        var ambiguousText = """
                {
                  :defs {
                    :EitherRepeat :Either( :Int32 :Uint32 )
                  }
                  :type :EitherRepeat
                  :body 1
                }
                """;
        var psiFile = myFixture.configureByText("either_ambiguous.stvn", ambiguousText);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
            .toList();

        assertFalse("Expected compilation error for ambiguous implicit :Either resolution", errors.isEmpty());
        var errorDescriptions = errors.stream().map(HighlightInfo::getDescription).toList();
        assertTrue(
            "Expected error containing 'Ambiguous implicit resolution: Value matches both Left and Right branches of :Either'. Found: " + errorDescriptions,
            errorDescriptions.stream().anyMatch(d -> d != null && d.contains("Ambiguous implicit resolution: Value matches both Left and Right branches of :Either"))
        );

        // Verify coordinate clamping on '1'
        var text = psiFile.getText();
        var bodyIdx = text.indexOf(":body");
        var oneOffset = text.indexOf("1", bodyIdx);
        var oneHighlight = errors.stream()
            .filter(h -> h.getStartOffset() == oneOffset && h.getEndOffset() == oneOffset + 1)
            .findFirst();
        assertTrue("Expected error highlight to clamp precisely to '1'", oneHighlight.isPresent());

        // Verify explicit #Left 1 produces 0 errors and 0 warnings
        setUseLongFormSumTypes(true);
        myFixture.enableInspections(new org.stvnadore.plugin.validation.StvnVariantStyleInspection());
        var projSettings = StvnProjectSettings.getInstance(getProject());
        if (projSettings != null) {
            projSettings.getState().enableRedundantTagInspection = true;
            projSettings.getState().preferImpliedSumTypes = true;
        }

        var leftText = """
                {
                  :defs {
                    :EitherRepeat :Either( :Int32 :Uint32 )
                  }
                  :type :EitherRepeat
                  :body #Left 1
                }
                """;
        myFixture.configureByText("either_explicit_left.stvn", leftText);
        var leftHighlights = myFixture.doHighlighting();
        assertEquals("Expected 0 errors for explicit #Left 1", 0, leftHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count());
        assertEquals("Expected 0 warnings for explicit #Left 1", 0, leftHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());

        var rightText = """
                {
                  :defs {
                    :EitherRepeat :Either( :Int32 :Uint32 )
                  }
                  :type :EitherRepeat
                  :body #Right 1
                }
                """;
        myFixture.configureByText("either_explicit_right.stvn", rightText);
        var rightHighlights = myFixture.doHighlighting();
        assertEquals("Expected 0 errors for explicit #Right 1", 0, rightHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).count());
        assertEquals("Expected 0 warnings for explicit #Right 1", 0, rightHighlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).count());
    }

    public void testEitherRuleEViolationUntaggedLeftBranch() {
        var text = """
                {
                  :type :Either( :Int32 :String )
                  :body 42
                }
                """;
        var psiFile = myFixture.configureByText("either_rule_e_left.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        assertFalse("Expected compilation error for untagged Left branch under Rule E", errors.isEmpty());
        var errorDescriptions = errors.stream().map(HighlightInfo::getDescription).toList();
        assertTrue(
            "Expected error containing 'Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required'. Found: " + errorDescriptions,
            errorDescriptions.stream().anyMatch(d -> d != null && d.contains("Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required"))
        );

        // Verify coordinate clamping on '42'
        var docText = psiFile.getText();
        var bodyIdx = docText.indexOf(":body");
        var numOffset = docText.indexOf("42", bodyIdx);
        var numHighlight = errors.stream()
            .filter(h -> h.getStartOffset() == numOffset && h.getEndOffset() == numOffset + 2)
            .findFirst();
        assertTrue("Expected error highlight to clamp precisely to '42'", numHighlight.isPresent());
    }

    public void testEitherRuleFNormativeNegativeExample() {
        var text = """
                {
                  :type :Seq( :Option( :Either( :String :Float ) ) )
                  :body [ "test" ]
                }
                """;
        myFixture.configureByText("either_rule_f_negative.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        assertFalse("Expected compilation error for Section 8.1 Rule F normative negative example", errors.isEmpty());
        var errorDescriptions = errors.stream().map(HighlightInfo::getDescription).toList();
        assertTrue(
            "Expected error containing Rule E violation diagnostic for [ \"test\" ]. Found: " + errorDescriptions,
            errorDescriptions.stream().anyMatch(d -> d != null && d.contains("Rule E Violation: Untagged value matching Left branch of :Either is non-inferable; explicit #Left tag is required"))
        );
    }

    public void testEitherRuleFNormativePositiveExamples() {
        var text = """
                {
                  :type :Seq( :Option( :Either( :String :Float ) ) )
                  :body [
                    #Left "test"
                    42.5
                  ]
                }
                """;
        myFixture.configureByText("either_rule_f_positive.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
        var warnings = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.WARNING).toList();
        assertEquals("Expected 0 errors for Section 8.1 Rule F positive examples", 0, errors.size());
        assertEquals("Expected 0 warnings for Section 8.1 Rule F positive examples", 0, warnings.size());
    }
}

