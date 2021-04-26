package com.magicreg.uriel

import org.apache.commons.beanutils.BeanMap
import java.io.BufferedReader
import java.io.InputStreamReader

val SIZE_KEYS = "size,length,count".split(",")

fun execute(value: Any?): Any? {
    if (value is Expression)
        return execute(value.execute())
    if (value is Resource)
        return execute(value.getData())
    if (value is java.net.URL || value is java.net.URI || value is java.io.File) {
        val res = Resource(value)
        if (res.uri != null)
            return execute(res.getData())
        if (value is java.io.File)
            return value.canonicalFile.toURI().toString()
        return value.toString()
    }
    return value
}

fun getValue(parent: Any?, key: String): Any? {
    if (key == "type")
        return if (parent == null) Any::class else parent::class
    if (parent is Map<*,*>)
        return parent[key]
    if (parent is Collection<*>) {
        if (SIZE_KEYS.contains(key))
            return parent.size
        if (key == "first")
            return getValue(parent, "0")
        if (key == "last")
            return getValue(parent, (parent.size-1).toString())
        val index = key.toIntOrNull()
        if (index == null || index < 0 || index >= parent.size)
            return null
        if (parent is List<*>)
            return parent[index]
        val it = parent.iterator()
        for (i in 0 until index)
            it.next()
        return it.next()
    }
    if (parent is Array<*>)
        return getValue(listOf<Any?>(*parent), key)
    if (parent is CharSequence) {
        if (SIZE_KEYS.contains(key))
            return parent.length
        if (key == "first")
            return getValue(parent, "0")
        if (key == "last")
            return getValue(parent, (parent.length-1).toString())
        val index = key.toIntOrNull()
        if (index != null && index >= 0 && index < parent.length)
            return parent[index].toString()
        return null
    }
    if (parent == null || parent is Number || parent is Boolean)
        return null
    return getValue(BeanMap(parent), key)
}

fun putValue(parent: Any?, key: String, value: Any?): Boolean {
    if (parent is MutableMap<*,*>) {
        (parent as MutableMap<Any?,Any?>)[key] = value
        return true
    }
    if (parent is Array<*>) {
        if (key == "first")
            return putValue(parent, "0", value)
        if (key == "last")
            return putValue(parent, (parent.size-1).toString(), value)
        val index = key.toIntOrNull()
        if (index == null || index < 0 || index >= parent.size)
            return false
        (parent as Array<Any?>)[index] = value
        return true
    }
    if (parent is MutableList<*>) {
        if (key == "first")
            return putValue(parent, "0", value)
        if (key == "last")
            return putValue(parent, (parent.size-1).toString(), value)
        val index = key.toIntOrNull()
        if (index == null || index < 0)
            return false
        val list = (parent as MutableList<Any?>)
        while (index >= parent.size)
            list.add(null)
        list[index] = value
        return true
    }
    if (parent is Iterable<*>)
        return false
    if (parent == null || parent is CharSequence || parent is Number || parent is Boolean)
        return false
    val bean = BeanMap(parent)
    if (bean.containsKey(key)) {
        bean[key] = value
        return true
    }
    return false
}

fun postValue(parent: Any?, value: Any?): Boolean {
    if (parent is MutableCollection<*>)
        return (parent as MutableCollection<Any?>).add(value)
    return false
}

fun deleteValue(parent: Any?, key: String): Boolean {
    if (parent is MutableMap<*,*>) {
        parent.remove(key)
        return true
    }
    if (parent is MutableList<*>) {
        if (key == "first")
            return deleteValue(parent, "0")
        if (key == "last")
            return deleteValue(parent, (parent.size-1).toString())
        val index = key.toIntOrNull()
        if (index == null || index < 0 || index >= parent.size)
            return false
        parent.removeAt(index)
        return true
    }
    return false
}

fun toClassCase(txt: String?): String? {
    if (txt == null || txt.isEmpty())
        return null
    val parts = txt.split("_").toTypedArray()
    if (parts.size == 1)
        return txt.substring(0, 1).toUpperCase()+txt.substring(1)
    for (i in parts.indices)
        parts[i] = parts[i].substring(0, 1).toUpperCase()+parts[i].substring(1)
    return parts.joinToString("")
}

fun toPropertyCase(txt: String?): String? {
    if (txt == null || txt.isEmpty())
        return null
    val parts = txt.split("_").toTypedArray()
    if (parts.size == 1)
        return txt.substring(0, 1).toLowerCase()+txt.substring(1)
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

fun simplify(list: List<Any?>): Any? {
    return when(list.size) {
        0 -> null
        1 -> list[0]
        else -> list
    }
}

fun checkDebug(args: Array<String>): Array<String> {
    if (args.isNotEmpty() && args[0] == "debug") {
        println("*** press enter after starting the debugger ***")
        BufferedReader(InputStreamReader(System.`in`)).readLine()
        return listOf(*args).subList(1, args.size).toTypedArray()
    }
    return args
}
