package org.stvnadore.plugin.validation;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.printer.NestedContainerWrapPolicy;
import org.stvnadore.core.printer.StvnSourceFormatterOptions;
import org.stvnadore.plugin.settings.StvnConfigurable;
import org.stvnadore.plugin.settings.StvnSettings;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * IntelliJ Platform regression suite verifying {@code :use} {@code UseMapAlias} {@code #CONST}
 * validation and {@code Pass A} / {@code Pass B} {@code count == 1} diagnostic deconfliction,
 * plus {@link StvnSettings} and {@link StvnConfigurable} wrap-policy persistence and UI lifecycle.
 */
@NullMarked
public final class StvnUseMapAliasConstantValidationTest extends BasePlatformTestCase {

    private void enableAll26StvnInspections() {
        List<LocalInspectionTool> tools = new ArrayList<>();
        for (LocalInspectionEP ep : LocalInspectionEP.LOCAL_INSPECTION.getExtensionList()) {
            if (ep.implementationClass != null && ep.implementationClass.startsWith("org.stvnadore.plugin.")) {
                tools.add((LocalInspectionTool) ep.instantiateTool());
            }
        }
        assertEquals("Expected all 26 registered STVN local inspections", 26, tools.size());
        myFixture.enableInspections(tools.toArray(LocalInspectionTool[]::new));
    }

    public void testProbe2ValidUseMapAliasConstantProducesZeroErrorsInPassAAndPassB() throws IOException {
        Path probe2Path = Paths.get(
            "src", "test", "resources", "shared-fixtures", "formatting",
            "source_pretty", "fmt_probe_02_constants_meta_use_and_maps.core_test.stvn"
        );
        String probe2Source = Files.readString(probe2Path, StandardCharsets.UTF_8);

        // Pass A (0 inspections enabled)
        myFixture.configureByText("fmt_probe_02_constants_meta_use_and_maps.core_test.stvn", probe2Source);
        var passAErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertTrue("Probe 2 must produce 0 errors in Pass A, found: " + passAErrors, passAErrors.isEmpty());

        // Pass B (all 26 inspections enabled)
        enableAll26StvnInspections();
        myFixture.configureByText("fmt_probe_02_constants_meta_use_and_maps.core_test.stvn", probe2Source);
        var passBErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertTrue("Probe 2 (#INIT_VALVE_MODE) must produce 0 errors in Pass B, found: " + passBErrors, passBErrors.isEmpty());
    }

    public void testValidUseMapAliasBooleanConstantProducesZeroErrorsInBodyAndMetadata() {
        String source = """
            {
              :meta {
                #name "bool_alias_ok"
                #domain "core_test"
                #kind #BODY
              }
              :defs {
                :package :cooling/flags {
                  #DEFAULT_ENABLED :Boolean #TRUE
                }
                :use [
                  :cooling/flags {
                    #DEFAULT_ENABLED #INIT_ENABLED
                  }
                ]
                :CustomPair { #equatable #INIT_ENABLED } :Tuple(:Int :Float)
              }
              :type :Tuple(:Boolean :CustomPair)
              :body (#INIT_ENABLED (1 2.5))
            }
            """;

        enableAll26StvnInspections();
        myFixture.configureByText("bool_alias_ok.core_test.stvn", source);
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertTrue("Valid UseMapAlias Boolean constant must produce 0 errors, found: " + errors, errors.isEmpty());
    }

    public void testGenuineInvalidUseMapAliasConstantsEmitExactCountOneAcrossPassAAndPassB() {
        // 1a. E012: Anonymous :Enum constant aliased via UseMapAlias whose value (#THROTTLED) violates :EnumSubset
        String anonymousEnumSubsetViolation = """
            {
              :meta {
                #name "anon_subset_alias_err"
                #domain "core_test"
                #kind #BODY
              }
              :defs {
                :package :cooling/manifold {
                  :CoolantValveMode :Enum[#CLOSED #THROTTLED #OPEN]
                  #RAW_THROTTLED :Enum[#CLOSED #THROTTLED #OPEN] #THROTTLED
                }
                :use [
                  :cooling/manifold {
                    :CoolantValveMode :ValveMode
                    #RAW_THROTTLED #INIT_RAW_THROTTLED
                  }
                ]
                :ActiveValveMode { #filterIncl [#CLOSED #OPEN] } :ValveMode
              }
              :type :ActiveValveMode
              :body #INIT_RAW_THROTTLED
            }
            """;

        // 1b. E028: Wider nominal enum constant (:CoolantValveMode) assigned to narrowed inline subset
        String narrowedSubsetMismatchSource = """
            {
              :meta {
                #name "subset_alias_err"
                #domain "core_test"
                #kind #BODY
              }
              :defs {
                :package :cooling/manifold {
                  :CoolantValveMode :Enum[#CLOSED #THROTTLED #OPEN]
                  #DEFAULT_VALVE_MODE :CoolantValveMode #THROTTLED
                }
                :use [
                  :cooling/manifold {
                    :CoolantValveMode :ValveMode
                    #DEFAULT_VALVE_MODE #INIT_VALVE_MODE
                  }
                ]
              }
              :type { #filterIncl [#CLOSED #OPEN] } :ValveMode
              :body #INIT_VALVE_MODE
            }
            """;

        // 1c. E027: UseMapAlias #CONST with parent brand (:CoolantValveMode) in distinct child brand (:ActiveValveMode)
        String brandMismatchSource = """
            {
              :meta {
                #name "brand_alias_err"
                #domain "core_test"
                #kind #BODY
              }
              :defs {
                :package :cooling/manifold {
                  :CoolantValveMode :Enum[#CLOSED #THROTTLED #OPEN]
                  #DEFAULT_VALVE_MODE :CoolantValveMode #CLOSED
                }
                :use [
                  :cooling/manifold {
                    :CoolantValveMode :ValveMode
                    #DEFAULT_VALVE_MODE #INIT_VALVE_MODE
                  }
                ]
                :ActiveValveMode { #filterIncl [#CLOSED #OPEN] } :ValveMode
              }
              :type :ActiveValveMode
              :body #INIT_VALVE_MODE
            }
            """;

        myFixture.configureByText("anon_subset_alias_err.core_test.stvn", anonymousEnumSubsetViolation);
        var passAAnonErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass A must emit strictly 1 error on excluded anonymous UseMapAlias #CONST", 1, passAAnonErrors.size());

        myFixture.configureByText("subset_alias_err.core_test.stvn", narrowedSubsetMismatchSource);
        var passASubsetErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass A must emit strictly 1 error on wider UseMapAlias #CONST", 1, passASubsetErrors.size());

        myFixture.configureByText("brand_alias_err.core_test.stvn", brandMismatchSource);
        var passABrandErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass A must emit strictly 1 error on nominal brand mismatch", 1, passABrandErrors.size());

        // 2. E028: UseMapAlias #CONST with incompatible nominal/enum type in enum slot and Boolean slot
        String typeMismatchSource = """
            {
              :meta {
                #name "mismatch_alias_err"
                #domain "core_test"
                #kind #BODY
              }
              :defs {
                :package :cooling/manifold {
                  :PumpMode :Enum[#STANDBY #BOOST]
                  #DEFAULT_PUMP :PumpMode #BOOST
                }
                :use [
                  :cooling/manifold {
                    #DEFAULT_PUMP #INIT_PUMP
                  }
                ]
                :ValveMode :Enum[#CLOSED #OPEN]
              }
              :type :Tuple(:ValveMode :Boolean)
              :body (#INIT_PUMP #INIT_PUMP)
            }
            """;

        myFixture.configureByText("mismatch_alias_err.core_test.stvn", typeMismatchSource);
        var passAMismatchErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass A must emit strictly 2 E028 errors (1 per slot)", 2, passAMismatchErrors.size());

        // Enable all 26 inspections (Pass B) and verify 1:1 count == 1 and exact diagnostic parity with Pass A
        enableAll26StvnInspections();

        myFixture.configureByText("anon_subset_alias_err.core_test.stvn", anonymousEnumSubsetViolation);
        var passBAnonErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass B must emit strictly 1 error on excluded anonymous UseMapAlias #CONST", 1, passBAnonErrors.size());
        assertEquals(passAAnonErrors.get(0).getDescription(), passBAnonErrors.get(0).getDescription());

        myFixture.configureByText("subset_alias_err.core_test.stvn", narrowedSubsetMismatchSource);
        var passBSubsetErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass B must emit strictly 1 error on wider UseMapAlias #CONST", 1, passBSubsetErrors.size());
        assertEquals(passASubsetErrors.get(0).getDescription(), passBSubsetErrors.get(0).getDescription());

        myFixture.configureByText("brand_alias_err.core_test.stvn", brandMismatchSource);
        var passBBrandErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass B must emit strictly 1 error on nominal brand mismatch", 1, passBBrandErrors.size());
        assertEquals(passABrandErrors.get(0).getDescription(), passBBrandErrors.get(0).getDescription());

        myFixture.configureByText("mismatch_alias_err.core_test.stvn", typeMismatchSource);
        var passBMismatchErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Pass B must emit strictly 2 E028 errors (1 per slot)", 2, passBMismatchErrors.size());

        // 3. Direct :defs ConstantDefinition RHS invalid enum literal continues to emit count == 1
        String badDefsConstSource = """
            {
              :meta {
                #name "bad_defs_const"
                #domain "core_test"
                #kind #DEFS
              }
              :defs {
                :ValveMode :Enum[#CLOSED #THROTTLED #OPEN]
                :ActiveValveMode { #filterIncl [#CLOSED #OPEN] } :ValveMode
                #BAD_VALVE :ActiveValveMode #THROTTLED
              }
            }
            """;
        myFixture.configureByText("bad_defs_const.core_test.stvn_d", badDefsConstSource);
        var badDefsErrors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEquals("Invalid :defs ConstantDefinition RHS variant must emit strictly 1 error", 1, badDefsErrors.size());
    }

    public void testStvnSettingsAndConfigurableWrapPolicyPersistenceAndUiLifecycle() {
        var settings = StvnSettings.getInstance(getProject());
        var prevSchema = settings.getState().schemaNestedWrapPolicy;
        var prevPayload = settings.getState().payloadNestedWrapPolicy;

        try {
            // Verify null-safe loadState fallback
            StvnSettings.State deserializedWithNulls = new StvnSettings.State();
            deserializedWithNulls.schemaNestedWrapPolicy = null;
            deserializedWithNulls.payloadNestedWrapPolicy = null;
            settings.loadState(deserializedWithNulls);
            assertEquals(NestedContainerWrapPolicy.PRESERVE_AUTHORED, settings.getState().schemaNestedWrapPolicy);
            assertEquals(NestedContainerWrapPolicy.PRESERVE_AUTHORED, settings.getState().payloadNestedWrapPolicy);
            assertEquals(StvnSourceFormatterOptions.DEFAULT, settings.toCoreFormatterOptions());

            // Verify StvnConfigurable UI labels and lifecycle
            StvnConfigurable configurable = new StvnConfigurable(getProject());
            JComponent root = configurable.createComponent();
            assertNotNull(root);
            assertFalse("Freshly reset configurable must not be modified", configurable.isModified());

            List<String> labels = new ArrayList<>();
            List<JComboBox<?>> wrapCombos = new ArrayList<>();
            collectLabelsAndWrapCombos(root, labels, wrapCombos);
            assertTrue(labels.contains("Schema nested container wrapping (:defs & :type):"));
            assertTrue(labels.contains("Payload nested container wrapping (:body & #CONST):"));
            assertEquals("Expected 2 NestedContainerWrapPolicy dropdowns", 2, wrapCombos.size());

            wrapCombos.get(0).setSelectedItem(NestedContainerWrapPolicy.EXPAND_NESTED);
            assertTrue("Changing schema wrap combo must mark configurable modified", configurable.isModified());
            configurable.apply();
            assertEquals(
                new StvnSourceFormatterOptions(
                    NestedContainerWrapPolicy.EXPAND_NESTED,
                    NestedContainerWrapPolicy.PRESERVE_AUTHORED
                ),
                settings.toCoreFormatterOptions()
            );
            assertFalse("After apply(), configurable must report unmodified", configurable.isModified());

            wrapCombos.get(1).setSelectedItem(NestedContainerWrapPolicy.EXPAND_NESTED);
            assertTrue(configurable.isModified());
            configurable.reset();
            assertFalse("After reset(), configurable must revert unsaved UI edits", configurable.isModified());
            assertEquals(NestedContainerWrapPolicy.PRESERVE_AUTHORED, wrapCombos.get(1).getSelectedItem());

            configurable.disposeUIResources();
            assertFalse("Disposed configurable must safely return false from isModified()", configurable.isModified());
        } finally {
            settings.getState().schemaNestedWrapPolicy = prevSchema;
            settings.getState().payloadNestedWrapPolicy = prevPayload;
        }
    }

    private static void collectLabelsAndWrapCombos(
            Component comp,
            List<String> labels,
            List<JComboBox<?>> wrapCombos
    ) {
        if (comp instanceof JLabel label && label.getText() != null) {
            labels.add(label.getText());
        } else if (comp instanceof JComboBox<?> combo
                && combo.getItemCount() > 0
                && combo.getItemAt(0) instanceof NestedContainerWrapPolicy) {
            wrapCombos.add(combo);
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabelsAndWrapCombos(child, labels, wrapCombos);
            }
        }
    }
}
