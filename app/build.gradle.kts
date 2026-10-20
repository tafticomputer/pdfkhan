plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pdfkhan.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pdfkhan.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    // برای هر معماری یک APK جدا؛ هر کدام کوچک‌تر است و دانلودش راحت‌تر.
    // گوشی‌های امروزی تقریباً همه arm64-v8a هستند.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    packaging {
        // کتابخانه‌های بومی چند بسته ممکن است یک فایل مشترک داشته باشند
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // استخراج متن از PDF
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // باز کردن آرشیو tar.bz2 مدل صدا پس از دانلود
    implementation("org.apache.commons:commons-compress:1.21")

    // OCR فارسی (داده‌ی fas.traineddata داخل assets است)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // موتور صدای فارسی آفلاین (Sherpa-ONNX)؛ فایل AAR با scripts/fetch_assets.sh دانلود می‌شود
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
