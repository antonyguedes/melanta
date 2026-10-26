plugins {
    alias(libs.plugins.melanta.android.library)
    alias(libs.plugins.melanta.hilt)
}

android {
    namespace = "com.melanta.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.android)
}
