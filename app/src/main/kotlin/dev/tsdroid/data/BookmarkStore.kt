package dev.tsdroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bookmarks")

class BookmarkStore(private val context: Context) {

    companion object {
        private val KEY_BOOKMARKS = stringPreferencesKey("bookmarks_json")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_LAST_BOOKMARK_ADDRESS = stringPreferencesKey("last_bookmark_address")
    }

    val bookmarks: Flow<List<ServerBookmark>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_BOOKMARKS] ?: "[]"
        parseBookmarks(json)
    }

    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RECONNECT] ?: false
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_RECONNECT] = enabled
        }
    }

    suspend fun save(bookmarks: List<ServerBookmark>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BOOKMARKS] = serializeBookmarks(bookmarks)
        }
    }

    suspend fun add(bookmark: ServerBookmark) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]")
            prefs[KEY_BOOKMARKS] = serializeBookmarks(current + bookmark)
        }
    }

    suspend fun remove(index: Int) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]").toMutableList()
            if (index in current.indices) {
                current.removeAt(index)
                prefs[KEY_BOOKMARKS] = serializeBookmarks(current)
            }
        }
    }

    suspend fun replace(index: Int, bookmark: ServerBookmark) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]").toMutableList()
            if (index in current.indices) {
                // Preserve serverName and iconId from the old bookmark if not set
                val old = current[index]
                current[index] = bookmark.copy(
                    serverName = bookmark.serverName ?: old.serverName,
                    iconId = if (bookmark.iconId != 0L) bookmark.iconId else old.iconId,
                )
                prefs[KEY_BOOKMARKS] = serializeBookmarks(current)
            }
        }
    }

    /** Save the address of the last connected bookmark (for auto-reconnect). */
    suspend fun saveLastBookmarkAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_BOOKMARK_ADDRESS] = address
        }
    }

    val lastBookmarkAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_BOOKMARK_ADDRESS] ?: ""
    }

    suspend fun updateServerInfo(address: String, serverName: String, iconId: Long) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]")
            val updated = current.map { b ->
                if (b.address == address) b.copy(serverName = serverName, iconId = iconId) else b
            }
            prefs[KEY_BOOKMARKS] = serializeBookmarks(updated)
        }
    }

    private fun parseBookmarks(json: String): List<ServerBookmark> {
        if (json == "[]") return emptyList()
        return try {
            val entries = json.removeSurrounding("[", "]").split("},{")
            entries.map { entry ->
                val clean = entry.removePrefix("{").removeSuffix("}")
                val fields = mutableMapOf<String, String>()
                for (pair in clean.split("\",\"")) {
                    val kv = pair.replace("\"", "").split(":", limit = 2)
                    if (kv.size == 2) fields[kv[0]] = kv[1]
                }
                ServerBookmark(
                    name = fields["name"] ?: "",
                    address = fields["address"] ?: "",
                    nickname = fields["nickname"] ?: "",
                    password = fields["password"]?.takeIf { it != "null" },
                    channel = fields["channel"]?.takeIf { it != "null" },
                    serverName = fields["serverName"]?.takeIf { it != "null" },
                    iconId = fields["iconId"]?.toLongOrNull() ?: 0,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeBookmarks(bookmarks: List<ServerBookmark>): String {
        return bookmarks.joinToString(",", "[", "]") { b ->
            """{"name":"${escape(b.name)}","address":"${escape(b.address)}","nickname":"${escape(b.nickname)}","password":"${b.password ?: "null"}","channel":"${b.channel ?: "null"}","serverName":"${b.serverName ?: "null"}","iconId":"${b.iconId}"}"""
        }
    }

    private fun escape(s: String): String = s.replace("\"", "\\\"")
}
