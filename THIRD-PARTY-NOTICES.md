# Third-party notices

This project is an application, not a fork of any of the libraries below. The
libraries are consumed as regular Gradle dependencies (no source is vendored in
this repository). Their licenses remain in effect for their respective code.

| Component | Usage | License |
|---|---|---|
| [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) (`io.github.zxing-cpp:android:3.1.1`) | On-device Code 128 decoding (native AAR) | Apache License 2.0 |
| [AndroidX](https://developer.android.com/jetpack/androidx) (Core, Activity, Lifecycle, Compose, Room, WorkManager, DataStore) | Android UI / storage / background work | Apache License 2.0 |
| [Jetpack Compose](https://developer.android.com/compose) + Material 3 | UI | Apache License 2.0 |
| [Kotlin](https://kotlinlang.org/) + [kotlinx.coroutines / kotlinx.serialization](https://github.com/Kotlin) | Language runtime, coroutines, JSON | Apache License 2.0 |
| [Retrofit](https://github.com/square/retrofit) | HTTP API client | Apache License 2.0 |
| [OkHttp](https://github.com/square/okhttp) | HTTP transport | Apache License 2.0 |
| [JUnit 4](https://github.com/junit-team/junit4) | JVM unit tests (test-only) | Eclipse Public License 1.0 |

`tools/dev-server/` is an original development-only mock of the API contract
and uses only the Python standard library.

If you redistribute an APK built from this project, keep the license notices of
the dependencies above (Apache-2.0 requires preserving NOTICE/License text for
redistributed binaries).
