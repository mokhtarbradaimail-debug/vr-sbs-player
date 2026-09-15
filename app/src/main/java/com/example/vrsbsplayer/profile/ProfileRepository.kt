package com.example.vrsbsplayer.profile

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * Persists viewer profiles + "last selected profile id" + "last selected stereo mode"
 * to a plain JSON file in the app's private storage. No network, no account.
 */
class ProfileRepository(context: Context) {

    private val file = File(context.filesDir, "viewer_profiles.json")
    private val prefs = context.getSharedPreferences("vr_sbs_player_prefs", Context.MODE_PRIVATE)

    fun loadAll(): MutableList<ViewerProfile> {
        if (!file.exists()) {
            val defaults = mutableListOf(builtInUserProfile(), cardboardV1DefaultProfile(UUID.randomUUID().toString()))
            saveAll(defaults)
            return defaults
        }
        return try {
            val text = file.readText()
            val arr = JSONArray(text)
            val list = mutableListOf<ViewerProfile>()
            for (i in 0 until arr.length()) {
                list.add(ViewerProfile.fromJson(arr.getJSONObject(i)))
            }
            if (list.isEmpty()) list.add(builtInUserProfile())
            list
        } catch (e: Exception) {
            mutableListOf(builtInUserProfile())
        }
    }

    fun saveAll(profiles: List<ViewerProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    fun upsert(profile: ViewerProfile) {
        val all = loadAll()
        val idx = all.indexOfFirst { it.id == profile.id }
        if (idx >= 0) all[idx] = profile else all.add(profile)
        saveAll(all)
    }

    fun delete(id: String) {
        val all = loadAll().filterNot { it.id == id }
        saveAll(all)
    }

    fun newId(): String = UUID.randomUUID().toString()

    var lastSelectedProfileId: String?
        get() = prefs.getString("last_profile_id", null)
        set(value) = prefs.edit().putString("last_profile_id", value).apply()

    var headTrackingEnabled: Boolean
        get() = prefs.getBoolean("head_tracking_enabled", false)
        set(value) = prefs.edit().putBoolean("head_tracking_enabled", value).apply()
}
