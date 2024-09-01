package xyz.regulad.blueheaven.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.collection.LruCache
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Provides a helper for persisting data about public keys in the network.
 * All methods guarantee thread safety.
 */
class BlueHeavenDatabase(
    context: Context,
    private val thisNodeId: UInt,
    private val thisNodePublicKeyParameters: Ed25519PublicKeyParameters
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        const val DATABASE_NAME = "blueheaven.db"
        const val DATABASE_VERSION = 1

        const val PK_TABLE_NAME = "pk"
        const val PK_COLUMN_ID = "id" // public key
        const val PK_COLUMN_AUTHORIZED_FOR = "node" // what node is this public key authorized for
        const val PK_EXCHANGED_AT = "exchanged_at" // timestamp when this public key was exchanged OOB
        private const val PK_INDEX_NODE = "node_index"
        private const val PK_INDEX_EXCHANGED_AT = "exchanged_at_index"

        // 90 days
        const val PK_TTL_MS = 1000L * 60 * 60 * 24 * 90

        private const val TAG = "BlueHeavenDatabase"
    }

    private val lock = ReentrantReadWriteLock()
    private val pkCache = LruCache<UInt, HashSet<Ed25519PublicKeyParameters>>(1000)

    override fun onCreate(db: SQLiteDatabase) {
        lock.write {
            db.execSQL(
                """
                CREATE TABLE $PK_TABLE_NAME (
                    $PK_COLUMN_ID BLOB PRIMARY KEY,
                    $PK_COLUMN_AUTHORIZED_FOR INTEGER,
                    $PK_EXCHANGED_AT INTEGER
                )
                """
            )
            db.execSQL("CREATE INDEX $PK_INDEX_NODE ON $PK_TABLE_NAME ($PK_COLUMN_AUTHORIZED_FOR)")
            db.execSQL("CREATE INDEX $PK_INDEX_EXCHANGED_AT ON $PK_TABLE_NAME ($PK_EXCHANGED_AT)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No-op for now; implement when schema changes are needed
    }

    /**
     * Registers a new public key for a node.
     * @param publicKey The public key to register.
     * @param nodeId The UUID of the node this key is authorized for.
     */
    fun registerPublicKey(publicKey: Ed25519PublicKeyParameters, nodeId: UInt) {
        if (nodeId == thisNodeId) {
            Log.w(TAG, "Attempted to register a public key for the local node")
            return
        }

        val exchangedAt = System.currentTimeMillis()
        lock.write {
            writableDatabase.insertWithOnConflict(
                PK_TABLE_NAME,
                null,
                ContentValues().apply {
                    put(PK_COLUMN_ID, publicKey.encoded)
                    put(PK_COLUMN_AUTHORIZED_FOR, nodeId.toInt())
                    put(PK_EXCHANGED_AT, exchangedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )

            // Update cache
            val nodePublicKeySet = pkCache[nodeId] ?: HashSet()
            nodePublicKeySet.add(publicKey)
            pkCache.put(nodeId, nodePublicKeySet)
        }
    }

    /**
     * Reads the fresh public keys for a node from the database.
     * @param nodeId The UUID of the node to fetch keys for.
     * @return A list of fresh Ed25519PublicKeyParameters for the given node.
     */
    fun readPublicKeysForNode(nodeId: UInt): Collection<Ed25519PublicKeyParameters> {
        if (nodeId == thisNodeId) {
            Log.w(TAG, "Attempted to read public keys for the local node")
            return listOf(thisNodePublicKeyParameters)
        }
        val currentTime = System.currentTimeMillis()
        val minValidTime = currentTime - PK_TTL_MS
        return lock.read {
            pkCache[nodeId] ?: run {
                val nodePublicKeySet = HashSet<Ed25519PublicKeyParameters>()
                readableDatabase.query(
                    PK_TABLE_NAME,
                    arrayOf(PK_COLUMN_ID),
                    "$PK_COLUMN_AUTHORIZED_FOR = ? AND $PK_EXCHANGED_AT > ?",
                    arrayOf(nodeId.toString(), minValidTime.toString()),
                    null,
                    null,
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val publicKeyBytes = cursor.getBlob(cursor.getColumnIndex(PK_COLUMN_ID))
                        try {
                            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
                            nodePublicKeySet.add(publicKey)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse public key for node $nodeId", e)
                        }
                    }
                }
                pkCache.put(nodeId, nodePublicKeySet)
                nodePublicKeySet.toList()
            }
        }
    }

    /**
     * Removes expired keys from the database and invalidates the cache.
     */
    // we keep expired keys for now
//    fun removeExpiredKeys() {
//        val currentTime = System.currentTimeMillis()
//        lock.write {
//            writableDatabase.delete(
//                PK_TABLE_NAME,
//                "$PK_EXCHANGED_AT <= ?",
//                arrayOf((currentTime - PK_TTL_MS).toString())
//            )
//            // Invalidate the entire cache as we don't know which nodes were affected
//            pkCache.evictAll()
//        }
//    }

    /**
     * Closes the database and clears the cache.
     */
    override fun close() {
        lock.write {
            pkCache.evictAll()
            super.close()
        }
    }
}
