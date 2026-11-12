package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnSyntaxHighlighterColors;

/**
 * Platform integration test suite validating {@link StvnMetadataOrderInspection}
 * and {@link StvnReorderMetadataFacetsQuickFix}.
 * Verifies strict enforcement of the canonical 5-Tier Decimal Hierarchy (§ 6.1).
 */
@NullMarked
public final class StvnMetadataOrderInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new InspectionProfileEntry[]{new StvnMetadataOrderInspection()});
    }

    public void testDetectOutOfOrderFacetsAndApplyQuickFix() {
        var text = """
            {
              :defs {
                :BadOrder { #size 16 #unsigned } :Int
              }
              :type :BadOrder
              :body 42
            }
            """;
        myFixture.configureByText("out_of_order.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertFalse("Expected error on out-of-order facet '#unsigned'", errors.isEmpty());

        int offset = text.indexOf("#unsigned");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected '#unsigned #size 16' in reordered text:\n" + result,
            result.contains("{ #unsigned #size 16 }"));
    }

    public void testIntervalLowerBeforeUpperOrdering() {
        var text = """
            {
              :defs {
                :IntervalDisorder { #maxExcl 100 #minIncl 0 } :Int
              }
              :type :IntervalDisorder
              :body 50
            }
            """;
        myFixture.configureByText("interval_disorder.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertEquals("Expected 1 error for interval lower bound after upper bound", 1, errors.size());

        int offset = text.indexOf("#minIncl");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected '#minIncl 0 #maxExcl 100' in reordered text:\n" + result,
            result.contains("{ #minIncl 0 #maxExcl 100 }"));
    }

    public void testMultiTierCanonicalReordering() {
        var text = """
            {
              :defs {
                :ComplexType {
                  #regex "^[A-Z]+$"
                  #maxSize 16
                  #minSize 4
                  #preserveIndent #TRUE
                  #equatable #TRUE
                } :String
              }
              :type :ComplexType
              :body "TEST"
            }
            """;
        myFixture.configureByText("multi_tier.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertFalse("Expected out-of-order errors on multi-tier disorder", errors.isEmpty());

        int offset = text.indexOf("#preserveIndent");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        // 5-Tier: Tier 1 (#minSize 4 #maxSize 16) < Tier 2 (#equatable #TRUE) < Tier 4 (#preserveIndent #TRUE #regex "^[A-Z]+$")
        var expectedMap = """
                :ComplexType {
                  #minSize 4
                  #maxSize 16
                  #equatable #TRUE
                  #preserveIndent #TRUE
                  #regex "^[A-Z]+$"
                } :String
            """.stripTrailing();
        assertTrue("Expected preserved multi-line canonical 5-tier ordering in result:\n" + result,
            result.contains(expectedMap));
    }

    public void testCompliantOrderProducesZeroWarnings() {
        var text = """
            {
              :defs {
                :CanonicalTemporal { #unsigned #s #size 16 #minIncl 1 #maxExcl 100 } :TimeEpoch
              }
              :type :CanonicalTemporal
              :body 50
            }
            """;
        myFixture.configureByText("canonical_temporal.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertTrue("Canonical ordering must produce 0 order violations", errors.isEmpty());
    }

    public void testCanonicalPortDeclarationOrder() {
        var text = """
            {
              :defs {
                :Port { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } :Int
              }
              :type :Port
              :body 8080
            }
            """;
        myFixture.configureByText("canonical_port.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertTrue("Canonical order { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } must produce 0 order violations", errors.isEmpty());
    }

    public void testDisorderedPortQuickFixReordersToCanonicalSevenTier() {
        var text = """
            {
              :defs {
                :DisorderedPort { #maxExcl 65536 #size 16 #unsigned #minIncl 1 } :Int
              }
              :type :DisorderedPort
              :body 8080
            }
            """;
        myFixture.configureByText("disordered_port.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates canonical 5-tier order"))
            .toList();
        assertFalse("Expected out-of-order errors on disordered Port definition", errors.isEmpty());

        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        assertTrue("Expected canonical order { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } in reordered text:\n" + result,
            result.contains("{ #unsigned #size 16 #minIncl 1 #maxExcl 65536 }"));
    }

    public void testSizeBeforeEquatableIsValidFiveTierOrder() {
        var text = """
            {
              :defs {
                :ValidOrderFloat { #size 32 #equatable #TRUE } :Float
              }
              :type :ValidOrderFloat
              :body 1.0
            }
            """;
        myFixture.configureByText("valid_size_equatable.stvn", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("canonical 5-tier order"))
            .toList();
        assertTrue("Canonical 5-tier order { #size 32 #equatable #TRUE } must produce zero errors", errors.isEmpty());
    }

    public void testOutOfOrderMetaEntriesWarningAndQuickFixWithComments() {
        var text = """
            {
              :meta {
                #kind #DEFS_INCLUDE   // Kind comment
                #name "gauntlet_v2"   // Name comment
                #domain "core_test"   // Domain comment
              }
              :defs {
                :Port :Int
              }
            }
            """;
        myFixture.configureByText("gauntlet_v2.core_test.stvn_di", text);
        var highlights = myFixture.doHighlighting();
        var errors = highlights.stream()
            .filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
            .filter(h -> h.getDescription() != null && h.getDescription().contains("violates 3-tier monotonic progression (#name -> #domain -> #kind)"))
            .toList();
        assertEquals("Expected 2 errors on out-of-order :meta entries (#name and #domain after #kind)", 2, errors.size());

        int offset = text.indexOf("#name");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder ':meta' entries to canonical order (#name, #domain, #kind)");
        assertFalse("Expected ':meta' reorder quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        var expectedMeta = """
              :meta {
                #name "gauntlet_v2"   // Name comment
                #domain "core_test"   // Domain comment
                #kind #DEFS_INCLUDE   // Kind comment
              }
            """.stripTrailing();
        assertTrue("Expected canonical :meta order with preserved trailing comments:\n" + result,
            result.contains(expectedMeta));
    }

    public void testFormatActionOnDisorderedMetaBlockProducesZeroPsiInvalidation() {
        var text = """
            {
              :meta {
                #kind #BODY_INCLUDE
                #domain "core_test"
                #name "gauntlet_v4_stage1"
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("gauntlet_v4_stage1.core_test.stvn_i", text);
        new com.intellij.codeInsight.actions.ReformatCodeProcessor(myFixture.getFile(), false).run();
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        var formatted = myFixture.getFile().getText();
        int nameIdx = formatted.indexOf("#name \"gauntlet_v4_stage1\"");
        int domainIdx = formatted.indexOf("#domain \"core_test\"");
        int kindIdx = formatted.indexOf("#kind #BODY_INCLUDE");
        assertTrue("Expected ReformatCodeProcessor to reorder :meta into #name -> #domain -> #kind without PSI invalidation:\n" + formatted,
            nameIdx >= 0 && domainIdx > nameIdx && kindIdx > domainIdx);
    }

    public void testDuplicateMetaFacetsDoNotTriggerFalseOrderViolation() {
        var text = """
            {
              :meta {
                #name "first_name"
                #name "second_name"
                #kind #BODY_INCLUDE
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("first_name.stvn_i", text);
        var highlights = myFixture.doHighlighting();
        var orderErrors = highlights.stream()
            .filter(h -> h.getDescription() != null && h.getDescription().contains("ERR_FACET_ORDER_VIOLATION"))
            .toList();
        assertTrue("Duplicate #name at same tier must not emit ERR_FACET_ORDER_VIOLATION: " + orderErrors, orderErrors.isEmpty());
    }

    public void testMultilineMetadataMapReorderingPreservesTrailingComments() {
        var text = """
            {
              :defs {
                :LedgerChannel {
                  #minIncl 1024   // Tier 1.4
                  #size 16        // Tier 1.3
                  #unsigned       // Tier 1.1
                } :Int
              }
              :type :LedgerChannel
              :body 2048
            }
            """;
        myFixture.configureByText("multiline_facets_comments.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("#size");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected facet reorder quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        var expectedMap = """
                :LedgerChannel {
                  #unsigned       // Tier 1.1
                  #size 16        // Tier 1.3
                  #minIncl 1024   // Tier 1.4
                } :Int
            """.stripTrailing();
        assertTrue("Expected multi-line facet order with preserved trailing comments:\n" + result,
            result.contains(expectedMap));
    }

    public void testScrambledFacetOrderTrapQuickFixPreservesCommentFreeMultiLineLayoutAcrossNestingDepths() {
        var text = """
            {
              :defs {
                :package :com/acme/network {
                  :ScrambledFacetOrderTrap {
                    #minIncl 10
                    #unsigned
                    #maxExcl 100
                    #size 16
                    #comparable #TRUE
                  } :Int
                }
              }
              :type :com/acme/network/ScrambledFacetOrderTrap
              :body 42
            }
            """;
        myFixture.configureByText("stage9_trap.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("#unsigned");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertFalse("Expected facet reorder quick-fix to be available", actions.isEmpty());
        myFixture.launchAction(actions.get(0));

        var result = myFixture.getFile().getText();
        var expectedMap = """
                  :ScrambledFacetOrderTrap {
                    #unsigned
                    #size 16
                    #comparable #TRUE
                    #minIncl 10
                    #maxExcl 100
                  } :Int
            """.stripTrailing();
        assertTrue("Expected comment-free multi-line MetadataMap inside :package to preserve 6/8/6 space indentation:\n" + result,
            result.contains(expectedMap));
    }

    public void testPathDelimitedValueKeywordAndTypeKeywordUniformSemanticHighlighting() {
        var text = """
            {
              :defs {
                :package :com/acme/network {
                  :Port { #unsigned #size 16 #minIncl 1 #maxExcl 65536 } :Int
                  #MIN_PORT :Port 1024
                  #POSIX_REGEX :String "^[a-z_]+$"
                }
                :use [ :com/acme/network { #MIN_PORT #LOCAL_MIN_PORT } ]
                #com/acme/network/DEFAULT_PORT :com/acme/network/Port 8080
                :ConstrainedPort { #minIncl #com/acme/network/MIN_PORT } :com/acme/network/Port
                :ConstrainedId { #regex #com/acme/network/POSIX_REGEX } :String
                :GatewayTuple :Tuple(:com/acme/network/Port :ConstrainedPort)
              }
              :type :GatewayTuple
              :body (
                443
                #com/acme/network/DEFAULT_PORT
              )
            }
            """;
        myFixture.configureByText("stage9_highlighting.stvn", text);
        var highlights = myFixture.doHighlighting();

        String[] valueKeywordTargets = {
            "#com/acme/network/DEFAULT_PORT",
            "#com/acme/network/MIN_PORT",
            "#com/acme/network/POSIX_REGEX"
        };
        for (String target : valueKeywordTargets) {
            int idx = 0;
            while ((idx = text.indexOf(target, idx)) >= 0) {
                final int start = idx;
                final int end = idx + target.length();
                boolean foundFullSpan = highlights.stream().anyMatch(h ->
                    h.getStartOffset() == start
                        && h.getEndOffset() == end
                        && StvnSyntaxHighlighterColors.STVN_VALUE_KEYWORD.equals(h.forcedTextAttributesKey)
                );
                assertTrue("Expected uniform STVN_VALUE_KEYWORD highlight across full span of '" + target + "' at offset " + start, foundFullSpan);
                idx = end;
            }
        }

        String typeTarget = ":com/acme/network/Port";
        int typeIdx = 0;
        while ((typeIdx = text.indexOf(typeTarget, typeIdx)) >= 0) {
            final int start = typeIdx;
            final int end = typeIdx + typeTarget.length();
            boolean foundFullSpan = highlights.stream().anyMatch(h ->
                h.getStartOffset() == start
                    && h.getEndOffset() == end
                    && StvnSyntaxHighlighterColors.STVN_NOMINAL_TYPE.equals(h.forcedTextAttributesKey)
            );
            assertTrue("Expected uniform STVN_NOMINAL_TYPE highlight across full span of '" + typeTarget + "' at offset " + start, foundFullSpan);
            typeIdx = end;
        }
    }

    public void testGauntletV6Stage1ReorderMetaEntriesPreservesAllLeadingTrailingAndFooterComments() {
        var text = """
            {
              :meta { // Opening header comment
                #kind #BODY_INCLUDE // Primary kind comment

                // [ERROR E008 / ERR_FACET_ORDER_VIOLATION]: #domain (Tier 2) placed after #kind (Tier 3).
                // [ERROR E038 / ERR_RESERVED_DOMAIN_PROHIBITED]: User document declares reserved "stvn_*" prefix.

                // [WARN  W001 / WARN_DOCUMENT_NAME_MISMATCH]: "stvn_gate_trap" != filename stem[1] "core_test".
                // Verify Alt+Enter -> More actions... opens Intention Preview cleanly and NEVER offers rename to `.stvn_gate_trap.stvn_i`!
                #domain "stvn_gate_trap"

                // [ERROR E008 / ERR_FACET_ORDER_VIOLATION]: #name (Tier 1) placed after #kind (Tier 3).
                // [WARN  W001 / WARN_DOCUMENT_NAME_MISMATCH]: Does not match filename stem[0].
                #name "gauntlet_v6_stage1_wrong_stem_id"

                // [ERROR E008 / ERR_DUPLICATE_METADATA_FACET]: Duplicate #kind facet inside :meta (zero secondary order error).
                #kind #BODY_INCLUDE

                // [ERROR E008 / ERR_INVALID_METADATA_FACET]: Unknown facet inside closed :meta enclosure.
                #reviewer "mct_director"

                // Footer comment inside :meta before closing brace.
              }
              :type :Int
              :body 42
            }
            """;
        myFixture.configureByText("gauntlet_v6_stage1_meta_identity_and_section_boundaries.core_test.stvn_i", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("#name \"gauntlet_v6_stage1_wrong_stem_id\"");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder ':meta' entries to canonical order (#name, #domain, #kind)");
        assertEquals("Expected ':meta' reorder quick-fix once at #name", 1, actions.size());

        String previewText = myFixture.getIntentionPreviewText(actions.get(0));
        assertNotNull("Intention Preview must render non-null DIFF text", previewText);
        assertEquals("Intention Preview must not mutate physical PSI file", text, myFixture.getFile().getText());
        assertAllStage1MetaCommentsAndOrderPreserved(previewText, true);

        myFixture.launchAction(actions.get(0));
        String reorderedViaQuickFix = myFixture.getFile().getText();
        assertAllStage1MetaCommentsAndOrderPreserved(reorderedViaQuickFix, true);

        myFixture.configureByText("gauntlet_v6_stage1_reformat.core_test.stvn_i", text);
        new com.intellij.codeInsight.actions.ReformatCodeProcessor(myFixture.getFile(), false).run();
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        String reorderedViaReformat = myFixture.getFile().getText();
        assertAllStage1MetaCommentsAndOrderPreserved(reorderedViaReformat, false);
    }

    private static void assertAllStage1MetaCommentsAndOrderPreserved(String actual, boolean expectBlankLinesPreserved) {
        String[] requiredComments = {
            "// Opening header comment",
            "// Primary kind comment",
            "// [ERROR E008 / ERR_FACET_ORDER_VIOLATION]: #domain (Tier 2) placed after #kind (Tier 3).",
            "// [ERROR E038 / ERR_RESERVED_DOMAIN_PROHIBITED]: User document declares reserved \"stvn_*\" prefix.",
            "// [WARN  W001 / WARN_DOCUMENT_NAME_MISMATCH]: \"stvn_gate_trap\" != filename stem[1] \"core_test\".",
            "// Verify Alt+Enter -> More actions... opens Intention Preview cleanly",
            "// [ERROR E008 / ERR_FACET_ORDER_VIOLATION]: #name (Tier 1) placed after #kind (Tier 3).",
            "// [WARN  W001 / WARN_DOCUMENT_NAME_MISMATCH]: Does not match filename stem[0].",
            "// [ERROR E008 / ERR_DUPLICATE_METADATA_FACET]: Duplicate #kind facet inside :meta (zero secondary order error).",
            "// [ERROR E008 / ERR_INVALID_METADATA_FACET]: Unknown facet inside closed :meta enclosure.",
            "// Footer comment inside :meta before closing brace."
        };
        for (String comment : requiredComments) {
            assertTrue("Missing comment after :meta reorder: " + comment + "\nActual:\n" + actual, actual.contains(comment));
        }
        if (expectBlankLinesPreserved) {
            assertTrue(
                "Intra-comment blank line inside leading comment block must be preserved:\n" + actual,
                actual.contains("reserved \"stvn_*\" prefix.\n\n    // [WARN  W001 / WARN_DOCUMENT_NAME_MISMATCH]: \"stvn_gate_trap\"")
            );
        }

        int nameIdx = actual.indexOf("#name \"gauntlet_v6_stage1_wrong_stem_id\"");
        int domainIdx = actual.indexOf("#domain \"stvn_gate_trap\"");
        int firstKindIdx = actual.indexOf("#kind #BODY_INCLUDE // Primary kind comment");
        int secondKindIdx = actual.indexOf("#kind #BODY_INCLUDE", firstKindIdx + 1);
        int reviewerIdx = actual.indexOf("#reviewer \"mct_director\"");
        int footerIdx = actual.indexOf("// Footer comment inside :meta before closing brace.");
        assertTrue(
            "Entries must sort stably into #name -> #domain -> #kind[0] -> #kind[1] -> #reviewer -> footer:\n" + actual,
            nameIdx >= 0
                && domainIdx > nameIdx
                && firstKindIdx > domainIdx
                && secondKindIdx > firstKindIdx
                && reviewerIdx > secondKindIdx
                && footerIdx > reviewerIdx
        );
        assertFalse("Closing '}' must never collapse onto footer '//' comment line:\n" + actual,
            actual.contains("closing brace. }") || actual.contains("closing brace.}"));
    }

    public void testReorderMultilineMetadataFacetsPreservesLeadingSameLineAndFooterComments() {
        var text = """
            {
              :defs {
                :PrecisionRate9 {
                  // Exclusive upper bound
                  #maxExcl 1000.00000 // Tier 4 upper
                  // Exact decimal mode
                  #exact              // Tier 1.1 exact
                  // Inclusive lower bound
                  #minIncl -1000.00000 // Tier 4 lower
                  // Max significant digits
                  #maxSize 9          // Tier 1.3 capacity
                  // Footer note inside MetadataMap
                } :Float
              }
              :type :PrecisionRate9
              :body 42.5
            }
            """;
        myFixture.configureByText("stage1_metadata_map_comments.stvn", text);
        myFixture.doHighlighting();

        int offset = text.indexOf("#exact");
        myFixture.getEditor().getCaretModel().moveToOffset(offset);
        var actions = myFixture.filterAvailableIntentions("Reorder metadata facets to canonical 5-tier order");
        assertEquals("Expected MetadataMap reorder quick-fix", 1, actions.size());

        String previewText = myFixture.getIntentionPreviewText(actions.get(0));
        assertNotNull("MetadataMap Intention Preview must return non-null DIFF", previewText);
        assertAllMetadataMapCommentsAndOrderPreserved(previewText);

        myFixture.launchAction(actions.get(0));
        assertAllMetadataMapCommentsAndOrderPreserved(myFixture.getFile().getText());

        myFixture.configureByText("stage1_metadata_map_reformat.stvn", text);
        new com.intellij.codeInsight.actions.ReformatCodeProcessor(myFixture.getFile(), false).run();
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertAllMetadataMapCommentsAndOrderPreserved(myFixture.getFile().getText());
    }

    private static void assertAllMetadataMapCommentsAndOrderPreserved(String actual) {
        String[] requiredComments = {
            "// Exact decimal mode",
            "// Tier 1.1 exact",
            "// Max significant digits",
            "// Tier 1.3 capacity",
            "// Inclusive lower bound",
            "// Tier 4 lower",
            "// Exclusive upper bound",
            "// Tier 4 upper",
            "// Footer note inside MetadataMap"
        };
        for (String comment : requiredComments) {
            assertTrue("Missing MetadataMap comment: " + comment + "\nActual:\n" + actual, actual.contains(comment));
        }
        int exactIdx = actual.indexOf("#exact");
        int maxSizeIdx = actual.indexOf("#maxSize 9");
        int minInclIdx = actual.indexOf("#minIncl -1000.00000");
        int maxExclIdx = actual.indexOf("#maxExcl 1000.00000");
        int footerIdx = actual.indexOf("// Footer note inside MetadataMap");
        assertTrue(
            "Expected #exact -> #maxSize -> #minIncl -> #maxExcl -> footer order:\n" + actual,
            exactIdx >= 0 && maxSizeIdx > exactIdx && minInclIdx > maxSizeIdx && maxExclIdx > minInclIdx && footerIdx > maxExclIdx
        );
        assertFalse("Closing '}' of MetadataMap must never collapse onto footer '//' comment line:\n" + actual,
            actual.contains("// Footer note inside MetadataMap }") || actual.contains("// Footer note inside MetadataMap}"));
    }
}
