package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle

/** Serializes a save-slot write, its backup snapshot, and cloud replacement. Never hold across network I/O. */
object SaveFileLocks {
    private val locks = HashMap<String, Any>()

    fun <T> withLock(file: FileHandle, action: () -> T): T {
        val key = file.file().canonicalPath
        val lock = synchronized(locks) { locks.getOrPut(key) { Any() } }
        return synchronized(lock) { action() }
    }
}
