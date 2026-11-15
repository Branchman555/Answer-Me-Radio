plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "radio.reply"
    compileSdk = 35
    defaultConfig {
        applicationId = "radio.reply.prototype"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-playback"
        testInstrumentationRunner = "radio.reply.catalog.CatalogInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.all {
        it.systemProperty("designDir", rootProject.file("docs/design").absolutePath)
    }
    sourceSets.getByName("androidTest").assets.srcDir("build/generated/rr004-assets")
}
// This first milestone is an explicit, silent simulator, not a vehicle release.
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}
