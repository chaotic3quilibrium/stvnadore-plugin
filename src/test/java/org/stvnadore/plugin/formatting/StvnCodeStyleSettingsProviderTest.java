package org.stvnadore.plugin.formatting;

import com.intellij.application.options.CodeStyleSchemesConfigurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.plugin.StvnLanguage;

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
}
