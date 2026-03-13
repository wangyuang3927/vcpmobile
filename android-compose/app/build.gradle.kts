import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.vcp.mobile"
    compileSdk = 35

    val signingProperties = Properties().apply {
        val signingFile = rootProject.file("keystore.properties")
        if (signingFile.exists()) {
            signingFile.inputStream().use { load(it) }
        }
    }
    val releaseStoreFile = (findProperty("VCP_RELEASE_STORE_FILE") as String?)
        ?: signingProperties.getProperty("storeFile")
    val releaseStorePassword = (findProperty("VCP_RELEASE_STORE_PASSWORD") as String?)
        ?: signingProperties.getProperty("storePassword")
    val releaseKeyAlias = (findProperty("VCP_RELEASE_KEY_ALIAS") as String?)
        ?: signingProperties.getProperty("keyAlias")
    val releaseKeyPassword = (findProperty("VCP_RELEASE_KEY_PASSWORD") as String?)
        ?: signingProperties.getProperty("keyPassword")
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }
    val releaseVersionCode = ((findProperty("VCP_RELEASE_VERSION_CODE") as String?)
        ?.toIntOrNull()) ?: 1
    val releaseVersionName = (findProperty("VCP_RELEASE_VERSION_NAME") as String?)
        ?: "1.0"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.vcp.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "HUB_BASE_URL", "\"http://10.0.2.2:4001\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "HUB_BASE_URL", "\"http://10.0.2.2:4001\"")
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { testTask ->
                val robolectricHome = layout.buildDirectory.dir("robolectric-home").get().asFile
                testTask.systemProperty("user.home", robolectricHome.absolutePath)
                testTask.doFirst {
                    robolectricHome.mkdirs()
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    implementation(libs.hilt.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.13")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt {
    correctErrorTypes = true
}
