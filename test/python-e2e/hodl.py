import time

import rgb_lightning_node as rln

from config import (
    CHANNEL_READY_TIMEOUT_SEC,
    NODE_A_DAEMON_PORT,
    NODE_A_PASSWORD,
    NODE_A_PEER_PORT,
    NODE_B_DAEMON_PORT,
    NODE_B_PASSWORD,
    NODE_B_PEER_PORT,
    NODE_C_DAEMON_PORT,
    NODE_C_PASSWORD,
    NODE_C_PEER_PORT,
    OPEN_CHANNEL_ASSET_AMOUNT,
    OPEN_CHANNEL_CAPACITY_SAT,
    OPEN_CHANNEL_CONFIRM_BLOCKS,
    OPEN_CHANNEL_PUSH_MSAT,
    PAYMENT_ASSET_AMOUNT,
    PAYMENT_MSAT,
    scenario_storage,
)
from harness import (
    assert_payment_core_fields,
    asset_balance_spendable,
    check_preimage_matches_hash,
    create_utxos,
    ensure_dir,
    ensure_funded_with_amount,
    init_if_needed,
    refresh_transfers,
    rgb_invoice,
    issue_asset_nia,
    make_node,
    mine_until_tx_confirmed,
    payment_hash_from_preimage,
    random_preimage_hex,
    run_regtest,
    safe_shutdown,
    unlock_if_needed,
    wait_for_channel_ready,
    wait_for_balance,
    wait_for_payment_state,
    wait_for_peer,
    wait_payment_final,
    wait_for_usable_channels,
)


def wait_for_peer_channel_funding_tx(
    sender: rln.SdkNode,
    receiver_pubkey,
    asset_id,
    timeout_sec: int = 120,
):
    deadline = time.time() + timeout_sec
    last = "no channels"
    while time.time() < deadline:
        sender.sync()
        channels = sender.list_channels()
        last = ", ".join(
            f"id={c.channel_id},peer={c.peer_pubkey},asset={c.asset_id},funding={c.funding_txid}"
            for c in channels
        ) or "no channels"

        opening = next(
            (
                c
                for c in channels
                if str(c.peer_pubkey) == str(receiver_pubkey)
                and str(c.asset_id) == str(asset_id)
                and c.funding_txid is not None
            ),
            None,
        )
        if opening is not None:
            print(f"channel funding tx found for peer {receiver_pubkey}: {opening.funding_txid}")
            return str(opening.funding_txid)

        print(f"waiting for channel funding tx broadcast for peer {receiver_pubkey}...")
        time.sleep(1)

    raise RuntimeError(
        f"No funding tx after {timeout_sec}s for peer={receiver_pubkey} asset_id={asset_id}; "
        f"last_channels={last}"
    )


def wait_for_channel_usable(
    node: rln.SdkNode,
    channel_id,
    timeout_sec: int = 120,
):
    deadline = time.time() + timeout_sec
    last = "channel not found"
    while time.time() < deadline:
        node.sync()
        channel = next((c for c in node.list_channels() if c.channel_id == channel_id), None)
        if channel is not None:
            last = (
                f"id={channel.channel_id},ready={channel.ready},usable={channel.is_usable},"
                f"funding={channel.funding_txid}"
            )
            if channel.is_usable:
                return
        time.sleep(1)
    raise RuntimeError(
        f"channel did not become usable after {timeout_sec}s: channel_id={channel_id} last={last}"
    )


