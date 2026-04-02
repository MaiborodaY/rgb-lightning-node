# Rust SDK Parity Tests

This suite runs the public `SdkNode` / UniFFI library path and checks parity
against the existing HTTP-based test scenarios.

## Prerequisites

Start local regtest services:

```sh
./regtest.sh start
```

## Run

Run the full SDK suite:

```sh
cargo test --features uniffi --test lib_sdk -- --test-threads=1
```

Run a single scenario:

```sh
cargo test --features uniffi --test lib_sdk <test_name> -- --test-threads=1
```

Examples of `<test_name>`:
- `success`
- `send_receive`
- `multi_hop`
- `restart`
- `swap_roundtrip_buy`
- `with_anchors`
- `without_anchors`

## Cleanup

```sh
./regtest.sh stop
```
