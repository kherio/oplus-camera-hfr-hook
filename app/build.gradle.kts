plugins { id("com.android.application") }

android {
    namespace = "io.github.kherio.hfrhook"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.kherio.hfrhook"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // API de Xposed solo en tiempo de compilación (la aporta LSPosed/Vector en el móvil)
    compileOnly(project(":xposedstub"))
}
