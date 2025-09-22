import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services")
    id("kotlin-parcelize")
}

android {
    val localProperties = Properties().apply {
        load(project.rootProject.file("local.properties").inputStream())
    }
    val apiKey = localProperties.getProperty("GOOGLE_MAPS_API_KEY") ?: throw IllegalArgumentException("GOOGLE_API_KEY not found in local.properties")

    namespace = "com.wim4you.intervene"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wim4you.intervene"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "1.6"
        manifestPlaceholders.put("google_maps_api_key", apiKey)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            keyAlias = "intervene"
            keyPassword = "Strawberry"
            storeFile = file("C:/Users/frank.de.pijper/AndroidStudioProjects/keystore/com.wim4you.intervene.keystore/InterVeneKey.jks")
            storePassword = "Strawberry"
        }
    }
    buildTypes {
        release {
            //signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.google.maps.ktx)
    implementation(libs.play.services.location)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.firebase.database)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.geofire.android)
    implementation(libs.firebase.messaging)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

}