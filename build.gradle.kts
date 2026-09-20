import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jetbrains.grammarkit") version "2023.3.0.3"
}

group = "io.github.chaotic3quilibrium"
version = "2.0.0-SNAPSHOT"

repositories {
    mavenLocal() // Prioritize local Maven repository for stvnadore-core SDK
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

// Custom configuration to resolve the core SDK test fixtures
val stvnFixtures: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    // 1. Ingest local Maven repository dependency
    implementation("io.github.chaotic3quilibrium:stvnadore-core:1.3.1")
    
    // 2. Local Maven dependency for zip classifier fixtures
    stvnFixtures("io.github.chaotic3quilibrium:stvnadore-core:1.3.1:fixtures@zip")

    // 3. Modern IntelliJ Platform SDK (2025.3) and Testing Frameworks
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    implementation("org.jspecify:jspecify:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

// Java & Kotlin Compatibility Options
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// 4. Custom Copy Task Architecture for Classpath-Embedded Fixtures
val extractFixtures by tasks.registering(Copy::class) {
    description = "Extracts test fixtures from the stvnadore-core SDK zip classifier"
    group = "verification"
    dependsOn(stvnFixtures)
    from(stvnFixtures.map { file -> zipTree(file) })
    into(layout.buildDirectory.dir("extracted-fixtures"))
}

// Map the extracted fixtures directly into the test classpath resources
sourceSets {
    main {
        java.srcDirs("src/main/gen")
    }
    test {
        resources {
            srcDir(extractFixtures)
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Werror",
        "-Xlint:-missing-explicit-ctor",
        "-Xlint:-requires-automatic",
        "-Xlint:-module",
        "-Xlint:-serial"
    ))
}

intellijPlatform {
    pluginConfiguration {
        id.set("org.stvnadore.plugin")
        name.set("STVN Language Support")
        version.set(project.version.toString())
        description.set("""
            Provides comprehensive language support for Strongly Typed Value Notation (STVN).
            Features include syntax highlighting, BNF-based parser inspection, 
            type resolution, diagnostics, and test fixture support.
        """.trimIndent())
        changeNotes.set("""
            <h3>1.3.1 - 2026.09.20</h3>
            <ul>
              <li>Integrated changes to <code>stvnadore-core:1.3.1</code>:
                <ul>
                  <li>Tolerant grammar recovery for empty <code>{}</code> blocks in directives and metadata.</li>
                  <li>Normalized token coordinate spans preventing off-by-one clipping on error squiggles.</li>
                  <li>Enforced package enclave isolation across module boundaries.</li>
                  <li>Restored recursive inlay hints via bounded depth traversal (<code>maxDepth = 16</code>).</li>
                  <li>Added <code>StvnMetadataFacetInspection</code> with automated quick-fixes.</li>
                </ul>
              </li>
            </ul>
            <h3>1.3.0 - 2026.09.17</h3>
            <ul>
              <li>Integrated changes to <code>stvnadore-core:1.3.0</code>:
                <ul>
                  <li>Enforced strict Zero-Tab Invariant (<code>ERR_TAB_CHARACTER_FORBIDDEN</code>) across all document parsers.</li>
                  <li>Integrated centralized string capacity bounds (<code>StvnStringCapacityUtils</code>).</li>
                  <li>Added transitive reachability analysis, dead-code pruning in <code>:defs</code>, and universal alias desugaring to FQNI.</li>
                  <li>Added deterministic canonical AST formatting via <code>AstPrettyPrinter</code> and <code>AstCompactPrinter</code>.</li>
                </ul>
              </li>
              <li><b>Value-Oriented Programming (VOP) Rename Refactoring (Shift+F6):</b>
                <ul>
                  <li>Decoupled mutable bare identifiers from immutable syntax kinds, initializing the rename text field with bare names (e.g. <code>AccountHolder</code>, <code>DefaultPort</code>).</li>
                  <li>Projected canonical syntax in dialog headers (<code>Rename Type Declaration ':AccountHolder' and its usages to:</code>), Find Usages trees, and Structure View.</li>
                  <li>Added real-time dialog input validation via <code>StvnRenameInputValidator</code> (<code>RenameInputValidatorEx</code>) rejecting leading <code>:</code> and <code>#</code> with explicit footer guidance and disabling the Refactor button.</li>
                  <li>Established a fail-closed mutator perimeter: invoking <code>setName()</code> or <code>handleElementRename()</code> with leading sigils throws <code>IncorrectOperationException</code>.</li>
                  <li>Implemented dual Search Everywhere indexing for bare and canonical symbols (<code>Ctrl+N</code> / <code>Shift+Shift</code>).</li>
                </ul>
              </li>
              <li><b>Interactive Namespace Dependency Browser (Ctrl+Alt+N / Cmd+Alt+N):</b>
                <ul>
                  <li>Added interactive gutter navigation markers on <code>:defs</code>, <code>:type</code>, and <code>:body</code> section headers.</li>
                  <li>Added a multi-column searchable grid displaying symbol name, namespaced source URI, type structure, and usage resolution depth.</li>
                  <li>Implemented <code>StvnPrioritizedTableSpeedSearch</code> prioritizing Column 0 (<code>Name</code>) declarations over secondary metadata mentions with clean fallback.</li>
                  <li>Supported synthetic standard library navigation via read-only <code>LightVirtualFile</code>.</li>
                </ul>
              </li>
              <li><b>String Capacity Governance Inspection (<code>StvnStringCapacity</code>):</b>
                <ul>
                  <li>Inspects nominal string declarations in <code>:defs</code> and <code>:type</code> sections.</li>
                  <li>Audits unadorned <code>:String</code> tokens and explicit capacities exceeding threshold (default: 4,096).</li>
                  <li>Dual QuickFix Resolution Protocol: rewrites nominal types to configured threshold (<code>:String4096</code>) or default (<code>:String16777216</code>).</li>
                  <li>Suppresses secondary default upgrade QuickFix under <code>ERROR</code> severity.</li>
                  <li>Optional payload scope detecting string literals exceeding declared schema capacity bounds with truncation and widening quick-fixes.</li>
                </ul>
              </li>
              <li><b>Native Code Formatter &amp; Dual Projections:</b>
                <ul>
                  <li>Added native code formatter (<code>Ctrl+Alt+L</code> / <code>Cmd+Alt+L</code>) enforcing canonical 2-space indentation hierarchy and zero tabs while preserving comments and fenced strings.</li>
                  <li>Added editor popup actions to canonicalize or copy pretty-printed and compact STVN projections.</li>
                  <li>Added Authoring Loss Guard dialog intercepting lossy formatting when documents contain comments, package enclaves, or unreferenced definitions.</li>
                </ul>
              </li>
              <li><b>Action Nomenclature &amp; UI Modernization:</b> Harmonized action menu display names and modernized file icons to transparent 2D vector wireframe geometry conforming to JetBrains New UI guidelines.</li>
            </ul>
            <h3>1.2.0 - 2026.09.12</h3>
            <ul>
              <li>Integrated changes to `stvnadore-core`:
                <ul>
                  <li>Standard prelude relocated to namespace <code>:org/stvnadore/prelude/*</code> and out of root, completely clearing the root namespace</li>
                  <li>Atomic temporal primitives pruned to nominal prelude schemas</li>
                  <li>Package enclosures (<code>:package</code>) with automatic LHS FQNI expansion</li>
                  <li>Scoped <code>:use</code> with atomic unary <code>#strip</code> terminal slicing</li>
                  <li>Hermetic flat payload tier (newly introduced <code>.stvn_f</code>) and flat schema tier (existing <code>.stvn_inclf</code>)</li>
                  <li>Arbitrary bit-width integer overflow enforcement (BigInteger)</li>
                  <li>Updated shared-fixtures conformance suite</li>
                </ul>
              </li>
              <li><b>STVN 1.2 Namespace &amp; Lexical Scope Architecture:</b> Introduced native grammar and editor support for <code>:package</code> enclaves and lexical import statements (<code>:use</code>) with multi-target maps, aliasing, and <code>#strip</code> modifier.</li>
              <li><b>Flat Payload Documents (<code>.stvn_f</code>):</b> Registered new <code>.stvn_f</code> file type and parser definition for detached flat payload evaluation without embedded headers.</li>
              <li><b>Prelude Standard Library Synchronization:</b> Synchronized with <code>stvnadore-core:1.2.0</code>, transitioning temporal atoms (<code>:DateTimeOffset</code>, <code>:DateTimeZoned</code>, <code>:DateTimeAudited</code>, epoch timestamps) to regex-constrained standard library prelude definitions under <code>:org/stvnadore/prelude/*</code>.</li>
              <li><b>5 New In-Editor Inspections:</b> Implemented real-time inspections for nested package declarations (<code>StvnNestedPackage</code>), trailing slashes in type definitions (<code>StvnTrailingSlash</code>), LHS reserved type keywords (<code>StvnLhsReservedType</code>), <code>:include</code> directives in flat payload documents (<code>StvnFlatDocumentInclude</code>), and out-of-order constant definitions (<code>StvnConstantRange</code>).</li>
              <li><b>Precise Error Range Clamping:</b> Clamped external compiler diagnostic ranges directly to offending AST elements across file definitions, resolving error displacement and suppressing cascading parser artifacts.</li>
            </ul>
            <h3>1.1.1 - 2026.09.07</h3>
            <ul>
              <li><b>Rule STR-04 Fenced String Delimiter Modernization:</b> Formalized canonical <code>&quot;&quot;&quot;[TAG]</code> opening delimiter; deprecated legacy <code>&quot;&quot;&quot;-&gt;[TAG]</code> with a <code>LIKE_DEPRECATED</code> strikeout diagnostic and inspection warning.</li>
              <li><b>Automated Code Cleanup:</b> Added <code>Remove deprecated '-&gt;' arrow</code> intention quick-fix with full IntelliJ batch <b>Code | Code Cleanup</b> support across files and projects.</li>
              <li><b>Interactive Tag Renaming (Shift+F6):</b> Implemented live linked editing for opening and closing delimiter tags with bidirectional caret focus retention and delta-aware cursor anchoring.</li>
              <li><b>AST Fracture & Collision Guard:</b> Intercepts tag renames that collide with delimiter sequences inside the string payload, preventing AST breakage and rolling back invalid commits.</li>
              <li><b>Core Dependency Alignment:</b> Upgraded compiler and runtime engine to <code>stvnadore-core:1.1.1</code>.</li>
            </ul>
            <h3>1.1.0 - 2026.09.06</h3>
            <ul>
              <li>Implemented enum subset filtering with transitive chaining.</li>
              <li>Added Control Byte 4 bitwise partitioning (1:3:4) for CRC-32C, SchemaIdentityStrategy, and BinaryEncodingStrategy.</li>
            </ul>
            <h3>1.0.2 - 2026.09.04</h3>
            <ul>
              <li>Initial General Availability release of STVN Language Support for IntelliJ 2025.3+.</li>
              <li>Full syntax highlighting for STVN primitives, compounds, and types.</li>
              <li>Compile-time diagnostics, parser verification, and test fixture support.</li>
            </ul>
        """.trimIndent())
        vendor {
            name.set("chaotic3quilibrium")
            email.set("jim.oflaherty.jr@gmail.com")
            url.set("https://github.com/chaotic3quilibrium/stvnadore-plugin")
        }
        ideaVersion {
            sinceBuild.set("253.0")
        }
    }

    publishing {
        token.set(providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN"))
    }

    buildSearchableOptions.set(false)
    instrumentCode.set(false)
}

val mirrorSharedFixtures by tasks.registering {
    description = "Mirrors test fixtures from sibling core repository if present"
    group = "verification"

    val siblingDir = file("../ij_stvnadore_core/shared-fixtures")
    val targetDir = file("src/test/resources/shared-fixtures")

    inputs.dir(siblingDir).optional()
    outputs.dir(targetDir)

    doLast {
        if (siblingDir.exists() && siblingDir.isDirectory) {
            logger.lifecycle("Syncing shared-fixtures from sibling core repository: ${siblingDir.absolutePath}")
            siblingDir.copyRecursively(targetDir, overwrite = true)
        } else {
            logger.warn("WARNING: Sibling core repository fixtures directory not found at: ${siblingDir.absolutePath}. Test execution will proceed with standard classpath assets.")
        }
    }
}

tasks.processTestResources {
    dependsOn(mirrorSharedFixtures)
}

val unitTest = tasks.register<Test>("unitTest") {
    description = "Runs pure unit tests without the IntelliJ sandbox"
    group = "verification"
    useJUnitPlatform()
    dependsOn(mirrorSharedFixtures)
    
    exclude("**/StvnDiagnosticsTest.class")
    exclude("**/StvnCommenterTest.class")
    exclude("**/StvnTypeResolverTest.class")
    exclude("**/StvnDegradedSchemaInspectionTest.class")
    exclude("**/StvnInspectionDescriptionsTest.class")
    exclude("**/StvnVariantStyleInspectionTest.class")
    exclude("**/StvnDegenerateCompositeInspectionTest.class")
    exclude("**/StvnDocumentationTest.class")
    exclude("**/StvnDocumentationProviderTest.class")
    exclude("**/StvnCompletionTest.class")
    exclude("**/StvnEnumSubsetCompletionTest.class")
    exclude("**/StvnEnumSubsetQuickFixTest.class")
    exclude("**/StvnWrapSumVariantQuickFixTest.class")
    exclude("**/StvnFencedStringInspectionTest.class")
    exclude("**/StvnFencedStringTagRenameHandlerTest.class")
    exclude("**/StvnDefsOverhaulInspectionsTest.class")
    exclude("**/StvnStringCapacityInspectionTest.class")
    exclude("**/StvnMetadataFacetInspectionTest.class")
    exclude("**/StvnFormatterTest.class")
    exclude("**/StvnDualProjectionActionsTest.class")
    exclude("**/StvnRenameRefactoringTest.class")
    exclude("**/StvnNamespaceBrowserTest.class")
    
    classpath = sourceSets.test.get().runtimeClasspath.filter { file ->
        val path = file.absolutePath.replace('\\', '/').lowercase()
        !path.contains("jetbrains") &&
        !path.contains("intellij") &&
        !path.contains("idea") &&
        !path.contains("plugins")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    maxHeapSize = "2g"
}


tasks.test {
    dependsOn(unitTest)
    enabled = true
    jvmArgs("-Xss16m")
    maxHeapSize = "4g"
}

tasks.generateLexer {
    sourceFile.set(file("src/main/grammar/stvn.flex"))
    targetOutputDir.set(layout.projectDirectory.dir("src/main/gen/org/stvnadore/parser"))
    purgeOldFiles.set(false)
}

tasks.generateParser {
    sourceFile.set(file("src/main/grammar/stvn.bnf"))
    targetRootOutputDir.set(layout.projectDirectory.dir("src/main/gen"))
    pathToParser.set("org/stvnadore/parser/StvnParser.java")
    pathToPsiRoot.set("org/stvnadore/psi")
    purgeOldFiles.set(false)
}

tasks.compileJava {
    dependsOn(tasks.generateLexer, tasks.generateParser)
}

tasks.javadoc {
    exclude("org/stvnadore/psi/**")
    exclude("org/stvnadore/parser/**")
    exclude("org/stvnadore/plugin/psi/**")
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        addStringOption("Xdoclint:all", "-quiet")
    }
}
