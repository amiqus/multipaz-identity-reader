import org.gradle.kotlin.dsl.implementation
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.ksp)
}


val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

buildConfig {
    packageName("org.multipaz.identityreader")
    buildConfigField("VERSION", projectVersionName)
    // Amiqus: no backend by default, so the app never contacts Multipaz's servers. Reader auth uses
    // an imported PKCS#12 reader certificate instead. Set IDENTITY_READER_BACKEND_URL (and
    // IDENTITY_READER_BACKEND_CLIENT_ID for Google sign-in) to re-enable the backend features.
    buildConfigField("IDENTITY_READER_BACKEND_URL",
        System.getenv("IDENTITY_READER_BACKEND_URL") ?: ""
    )
    buildConfigField("IDENTITY_READER_BACKEND_CLIENT_ID",
        System.getenv("IDENTITY_READER_BACKEND_CLIENT_ID") ?: ""
    )
    buildConfigField("IDENTITY_READER_UPDATE_URL",
        System.getenv("IDENTITY_READER_UPDATE_URL") ?: ""
    )
    buildConfigField("IDENTITY_READER_UPDATE_WEBSITE_URL",
        System.getenv("IDENTITY_READER_UPDATE_WEBSITE_URL") ?: ""
    )
    buildConfigField("IDENTITY_READER_REQUIRE_TOS_ACCEPTANCE",
        System.getenv("IDENTITY_READER_REQUIRE_TOS_ACCEPTANCE") != null
    )
    useKotlinOutput { internalVisibility = false }
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    compilerOptions {
        allWarningsAsErrors = true
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    sourceSets {

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.fragment)
            implementation(libs.ktor.client.android)

            implementation(libs.androidx.credentials)
            implementation(libs.play.services.auth)
            implementation(libs.identity.googleid)
        }
        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                implementation(project(":libfrontend"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)

                implementation(libs.multipaz)
                implementation(libs.multipaz.compose)
                implementation(libs.multipaz.doctypes)

                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.bytestring)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.network)
                implementation(libs.compottie)
                implementation(libs.semver)
                implementation(libs.bundles.nav3)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.cio)
                implementation(libs.androidx.testExt.junit)
                implementation(libs.androidx.espresso.core)
                implementation(project(":libbackend"))
            }
        }
    }
}

android {
    namespace = "org.multipaz.identityreader"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.amiqus.demoverifier"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = projectVersionCode
        versionName = projectVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // Amiqus: sign with the Amiqus key instead of the public upstream devkey.keystore. The keystore
    // and its passwords live outside the repo, set as Gradle properties (e.g. in
    // ~/.gradle/gradle.properties). Without them, debug builds use the local Android debug key and
    // release builds are left unsigned.
    val amiqusStoreFile = providers.gradleProperty("amiqusSigningStoreFile").orNull
    val amiqusSigning = amiqusStoreFile?.let {
        signingConfigs.create("amiqus") {
            storeFile = file(it)
            storePassword = providers.gradleProperty("amiqusSigningStorePassword").get()
            keyAlias = providers.gradleProperty("amiqusSigningKeyAlias").get()
            keyPassword = providers.gradleProperty("amiqusSigningKeyPassword").get()
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = amiqusSigning
        }
        getByName("debug") {
            if (amiqusSigning != null) {
                signingConfig = amiqusSigning
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
    add("kspCommonMainMetadata", libs.multipaz.cbor.rpc)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

tasks["compileKotlinIosX64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosArm64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosSimulatorArm64"].dependsOn("kspCommonMainKotlinMetadata")
