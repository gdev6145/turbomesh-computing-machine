package com.turbomesh.computingmachine.mesh

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Manages end-to-end encryption for mesh messages (feature 11) and
 * 6-digit pairing PINs (feature 12).
 *
 * Keys are backed by Android Keystore (ECDH P-256). On first use, a key pair
 * is generated automatically. Peers exchange public keys via CONTROL messages
 * (sub-type 0x01). Derived shared secrets produce AES-256-GCM session keys.
 */
class CryptoManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    init {
        ensureKeyPair()
    }

    // -------------------------------------------------------------------------
    // Key management
    // -------------------------------------------------------------------------

    /** Returns this device's encoded EC public key (X.509 / SubjectPublicKeyInfo). */
    fun getPublicKeyBytes(): ByteArray {
        ensureKeyPair()
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey.encoded
    }

    /** Store a peer's public key bytes (received via CONTROL / KEY_EXCHANGE). */
    fun storePeerPublicKey(peerId: String, publicKeyBytes: ByteArray) {
        prefs.edit().putString("pk_$peerId", Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)).apply()
    }

    fun hasPeerPublicKey(peerId: String): Boolean = prefs.contains("pk_$peerId")

    // -------------------------------------------------------------------------
    // Encryption / Decryption
    // -------------------------------------------------------------------------

    /**
     * Encrypt [plaintext] for [peerId].
     * Returns: nonce(12 bytes) + ciphertext+GCM-tag, or null on failure.
     */
    fun encrypt(plaintext: ByteArray, peerId: String): ByteArray? {
        return try {
            val sessionKey = deriveSessionKey(peerId) ?: return null
            val nonce = ByteArray(GCM_NONCE_LEN).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertext = cipher.doFinal(plaintext)
            nonce + ciphertext
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for $peerId", e)
            null
        }
    }

    /**
     * Decrypt a payload (nonce + ciphertext) from [peerId].
     * Returns plaintext or null on failure.
     */
    fun decrypt(data: ByteArray, peerId: String): ByteArray? {
        if (data.size <= GCM_NONCE_LEN) return null
        return try {
            val sessionKey = deriveSessionKey(peerId) ?: return null
            val nonce = data.copyOfRange(0, GCM_NONCE_LEN)
            val ciphertext = data.copyOfRange(GCM_NONCE_LEN, data.size)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed from $peerId", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Pairing PIN (feature 12)
    // -------------------------------------------------------------------------

    /**
     * Derives a 6-digit numeric PIN from the shared secret with [peerId].
     * Both peers independently compute the same PIN, which can be compared
     * out-of-band to confirm no MITM.
     * Returns null if the peer's public key is not yet known.
     */
    fun derivePairingPin(peerId: String): String? {
        val sharedSecret = computeSharedSecret(peerId) ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        // Take first 3 bytes as a 24-bit integer, mod 1_000_000 for a 6-digit PIN.
        val raw = ((hash[0].toInt() and 0xFF) shl 16) or
                  ((hash[1].toInt() and 0xFF) shl 8) or
                   (hash[2].toInt() and 0xFF)
        return (abs(raw) % 1_000_000).toString().padStart(6, '0')
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun deriveSessionKey(peerId: String): SecretKey? {
        val sharedSecret = computeSharedSecret(peerId) ?: return null
        // HKDF-lite: SHA-256(sharedSecret || peerId) → 32-byte AES key
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update(peerId.toByteArray(Charsets.UTF_8))
        val keyBytes = digest.digest()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun computeSharedSecret(peerId: String): ByteArray? {
        val peerPkB64 = prefs.getString("pk_$peerId", null) ?: return null
        return try {
            val peerPkBytes = Base64.decode(peerPkB64, Base64.NO_WRAP)
            val kf = java.security.KeyFactory.getInstance("EC")
            val peerPublicKey: PublicKey = kf.generatePublic(X509EncodedKeySpec(peerPkBytes))
            val privateKeyEntry = keyStore.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(privateKeyEntry.privateKey)
            ka.doPhase(peerPublicKey, true)
            ka.generateSecret()
        } catch (e: Exception) {
            Log.e(TAG, "Key agreement failed for $peerId", e)
            null
        }
    }

    private fun ensureKeyPair() {
        if (!keyStore.containsAlias(ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_AGREE_KEY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
            Log.d(TAG, "Generated new ECDH P-256 key pair in Keystore")
        }
    }

    companion object {
        private const val TAG = "CryptoManager"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "turbomesh_ecdh_key"
        private const val PREFS_NAME = "crypto_store"
        private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_NONCE_LEN = 12
        private const val GCM_TAG_BITS = 128
    }
}
