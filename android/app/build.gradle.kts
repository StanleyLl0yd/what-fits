import java.net.URI
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.whatfits"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.whatfits"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.0.10"

        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main").assets.directories.add(file("../../data").absolutePath)
    sourceSets.getByName("main").assets.directories.add(
        layout.buildDirectory.get().dir("generated/tessdataAssets").asFile.absolutePath,
    )
    sourceSets.getByName("main").assets.directories.add(
        layout.buildDirectory.get().dir("generated/ppocrAssets").asFile.absolutePath,
    )

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")

    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation(files("libs/ppocr-sdk-release.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    implementation("com.quickbirdstudios:opencv:4.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20251224")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

val tessdataUrl =
    "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/" +
        "65727574dfcd264acbb0c3e07860e4e9e9b22185/eng.traineddata"
val tessdataSha256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
val tessdataFile = layout.buildDirectory.file("generated/tessdataAssets/tessdata/eng.traineddata")

data class PinnedOcrAsset(
    val url: String,
    val sha256: String,
    val relativePath: String,
)

val ppOcrAssets = listOf(
    PinnedOcrAsset(
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx",
        sha256 = "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
        relativePath = "models/det/inference.onnx",
    ),
    PinnedOcrAsset(
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx",
        sha256 = "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
        relativePath = "models/rec/inference.onnx",
    ),
    PinnedOcrAsset(
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml",
        sha256 = "66170210bad538e83fff3c4a3867e547d6bf20b50d64b20347c4b913f3034ea1",
        relativePath = "models/rec/inference.yml",
    ),
)
val ppOcrAssetsRoot = layout.buildDirectory.dir("generated/ppocrAssets")

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

val prepareTessdata by tasks.registering {
    description = "Downloads the pinned English OCR model for offline packaging."
    outputs.file(tessdataFile)

    doLast {
        val target = tessdataFile.get().asFile
        if (target.isFile && sha256(target) == tessdataSha256) return@doLast

        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        URI.create(tessdataUrl).toURL().openStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(sha256(temporary) == tessdataSha256) { "Downloaded OCR model has an invalid SHA-256." }
        temporary.copyTo(target, overwrite = true)
        temporary.delete()
    }
}

val preparePpOcrModels by tasks.registering {
    description = "Downloads pinned PP-OCRv6 tiny models for offline packaging."
    outputs.files(ppOcrAssets.map { ppOcrAssetsRoot.map { root -> root.file(it.relativePath) } })

    doLast {
        val root = ppOcrAssetsRoot.get().asFile
        ppOcrAssets.forEach { asset ->
            val target = File(root, asset.relativePath)
            if (target.isFile && sha256(target) == asset.sha256) return@forEach

            target.parentFile.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            URI.create(asset.url).toURL().openStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(sha256(temporary) == asset.sha256) {
                "Downloaded PP-OCR asset ${asset.relativePath} has an invalid SHA-256."
            }
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareTessdata)
    dependsOn(preparePpOcrModels)
}

val forbiddenRuntimeGroups = setOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.android.billingclient",
    "com.google.mlkit",
)

tasks.register("verifyNoGoogleRuntime") {
    group = "verification"
    description = "Fails if an APK runtime classpath depends on Google Play Services or Firebase."

    doLast {
        val violations = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
            .flatMap { configurationName ->
                configurations.getByName(configurationName)
                    .incoming.resolutionResult.allComponents
                    .mapNotNull { component ->
                        val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                        if (id.group in forbiddenRuntimeGroups) {
                            "$configurationName: ${id.group}:${id.module}:${id.version}"
                        } else {
                            null
                        }
                    }
            }

        check(violations.isEmpty()) {
            "Forbidden Google runtime dependencies found:\n${violations.joinToString("\n")}"
        }
    }
}
