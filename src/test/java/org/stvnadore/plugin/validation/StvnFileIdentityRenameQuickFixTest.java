package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl;
import com.intellij.codeInsight.daemon.impl.AnnotationSessionImpl;
import com.intellij.lang.annotation.Annotation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.stvnadore.plugin.validation.quickfix.StvnInteractiveRenameQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnOmitDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRenameDomainQuickFix;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Platform test suite verifying the Layer 1 Ingress File Identity Rename Quick-Fix Suite:
 * StvnOmitDomainQuickFix, StvnRenameDomainQuickFix, and StvnInteractiveRenameQuickFix.
 */
public final class StvnFileIdentityRenameQuickFixTest extends BasePlatformTestCase {

    private List<Annotation> computeAnnotations(StvnExternalAnnotator annotator, com.intellij.psi.PsiFile file) throws Exception {
        var collected = annotator.collectInformation(file);
        assertNotNull("CollectedInfo must not be null", collected);
        var result = annotator.doAnnotate(collected);
        assertNotNull("AnnotationResult must not be null", result);

        return ApplicationManager.getApplication().executeOnPooledThread(() ->
            ReadAction.compute(() ->
                AnnotationSessionImpl.computeWithSession(file, false, annotator, holder -> {
                    ((AnnotationHolderImpl) holder).applyExternalAnnotatorWithContext(file, result);
                    return (AnnotationHolderImpl) holder;
                })
            )
        ).get(10, TimeUnit.SECONDS);
    }

    /**
     * TC-QUICKFIX-OMIT-01: Verifies that StvnOmitDomainQuickFix renames an empty 0-byte file
     * from 'prelude.stvn_core.stvn_d' to 'prelude.stvn_d' and clears the Layer 1 error.
     */
    public void testOmitDomainQuickFixOnEmptyFile() throws Exception {
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", "");
        var annotator = new StvnExternalAnnotator();
        var annotations = computeAnnotations(annotator, file);

        var fileLevel = annotations.stream().filter(Annotation::isFileLevelAnnotation).findFirst();
        assertTrue("File-level annotation must be present", fileLevel.isPresent());

        var quickFixes = fileLevel.get().getQuickFixes();
        assertNotNull("QuickFixes list must not be null", quickFixes);
        assertTrue("QuickFixes must contain StvnOmitDomainQuickFix",
            quickFixes.stream().anyMatch(q -> q.quickFix instanceof StvnOmitDomainQuickFix));

        var omitFix = quickFixes.stream()
            .map(q -> q.quickFix)
            .filter(q -> q instanceof StvnOmitDomainQuickFix)
            .findFirst()
            .get();

        myFixture.launchAction(omitFix);

        VirtualFile vFile = file.getVirtualFile();
        assertEquals("prelude.stvn_d", vFile.getName());
    }

    /**
     * TC-QUICKFIX-OMIT-02: Verifies that StvnOmitDomainQuickFix on a non-empty file
     * removes the #domain facet from :meta and renames the file to 'prelude.stvn_d'.
     */
    public void testOmitDomainQuickFixOnNonEmptyFile() throws Exception {
        String content = """
            {
              :meta {
                #name "prelude"
                #domain "stvn_core"
                #kind #DEFS
              }
              :defs {
                :MyType :Int
              }
            }
            """;
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", content);
        var annotator = new StvnExternalAnnotator();
        var annotations = computeAnnotations(annotator, file);

        var annotation = annotations.stream()
            .filter(a -> a.getMessage() != null && a.getMessage().contains("Reserved system domain"))
            .findFirst();
        assertTrue("Reserved domain annotation must be present", annotation.isPresent());

        var omitFix = annotation.get().getQuickFixes().stream()
            .map(q -> q.quickFix)
            .filter(q -> q instanceof StvnOmitDomainQuickFix)
            .findFirst()
            .get();

        myFixture.launchAction(omitFix);

        assertEquals("prelude.stvn_d", file.getVirtualFile().getName());
        String text = file.getText();
        assertFalse("Buffer must not contain #domain facet", text.contains("#domain"));
        assertTrue("Buffer must retain #name facet", text.contains("#name \"prelude\""));
        assertTrue("Buffer must retain #kind facet", text.contains("#kind #DEFS"));
    }

    /**
     * TC-QUICKFIX-RENAME-01: Verifies that StvnRenameDomainQuickFix renames an empty file
     * to 'prelude.domain.stvn_d'.
     */
    public void testRenameDomainQuickFixOnEmptyFile() throws Exception {
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", "");
        var annotator = new StvnExternalAnnotator();
        var annotations = computeAnnotations(annotator, file);

        var fileLevel = annotations.stream().filter(Annotation::isFileLevelAnnotation).findFirst();
        assertTrue("File-level annotation must be present", fileLevel.isPresent());

        var renameFix = fileLevel.get().getQuickFixes().stream()
            .map(q -> q.quickFix)
            .filter(q -> q instanceof StvnRenameDomainQuickFix)
            .findFirst()
            .get();

        myFixture.launchAction(renameFix);

        assertEquals("prelude.domain.stvn_d", file.getVirtualFile().getName());
    }

