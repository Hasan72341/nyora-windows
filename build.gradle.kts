// Compose Multiplatform version — check https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html
// for the version compatible with your Kotlin build. The values below target Kotlin 2.1.x / CMP 1.7.x.
// If using Kotlin 2.2.x, bump compose to 1.8.x once a stable release is available.
plugins {
    kotlin("multiplatform") version "2.1.21" apply false
    kotlin("jvm")           version "2.1.21" apply false
    kotlin("plugin.serialization") version "2.1.21" apply false
    id("org.jetbrains.compose")              version "1.8.2"  apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("app.cash.sqldelight")                version "2.1.0"  apply false
}

group = "com.nyora"
version = "2.0.5"
