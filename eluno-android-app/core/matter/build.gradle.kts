plugins { alias(libs.plugins.android.library) }

android {
    namespace = "ru.eluno.core.matter"
    compileSdk = 37
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "MATTER_VENDOR_ID", providers.gradleProperty("matterVendorId").getOrElse("65524"))
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:chip"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(project(":core:chip"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    implementation(libs.coroutines.core)
}