def open_hodl_asset_channel(
    sender: rln.SdkNode,
    receiver: rln.SdkNode,
    receiver_name: str,
    receiver_peer_port: int,
    asset_id,
):
    receiver_info = receiver.node_info()
    peer_uri = f"{receiver_info.pubkey}@127.0.0.1:{receiver_peer_port}"
    try:
        sender.connectpeer(peer_uri)
        print(f"connectpeer to {receiver_name}: ok")
    except rln.RlnError.Conflict:
        print(f"connectpeer to {receiver_name}: already connected")
    wait_for_peer(sender, receiver_info.pubkey, 20)

    open_response = sender.openchannel(
        rln.SdkOpenChannelRequest(
            peer_pubkey_and_opt_addr=peer_uri,
            capacity_sat=OPEN_CHANNEL_CAPACITY_SAT,
            push_msat=OPEN_CHANNEL_PUSH_MSAT,
            public=False,
            with_anchors=True,
            fee_base_msat=None,
            fee_proportional_millionths=None,
            temporary_channel_id=None,
            asset_id=asset_id,
            asset_amount=OPEN_CHANNEL_ASSET_AMOUNT,
            push_asset_amount=None,
            virtual_open_mode=None,
        )
    )
    print(
        f"openchannel to {receiver_name} temporary_channel_id: "
        f"{open_response.temporary_channel_id}"
    )

    funding_txid = wait_for_peer_channel_funding_tx(
        sender, receiver_info.pubkey, asset_id, 120
    )
    print(f"Mining blocks until {receiver_name} funding tx is confirmed...")
    mine_until_tx_confirmed(sender, funding_txid, 180)
    print(f"Mining {OPEN_CHANNEL_CONFIRM_BLOCKS} blocks for channel confirmations...")
    run_regtest("mine", str(OPEN_CHANNEL_CONFIRM_BLOCKS))
    channel_id = sender.get_channel_id(open_response.temporary_channel_id)
    wait_for_channel_ready(sender, channel_id, 10)
    wait_for_channel_usable(sender, channel_id, CHANNEL_READY_TIMEOUT_SEC)
    wait_for_channel_usable(receiver, channel_id, CHANNEL_READY_TIMEOUT_SEC)
    print(f"Channel to {receiver_name} is usable")
    return channel_id


def send_asset_for_second_channel(
    sender: rln.SdkNode,
    receiver: rln.SdkNode,
    receiver_name: str,
    asset_id,
    asset_amount: int,
):
    recipient_id = rgb_invoice(receiver)
    sender.send_rgb(
        rln.SendRgbRequest(
            donation=True,
            fee_rate=1,
            min_confirmations=1,
            skip_sync=False,
            recipient_groups=[
                rln.AssetRecipients(
                    asset_id=asset_id,
                    recipients=[
                        rln.RgbRecipient(
                            recipient_id=recipient_id,
                            witness_data=None,
                            assignment_kind=rln.AssignmentKind.FUNGIBLE,
                            assignment_amount=asset_amount,
                            transport_endpoints=["rpc://127.0.0.1:3000/json-rpc"],
                        )
                    ],
                )
            ],
        )
    )
    print(f"sent on-chain asset {asset_amount} to {receiver_name} for second channel setup")
    run_regtest("mine", "1")
    refresh_transfers(receiver)
    refresh_transfers(receiver)
    refresh_transfers(sender)
    wait_for_balance(receiver, asset_id, asset_amount, 60)


def assert_decoded_hodl_invoice(decoded, payment_hash_hex: str, asset_id):
    if str(decoded.payment_hash) != payment_hash_hex:
        raise RuntimeError(
            f"decoded payment_hash mismatch: expected={payment_hash_hex} actual={decoded.payment_hash}"
        )
    if str(decoded.asset_id) != str(asset_id):
        raise RuntimeError(
            f"decoded asset_id mismatch: expected={asset_id} actual={decoded.asset_id}"
        )
    if decoded.asset_amount != PAYMENT_ASSET_AMOUNT:
        raise RuntimeError(
            f"decoded asset_amount mismatch: expected={PAYMENT_ASSET_AMOUNT} actual={decoded.asset_amount}"
        )
    if decoded.amt_msat != PAYMENT_MSAT:
        raise RuntimeError(
            f"decoded amt_msat mismatch: expected={PAYMENT_MSAT} actual={decoded.amt_msat}"
        )


def assert_sender_not_succeeded(sender: rln.SdkNode, payment_hash, phase: str):
    try:
        sender_payment = sender.get_payment(payment_hash)
        if sender_payment.status == rln.HtlcStatus.SUCCEEDED:
            raise RuntimeError(f"sender payment succeeded before explicit hodl {phase}")
    except rln.RlnError.NotFound:
        pass


def get_channel(node: rln.SdkNode, channel_id):
    channel = next((c for c in node.list_channels() if c.channel_id == channel_id), None)
    if channel is None:
        raise RuntimeError(f"channel not found: channel_id={channel_id}")
    return channel


def assert_channel_asset_amounts(
    node: rln.SdkNode,
    channel_id,
    expected_local: int,
    expected_remote: int,
    node_name: str,
):
    channel = get_channel(node, channel_id)
    if channel.asset_local_amount != expected_local:
        raise RuntimeError(
            f"{node_name} unexpected channel asset_local_amount: "
            f"expected={expected_local} actual={channel.asset_local_amount}"
        )
    if channel.asset_remote_amount != expected_remote:
        raise RuntimeError(
            f"{node_name} unexpected channel asset_remote_amount: "
            f"expected={expected_remote} actual={channel.asset_remote_amount}"
        )


