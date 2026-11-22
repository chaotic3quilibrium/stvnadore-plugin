import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.Arrays
import java.util.TreeMap
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.work.DisableCachingByDefault
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

@DisableCachingByDefault(because = "Synchronizes local sibling core classes into prepared IntelliJ sandbox library JARs in place")
abstract class SyncSiblingCoreTask : DefaultTask() {
    @get:Internal
    abstract val siblingClassesDir: DirectoryProperty

    @get:Internal
    abstract val sandboxRootDir: DirectoryProperty

    @get:Input
    abstract val targetPluginsDirName: Property<String>

    @TaskAction
    fun sync() {
        if (!siblingClassesDir.isPresent || !sandboxRootDir.isPresent) {
            return
        }
        val siblingClasses = siblingClassesDir.get().asFile
        if (!siblingClasses.exists() || !siblingClasses.isDirectory) {
            return
        }
        val sandboxRoot = sandboxRootDir.get().asFile
        if (!sandboxRoot.exists() || !sandboxRoot.isDirectory) {
            return
        }

        val siblingFiles = collectSiblingFiles(siblingClasses)
        if (siblingFiles.isEmpty()) {
            return
        }

        val targetDirName = targetPluginsDirName.get()
        val excludedDirNames = setOf(
            "system",
            "system-test",
            "config",
            "config-test",
            "log",
            "log-test",
            if (targetDirName == "plugins") "plugins-test" else "plugins"
        )

        val coreJars = sandboxRoot.walkTopDown()
            .onEnter { dir -> dir == sandboxRoot || dir.name !in excludedDirNames }
            .filter { file ->
                file.isFile &&
                    isTargetCoreBinaryJar(file.name) &&
                    hasAncestorNamed(file, sandboxRoot, targetDirName)
            }
            .sortedBy { it.absolutePath }
            .toList()

        coreJars.forEach { coreJar ->
            cleanupStaleTempFiles(coreJar.parentFile)
            val differingCount = countDifferingOrMissingEntries(coreJar, siblingFiles)
            if (differingCount == 0) {
                logger.info("Sibling core classes already match sandbox library: ${coreJar.absolutePath}")
            } else {
                logger.lifecycle(
                    "Syncing $differingCount updated sibling core file(s) into: ${coreJar.absolutePath}"
                )
                rewriteJarAtomically(coreJar, siblingClasses, siblingFiles, differingCount)
            }
        }
    }

    private fun isTargetCoreBinaryJar(fileName: String): Boolean =
        fileName.startsWith("stvnadore-core-") &&
            fileName.endsWith(".jar") &&
            !fileName.endsWith("-sources.jar") &&
            !fileName.endsWith("-javadoc.jar") &&
            !fileName.endsWith("-tests.jar")

    private fun hasAncestorNamed(file: File, stopExclusive: File, expectedName: String): Boolean {
        var current: File? = file.parentFile
        while (current != null && current != stopExclusive) {
            if (current.name == expectedName) {
                return true
            }
            current = current.parentFile
        }
        return false
    }

    private fun collectSiblingFiles(siblingClasses: File): Map<String, File> {
        val sorted = TreeMap<String, File>()
        siblingClasses.walkTopDown()
            .filter { it.isFile }
            .forEach { sourceFile ->
                val relPath = sourceFile.relativeTo(siblingClasses).invariantSeparatorsPath.trimStart('/')
                if (relPath.isNotEmpty()) {
                    sorted[relPath] = sourceFile
                }
            }
        return sorted
    }

    private fun cleanupStaleTempFiles(libDir: File?) {
        val candidates = libDir?.listFiles() ?: return
        for (candidate in candidates) {
            if (!candidate.isFile || !candidate.name.endsWith(".tmp")) {
                continue
            }
            if (candidate.name.startsWith("zipfstmp") ||
                (candidate.name.startsWith("stvnadore-core-") && candidate.name.contains(".sync-"))
            ) {
                deleteQuietly(candidate.toPath())
            }
        }
    }

