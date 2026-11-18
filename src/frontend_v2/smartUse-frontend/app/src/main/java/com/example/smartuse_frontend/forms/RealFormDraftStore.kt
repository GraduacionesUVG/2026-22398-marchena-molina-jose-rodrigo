package com.example.smartuse_frontend.forms

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.smartuse_frontend.network.dto.appJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val Context.endFormsDataStore: DataStore<Preferences> by preferencesDataStore(name = "end_forms")

private val COMPLETED = stringPreferencesKey("completed")
private fun draftKey(formId: String) = stringPreferencesKey("draft_$formId")

/** DataStore `end_forms`: one JSON object per form's draft, and the frozen copy. Unreadable values read as empty. */
class RealFormDraftStore(private val context: Context, private val json: Json = appJson) : FormDraftStore {

    override suspend fun draft(formId: String): Map<String, FormAnswer> =
        parseDraft(context.endFormsDataStore.data.first()[draftKey(formId)])

    override suspend fun saveAnswer(formId: String, questionId: String, answer: FormAnswer?) {
        context.endFormsDataStore.edit { prefs ->
            val current = parseDraft(prefs[draftKey(formId)])
            val next = answer.storable()?.let { current + (questionId to it) } ?: (current - questionId)
            prefs[draftKey(formId)] = next.toJson().toString()
        }
    }

    override suspend fun completed(): CompletedForms? =
        context.endFormsDataStore.data.first()[COMPLETED]?.let {
            runCatching { json.decodeFromString(CompletedForms.serializer(), it) }.getOrNull()
        }

    override suspend fun saveCompleted(forms: CompletedForms) {
        context.endFormsDataStore.edit { it[COMPLETED] = json.encodeToString(CompletedForms.serializer(), forms) }
    }

    override suspend fun clear() {
        context.endFormsDataStore.edit { it.clear() }
    }

    private fun parseDraft(text: String?): Map<String, FormAnswer> =
        text?.let { runCatching { json.parseToJsonElement(it).jsonObject.toAnswers() }.getOrNull() }.orEmpty()
}