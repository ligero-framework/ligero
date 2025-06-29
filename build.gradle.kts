// Top-level build file where you can add configuration options common to all sub-projects/modules.

plugins {
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.9.0" apply false
    // Apply the java-library plugin for API and implementation separation.
    id("java-library")
    id("maven-publish")
}

allprojects {
    group = "com.ligero"
    version = "0.1.0-SNAPSHOT"
    
    repositories {
        mavenCentral()
    }
    
    tasks.withType<JavaCompile> {
        sourceCompatibility = JavaVersion.VERSION_21.toString()
        targetCompatibility = JavaVersion.VERSION_21.toString()
    }
    
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
        }
    }
}
