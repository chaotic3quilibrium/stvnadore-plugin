import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    configurations.all {
        resolutionStrategy {
            cacheChangingModulesFor(0, "seconds")
        }
    }
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
    // 1. Ingest local Maven repository dependency with sibling core classes priority
    val siblingCoreClasses = file("../ij_stvnadore_core/target/classes")
    if (siblingCoreClasses.exists() && siblingCoreClasses.isDirectory) {
        implementation(files(siblingCoreClasses))
    }
    implementation("io.github.chaotic3quilibrium:stvnadore-core:2.0.0-SNAPSHOT")
    
    // 2. Local Maven dependency for zip classifier fixtures
    stvnFixtures("io.github.chaotic3quilibrium:stvnadore-core:2.0.0-SNAPSHOT:fixtures@zip")

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
            <h3>2.0.0 - 2026.10.31</h3>
            <ul>
              <li><b>STVN 2.0.0 Pure-IR Architecture Cutover:</b>
                <ul>
                  <li>Coupled type resolution, hover documentation, inlay hints, code completion, local inspections, and canonical projections directly to the immutable <code>org.stvnadore.core.ir</code> <code>StvnDocument</code>, <code>StvnDefsCatalog</code>, <code>StvnSchema</code>, and <code>StvnValue</code> record graph.</li>
                  <li>Synchronized Grammar-Kit BNF and JFlex lexical tokens with the 6-file STVN 2.0.0 taxonomy (<code>.stvn</code>, <code>.stvn_i</code>, <code>.stvn_d</code>, <code>.stvn_di</code>, <code>.stvn_b</code>, <code>.stvn_bd</code>) and zero-argument standalone metadata flags.</li>
                  <li>Routed cross-file <code>:include</code> diagnostics directly via authoritative <code>StvnDiagnostic.sourcePath()</code> provenance and resolved inspection deconfliction via <code>StvnDiagnostic.symbolicId()</code>.</li>
                  <li>Aligned remote schema repository publishing with <code>application/stvn; charset=utf-8</code> and full 7-field <code>CompileDiagnosticDto</code> / 5-field <code>SchemaMetadataDto</code> wire payloads.</li>
                </ul>
              </li>
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

