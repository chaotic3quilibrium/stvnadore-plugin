package org.stvnadore.plugin.validation;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.core.ir.StvnDefsCatalog;
import org.stvnadore.core.ir.StvnDocument;
import org.stvnadore.core.ir.StvnValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless {@code :unitTest} suite verifying {@link StvnSumTagRedundancyEngine} across all 19
 * elements of {@code sum_type_torture.stvn}, nested {@code :Union}/{@code :Either}/{@code :Option}
 * tag-hijacking traps, Golden Quartet #04 and #18 control-keyword collisions, packaged nominal
 * schemas with {@code :use}-renamed constants, recursive nominal schemas, and {@code < 20 ms}
 * batch latency.
 */
@NullMarked
public final class StvnSumTagRedundancyEngineUnitTest {

    @Test
    void testSumTypeTortureAll19ElementsSoundnessAndLatency() {
        String source = """
            {
              :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))
              :body [
                #None
                #Some #Left "Explicit Outer Left"
                #Some #Right #None
                #Some #Right #Some #Left "Explicit Inner Left"
                #Some #Right #Some #Right 3.14159
                #None
                #Some #None
                #Right #None
                #Some #Right #Some #Right 1.234
                #Some #Right #Some 1.234
                #Some #Right #Right 1.234
                #Right #Some #Right 1.234
                #Some #Some 1.234
                #Right #Right 1.234
                #Some #Right 1.234
                #Right #Some 1.234
                #Some 1.234
                #Right 1.234
                1.234
              ]
            }
            """;
        StvnDocument doc = StvnCompiler.compile(source).document().orElseThrow();
        StvnValue.StvnSeq seq = (StvnValue.StvnSeq) doc.payload().orElseThrow();
        List<String> exprs = List.of(
            "#None",
            "#Some #Left \"Explicit Outer Left\"",
            "#Some #Right #None",
            "#Some #Right #Some #Left \"Explicit Inner Left\"",
            "#Some #Right #Some #Right 3.14159",
            "#None",
            "#Some #None",
            "#Right #None",
            "#Some #Right #Some #Right 1.234",
            "#Some #Right #Some 1.234",
            "#Some #Right #Right 1.234",
            "#Right #Some #Right 1.234",
            "#Some #Some 1.234",
            "#Right #Right 1.234",
            "#Some #Right 1.234",
            "#Right #Some 1.234",
            "#Some 1.234",
            "#Right 1.234",
            "1.234"
        );

        List<StvnSumTagRedundancyEngine.SlotProbe> probes = new ArrayList<>(exprs.size());
        for (int i = 0; i < exprs.size(); i++) {
            StvnValue vOrig = seq.elements().get(i);
            var spans = StvnSumTagRedundancyEngine.parseSimpleSlotTagChain(exprs.get(i), vOrig, doc.defs());
            probes.add(new StvnSumTagRedundancyEngine.SlotProbe(exprs.get(i), spans, vOrig, Map.of()));
        }

        // Warmup once
        StvnSumTagRedundancyEngine.selectComposableRemovableTagsBatched(probes, doc.defs());

        long startNanos = System.nanoTime();
        List<Set<Integer>> selected =
            StvnSumTagRedundancyEngine.selectComposableRemovableTagsBatched(probes, doc.defs());
        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;

        assertEquals(Set.of(), selected.get(0));
        assertEquals(Set.of(0), selected.get(1));
        assertEquals(Set.of(1), selected.get(2), "Line 10 (#Some #Right #None) must flag only #Right");
        assertEquals(Set.of(0, 2), selected.get(3), "Line 11 must flag only 1st and 2nd #Some");
        assertEquals(Set.of(0, 1, 2, 3), selected.get(4));
        assertEquals(Set.of(), selected.get(5));
        assertEquals(Set.of(), selected.get(6), "Line 20 (#Some #None) is irreducible");
        assertEquals(Set.of(), selected.get(7), "Line 21 (#Right #None) is irreducible");
        assertEquals(Set.of(0, 1, 2, 3), selected.get(8));
        assertEquals(Set.of(0, 1, 2), selected.get(9));
        assertEquals(Set.of(0, 1, 2), selected.get(10));
        assertEquals(Set.of(0, 1, 2), selected.get(11));
        assertEquals(Set.of(0, 1), selected.get(12));
        assertEquals(Set.of(0, 1), selected.get(13));
        assertEquals(Set.of(0, 1), selected.get(14));
        assertEquals(Set.of(0, 1), selected.get(15));
        assertEquals(Set.of(0), selected.get(16));
        assertEquals(Set.of(0), selected.get(17));
        assertEquals(Set.of(), selected.get(18));
        assertTrue(elapsedMs < 20.0, "Expected < 20 ms on sum_type_torture.stvn, got " + elapsedMs + " ms");

        // Verify post-removal batch output compiles cleanly to identical StvnSeq
        StringBuilder postBody = new StringBuilder("{\n  :type :Seq(:Option(:Either(:String :Option(:Either(:String :Float)))))\n  :body [\n");
        for (int i = 0; i < exprs.size(); i++) {
            String reduced = StvnSumTagRedundancyEngine.applyTagRemovals(
                exprs.get(i),
                probes.get(i).tags(),
                selected.get(i)
            );
            postBody.append("    ").append(reduced).append('\n');
        }
        postBody.append("  ]\n}");
        var recomp = StvnCompiler.compile(postBody.toString());
        assertFalse(recomp.hasErrors(), "Post-removal document must compile with 0 errors");
        assertEquals(seq, recomp.document().orElseThrow().payload().orElseThrow());
    }