def assert_node_channel_counts(
    node: rln.SdkNode,
    expected_channels: int,
    expected_usable_channels: int,
    expected_peers: int,
    node_name: str,
):
    info = node.node_info()
    if info.num_channels != expected_channels:
        raise RuntimeError(
            f"{node_name} unexpected num_channels: "
            f"expected={expected_channels} actual={info.num_channels}"
        )
    if info.num_usable_channels != expected_usable_channels:
        raise RuntimeError(
            f"{node_name} unexpected num_usable_channels: "
            f"expected={expected_usable_channels} actual={info.num_usable_channels}"
        )
    if info.num_peers != expected_peers:
        raise RuntimeError(
            f"{node_name} unexpected num_peers: "
            f"expected={expected_peers} actual={info.num_peers}"
        )


def assert_offchain_balances(
    node: rln.SdkNode,
    asset_id,
    expected_outbound: int,
    expected_inbound: int,
    node_name: str,
    timeout_sec: int = 30,
):
    deadline = time.time() + timeout_sec
    last_outbound = None
    last_inbound = None
    while time.time() < deadline:
        node.sync()
        balance = node.asset_balance(asset_id)
        last_outbound = balance.offchain_outbound
        last_inbound = balance.offchain_inbound
        if balance.offchain_outbound == expected_outbound and balance.offchain_inbound == expected_inbound:
            return
        time.sleep(1)
    raise RuntimeError(
        f"{node_name} unexpected offchain balances after {timeout_sec}s: "
        f"expected_outbound={expected_outbound} actual_outbound={last_outbound} "
        f"expected_inbound={expected_inbound} actual_inbound={last_inbound}"
    )


def run_hodl_claim_phase(
    sender: rln.SdkNode, receiver: rln.SdkNode, asset_id, sender_channel_id, receiver_channel_id
):
    print("=== HODL phase: claim ===")

    preimage_hex = random_preimage_hex()
    payment_hash_hex = payment_hash_from_preimage(preimage_hex)
    invoice = receiver.ln_invoice(
        rln.LnInvoiceRequest(
            amt_msat=PAYMENT_MSAT,
            expiry_sec=3600,
            asset_id=asset_id,
            asset_amount=PAYMENT_ASSET_AMOUNT,
            payment_hash=payment_hash_hex,
            description_hash=None,
        )
    ).invoice
    print(f"hodl claim invoice: {invoice}")

    decoded = sender.decode_ln_invoice(invoice)
    assert_decoded_hodl_invoice(decoded, payment_hash_hex, asset_id)

    send_payment = sender.sendpayment(
        rln.SdkSendPaymentRequest(
            invoice=invoice,
            amt_msat=None,
            asset_id=None,
            asset_amount=None,
        )
    )
    print(f"sendpayment claim status: {send_payment.status.name}")
    if send_payment.status in (rln.HtlcStatus.FAILED, rln.HtlcStatus.CANCELLED):
        raise RuntimeError(
            f"unexpected initial sendpayment status on claim phase: {send_payment.status.name}"
        )

    claimable_payment = wait_for_payment_state(
        receiver,
        decoded.payment_hash,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.CLAIMABLE,
        60,
    )
    assert_payment_core_fields(
        claimable_payment,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.CLAIMABLE,
        asset_id,
        PAYMENT_ASSET_AMOUNT,
        PAYMENT_MSAT,
    )
    assert_sender_not_succeeded(sender, decoded.payment_hash, "claim")

    claim_response = receiver.claimhodlinvoice(
        rln.ClaimHodlInvoiceRequest(
            payment_hash=decoded.payment_hash,
            payment_preimage=preimage_hex,
        )
    )
    if not claim_response.changed:
        raise RuntimeError("first claimhodlinvoice should report changed=True")

    sender_final = wait_for_payment_state(
        sender,
        decoded.payment_hash,
        rln.PaymentType.OUTBOUND,
        rln.HtlcStatus.SUCCEEDED,
        60,
    )
    receiver_final = wait_for_payment_state(
        receiver,
        decoded.payment_hash,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.SUCCEEDED,
        60,
    )
    assert_payment_core_fields(
        receiver_final,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.SUCCEEDED,
        asset_id,
        PAYMENT_ASSET_AMOUNT,
        PAYMENT_MSAT,
    )
    if sender_final.preimage != preimage_hex:
        raise RuntimeError(
            f"unexpected sender preimage: expected={preimage_hex} actual={sender_final.preimage}"
        )
    check_preimage_matches_hash(sender_final, str(decoded.payment_hash))

    final_status = wait_payment_final(receiver, invoice, 30)
    if final_status != rln.InvoiceStatus.SUCCEEDED:
        raise RuntimeError(
            f"unexpected final invoice_status on claim phase: {final_status.name}"
        )

    claim_response_again = receiver.claimhodlinvoice(
        rln.ClaimHodlInvoiceRequest(
            payment_hash=decoded.payment_hash,
            payment_preimage=preimage_hex,
        )
    )
    if claim_response_again.changed:
        raise RuntimeError("second claimhodlinvoice should report changed=False")

    assert_channel_asset_amounts(
        sender,
        sender_channel_id,
        OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
        PAYMENT_ASSET_AMOUNT,
        "claim sender",
    )
    assert_channel_asset_amounts(
        receiver,
        receiver_channel_id,
        PAYMENT_ASSET_AMOUNT,
        OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
        "claim receiver",
    )


