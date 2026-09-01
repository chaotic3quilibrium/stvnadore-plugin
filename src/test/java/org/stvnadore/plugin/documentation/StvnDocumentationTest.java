package org.stvnadore.plugin.documentation;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

/**
 * Platform test suite verifying quick documentation and structural metric
 * consistency across empty and non-empty :Tuple, :Union, :Enum, and collection schemas.
 */
@NullMarked
public final class StvnDocumentationTest extends BasePlatformTestCase {

    private final StvnDocumentationProvider provider = new StvnDocumentationProvider();

    private String getDocAtOffset(String text, int offset) {
        var elem = myFixture.getFile().findElementAt(offset);
        assertNotNull("PSI element at offset " + offset + " must not be null", elem);
        var docElem = provider.getCustomDocumentationElement(myFixture.getEditor(), myFixture.getFile(), elem, offset);
        assertNotNull("Documentation element at offset " + offset + " must not be null", docElem);
        var doc = provider.generateDoc(docElem, elem);
        assertNotNull("Generated documentation at offset " + offset + " must not be null", doc);
        return doc;
    }

    public void testEmptyTupleDirectHoverRendersArityZero() {
        var text = """
            {
              :type :Tuple()
              :body ()
            }
            """;
        myFixture.configureByText("empty_tuple.stvn", text);

        // 1. Hover on :Tuple keyword
        var tupleOffset = text.indexOf(":Tuple");
        var docKw = getDocAtOffset(text, tupleOffset);
        assertTrue("Expected 'Arity:</b> 0' on :Tuple keyword", docKw.contains("Arity:</b> 0"));
        assertTrue("Expected 'Product Type Constructor' header", docKw.contains("<b>Product Type Constructor:</b> :Tuple( ... )"));

        // 2. Hover on '(' delimiter
        var parenOffset = text.indexOf("()");
        var docParen = getDocAtOffset(text, parenOffset);
        assertTrue("Expected 'Arity:</b> 0' on '(' delimiter", docParen.contains("Arity:</b> 0"));
    }

    public void testNonEmptyTupleDirectHoverRendersArity() {
        var text = """
            {
              :type :Tuple( :Int32 :String :Boolean )
              :body ( 42 "hello" #TRUE )
            }
            """;
        myFixture.configureByText("non_empty_tuple.stvn", text);

        var offset = text.indexOf(":Tuple");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Expected 'Arity:</b> 3' on :Tuple keyword", doc.contains("Arity:</b> 3"));
        assertTrue("Expected 'Product Type Constructor' header", doc.contains("<b>Product Type Constructor:</b> :Tuple( ... )"));
    }

    public void testEmptyUnionDirectHoverRendersBranchCountZero() {
        var text = """
            {
              :type :Union()
              :body 0
            }
            """;
        myFixture.configureByText("empty_union.stvn", text);

        // 1. Hover on :Union keyword
        var unionOffset = text.indexOf(":Union");
        var docKw = getDocAtOffset(text, unionOffset);
        assertTrue("Expected 'Branch Count:</b> 0' on :Union keyword", docKw.contains("Branch Count:</b> 0"));
        assertTrue("Expected 'Algebraic Union Constructor' header", docKw.contains("<b>Algebraic Union Constructor:</b> :Union( ... )"));

        // 2. Hover on '(' delimiter
        var parenOffset = text.indexOf("()");
        var docParen = getDocAtOffset(text, parenOffset);
        assertTrue("Expected 'Branch Count:</b> 0' on '(' delimiter", docParen.contains("Branch Count:</b> 0"));
    }

    public void testNonEmptyUnionDirectHoverRendersBranchCount() {
        var text = """
            {
              :type :Union( :Int32 :String )
              :body #1 100
            }
            """;
        myFixture.configureByText("non_empty_union.stvn", text);

        var offset = text.indexOf(":Union");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Expected 'Branch Count:</b> 2' on :Union keyword", doc.contains("Branch Count:</b> 2"));
        assertTrue("Expected 'Algebraic Union Constructor' header", doc.contains("<b>Algebraic Union Constructor:</b> :Union( ... )"));
    }

