plugins {
    alias(libs.plugins.melanta.android.application)
    alias(libs.plugins.melanta.android.compose)
    alias(libs.plugins.melanta.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.melanta"

    defaultConfig {
        applicationId = "com.melanta"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.ai)
    implementation(projects.core.automation)
    implementation(projects.feature.agent)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)
}
