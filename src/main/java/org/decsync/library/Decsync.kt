/**
 * libdecsync-android - Decsync.kt
 *
 * Copyright (C) 2018 Aldo Gunsing
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, see <http://www.gnu.org/licenses/>.
 */

package org.decsync.library

import android.os.Build
import android.os.Environment
import android.util.Log
import org.decsync.library.FileUtils.pathToString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

const val TAG = "DecSync"

/**
 * The `DecSync` class represents an interface to synchronized key-value mappings stored on the file
 * system.
 *
 * The mappings can be synchronized by synchronizing the directory [dir]. The stored mappings are
 * stored in a conflict-free way. When the same keys are updated independently, the most recent
 * value is taken. This should not cause problems when the individual values contain as little
 * information as possible.
 *
 * Every entry consists of a path, a key and a value. The path is a list of strings which contains
 * the location to the used mapping. This can make interacting with the data easier. It is also used
 * to construct a path in the file system. All characters are allowed in the path. However, other
 * limitations of the file system may apply. For example, there may be a maximum length or the file
 * system may be case insensitive.
 *
 * To update an entry, use the method [setEntry]. When multiple keys in the same path are updated
 * simultaneous, it is encouraged to use the more efficient methods [setEntriesForPath] and
 * [setEntries].
 *
 * To get notified about updated entries, use the method [executeAllNewEntries] to get all updated
 * entries and execute the corresponding actions. The method [initObserver] creates a file observer
 * which is notified about the updated entries immediately.
 *
 * Sometimes, updates cannot be execute immediately. For example, if the name of a category is
 * updated when the category does not exist yet, the name cannot be changed. In such cases, the
 * updates have to be executed retroactively. In the example, the update can be executed when the
 * category is created. For such cases, use the method [executeStoredEntries].
 *
 * Finally, to initialize the stored entries to the most recent values, use the method
 * [initStoredEntries]. This method is almost exclusively used when the application is installed. It
 * is almost always followed by a call to [executeStoredEntries].
 *
 * @param T the type of the extra data passed to the [listeners] and [syncComplete].
 * @property dir the directory in which the synchronized DecSync files are stored.
 * For the default location, use [getDecsyncSubdir].
 * @property ownAppId the unique appId corresponding to the stored data by the application. There
 * must not be two simultaneous instances with the same appId. However, if an application is
 * reinstalled, it may reuse its old appId. In that case, it has to call [initStoredEntries] and
 * [executeStoredEntries]. Even if the old appId is not reused, it is still recommended call these.
 * For the default appId, use [getAppId].
 * @property listeners a list of listeners describing the actions to execute on every updated entry.
 * When an entry is updated, the method [OnEntryUpdateListener.onEntriesUpdate] is called on the
 * listener whose method [OnEntryUpdateListener.matchesPath] returns true.
 * @property syncComplete an optional function which is called when a sync is complete. For example,
 * it can be used to update the UI.
 */
