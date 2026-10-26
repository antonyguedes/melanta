plugins {
    alias(libs.plugins.melanta.android.library)
    alias(libs.plugins.melanta.hilt)
}

android {
    namespace = "com.melanta.core.automation"
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
}
