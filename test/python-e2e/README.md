# Python UniFFI E2E Harness

This harness runs local regtest end-to-end scenarios against the public `SdkNode`
/ UniFFI path from Python.

Current scenarios:

1. `payment`
2. `openchannel_push_asset_amount`
3. `getchannelid_fail`
4. `openchannel_fail_no_utxos`
5. `openchannel_fail_unknown_asset`

## Prerequisites

Required tools:
- `cargo`
- `python3`
- `docker`

Start local regtest services:

```sh
./regtest.sh start
```

## Run

Default scenario:

```sh
./scripts/python_uniffi_e2e.sh
```

Run a specific scenario:

```sh
PYTHON_E2E_SCENARIO=payment ./scripts/python_uniffi_e2e.sh
PYTHON_E2E_SCENARIO=openchannel_push_asset_amount ./scripts/python_uniffi_e2e.sh
PYTHON_E2E_SCENARIO=getchannelid_fail ./scripts/python_uniffi_e2e.sh
PYTHON_E2E_SCENARIO=openchannel_fail_no_utxos ./scripts/python_uniffi_e2e.sh
PYTHON_E2E_SCENARIO=openchannel_fail_unknown_asset ./scripts/python_uniffi_e2e.sh
```

## Cleanup

```sh
./regtest.sh stop
```
