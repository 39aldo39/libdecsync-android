/**
 * libdecsync-android - FileUtils.kt
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

import android.util.Log
import java.io.*
import java.util.ArrayList

object FileUtils {
    fun listFilesRecursiveRelative(src: File,
                                   readBytesSrc: File? = null,
                                   pathPred: (List<String>) -> Boolean = { true }): List<ArrayList<String>> {
        if (src.name[0] == '.') {
            return emptyList()
        }
        if (!pathPred(emptyList())) {
            return emptyList()
        }

        return when {
            src.isFile -> listOf(arrayListOf())
            src.isDirectory -> {
                // Skip same versions
                if (readBytesSrc != null) {
                    val file = File("$src/.decsync-sequence")
                    val version = if (file.exists()) file.readText() else null
                    val readBytesFile = File("$readBytesSrc/.decsync-sequence")
                    val readBytesVersion = if (readBytesFile.exists()) readBytesFile.readText() else null
                    if (version != null) {
                        if (version == readBytesVersion) {
                            return emptyList()
                        } else {
                            try {
                                readBytesFile.parentFile.mkdirs()
                                file.copyTo(readBytesFile, true)
                            } catch (e: IOException) {
                                Log.w(TAG, e.message)
                            }
                        }
                    }
                }

                val files = src.listFiles() ?: emptyArray()
                files.flatMap { file ->
                    val nameDecoded = urldecode(file.name)
                    if (nameDecoded == null) {
                        Log.w(TAG, "Cannot decode name ${file.name}")
                        emptyList()
                    } else {
                        val newReadBytesSrc = if (readBytesSrc == null) null else File("$readBytesSrc/${file.name}")
                        val newPred = { path: List<String> -> pathPred(listOf(nameDecoded) + path)}
                        val paths = listFilesRecursiveRelative(file, newReadBytesSrc, newPred)
                        paths.forEach { it.add(0, nameDecoded) }
                        paths
                    }
                }
            }
            else -> emptyList()
        }
    }

    fun filterFile(file: File, filter: (line: String) -> Boolean) {
        val tempFile = File("${file.parent}/.${file.name}.tmp")
        tempFile.bufferedWriter().use { out ->
            file.forEachLine { line ->
                if (filter(line)) {
                    out.write(line + "\n")
                }
            }
        }
        tempFile.renameTo(file)
    }

    fun pathToString(path: List<String>): String = path.joinToString("/") { urlencode(it) }

    fun urlencode(input: String): String {
        val output = input.toByteArray().joinToString("") {
            if (it.toChar().isLetterOrDigit() || "-_.~".contains(it.toChar())) {
                "%c".format(it)
            } else {
                "%%%2X".format(it)
            }
        }

        return if (output.isNotEmpty() && output[0] == '.') {
            "%2E" + output.substring(1)
        } else {
            output
        }
    }

    fun urldecode(input: String): String? {
        val builder = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c != '%') {
                builder.append(c)
            } else {
                if (i + 2 >= input.length) return null
                val value = try {
                    (input[i + 1].toString() + input[i + 2].toString()).toInt(16)
                } catch (e: NumberFormatException) {
                    return null
                }
                builder.append(value.toChar())
                i += 2
            }
            i++
        }
        return builder.toString()
    }
}
