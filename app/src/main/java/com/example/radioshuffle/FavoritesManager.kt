package com.example.radioshuffle

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class FavoritesManager(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getFavorites(): List<ResolvedStation> {
        val json = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ResolvedStation>>() {}.type
            gson.fromJson<List<ResolvedStation>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isFavorite(channelId: String): Boolean {
        return getFavorites().any { it.channelId == channelId }
    }

    fun toggleFavorite(station: ResolvedStation): Boolean {
        val current = getFavorites().toMutableList()
        val exists = current.any { it.channelId == station.channelId }
        val newStatus: Boolean
        if (exists) {
            current.removeAll { it.channelId == station.channelId }
            newStatus = false
        } else {
            current.add(0, station)
            newStatus = true
        }
        prefs.edit().putString(KEY_FAVORITES, gson.toJson(current)).apply()
        return newStatus
    }

    fun removeFavorite(channelId: String) {
        val current = getFavorites().toMutableList()
        current.removeAll { it.channelId == channelId }
        prefs.edit().putString(KEY_FAVORITES, gson.toJson(current)).apply()
    }

    fun getRecents(): List<ResolvedStation> {
        val json = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ResolvedStation>>() {}.type
            gson.fromJson<List<ResolvedStation>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecent(station: ResolvedStation) {
        val current = getRecents().toMutableList()
        current.removeAll { it.channelId == station.channelId }
        current.add(0, station)
        if (current.size > MAX_RECENTS) {
            current.subList(MAX_RECENTS, current.size).clear()
        }
        prefs.edit().putString(KEY_RECENTS, gson.toJson(current)).apply()
    }

    companion object {
        private const val PREFS_NAME = "radio_shuffler_favorites"
        private const val KEY_FAVORITES = "favorites_list"
        private const val KEY_RECENTS = "recents_list"
        private const val MAX_RECENTS = 20
    }
}
