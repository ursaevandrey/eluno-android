plugins { alias(libs.plugins.android.library) }

android {
    namespace = "ru.eluno.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