    private fun countDifferingOrMissingEntries(coreJar: File, siblingFiles: Map<String, File>): Int {
        val jarBuf = ByteArray(8192)
        val fileBuf = ByteArray(8192)
        var differingCount = 0
        try {
            ZipFile(coreJar).use { zipFile ->
                for ((relPath, sourceFile) in siblingFiles) {
                    val entry = zipFile.getEntry(relPath)
                    if (entry == null || entry.isDirectory) {
                        differingCount++
                    } else if (entry.size != -1L && entry.size != sourceFile.length()) {
                        differingCount++
                    } else {
                        val identical = zipFile.getInputStream(entry).use { jarStream ->
                            Files.newInputStream(sourceFile.toPath()).use { fileStream ->
                                streamsMatchByteForByte(jarStream, fileStream, jarBuf, fileBuf)
                            }
                        }
                        if (!identical) {
                            differingCount++
                        }
                    }
                }
            }
        } catch (zipEx: ZipException) {
            throw GradleException(
                "Cannot read corrupted sandbox library '${coreJar.absolutePath}'. " +
                    "Run './gradlew cleanSandbox' and re-run the Gradle task.",
                zipEx
            )
        }
        return differingCount
    }

    private fun streamsMatchByteForByte(
        first: InputStream,
        second: InputStream,
        firstBuf: ByteArray,
        secondBuf: ByteArray
    ): Boolean {
        while (true) {
            val firstRead = first.readNBytes(firstBuf, 0, firstBuf.size)
            val secondRead = second.readNBytes(secondBuf, 0, secondBuf.size)
            if (firstRead != secondRead) {
                return false
            }
            if (firstRead == 0) {
                return true
            }
            if (!Arrays.equals(firstBuf, 0, firstRead, secondBuf, 0, secondRead)) {
                return false
            }
        }
    }

