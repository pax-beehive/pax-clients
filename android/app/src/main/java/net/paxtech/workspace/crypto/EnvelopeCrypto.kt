package net.paxtech.workspace.crypto

import com.google.crypto.tink.subtle.Hkdf
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

fun encode64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

fun decode64(value: String): ByteArray = Base64.getDecoder().decode(value)

fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

@Serializable
data class Envelope(
    val protocol_version: Int,
    val cipher_version: Int,
    val key_epoch: Int,
    val record_id: String,
    val agent_id: String,
    val session_id: String,
    val kind: String,
    val nonce: String,
    val payload: String,
)

/** Exact web/paxd wire construction; all cryptographic operations use JCA/Tink. */
object EnvelopeCrypto {
    fun open(root: ByteArray, direction: String, envelope: Envelope): ByteArray {
        validate(root, direction, envelope)
        return crypt(
            Cipher.DECRYPT_MODE,
            key(root, direction, envelope),
            decode64(envelope.nonce),
            aad(envelope),
            decode64(envelope.payload),
        )
    }

    fun seal(
        root: ByteArray,
        direction: String,
        agent: String,
        session: String,
        epoch: Int,
        id: String,
        kind: String,
        plain: ByteArray,
    ): Envelope {
        val e =
            Envelope(
                protocol_version = 1,
                cipher_version = 1,
                key_epoch = epoch,
                record_id = id,
                agent_id = agent,
                session_id = session,
                kind = kind,
                nonce = encode64(randomBytes(12)),
                payload = "",
            )
        validate(root, direction, e)
        return e.copy(
            payload =
                encode64(
                    crypt(
                        Cipher.ENCRYPT_MODE,
                        key(root, direction, e),
                        decode64(e.nonce),
                        aad(e),
                        plain,
                    )
                )
        )
    }

    private fun validate(root: ByteArray, direction: String, e: Envelope) {
        require(root.size == 32 && direction in setOf("command", "event"))
        require(e.protocol_version == 1 && e.cipher_version == 1 && e.key_epoch > 0)
        require(decode64(e.nonce).size == 12)
        for (s in listOf(e.record_id, e.agent_id, e.session_id, e.kind)) require(
            s.isNotBlank() && s.length <= 512 && !s.contains('\u0000')
        )
    }

    private fun key(root: ByteArray, direction: String, e: Envelope) =
        Hkdf.computeHkdf(
            "HmacSha256",
            root,
            byteArrayOf(),
            "pax/e2ee/v1/$direction\u0000${e.agent_id}\u0000${e.session_id}\u0000${e.key_epoch}"
                .toByteArray(),
            32,
        )

    private fun aad(e: Envelope) =
        buildJsonArray {
                add(1)
                add(1)
                add(e.key_epoch.toString())
                add(e.record_id)
                add(e.agent_id)
                add(e.session_id)
                add(e.kind)
            }
            .toString()
            .toByteArray()

    fun crypt(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        bytes: ByteArray,
    ): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        return Cipher.getInstance("AES/GCM/NoPadding")
            .apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
            }
            .doFinal(bytes)
    }
}
