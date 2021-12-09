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
    compileSdk = 31
    buildToolsVersion = "31.0.0"

    defaultConfig {
        applicationId = "ua.hope.radio"
        minSdk = 21
        targetSdk = 31
        versionCode = 16
        versionName = "1.2.0"
        multiDexEnabled = true
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

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.1.5")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.0")
    implementation("androidx.media:media:1.4.3")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.2")
    val lifecycleVersion = "2.4.0"
    implementation("androidx.lifecycle:lifecycle-livedata-core-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")

    val coroutinesVersion = "1.5.2"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    implementation(platform("com.google.firebase:firebase-bom:29.0.1"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    val okHttpVersion = "4.9.3"
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okHttpVersion")

    val exoPlayerVersion = "2.16.1"
    implementation("com.google.android.exoplayer:exoplayer-core:$exoPlayerVersion")
    implementation("com.google.android.exoplayer:exoplayer-hls:$exoPlayerVersion")
    implementation("com.google.android.exoplayer:exoplayer-ui:$exoPlayerVersion")

    implementation("com.jakewharton.timber:timber:5.0.1")

    val koinVersion = "3.1.4"
    implementation("io.insert-koin:koin-core:$koinVersion")
    implementation("io.insert-koin:koin-android:$koinVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
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
