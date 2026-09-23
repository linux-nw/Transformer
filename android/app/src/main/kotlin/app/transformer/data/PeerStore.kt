package app.transformer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "transformer_store")

/** Persisted paired devices and app settings — the one source of truth both
 * the UI and [app.transformer.net.TransferService] read from. */
class PeerStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val PEERS = stringPreferencesKey("peers_json")
        val MY_NAME = stringPreferencesKey("my_device_name")
        val AUTO_ACCEPT = booleanPreferencesKey("auto_accept")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
    }

    val peers: Flow<List<StoredPeer>> = context.dataStore.data.map { prefs ->
        prefs[Keys.PEERS]?.let { raw ->
            runCatching { json.decodeFromString<List<StoredPeer>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            myDeviceName = prefs[Keys.MY_NAME] ?: "Mein Handy",
            autoAccept = prefs[Keys.AUTO_ACCEPT] ?: true,
            notifications = prefs[Keys.NOTIFICATIONS] ?: true,
            darkTheme = prefs[Keys.DARK_THEME] ?: false,
        )
    }

    suspend fun peerById(id: String): StoredPeer? = peers.first().firstOrNull { it.id == id }

    private suspend fun savePeers(list: List<StoredPeer>) {
        context.dataStore.edit { it[Keys.PEERS] = json.encodeToString(list) }
    }

    suspend fun upsertPeer(peer: StoredPeer) {
        val current = peers.first()
        val next = if (current.any { it.id == peer.id }) {
            current.map { if (it.id == peer.id) peer else it }
        } else {
            current + peer
        }
        savePeers(next)
    }

    suspend fun updateLastAddress(id: String, host: String, port: Int) {
        val current = peers.first()
        savePeers(current.map { if (it.id == id) it.copy(lastHost = host, lastPort = port) else it })
    }

    suspend fun renamePeer(id: String, name: String) {
        val current = peers.first()
        savePeers(current.map { if (it.id == id) it.copy(name = name) else it })
    }

    suspend fun removePeer(id: String) {
        savePeers(peers.first().filterNot { it.id == id })
    }

    suspend fun setMyDeviceName(name: String) {
        context.dataStore.edit { it[Keys.MY_NAME] = name }
    }

    suspend fun setAutoAccept(value: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_ACCEPT] = value }
    }

    suspend fun setNotifications(value: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = value }
    }

    suspend fun setDarkTheme(value: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = value }
    }
}
