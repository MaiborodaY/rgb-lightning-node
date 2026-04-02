# Kotlin JVM UniFFI E2E Harness

This harness runs local regtest end-to-end scenarios against the public `SdkNode`
/ UniFFI path from Kotlin/JVM.

Current scenarios:

1. `payment`
2. `openchannel_push_asset_amount`
3. `close_coop_vanilla_with_anchors`
4. `close_coop_vanilla_without_anchors`
5. `openchannel_optional_addr_forward`
6. `openchannel_optional_addr_reverse`

## Prerequisites

Required tools:
- `cargo`
- `java`
- `kotlinc`
- `libjna-java`
- `docker`

Start local regtest services:

```sh
./regtest.sh start
```

If needed, override the default JNA jar path:

```sh
export JNA_JAR=/path/to/jna.jar
```

## Run

Default scenario:

```sh
./scripts/kotlin_uniffi_e2e.sh
```

Run a specific scenario:

```sh
KOTLIN_E2E_SCENARIO=<scenario_name> ./scripts/kotlin_uniffi_e2e.sh
```

## Cleanup

```sh
./regtest.sh stop
```
