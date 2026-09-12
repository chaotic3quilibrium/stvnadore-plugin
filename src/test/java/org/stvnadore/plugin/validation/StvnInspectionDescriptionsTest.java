package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.Map;

/**
 * Platform unit test verifying that all registered STVN inspection tools
 * have corresponding HTML description files that load properly into the IDE Settings UI.
 */
@NullMarked
public final class StvnInspectionDescriptionsTest extends BasePlatformTestCase {

    private static final Map<Class<? extends LocalInspectionTool>, List<String>> EXPECTED_INSPECTION_KEYWORDS = Map.ofEntries(
        Map.entry(StvnDegradedSchemaInspection.class, List.of("degraded", "schema", "fallback", "defs")),
        Map.entry(StvnMapStructuralInspection.class, List.of("map", "list", "Convert to Map Literal")),
        Map.entry(StvnVariantStyleInspection.class, List.of("variant", "tag", ":Union", ":Either", ":Option", "#Some", "#Right", "#1")),
        Map.entry(StvnBooleanValidityInspection.class, List.of("boolean", "#TRUE", "#FALSE", "#T", "#F")),
        Map.entry(StvnDegenerateCompositeInspection.class, List.of("degenerate", ":Enum", ":Union", ":Tuple", "unwrap")),
        Map.entry(StvnEnumSubsetInspection.class, List.of("Enum Subset", "#filterIncl", "#filterExcl", "order", "narrowing")),
        Map.entry(StvnFencedStringInspection.class, List.of("fenced", "string", "delimiter", "Rule STR-04")),
        Map.entry(StvnLhsReservedTypeInspection.class, List.of("fundamental", "LHS", "aliased")),
        Map.entry(StvnConstantRangeInspection.class, List.of("bit-width", "constant", "range")),
        Map.entry(StvnFlatDocumentIncludeInspection.class, List.of("flat", "leaf", "include")),
        Map.entry(StvnNestedPackageInspection.class, List.of("nested", "package")),
        Map.entry(StvnTrailingSlashInspection.class, List.of("trailing", "slash", ":use"))
    );

    public void testDirectInspectionToolDescriptionLoading() throws Exception {
        for (var entry : EXPECTED_INSPECTION_KEYWORDS.entrySet()) {
            var inspectionClass = entry.getKey();
            var expectedKeywords = entry.getValue();

            LocalInspectionTool tool;
            try {
                tool = inspectionClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                fail("Failed to instantiate inspection class: " + inspectionClass.getName() + " - " + e.getMessage());
                return;
            }

            var wrapper = new com.intellij.codeInspection.ex.LocalInspectionToolWrapper(tool);
            var description = wrapper.loadDescription();
            assertNotNull("Inspection description HTML must not be null for " + inspectionClass.getSimpleName(), description);
            assertFalse("Inspection description HTML must not be empty for " + inspectionClass.getSimpleName(), description.isBlank());
            assertTrue("Description must start with <html> tag for " + inspectionClass.getSimpleName(), description.contains("<html>"));
            assertTrue("Description must contain <body> tag for " + inspectionClass.getSimpleName(), description.contains("<body>"));
            assertFalse(
                "Inspection description HTML for " + inspectionClass.getSimpleName() + " must not contain invalid ';;' comments",
                description.contains(";;")
            );

            for (var keyword : expectedKeywords) {
                assertTrue(
                    "Inspection description for " + inspectionClass.getSimpleName() + " must contain keyword: '" + keyword + "'",
                    description.toLowerCase().contains(keyword.toLowerCase())
                );
            }
        }
    }

    public void testPluginXmlExtensionPointResolution() throws Exception {
        var eps = LocalInspectionEP.LOCAL_INSPECTION.getExtensionList();
        var stvnInspections = eps.stream()
            .filter(ep -> "STVN".equals(ep.language))
            .toList();

        assertEquals("Expected exactly 12 registered STVN local inspections in plugin.xml", 12, stvnInspections.size());

        for (var ep : stvnInspections) {
            var wrapper = new com.intellij.codeInspection.ex.LocalInspectionToolWrapper(ep);
            var description = wrapper.loadDescription();
            assertNotNull("Inspection tool instance must not be null for EP " + ep.implementationClass, wrapper.getTool());

            assertNotNull("Inspection description HTML must load successfully for EP shortName '" + ep.getShortName() + "'", description);
            assertFalse("Inspection description HTML must not be blank for EP shortName '" + ep.getShortName() + "'", description.isBlank());
            assertTrue("Inspection description HTML must contain valid body for EP shortName '" + ep.getShortName() + "'", description.contains("<body>"));
            assertFalse(
                "Inspection description HTML must not contain invalid ';;' comments for EP shortName '" + ep.getShortName() + "'",
                description.contains(";;")
            );
        }
    }
}
