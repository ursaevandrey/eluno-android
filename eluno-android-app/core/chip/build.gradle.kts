import java.security.MessageDigest
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

plugins { alias(libs.plugins.android.library) }

// Imported SDK sources are generated build inputs, like the upstream JARs. Lint
// checks Eluno's adapter; it does not re-audit the third-party generated clusters.
abstract class ImportChipSources : DefaultTask() {
    @get:Internal abstract val sourceDirectory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    val sdkSources: FileTree get() = sourceDirectory.asFileTree.matching {
        include("src/controller/java/src/chip/**/*.java", "src/controller/java/src/matter/tlv/**/*.kt",
            "src/controller/java/src/matter/jsontlv/**/*.kt", "src/controller/java/src/matter/onboardingpayload/**/*.kt",
            "src/controller/java/generated/java/**/*.java", "src/platform/android/java/**/*.java")
        exclude("**/BatchInvokeCallback.java", "**/BatchInvokeCallbackJni.java", "**/GetConnectedDeviceCallbackForTestJni.java")
    }
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations
    @TaskAction fun copySources() {
        files.sync {
            into(outputDirectory)
            from(sourceDirectory.dir("src/controller/java/src")) {
                include("chip/**/*.java", "matter/tlv/**/*.kt", "matter/jsontlv/**/*.kt", "matter/onboardingpayload/**/*.kt")
                exclude("**/BatchInvokeCallback.java", "**/BatchInvokeCallbackJni.java", "**/GetConnectedDeviceCallbackForTestJni.java")
            }
            from(sourceDirectory.dir("src/controller/java/generated/java")) { include("**/*.java") }
            from(sourceDirectory.dir("src/platform/android/java")) { include("**/*.java") }
        }
    }
}
val importChipSources = tasks.register<ImportChipSources>("importChipSources") {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir(".matter-sdk/connectedhomeip"))
    outputDirectory.set(layout.buildDirectory.dir("generated/chip"))
}
android {
    namespace = "ru.eluno.core.chip"
    compileSdk = 37
    defaultConfig { minSdk = 28; consumerProguardFiles("consumer-rules.pro") }
    sourceSets["main"].apply {
        jniLibs.srcDir(rootProject.file(".matter-sdk/jniLibs"))
        assets.srcDir(rootProject.file(".matter-sdk/assets"))
    }
    lint { checkGeneratedSources = false }
}
androidComponents.onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(importChipSources) { it.outputDirectory }
    variant.sources.kotlin?.addGeneratedSourceDirectory(importChipSources) { it.outputDirectory }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.google.code.gson:gson:2.9.1")
    implementation("com.google.protobuf:protobuf-java:3.22.0")
}

val verifyMatterSdk = tasks.register("verifyMatterSdk") {
    doLast {
        val revision = rootProject.file(".matter-sdk/revision")
        check(revision.exists() && revision.readText().trim() == "3bcdd56ba54fb88b2afb4bfef575014671df7aa7") {
            "Сначала выполните scripts/build-matter-sdk.sh: требуется connectedhomeip v1.6.1.0 с поправками Eluno"
        }
        val patch = rootProject.file("patches/connectedhomeip-eluno.patch")
        val hash = MessageDigest.getInstance("SHA-256").digest(patch.readBytes()).joinToString("") { "%02x".format(it) }
        check(rootProject.file(".matter-sdk/patch-sha256").readText().trim() == hash) { "Пересоберите Matter SDK после изменения патча" }
        listOf("libCHIPController.so", "libc++_shared.so").forEach {
            check(rootProject.file(".matter-sdk/jniLibs/arm64-v8a/$it").isFile) { "Не найдена нативная библиотека $it" }
        }
        check(rootProject.file(".matter-sdk/assets/paa").listFiles().orEmpty().any { it.extension == "der" }) { "Отсутствуют production PAA" }
    }
}
tasks.named("preBuild") { dependsOn(verifyMatterSdk) }
importChipSources { dependsOn(verifyMatterSdk) }
