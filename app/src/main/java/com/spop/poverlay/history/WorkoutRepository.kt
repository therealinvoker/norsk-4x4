package com.spop.poverlay.history

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import timber.log.Timber
import java.io.File

/**
 * File-backed store for completed [WorkoutSession]s. One JSON file per session under
 * filesDir/sessions. Simple and dependency-light; fine for the personal, single-user scale of
 * this app (a 40-minute session is ~2400 samples ≈ a small JSON file).
 */
class WorkoutRepository(context: Context) {

    private val dir = File(context.filesDir, SESSIONS_DIR).apply { mkdirs() }
    private val gson: Gson = GsonBuilder().create()

    fun save(session: WorkoutSession) {
        try {
            val file = File(dir, "${session.startedAtEpochMs}_${session.id}.json")
            file.writeText(gson.toJson(session))
        } catch (t: Throwable) {
            Timber.e(t, "Failed to save workout session ${session.id}")
        }
    }

    /** All sessions, newest first. Corrupt files are skipped rather than crashing. */
    fun loadAll(): List<WorkoutSession> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { gson.fromJson(file.readText(), WorkoutSession::class.java) }
                    .onFailure { Timber.e(it, "Skipping unreadable session ${file.name}") }
                    .getOrNull()
            }
            ?.sortedByDescending { it.startedAtEpochMs }
            ?: emptyList()

    fun get(id: String): WorkoutSession? = loadAll().firstOrNull { it.id == id }

    /** The best completed session by work-interval output (kJ), or null if none yet. */
    fun best(): WorkoutSession? =
        loadAll().filter { it.completed }.maxByOrNull { it.summary.workOutputKj }

    /** Highest single work-interval average power (W) across all sessions, or null if none yet. */
    fun bestIntervalAverageWatts(): Float? =
        loadAll().asSequence()
            .flatMap { it.workIntervalAverages().asSequence() }
            .maxOrNull()

    fun delete(id: String) {
        dir.listFiles { f -> f.name.contains(id) }?.forEach { it.delete() }
    }

    companion object {
        private const val SESSIONS_DIR = "sessions"
    }
}
