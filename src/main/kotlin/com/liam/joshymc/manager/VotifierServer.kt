package com.liam.joshymc.manager

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.liam.joshymc.Joshymc
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Drop-in Votifier protocol server. Implements both:
 *  - v1 (legacy): 256-byte RSA/ECB/PKCS1Padding block, newline-separated VOTE payload
 *  - v2 (token): 4-byte frame [0x733A][len], JSON with HMAC-SHA256 signature
 *
 * Wire format and semantics match NuVotifier exactly so any vote site that works
 * with Votifier/NuVotifier works here. Keys are stored as base64-encoded DER in
 * plugins/Joshymc/votifier/{public,private}.key — the same format NuVotifier uses,
 * so existing keys can be dropped in.
 */
class VotifierServer(
    private val plugin: Joshymc,
    private val port: Int,
    private val keyPair: KeyPair,
    private val tokens: Map<String, String>,
    private val onVote: (username: String, service: String) -> Unit
) {
    private val secureRandom = SecureRandom()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "JoshyMC-Votifier-Worker").also { it.isDaemon = true }
    }

    fun start() {
        val sock = ServerSocket(port)
        serverSocket = sock
        acceptThread = Thread({
            while (!sock.isClosed) {
                val client = try {
                    sock.accept()
                } catch (e: IOException) {
                    if (!sock.isClosed) plugin.logger.warning("[Votifier] Accept error: ${e.message}")
                    break
                }
                workers.submit { handleConnection(client) }
            }
        }, "JoshyMC-Votifier-Accept").apply { isDaemon = true; start() }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        acceptThread = null
        workers.shutdown()
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) workers.shutdownNow()
        } catch (_: InterruptedException) {
            workers.shutdownNow()
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CONNECTION HANDLING
    // ══════════════════════════════════════════════════════════

    private fun handleConnection(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 5000
                val out = sock.getOutputStream()
                val challenge = generateChallenge()

                // NuVotifier-compatible greeting. v1 clients ignore the suffix.
                out.write("VOTIFIER 2 $challenge\n".toByteArray(Charsets.UTF_8))
                out.flush()

                val input = PushbackInputStream(sock.getInputStream(), 2)
                val b1 = input.read()
                val b2 = input.read()
                if (b1 == -1 || b2 == -1) return
                input.unread(b2)
                input.unread(b1)

                if (b1 == 0x73 && b2 == 0x3A) {
                    handleV2(input, out, challenge)
                } else {
                    handleV1(input)
                }
            } catch (e: Exception) {
                plugin.logger.warning("[Votifier] Error handling ${sock.remoteSocketAddress}: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  V1 — RSA encrypted block
    // ══════════════════════════════════════════════════════════

    private fun handleV1(input: PushbackInputStream) {
        val block = ByteArray(256)
        var read = 0
        while (read < 256) {
            val n = input.read(block, read, 256 - read)
            if (n == -1) {
                plugin.logger.warning("[Votifier] v1: short read ($read/256) — dropping")
                return
            }
            read += n
        }

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        val decrypted = try {
            cipher.doFinal(block)
        } catch (e: Exception) {
            plugin.logger.warning("[Votifier] v1: decryption failed — wrong public key on vote site?")
            return
        }

        val payload = String(decrypted, Charsets.UTF_8)
        val lines = payload.split("\n")
        if (lines.size < 5 || lines[0] != "VOTE") {
            plugin.logger.warning("[Votifier] v1: malformed payload")
            return
        }
        val service = lines[1]
        val username = lines[2]
        // lines[3] = address, lines[4] = timestamp — not used
        if (username.isBlank()) return
        onVote(username, service)
        // v1 has no response
    }

    // ══════════════════════════════════════════════════════════
    //  V2 — HMAC-SHA256 token with JSON framing
    // ══════════════════════════════════════════════════════════

    private fun handleV2(input: PushbackInputStream, out: OutputStream, challenge: String) {
        val dis = DataInputStream(input)
        val magic = dis.readShort().toInt() and 0xFFFF
        if (magic != 0x733A) {
            sendV2Error(out, "unknown magic: 0x${magic.toString(16)}")
            return
        }
        val length = dis.readShort().toInt() and 0xFFFF
        if (length <= 0 || length > 4096) {
            sendV2Error(out, "invalid payload length: $length")
            return
        }
        val bytes = ByteArray(length)
        dis.readFully(bytes)
        val envelope = String(bytes, Charsets.UTF_8)

        val outer: JsonObject = try {
            JsonParser.parseString(envelope).asJsonObject
        } catch (e: Exception) {
            sendV2Error(out, "malformed json envelope")
            return
        }
        val signatureB64 = outer.get("signature")?.asString
        val payloadStr = outer.get("payload")?.asString
        if (signatureB64 == null || payloadStr == null) {
            sendV2Error(out, "envelope missing signature or payload")
            return
        }

        val expected = try {
            Base64.getDecoder().decode(signatureB64)
        } catch (e: Exception) {
            sendV2Error(out, "signature not base64")
            return
        }

        // Identify token: check the payload's serviceName → token entry first
        // (NuVotifier behavior), then fall back to trying every configured token.
        val payload: JsonObject = try {
            JsonParser.parseString(payloadStr).asJsonObject
        } catch (e: Exception) {
            sendV2Error(out, "malformed json payload")
            return
        }
        val serviceName = payload.get("serviceName")?.asString ?: ""
        val username = payload.get("username")?.asString ?: ""
        val receivedChallenge = payload.get("challenge")?.asString

        if (receivedChallenge != challenge) {
            sendV2Error(out, "challenge mismatch")
            return
        }
        if (username.isBlank()) {
            sendV2Error(out, "missing username")
            return
        }

        val payloadBytes = payloadStr.toByteArray(Charsets.UTF_8)
        val matched = findMatchingToken(serviceName, payloadBytes, expected)
        if (matched == null) {
            sendV2Error(out, "signature does not match any configured token")
            return
        }

        onVote(username, serviceName)
        sendV2Ok(out)
    }

    private fun findMatchingToken(service: String, payload: ByteArray, expected: ByteArray): String? {
        // Try a token named exactly for this service first, then "default", then everything else.
        val order = buildList {
            if (service.isNotBlank() && tokens.containsKey(service)) add(service)
            if (tokens.containsKey("default")) add("default")
            for (name in tokens.keys) if (name != service && name != "default") add(name)
        }
        for (name in order) {
            val token = tokens[name] ?: continue
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val computed = mac.doFinal(payload)
            if (MessageDigest.isEqual(computed, expected)) return name
        }
        return null
    }

    private fun sendV2Ok(out: OutputStream) {
        try {
            out.write("""{"status":"ok"}""".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: IOException) {}
    }

    private fun sendV2Error(out: OutputStream, cause: String) {
        try {
            val escaped = cause.replace("\\", "\\\\").replace("\"", "\\\"")
            out.write("""{"status":"error","cause":"$escaped","error":"$escaped"}""".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: IOException) {}
    }

    // ══════════════════════════════════════════════════════════
    //  CHALLENGE
    // ══════════════════════════════════════════════════════════

    private fun generateChallenge(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder(64)
        for (b in bytes) sb.append("%02x".format(b))
        return sb.toString()
    }

    companion object {
        /**
         * Loads an RSA keypair from `plugins/Joshymc/votifier/` or generates a
         * new 2048-bit one if the files do not exist. Format matches NuVotifier:
         * base64-encoded DER, X.509 for public, PKCS#8 for private.
         */
        fun loadOrGenerateKeyPair(plugin: Joshymc): KeyPair {
            val dir = File(plugin.dataFolder, "votifier").apply { mkdirs() }
            val pubFile = File(dir, "public.key")
            val privFile = File(dir, "private.key")

            if (pubFile.exists() && privFile.exists()) {
                val kf = KeyFactory.getInstance("RSA")
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubFile.readText().trim())))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privFile.readText().trim())))
                return KeyPair(pub, priv)
            }

            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom())
            val kp = gen.generateKeyPair()
            pubFile.writeText(Base64.getEncoder().encodeToString(kp.public.encoded))
            privFile.writeText(Base64.getEncoder().encodeToString(kp.private.encoded))
            plugin.logger.info("[Votifier] Generated new 2048-bit RSA keypair at ${dir.absolutePath}")
            return kp
        }
    }
}
