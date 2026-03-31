# Android UniFFI E2E Harness

This project runs Android instrumented end-to-end tests against the public
`SdkNode` / UniFFI path.

Current scenario:

1. `payment`
2. `multi_open_close`

## Prerequisites

Required tools:
- `cargo`
- `java`
- `adb`
- `docker`

Required environment:
- `ANDROID_NDK_HOME`

Start local regtest services:

```sh
./regtest.sh start
```

Make sure an Android emulator is running and visible in `adb devices`.

The Android test connects to local regtest services through `10.0.2.2`:
- bitcoind RPC: `10.0.2.2:18443`
- electrs: `10.0.2.2:50001`
- proxy: `10.0.2.2:3000`

Current Android e2e baseline uses the emulator `x86_64` ABI only. Multi-ABI
Android artifact coverage remains a separate rollout step.

## Run

Recommended entry point:

```sh
./scripts/android_uniffi_e2e.sh
```

This script:
- checks required commands and `ANDROID_NDK_HOME`
- checks that local regtest services are running
- checks that an Android emulator/device is visible in `adb devices`
- builds Android JNI libraries for `x86_64`
- generates Kotlin Android UniFFI bindings
- runs `connectedDebugAndroidTest`

Manual Gradle run from the Android project:

Before running Gradle directly, first prepare Android artifacts:

```sh
./scripts/ci/build_android_jni_x86_64.sh
./scripts/ci/uniffi_generate_kotlin_android.sh
```

```sh
cd android-e2e
./gradlew connectedDebugAndroidTest
```

## Cleanup

```sh
./regtest.sh stop
```
