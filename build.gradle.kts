plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "ru.dsudomoin"
version = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0")
    isIgnoreExitValue = true
}.standardOutput.asText.map { text ->
    text.trim().removePrefix("v").ifEmpty { "0.0.0-SNAPSHOT" }
}.get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Compose/Jewel UI support (bundled in IntelliJ 2025.1+)
        @Suppress("UnstableApiUsage")
        composeUI()

        // Jewel Markdown modules (bundled but not included by composeUI)
        bundledModule("intellij.platform.jewel.markdown.core")
        bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmAlerts")
        bundledModule("intellij.platform.jewel.markdown.extensions.gfmStrikethrough")
        bundledModule("intellij.platform.jewel.markdown.extensions.autolink")
    }

    // Kotlin Coroutines — provided by IDE, must NOT be bundled (classloader conflict with Compose runtime)
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Serialization — provided by IDE
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253.28294"
        }

        changeNotes = """
            Phase 1: Core foundation with native chat UI
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
