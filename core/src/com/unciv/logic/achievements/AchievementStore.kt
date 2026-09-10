package com.unciv.logic.achievements

import com.unciv.json.json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One owner per account-specific directory. No global singleton or dependency on a loaded game. */
class AchievementStore(private val directory: File) {
    private val profileFile = File(directory, "profile.json")
    private val pendingFile = File(directory, "pending.json")
    private val stagingFile = File(directory, "pending.tmp")

    /** A failed commit must be recovered before any later read or mutation. Never return a fake empty profile. */
    @Synchronized
    fun load(): AchievementProfile {
        recover()
        return if (profileFile.exists()) read(profileFile) else AchievementProfile()
    }

    /** Returns only after the durable profile exists; callers may show unlock notifications afterwards. */
    @Synchronized
    fun update(change: (AchievementProfile) -> Unit): AchievementProfile {
        val next = load()
        change(next)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create achievement directory")
        FileOutputStream(stagingFile).use {
            it.write(json().toJson(next).toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!stagingFile.renameTo(pendingFile)) throw IOException("Cannot persist pending achievements")
        recover()
        return next.clone()
    }

    private fun recover() {
        if (!pendingFile.exists()) return
        // Validate before replacing the only committed copy. Keep corrupt evidence for recovery.
        read(pendingFile)
        if (!pendingFile.renameTo(profileFile)) throw IOException("Cannot commit pending achievements")
    }

    private fun read(file: File): AchievementProfile = try {
        json().fromJson(AchievementProfile::class.java, file.readText(Charsets.UTF_8))
    } catch (error: Exception) {
        throw IOException("Cannot read achievement profile", error)
    }
}
