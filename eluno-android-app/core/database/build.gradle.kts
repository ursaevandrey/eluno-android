plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    namespace = "ru.eluno.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
}

room3 { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.room3.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    ksp(libs.room3.compiler)
    testImplementation(libs.junit)
}
