package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.psi.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Conformance test suite verifying universal schemaType metadata elevation across composite constructors.
 */
@NullMarked
public final class StvnSchemaTypeMetadataTest extends BasePlatformTestCase {

    private String loadResource(String path) throws Exception {
        var loader = getClass().getClassLoader();
        var primary = loader.getResourceAsStream(path);
        var stream = primary != null ? primary : loader.getResourceAsStream("shared-fixtures/" + path);
        assertNotNull("Resource must exist on classpath: " + path, stream);
        try (var in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void testCompositeInlineMetadataParsesCleanly() throws Exception {
        var content = loadResource("syntax/valid/composites/composite_inline_metadata.stvn");
        var file = myFixture.configureByText("composite_inline_metadata.stvn", content);
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        assertTrue("Valid composite inline metadata must parse with 0 syntax errors. Found: " + errors, errors.isEmpty());

        // Verify child metadata maps exist in AST
        var metaMaps = PsiTreeUtil.findChildrenOfType(file, MetadataMap.class);
        assertEquals("Must contain exactly 9 metadata blocks across composite constructors", 9, metaMaps.size());

        // Verify child SchemaTypes have non-null getMetadataMap()
        var schemaTypes = PsiTreeUtil.findChildrenOfType(file, SchemaType.class);
        var schemaTypesWithMeta = schemaTypes.stream()
                .filter(st -> st.getMetadataMap() != null)
                .toList();
        assertEquals("Must contain exactly 9 SchemaType nodes with metadata", 9, schemaTypesWithMeta.size());
    }

    public void testEmptyChildMetadataBlockRejection() throws Exception {
        var content = loadResource("syntax/invalid/composites/inline_metadata_empty_block.stvn");
        myFixture.configureByText("inline_metadata_empty_block.stvn", content);
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnMetadataFacetInspection()});
        var highlights = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
                .toList();

        assertFalse("Must flag empty metadata block in child schema", highlights.isEmpty());
        assertTrue("Error message must indicate empty block",
                highlights.get(0).getDescription().contains("Empty metadata block is invalid"));
    }

    public void testIncompatibleFacetOnChildScalarRejection() throws Exception {
        var content = loadResource("syntax/invalid/composites/inline_metadata_incompatible_facet.stvn");
        myFixture.configureByText("inline_metadata_incompatible_facet.stvn", content);
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnMetadataFacetInspection()});
        var highlights = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
                .toList();

        assertFalse("Must flag incompatible facet on child schema", highlights.isEmpty());
        assertTrue("Error message must indicate facet prohibition",
                highlights.get(0).getDescription().contains("Facet is not permitted on :Int"));
    }

    public void testChildStringSizeProhibitedInspection() throws Exception {
        var content = loadResource("syntax/invalid/composites/inline_metadata_string_size_prohibited.stvn");
        myFixture.configureByText("inline_metadata_string_size_prohibited.stvn", content);
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnStringCardinalityInspection()});
        var highlights = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
                .toList();

        assertFalse("Must flag '#size' facet on child :String", highlights.isEmpty());
        var hasSizeProhibitedError = highlights.stream()
                .anyMatch(h -> h.getDescription() != null && h.getDescription().contains("Facet '#size' is prohibited on ':String'"));
        assertTrue("Error message must cite Rule MCT § 3.1.3. Actual highlights: " + highlights.stream().map(com.intellij.codeInsight.daemon.impl.HighlightInfo::getDescription).toList(),
                hasSizeProhibitedError);
    }

    public void testChildDiscreteIntervalClosedBoundInspection() throws Exception {
        var content = loadResource("syntax/invalid/composites/inline_metadata_inverted_interval.stvn");
        myFixture.configureByText("inline_metadata_inverted_interval.stvn", content);
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnDiscreteIntervalInspection()});
        var highlights = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity().equals(HighlightSeverity.ERROR))
                .toList();

        assertFalse("Must flag '#maxIncl' on discrete child :Int", highlights.isEmpty());
        var hasDiscreteBoundError = highlights.stream()
                .anyMatch(h -> h.getDescription() != null && h.getDescription().contains("Discrete types reject closed upper bound '#maxIncl'"));
        assertTrue("Error message must reject closed upper bound. Actual highlights: " + highlights.stream().map(com.intellij.codeInsight.daemon.impl.HighlightInfo::getDescription).toList(),
                hasDiscreteBoundError);
    }

    public void testEitherChildMetadataInline() {
        var source = """
            {
              :defs {
                :Choice :Either( :Float { #size 32 } :Int )
              }
              :type :Choice
              :body #Right 42
            }
            """;
        var file = myFixture.configureByText("either_inline.stvn", source);
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        assertTrue("Inline Either metadata must parse cleanly: " + errors, errors.isEmpty());
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
        var file = myFixture.configureByText("tuple_inline.stvn", source);
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        assertTrue("Inline Tuple metadata must parse cleanly: " + errors, errors.isEmpty());
    }

    public void testSeqInlineChildMetadata() {
        var source = """
            {
              :defs {
                :Queue :Seq( { #unsigned #size 32 } :Int )
              }
              :type :Queue
              :body [ 10 20 30 ]
            }
            """;
        var file = myFixture.configureByText("seq_inline.stvn", source);
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        assertTrue("Inline Seq metadata must parse cleanly: " + errors, errors.isEmpty());
    }

    public void testMapInlineChildMetadata() {
        var source = """
            {
              :defs {
                :PortMap :Map( { #minSize 1 #maxSize 10 } :String { #minIncl 1 #maxExcl 65536 } :Int )
              }
              :type :PortMap
              :body { [ "http" 80 ] [ "https" 443 ] }
            }
            """;
        var file = myFixture.configureByText("map_inline.stvn", source);
        var errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class);
        assertTrue("Inline Map metadata must parse cleanly: " + errors, errors.isEmpty());
    }
}
