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
 * standard and Zero-Tab Invariant for STVN documents.
 *
 * @since 1.3.0
 */
@NullMarked
public final class StvnLanguageCodeStyleSettingsProvider extends LanguageCodeStyleSettingsProvider {

    private static final String SAMPLE_INDENT = """
        {
          :meta {
            #name "indent_showcase"
            #domain "layout"
            #kind #BODY
          }
          :defs {
            :package :org/example/service {
              :Port { #unsigned #size 16 #minIncl 1 } :Int
              :Status :Enum [ #ACTIVE #SUSPENDED ]
              :ServiceConfig :Tuple(
                :Port
                :Status
                :String
              )
            }
          }
          :type :Tuple(
            :org/example/service/Port
            :org/example/service/Status
            :String
          )
          :body (
            8443
            #ACTIVE
            \"\"\"[JSON]
        {
          "service": "active",
          "port": 8443
        }
        \"\"\"
          )
        }
        """;

    private static final String SAMPLE_SPACES = """
        {
          :meta {
            #name "spaces_showcase"
            #domain "layout"
            #kind #BODY
          }
          :defs {
            :Port { #unsigned #size 16 } :Int
            :State :Enum [ #ACTIVE #INACTIVE ]
            :Endpoint :Tuple(:String :Port)
          }
          :type :Tuple(:Endpoint :State :Option(:String))
          :body (
            ("gateway.internal" 8080)
            #ACTIVE
            #Some "Standard string literal"
          )
        }
        """;

    private static final String SAMPLE_BLANK_LINES = """
        {
          :meta {
            #name "blank_lines_showcase"
            #domain "layout"
            #kind #BODY
          }

          :defs {
            :Port { #unsigned #size 16 } :Int

            :Status :Enum [ #ACTIVE #SUSPENDED ]

            :Payload :Tuple(:Port :Status :String)
          }

          :type :Payload

          :body (
            8443
            #ACTIVE
            \"\"\"[TEXT]
        Line 1
        Line 2
        \"\"\"
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
