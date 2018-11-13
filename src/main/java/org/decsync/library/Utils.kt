/**
 * libdecsync-android - Utils.kt
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

import org.json.JSONArray
import org.json.JSONObject

fun equalsJSON(lhs: Any, rhs: Any): Boolean {
    if (lhs == JSONObject.NULL && rhs == JSONObject.NULL) {
        return true
    }
    if (lhs is JSONArray && rhs is JSONArray) {
        if (lhs.length() != rhs.length()) {
            return false
        }
        for (i in 0 until lhs.length()) {
            if (!equalsJSON(lhs.get(i), rhs.get(i))) {
                return false
            }
        }
        return true
    }
    if (lhs is JSONObject && rhs is JSONObject) {
        if (lhs.length() != rhs.length()) {
            return false
        }
        for (key in lhs.keys()) {
            if (!rhs.has(key) || !equalsJSON(lhs.get(key), rhs.get(key))) {
                return false
            }
        }
        return true
    }
    return lhs == rhs
}
