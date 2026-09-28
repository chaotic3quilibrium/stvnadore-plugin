package org.stvnadore.plugin.psi;

import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.ConstantDefinition;
import org.stvnadore.psi.SchemaType;
import org.stvnadore.psi.TypeDefinition;

/**
 * Unit test suite verifying canonical schema formatting and child metadata preservation
 * in {@link StvnSchemaFormatter}.
 */
@NullMarked
public final class StvnSchemaFormatterTest extends BasePlatformTestCase {

    public void testEitherWithChildMetadataFormatting() {
        var source = """
            {
              :defs {
                :Choice :Either( :Float { #size 32 } :Int )
              }
              :type :Choice
              :body #Right 42
            }
            """;
        var file = myFixture.configureByText("either_format.stvn", source);
        var typeDef = PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        assertNotNull("TypeDefinition must be present", typeDef);

        var schemaType = typeDef.getSchemaType();
        assertNotNull("SchemaType must be present", schemaType);

        var formatted = StvnSchemaFormatter.formatSchema(schemaType);
        assertEquals(":Either( :Float { #size 32 } :Int )", formatted);

        var defFormatted = StvnSchemaFormatter.formatSchema(typeDef);
        assertEquals(":Either( :Float { #size 32 } :Int )", defFormatted);
    }

    public void testTupleMultipleInlineChildMetadata() {
        var source = """
            {
              :defs {
                :Triad :Tuple( { #size 16 } :Int { #size 32 } :Int { #unsigned #size 64 } :Int )
              }
              :type :Triad
              :body ( 100 200 400 )
            }
            """;
        var file = myFixture.configureByText("tuple_format.stvn", source);
        var typeDef = PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        assertNotNull("TypeDefinition must be present", typeDef);

        var schemaType = typeDef.getSchemaType();
        assertNotNull("SchemaType must be present", schemaType);

        var formatted = StvnSchemaFormatter.formatSchema(schemaType);
        assertEquals(":Tuple( { #size 16 } :Int { #size 32 } :Int { #unsigned #size 64 } :Int )", formatted);
    }

    public void testSeqWithChildMetadata() {
        var source = """
            {
              :defs {
                :Queue :Seq( { #unsigned #size 32 } :Int )
              }
              :type :Queue
              :body [ 10 20 30 ]
            }
            """;
        var file = myFixture.configureByText("seq_format.stvn", source);
        var typeDef = PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        assertNotNull("TypeDefinition must be present", typeDef);

        var formatted = StvnSchemaFormatter.formatSchema(typeDef.getSchemaType());
        assertEquals(":Seq( { #unsigned #size 32 } :Int )", formatted);
    }

    public void testMapWithChildMetadata() {
        var source = """
            {
              :defs {
                :PortMap :Map( { #minSize 1 #maxSize 10 } :String { #minIncl 1 #maxExcl 65536 } :Int )
              }
              :type :PortMap
              :body { [ "http" 80 ] }
            }
            """;
        var file = myFixture.configureByText("map_format.stvn", source);
        var typeDef = PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        assertNotNull("TypeDefinition must be present", typeDef);

        var formatted = StvnSchemaFormatter.formatSchema(typeDef.getSchemaType());
        assertEquals(":Map( { #minSize 1 #maxSize 10 } :String { #minIncl 1 #maxExcl 65536 } :Int )", formatted);
    }

    public void testOptionWithChildMetadata() {
        var source = """
            {
              :defs {
                :OptDecimal :Option( { #exact } :Float )
              }
              :type :OptDecimal
              :body #Some 3.14
            }
            """;
        var file = myFixture.configureByText("option_format.stvn", source);
        var typeDef = PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        assertNotNull("TypeDefinition must be present", typeDef);

        var formatted = StvnSchemaFormatter.formatSchema(typeDef.getSchemaType());
        assertEquals(":Option( { #exact } :Float )", formatted);
    }

    public void testConstantDefinitionFormatting() {
        var source = """
            {
              :defs {
                #MAX_PORTS { #size 16 } :Int 65535
              }
              :type :Int
              :body 42
            }
            """;
        var file = myFixture.configureByText("const_format.stvn", source);
        var constDef = PsiTreeUtil.findChildOfType(file, ConstantDefinition.class);
        assertNotNull("ConstantDefinition must be present", constDef);

        var formatted = StvnSchemaFormatter.formatSchema(constDef);
        assertEquals("{ #size 16 } :Int", formatted);
    }

    public void testCommentStrippingInMetadataMap() {
        var source = """
            {
              :defs {
                :Custom :Either( // leading comment
                  { // child comment
                    #size 32 // bit width
                  } :Int
                  :Float
                )
              }
              :type :Custom
              :body 42
            }
            """;
        var file = myFixture.configureByText("comments_format.stvn", source);
        var typeDef = PsiTreeUtil.findChildOfType(file, TypeDefinition.class);
        assertNotNull("TypeDefinition must be present", typeDef);

        var formatted = StvnSchemaFormatter.formatSchema(typeDef.getSchemaType());
        assertEquals(":Either( { #size 32 } :Int :Float )", formatted);
    }
}