def run_hodl_cancel_phase(
    sender: rln.SdkNode, receiver: rln.SdkNode, asset_id, sender_channel_id, receiver_channel_id
):
    print("=== HODL phase: cancel ===")

    preimage_hex = random_preimage_hex()
    payment_hash_hex = payment_hash_from_preimage(preimage_hex)
    invoice = receiver.ln_invoice(
        rln.LnInvoiceRequest(
            amt_msat=PAYMENT_MSAT,
            expiry_sec=3600,
            asset_id=asset_id,
            asset_amount=PAYMENT_ASSET_AMOUNT,
            payment_hash=payment_hash_hex,
            description_hash=None,
        )
    ).invoice
    print(f"hodl cancel invoice: {invoice}")

    decoded = sender.decode_ln_invoice(invoice)
    assert_decoded_hodl_invoice(decoded, payment_hash_hex, asset_id)

    send_payment = sender.sendpayment(
        rln.SdkSendPaymentRequest(
            invoice=invoice,
            amt_msat=None,
            asset_id=None,
            asset_amount=None,
        )
    )
    print(f"sendpayment cancel status: {send_payment.status.name}")
    if send_payment.status in (rln.HtlcStatus.FAILED, rln.HtlcStatus.CANCELLED):
        raise RuntimeError(
            f"unexpected initial sendpayment status on cancel phase: {send_payment.status.name}"
        )

    claimable_payment = wait_for_payment_state(
        receiver,
        decoded.payment_hash,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.CLAIMABLE,
        60,
    )
    assert_payment_core_fields(
        claimable_payment,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.CLAIMABLE,
        asset_id,
        PAYMENT_ASSET_AMOUNT,
        PAYMENT_MSAT,
    )
    assert_sender_not_succeeded(sender, decoded.payment_hash, "cancel")

    receiver.cancelhodlinvoice(
        rln.CancelHodlInvoiceRequest(payment_hash=decoded.payment_hash)
    )

    sender_final = wait_for_payment_state(
        sender,
        decoded.payment_hash,
        rln.PaymentType.OUTBOUND,
        rln.HtlcStatus.FAILED,
        60,
    )
    receiver_final = wait_for_payment_state(
        receiver,
        decoded.payment_hash,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.CANCELLED,
        60,
    )
    assert_payment_core_fields(
        receiver_final,
        rln.PaymentType.INBOUND_HODL,
        rln.HtlcStatus.CANCELLED,
        asset_id,
        PAYMENT_ASSET_AMOUNT,
        PAYMENT_MSAT,
    )
    if sender_final.payment_type != rln.PaymentType.OUTBOUND:
        raise RuntimeError(
            f"unexpected sender payment_type after cancel: {sender_final.payment_type.name}"
        )

    final_status = wait_payment_final(receiver, invoice, 30)
    if final_status != rln.InvoiceStatus.CANCELLED:
        raise RuntimeError(
            f"unexpected final invoice_status on cancel phase: {final_status.name}"
        )

    assert_channel_asset_amounts(
        sender,
        sender_channel_id,
        OPEN_CHANNEL_ASSET_AMOUNT,
        0,
        "cancel sender",
    )
    assert_channel_asset_amounts(
        receiver,
        receiver_channel_id,
        0,
        OPEN_CHANNEL_ASSET_AMOUNT,
        "cancel receiver",
    )