    @Test
    void testNestedUnionEitherOptionHijackTrapsAndControlKeywordGuards() {
        // #2 #1 1.5 on :Union(:Union(:Int :String) :Union(:Float :Boolean)) must flag only index 1 (#1)
        assertSingleSlotSelection(
            ":Union(:Union(:Int :String) :Union(:Float :Boolean))",
            "#2 #1 1.5",
            Set.of(1)
        );

        // Defect U1: #Some #2 #None on :Option(:Union(:String :Option(:Int))) -> flag #2 only (#Some #None)
        assertSingleSlotSelection(
            ":Option(:Union(:String :Option(:Int)))",
            "#Some #2 #None",
            Set.of(1)
        );

        // Defect U2: #Right #Left "x" on :Either(:String :Either(:String :Float)) -> irreducible
        assertSingleSlotSelection(
            ":Either(:String :Either(:String :Float))",
            "#Right #Left \"x\"",
            Set.of()
        );

        // Defect U3: #2 #1 "inner" on :Union(:String :Union(:String :String)) and :Union(:Int :Union(:String :String)) -> irreducible
        assertSingleSlotSelection(
            ":Union(:String :Union(:String :String))",
            "#2 #1 \"inner\"",
            Set.of()
        );
        assertSingleSlotSelection(
            ":Union(:Int :Union(:String :String))",
            "#2 #1 \"inner\"",
            Set.of()
        );

        // Defect U4: Unwrapped sum leaf overlap
        assertSingleSlotSelection(
            ":Option(:Either(:Float :Union(:Boolean :Float)))",
            "#Some #Right #2 1.5",
            Set.of(0, 2)
        );
        assertSingleSlotSelection(
            ":Either(:Float :Option(:Float))",
            "#Right #Some 1.5",
            Set.of(1)
        );

        // Golden Quartet #04: #Right "#Left" on :Either(:Int :String) must retain #Right
        assertSingleSlotSelection(
            ":Either(:Int :String)",
            "#Right \"#Left\"",
            Set.of()
        );

        // Golden Quartet #18 CaseR: Control enum collision retains immediate wrapper (#Right and #2)
        String enumSrc = """
            {
              :defs {
                :ControlEnum :Enum [ #None #Left #1 #OK ]
              }
              :type :Option(:Union(:String :ControlEnum))
              :body #Some #2 #1
            }
            """;
        StvnDocument enumDoc = StvnCompiler.compile(enumSrc).document().orElseThrow();
        StvnValue enumVal = enumDoc.payload().orElseThrow();
        var enumSpans = StvnSumTagRedundancyEngine.parseSimpleSlotTagChain("#Some #2 #1", enumVal, enumDoc.defs());
        var enumRes = StvnSumTagRedundancyEngine.selectComposableRemovableTagsBatched(
            List.of(new StvnSumTagRedundancyEngine.SlotProbe("#Some #2 #1", enumSpans, enumVal, Map.of())),
            enumDoc.defs()
        );
        assertEquals(Set.of(0), enumRes.getFirst());
    }

