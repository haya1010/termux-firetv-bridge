plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
  namespace = "dev.termux.firetvreceiver"
  compileSdk = 35
  defaultConfig {
    applicationId = "dev.termux.firetvreceiver"
    minSdk = 25
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("io.github.webrtc-sdk:android:144.7559.09")
}
