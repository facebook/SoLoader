group = "com.facebook"
version = "0.11.0"

plugins {
    id("com.android.library").version("8.5.1")
    id("maven-publish")
}

android {
    compileSdk = 34
    defaultConfig {
        minSdk = 9
        namespace = "com.facebook.soloader"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }    
    sourceSets {
        getByName("main") {
            java {
                setSrcDirs(listOf("java"))
            }
        }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("com.google.code.findbugs:jsr305:3.0.0")
    implementation("com.facebook.yoga:proguard-annotations:1.14.1")
}

publishing {
  publications {
    register<MavenPublication>("release") {
      groupId = "com.facebook"
      artifactId = "soloader"
      version = version

      afterEvaluate {
        from(components["release"])
      }
    }
  }
}