abstract class SyncSiblingCoreTask : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    abstract val siblingClassesDir: DirectoryProperty

    @get:Internal
    abstract val sandboxDir: DirectoryProperty

    @TaskAction
    fun sync() {
        if (siblingClassesDir.isPresent) {
            val siblingClasses = siblingClassesDir.get().asFile
            if (siblingClasses.exists() && siblingClasses.isDirectory && sandboxDir.isPresent) {
                val sandbox = sandboxDir.get().asFile
                if (sandbox.exists()) {
                    val coreJars = sandbox.walk().filter { it.isFile && it.name.startsWith("stvnadore-core-") && it.name.endsWith(".jar") }
                    coreJars.forEach { coreJar ->
                        logger.lifecycle("Syncing updated sibling core classes into: ${coreJar.absolutePath}")
                        val env = mapOf("create" to "false")
                        val uri = URI.create("jar:" + coreJar.toURI().toString())
                        FileSystems.newFileSystem(uri, env).use { zipFs ->
                            siblingClasses.walk().filter { it.isFile }.forEach { sourceFile ->
                                val relPath = sourceFile.relativeTo(siblingClasses).path.replace('\\', '/')
                                val targetPath = zipFs.getPath("/" + relPath)
                                val parent = targetPath.parent
                                if (parent != null && !Files.exists(parent)) {
                                    Files.createDirectories(parent)
                                }
                                Files.copy(sourceFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
            }
        }
    }
}

val syncSiblingCoreToSandbox by tasks.registering(SyncSiblingCoreTask::class) {
    description = "Syncs sibling core classes to sandbox plugin libraries"
    group = "verification"
    val siblingClasses = file("../ij_stvnadore_core/target/classes")
    if (siblingClasses.exists() && siblingClasses.isDirectory) {
        siblingClassesDir.set(siblingClasses)
    }
    sandboxDir.set(layout.projectDirectory.dir(".intellijPlatform/sandbox"))
}

tasks.prepareSandbox {
    doLast {
        val noisyBundledPlugins = listOf(
            "com.intellij.kubernetes",
            "com.intellij.docker",
            "com.intellij.microservices.jvm",
            "com.intellij.Clouds.docker.gateway"
        )
        val sandboxDir = (this as Sync).destinationDir
        val disabledFile = sandboxDir.resolve("../config/disabled_plugins.txt").canonicalFile
        disabledFile.parentFile.mkdirs()
        val existing = if (disabledFile.exists()) disabledFile.readLines().map { it.trim() } else emptyList()
        val merged = (existing + noisyBundledPlugins).filter { it.isNotEmpty() }.toSortedSet()
        disabledFile.writeText(merged.joinToString("\n") + "\n")
    }
    finalizedBy(syncSiblingCoreToSandbox)
}

tasks.runIde {
    jvmArgs("-Didea.log.debug.categories=#org.stvnadore")
}

tasks.prepareTestSandbox {
    finalizedBy(syncSiblingCoreToSandbox)
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
            if (targetDir.exists()) {
                targetDir.walkBottomUp().forEach { dst ->
                    if (dst != targetDir) {
                        val rel = dst.relativeTo(targetDir)
                        val src = siblingDir.resolve(rel)
                        if (!src.exists()) {
                            dst.deleteRecursively()
                        }
                    }
                }
            }
            siblingDir.walkTopDown().forEach { src ->
                val rel = src.relativeTo(siblingDir)
                val dst = targetDir.resolve(rel)
                if (src.isDirectory) {
                    dst.mkdirs()
                } else if (!dst.exists() || Files.mismatch(src.toPath(), dst.toPath()) != -1L) {
                    dst.parentFile?.mkdirs()
                    Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
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
    
    exclude("**/StvnDiagnosticsTest*.class")
    exclude("**/StvnCommenterTest.class")
    exclude("**/StvnTypeResolverTest.class")
    exclude("**/StvnDegradedSchemaInspectionTest.class")
    exclude("**/StvnInspectionDescriptionsTest.class")
    exclude("**/StvnVariantStyleInspectionTest.class")
    exclude("**/StvnDegenerateCompositeInspectionTest.class")
    exclude("**/StvnDocumentationTest.class")
    exclude("**/StvnDocumentationProviderTest.class")
    exclude("**/StvnCompletionTest.class")
    exclude("**/StvnIncludeCompletionTest.class")
    exclude("**/StvnEnumSubsetCompletionTest.class")
    exclude("**/StvnEnumSubsetQuickFixTest.class")
    exclude("**/StvnWrapSumVariantQuickFixTest.class")
    exclude("**/StvnFencedStringInspectionTest.class")
    exclude("**/StvnTabCharacterInspectionTest.class")
    exclude("**/StvnFencedStringTagRenameHandlerTest.class")
    exclude("**/StvnDefsOverhaulInspectionsTest.class")
    exclude("**/StvnConstantRangeInspectionTest.class")
    exclude("**/StvnStringCapacityInspectionTest.class")
    exclude("**/StvnFloatPrecisionLossInspectionTest.class")
    exclude("**/StvnExcessiveExactFloatPrecisionInspectionTest.class")
    exclude("**/StvnUnadornedNominalCapacityInspectionTest.class")
    exclude("**/StvnMetadataFacetInspectionTest.class")
    exclude("**/StvnStringCardinalityInspectionTest.class")
    exclude("**/StvnDiscreteIntervalInspectionTest.class")
    exclude("**/StvnTemporalModeInspectionTest.class")
    exclude("**/StvnMetadataOrderInspectionTest.class")
    exclude("**/StvnRedundantFacetOverrideInspectionTest.class")
    exclude("**/StvnRedundantFacetAlignmentTest.class")
    exclude("**/StvnSchemaTypeMetadataTest.class")
    exclude("**/StvnSchemaFormatterTest.class")
    exclude("**/StvnStructureViewTest.class")
    exclude("**/StvnBreadcrumbsTest.class")
    exclude("**/StvnFormatterTest.class")
    exclude("**/StvnGoldenFormatterPluginConformanceTest.class")
    exclude("**/StvnCodeStyleSettingsProviderTest.class")
    exclude("**/StvnDualProjectionActionsTest.class")
    exclude("**/StvnActionThreadingTest.class")
    exclude("**/StvnRenameRefactoringTest.class")
    exclude("**/StvnNamespaceBrowserTest.class")
    exclude("**/StvnDualGrammarDifferentialTest*.class")
    exclude("**/StvnFileTypeTest.class")
    exclude("**/StvnIconsTest.class")
    exclude("**/StvnIconProviderTest.class")
    exclude("**/StvnTypeReferenceTest.class")
    exclude("**/StvnUnresolvedTypeQuickFixTest.class")
    exclude("**/StvnBareTraitCompletionQuickFixTest.class")
    exclude("**/StvnBatchQuickFixTest.class")
    exclude("**/StvnExternalAnnotatorTest.class")
    exclude("**/StvnMissingMetaInspectionTest.class")
    exclude("**/StvnStemMismatchInspectionTest.class")
    exclude("**/StvnPreludeNavigationTest.class")
    exclude("**/StvnAddMetaBlockIntentionTest.class")
    exclude("**/StvnFileEditorListenerTest.class")
    exclude("**/StvnPreludeConstantDocumentationTest.class")
    exclude("**/StvnMetaVisibilityAndWolfTest.class")
    exclude("**/StvnReservedPackageAndDomainPluginTest.class")
    exclude("**/StvnFileIdentityRenameQuickFixTest.class")
    exclude("**/StvnIdentityHardeningTest.class")
    exclude("**/StvnMissingNominalCapacityInspectionTest.class")
    exclude("**/StvnTimeEpochHoverTest.class")
    exclude("**/StvnSuspiciousEpochMagnitudeInspectionTest.class")
    exclude("**/StvnTypeInlayHintsTest.class")
    exclude("**/StvnDuplicateDiagnosticsIntegrationTest*.class")
    exclude("**/StvnSemanticHoverContractTest.class")
    exclude("**/StvnTraitViolationNavigationAndHealingTest.class")
    exclude("**/StvnFacetAllowlistAndCoreDiagnosticParityTest.class")
    exclude("**/StvnTradingWorkflowIntegrationTest.class")
    exclude("**/StvnPayloadValueQuickFixTest.class")
    exclude("**/StvnCtrlClickNavigationTest.class")
    exclude("**/StvnRootSectionCohesionInspectionAndQuickFixTest*.class")
    exclude("**/StvnSimpleStringHighlightingLexerTest*.class")
    exclude("**/StvnTemporalConstantAndLiteralHoverTest*.class")
    
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
        addStringOption("Xdoclint:all,-missing", "-quiet")
        addBooleanOption("Werror", true)
    }
}
