# Rust SDK Parity Tests

This suite runs the public `SdkNode` / UniFFI library path and checks parity
against the existing HTTP-based test scenarios.

Covered scenarios:

1. `payment::success`
2. `send_receive::send_receive`
3. `close_coop_standard::close_coop_standard`
4. `close_force_standard::close_force_standard`
5. `openchannel_push_asset_amount::openchannel_push_asset_amount`
6. `vanilla_payment_on_rgb_channel::vanilla_payment_on_rgb_channel`
7. `close_coop_other_side::close_coop_other_side`
8. `multi_hop::multi_hop`
9. `restart::restart`
10. `close_coop_vanilla::with_anchors`
11. `close_coop_vanilla::without_anchors`

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
- `with_anchors`
- `without_anchors`

## Cleanup

```sh
./regtest.sh stop
```
