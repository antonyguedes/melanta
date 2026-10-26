package com.melanta.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

object MelantaSdk {
    const val COMPILE = 37
    const val TARGET = 36
    const val MIN = 31
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Configuração compartilhada por :app e por todos os módulos de biblioteca. */
internal fun configureAndroidCommon(extension: CommonExtension) = with(extension) {
    compileSdk = MelantaSdk.COMPILE
    defaultConfig.minSdk = MelantaSdk.MIN
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
    // Os textos do app são em PT-BR; o verificador de ortografia do lint só conhece inglês.
    lint.disable += "Typos"
    // android.util.Log e afins devolvem valores padrão nos testes JVM em vez de lançar exceção.
    testOptions.unitTests.isReturnDefaultValues = true
}
