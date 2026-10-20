package org.stvnadore.plugin.validation;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jspecify.annotations.NullMarked;
import org.stvnadore.core.stdlib.StvnPrelude;
import org.stvnadore.core.validation.DiagnosticBag;
import org.stvnadore.plugin.validation.quickfix.StvnChangeReservedDomainQuickFix;
import org.stvnadore.plugin.validation.quickfix.StvnRenameReservedPackageQuickFix;

import java.util.Arrays;

/**
 * Platform verification test suite verifying IntelliJ plugin enforcement of
 * Reserved Package Namespaces (E037) and Reserved System Domains (E038).
 */
@NullMarked
public final class StvnReservedPackageAndDomainPluginTest extends BasePlatformTestCase {

  /**
   * TC-PLUG-RES-01: Verifies that declaring reserved system domain "stvn_core" in a user
   * document produces fatal error E038 pinned strictly to the domain string literal.
   */
  public void testReservedDomainStvnCoreAnnotationPinning() {
    var content = """
        {
          :meta {
            #name "custom"
            #domain "stvn_core"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    myFixture.configureByText("test.stvn_core.stvn", content);
    var highlights = myFixture.doHighlighting();

    var e038 = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
            && h.getDescription() != null
            && h.getDescription().contains(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED))
        .findFirst();

    assertTrue("Must emit ERR_RESERVED_DOMAIN_PROHIBITED (E038)", e038.isPresent());
    var h = e038.get();

    int expectedStart = content.indexOf("\"stvn_core\"");
    int expectedEnd = expectedStart + "\"stvn_core\"".length();
    assertEquals("Start offset must pin strictly to domain string literal", expectedStart, h.getStartOffset());
    assertEquals("End offset must pin strictly to domain string literal", expectedEnd, h.getEndOffset());
  }

  /**
   * TC-PLUG-RES-02: Verifies that declaring reserved system domain "stvn_ir" in a user
   * document produces fatal error E038.
   */
  public void testReservedDomainStvnIrAnnotation() {
    var content = """
        {
          :meta {
            #name "custom"
            #domain "stvn_ir"
            #kind #BODY
          }
          :type :Int
          :body 20
        }
        """;
    myFixture.configureByText("test.stvn_ir.stvn", content);
    var highlights = myFixture.doHighlighting();
    assertTrue("Must emit E038 on stvn_ir",
        highlights.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("ERR_RESERVED_DOMAIN_PROHIBITED")));
  }

  /**
   * TC-PLUG-RES-03: Verifies that declaring reserved system domain "stvn_cas" in a user
   * document produces fatal error E038.
   */
  public void testReservedDomainStvnCasAnnotation() {
    var content = """
        {
          :meta {
            #name "custom"
            #domain "stvn_cas"
            #kind #BODY
          }
          :type :Int
          :body 30
        }
        """;
    myFixture.configureByText("test.stvn_cas.stvn", content);
    var highlights = myFixture.doHighlighting();
    assertTrue("Must emit E038 on stvn_cas",
        highlights.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("ERR_RESERVED_DOMAIN_PROHIBITED")));
  }

  /**
   * TC-PLUG-RES-04: Verifies that declaring reserved package namespace :org/stvnadore/prelude
   * produces fatal error E037 pinned strictly to the package path token.
   */
  public void testReservedPackagePreludeAnnotationPinning() {
    var content = """
        {
          :defs {
            :package :org/stvnadore/prelude {
              :CustomPort :Int
            }
          }
          :type :Int
          :body 8080
        }
        """;
    myFixture.configureByText("test_prelude.stvn", content);
    var highlights = myFixture.doHighlighting();

    var e037 = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
            && h.getDescription() != null
            && h.getDescription().contains(DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED))
        .findFirst();

    assertTrue("Must emit ERR_RESERVED_PACKAGE_PROHIBITED (E037)", e037.isPresent());
    var h = e037.get();

    int expectedStart = content.indexOf(":org/stvnadore/prelude");
    int expectedEnd = expectedStart + ":org/stvnadore/prelude".length();
    assertEquals("Start offset must pin strictly to package path token", expectedStart, h.getStartOffset());
    assertEquals("End offset must pin strictly to package path token", expectedEnd, h.getEndOffset());
  }

  /**
   * TC-PLUG-RES-05: Verifies that declaring a qualified constant targeting a reserved
   * namespace produces fatal error E037.
   */
  public void testReservedQualifiedConstantDefinitionAnnotation() {
    var content = """
        {
          :defs {
            #org/stvnadore/prelude/POSIX_ID_REGEX :String "^[a-z]+$"
          }
          :type :String
          :body "test"
        }
        """;
    myFixture.configureByText("test_const.stvn", content);
    var highlights = myFixture.doHighlighting();
    assertTrue("Must emit E037 on qualified constant definition targeting reserved namespace",
        highlights.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().contains("ERR_RESERVED_PACKAGE_PROHIBITED")));
  }

  /**
   * TC-PLUG-RES-06: Verifies that valid user packages and project domains produce zero error annotations.
   */
  public void testValidUserPackageAndDomainProduceZeroErrors() {
    var content = """
        {
          :meta {
            #name "service"
            #domain "telemetry"
            #kind #BODY
          }
          :defs {
            :package :com/example/domain {
              :Port :Int
            }
          }
          :type :com/example/domain/Port
          :body 9000
        }
        """;
    myFixture.configureByText("service.telemetry.stvn", content);
    var highlights = myFixture.doHighlighting();
    var errors = highlights.stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR).toList();
    assertTrue("Valid user package and domain must produce zero error annotations: " + errors, errors.isEmpty());
  }

  /**
   * TC-PLUG-RES-07: Verifies that package completion suppresses :org/stvnadore namespaces.
   */
  public void testPackageCompletionSuppressesReservedNamespaces() {
    myFixture.configureByText("test_pkg_comp.stvn", """
        {
          :defs {
            :package :<caret>
          }
        }
        """);
    var elements = myFixture.completeBasic();
    assertNotNull(elements);
    var strings = Arrays.stream(elements).map(LookupElement::getLookupString).toList();

    assertFalse("Must suppress :org/stvnadore from package completion", strings.contains(":org/stvnadore"));
    assertFalse("Must suppress :org/stvnadore/prelude from package completion", strings.contains(":org/stvnadore/prelude"));
  }

  /**
   * TC-PLUG-RES-08: Verifies that :use completion includes standard prelude packages.
   */
  public void testUseCompletionIncludesPreludePackages() {
    myFixture.configureByText("test_use_comp.stvn", """
        {
          :defs {
            :use [ :<caret> ]
          }
        }
        """);
    var elements = myFixture.completeBasic();
    assertNotNull(elements);
    var strings = Arrays.stream(elements).map(LookupElement::getLookupString).toList();

    assertTrue("Must propose :org/stvnadore/prelude in :use", strings.contains(":org/stvnadore/prelude"));
    assertTrue("Must propose :org/stvnadore/prelude/meta in :use", strings.contains(":org/stvnadore/prelude/meta"));
  }

  /**
   * TC-PLUG-RES-09: Verifies that domain completion suppresses reserved system domains.
   */
  public void testDomainCompletionSuppressesSystemDomains() {
    myFixture.configureByText("test_domain_comp.stvn", """
        {
          :meta {
            #domain "<caret>"
          }
        }
        """);
    var elements = myFixture.completeBasic();
    assertNotNull(elements);
    var strings = Arrays.stream(elements).map(LookupElement::getLookupString).toList();

    assertFalse("Must suppress 'stvn_core'", strings.contains("stvn_core"));
    assertFalse("Must suppress 'stvn_ir'", strings.contains("stvn_ir"));
    assertFalse("Must suppress 'stvn_cas'", strings.contains("stvn_cas"));
  }

  /**
   * Verifies that StvnRenameReservedPackageQuickFix renames reserved package namespace to project namespace.
   */
  public void testRenameReservedPackageQuickFixExecution() {
    var content = """
        {
          :defs {
            :package :org/stvnadore/prelude {
              :Port :Int
            }
          }
          :type :Int
          :body 8080
        }
        """;
    var psiFile = myFixture.configureByText("fix_pkg.stvn", content);
    int offset = content.indexOf(":org/stvnadore/prelude");
    var element = psiFile.findElementAt(offset + 1);
    assertNotNull(element);

    var fix = new StvnRenameReservedPackageQuickFix(element);
    assertEquals("Rename reserved namespace to project namespace ':com/example'", fix.getText());
    assertEquals("STVN Package Governance", fix.getFamilyName());

    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
      fix.invoke(getProject(), psiFile, myFixture.getEditor(), element, element);
    });

    var updated = myFixture.getEditor().getDocument().getText();
    assertTrue("Must rename package to :com/example/prelude:\n" + updated, updated.contains(":package :com/example/prelude"));
  }

  /**
   * Verifies that StvnChangeReservedDomainQuickFix replaces reserved system domain with filename-derived domain.
   */
  public void testChangeReservedDomainQuickFixExecution() {
    var content = """
        {
          :meta {
            #name "account"
            #domain "stvn_core"
            #kind #BODY
          }
          :type :Int
          :body 10
        }
        """;
    var psiFile = myFixture.configureByText("account.telemetry.stvn", content);
    int offset = content.indexOf("\"stvn_core\"");
    var element = psiFile.findElementAt(offset + 1);
    assertNotNull(element);

    var fix = new StvnChangeReservedDomainQuickFix(element);
    assertEquals("Change reserved domain to compliant user domain", fix.getText());
    assertEquals("STVN Document Identity", fix.getFamilyName());

    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () -> {
      fix.invoke(getProject(), psiFile, myFixture.getEditor(), element, element);
    });

    var updated = myFixture.getEditor().getDocument().getText();
    assertTrue("Must change domain to account:\n" + updated, updated.contains("#domain \"account\""));
  }

  /**
   * TC-PLUG-RES-10: Verifies that configuring a physical project file named 'prelude.stvn_core.stvn_d'
   * does not bypass security gates and emits ERR_RESERVED_DOMAIN_PROHIBITED and ERR_RESERVED_PACKAGE_PROHIBITED.
   */
  public void testProjectPreludeFileEmitsReservedDomainAndPackageErrors() {
    myFixture.configureByText("prelude.stvn_core.stvn_d", StvnPrelude.PRELUDE_STVN_D);
    var highlights = myFixture.doHighlighting();

    var e038 = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
            && h.getDescription() != null
            && h.getDescription().contains(DiagnosticBag.ERR_RESERVED_DOMAIN_PROHIBITED))
        .findFirst();
    assertTrue("Must emit ERR_RESERVED_DOMAIN_PROHIBITED on 'stvn_core'", e038.isPresent());

    var e037 = highlights.stream()
        .filter(h -> h.getSeverity() == HighlightSeverity.ERROR
            && h.getDescription() != null
            && h.getDescription().contains(DiagnosticBag.ERR_RESERVED_PACKAGE_PROHIBITED))
        .findFirst();
    assertTrue("Must emit ERR_RESERVED_PACKAGE_PROHIBITED on ':org/stvnadore/prelude'", e037.isPresent());
  }
}
