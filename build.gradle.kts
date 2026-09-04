import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("org.jetbrains.grammarkit") version "2023.3.0.3"
}

group = "io.github.chaotic3quilibrium"
version = "1.0.2"

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
    implementation("io.github.chaotic3quilibrium:stvnadore-core:1.0.2")
    
    // 2. Local Maven dependency for zip classifier fixtures
    stvnFixtures("io.github.chaotic3quilibrium:stvnadore-core:1.0.2:fixtures@zip")

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
            Initial General Availability release of STVN Language Support for IntelliJ 2025.3+.
            - Full syntax highlighting for STVN primitives, compounds, and types
            - Compile-time diagnostics and parser verification
            - Integration with stvnadore-core 1.0.1
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
            targetDir.deleteRecursively()
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
    exclude("**/StvnCompletionTest.class")
    
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
