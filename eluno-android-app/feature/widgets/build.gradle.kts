plugins { alias(libs.plugins.android.library) }

android {
    namespace = "ru.eluno.feature.widgets"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.coroutines.core)
}
