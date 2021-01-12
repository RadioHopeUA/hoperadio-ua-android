import com.android.builder.signing.DefaultSigningConfig
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.*
import kotlin.collections.mutableMapOf

plugins {
    id("com.android.application")
    id("com.google.firebase.crashlytics")
    id("com.google.gms.google-services")
    kotlin("android")
    kotlin("kapt")
    id("koin")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjvm-default=all", "-Xopt-in=kotlin.RequiresOptIn")
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

android {
    compileSdkVersion(30)
    buildToolsVersion("30.0.3")

    defaultConfig {
        applicationId = "ua.hope.radio"
        minSdkVersion(21)
        targetSdkVersion(30)
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
            firebaseCrashlytics.mappingFileUploadEnabled = false
            addManifestPlaceholders(mutableMapOf<String, Any>("applicationLabel" to "@string/app_name_dev"))
        }

        getByName("release") {
            isMinifyEnabled = true
            isZipAlignEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            addManifestPlaceholders(mutableMapOf<String, Any>("applicationLabel" to "@string/app_name"))
        }
    }

    flavorDimensions("version")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        viewBinding = true
    }

    packagingOptions {
        exclude("META-INF/*")
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.1.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation("androidx.core:core-ktx:1.3.2")
    implementation("androidx.appcompat:appcompat:1.2.0")
    implementation("androidx.media:media:1.2.1")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.2.0")
    implementation("androidx.lifecycle:lifecycle-service:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.2.0")
    implementation("com.android.support.constraint:constraint-layout:2.0.4")

    val coroutinesVersion = "1.4.2"
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    implementation("com.google.firebase:firebase-crashlytics:17.3.0")
    implementation("com.google.firebase:firebase-analytics:18.0.0")
    val okHttpVersion = "4.9.0"
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okHttpVersion")
    val exoPlayerVersion = "2.12.2"
    implementation("com.google.android.exoplayer:exoplayer-core:$exoPlayerVersion")
    implementation("com.google.android.exoplayer:exoplayer-hls:$exoPlayerVersion")
    implementation("com.google.android.exoplayer:exoplayer-ui:$exoPlayerVersion")

    implementation("com.jakewharton.timber:timber:4.7.1")

    val koinVersion = "2.2.2"
    implementation("org.koin:koin-androidx-scope:$koinVersion")
    implementation("org.koin:koin-androidx-viewmodel:$koinVersion")

    testImplementation("junit:junit:4.13.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.3.0")
}

fun loadSigningConfig(propertiesPath: String, config: DefaultSigningConfig) {
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