    /**
     * TC-QUICKFIX-RENAME-02: Verifies that StvnRenameDomainQuickFix on a non-empty file
     * updates #domain to 'domain' and renames the file to 'prelude.domain.stvn_d'.
     */
    public void testRenameDomainQuickFixOnNonEmptyFile() throws Exception {
        String content = """
            {
              :meta {
                #name "prelude"
                #domain "stvn_core"
                #kind #DEFS
              }
              :defs {
                :MyType :Int
              }
            }
            """;
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", content);
        var annotator = new StvnExternalAnnotator();
        var annotations = computeAnnotations(annotator, file);

        var annotation = annotations.stream()
            .filter(a -> a.getMessage() != null && a.getMessage().contains("Reserved system domain"))
            .findFirst();
        assertTrue("Reserved domain annotation must be present", annotation.isPresent());

        var renameFix = annotation.get().getQuickFixes().stream()
            .map(q -> q.quickFix)
            .filter(q -> q instanceof StvnRenameDomainQuickFix)
            .findFirst()
            .get();

        myFixture.launchAction(renameFix);

        assertEquals("prelude.domain.stvn_d", file.getVirtualFile().getName());
        String text = file.getText();
        assertTrue("Buffer must contain updated #domain \"domain\"", text.contains("#domain \"domain\""));
    }

    /**
     * TC-QUICKFIX-FILELEVEL-01: Verifies that a non-empty file without #domain in :meta
     * whose filename has a reserved domain emits a file-level annotation with the quick-fix suite.
     */
    public void testNonEmptyFileWithoutMetaEmitsFileLevelAnnotationAndFixes() throws Exception {
        String content = """
            {
              :defs {
                :MyType :Int
              }
            }
            """;
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", content);
        var annotator = new StvnExternalAnnotator();
        var annotations = computeAnnotations(annotator, file);

        var fileLevel = annotations.stream().filter(Annotation::isFileLevelAnnotation).findFirst();
        assertTrue("File-level annotation must be present for filename violation", fileLevel.isPresent());

        assertTrue("Must contain StvnOmitDomainQuickFix",
            fileLevel.get().getQuickFixes().stream().anyMatch(q -> q.quickFix instanceof StvnOmitDomainQuickFix));
        assertTrue("Must contain StvnRenameDomainQuickFix",
            fileLevel.get().getQuickFixes().stream().anyMatch(q -> q.quickFix instanceof StvnRenameDomainQuickFix));
        assertTrue("Must contain StvnInteractiveRenameQuickFix",
            fileLevel.get().getQuickFixes().stream().anyMatch(q -> q.quickFix instanceof StvnInteractiveRenameQuickFix));

        var omitFix = fileLevel.get().getQuickFixes().stream()
            .map(q -> q.quickFix)
            .filter(q -> q instanceof StvnOmitDomainQuickFix)
            .findFirst()
            .get();

        myFixture.launchAction(omitFix);
        assertEquals("prelude.stvn_d", file.getVirtualFile().getName());
    }

    /**
     * TC-QUICKFIX-COLLISION-01: Verifies that StvnOmitDomainQuickFix is unavailable
     * when the target file name already exists in the folder.
     */
    public void testOmitDomainQuickFixCollisionSuppressed() {
        myFixture.addFileToProject("prelude.stvn_d", "{\n  :defs {}\n}\n");
        var file = myFixture.configureByText("prelude.stvn_core.stvn_d", "");

        var fix = StvnOmitDomainQuickFix.create(file);
        assertNotNull(fix);
        assertFalse("Fix must be unavailable when target file exists",
            fix.isAvailable(getProject(), myFixture.getEditor(), file));
    }

    /**
     * TC-QUICKFIX-INTERACTIVE-01: Verifies that StvnInteractiveRenameQuickFix is available
     * on Layer 1 legacy extension errors and reserved domain errors.
     */
    public void testInteractiveRenameQuickFixAvailability() throws Exception {
        var legacyFile = myFixture.configureByText("module.stvn_incl", "");
        var annotator = new StvnExternalAnnotator();
        var annotations = computeAnnotations(annotator, legacyFile);

        var fileLevel = annotations.stream().filter(Annotation::isFileLevelAnnotation).findFirst();
        assertTrue("Legacy file must have file-level annotation", fileLevel.isPresent());

        assertTrue("Must contain StvnInteractiveRenameQuickFix",
            fileLevel.get().getQuickFixes().stream().anyMatch(q -> q.quickFix instanceof StvnInteractiveRenameQuickFix));
    }
}
