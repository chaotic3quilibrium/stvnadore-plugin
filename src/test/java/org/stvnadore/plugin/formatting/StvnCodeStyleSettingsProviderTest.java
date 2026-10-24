package org.stvnadore.plugin.formatting;

import com.intellij.application.options.CodeStyleSchemesConfigurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider.SettingsType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.StvnCompiler;
import org.stvnadore.plugin.StvnColorSettingsPage;
import org.stvnadore.plugin.StvnLanguage;

import java.util.regex.Pattern;

/**
 * Unit and platform integration tests verifying the STVN code style settings provider contract,
 * language association, and configurable ID invariant.
 */
@NullMarked
public final class StvnCodeStyleSettingsProviderTest extends BasePlatformTestCase {

    /**
     * Verifies that the registered provider implements the canonical language and configurable ID contract.
     */
    public void testProviderLanguageAndConfigurableIdInvariant() {
        var provider = CodeStyleSettingsProvider.EXTENSION_POINT_NAME.findExtension(StvnCodeStyleSettingsProvider.class);
        assertNotNull("StvnCodeStyleSettingsProvider must be registered in the platform extension point", provider);
        assertEquals("Provider language must return StvnLanguage.INSTANCE",
                StvnLanguage.INSTANCE, provider.getLanguage());
        assertEquals("Configurable ID must match canonical pattern 'preferences.sourceCode.' + language ID",
                "preferences.sourceCode." + StvnLanguage.INSTANCE.getID(), provider.getConfigurableId());
        assertEquals("preferences.sourceCode.STVN", provider.getConfigurableId());
        assertEquals("STVN", provider.getConfigurableDisplayName());
    }

    /**
     * Verifies that resolving the configurable via CodeStyleSchemesConfigurable produces zero exceptions.
     */
    public void testResolveConfigurableViaCodeStyleSchemesConfigurableProducesZeroExceptions() {
        var schemesConfigurable = new CodeStyleSchemesConfigurable(getProject());
        try {
            SearchableConfigurable subConfigurable = schemesConfigurable.findSubConfigurable("preferences.sourceCode.STVN");
            if (subConfigurable == null) {
                for (var config : schemesConfigurable.getConfigurables()) {
                    if (config instanceof SearchableConfigurable sc && "preferences.sourceCode.STVN".equals(sc.getId())) {
                        subConfigurable = sc;
                        break;
                    }
                }
            }
            if (subConfigurable == null) {
                subConfigurable = schemesConfigurable.findSubConfigurable("STVN");
            }
            assertNotNull("Configurable for 'preferences.sourceCode.STVN' must be resolvable without exceptions",
                    subConfigurable);
            assertEquals("Resolved configurable ID must match expected ID",
                    "preferences.sourceCode.STVN", subConfigurable.getId());
            assertEquals("Configurable display name must match 'STVN'",
                    "STVN", subConfigurable.getDisplayName());
        } finally {
            schemesConfigurable.disposeUIResources();
        }
    }

    /**
     * Verifies that direct instantiation maintains identical contract behavior.
     */
    public void testDirectInstantiationContract() {
        var provider = new StvnCodeStyleSettingsProvider();
        assertEquals(StvnLanguage.INSTANCE, provider.getLanguage());
        assertEquals("preferences.sourceCode.STVN", provider.getConfigurableId());
        assertEquals("STVN", provider.getConfigurableDisplayName());
    }

    /**
     * Verifies that all code samples returned by the provider enforce fenced string symmetry
     * and compile with zero errors under StvnCompiler.
     */
    public void testCodeSamplesEnforceFencedStringSymmetryAndCompileCleanly() {
        var provider = new StvnLanguageCodeStyleSettingsProvider();
        var bareFencePattern = Pattern.compile("(?m)^\\s*\"\"\"\\s*$");
        for (var type : new SettingsType[]{SettingsType.INDENT_SETTINGS, SettingsType.SPACING_SETTINGS, SettingsType.BLANK_LINES_SETTINGS}) {
            String sample = provider.getCodeSample(type);
            assertNotNull("Code sample for " + type + " must not be null", sample);
            assertFalse("Code sample for " + type + " must not contain bare triple quote closing delimiters",
                    bareFencePattern.matcher(sample).find());
            var result = StvnCompiler.compile(sample);
            assertTrue("Code sample for " + type + " must compile cleanly with 0 errors:\n"
                    + result.diagnostics(), result.isSuccess());
        }
    }

    /**
     * Verifies that the demo text in StvnColorSettingsPage compiles cleanly with zero errors
     * once highlight markup tags are stripped.
     */
    public void testColorSettingsPageDemoTextCompilesCleanly() {
        var page = new StvnColorSettingsPage();
        String rawDemo = page.getDemoText();
        assertNotNull("Demo text must not be null", rawDemo);
        assertTrue("Demo text must contain symmetrical closing delimiter for [JSON]", rawDemo.contains("[JSON]\"\"\""));

        // Strip XML-like highlighting markup tags (<string>, </string>, etc.)
        String stripped = rawDemo.replaceAll("<[^>]+>", "");
        var result = StvnCompiler.compile(stripped);
        assertTrue("Stripped demo text must compile cleanly with 0 errors:\n"
                + result.diagnostics(), result.isSuccess());
    }
}