    public void testEmptyEnumDirectHoverRendersVariantCountZero() {
        var text = """
            {
              :type :Enum []
              :body #None
            }
            """;
        myFixture.configureByText("empty_enum.stvn", text);

        // 1. Hover on :Enum keyword
        var enumOffset = text.indexOf(":Enum");
        var docKw = getDocAtOffset(text, enumOffset);
        assertTrue("Expected 'Variant Count:</b> 0' on :Enum keyword", docKw.contains("Variant Count:</b> 0"));
        assertTrue("Expected 'Enumeration Constructor' header", docKw.contains("<b>Enumeration Constructor:</b> :Enum [ ... ]"));

        // 2. Hover on '[' delimiter
        var brackOffset = text.indexOf("[]");
        var docBrack = getDocAtOffset(text, brackOffset);
        assertTrue("Expected 'Variant Count:</b> 0' on '[' delimiter", docBrack.contains("Variant Count:</b> 0"));
    }

    public void testNonEmptyEnumDirectHoverRendersVariantCount() {
        var text = """
            {
              :type :Enum [ #Active #Inactive #Pending ]
              :body #Active
            }
            """;
        myFixture.configureByText("non_empty_enum.stvn", text);

        var offset = text.indexOf(":Enum");
        var doc = getDocAtOffset(text, offset);
        assertTrue("Expected 'Variant Count:</b> 3' on :Enum keyword", doc.contains("Variant Count:</b> 3"));
        assertTrue("Expected 'Enumeration Constructor' header", doc.contains("<b>Enumeration Constructor:</b> :Enum [ ... ]"));
    }

    public void testNominalAliasEmptyCompositesInDefs() {
        var text = """
            {
              :defs {
                :EmptyTuple :Tuple()
                :EmptyUnion :Union()
                :EmptyEnum  :Enum []
              }
              :type :Tuple( :EmptyTuple :EmptyUnion :EmptyEnum )
              :body ( () 0 #None )
            }
            """;
        myFixture.configureByText("empty_aliases.stvn", text);

        var tupleOffset = text.indexOf(":EmptyTuple");
        var tupleDoc = getDocAtOffset(text, tupleOffset);
        assertTrue("Expected 'Arity:</b> 0' for :EmptyTuple alias", tupleDoc.contains("Arity:</b> 0"));
        assertTrue(tupleDoc.contains("Type Alias:</b> :EmptyTuple"));

        var unionOffset = text.indexOf(":EmptyUnion");
        var unionDoc = getDocAtOffset(text, unionOffset);
        assertTrue("Expected 'Branch Count:</b> 0' for :EmptyUnion alias", unionDoc.contains("Branch Count:</b> 0"));
        assertTrue(unionDoc.contains("Type Alias:</b> :EmptyUnion"));

        var enumOffset = text.indexOf(":EmptyEnum");
        var enumDoc = getDocAtOffset(text, enumOffset);
        assertTrue("Expected 'Variant Count:</b> 0' for :EmptyEnum alias", enumDoc.contains("Variant Count:</b> 0"));
        assertTrue(enumDoc.contains("Type Alias:</b> :EmptyEnum"));
    }

    public void testCollectionConstructorsSpecificationDoc() {
        var text = """
            {
              :type :Tuple(
                :Seq( :Int32 )
                :Set( :String )
                :Map( :String :Int32 )
                :MapInv( :String :Int32 )
              )
              :body ( [] [] {} {} )
            }
            """;
        myFixture.configureByText("collections_doc.stvn", text);

        var mapOffset = text.indexOf(":Map(");
        var mapDoc = getDocAtOffset(text, mapOffset);
        assertTrue(mapDoc.contains("<b>Collection Constructor:</b> :Map( :KeyType :ValType )"));
        assertTrue(mapDoc.contains("Defines a key-value mapping with strictly unique keys."));

        var mapInvOffset = text.indexOf(":MapInv(");
        var mapInvDoc = getDocAtOffset(text, mapInvOffset);
        assertTrue(mapInvDoc.contains("<b>Collection Constructor:</b> :MapInv( :KeyType :ValType )"));
        assertTrue(mapInvDoc.contains("bijective uniqueness"));

        var seqOffset = text.indexOf(":Seq(");
        var seqDoc = getDocAtOffset(text, seqOffset);
        assertTrue(seqDoc.contains("<b>Collection Constructor:</b> :Seq( :ElemType )"));

        var setOffset = text.indexOf(":Set(");
        var setDoc = getDocAtOffset(text, setOffset);
        assertTrue(setDoc.contains("<b>Collection Constructor:</b> :Set( :ElemType )"));
    }
}
