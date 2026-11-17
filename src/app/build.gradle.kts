plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.omrscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.omrscanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.1"
    }

    // 4 biến thể thuật toán dò chấm (cài song song được vì applicationId khác nhau):
    //  classic — pipeline OpenCV cổ điển (fit-to-block), không AI
    //  tflite  — CNN keypoint tự huấn luyện, chạy TensorFlow Lite
    //  mlkit   — chụp bằng Google ML Kit Document Scanner (AI dò mép trang Google)
    //  dnn     — CNN keypoint cùng model, chạy qua OpenCV DNN (ONNX)
    flavorDimensions += "detector"
    productFlavors {
        create("classic") {
            dimension = "detector"
            applicationIdSuffix = ".classic"
        }
        create("tflite") {
            dimension = "detector"
            applicationIdSuffix = ".tflite"
        }
        create("mlkit") {
            dimension = "detector"
            applicationIdSuffix = ".mlkit"
        }
        create("dnn") {
            dimension = "detector"
            applicationIdSuffix = ".dnn"
            versionCode = 1
            versionName = "1.0"
        }
    }

    sourceSets {
        getByName("mlkit") { java.srcDirs("src/mlkitShared/java") }
        getByName("dnn") { java.srcDirs("src/mlkitShared/java") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }
    // Tách APK theo ABI: bản arm64-v8a nhẹ (~45MB) cho điện thoại hiện đại,
    // bản universal chứa đủ kiến trúc để cất làm dự phòng.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // CameraX
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // OpenCV (official Maven Central artifact since 4.9.0) — bundles native .so
    implementation("org.opencv:opencv:4.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// Dependency chỉ có ở flavor tương ứng
dependencies {
    "tfliteImplementation"("org.tensorflow:tensorflow-lite:2.14.0")
    "mlkitImplementation"("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    "dnnImplementation"("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
}
