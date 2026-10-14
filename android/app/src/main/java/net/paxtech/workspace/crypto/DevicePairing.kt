package net.paxtech.workspace.crypto

import com.google.crypto.tink.subtle.Hkdf
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.paxtech.workspace.data.*

@Singleton
class DevicePairing
@Inject
constructor(private val repo: WorkspaceRepository, private val vault: Vault) {
    suspend fun key(agentId: String): RootKey? {
        val account = repo.credential().userId
        return vault.read().keys.firstOrNull { it.account == account && it.agentId == agentId }
    }

    suspend fun pending(agentId: String): Pairing? {
        val account = repo.credential().userId
        return vault.read().pairings.firstOrNull { it.account == account && it.agentId == agentId }
    }

    suspend fun start(agentId: String, epoch: Int = 1): Pairing =
        withContext(Dispatchers.Default) {
            pending(agentId)?.let {
                return@withContext it
            }
            val pair =
                KeyPairGenerator.getInstance("EC")
                    .apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            val public = pair.public as ECPublicKey
            fun coordinate(n: BigInteger): ByteArray =
                n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
            val encodedPublic =
                encode64(
                    byteArrayOf(4) + coordinate(public.w.affineX) + coordinate(public.w.affineY)
                )
            val p =
                Pairing(
                    repo.credential().userId,
                    agentId,
                    epoch,
                    "pair_${UUID.randomUUID()}",
                    "device_${UUID.randomUUID()}",
                    encode64(pair.private.encoded),
                    encodedPublic,
                    encode64(randomBytes(16)),
                )
            val publicContext = buildJsonArray {
                add("pax/e2ee/pairing-public/v1")
                add(p.id)
                add(p.agentId)
                add(p.deviceId)
                add(p.epoch)
                add(p.publicKey)
            }
            val commitment =
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        "pax/e2ee/pairing-commitment/v1\u0000".toByteArray() +
                            decode64(p.secret) +
                            byteArrayOf(0) +
                            publicContext.toString().toByteArray()
                    )
            // Save before POST; a lost response must not destroy the recovery keys.
            vault.update { it.copy(pairings = it.pairings + p) }
            repo.post(
                listOf("agents", agentId, "e2ee", "pairings"),
                buildJsonObject {
                    put("pairing_id", p.id)
                    put("device_id", p.deviceId)
                    put("device_name", "PaxWorkspace Android")
                    put("key_epoch", epoch)
                    put("recipient_public_key", p.publicKey)
                    put("secret_commitment", encode64(commitment))
                },
            )
            p
        }

    suspend fun finish(p: Pairing) =
        withContext(Dispatchers.Default) {
            val data =
                repo
                    .get(
                        "agents",
                        p.agentId,
                        "e2ee",
                        "key-packages",
                        p.deviceId,
                        query = mapOf("key_epoch" to p.epoch.toString()),
                    )
                    .jsonObject
            require(
                data.text("pairing_id") == p.id &&
                    data.text("agent_id") == p.agentId &&
                    data.text("device_id") == p.deviceId &&
                    data.number("key_epoch") == p.epoch.toLong()
            ) {
                "Device authorization identity mismatch"
            }
            require(
                MessageDigest.isEqual(
                    decode64(data.text("recipient_public_key")),
                    decode64(p.publicKey),
                )
            )
            val rawPublic = decode64(data.text("sender_ephemeral_public_key"))
            require(rawPublic.size == 65 && rawPublic[0] == 4.toByte())
            val parameters =
                AlgorithmParameters.getInstance("EC")
                    .apply { init(ECGenParameterSpec("secp256r1")) }
                    .getParameterSpec(ECParameterSpec::class.java)
            val factory = KeyFactory.getInstance("EC")
            val sender =
                factory.generatePublic(
                    ECPublicKeySpec(
                        ECPoint(
                            BigInteger(1, rawPublic.copyOfRange(1, 33)),
                            BigInteger(1, rawPublic.copyOfRange(33, 65)),
                        ),
                        parameters,
                    )
                )
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(decode64(p.privateKey)))
            val shared =
                KeyAgreement.getInstance("ECDH")
                    .apply {
                        init(private)
                        doPhase(sender, true)
                    }
                    .generateSecret()
            val aad =
                buildJsonArray {
                        add("pax/e2ee/root-package/v1")
                        add(p.id)
                        add(p.agentId)
                        add(p.deviceId)
                        add(p.epoch)
                        add(p.publicKey)
                        add(data.text("sender_ephemeral_public_key"))
                    }
                    .toString()
                    .toByteArray()
            val key =
                Hkdf.computeHkdf(
                    "HmacSha256",
                    shared,
                    MessageDigest.getInstance("SHA-256").digest(decode64(p.secret)),
                    aad,
                    32,
                )
            val root =
                EnvelopeCrypto.crypt(
                    Cipher.DECRYPT_MODE,
                    key,
                    decode64(data.text("nonce")),
                    aad,
                    decode64(data.text("ciphertext")),
                )
            require(root.size == 32)
            vault.update { current ->
                check(current.credential?.userId == p.account) { "The signed-in account changed" }
                current.copy(
                    keys =
                        current.keys.filterNot {
                            it.account == p.account && it.agentId == p.agentId
                        } + RootKey(p.account, p.agentId, p.epoch, encode64(root)),
                    pairings = current.pairings.filterNot { it.id == p.id },
                )
            }
        }

    fun command(p: Pairing): String {
        val json =
            body("agent_id" to p.agentId, "pairing_secret" to p.secret)
                .toString()
                .replace("'", "'\"'\"'")
        return "curl --unix-socket \"\$HOME/.paxd/paxd.sock\" -X POST 'http://paxd/v1/e2ee/pairings/${p.id}/complete' -H 'Content-Type: application/json' --data '$json'"
    }
}
