plugins {
    alias(libs.plugins.melanta.android.library)
    alias(libs.plugins.melanta.android.compose)
}

android {
    namespace = "com.melanta.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material.icons.core)
}
