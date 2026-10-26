plugins {
    `kotlin-dsl`
}

group = "com.melanta.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.melanta.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.melanta.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.melanta.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("hilt") {
            id = libs.plugins.melanta.hilt.get().pluginId
            implementationClass = "HiltConventionPlugin"
        }
    }
}
