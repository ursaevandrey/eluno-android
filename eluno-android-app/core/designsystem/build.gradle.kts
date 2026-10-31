plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.eluno.core.designsystem"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
}

dependencies {
    constraints {
        implementation(libs.lifecycle.runtime.compose) {
            because("Keep Compose's transitive Lifecycle and its lint checks aligned with the app")
        }
    }
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
}
