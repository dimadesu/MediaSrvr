plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dimadesu.mediasrvr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dimadesu.mediasrvr"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

// Task: decode ASCII-hex golden files placed under src/main/assets/golden/*.hex into
// binary files under build/generated/assets/golden-bin/ so the app can include binary
// goldens and avoid runtime decoding.
val generatedGoldenDir = file("${buildDir.path}/generated/assets/golden-bin")
val generateBinaryGoldenAssets = tasks.register("generateBinaryGoldenAssets") {
    val srcDir = file("src/main/assets/golden")
    inputs.dir(srcDir)
    outputs.dir(generatedGoldenDir)
    doLast {
        if (!srcDir.exists()) return@doLast
        if (!generatedGoldenDir.exists()) generatedGoldenDir.mkdirs()
        srcDir.listFiles()?.filter { it.isFile && it.extension == "hex" }?.forEach { f ->
            try {
                val text = f.readText(Charsets.UTF_8).trim()
                val hex = text.replace(Regex("\\s+"), "")
                if (!hex.matches(Regex("^[0-9a-fA-F]*$"))) {
                    logger.lifecycle("Skipping non-hex file: ${f.name}")
                    return@forEach
                }
                val evenHex = if (hex.length % 2 != 0) "0$hex" else hex
                val out = ByteArray(evenHex.length / 2)
                var j = 0
                for (i in evenHex.indices step 2) {
                    val pair = evenHex.substring(i, i + 2)
                    out[j++] = Integer.parseInt(pair, 16).toByte()
                }
                val outFile = File(generatedGoldenDir, f.nameWithoutExtension + ".bin")
                outFile.writeBytes(out)
                logger.lifecycle("Generated binary golden: ${outFile.absolutePath}")
            } catch (e: Exception) {
                logger.warn("Error converting ${f.name}: ${e.message}")
            }
        }
    }
}

// Add generated assets dir to main source set so it's packaged into the APK
android.sourceSets["main"].assets.srcDir(generatedGoldenDir)

// Ensure asset merging depends on generation task
// Hook generateBinaryGoldenAssets into any asset-merge tasks (robust across AGP versions)
tasks.matching { t ->
    val n = t.name.toLowerCase()
    n.contains("merge") && n.contains("assets")
}.configureEach { dependsOn(generateBinaryGoldenAssets) }

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}