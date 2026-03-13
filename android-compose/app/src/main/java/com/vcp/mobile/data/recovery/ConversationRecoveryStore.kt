package com.vcp.mobile.data.recovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

interface RecoveryStore {
    suspend fun lastConversationId(): String?

    suspend fun saveLastConversationId(conversationId: String)

    suspend fun clearLastConversationId()

    suspend fun loadResumeAnchor(): RecoveryResumeAnchor?

    suspend fun saveResumeAnchor(anchor: RecoveryResumeAnchor)

    suspend fun clearResumeAnchor()

    suspend fun loadSceneAnchor(conversationId: String): RecoverySceneAnchor?

    suspend fun saveSceneAnchor(anchor: RecoverySceneAnchor)

    suspend fun clearSceneAnchor(conversationId: String)
}

data class RecoverySceneAnchor(
    val conversationId: String,
    val lastMessageId: String?,
    val stickToBottom: Boolean,
)

data class RecoveryResumeAnchor(
    val conversationId: String,
    val messageId: String?,
)

class DataStoreConversationRecoveryStore(
    private val dataStore: DataStore<Preferences>
) : RecoveryStore {
    override suspend fun lastConversationId(): String? {
        return dataStore.data.first()[LAST_CONVERSATION_ID]?.takeIf { it.isNotBlank() }
    }

    override suspend fun saveLastConversationId(conversationId: String) {
        dataStore.edit { preferences ->
            preferences[LAST_CONVERSATION_ID] = conversationId
        }
    }

    override suspend fun clearLastConversationId() {
        dataStore.edit { preferences ->
            preferences.remove(LAST_CONVERSATION_ID)
        }
    }

    override suspend fun loadResumeAnchor(): RecoveryResumeAnchor? {
        val preferences = dataStore.data.first()
        val conversationId = preferences[RESUME_CONVERSATION_ID]?.takeIf { it.isNotBlank() }
            ?: return null
        return RecoveryResumeAnchor(
            conversationId = conversationId,
            messageId = preferences[RESUME_MESSAGE_ID]?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun saveResumeAnchor(anchor: RecoveryResumeAnchor) {
        dataStore.edit { preferences ->
            preferences[RESUME_CONVERSATION_ID] = anchor.conversationId
            anchor.messageId?.takeIf { it.isNotBlank() }?.let {
                preferences[RESUME_MESSAGE_ID] = it
            } ?: preferences.remove(RESUME_MESSAGE_ID)
        }
    }

    override suspend fun clearResumeAnchor() {
        dataStore.edit { preferences ->
            preferences.remove(RESUME_CONVERSATION_ID)
            preferences.remove(RESUME_MESSAGE_ID)
        }
    }

    override suspend fun loadSceneAnchor(conversationId: String): RecoverySceneAnchor? {
        val preferences = dataStore.data.first()
        val storedConversationId = preferences[SCENE_CONVERSATION_ID]?.takeIf { it.isNotBlank() }
        if (storedConversationId != conversationId) return null

        return RecoverySceneAnchor(
            conversationId = conversationId,
            lastMessageId = preferences[SCENE_LAST_MESSAGE_ID]?.takeIf { it.isNotBlank() },
            stickToBottom = preferences[SCENE_STICK_TO_BOTTOM]?.toBooleanStrictOrNull() ?: true,
        )
    }

    override suspend fun saveSceneAnchor(anchor: RecoverySceneAnchor) {
        dataStore.edit { preferences ->
            preferences[SCENE_CONVERSATION_ID] = anchor.conversationId
            anchor.lastMessageId?.takeIf { it.isNotBlank() }?.let {
                preferences[SCENE_LAST_MESSAGE_ID] = it
            } ?: preferences.remove(SCENE_LAST_MESSAGE_ID)
            preferences[SCENE_STICK_TO_BOTTOM] = anchor.stickToBottom.toString()
        }
    }

    override suspend fun clearSceneAnchor(conversationId: String) {
        dataStore.edit { preferences ->
            val storedConversationId = preferences[SCENE_CONVERSATION_ID]
            if (storedConversationId == conversationId) {
                preferences.remove(SCENE_CONVERSATION_ID)
                preferences.remove(SCENE_LAST_MESSAGE_ID)
                preferences.remove(SCENE_STICK_TO_BOTTOM)
            }
        }
    }

    private companion object {
        val LAST_CONVERSATION_ID = stringPreferencesKey("last_conversation_id")
        val RESUME_CONVERSATION_ID = stringPreferencesKey("resume_conversation_id")
        val RESUME_MESSAGE_ID = stringPreferencesKey("resume_message_id")
        val SCENE_CONVERSATION_ID = stringPreferencesKey("scene_conversation_id")
        val SCENE_LAST_MESSAGE_ID = stringPreferencesKey("scene_last_message_id")
        val SCENE_STICK_TO_BOTTOM = stringPreferencesKey("scene_stick_to_bottom")
    }
}