def hodl_e2e_scenario():
    scenario = "hodl_e2e"
    node_a_storage = scenario_storage(scenario, "node_a")
    node_b_storage = scenario_storage(scenario, "node_b")
    node_c_storage = scenario_storage(scenario, "node_c")

    print("Python UniFFI HODL 3-node flow")
    print(f"node A storage: {node_a_storage}")
    print(f"node B storage: {node_b_storage}")
    print(f"node C storage: {node_c_storage}")

    ensure_dir(node_a_storage)
    ensure_dir(node_b_storage)
    ensure_dir(node_c_storage)

    node_a = None
    node_b = None
    node_c = None
    try:
        node_a = make_node(node_a_storage, NODE_A_DAEMON_PORT + 20, NODE_A_PEER_PORT + 20)
        node_b = make_node(node_b_storage, NODE_B_DAEMON_PORT + 20, NODE_B_PEER_PORT + 20)
        node_c = make_node(node_c_storage, NODE_C_DAEMON_PORT + 20, NODE_C_PEER_PORT + 20)

        init_if_needed(node_a, NODE_A_PASSWORD, "node A")
        init_if_needed(node_b, NODE_B_PASSWORD, "node B")
        init_if_needed(node_c, NODE_C_PASSWORD, "node C")
        unlock_if_needed(node_a, NODE_A_PASSWORD, "node A")
        unlock_if_needed(node_b, NODE_B_PASSWORD, "node B")
        unlock_if_needed(node_c, NODE_C_PASSWORD, "node C")

        ensure_funded_with_amount(
            node_a, "node A", OPEN_CHANNEL_CAPACITY_SAT * 2 + 300_000, "0.02"
        )
        ensure_funded_with_amount(node_b, "node B", 200_000, "0.02")
        ensure_funded_with_amount(node_c, "node C", 200_000, "0.02")

        create_utxos(node_a, "node A")
        create_utxos(node_b, "node B")
        create_utxos(node_c, "node C")
        run_regtest("mine", "1")
        node_a.sync()
        node_b.sync()
        node_c.sync()

        asset_id = issue_asset_nia(node_a, "node A")
        send_asset_for_second_channel(
            node_a, node_b, "node B", asset_id, OPEN_CHANNEL_ASSET_AMOUNT + PAYMENT_ASSET_AMOUNT
        )

        node_a_asset = asset_balance_spendable(node_a, asset_id)
        node_b_asset = asset_balance_spendable(node_b, asset_id)
        print(f"node A spendable asset after transfer: {node_a_asset}")
        print(f"node B spendable asset after transfer: {node_b_asset}")

        channel_ab = open_hodl_asset_channel(
            node_a, node_b, "node B", NODE_B_PEER_PORT + 20, asset_id
        )
        channel_bc = open_hodl_asset_channel(
            node_b, node_c, "node C", NODE_C_PEER_PORT + 20, asset_id
        )

        assert_node_channel_counts(node_a, 1, 1, 1, "node A")
        assert_node_channel_counts(node_b, 2, 2, 2, "node B")
        assert_node_channel_counts(node_c, 1, 1, 1, "node C")

        assert_channel_asset_amounts(node_a, channel_ab, OPEN_CHANNEL_ASSET_AMOUNT, 0, "node A")
        assert_channel_asset_amounts(node_b, channel_ab, 0, OPEN_CHANNEL_ASSET_AMOUNT, "node B")
        assert_channel_asset_amounts(node_b, channel_bc, OPEN_CHANNEL_ASSET_AMOUNT, 0, "node B")
        assert_channel_asset_amounts(node_c, channel_bc, 0, OPEN_CHANNEL_ASSET_AMOUNT, "node C")

        assert_offchain_balances(node_a, asset_id, OPEN_CHANNEL_ASSET_AMOUNT, 0, "node A")
        assert_offchain_balances(
            node_b,
            asset_id,
            OPEN_CHANNEL_ASSET_AMOUNT,
            OPEN_CHANNEL_ASSET_AMOUNT,
            "node B",
        )
        assert_offchain_balances(node_c, asset_id, 0, OPEN_CHANNEL_ASSET_AMOUNT, "node C")

        run_hodl_claim_phase(node_a, node_b, asset_id, channel_ab, channel_ab)
        assert_offchain_balances(
            node_a,
            asset_id,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            PAYMENT_ASSET_AMOUNT,
            "node A",
        )
        assert_offchain_balances(
            node_b,
            asset_id,
            OPEN_CHANNEL_ASSET_AMOUNT + PAYMENT_ASSET_AMOUNT,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            "node B",
        )
        assert_offchain_balances(node_c, asset_id, 0, OPEN_CHANNEL_ASSET_AMOUNT, "node C")

        print("=== HODL phase: restart ===")
        node_a.shutdown()
        node_b.shutdown()
        node_c.shutdown()
        time.sleep(1)

        node_a = make_node(node_a_storage, NODE_A_DAEMON_PORT + 20, NODE_A_PEER_PORT + 20)
        node_b = make_node(node_b_storage, NODE_B_DAEMON_PORT + 20, NODE_B_PEER_PORT + 20)
        node_c = make_node(node_c_storage, NODE_C_DAEMON_PORT + 20, NODE_C_PEER_PORT + 20)

        unlock_if_needed(node_a, NODE_A_PASSWORD, "node A")
        unlock_if_needed(node_b, NODE_B_PASSWORD, "node B")
        unlock_if_needed(node_c, NODE_C_PASSWORD, "node C")

        wait_for_usable_channels(node_a, 1, 120)
        wait_for_usable_channels(node_b, 2, 120)
        wait_for_usable_channels(node_c, 1, 120)
        wait_for_channel_usable(node_a, channel_ab, CHANNEL_READY_TIMEOUT_SEC)
        wait_for_channel_usable(node_b, channel_ab, CHANNEL_READY_TIMEOUT_SEC)
        wait_for_channel_usable(node_b, channel_bc, CHANNEL_READY_TIMEOUT_SEC)
        wait_for_channel_usable(node_c, channel_bc, CHANNEL_READY_TIMEOUT_SEC)

        assert_node_channel_counts(node_a, 1, 1, 1, "node A")
        assert_node_channel_counts(node_b, 2, 2, 2, "node B")
        assert_node_channel_counts(node_c, 1, 1, 1, "node C")

        assert_channel_asset_amounts(
            node_a,
            channel_ab,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            PAYMENT_ASSET_AMOUNT,
            "node A after restart",
        )
        assert_channel_asset_amounts(
            node_b,
            channel_ab,
            PAYMENT_ASSET_AMOUNT,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            "node B channel AB after restart",
        )
        assert_channel_asset_amounts(
            node_b,
            channel_bc,
            OPEN_CHANNEL_ASSET_AMOUNT,
            0,
            "node B channel BC after restart",
        )
        assert_channel_asset_amounts(
            node_c,
            channel_bc,
            0,
            OPEN_CHANNEL_ASSET_AMOUNT,
            "node C after restart",
        )

        assert_offchain_balances(
            node_a,
            asset_id,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            PAYMENT_ASSET_AMOUNT,
            "node A after restart",
        )
        assert_offchain_balances(
            node_b,
            asset_id,
            OPEN_CHANNEL_ASSET_AMOUNT + PAYMENT_ASSET_AMOUNT,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            "node B after restart",
        )
        assert_offchain_balances(
            node_c,
            asset_id,
            0,
            OPEN_CHANNEL_ASSET_AMOUNT,
            "node C after restart",
        )

        run_hodl_cancel_phase(node_b, node_c, asset_id, channel_bc, channel_bc)
        assert_offchain_balances(
            node_a,
            asset_id,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            PAYMENT_ASSET_AMOUNT,
            "node A",
        )
        assert_offchain_balances(
            node_b,
            asset_id,
            OPEN_CHANNEL_ASSET_AMOUNT + PAYMENT_ASSET_AMOUNT,
            OPEN_CHANNEL_ASSET_AMOUNT - PAYMENT_ASSET_AMOUNT,
            "node B",
        )
        assert_offchain_balances(node_c, asset_id, 0, OPEN_CHANNEL_ASSET_AMOUNT, "node C")

        print("SUCCESS: Python HODL 3-node flow completed")
    finally:
        safe_shutdown(node_a)
        safe_shutdown(node_b)
        safe_shutdown(node_c)
