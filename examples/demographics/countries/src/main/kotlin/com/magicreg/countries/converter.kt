package com.magicreg.countries

import org.apache.commons.beanutils.BeanMap
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter

fun <T: Any> convert(value: Any?, type: KClass<T>): T {
    if (type.isInstance(value))
        return value as T
    var c = CONVERTERS[type]
    if (c != null)
        return c.call(value) as T
    c = findConstructorConverter(type, value)
    if (c != null)
        return c.call(value) as T
    throw RuntimeException("No converter found for "+type.qualifiedName)
}

private val FALSE_WORDS = "false,no,0,none,empty".split(",")
private enum class DatePart { YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, MILLISECOND }
private const val DATE_SEPARATORS = " \ttT,_"
private val CONVERTERS = mutableMapOf<KClass<*>, KFunction<*>>(
    String::class to ::toString,
    CharSequence::class to ::toCharSequence,
    ByteArray::class to ::toByteArray,
    Byte::class to ::toByte,
    Short::class to ::toShort,
    Int::class to ::toInt,
    Long::class to ::toLong,
    Float::class to ::toFloat,
    Double::class to ::toDouble,
    Number::class to ::toNumber,
    Boolean::class to ::toBoolean,
    java.util.Date::class to ::toDate,
    Map::class to ::toMap,
    List::class to ::toList,
    Set::class to ::toSet,
    Collection::class to ::toCollection,
    Array::class to ::toArray,
    Resource::class to ::toResource
)

private fun findConstructorConverter(type: KClass<*>, value: Any?): KFunction<*>? {
    val size = if (value == null) 0 else 1 
    for (cons in type.constructors) {
        val params = cons.parameters
        if (params.size == size && isType(value, params))
            return cons
    }
    return null
}

private fun isType(value: Any?, params: List<KParameter>): Boolean {
    if (value == null)
        return true
    return (params[0].type.classifier as KClass<Any>?)?.isInstance(value) ?: false
}

fun toByteArray(value: Any?): ByteArray {
    if (value is ByteArray)
        return value
    if (value == null)
        return byteArrayOf()
    return toString(value).toByteArray(Charset.forName("utf-8"))
}

fun toCharSequence(value: Any?): CharSequence {
    if (value is CharSequence)
        return value
    return toString(value)
}

fun toString(value: Any?): String {
    if (value is CharSequence || value is Number || value is Boolean)
        return value.toString()
    if (value == null)
        return "null"
    return printData(value, "text/plain")
}

fun toByte(value: Any?): Byte {
    return toNumber(value).toByte()
}

fun toShort(value: Any?): Short {
    return toNumber(value).toShort()
}

fun toInt(value: Any?): Int {
    return toNumber(value).toInt()
}

fun toLong(value: Any?): Long {
    return toNumber(value).toLong()
}

fun toFloat(value: Any?): Float {
    return toNumber(value).toFloat()
}

fun toDouble(value: Any?): Double {
    return toNumber(value).toDouble()
}

fun toNumber(value: Any?): Number {
    val n = numericValue(value, null)
    if (n != null)
        return n
    if (value is CharSequence)
        return value.length
    if (value is CharArray)
        return toNumber(value.joinToString(""))
    if ((value!!)::class.java.isArray()) {
        val length = java.lang.reflect.Array.getLength(value)
        return when(length) {
            0 -> 0
            1 -> numericValue(java.lang.reflect.Array.get(value, 0), 1)!!
            else -> length
        }
    }
    if (value is Collection<*>) {
        val size = value.size
        return when(size) {
            0 -> 0
            1 -> numericValue(value.iterator().next(), 1)!!
            else -> size
        }
    }
    if (value is Map<*,*>) {
        return when(value.size) {
            0 -> 0
            1 -> numericValue(value.values.iterator().next(), 1)!!
            else -> 1
        }
    }
    if (value is Iterable<*> || value is Iterator<*> || value is Enumeration<*>)
        return toNumber(toCollection(value))
    return toNumber(toString(value))
}

fun toBoolean(value: Any?): Boolean {
    if (value is Boolean)
        return value
    if (value is Number)
        return value.toDouble() != 0.0
    if (value is Collection<*>)
        return !value.isEmpty()
    if (value is Map<*,*>)
        return value.isNotEmpty()
    if (value is Collection<*>)
        return !value.isEmpty()
    if (value is CharSequence || value is Char) {
        val txt = value.toString().trim().toLowerCase()
        return !(txt.isEmpty() || FALSE_WORDS.contains(txt))
    }
    if (value == null)
        return false
    if (value::class.java.isArray)
        return java.lang.reflect.Array.getLength(value) > 0
    if (value is Iterable<*> || value is Iterator<*> || value is Enumeration<*>)
        return toBoolean(toCollection(value))
    return toBoolean(toString(value))
}

