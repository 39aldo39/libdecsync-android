/**
 * libdecsync-android - Subpath.kt
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

interface OnEntryUpdateListener<in T> {
    fun matchesPath(path: List<String>): Boolean
    fun onEntriesUpdate(path: List<String>, entries: List<Decsync.Entry>, extra: T)
}

interface OnSubdirEntryUpdateListener<in T> : OnEntryUpdateListener<T> {
    val subdir: List<String>
    fun onSubdirEntryUpdate(path: List<String>, entry: Decsync.Entry, extra: T)

    override fun matchesPath(path: List<String>): Boolean = path.take(subdir.size) == subdir

    override fun onEntriesUpdate(path: List<String>, entries: List<Decsync.Entry>, extra: T) {
        val convertedPath = convertPath(path)
        for (entry in entries) {
            onSubdirEntryUpdate(convertedPath, entry, extra)
        }
    }

    private fun convertPath(path: List<String>): List<String> = path.drop(subdir.size)
}

interface OnSubfileEntryUpdateListener<in T> : OnEntryUpdateListener<T> {
    val subfile: List<String>
    fun onSubfileEntryUpdate(entry: Decsync.Entry, extra: T)

    override fun matchesPath(path: List<String>): Boolean = path == subfile

    override fun onEntriesUpdate(path: List<String>, entries: List<Decsync.Entry>, extra: T) {
        for (entry in entries) {
            onSubfileEntryUpdate(entry, extra)
        }
    }
}
