plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.example.repro"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  androidTestImplementation("androidx.test:runner:1.6.1")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("junit:junit:4.13.2")
}
