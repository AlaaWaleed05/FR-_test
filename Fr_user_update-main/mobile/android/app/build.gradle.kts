plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.sfbank.bayanati"
    // S5-03: flutter_secure_storage 11.0.0 requires compileSdk >= 37 (reviewer-found BLOCKER —
    // the debug APK failed to build at 36). minSdk/targetSdk unchanged; compileSdk only affects
    // which SDK APIs are available at compile time, not runtime behaviour on older devices.
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // S8-06 (BL-079/BL-081): the real identifier, replacing Flutter's template
        // `com.example.mobile`. `sfbank` is the bank's public domain (sfbank-sd.com),
        // `bayanati` the app name «بياناتي». This value is IMMUTABLE after the first Play
        // Store upload -- changing it later means a new listing and a manual reinstall for
        // every user, not an update. `namespace` above is deliberately identical to it.
        applicationId = "com.sfbank.bayanati"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // S8-09 (R-002 / BL-071). The Uqudo SDK renders its own screens in Arabic only because
    // `src/main/res/values-ar/strings.xml` supplies the `uq_*` values its AAR omits. In an Android
    // App Bundle, Play splits resources by language and delivers only the splits matching the
    // DEVICE locale -- so a handset set to English would install without the `ar` split and the SDK
    // would fall silently back to its bundled English, with no error anywhere. The pilot ships an
    // APK and is unaffected; this makes the AAB safe too, before anyone builds one and is surprised.
    //
    // This belongs in the DSL, NOT in `gradle.properties`. `android.bundle.language.enableSplit` is
    // not an AGP project option -- reviewer-found at S8-09: AGP 8.11.1's `BooleanOption` carries no
    // such key, `enableSplit` exists only as this DSL member, and an unknown `android.*` property is
    // silently ignored rather than rejected. The property form looked right and did nothing.
    bundle {
        language {
            enableSplit = false
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}