fun toDate(value: Any?): java.util.Date? {
    try {
        if (value is java.util.Date) {
            if (value is java.sql.Date || value is java.sql.Time || value is java.sql.Timestamp)
                return value
            return java.sql.Timestamp(value.time)
        }
        if (value is java.util.Calendar)
            return java.sql.Timestamp(value.time.time)
        if (value is Array<*>)
            return dateFromArray(value as Array<Any?>)
        if (value is Collection<*>)
            return dateFromArray(value.toTypedArray())
        if (value is CharSequence)
            return dateFromString(value.toString())
        if (value is Number) {
            val year = value.toInt()
            val millis = ((value.toFloat() - year) * 86400000L).toLong()
            val start = parseDate("$year-01-01")
            return java.sql.Timestamp(start.time + millis)
        }
        if (value == null)
            return now()
        /** TODO: other expected date formats
        - partial year-month month-day hour'h|H' hour:minute
        - map of date parts by keys
         **/
    }
    catch (e: Throwable) {}
    return null
}

fun toMap(value: Any?): Map<Any?,Any?> {
    if (value is Map<*,*>)
        return value as Map<Any?,Any?>
    if (value is Collection<*>)
        return CollectionMap(value)
    if (value == null)
        return mapOf<Any?,Any?>()
    if (value::class.java.isArray)
        return CollectionMap(ArrayWrapper(value, java.lang.reflect.Array.getLength(value)))
    if (isPrimitiveValue(value))
        return mapOf<Any?,Any?>("value" to value)
    if (value is Iterable<*> || value is Iterator<*> || value is Enumeration<*>)
        return toMap(toCollection(value))
    return BeanMap(value)
}

fun toArray(value: Any?): Array<Any?> {
    if (value is Array<*>)
        return value as Array<Any?>
    return toList(value).toTypedArray<Any?>()
}

fun toCollection(value: Any?): Collection<Any?> {
    if (value is Collection<*>)
        return value
    return toList(value)
}

fun toSet(value: Any?): Set<Any?> {
    if (value is Set<*>)
        return value
    if (value is Collection<*>)
        return setOf(*value.toTypedArray())
    return setOf(*toList(value).toTypedArray())
}

fun toList(value: Any?): List<Any?> {
    if (value is List<*>)
        return value
    if (value is Collection<*>)
        return value.map {it}        
    if (value == null)
        return emptyList()
    if (value::class.java.isArray)
        return ArrayWrapper(value, java.lang.reflect.Array.getLength(value))
    if (value is CharSequence) {
        val txt = value.toString().trim()
        if (txt.isEmpty())
            return emptyList()
        if (txt.indexOf('\n') > 0)
            return txt.split("\n")
        return txt.split(",") // TODO: we could have other splitters like ; : = & | - / tab and blank
    }
    if (value is Char)
        return listOf(value.toString())
    if (value is Iterator<*>) {
        val list = mutableListOf<Any?>()
        while (value.hasNext())
            list.add(value.next())
        return list
    }
    if (value is Enumeration<*>) {
        val list = mutableListOf<Any?>()
        while (value.hasMoreElements())
            list.add(value.nextElement())
        return list
    }
    if (value is Iterable<*>)
        return toList(value.iterator())
    return listOf(value)
}

fun toResource(value: Any?): Resource {
    if (value is Resource)
        return value
    if (value == null)
        return Resource("data:application/json,null")
    if (value is URI || value is URL || value is File)
        return Resource(value)
    var res: Resource? = null
    if (value is CharSequence) {
        res = Resource(value)
        if (res.uri == null) {
            res = Resource("data:text/plain,")
            res.putData(value)
        }
    }
    else {
        res = Resource("data:application/json,null")
        res.putData(value)
    }
    return res
}

private fun numericValue(value: Any?, defaultValue: Number?): Number? {
    if (value is Number)
        return value
    if (value is Boolean)
        return if (value) 1 else 0
    if (value is CharSequence)
        return value.toString().toDoubleOrNull()
    if (value is Char)
        return value.toString().toDoubleOrNull()
    if (value == null)
        return 0
    return defaultValue
}

private fun isPrimitiveValue(value: Any?): Boolean {
    return value is CharSequence || value is Number || value is Boolean || value is Char
            || value is File || value is URI || value is URL || value is KClass<*> || value is Class<*>
}

