package io.github.arnavdugad.ember.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

/**
 * Snapshot listener as a Flow. Permission errors end the flow quietly with [onDenied] instead
 * of crashing: they're expected (e.g. an album whose access was just revoked).
 *
 * Callers that skip cache-only snapshots must pass [includeMetadata]: when the server agrees
 * with the cache, Firestore reports that confirmation only as a metadata change, so without it
 * a "wait for the server" filter would wait forever.
 */
internal fun DocumentReference.snapshots(
    onDenied: (() -> DocumentSnapshot?)? = null,
    includeMetadata: Boolean = false,
): Flow<DocumentSnapshot?> = callbackFlow {
    val changes = if (includeMetadata) MetadataChanges.INCLUDE else MetadataChanges.EXCLUDE
    val registration = addSnapshotListener(changes) { snapshot, error ->
        if (error != null) {
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                trySend(onDenied?.invoke())
            } else {
                close(error)
            }
            return@addSnapshotListener
        }
        trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

internal fun Query.snapshots(includeMetadata: Boolean = false): Flow<QuerySnapshot?> = callbackFlow {
    val changes = if (includeMetadata) MetadataChanges.INCLUDE else MetadataChanges.EXCLUDE
    val registration = addSnapshotListener(changes) { snapshot, error ->
        if (error != null) {
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) trySend(null) else close(error)
            return@addSnapshotListener
        }
        trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

// --- Loose readers. Firestore and callable results give numbers as Long, Integer or Double. ---

internal fun Map<*, *>.str(key: String): String? = this[key] as? String
internal fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()
internal fun Map<*, *>.dbl(key: String): Double? = (this[key] as? Number)?.toDouble()
internal fun Map<*, *>.bool(key: String): Boolean = this[key] as? Boolean ?: false
internal fun Map<*, *>.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()
internal fun Map<*, *>.maps(key: String): List<Map<*, *>> = (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
internal fun Map<*, *>.map(key: String): Map<*, *>? = this[key] as? Map<*, *>
internal fun Map<*, *>.instant(key: String): Instant? = (this[key] as? Timestamp)?.toInstant()
