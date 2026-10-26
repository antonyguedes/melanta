import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Avalia modelos `.litertlm` no desktop com o mesmo pipeline do app: compila os fontes Kotlin
 * puros de prompt, parser, schema e cenários do :core:ai e roda sobre o litertlm-jvm.
 *
 *     ./gradlew :tools:model-bench:run --args="<modelo.litertlm> [--free] [--threads 8]"
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

val sharedAi = rootProject.file("core/ai/src/main/kotlin/com/melanta/core/ai")

sourceSets.main {
    kotlin.srcDirs("src/main/kotlin", "$sharedAi/prompt", "$sharedAi/tool", "$sharedAi/eval")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

application {
    mainClass = "com.melanta.tools.bench.ModelBenchKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    maxHeapSize = "2g"
}

dependencies {
    implementation(libs.litertlm.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
}
