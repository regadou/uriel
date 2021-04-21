package com.magicreg.uriel

import java.io.InputStreamReader

fun toClassCase(txt: String?): String? {
    if (txt == null || txt.isEmpty())
        return null
    val parts = txt.split("_").toTypedArray()
    for (i in parts.indices)
        parts[i] = parts[i].substring(0, 1).toUpperCase()+parts[i].substring(1)
    return parts.joinToString("")
}

fun toPropertyCase(txt: String?): String? {
    if (txt == null || txt.isEmpty())
        return null
    val parts = txt.split("_").toTypedArray()
    for (i in parts.indices)
        parts[i] = if (i == 0) parts[i].toLowerCase() else parts[i].substring(0, 1).toUpperCase()+parts[i].substring(1)
    return parts.joinToString("")
}

fun toSqlCase(txt: String?): String? {
    if (txt == null || txt.isEmpty())
        return null
    if (txt.split("_").size > 1)
        return txt.toLowerCase()
    val parts = mutableListOf<String>()
    var start: Int? = null
    for (i in txt.indices) {
        val c = txt[i]
        if (c in 'A'..'Z') {
            if (start != null)
                parts.add(txt.substring(start, i).toLowerCase())
            start = i
        }
    }
    if (start == null)
        return txt.toLowerCase()
    parts.add(txt.substring(start, txt.length).toLowerCase())
    return parts.joinToString("_")
}

fun getInternalResource(path: String): String {
    val input = object {}.javaClass.getResourceAsStream(path)
    if (input == null)
        throw RuntimeException("Cannot open internal resource: $path")
    val txt = InputStreamReader(input).readText()
    input.close()
    return txt
}