private fun now(): java.sql.Timestamp {
    return java.sql.Timestamp(System.currentTimeMillis())
}

private fun parseDate(text: String): java.util.Date {
    return java.text.SimpleDateFormat("yyyy-MM-dd").parse(text)
}

private fun dateFromArray(parts: Array<Any?>): java.util.Date? {
    if (parts.isEmpty())
        return now()
    val values: List<Int> = parts.map { getUnsignedIntOrThrow(it) }
    if (values.size <= 3) {
        val x = values[0]
        val y = if (values.size > 1) values[1] else 1
        val z = if (values.size > 2) values[2] else 1
        if (y > 12 || z > 31)
            return null
        return parseDate("$x-$y-$z")
    }
    val date = java.sql.Timestamp(0)
    val length = Math.min(6, parts.size)
    for (i in 0 until length) {
        val v = values[i]
        when (DatePart.values()[i]) {
            DatePart.YEAR -> date.setYear(v)
            DatePart.MONTH -> date.setMonth(v-1)
            DatePart.DAY -> date.setDate(v)
            DatePart.HOUR -> date.setHours(v)
            DatePart.MINUTE -> date.setMinutes(v)
            DatePart.SECOND -> date.setSeconds(v)
        }
    }
    return date
}

private fun dateFromString(txt: String): java.util.Date? {
    if (txt.trim().isEmpty())
        return now()
    for (c in DATE_SEPARATORS.toCharArray()) {
        val i = txt.indexOf(c)
        if (i > 0) {
            val parts = arrayOf(txt.substring(0, i).trim(), txt.substring(i + 1).trim())
            return dateFromArray(arrayOf<Any?>(*parts[0].split("-").toTypedArray(), *parts[1].split(":").toTypedArray()))
        }
    }
    if (txt.indexOf(':') > 0) {
        val parts = txt.split(':')
        val h = toInt(parts[0])
        val m = if (parts.size > 1) toInt(parts[1]) else 0
        val s = if (parts.size > 2) toInt(parts[2]) else 0
        return java.sql.Time(h, m, s)
    }
    return parseDate(txt)
}

private fun getUnsignedIntOrThrow(value: Any?): Int {
    var n: Int = 0
    if (value is Number)
        n = value.toInt()
    else if (value is CharSequence)
        n = value.toString().toInt()
    else
        throw RuntimeException("Invalid integer value: $value")
    if (n < 0)
        throw RuntimeException("Value is negative: $value")
    return n
}
private class ArrayWrapper(private val array: Any, override val size: Int): AbstractMutableList<Any?>() {

    override fun add(element: Any?): Boolean {
        return false
    }

    override fun add(index: Int, element: Any?) {}

    override fun addAll(elements: Collection<Any?>): Boolean {
        return false
    }

    override fun clear() {}

    override fun get(index: Int): Any? {
        return java.lang.reflect.Array.get(array, index)
    }

    override fun remove(element: Any?): Boolean {
        return false
    }

    override fun removeAll(elements: Collection<Any?>): Boolean {
        return false
    }

    override fun removeAt(index: Int): Any? {
        return null
    }

    override fun removeRange(fromIndex: Int, toIndex: Int) {}

    override fun set(index: Int, element: Any?): Any? {
        val old = java.lang.reflect.Array.get(array, index)
        java.lang.reflect.Array.set(array, index, element)
        return old
    }

    override fun retainAll(elements: Collection<Any?>): Boolean {
        return false
    }
}

private class CollectionMap(private val src: Collection<Any?>): AbstractMutableMap<Any?,Any?>() {
    override val entries
        get() = mutableSetOf<MutableMap.MutableEntry<Any?,Any?>>(FalseMutableEntry("size", src.size))
    override val size
        get() = src.size
    override val keys
        get() = mutableSetOf<Any?>("size")
    override val values
        get() = mutableSetOf<Any?>(src.size)

    override fun get(key: Any?): Any? {
        if (key == "size")
            return src.size
        val index = numericValue(key, null)?.toInt()
        if (index != null && index >= 0 && index < src.size) {
            if (src is List<*>)
                return src.get(index)
            var i = 0
            val it = src.iterator()
            while (i < index) {
                it.next()
                i++
            }
            return it.next()
        }
        return null
    }

    override fun put(key: Any?, value: Any?): Any? {
        val index = numericValue(key, null)?.toInt()
        val old = get(key)
        if (src is MutableList<*> && index != null && index >= 0 && index < src.size)
            (src as MutableList<Any?>).set(index, value)
        return old
    }
}

private class FalseMutableEntry<K,V>(override val key: K, override val value: V): MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V {
        return value
    }
}
