import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

val versionProperties = Properties().apply {
    val versionPropertiesFile = rootProject.file("version.properties")
    if (versionPropertiesFile.exists()) {
        versionPropertiesFile.inputStream().use(::load)
    }
}

fun localPropertyOrEnv(propertyName: String, envName: String): String? {
    val propertyValue = localProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
    return propertyValue ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
}

val releaseKeystoreFile = localPropertyOrEnv("releaseKeystoreFile", "RELEASE_KEYSTORE_FILE")
val releaseStorePassword = localPropertyOrEnv("releaseStorePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localPropertyOrEnv("releaseKeyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = localPropertyOrEnv("releaseKeyPassword", "RELEASE_KEY_PASSWORD")
val playServiceAccountFile = localPropertyOrEnv("playServiceAccountFile", "PLAY_SERVICE_ACCOUNT_FILE")
val fileVersionCode = versionProperties.getProperty("VERSION_CODE")?.toIntOrNull() ?: 1
val fileVersionName = versionProperties.getProperty("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
val ciVersionCode = providers.gradleProperty("ciVersionCode").orNull?.toIntOrNull() ?: fileVersionCode
val ciVersionName = providers.gradleProperty("ciVersionName").orNull ?: fileVersionName
val pythonBinary = providers.gradleProperty("pythonBinary").orNull?.takeIf { it.isNotBlank() } ?: "python3"
val hasReleaseSigning = listOf(
    releaseKeystoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val runtimeAssetsDirectory = layout.buildDirectory.dir("generated/runtime-assets/main")
val prepackagedDatabaseAssetName = "descente_canyon_prepackaged.db"
val staticFeatureIndexAssetName = "canyon_static_features.index.json"
val runtimeLookupCoreAssetName = "runtime_feature_lookups.core.json"
val runtimeLookupCanyonsAssetName = "runtime_feature_lookups.canyons.json"
val runtimeLookupIndexAssetName = "runtime_feature_lookups.canyons.index.json"
val generatedPrepackagedDatabaseFile = layout.buildDirectory.file("generated/prepackaged-room-db/$prepackagedDatabaseAssetName")
val prepackagedDatabaseGeneratorScript = rootProject.file("scripts/app/generate_prepackaged_room_db.py")
val staticFeatureIndexGeneratorScript = rootProject.file("scripts/app/generate_static_feature_index.py")
val runtimeLookupAssetGeneratorScript = rootProject.file("scripts/app/generate_runtime_lookup_assets.py")
val prepackagedDatabaseSchemaFile = project.file("schemas/fr.descentecanyon.app.data.local.database.DescenteCanyonDatabase/15.json")
val prepackagedRoomImportDirectory = rootProject.file("offline-data/full/room-import")
val staticFeaturesAssetFile = rootProject.file("modele_statistique/canyon_static_features.json")
val runtimeLookupsAssetFile = rootProject.file("modele_statistique/runtime_feature_lookups.json")
val generatedStaticFeatureIndexFile = layout.buildDirectory.file("generated/ml-indexes/$staticFeatureIndexAssetName")
val generatedRuntimeLookupCoreFile = layout.buildDirectory.file("generated/ml-indexes/$runtimeLookupCoreAssetName")
val generatedRuntimeLookupCanyonsFile = layout.buildDirectory.file("generated/ml-indexes/$runtimeLookupCanyonsAssetName")
val generatedRuntimeLookupIndexFile = layout.buildDirectory.file("generated/ml-indexes/$runtimeLookupIndexAssetName")

val generatePrepackagedRoomDatabase = tasks.register<Exec>("generatePrepackagedRoomDatabase") {
    group = "build"
    description = "Generates a prototype prepackaged Room database from room-import JSON."
    inputs.file(prepackagedDatabaseGeneratorScript)
    inputs.file(prepackagedDatabaseSchemaFile)
    inputs.dir(prepackagedRoomImportDirectory)
    outputs.file(generatedPrepackagedDatabaseFile)

    commandLine(
        pythonBinary,
        prepackagedDatabaseGeneratorScript.path,
        "--room-import-dir",
        prepackagedRoomImportDirectory.path,
        "--schema",
        prepackagedDatabaseSchemaFile.path,
        "--output",
        generatedPrepackagedDatabaseFile.get().asFile.path,
    )
}

val generateStaticFeatureIndex = tasks.register<Exec>("generateStaticFeatureIndex") {
    group = "build"
    description = "Generates a byte-offset index for on-demand static feature loading."
    inputs.file(staticFeatureIndexGeneratorScript)
    inputs.file(staticFeaturesAssetFile)
    outputs.file(generatedStaticFeatureIndexFile)

    commandLine(
        pythonBinary,
        staticFeatureIndexGeneratorScript.path,
        "--input",
        staticFeaturesAssetFile.path,
        "--output",
        generatedStaticFeatureIndexFile.get().asFile.path,
    )
}

val generateRuntimeLookupAssets = tasks.register<Exec>("generateRuntimeLookupAssets") {
    group = "build"
    description = "Generates split runtime lookup assets for on-demand canyon loading."
    inputs.file(runtimeLookupAssetGeneratorScript)
    inputs.file(runtimeLookupsAssetFile)
    outputs.files(
        generatedRuntimeLookupCoreFile,
        generatedRuntimeLookupCanyonsFile,
        generatedRuntimeLookupIndexFile,
    )

    commandLine(
        pythonBinary,
        runtimeLookupAssetGeneratorScript.path,
        "--input",
        runtimeLookupsAssetFile.path,
        "--output-core",
        generatedRuntimeLookupCoreFile.get().asFile.path,
        "--output-canyons",
        generatedRuntimeLookupCanyonsFile.get().asFile.path,
        "--output-index",
        generatedRuntimeLookupIndexFile.get().asFile.path,
    )
}

val syncRuntimeAssets = tasks.register<Sync>("syncRuntimeAssets") {
    group = "build"
    description = "Stages the exact runtime assets packaged into the Android app."
    into(runtimeAssetsDirectory)
    dependsOn(generatePrepackagedRoomDatabase, generateStaticFeatureIndex, generateRuntimeLookupAssets)

    from("src/main/assets")
    from("../offline-data/full/room-import") {
        include("manifest.json")
    }
    from(generatedPrepackagedDatabaseFile) {
        into("databases")
    }
    from(generatedStaticFeatureIndexFile)
    from(generatedRuntimeLookupCoreFile)
    from(generatedRuntimeLookupCanyonsFile)
    from(generatedRuntimeLookupIndexFile)
    from("../modele_statistique") {
        exclude(
            "runtime_feature_lookups.json",
            "runtime-lookups/**",
            "high_risk_overlay_feature_spec.json",
            "high_risk_overlay_metrics.json",
        )
    }
}

data class ReleasePackageEntry(
    val path: String,
    val bytes: Long,
)

data class ReleasePackageInspection(
    val label: String,
    val file: File,
    val packageBytes: Long,
    val assetBytes: Long,
    val topEntries: List<ReleasePackageEntry>,
)

fun inspectReleasePackage(label: String, file: File, assetPathPrefix: String): ReleasePackageInspection {
    require(file.exists()) { "$label package not found: ${file.path}" }

    ZipFile(file).use { zipFile ->
        val entries = zipFile.entries().asSequence()
            .filterNot { it.isDirectory }
            .map { entry ->
                ReleasePackageEntry(
                    path = entry.name,
                    bytes = entry.size.takeIf { it >= 0 } ?: entry.compressedSize.takeIf { it >= 0 } ?: 0L,
                )
            }
            .sortedByDescending { it.bytes }
            .toList()

        return ReleasePackageInspection(
            label = label,
            file = file,
            packageBytes = file.length(),
            assetBytes = entries.filter { it.path.startsWith(assetPathPrefix) }.sumOf { it.bytes },
            topEntries = entries.take(100),
        )
    }
}

fun Long.toDisplayMegabytes(): String = "%.2f MB".format(this / (1024.0 * 1024.0))

fun buildReleasePackageReport(inspections: List<ReleasePackageInspection>): String {
    return buildString {
        inspections.forEach { inspection ->
            appendLine("${inspection.label}: ${inspection.file.name}")
            appendLine("Package size: ${inspection.packageBytes.toDisplayMegabytes()}")
            appendLine("Packaged assets: ${inspection.assetBytes.toDisplayMegabytes()}")
            appendLine("Top 100 packaged files:")
            inspection.topEntries.forEachIndexed { index, entry ->
                appendLine("${index + 1}. ${entry.bytes.toDisplayMegabytes()}  ${entry.path}")
            }
            appendLine()
        }
    }
}

android {
    namespace = "fr.descentecanyon.app"
    compileSdk = 36

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseKeystoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.clear()
            assets.directories.add(runtimeAssetsDirectory.get().asFile.path)
        }
    }

    defaultConfig {
        applicationId = "fr.descentecanyon.app"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName

        testInstrumentationRunner = "fr.descentecanyon.app.e2e.runner.HiltTestRunner"
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("minifiedDebug") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            matchingFallbacks += listOf("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(syncRuntimeAssets)
}

val releaseAabFile = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
val releaseApkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val releaseSizeReportFile = layout.buildDirectory.file("reports/release-size/release-package-report.txt")

tasks.register("reportReleasePackageSizes") {
    group = "verification"
    description = "Builds release artifacts and reports the largest packaged files."
    dependsOn("bundleRelease", "assembleRelease")
    outputs.file(releaseSizeReportFile)

    doLast {
        val inspections = listOf(
            inspectReleasePackage(
                label = "Release AAB",
                file = releaseAabFile.get().asFile,
                assetPathPrefix = "base/assets/",
            ),
            inspectReleasePackage(
                label = "Release APK",
                file = releaseApkFile.get().asFile,
                assetPathPrefix = "assets/",
            ),
        )

        val report = buildReleasePackageReport(inspections)
        val outputFile = releaseSizeReportFile.get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(report)

        logger.lifecycle(report)
        logger.lifecycle("Release size report written to ${outputFile.relativeTo(rootDir)}")
    }
}

tasks.register("verifyReleaseSizeBudget") {
    group = "verification"
    description = "Fails when release package sizes exceed configured budgets."
    dependsOn("reportReleasePackageSizes")

    doLast {
        val aabBudgetBytes = ((providers.gradleProperty("releaseBudgetAabMb").orNull?.toLongOrNull() ?: 100L) * 1024 * 1024)
        val apkBudgetBytes = ((providers.gradleProperty("releaseBudgetApkMb").orNull?.toLongOrNull() ?: 155L) * 1024 * 1024)
        val assetBudgetBytes = ((providers.gradleProperty("releaseBudgetAssetsMb").orNull?.toLongOrNull() ?: 225L) * 1024 * 1024)

        val inspections = listOf(
            inspectReleasePackage(
                label = "Release AAB",
                file = releaseAabFile.get().asFile,
                assetPathPrefix = "base/assets/",
            ),
            inspectReleasePackage(
                label = "Release APK",
                file = releaseApkFile.get().asFile,
                assetPathPrefix = "assets/",
            ),
        )

        val failures = buildList {
            inspections.forEach { inspection ->
                val packageBudgetBytes = when (inspection.label) {
                    "Release AAB" -> aabBudgetBytes
                    "Release APK" -> apkBudgetBytes
                    else -> Long.MAX_VALUE
                }
                if (inspection.packageBytes > packageBudgetBytes) {
                    add(
                        "${inspection.label} size ${inspection.packageBytes.toDisplayMegabytes()} exceeds budget ${packageBudgetBytes.toDisplayMegabytes()}"
                    )
                }
                if (inspection.assetBytes > assetBudgetBytes) {
                    add(
                        "${inspection.label} assets ${inspection.assetBytes.toDisplayMegabytes()} exceed budget ${assetBudgetBytes.toDisplayMegabytes()}"
                    )
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(failures.joinToString(separator = "\n", prefix = "Release size budget check failed:\n"))
        }
    }
}

play {
    defaultToAppBundles.set(true)
    track.set(providers.gradleProperty("playTrack").orElse("internal"))

    if (!playServiceAccountFile.isNullOrBlank()) {
        serviceAccountCredentials.set(layout.projectDirectory.file(playServiceAccountFile))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room (local database - offline storage)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore (preferences)
    implementation(libs.androidx.datastore.preferences)

    // WorkManager (periodic background sync)
    implementation(libs.androidx.work.runtime.ktx)

    // Security (encrypted credential storage)
    implementation(libs.androidx.security.crypto)

    // Hilt (dependency injection)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // JSoup (HTML parsing)
    implementation(libs.jsoup)

    // Ktor (HTTP client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // On-device ML inference
    implementation(libs.onnxruntime.android)

    // MapLibre (offline maps)
    implementation(libs.maplibre)
    implementation(libs.play.services.location)

    // Coil (image loading with caching)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}
