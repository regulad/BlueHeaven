package xyz.regulad.blueheaven.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom

/**
 * Repository for managing user preferences, including the node ID and Ed25519 key pair.
 * This class provides secure storage and retrieval of sensitive information using EncryptedSharedPreferences.
 *
 * @property context The Android application context.
 */
class UserPreferencesRepository(context: Context) {
    enum class SentryPermissions {
        UNASKED,
        DENIED,
        GRANTED
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "user_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_SENTRY_PERMISSIONS = "sentry_permissions"
    }

    fun getSentryPermissions(): SentryPermissions {
        val sentryPermissions = sharedPreferences.getString(KEY_SENTRY_PERMISSIONS, null)
        return sentryPermissions?.let { SentryPermissions.valueOf(it) } ?: SentryPermissions.UNASKED
    }

    fun setSentryPermissions(permissions: SentryPermissions) {
        sharedPreferences.edit()
            .putString(KEY_SENTRY_PERMISSIONS, permissions.name)
            .apply()
    }

    /**
     * Retrieves the node ID. If it doesn't exist, a new one is generated.
     *
     * @return The node ID as a String.
     */
    fun getNodeId(): UInt {
        val nodeIdString = sharedPreferences.getInt(KEY_NODE_ID, 0)
        if (nodeIdString == 0) {
            return generateAndSaveNodeId()
        }
        return nodeIdString.toUInt()
    }

    /**
     * Retrieves the public key.
     *
     * @return The public key as a ByteArray, or null if it doesn't exist.
     */
    private fun getPublicKeyBytes(): ByteArray? {
        val publicKeyString = sharedPreferences.getString(KEY_PUBLIC_KEY, null)
        return publicKeyString?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
    }

    fun getPublicKey(): Ed25519PublicKeyParameters {
        if (!isKeyStoreInitalized()) {
            throw IllegalStateException("Key pair not initialized")
        }

        val publicKeyBytes = getPublicKeyBytes()!!
        return Ed25519PublicKeyParameters(publicKeyBytes, 0)
    }

    /**
     * Retrieves the private key.
     *
     * @return The private key as a ByteArray, or null if it doesn't exist.
     */
    private fun getPrivateKeyBytes(): ByteArray? {
        val privateKeyString = sharedPreferences.getString(KEY_PRIVATE_KEY, null)
        return privateKeyString?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
    }

    fun getPrivateKey(): Ed25519PrivateKeyParameters {
        if (!isKeyStoreInitalized()) {
            throw IllegalStateException("Key pair not initialized")
        }

        val privateKeyBytes = getPrivateKeyBytes()!!
        return Ed25519PrivateKeyParameters(privateKeyBytes, 0)
    }

    /**
     * Generates a new Ed25519 key pair and stores it.
     */
    fun generateKeyPair(): AsymmetricCipherKeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        setKeyPair(keyPair)
        return keyPair
    }

    /**
     * Checks if the store is initialized with a key pair.
     *
     * @return true if both public and private keys exist, false otherwise.
     */
    fun isKeyStoreInitalized(): Boolean {
        return getPublicKeyBytes() != null && getPrivateKeyBytes() != null
    }

    private fun generateAndSaveNodeId(): UInt {
        val newNodeId = SecureRandom().nextInt().toUInt()
        setNodeId(newNodeId)
        return newNodeId
    }

    private fun setNodeId(nodeId: UInt) {
        sharedPreferences.edit()
            .putInt(KEY_NODE_ID, nodeId.toInt())
            .apply()
    }

    private fun setKeyPair(keyPair: AsymmetricCipherKeyPair) {
        val publicKey = keyPair.public as Ed25519PublicKeyParameters
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters

        val publicKeyEncoded = android.util.Base64.encodeToString(publicKey.encoded, android.util.Base64.DEFAULT)
        val privateKeyEncoded = android.util.Base64.encodeToString(privateKey.encoded, android.util.Base64.DEFAULT)

        sharedPreferences.edit()
            .putString(KEY_PUBLIC_KEY, publicKeyEncoded)
            .putString(KEY_PRIVATE_KEY, privateKeyEncoded)
            .apply()
    }
}
