plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ru.eluno.feature.commissioning"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.zxing.embedded)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test>().configureEach {
    val qrImage = providers.environmentVariable("ELUNO_QR_TEST_IMAGE").orNull
    inputs.property("hasLocalQrRegressionImage", qrImage != null)
    if (qrImage != null) inputs.file(qrImage).withPropertyName("localQrRegressionImage")
}
