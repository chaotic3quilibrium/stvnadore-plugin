package org.stvnadore.plugin.formatting;

import com.intellij.lang.Language;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

/**
 * Language code style settings provider configuring the canonical 2-space indentation
 * standard, tabular column alignment preview, and Zero-Tab Invariant for STVN 2.0.0 documents.
 *
 * @since 2.0.0
 */
@NullMarked
public final class StvnLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

    private static final String SAMPLE_INDENT = """
        {
          :meta {
            #name   "indent_showcase"
            #domain "layout"
            #kind   #BODY
          }
          :defs {
            :package :cooling/manifold {
              :CoolantValveMode  :Enum[#CLOSED #THROTTLED #OPEN]
              :FlowTargetLpm     { #unsigned #size 16 #maxExcl 5000 } :Int
              #DEFAULT_VALVE     :CoolantValveMode  #THROTTLED
            }
            :use [
              :cooling/manifold {
                :CoolantValveMode  :ValveMode
                :FlowTargetLpm     :LoopFlowLpm
                #DEFAULT_VALVE     #INIT_VALVE
              }
            ]
            :ManifoldSnapshot :Tuple(
              :ValveMode
              :Map(:String :LoopFlowLpm)
              :String
            )
          }
          :type :ManifoldSnapshot
          :body (
            #INIT_VALVE
            {
              ["zone_a"          120]
              ["zone_north_rack" 480]
            }
            \"\"\"[JSON]
        {
          "service": "active",
          "port": 8443
        }
        [JSON]\"\"\"
          )
        }
        """;

    private static final String SAMPLE_SPACES = """
        {
          :meta {
            #name   "spaces_showcase"
            #domain "layout"
            #kind   #BODY
          }
          :defs {
            :ListenPort  { #unsigned #size 16 #minIncl 1024 } :Int
            :LinkState   :Enum[#ACTIVE #INACTIVE]
            :Endpoint    :Tuple(:String :ListenPort :LinkState)
          }
          :type :Seq(:Endpoint)
          :body [
            ("gateway.internal"  8080  #ACTIVE)
            ("metrics.internal"  9090  #INACTIVE)
          ]
        }
        """;

    private static final String SAMPLE_BLANK_LINES = """
        {
          :meta {
            #name   "blank_lines_showcase"
            #domain "layout"
            #kind   #BODY
          }

          :defs {
            :ListenPort { #unsigned #size 16 } :Int

            :Status :Enum[#ACTIVE #SUSPENDED]

            :Payload :Tuple(:ListenPort :Status :String)
          }

          :type :Payload

          :body (
            8443
            #ACTIVE
            \"\"\"[TEXT]
        Line 1
        Line 2
        [TEXT]\"\"\"
          )
        }
        """;

    /**
     * Default constructor for the STVN language code style settings provider.
     */
    public StvnLanguageCodeStyleSettingsProvider() {
        super();
    }

    @Override
    public @NotNull Language getLanguage() {
        return StvnLanguage.INSTANCE;
    }

    @Override
    protected void customizeDefaults(
        @NotNull CommonCodeStyleSettings commonSettings,
        @NotNull CommonCodeStyleSettings.IndentOptions indentOptions
    ) {
        commonSettings.KEEP_BLANK_LINES_IN_CODE = 1;
        commonSettings.KEEP_BLANK_LINES_IN_DECLARATIONS = 1;
        indentOptions.INDENT_SIZE = 2;
        indentOptions.TAB_SIZE = 2;
        indentOptions.CONTINUATION_INDENT_SIZE = 4;
        indentOptions.USE_TAB_CHARACTER = false;
    }

    @Override
    public @Nullable String getCodeSample(@NotNull SettingsType settingsType) {
        return switch (settingsType) {
            case SPACING_SETTINGS -> SAMPLE_SPACES;
            case BLANK_LINES_SETTINGS -> SAMPLE_BLANK_LINES;
            case INDENT_SETTINGS -> SAMPLE_INDENT;
            default -> SAMPLE_INDENT;
        };
    }
}
