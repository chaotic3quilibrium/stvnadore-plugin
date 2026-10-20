package org.stvnadore.plugin.actions;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;

import java.util.List;

/**
 * Unit test suite verifying that all STVN plugin actions declare
 * ActionUpdateThread.BGT to prevent EDT threading violations.
 */
@NullMarked
public final class StvnActionThreadingTest extends BasePlatformTestCase {

    private static final List<String> REGISTERED_ACTION_IDS = List.of(
        "org.stvnadore.plugin.actions.StvnFlattenWorkspaceAction",
        "org.stvnadore.plugin.actions.PublishSchemaAction",
        "org.stvnadore.plugin.browser.StvnBrowseNamespaceAction",
        "org.stvnadore.plugin.actions.StvnConvertToPrettyPrintAction",
        "org.stvnadore.plugin.actions.StvnConvertToCompactPrintAction",
        "org.stvnadore.plugin.actions.StvnCopyCanonicalPrettyPrintAction",
        "org.stvnadore.plugin.actions.StvnCopyCanonicalCompactPrintAction"
    );

    private static final List<Class<? extends AnAction>> ACTION_CLASSES = List.of(
        StvnFlattenWorkspaceAction.class,
        PublishSchemaAction.class,
        org.stvnadore.plugin.browser.StvnBrowseNamespaceAction.class,
        StvnConvertToPrettyPrintAction.class,
        StvnConvertToCompactPrintAction.class,
        StvnCopyCanonicalPrettyPrintAction.class,
        StvnCopyCanonicalCompactPrintAction.class
    );

    /**
     * Verifies that each registered action in plugin.xml returns ActionUpdateThread.BGT.
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
     * Verifies that each action class directly instantiates and declares ActionUpdateThread.BGT.
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
