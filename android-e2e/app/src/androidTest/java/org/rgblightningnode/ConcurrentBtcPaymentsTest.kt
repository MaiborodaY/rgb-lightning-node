package org.rgblightningnode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.utexo.rgblightningnode.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ConcurrentBtcPaymentsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storageBase = context.filesDir.absolutePath

    private val bitcoindHost = "10.0.2.2"
    private val bitcoindPort = 18443
    private val bitcoindUser = "user"
    private val bitcoindPass = "password"
    private val proxyEndpoint = "rpc://10.0.2.2:3000/json-rpc"

    private val nodeADaemonPort: UShort = 4211u
    private val nodeBDaemonPort: UShort = 4212u
    private val nodeCDaemonPort: UShort = 4213u
    private val nodeDDaemonPort: UShort = 4214u
    private val nodeAPeerPort: UShort = 13611u
    private val nodeBPeerPort: UShort = 13612u
    private val nodeCPeerPort: UShort = 13613u
    private val nodeDPeerPort: UShort = 13614u

    private val channelCapacitySat: ULong = 100_000u
    private val channelPushMsat: ULong = 0u
    private val invoiceAmtMsat1: ULong = 4_000_000u
    private val invoiceAmtMsat2: ULong = 5_000_000u
    private val utxosNum: UByte = 10u
    private val utxosFeeRate: ULong = 7u
    private val channelReadyTimeoutSec: Long = 300L

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
        val addr = bitcoindRpc("getnewaddress").getString("result")
        bitcoindRpc("generatetoaddress", blocks, addr)
        log("mined $blocks block(s)")
    }

    private fun sendToAddress(address: String, amountBtc: String) {
        bitcoindRpc("sendtoaddress", address, amountBtc.toDouble())
        log("sent $amountBtc BTC to $address")
    }

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

    private fun initNode(node: SdkNode, password: String, name: String) {
        node.init(password, null)
        log("$name: initialized")
    }

    private fun unlockNode(node: SdkNode, password: String, name: String) {
        node.unlock(unlockRequest(password))
        log("$name: unlocked")
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

    private fun fundAndCreateUtxos(node: SdkNode, name: String) {
        ensureFunded(node, name, 1u, "1")
        node.createutxos(
            SdkCreateUtxosRequest(
                upTo = false,
                num = utxosNum,
                size = null,
                feeRate = utxosFeeRate,
                skipSync = false,
            )
        )
        log("$name: createutxos done")
        mine(1)
        node.sync()
    }

    private fun connectPeer(node: SdkNode, peerPubkey: String, peerPort: UShort, name: String) {
        val peerUri = "$peerPubkey@127.0.0.1:${peerPort.toInt()}"
        try {
            node.connectpeer(peerUri)
            log("$name connectpeer: ok")
        } catch (_: RlnException.Conflict) {
            log("$name connectpeer: already connected")
        }
    }

    private fun waitForChannelFundingTx(
        node: SdkNode,
        matcher: (Channel) -> Boolean,
        timeoutSec: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        while (System.currentTimeMillis() < deadline) {
            node.sync()
            val found = node.listChannels().any { matcher(it) && it.fundingTxid != null }
            if (found) {
                log("channel funding tx found")
                return
            }
            log("waiting for channel funding tx...")
            Thread.sleep(1_000L)
        }
        error("no channel funding tx after ${timeoutSec}s")
    }

    private fun waitForUsableChannel(
        node: SdkNode,
        matcher: (Channel) -> Boolean,
        timeoutSec: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var polls = 0
        while (System.currentTimeMillis() < deadline) {
            polls++
            node.sync()
            val usable = node.listChannels().any { matcher(it) && it.isUsable }
            if (usable) {
                log("channel is usable")
                return
            }
            if (polls % 5 == 0) {
                log("mining 1 block...")
                mine(1)
            }
            log("waiting for usable channel... (poll $polls)")
            Thread.sleep(2_000L)
        }
        error("channel not usable after ${timeoutSec}s")
    }

    private fun waitForObservedPayments(
        node: SdkNode,
        paymentHashes: List<PaymentHash>,
        timeoutSec: Long,
    ): List<Payment> {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var lastPayments = emptyList<Payment>()
        while (System.currentTimeMillis() < deadline) {
            node.sync()
            val payments = node.listPayments()
            lastPayments = payments
            val observed = payments.filter { it.paymentHash in paymentHashes }
            if (observed.size == paymentHashes.size && observed.none { it.status == HtlcStatus.FAILED }) {
                return observed
            }
            Thread.sleep(1_000L)
        }
        error(
            "did not observe ${paymentHashes.size} payments after ${timeoutSec}s; " +
                "last_count=${lastPayments.size} last_hashes=${lastPayments.map { it.paymentHash }} " +
                "last_statuses=${lastPayments.map { it.status.name }}"
        )
    }

    private fun waitForPaymentStatus(node: SdkNode, paymentHash: PaymentHash, timeoutSec: Long): Payment {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var last = "not found"
        while (System.currentTimeMillis() < deadline) {
            try {
                val payment = node.getPayment(paymentHash)
                last = payment.status.name
                if (payment.status == HtlcStatus.SUCCEEDED) {
                    return payment
                }
            } catch (_: RlnException.NotFound) {
                last = "not found"
            }
            Thread.sleep(1_000L)
        }
        error("payment did not succeed after ${timeoutSec}s, last=$last")
    }

    private fun waitForInvoiceStatus(
        node: SdkNode,
        invoice: String,
        expected: InvoiceStatus,
        timeoutSec: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var last = InvoiceStatus.PENDING
        while (System.currentTimeMillis() < deadline) {
            node.sync()
            val status = node.invoiceStatus(invoice)
            last = status
            if (status == expected) {
                return
            }
            Thread.sleep(1_000L)
        }
        error("invoice did not reach $expected after ${timeoutSec}s, last=$last")
    }

    private fun waitForChannelLocalBalanceMsat(
        node: SdkNode,
        channelId: String,
        expectedMsat: ULong,
        timeoutSec: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1_000L
        var lastMsat: ULong? = null
        while (System.currentTimeMillis() < deadline) {
            node.sync()
            val channel = node.listChannels().firstOrNull { it.channelId == channelId }
            lastMsat = channel?.localBalanceSat?.times(1000u)
            if (lastMsat == expectedMsat) {
                return
            }
            Thread.sleep(1_000L)
        }
        error("channel local balance did not become expected=$expectedMsat actual=$lastMsat")
    }

    private fun log(msg: String) {
        android.util.Log.i("ConcurrentBtcPaymentsTest", msg)
    }

    private fun safeShutdown(node: SdkNode?) {
        try {
            node?.shutdown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun concurrentBtcPayments() {
        File("$storageBase/concurrent_btc_payments/node_a").deleteRecursively()
        File("$storageBase/concurrent_btc_payments/node_b").deleteRecursively()
        File("$storageBase/concurrent_btc_payments/node_c").deleteRecursively()
        File("$storageBase/concurrent_btc_payments/node_d").deleteRecursively()

        val nodeA = makeNode("concurrent_btc_payments/node_a", nodeADaemonPort, nodeAPeerPort)
        val nodeB = makeNode("concurrent_btc_payments/node_b", nodeBDaemonPort, nodeBPeerPort)
        val nodeC = makeNode("concurrent_btc_payments/node_c", nodeCDaemonPort, nodeCPeerPort)
        val nodeD = makeNode("concurrent_btc_payments/node_d", nodeDDaemonPort, nodeDPeerPort)
        var step = "start"
        try {
            step = "initA"; initNode(nodeA, "nodeApass", "node A")
            step = "initB"; initNode(nodeB, "nodeBpass", "node B")
            step = "initC"; initNode(nodeC, "nodeCpass", "node C")
            step = "initD"; initNode(nodeD, "nodeDpass", "node D")
            step = "unlockA"; unlockNode(nodeA, "nodeApass", "node A")
            step = "unlockB"; unlockNode(nodeB, "nodeBpass", "node B")
            step = "unlockC"; unlockNode(nodeC, "nodeCpass", "node C")
            step = "unlockD"; unlockNode(nodeD, "nodeDpass", "node D")

            step = "fundA"; fundAndCreateUtxos(nodeA, "node A")
            step = "fundB"; fundAndCreateUtxos(nodeB, "node B")
            step = "fundC"; fundAndCreateUtxos(nodeC, "node C")
            step = "fundD"; fundAndCreateUtxos(nodeD, "node D")

            step = "nodeInfo"
            val infoA = nodeA.nodeInfo()
            val infoB = nodeB.nodeInfo()
            log("node A pubkey: ${infoA.pubkey}")
            log("node B pubkey: ${infoB.pubkey}")

            step = "connectBtoA"; connectPeer(nodeB, infoA.pubkey, nodeAPeerPort, "node B")
            step = "connectCtoB"; connectPeer(nodeC, infoB.pubkey, nodeBPeerPort, "node C")
            step = "connectDtoB"; connectPeer(nodeD, infoB.pubkey, nodeBPeerPort, "node D")

            step = "openBtoA"
            val openBtoA = nodeB.openchannel(
                SdkOpenChannelRequest(
                    peerPubkeyAndOptAddr = "${infoA.pubkey}@127.0.0.1:${nodeAPeerPort.toInt()}",
                    capacitySat = channelCapacitySat,
                    pushMsat = channelPushMsat,
                    `public` = true,
                    withAnchors = true,
                    feeBaseMsat = null,
                    feeProportionalMillionths = null,
                    temporaryChannelId = null,
                    assetId = null,
                    assetAmount = null,
                    pushAssetAmount = null,
                )
            )
            step = "waitFundingBtoA"
            waitForChannelFundingTx(nodeB, { it.peerPubkey == infoA.pubkey && it.assetId == null }, 120L)
            step = "waitUsableBtoA"
            waitForUsableChannel(nodeB, { it.channelId == nodeB.getChannelId(openBtoA.temporaryChannelId) }, channelReadyTimeoutSec)

            step = "openCtoB"
            val openCtoB = nodeC.openchannel(
                SdkOpenChannelRequest(
                    peerPubkeyAndOptAddr = "${infoB.pubkey}@127.0.0.1:${nodeBPeerPort.toInt()}",
                    capacitySat = channelCapacitySat,
                    pushMsat = channelPushMsat,
                    `public` = true,
                    withAnchors = true,
                    feeBaseMsat = null,
                    feeProportionalMillionths = null,
                    temporaryChannelId = null,
                    assetId = null,
                    assetAmount = null,
                    pushAssetAmount = null,
                )
            )
            step = "waitFundingCtoB"
            waitForChannelFundingTx(nodeC, { it.peerPubkey == infoB.pubkey && it.assetId == null }, 120L)
            step = "waitUsableCtoB"
            waitForUsableChannel(nodeC, { it.channelId == nodeC.getChannelId(openCtoB.temporaryChannelId) }, channelReadyTimeoutSec)

            step = "openDtoB"
            val openDtoB = nodeD.openchannel(
                SdkOpenChannelRequest(
                    peerPubkeyAndOptAddr = "${infoB.pubkey}@127.0.0.1:${nodeBPeerPort.toInt()}",
                    capacitySat = channelCapacitySat,
                    pushMsat = channelPushMsat,
                    `public` = true,
                    withAnchors = true,
                    feeBaseMsat = null,
                    feeProportionalMillionths = null,
                    temporaryChannelId = null,
                    assetId = null,
                    assetAmount = null,
                    pushAssetAmount = null,
                )
            )
            step = "waitFundingDtoB"
            waitForChannelFundingTx(nodeD, { it.peerPubkey == infoB.pubkey && it.assetId == null }, 120L)
            step = "waitUsableDtoB"
            waitForUsableChannel(nodeD, { it.channelId == nodeD.getChannelId(openDtoB.temporaryChannelId) }, channelReadyTimeoutSec)

            step = "channelsBefore"
            val channelsA = nodeA.listChannels()
            assertEquals(1, channelsA.size)
            val channelA = channelsA.first()
            assertEquals(0uL, channelA.localBalanceSat)

            step = "invoice1"
            val invoice1 = nodeA.lnInvoice(
                LnInvoiceRequest(
                    amtMsat = invoiceAmtMsat1,
                    expirySec = 900u,
                    assetId = null,
                    assetAmount = null,
                )
            ).invoice

            step = "invoice2"
            val invoice2 = nodeA.lnInvoice(
                LnInvoiceRequest(
                    amtMsat = invoiceAmtMsat2,
                    expirySec = 900u,
                    assetId = null,
                    assetAmount = null,
                )
            ).invoice
            val decoded1 = nodeA.decodeLnInvoice(invoice1)
            val decoded2 = nodeA.decodeLnInvoice(invoice2)

            step = "sendConcurrent"
            var response1: SdkSendPaymentResponse? = null
            var response2: SdkSendPaymentResponse? = null
            var error1: Throwable? = null
            var error2: Throwable? = null
            val thread1 = Thread {
                try {
                    response1 = nodeC.sendpayment(
                        SdkSendPaymentRequest(
                            invoice = invoice1,
                            amtMsat = null,
                            assetId = null,
                            assetAmount = null,
                        )
                    )
                } catch (t: Throwable) {
                    error1 = t
                }
            }
            val thread2 = Thread {
                try {
                    response2 = nodeD.sendpayment(
                        SdkSendPaymentRequest(
                            invoice = invoice2,
                            amtMsat = null,
                            assetId = null,
                            assetAmount = null,
                        )
                    )
                } catch (t: Throwable) {
                    error2 = t
                }
            }
            thread1.start()
            thread2.start()
            thread1.join()
            thread2.join()
            if (error1 != null) throw RuntimeException("sendpayment from node C failed", error1)
            if (error2 != null) throw RuntimeException("sendpayment from node D failed", error2)
            assertEquals(HtlcStatus.PENDING, response1!!.status)
            assertEquals(HtlcStatus.PENDING, response2!!.status)

            step = "receiverPaymentsObserved"
            val receiverPayments = waitForObservedPayments(
                nodeA,
                listOf(decoded1.paymentHash, decoded2.paymentHash),
                30L,
            )
            assertEquals(2, receiverPayments.size)
            assertTrue(receiverPayments.none { it.status == HtlcStatus.FAILED })

            step = "waitPayment1"
            val payment1Sender = waitForPaymentStatus(nodeC, response1!!.paymentHash!!, 60L)
            step = "waitPayment2"
            val payment2Sender = waitForPaymentStatus(nodeD, response2!!.paymentHash!!, 60L)
            assertEquals(HtlcStatus.SUCCEEDED, payment1Sender.status)
            assertEquals(HtlcStatus.SUCCEEDED, payment2Sender.status)

            step = "invoiceStatus1"
            waitForInvoiceStatus(nodeA, invoice1, InvoiceStatus.SUCCEEDED, 60L)
            step = "invoiceStatus2"
            waitForInvoiceStatus(nodeA, invoice2, InvoiceStatus.SUCCEEDED, 60L)

            step = "decodeInvoices"
            val payments = nodeA.listPayments()
            val payment1 = payments.first { it.paymentHash == decoded1.paymentHash }
            val payment2 = payments.first { it.paymentHash == decoded2.paymentHash }
            assertEquals(invoiceAmtMsat1, payment1.amtMsat)
            assertEquals(invoiceAmtMsat2, payment2.amtMsat)
            assertEquals(HtlcStatus.SUCCEEDED, payment1.status)
            assertEquals(HtlcStatus.SUCCEEDED, payment2.status)

            step = "channelBalanceAfter"
            waitForChannelLocalBalanceMsat(nodeA, channelA.channelId, invoiceAmtMsat1 + invoiceAmtMsat2, 30L)
            val channelsAfter = nodeA.listChannels()
            assertEquals(1, channelsAfter.size)
            assertEquals(invoiceAmtMsat1 + invoiceAmtMsat2, channelsAfter.first().localBalanceSat * 1000u)
        } catch (t: Throwable) {
            throw RuntimeException("FAILED at step=$step: ${t.message}", t)
        } finally {
            Thread.sleep(1_000L)
            safeShutdown(nodeD)
            safeShutdown(nodeC)
            safeShutdown(nodeB)
            safeShutdown(nodeA)
        }
    }
}
