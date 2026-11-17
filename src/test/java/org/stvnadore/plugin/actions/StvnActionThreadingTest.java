package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Platform test suite verifying that all STVN plugin popup groups and actions declare
 * {@link ActionUpdateThread#BGT} to prevent EDT threading violations.
 */
@NullMarked
public final class StvnActionThreadingTest extends BasePlatformTestCase {

    private static final List<String> REGISTERED_ACTION_IDS = List.of(
        "org.stvnadore.plugin.actions.PublishSchemaAction",
        "org.stvnadore.plugin.browser.StvnBrowseNamespaceAction",
        "org.stvnadore.plugin.actions.StvnEditorPopupMenu",
        "org.stvnadore.plugin.actions.StvnExtractSchemaMenuGroup",
        "org.stvnadore.plugin.actions.StvnExtractPayloadMenuGroup",
        "org.stvnadore.plugin.actions.StvnCanonicalizeInPlaceMenuGroup",
        "org.stvnadore.plugin.actions.StvnCopyHermeticSchemaPrettyAction",
        "org.stvnadore.plugin.actions.StvnCopyHermeticSchemaCompactCasAction",
        "org.stvnadore.plugin.actions.StvnExportHermeticSchemaPrettyAction",
        "org.stvnadore.plugin.actions.StvnExportHermeticSchemaCompactCasAction",
        "org.stvnadore.plugin.actions.StvnBuildExportSchemaCompactCasAction",
        "org.stvnadore.plugin.actions.StvnCopyHermeticPayloadPrettyAction",
        "org.stvnadore.plugin.actions.StvnCopyHermeticPayloadCompactAction",
        "org.stvnadore.plugin.actions.StvnExportHermeticPayloadPrettyAction",
        "org.stvnadore.plugin.actions.StvnExportHermeticPayloadCompactAction",
        "org.stvnadore.plugin.actions.StvnReplaceBufferPrettyIrAction",
        "org.stvnadore.plugin.actions.StvnReplaceBufferCompactIrAction"
    );

    private static final List<Class<? extends AnAction>> ACTION_CLASSES = List.of(
        PublishSchemaAction.class,
        org.stvnadore.plugin.browser.StvnBrowseNamespaceAction.class,
        StvnEditorPopupMenuGroup.class,
        StvnExtractSchemaMenuGroup.class,
        StvnExtractPayloadMenuGroup.class,
        StvnCanonicalizeInPlaceMenuGroup.class,
        StvnCopyHermeticSchemaPrettyAction.class,
        StvnCopyHermeticSchemaCompactCasAction.class,
        StvnExportHermeticSchemaPrettyAction.class,
        StvnExportHermeticSchemaCompactCasAction.class,
        StvnBuildExportSchemaCompactCasAction.class,
        StvnCopyHermeticPayloadPrettyAction.class,
        StvnCopyHermeticPayloadCompactAction.class,
        StvnExportHermeticPayloadPrettyAction.class,
        StvnExportHermeticPayloadCompactAction.class,
        StvnReplaceBufferPrettyIrAction.class,
        StvnReplaceBufferCompactIrAction.class
    );

    /**
     * Verifies that each registered action and group in {@code plugin.xml} returns {@link ActionUpdateThread#BGT}.
     */
    public void testRegisteredPluginActionsDeclareBgtUpdateThread() {
        var actionManager = ActionManager.getInstance();
        for (var actionId : REGISTERED_ACTION_IDS) {
            var action = actionManager.getAction(actionId);
            assertNotNull("Action must be registered in ActionManager: " + actionId, action);
            assertEquals("Action '" + actionId + "' must declare ActionUpdateThread.BGT",
                ActionUpdateThread.BGT, action.getActionUpdateThread());
        }
    }

    /**
     * Verifies that each action and group class directly instantiates and declares {@link ActionUpdateThread#BGT}.
     *
     * @throws Exception if reflective instantiation fails
     */
    public void testActionClassesDeclareBgtUpdateThread() throws Exception {
        for (var clazz : ACTION_CLASSES) {
            var ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            AnAction action = ctor.newInstance();
            assertEquals("Class '" + clazz.getSimpleName() + "' must declare ActionUpdateThread.BGT",
                ActionUpdateThread.BGT, action.getActionUpdateThread());
        }
    }
}
