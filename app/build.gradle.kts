import com.android.build.api.dsl.ApkSigningConfig
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.*
import kotlin.collections.mutableMapOf

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
}

android {
    compileSdk = 33
    buildToolsVersion = "33.0.2"
    namespace = "ua.hope.radio"

    defaultConfig {
        applicationId = "ua.hope.radio"
        minSdk = 23
        targetSdk = 33
        versionCode = 18
        versionName = "1.2.2"
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("hopefm") {
            loadSigningConfig("signing/hopefm/HopeFM.properties", this)
        }
        create("golosnadii") {
            loadSigningConfig("signing/golosnadii/GolosNadii.properties", this)
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            addManifestPlaceholders(mutableMapOf<String, Any>("applicationLabel" to "@string/app_name_dev"))
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            addManifestPlaceholders(mutableMapOf<String, Any>("applicationLabel" to "@string/app_name"))
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("hopeFm") {
            dimension = "version"
            applicationIdSuffix = ".hopefm"
            signingConfig = signingConfigs.getByName("hopefm")
        }
        create("golosNadii") {
            dimension = "version"
            applicationIdSuffix = ".golosnadii"
            signingConfig = signingConfigs.getByName("golosnadii")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }

    packagingOptions {
        resources.excludes.add("META-INF/*")
    }

    applicationVariants.all {
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "${project.name}-$name-$versionName.$versionCode.apk"
        }
    }

    sourceSets {
        getByName("main").java.srcDir("$projectDir/src/main/kotlin")
        getByName("golosNadii").java.srcDir("$projectDir/src/golosNadii/kotlin")
        getByName("hopeFm").java.srcDir("$projectDir/src/hopeFm/kotlin")
        getByName("test").java.srcDir("$projectDir/src/test/kotlin")
        getByName("androidTest").java.srcDir("$projectDir/src/androidTest/kotlin")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events = mutableSetOf(
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation("androidx.core:core-ktx:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val lifecycleVersion = "2.6.1"
    implementation("androidx.lifecycle:lifecycle-livedata-core-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")

    val coroutinesVersion = "1.6.4"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    implementation(platform("com.google.firebase:firebase-bom:31.4.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-perf-ktx")

    val okHttpVersion = "4.10.0"
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okHttpVersion")

    val media3Version = "1.0.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation("com.jakewharton.timber:timber:5.0.1")

    val koinVersion = "3.4.0"
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("io.insert-koin:koin-android:$koinVersion")

    // Dependencies for local unit tests
    val junitVersion = "5.9.2"
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("io.mockk:mockk:1.13.4")

    // Dependencies for Android tests
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

fun loadSigningConfig(propertiesPath: String, config: ApkSigningConfig) {
    val props = Properties()
    val propertiesFile = project.file(propertiesPath)
    if (propertiesFile.exists()) {
        props.load(FileInputStream(propertiesFile))
    } else {
        println("Can't find signing config file $propertiesFile")
    }
    props["keystore"]?.let {
        config.storeFile = file(props["keystore"] as String)
        config.storePassword = props["keystore.password"] as String
        config.keyAlias = props["keyAlias"] as String
        config.keyPassword = props["keyPassword"] as String
    }
}
