plugins {
    alias(libs.plugins.melanta.android.library)
    alias(libs.plugins.melanta.android.compose)
    alias(libs.plugins.melanta.hilt)
}

android {
    namespace = "com.melanta.feature.agent"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.ai)
    implementation(projects.core.automation)
    implementation(projects.core.designsystem)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
}
