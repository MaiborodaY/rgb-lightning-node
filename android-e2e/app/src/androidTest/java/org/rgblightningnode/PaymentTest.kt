package org.rgblightningnode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.utexo.rgblightningnode.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class PaymentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storageBase = context.filesDir.absolutePath

    private val bitcoindHost = "10.0.2.2"
    private val bitcoindPort = 18443
    private val bitcoindUser = "user"
    private val bitcoindPass = "password"
    private val proxyEndpoint = "rpc://10.0.2.2:3000/json-rpc"

    private val nodeADaemonPort: UShort = 3711u
    private val nodeBDaemonPort: UShort = 3712u
    private val nodeAPeerPort: UShort = 13111u
    private val nodeBPeerPort: UShort = 13112u

    private val channelCapacitySat: ULong = 500_000u
    private val paymentMsat: ULong = 3_000_000u
    private val utxosNum: UByte = 10u
    private val utxosFeeRate: ULong = 7u
    private val assetSupply: ULong = 1000u
    private val channelAssetAmount: ULong = 200u
    private val paymentAssetAmount: ULong = 50u
    private val channelReadyTimeoutSec: Long = 300L

    // ── Bitcoin RPC ──────────────────────────────────────────────────────────

    private fun bitcoindRpc(method: String, vararg params: Any): JSONObject {
        val url = URL("http://$bitcoindHost:$bitcoindPort/")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val creds = Base64.getEncoder().encodeToString("$bitcoindUser:$bitcoindPass".toByteArray())
        conn.setRequestProperty("Authorization", "Basic $creds")

        val body = JSONObject().apply {
            put("jsonrpc", "1.0")
            put("id", "android-e2e")
            put("method", method)
            put("params", JSONArray().apply { params.forEach { put(it) } })
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }

        val response = conn.inputStream.bufferedReader().readText()
        return JSONObject(response)
    }

    private fun mine(blocks: Int) {
        val addrResp = bitcoindRpc("getnewaddress")
        val addr = addrResp.getString("result")
        bitcoindRpc("generatetoaddress", blocks, addr)
        log("mined $blocks block(s)")
    }

    private fun sendToAddress(address: String, amountBtc: String) {
        bitcoindRpc("sendtoaddress", address, amountBtc.toDouble())
        log("sent $amountBtc BTC to $address")
    }

    // ── Node helpers ─────────────────────────────────────────────────────────

    private fun makeNode(name: String, daemonPort: UShort, peerPort: UShort): SdkNode {
        return SdkNode.create(
            SdkInitRequest(
                storageDirPath = "$storageBase/$name",
                daemonListeningPort = daemonPort,
                ldkPeerListeningPort = peerPort,
                network = "regtest",
                maxMediaUploadSizeMb = 20u,
            )
        )
    }

    private fun unlockRequest(password: String) = SdkUnlockRequest(
        password = password,
        bitcoindRpcUsername = bitcoindUser,
        bitcoindRpcPassword = bitcoindPass,
        bitcoindRpcHost = bitcoindHost,
        bitcoindRpcPort = bitcoindPort.toUShort(),
        indexerUrl = "$bitcoindHost:50001",
        proxyEndpoint = proxyEndpoint,
        announceAddresses = listOf(),
        announceAlias = null,
    )

    private fun initIfNeeded(node: SdkNode, password: String, name: String) {
        try {
            node.init(password, null)
            log("$name: initialized")
        } catch (_: RlnException.Conflict) {
            log("$name: already initialized")
        }
    }

    private fun unlockIfNeeded(node: SdkNode, password: String, name: String) {
        try {
            node.unlock(unlockRequest(password))
            log("$name: unlocked")
        } catch (_: RlnException.Conflict) {
            log("$name: already unlocked")
        }
    }

    private fun ensureFunded(node: SdkNode, name: String, minSat: ULong, amountBtc: String) {
        val spendable = node.btcBalance(false).vanilla.spendable
        log("$name spendable: $spendable sat")
        if (spendable >= minSat) return
        val address = node.address().address
        sendToAddress(address, amountBtc)
        mine(6)
        node.sync()
        val after = node.btcBalance(false).vanilla.spendable
        log("$name spendable after fund: $after sat")
        assertTrue("$name still underfunded: $after < $minSat", after >= minSat)
    }

    private fun waitForChannelFundingTx(nodeA: SdkNode, nodeB: SdkNode, assetId: ContractId, timeoutSec: Long) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        while (System.currentTimeMillis() < deadline) {
            nodeA.sync(); nodeB.sync()
            val found = nodeA.listChannels().any { it.assetId == assetId && it.fundingTxid != null }
            if (found) { log("channel funding tx found"); return }
            log("waiting for channel funding tx...")
            Thread.sleep(1_000L)
        }
        error("no channel funding tx after ${timeoutSec}s")
    }

    private fun waitForUsableChannel(nodeA: SdkNode, nodeB: SdkNode, assetId: ContractId, timeoutSec: Long) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            polls++
            nodeA.sync(); nodeB.sync()
            val usable = nodeA.listChannels().any { it.isUsable && it.assetId == assetId }
            if (usable) { log("channel is usable"); return }
            if (polls % 5 == 0) { log("mining 1 block..."); mine(1) }
            log("waiting for usable channel... (poll $polls)")
            Thread.sleep(2_000L)
        }
        error("channel not usable after ${timeoutSec}s")
    }

    private fun waitPaymentFinal(node: SdkNode, invoice: String, timeoutSec: Long = 60L): InvoiceStatus {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var last = InvoiceStatus.PENDING
        while (System.currentTimeMillis() < deadline) {
            node.sync()
            val status = node.invoiceStatus(invoice)
            last = status
            if (status == InvoiceStatus.SUCCEEDED || status == InvoiceStatus.FAILED || status == InvoiceStatus.EXPIRED) {
                return status
            }
            Thread.sleep(1_000L)
        }
        error("invoice did not finalize after ${timeoutSec}s, last=$last")
    }

    private fun log(msg: String) {
        android.util.Log.i("PaymentTest", msg)
    }

    private fun safeShutdown(node: SdkNode?) {
        try {
            node?.shutdown()
        } catch (_: Exception) {
        }
    }

    // ── Test ─────────────────────────────────────────────────────────────────

    @Test
    fun payment() {
        File("$storageBase/payment/node_a").deleteRecursively()
        File("$storageBase/payment/node_b").deleteRecursively()

        val nodeA = makeNode("payment/node_a", nodeADaemonPort, nodeAPeerPort)
        val nodeB = makeNode("payment/node_b", nodeBDaemonPort, nodeBPeerPort)
        var step = "start"
        try {
            step = "initA";    initIfNeeded(nodeA, "nodeApass", "node A")
            step = "initB";    initIfNeeded(nodeB, "nodeBpass", "node B")
            step = "unlockA";  unlockIfNeeded(nodeA, "nodeApass", "node A")
            step = "unlockB";  unlockIfNeeded(nodeB, "nodeBpass", "node B")

            step = "fundA";    ensureFunded(nodeA, "node A", channelCapacitySat + 200_000u, "0.02")
            step = "fundB";    ensureFunded(nodeB, "node B", 200_000u, "0.02")

            step = "utxosA";   nodeA.createutxos(SdkCreateUtxosRequest(upTo = false, num = utxosNum, size = 100_000u, feeRate = utxosFeeRate, skipSync = false))
            step = "utxosB";   nodeB.createutxos(SdkCreateUtxosRequest(upTo = false, num = utxosNum, size = 100_000u, feeRate = utxosFeeRate, skipSync = false))
            step = "mine1";    mine(1)
            step = "syncAB";   nodeA.sync(); nodeB.sync()

            step = "issueNia"; val assetId = nodeA.issueassetnia(
                SdkIssueAssetNiaRequest(
                    amounts = listOf(assetSupply),
                    ticker = "USDT",
                    name = "Tether",
                    precision = 0u,
                )
            ).assetId
            log("issued asset: $assetId")
            step = "mineIssue"; mine(1); nodeA.sync()

            step = "nodeInfo";  val infoA = nodeA.nodeInfo(); val infoB = nodeB.nodeInfo()
            log("node A pubkey: ${infoA.pubkey}")
            log("node B pubkey: ${infoB.pubkey}")

            val peerUri = "${infoB.pubkey}@127.0.0.1:${nodeBPeerPort.toInt()}"
            step = "connectpeer"
            try {
                nodeA.connectpeer(peerUri)
                log("connectpeer: ok")
            } catch (_: RlnException.Conflict) {
                log("connectpeer: already connected")
            }

            step = "openchannel"
            nodeA.openchannel(
                SdkOpenChannelRequest(
                    peerPubkeyAndOptAddr = peerUri,
                    capacitySat = channelCapacitySat,
                    pushMsat = 0u,
                    `public` = false,
                    withAnchors = true,
                    feeBaseMsat = null,
                    feeProportionalMillionths = null,
                    temporaryChannelId = null,
                    assetId = assetId,
                    assetAmount = channelAssetAmount,
                    pushAssetAmount = null,
                )
            )
            log("openchannel sent")

            step = "waitFundingTx";  waitForChannelFundingTx(nodeA, nodeB, assetId, 120L)
            step = "mine6";          mine(6)
            step = "waitUsable";     waitForUsableChannel(nodeA, nodeB, assetId, channelReadyTimeoutSec)

            step = "lnInvoice"
            val invoice = nodeB.lnInvoice(
                LnInvoiceRequest(
                    amtMsat = paymentMsat,
                    expirySec = 3600u,
                    assetId = assetId,
                    assetAmount = paymentAssetAmount,
                )
            ).invoice
            log("invoice: $invoice")

            step = "sendpayment"
            val payResp = nodeA.sendpayment(
                SdkSendPaymentRequest(
                    invoice = invoice,
                    amtMsat = null,
                    assetId = null,
                    assetAmount = null,
                )
            )
            log("sendpayment status: ${payResp.status.name}")

            step = "waitPayment"
            val finalStatus = waitPaymentFinal(nodeB, invoice)
            log("invoice final status: ${finalStatus.name}")
            assertEquals("payment did not succeed", InvoiceStatus.SUCCEEDED, finalStatus)

            log("SUCCESS: Android SDK node-to-node RGB payment completed")
        } catch (e: Exception) {
            throw RuntimeException("FAILED at step=$step: ${e.message}", e)
        } finally {
            safeShutdown(nodeA)
            safeShutdown(nodeB)
            Thread.sleep(1_000L)
        }
    }
}
