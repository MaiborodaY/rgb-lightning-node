use super::*;

const TEST_DIR_BASE: &str = "tmp/openchannel_rgb_funding_race/";
const DEFAULT_ATTEMPTS: u32 = 5;

struct RgbOpenChannelRaceDelayGuard;

impl RgbOpenChannelRaceDelayGuard {
    fn enable() -> Self {
        crate::routes::set_rgb_openchannel_race_force_metadata_delay(true);
        Self
    }
}

impl Drop for RgbOpenChannelRaceDelayGuard {
    fn drop(&mut self) {
        crate::routes::set_rgb_openchannel_race_force_metadata_delay(false);
    }
}

#[serial_test::serial]
#[tokio::test(flavor = "multi_thread", worker_threads = 1)]
#[traced_test]
#[ignore = "expected to fail until the RGB /openchannel FundingGenerationReady metadata race is fixed"]
async fn rgb_openchannel_without_explicit_temporary_channel_id_stress() {
    initialize();

    let attempts = std::env::var("RGB_OPENCHANNEL_RACE_ATTEMPTS")
        .ok()
        .and_then(|value| value.parse::<u32>().ok())
        .unwrap_or(DEFAULT_ATTEMPTS);

    // Enable the cfg(test) hook in /openchannel so the race is reproducible without
    // relying on scheduler timing.
    let _race_delay_guard = RgbOpenChannelRaceDelayGuard::enable();

    for attempt in 1..=attempts {
        println!("RGB openchannel funding race repro attempt {attempt}/{attempts}");

        let test_dir_base = format!("{TEST_DIR_BASE}attempt_{attempt}/");
        let test_dir_node1 = format!("{test_dir_base}node1");
        let test_dir_node2 = format!("{test_dir_base}node2");
        let (node1_addr, _) = start_node(&test_dir_node1, NODE1_PEER_PORT, false).await;
        let (node2_addr, _) = start_node(&test_dir_node2, NODE2_PEER_PORT, false).await;

        let node1_funding_address = address(node1_addr).await;
        _fund_wallet(node1_funding_address);
        mine(false);
        create_utxos(node1_addr, false, Some(10), Some(100_000)).await;
        mine(false);

        let node2_funding_address = address(node2_addr).await;
        _fund_wallet(node2_funding_address);
        mine(false);
        create_utxos(node2_addr, false, Some(10), Some(100_000)).await;
        mine(false);

        let asset_id = issue_asset_nia(node1_addr).await.asset_id;
        let node2_pubkey = node_info(node2_addr).await.pubkey;

        let channel = open_channel_with_custom_data(
            node1_addr,
            &node2_pubkey,
            Some(NODE2_PEER_PORT),
            Some(500_000),
            Some(0),
            Some(200),
            Some(&asset_id),
            None,
            None,
            None,
            None,
            true,
        )
        .await;

        assert_eq!(channel.asset_id, Some(asset_id));
        assert_eq!(channel.asset_local_amount, Some(200));
        assert_eq!(channel.asset_remote_amount, Some(0));

        shutdown(&[node1_addr, node2_addr]).await;
    }
}
