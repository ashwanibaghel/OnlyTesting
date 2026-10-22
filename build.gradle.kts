plugins {
    // Share Kotlin's classpath without resolving AGP or requiring an SDK for engineOnly.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
}
