package com.magicreg.uriel

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.reflect.KClass
import org.apache.commons.beanutils.BeanMap

private val SIZE_KEYS = "size,length,count".split(",")
private val HTML_TAGS = arrayOf("html", "head", "title", "style", "script", "link", "body", "div", "img", "audio", "video")
private val TOP_LEVEL_TYPES = "text,image,audio,video,model,chemical,application,message,inode,multipart".split(",")

fun execute(value: Any?): Any? {
    if (value is Expression)
        return execute(value.execute())
    if (value is Resource)
        return execute(value.getData())
    if (value is java.net.URL || value is java.net.URI || value is java.io.File) {
        val res = Resource(value)
        if (res.uri != null)
            return execute(res.getData())
        return value.toString()
    }
    return value
}

fun isType(value: Any?, vararg types: KClass<*>): Boolean {
    for (type in types) {
        if (type.isInstance(value))
            return true
    }
    return false
}

fun isNotType(value: Any?, vararg types: KClass<*>): Boolean {
    for (type in types) {
        if (type.isInstance(value))
            return false
    }
    return true
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

fun isValidMimetype(src: String): Boolean {
    val parts = src.split("/")
    if (parts.size == 2 && TOP_LEVEL_TYPES.contains(parts[0])) {
        val txt = parts[1]
        for (i in txt.indices) {
            val c = txt[i]
            if (c in 'a'..'z' || c == '.' || c == '-' || c == '+')
                continue
            return false
        }
        return true
    }
    return false
}

fun detectMimetype(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
        val tag = getFirstTag(trimmed)
        if (tag == null)
            return null
        if (HTML_TAGS.contains(tag))
            return "text/html"
        return "application/xml"
    }
    if ( (trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"))
            || (trimmed.startsWith("\"") && trimmed.endsWith("\"")) )
        return "application/json"
    val lines = trimmed.split("\n")
    if (lines.size == 1) {
        val parts = lines[0].split("&")
        var eq = 0
        for (part in parts) {
            if (part.indexOf('=') > 0)
                eq++
            else
                break
        }
        if (eq == parts.size)
            return "application/x-www-form-urlencoded"
    }
    if (lines[0].trim() == "---")
        return "text/yaml"
    var count = 0
    var pounds = 0
    var equals = 0
    var colons = 0
    var dashes = 0
    var records = 0
    var fields = lines[0].split(",").size
    var separator: String? = null
    for (line in lines) {
        if (line.trim().isEmpty())
            continue
        if (fields > 1 && line.split(",").size >= fields)
            records++
        else if (line[0] == '#')
            pounds++
        else if (line.trim().startsWith("-"))
            dashes++
        else {
            val eq = line.indexOf('=')
            val col = line.indexOf(':')
            if (eq > 0 && (col < 0 || col > eq))
                equals++
            else if (col > 0 && (eq < 0 || eq > col))
                colons++
        }
        count++
        if (count >= 10)
            break
    }
    if (records == count)
        return "text/csv"
    if (equals+pounds == count)
        return "text/x-java-properties"
    if (colons+pounds+dashes == count)
        return if (dashes > 0) "text/yaml" else "text/x-java-properties"
    return null
}

fun createMap(params: Array<Any?>): MutableMap<Any?,Any?> {
    if (params.size == 1) {
        val param = execute(params[0])
        if (param is Map<*,*>)
            return LinkedHashMap(param)
        if (isNotType(param, Array::class, Collection::class, CharSequence::class, Number::class, Boolean::class)) {
            val map = mutableMapOf<Any?, Any?>()
            if (param != null) {
                val bean = BeanMap(param)
                for (key in bean.keys)
                    map[key.toString()] = bean[key]
            }
            return map
        }
    }

    val map = mutableMapOf<Any?,Any?>()
    var key: String? = null
    for (param in params) {
        if (key != null) {
            map[key] = execute(param)
            key = null
        }
        else if (isArrayExpression(param)) {
            val values = toList(execute(param))
            if (values.isNotEmpty())
                map[getKey(values[0])] = execute(Expression(null, values.subList(1, values.size).toTypedArray()))
        }
        else
            key = getKey(param)
    }
    if (key != null)
        map[key] = null
    return map
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

fun checkDebug(args: Array<String>): Array<String> {
    if (args.isNotEmpty() && args[0] == "debug") {
        println("*** press enter after starting the debugger ***")
        BufferedReader(InputStreamReader(System.`in`)).readLine()
        return listOf(*args).subList(1, args.size).toTypedArray()
    }
    return args
}

private fun getFirstTag(txt: String): String? {
    val open = txt.indexOf("<")
    if (open < 0)
        return null
    val close = txt.indexOf(">")
    if (close < 0 || close < open)
        return null
    val tag = txt.substring(open+1, close)
    for (i in 0..tag.length-1) {
        val c = tag[i]
        if (c >= 'a' && c <= 'z')
            continue
        if (c >= 'A' && c <= 'Z')
            continue
        if (c >= '0' && c <= '9')
            continue
        if (c != '-' && c != '_')
            return tag.substring(0, i).toLowerCase()
    }
    return tag.toLowerCase()
}
