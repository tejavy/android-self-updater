plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "io.github.selfupdater"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        aarMetadata {
            minCompileSdk = 26
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.4.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.core:core-ktx:1.19.0")

    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = rootProject.group.toString()
                artifactId = "android-self-updater"
                version = rootProject.version.toString()
                pom {
                    name.set("Android Self Updater")
                    description.set("A UI-free, self-hosted Android APK update library.")
                }
            }
        }
    }
}
