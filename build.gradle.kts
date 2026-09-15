// Top-level build file
// AGP 8.6.x is the first release that officially supports compileSdk 35, which is what
// the app module targets. 8.5.2 only emitted a warning today and refuses tomorrow.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
