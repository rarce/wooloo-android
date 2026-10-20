plugins {
    jacoco
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    val releaseSmoke = providers.gradleProperty("releaseSmoke").isPresent
    // `-PappVersion=X.Y.Z` (the release tag) marks a published build; the version code grows with it.
    val publishedVersion = providers.gradleProperty("appVersion").orNull
    val appVersion = publishedVersion ?: "0.1.0"
    val (major, minor, patch) = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""").matchEntire(appVersion)?.destructured
        ?: error("appVersion must look like 1.2.3, got $appVersion")
    val majorNumber = major.toLongOrNull() ?: error("appVersion major exceeds Long")
    val minorNumber = minor.toLongOrNull() ?: error("appVersion minor exceeds Long")
    val patchNumber = patch.toLongOrNull() ?: error("appVersion patch exceeds Long")
    require(majorNumber <= Int.MAX_VALUE / 1_000_000L && minorNumber <= 999 && patchNumber <= 999) {
        "appVersion exceeds versionCode range or minor/patch 999"
    }
    val releaseVersionCode = majorNumber * 1_000_000L + minorNumber * 1_000L + patchNumber
    require(releaseVersionCode in 1..Int.MAX_VALUE.toLong()) { "versionCode must be a positive Int" }
    testBuildType = if (releaseSmoke) "release" else "debug"
    sourceSets.getByName("androidTest").java.setSrcDirs(
        if (releaseSmoke) listOf("src/releaseSmokeTest/java")
        else listOf("src/androidTest/java", "src/releaseSmokeTest/java"),
    )
    // Preserve the installed app's identity across the wooloo mobile rename.
    namespace = "dev.herdroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.herdroid"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode.toInt()
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // `-PupdateFeedUrl=https://…` points a build at another feed, to try updates before publishing.
        val updateFeedUrl = providers.gradleProperty("updateFeedUrl")
            .getOrElse("https://github.com/rarce/herdroid/releases/latest/download/update.json")
        buildConfigField("String", "UPDATE_FEED_URL", "\"$updateFeedUrl\"")
        buildConfigField("boolean", "UPDATES", "false")
    }

    buildTypes {
        release {
            if (releaseSmoke) {
                signingConfig = signingConfigs.getByName("debug")
                proguardFiles("release-smoke-rules.pro")
            } else if (publishedVersion != null) {
                // Only published releases (signed by .github/workflows/release.yml) update themselves:
                // any other build would offer a release that Android can't install over it.
                buildConfigField("boolean", "UPDATES", "true")
            }
            testProguardFiles("release-test-rules.pro")
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // `./gradlew testDebugUnitTest -Pscreenshots` records UI screenshots to docs/screenshots.
                test.systemProperty("roborazzi.test.record", "true")
                test.systemProperty("herdroid.screenshots", project.hasProperty("screenshots").toString())
                test.systemProperty("herdroid.screenshotDir", rootProject.file("docs/screenshots").absolutePath)
                // Optional: point Robolectric at pre-downloaded android-all jars (offline CI).
                project.findProperty("robolectricDeps")?.let { dir ->
                    test.systemProperty("robolectric.offline", "true")
                    test.systemProperty("robolectric.dependency.dir", dir.toString())
                }
            }
        }
    }

    packaging {
        resources {
            // BC modules ship their license at the same path. Preserve all notices.
            merges += "META-INF/LICENSE.md"
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/BCKEY.DSA",
                "META-INF/BCKEY.SF",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    // Match AndroidX Test's target dependency; AGP shares these classes across both APKs.
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // Bundle the QR decoder so scanning works immediately, including offline.
    implementation(libs.mlkit.barcode.scanning)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

jacoco { toolVersion = "0.8.13" }

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val coverageClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/*\$serializer.class")
}
val coverageReport = tasks.register<JacocoReport>("jacocoDebugReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(coverageClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}

// Keep the overall baseline visible, with a stronger floor for protocol/storage/terminal logic.
val coreCoverageClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("dev/herdroid/herdr/**", "dev/herdroid/data/**", "dev/herdroid/ssh/**",
        "dev/herdroid/connection/**", "dev/herdroid/terminal/**", "dev/herdroid/update/**")
    exclude("**/*\$serializer.class")
}
tasks.register<JacocoCoverageVerification>("jacocoDebugCoverageVerification") {
    dependsOn(coverageReport)
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(coreCoverageClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    violationRules {
        rule { limit { counter = "LINE"; minimum = "0.70".toBigDecimal() } }
    }
}
tasks.register<JacocoCoverageVerification>("paneMetricsCoverageVerification") {
    dependsOn(coverageReport)
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        include("dev/herdroid/ui/terminal/PaneMetricsModelKt*.class")
    })
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.90".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.80".toBigDecimal() }
        }
    }
}