    @Test
    void testPackagedNominalSchemaAndRenamedConstantBindingSoundness() {
        String pkgSrc = """
            {
              :defs {
                :package :math {
                  :NonNegFloat { #minIncl 0.0 } :Float
                  #PI :NonNegFloat 3.14159
                }
                :use [ :math { :NonNegFloat :LocalFloat #PI #LOCAL_PI } ]
              }
              :type :Option(:Either(:String :LocalFloat))
              :body #Some #Right #LOCAL_PI
            }
            """;
        StvnDocument pkgDoc = StvnCompiler.compile(pkgSrc).document().orElseThrow();
        StvnValue pkgVal = pkgDoc.payload().orElseThrow();
        StvnDefsCatalog.StvnConstant piConst = pkgDoc.defs().constants().values().iterator().next();
        var spans = StvnSumTagRedundancyEngine.parseSimpleSlotTagChain("#Some #Right #LOCAL_PI", pkgVal, pkgDoc.defs());
        var res = StvnSumTagRedundancyEngine.selectComposableRemovableTagsBatched(
            List.of(new StvnSumTagRedundancyEngine.SlotProbe(
                "#Some #Right #LOCAL_PI",
                spans,
                pkgVal,
                Map.of("#LOCAL_PI", piConst)
            )),
            pkgDoc.defs()
        );
        assertEquals(Set.of(0, 1), res.getFirst());
    }

    @Test
    void testRecursiveNominalSchemaAndDocumentEquivalenceGuard() {
        String recSrc = """
            {
              :defs {
                :FormulaAst :Union(:Float :Tuple(:String :FormulaAst :FormulaAst))
              }
              :type :Either(:FormulaAst :Option(:Float))
              :body #Right #Some 2.5
            }
            """;
        StvnDocument recDoc = StvnCompiler.compile(recSrc).document().orElseThrow();
        StvnValue recVal = recDoc.payload().orElseThrow();
        assertFalse(StvnSumTagRedundancyEngine.containsRealPoisonedSchema(recVal.schema()));
        var spans = StvnSumTagRedundancyEngine.parseSimpleSlotTagChain("#Right #Some 2.5", recVal, recDoc.defs());
        var res = StvnSumTagRedundancyEngine.selectComposableRemovableTagsBatched(
            List.of(new StvnSumTagRedundancyEngine.SlotProbe("#Right #Some 2.5", spans, recVal, Map.of())),
            recDoc.defs()
        );
        assertEquals(Set.of(1), res.getFirst());

        StvnDocument eqDoc = StvnCompiler.compile(recSrc.replace("#Right #Some 2.5", "#Right 2.5")).document().orElseThrow();
        StvnDocument mutatedDoc = StvnCompiler.compile(recSrc.replace("#Right #Some 2.5", "#Left 2.5")).document().orElseThrow();
        assertTrue(StvnSumTagRedundancyEngine.isDocumentsPayloadAndConstantsEquivalent(recDoc, eqDoc));
        assertFalse(StvnSumTagRedundancyEngine.isDocumentsPayloadAndConstantsEquivalent(recDoc, mutatedDoc));
        assertFalse(StvnSumTagRedundancyEngine.isDocumentsPayloadAndConstantsEquivalent(recDoc, null));
    }

    private static void assertSingleSlotSelection(String schemaText, String slotExpr, Set<Integer> expectedIndices) {
        String src = "{ :type " + schemaText + " :body " + slotExpr + " }";
        StvnDocument doc = StvnCompiler.compile(src).document().orElseThrow();
        StvnValue val = doc.payload().orElseThrow();
        var spans = StvnSumTagRedundancyEngine.parseSimpleSlotTagChain(slotExpr, val, doc.defs());
        var res = StvnSumTagRedundancyEngine.selectComposableRemovableTagsBatched(
            List.of(new StvnSumTagRedundancyEngine.SlotProbe(slotExpr, spans, val, Map.of())),
            doc.defs()
        );
        assertEquals(expectedIndices, res.getFirst(), "Unexpected tag selection on " + slotExpr + " under " + schemaText);
    }
}