    private fun rewriteJarAtomically(
        coreJar: File,
        siblingClasses: File,
        siblingFiles: Map<String, File>,
        differingCount: Int
    ) {
        val targetPath = coreJar.toPath()
        val tempJar = targetPath.resolveSibling("${coreJar.name}.sync-${UUID.randomUUID()}.tmp")
        val copyBuf = ByteArray(8192)
        try {
            val writtenEntries = HashSet<String>()
            ZipFile(coreJar).use { existingZip ->
                ZipOutputStream(BufferedOutputStream(Files.newOutputStream(tempJar))).use { zos ->
                    val entries = existingZip.entries()
                    while (entries.hasMoreElements()) {
                        val existingEntry = entries.nextElement()
                        val entryName = existingEntry.name
                        if (!writtenEntries.add(entryName)) {
                            continue
                        }
                        val replacementFile = if (!existingEntry.isDirectory) siblingFiles[entryName] else null
                        if (replacementFile != null) {
                            writeFileEntry(zos, entryName, replacementFile, copyBuf)
                        } else {
                            val preservedEntry = ZipEntry(entryName)
                            existingEntry.lastModifiedTime?.let { preservedEntry.lastModifiedTime = it }
                            zos.putNextEntry(preservedEntry)
                            if (!existingEntry.isDirectory) {
                                existingZip.getInputStream(existingEntry).use { input ->
                                    copyStream(input, zos, copyBuf)
                                }
                            }
                            zos.closeEntry()
                        }
                    }

                    for ((relPath, sourceFile) in siblingFiles) {
                        if (relPath !in writtenEntries) {
                            ensureParentDirectories(zos, relPath, writtenEntries)
                            writtenEntries.add(relPath)
                            writeFileEntry(zos, relPath, sourceFile, copyBuf)
                        }
                    }
                }
            }

            try {
                Files.move(
                    tempJar,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempJar,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (lockEx: FileSystemException) {
            deleteQuietly(tempJar)
            throw GradleException(
                "Cannot update locked sandbox library '${targetPath.toAbsolutePath()}': " +
                    "$differingCount class or resource file(s) in '${siblingClasses.absolutePath}' differ from the sandbox JAR, " +
                    "but another process holds a Windows file lock on '${targetPath.fileName}'. " +
                    "Close the running 'runIde' IntelliJ IDEA sandbox instance and re-run the Gradle task.",
                lockEx
            )
        } finally {
            deleteQuietly(tempJar)
        }
    }

    private fun ensureParentDirectories(
        zos: ZipOutputStream,
        relPath: String,
        writtenEntries: MutableSet<String>
    ) {
        var slashIdx = relPath.indexOf('/')
        while (slashIdx > 0) {
            val dirEntryName = relPath.substring(0, slashIdx + 1)
            if (writtenEntries.add(dirEntryName)) {
                zos.putNextEntry(ZipEntry(dirEntryName))
                zos.closeEntry()
            }
            slashIdx = relPath.indexOf('/', slashIdx + 1)
        }
    }

    private fun writeFileEntry(
        zos: ZipOutputStream,
        entryName: String,
        sourceFile: File,
        copyBuf: ByteArray
    ) {
        val entry = ZipEntry(entryName)
        entry.lastModifiedTime = FileTime.fromMillis(sourceFile.lastModified())
        zos.putNextEntry(entry)
        Files.newInputStream(sourceFile.toPath()).use { input ->
            copyStream(input, zos, copyBuf)
        }
        zos.closeEntry()
    }

    private fun copyStream(input: InputStream, output: OutputStream, buffer: ByteArray) {
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Best-effort cleanup of temporary sibling archive.
        }
    }
}

val syncSiblingCoreToSandbox by tasks.registering(SyncSiblingCoreTask::class) {
    description = "Syncs sibling core classes to main IDE sandbox plugin libraries (plugins/)"
    group = "verification"
    siblingClassesDir.set(layout.projectDirectory.dir("../ij_stvnadore_core/target/classes"))
    sandboxRootDir.set(layout.projectDirectory.dir(".intellijPlatform/sandbox"))
    targetPluginsDirName.set("plugins")
    mustRunAfter(tasks.prepareSandbox)
}

val syncSiblingCoreToTestSandbox by tasks.registering(SyncSiblingCoreTask::class) {
    description = "Syncs sibling core classes to test IDE sandbox plugin libraries (plugins-test/)"
    group = "verification"
    siblingClassesDir.set(layout.projectDirectory.dir("../ij_stvnadore_core/target/classes"))
    sandboxRootDir.set(layout.projectDirectory.dir(".intellijPlatform/sandbox"))
    targetPluginsDirName.set("plugins-test")
    mustRunAfter(tasks.prepareTestSandbox)
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

tasks.buildPlugin {
    dependsOn(syncSiblingCoreToSandbox)
    exclude("**/zipfstmp*.tmp", "**/*.sync-*.tmp")
}

tasks.runIde {
    dependsOn(syncSiblingCoreToSandbox)
    jvmArgs("-Didea.log.debug.categories=#org.stvnadore")
}

tasks.verifyPluginStructure {
    mustRunAfter(syncSiblingCoreToSandbox)
}

tasks.prepareJarSearchableOptions {
    mustRunAfter(syncSiblingCoreToSandbox)
}

tasks.prepareTestSandbox {
    finalizedBy(syncSiblingCoreToTestSandbox)
}

tasks.prepareTest {
    dependsOn(syncSiblingCoreToTestSandbox)
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
    exclude("**/StvnPreludeFencedRegexHermeticUseAndHoverParityTest*.class")
    exclude("**/StvnConvertEscapedStringToFencedIntentionTest*.class")
    exclude("**/StvnFlattenActionsTest*.class")
    exclude("**/StvnHermeticIncludeQuickFixParityTest*.class")
    exclude("**/StvnGauntletV8Batch2StringAndEpochDxTest*.class")
    exclude("**/StvnGauntletV8Batch3HoverAndSumTrajectoryTest*.class")
    exclude("**/StvnGauntletV8Batch4CrossFilePsiAndReorderDxTest*.class")
    exclude("**/StvnVopProjectionMenuMatrixTest*.class")
    exclude("**/StvnSumTagRedundancySoundnessTest*.class")
    exclude("**/StvnSimpleStringEscapeHighlightAndHoverParityTest*.class")
    exclude("**/StvnGauntletV9Batch2PluginParityTest*.class")
    exclude("**/StvnGauntletV9Batch3GrammarUseAndHoverParityTest*.class")
    exclude("**/StvnGauntletV9Batch3PluginRemediationTest*.class")
    exclude("**/StvnPluginDiagnosticSuffixTotalityAndConstSpineHoverTest*.class")
    
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
    dependsOn(syncSiblingCoreToTestSandbox)
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
