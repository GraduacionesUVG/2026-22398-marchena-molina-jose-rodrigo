package com.example.smartuse_frontend.inference

import android.content.ContentValues
import com.example.smartuse_frontend.data.db.CanonicalDbHelper
import kotlinx.serialization.json.Json

/** The two frozen profiles. Written once; usage data, erased with canonical.db. */
interface ProfileStore {
    fun read(pattern: Pattern): Profile?

    /** Both in one transaction; throws if either is already stored: a frozen profile is never rewritten. */
    fun freeze(f: Profile, s: Profile)
}

class SqlProfileStore(private val helper: CanonicalDbHelper) : ProfileStore {

    override fun read(pattern: Pattern): Profile? = helper.readableDatabase.use { db ->
        db.rawQuery("SELECT profile_json FROM model_profiles WHERE pattern = ?", arrayOf(pattern.name)).use { c ->
            if (c.moveToFirst()) Json.decodeFromString(Profile.serializer(), c.getString(0)) else null
        }
    }

    override fun freeze(f: Profile, s: Profile) {
        helper.writableDatabase.use { db ->
            db.beginTransaction()
            try {
                for ((pattern, profile) in listOf(Pattern.F to f, Pattern.S to s)) {
                    db.insertOrThrow("model_profiles", null, ContentValues().apply {
                        put("pattern", pattern.name)
                        put("profile_json", Json.encodeToString(Profile.serializer(), profile))
                    })
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
