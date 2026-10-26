plugins {
    alias(libs.plugins.melanta.android.library)
    alias(libs.plugins.melanta.hilt)
}

android {
    namespace = "com.melanta.core.ai"
}

dependencies {
    implementation(projects.core.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.litertlm.android)
    api(libs.kotlinx.serialization.json)
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