class Decsync<in T>(
        private val dir: String,
        private val ownAppId: String,
        private val listeners: Iterable<OnEntryUpdateListener<T>>,
        private val syncComplete: (T) -> Unit = {}
) {
    private val ownAppIdEncoded = FileUtils.urlencode(ownAppId)
    private var observer: FolderObserver? = null

    /**
     * Represents an [Entry] with its path.
     */
    class EntryWithPath(val path: List<String>, val entry: Entry) {
        /**
         * Convenience constructor for nicer syntax.
         */
        constructor(path: List<String>, key: Any, value: Any) : this(path, Entry(key, value))
    }

    /**
     * Represents a key/value pair stored by DecSync. Additionally, it has a datetime property
     * indicating the most recent update. It does not store its path, see [EntryWithPath].
     */
    class Entry(internal val datetime: String, val key: Any, val value: Any) {
        /**
         * Convenience constructor which sets the [datetime] property to the current datetime.
         */
        constructor(key: Any, value: Any) : this(iso8601Format.format(Date()), key, value)

        override fun toString(): String {
            val result = JSONArray()
            result.put(datetime)
            result.put(key)
            result.put(value)
            return jsonToString(result)
        }

        companion object {
            internal fun fromLine(line: String): Entry? {
                try {
                    val json = JSONTokener(line).nextValue()
                    if (json !is JSONArray || json.length() != 3) {
                        Log.w(TAG, "Invalid entry $line")
                        return null
                    }
                    val datetime: String? = json.optString(0, null)
                    if (datetime == null) {
                        Log.w(TAG, "Invalid entry $line")
                        return null
                    }
                    val key = json.get(1)
                    val value = json.get(2)
                    return Entry(datetime, key, value)
                } catch (e: JSONException) {
                    Log.w(TAG, "Invalid JSON: $line", e)
                    return null
                }
            }
        }
    }

    private class EntriesLocation(val path: List<String>, val newEntriesFile: File, val storedEntriesFile: File?, val readBytesFile: File?)

    private fun getNewEntriesLocation(path: List<String>, appId: String): EntriesLocation {
        val pathString = FileUtils.pathToString(path)
        val appIdEncoded = FileUtils.urlencode(appId)
        return EntriesLocation(
                path,
                File("$dir/new-entries/$appIdEncoded/$pathString"),
                File("$dir/stored-entries/$ownAppIdEncoded/$pathString"),
                File("$dir/read-bytes/$ownAppIdEncoded/$appIdEncoded/$pathString")
        )
    }

    private fun getStoredEntriesLocation(path: List<String>): EntriesLocation {
        val pathString = FileUtils.pathToString(path)
        return EntriesLocation(
                path,
                File("$dir/stored-entries/$ownAppIdEncoded/$pathString"),
                null,
                null
        )
    }

    /**
     * Associates the given [value] with the given [key] in the map corresponding to the given
     * [path]. This update is sent to synchronized devices.
     */
    fun setEntry(path: List<String>, key: Any, value: Any) = setEntriesForPath(path, listOf(Entry(key, value)))

    /**
     * Like [setEntry], but allows multiple entries to be set. This is more efficient if multiple
     * entries share the same path.
     *
     * @param entriesWithPath entries with path which are inserted.
     */
    fun setEntries(entriesWithPath: List<EntryWithPath>) {
        entriesWithPath.groupBy({ it.path }, { it.entry }).forEach { (path, entries) ->
            setEntriesForPath(path, entries)
        }
    }

    /**
     * Like [setEntries], but only allows the entries to have the same path. Consequently, it can
     * be slightly more convenient since the path has to be specified just once.
     *
     * @param path path to the map in which the entries are inserted.
     * @param entries entries which are inserted.
     */
    fun setEntriesForPath(path: List<String>, entries: List<Entry>) {
        Log.d(TAG, "Write to path ${pathToString(path)}")
        val entriesLocation = getNewEntriesLocation(path, ownAppId)

        // Write new entries
        val builder = StringBuilder()
        for (entry in entries) {
            builder.appendln(entry.toString())
        }
        entriesLocation.newEntriesFile.parentFile.mkdirs()
        entriesLocation.newEntriesFile.appendText(builder.toString())

        // Update .decsync-sequence files
        var pathVar = path
        while (pathVar.isNotEmpty()) {
            pathVar = pathVar.dropLast(1)
            val dir = getNewEntriesLocation(pathVar, ownAppId).newEntriesFile
            val file = File("$dir/.decsync-sequence")

            // Get the old version
            val version =
                    if (file.exists())
                        file.readText().toLongOrNull() ?: 0
                    else
                        0

            // Write the new version
            file.parentFile.mkdirs()
            file.writeText((version + 1).toString())
        }

        // Update stored entries
        updateStoredEntries(entriesLocation, entries.toMutableList())
    }

    /**
     * Initializes the observer which watches the filesystem for updated entries and executes the
     * corresponding actions.
     *
     * @param extra extra data passed to the [listeners].
     */
    fun initObserver(extra: T) {
        val listener = fun (_: File, pathString: String) {
            try {
                val pathEncoded = pathString.split("/").filter { it.isNotEmpty() }
                if (pathEncoded.isEmpty() || pathEncoded.last()[0] == '.') {
                    return
                }
                val pathWithAppId = pathEncoded.map { FileUtils.urldecode(it) }.requireNoNulls()
                val appId = pathWithAppId.first()
                val path = pathWithAppId.drop(1)
                val entriesLocation = getNewEntriesLocation(path, appId)
                if (appId != ownAppId && entriesLocation.newEntriesFile.isFile) {
                    executeEntriesLocation(entriesLocation, extra)
                    Log.d(TAG, "Sync complete")
                    syncComplete(extra)
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Cannot decode path $pathString")
            }
        }
        try {
            val newEntriesDir = File("$dir/new-entries")
            newEntriesDir.mkdirs()
            observer = FolderObserver(listener, newEntriesDir)
            observer?.startWatching()
            Log.v(TAG, "Initialized folder observer for $dir/new-entries")
        } catch (e: FolderObserver.FolderNotExistingException) {
            Log.w(TAG, e)
        }
    }

    /**
     * Gets all updated entries and executes the corresponding actions.
     *
     * @param extra extra data passed to the [listeners].
     */
    fun executeAllNewEntries(extra: T) {
        Log.d(TAG, "Execute all new entries in $dir")
        val newEntriesDir = File("$dir/new-entries")
        val readBytesDir = File("$dir/read-bytes/$ownAppIdEncoded")
        val pathPred = { path: List<String> -> path.isEmpty() || path.first() != ownAppId }
        FileUtils.listFilesRecursiveRelative(newEntriesDir, readBytesDir, pathPred)
                .map { getNewEntriesLocation(it.drop(1), it.first()) }
                //.filter { it.newEntriesFile.isFile } // TODO: why?
                .forEach { executeEntriesLocation(it, extra) }
        Log.d(TAG, "Sync complete")
        syncComplete(extra)
    }

    private fun executeEntriesLocation(entriesLocation: EntriesLocation,
                                       extra: T,
                                       keyPred: (json: Any) -> Boolean = { true },
                                       valuePred: (json: Any) -> Boolean = { true }) {
        val readBytes =
                if (entriesLocation.readBytesFile?.exists() == true)
                    entriesLocation.readBytesFile.readText().toLongOrNull() ?: 0
                else
                    0
        val size = entriesLocation.newEntriesFile.length()
        if (readBytes >= size) return
        entriesLocation.readBytesFile?.parentFile?.mkdirs()
        entriesLocation.readBytesFile?.writeText(size.toString())

        Log.d(TAG, "Execute entries of ${entriesLocation.newEntriesFile}")

        val reader = entriesLocation.newEntriesFile.reader()
        reader.skip(readBytes)
        val entries = reader
                .readLines()
                .mapNotNull { Entry.fromLine(it) }
                .filter { keyPred(it.key) && valuePred(it.value) }
                .groupBy { it.key }.values
                .map { it.maxBy { it.datetime }!! }
                .toMutableList()
        executeEntries(entriesLocation, entries, extra)
    }

    private fun executeEntries(entriesLocation: EntriesLocation, entries: MutableList<Entry>, extra: T) {
        updateStoredEntries(entriesLocation, entries)

        val listener = listeners.firstOrNull { it.matchesPath(entriesLocation.path) }
        if (listener == null) {
            Log.e(TAG, "Unknown action for path ${entriesLocation.path}")
            return
        }
        listener.onEntriesUpdate(entriesLocation.path, entries, extra)
    }

    private fun updateStoredEntries(entriesLocation: EntriesLocation, entries: MutableList<Entry>) {
        if (entriesLocation.storedEntriesFile == null) {
            return
        }

        try {
            var haveToFilterFile = false
            if (entriesLocation.storedEntriesFile.exists()) {
                entriesLocation.storedEntriesFile.forEachLine { line ->
                    val entryLine = Entry.fromLine(line)
                    if (entryLine != null) {
                        val iterate = entries.iterator()
                        while (iterate.hasNext()) {
                            val entry = iterate.next()
                            if (equalsJSON(entry.key, entryLine.key)) {
                                if (entry.datetime > entryLine.datetime) {
                                    haveToFilterFile = true
                                } else {
                                    iterate.remove()
                                }
                            }
                        }
                    }
                }
            }

            if (haveToFilterFile) {
                FileUtils.filterFile(entriesLocation.storedEntriesFile) { line ->
                    val entryLine = Entry.fromLine(line)
                    if (entryLine == null) {
                        false
                    } else {
                        !entries.any { equalsJSON(it.key, entryLine.key) }
                    }
                }
            }

            val builder = StringBuilder()
            for (entry in entries) {
                builder.appendln(entry.toString())
            }
            entriesLocation.storedEntriesFile.parentFile.mkdirs()
            entriesLocation.storedEntriesFile.appendText(builder.toString())
        } catch (e: Exception) {
            Log.e(TAG, e.message)
        }
    }

    /**
     * Gets all stored entries satisfying the predicates and executes the corresponding actions.
     *
     * @param executePath path to the entries to executes. This can be either a file or a directory.
     * If it specifies a file, the entries in that file are executed. If it specifies a directory,
     * all entries in all subfiles are executed.
     * @param extra extra data passed to the [listeners].
     * @param keyPred optional predicate on the keys. The key has to satisfy this predicate to be
     * executed.
     * @param valuePred optional predicate on the values. The value has to satisfy this predicate to
     * be executed.
     * @param pathPred optional predicate on the subpaths. Each subpath has to satisfy this
     * predicate to be executed. This holds for directories as well. Furthermore, the path of
     * specified in [executePath] is not part of the argument.
     */
    @JvmOverloads
    fun executeStoredEntries(executePath: List<String>,
                             extra: T,
                             keyPred: (json: Any) -> Boolean = { true },
                             valuePred: (json: Any) -> Boolean = { true },
                             pathPred: (path: List<String>) -> Boolean = { true }) {
        val executePathString = FileUtils.pathToString(executePath)
        val executeDir = File("$dir/stored-entries/$ownAppIdEncoded/$executePathString")
        FileUtils.listFilesRecursiveRelative(executeDir, pathPred = pathPred)
                .map { executePath + it }
                .forEach { path ->
                    val entriesLocation = getStoredEntriesLocation(path)
                    executeEntriesLocation(entriesLocation, extra, keyPred, valuePred)
                }
    }

    /**
     * Initializes the stored entries. This method does not execute any actions. This is often
     * followed with a call to [executeStoredEntries].
     */
    fun initStoredEntries() {
        // Get the most up-to-date appId
        var appIdVar: String? = null
        var maxDatetime: String? = null
        FileUtils.listFilesRecursiveRelative(File("$dir/stored-entries"))
                .filter { it.isNotEmpty() }
                .forEach { path ->
                    val pathString = FileUtils.pathToString(path)
                    val file = File("$dir/stored-entries/$pathString")
                    file.forEachLine { line ->
                        val entry = Entry.fromLine(line)
                        if (entry != null) {
                            if ((maxDatetime?.compareTo(entry.datetime) ?: -1) < 0 ||
                                    path.first() == ownAppId && entry.datetime == maxDatetime) // Prefer own appId
                            {
                                maxDatetime = entry.datetime
                                appIdVar = path.first()
                            }
                        }
                    }
                }

        val appId = appIdVar
        if (appId == null) {
            Log.i(TAG, "No appId found for initialization")
            return
        }

        // Copy the stored files and update the read bytes
        if (appId != ownAppId) {
            val appIdEncoded = FileUtils.urlencode(appId)

            val storedEntriesDir = File("$dir/stored-entries/$appIdEncoded")
            if (storedEntriesDir.exists()) {
                storedEntriesDir.copyRecursively(File("$dir/stored-entries/$ownAppIdEncoded"), overwrite = true)
            }

            val readBytesDir = File("$dir/read-bytes/$appIdEncoded")
            if (readBytesDir.exists()) {
                readBytesDir.copyRecursively(File("$dir/read-bytes/$ownAppIdEncoded"), overwrite = true)
            }
            val newEntriesDir = File("$dir/new-entries/$appIdEncoded")
            val ownReadBytesDir = File("$dir/read-bytes/$ownAppIdEncoded/$appIdEncoded")
            FileUtils.listFilesRecursiveRelative(newEntriesDir, ownReadBytesDir).forEach { path ->
                val pathString = FileUtils.pathToString(path)
                val size = File("$dir/new-entries/$appIdEncoded/$pathString").length()
                val file = File("$dir/read-bytes/$ownAppIdEncoded/$appIdEncoded/$pathString")
                file.parentFile.mkdirs()
                file.writeText(size.toString())
            }
        }
    }

    companion object {
        /**
         * Returns the value of the given [key] in the map of the given [path], and in the given
         * [DecSync directory][decsyncDir] without specifying an appId, or `null` if there is no
         * such value. The use of this method is discouraged. It is recommended to use the method
         * [executeStoredEntries] when possible.
         */
        fun getStoredStaticValue(decsyncDir: String, path: List<String>, key: Any): Any? {
            Log.d(TAG, "Get value for key ${jsonToString(key)} for path ${pathToString(path)} in $decsyncDir")
            val pathString = FileUtils.pathToString(path)
            var result: Any? = null
            var maxDatetime: String? = null
            val storedEntriesDir = File("$decsyncDir/stored-entries")
            (storedEntriesDir.listFiles { file -> file.name[0] != '.' } ?: emptyArray())
                    .map { File("$it/$pathString") }
                    .filter { it.isFile }
                    .forEach {
                        it.forEachLine { line ->
                            val entry = Entry.fromLine(line)
                            if (entry != null) {
                                if (equalsJSON(entry.key, key) && (maxDatetime?.compareTo(entry.datetime) ?: -1) < 0) {
                                    maxDatetime = entry.datetime
                                    result = entry.value
                                }
                            }
                        }
                    }
            return result
        }

        private val iso8601Format: DateFormat = formatDateAs("yyyy-MM-dd'T'HH:mm:ss")

        private fun formatDateAs(format: String): DateFormat {
            val df = SimpleDateFormat(format)
            df.timeZone = TimeZone.getTimeZone("UTC")
            return df
        }

        private fun jsonToString(json: Any) = if (json is String) JSONObject.quote(json) else json.toString()
    }
}

/**
 * Returns the path to the DecSync subdirectory in a [decsyncBaseDir] for a [syncType] and
 * optionally with a [collection].
 *
 * @param decsyncBaseDir the path to the main DecSync directory, or null for the default one.
 * @param syncType the type of data to sync. For example, "rss", "contacts" or "calendars".
 * @param collection an optional collection identifier when multiple instances of the [syncType] are
 * supported. For example, this is the case for "contacts" and "calendars", but not for "rss".
 */
fun getDecsyncSubdir(decsyncBaseDir: String? = null, syncType: String, collection: String? = null): String {
    var dir = decsyncBaseDir ?: getDefaultDecsyncBaseDir()
    dir += "/" + FileUtils.urlencode(syncType)
    if (collection != null) {
        dir += "/" + FileUtils.urlencode(collection)
    }
    return dir
}

/**
 * Returns the default DecSync directory. This is the "DecSync" subdirectory on the primary external
 * storage.
 */
fun getDefaultDecsyncBaseDir(): String = "${Environment.getExternalStorageDirectory()}/DecSync"

/**
 * Returns a list of DecSync collections inside a [decsyncBaseDir] for a [syncType]. This function
 * does not apply for sync types with single instances.
 *
 * @param decsyncBaseDir the path to the main DecSync directory, or null for the default one.
 * @param syncType the type of data to sync. For example, "contacts" or "calendars".
 * @param ignoreDeleted `true` to ignore deleted collections. A collection is considered deleted if
 * the most recent value of the key "deleted" with the path ["info"] is set to `true`.
 */
fun listDecsyncCollections(decsyncBaseDir: String? = null, syncType: String, ignoreDeleted: Boolean = true): List<String> {
    val decsyncSubdir = getDecsyncSubdir(decsyncBaseDir, syncType)
    return (File(decsyncSubdir).listFiles() ?: emptyArray()).mapNotNull(
        fun(dir): String? {
            if (!dir.isDirectory || dir.name[0] == '.') return null
            if (ignoreDeleted) {
                val deleted = Decsync.getStoredStaticValue(dir.path, listOf("info"), "deleted") as? Boolean ?: false
                if (deleted) return null
            }
            return FileUtils.urldecode(dir.name)
        })
}

/**
 * Returns the appId of the current device and application combination.
 *
 * @param appName the name of the application.
 * @param id an optional integer (between 0 and 100000 exclusive) to distinguish different instances
 * on the same device and application.
 */
fun getAppId(appName: String, id: Int? = null): String {
    val appId = "${Build.MODEL}-$appName"
    return when (id) {
        null -> appId
        else -> "$appId-${"%05d".format(id)}"
    }
}
