package com.magicreg.uriel

import java.util.logging.Logger
import kotlin.system.exitProcess

fun getFunction(name: String): UFunction? {
    return FUNCTIONS[name]
}

fun addFunction(function: UFunction): Boolean {
    val name = function.name
    if (FUNCTIONS[name] != null)
        return false
    FUNCTIONS[name] = function
    return true
}

interface UFunction {

    val name: String
    val syntax: FunctionSyntax
    val parameters: Int?
    fun execute(vararg params: Any?): Any?
}

enum class FunctionSyntax {
    BLOC, COMMAND, INFIX, LOGIC, MODIFIER
}

open class BaseFunction(
    override val name: String,
    override val syntax: FunctionSyntax,
    override val parameters: Int?,
    private val function: (Array<Any?>) -> Any?
): UFunction {
    override fun execute(vararg params: Any?): Any? {
        return function(params as Array<Any?>)
    }
    override fun toString(): String {
        return name
    }
}

class Bloc(
        name: String,
        function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionSyntax.BLOC, null, function) {}

class Action(
        name: String,
        parameters: Int?,
        function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionSyntax.COMMAND, parameters, function) {}

class Type(
    name: String,
    parameters: Int?,
    function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionSyntax.COMMAND, parameters, function) {}

class Relation(
    name: String,
    function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionSyntax.INFIX, 2, function) {}

class Conjunction(
    name: String,
    function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionSyntax.LOGIC, 2, function) {}

class Unary(
    name: String,
    function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionSyntax.MODIFIER, 1, function) {}

val GET = Action("get", null) { params ->
    var value: Any? = null
    for (param in params) {
        if (value != null) {
            value = getValue(execute(value), getKey(param))
            if (value == null)
                break
            continue
        }
        value = getResource(param)
        if (value == null)
            break
    }
    value
}

val PUT = Action("put", 2) { params ->
    val res = if (params.isEmpty()) null else getResource(params[0])
    if (res is Resource)
        res.putData(if (params.size < 2) null else execute(params[1]))
    res
}

val POST = Action("post", 2) { params ->
    val res = if (params.isEmpty()) null else getResource(params[0])
    if (res is Resource)
        res.postData(if (params.size < 2) null else execute(params[1]))
    else
        postValue(res, execute(params[1]))
    res
}

val DELETE = Action("delete", 1) { params ->
    val res = if (params.isEmpty()) null else getResource(params[0])
    if (res is Resource)
        res.delete()
    else
        false
}

val URI = Type("uri", null) { params ->
    val buffer = StringBuilder()
    for (param in params) {
        val txt = toString(execute(param))
        if (txt.isEmpty())
            continue
        if (buffer.isNotEmpty() && !buffer.endsWith("/") && !txt.startsWith("/"))
            buffer.append("/")
        buffer.append(txt)
    }
    Resource(buffer.toString())
}

val DATA = Type("data", null) { params ->
    val value = if (params.isEmpty()) null else execute(params[0])
    val mimetype = if (params.size > 1) toString(execute(params[1])) else "text/plain"
    createDataUri(value, mimetype)
}

val TEXT = Type("text", null) { params ->
    if (params.size == 2) {
        val first = execute(params[0])
        val second = execute(params[1])
        val type: String? = if (second is CharSequence) second.toString() else null
        if (type != null && isMimetype(type))
            printData(first, type)
        else
            toString(first) + toString(second)
    }
    else
        params.joinToString("") { toString(execute(it)) }
}

val LIST = Type("list", null) { params ->
    mutableListOf<Any?>(*executeItems(params))
}

val SET = Type("set", null) { params ->
    mutableSetOf<Any?>(*executeItems(params))
}

val MAP = Type("map", null) { params ->
    val map = mutableMapOf<String,Any?>()
    var key: String? = null
    for (param in params) {
        if (key == null)
            key = toString(execute(param))
        else {
            map[key] = execute(param)
            key = null
        }
    }
    if (key != null)
        map[key] = null
    map
}

val NUMBER = Type("number", 1) { params ->
    if (params.isEmpty()) 0 else toNumber(execute(params[0]))
}

val INTEGER = Type("integer", 1) { params ->
    if (params.isEmpty()) 0 else toLong(execute(params[0]))
}

val BOOLEAN = Type("boolean", 1) { params ->
    if (params.isEmpty()) false else toBoolean(execute(params[0]))
}

val DATE = Type("date", null) { params ->
    if (params.isEmpty())
        java.util.Date()
    else
        toDate(params.map { execute(it) })
}

val PRINT = Action("print", null) { params ->
    println(TEXT.execute(*params))
    null
}

val EXIT = Action("exit", 1) { params ->
    val param: Any = (if (params.isEmpty()) null else params[0]) ?: 0
    val n: Int? = if (param is Number) param.toInt() else param.toString().toIntOrNull()
    if (n != null)
        exitProcess(n)
    else {
        // TODO: find a parent block matching the param value
        throw RuntimeException("Block exit is not implemented yet: "+toString(arrayOf<Any?>("exit", *params)))
    }
    null
}

val EVAL = Action("eval", 1) { params ->
    if (params.isEmpty())
        null
    else {
        val param = params[0]
        execute(if (param is CharSequence) Expression(param.toString()) else param)
    }
}

val SIMPLIFY = Action("simplify", null) { params ->
    when(params.size) {
        0 -> null
        1 -> params[0] //TODO: check if value is array or collection and could be empty or first element null
        else -> listOf(*params)
    }
}

val ADD = Action("add", null) { params ->
    var result: Any? = null
    for (param in params) {
        val value = execute(param)
        if (result == null)
            result = value
        else if (result is Number)
            result = result.toDouble() + toDouble(value)
        else if (result is CharSequence)
            result = result.toString() + toString(value ?: "")
        else if (result is Boolean)
            result = result && toBoolean(value)
        else {
            val list = mutableListOf<Any?>()
            list.addAll(toCollection(result))
            list.addAll(toCollection(value))
            result = list
        }
    }
    result
}

val EQUAL = Relation("equal") { params ->
    when (params.size) {
        0 -> true
        1 -> compare(execute(params[0]), null) == 0
        else -> compare(execute(params[0]), execute(params[1])) == 0
    }
}

val LESS = Relation("less") { params ->
    when (params.size) {
        0 -> false
        1 -> compare(execute(params[0]), null) < 0
        else -> compare(execute(params[0]), execute(params[1])) < 0
    }
}

val MORE = Relation("more") { params ->
    when (params.size) {
        0 -> false
        1 -> compare(execute(params[0]), null) > 0
        else -> compare(execute(params[0]), execute(params[1])) > 0
    }
}

val AND = Conjunction("and") { params ->
    var result = true
    for (param in params) {
        result = toBoolean(execute(param))
        if (!result)
            break
    }
    result
}

val OR = Conjunction("or") { params ->
    var result = false
    for (param in params) {
        result = toBoolean(execute(param))
        if (result)
            break
    }
    result
}

val NOT = Unary("not") { params ->
    var result = true
    for (param in params) {
        result = !toBoolean(execute(param))
        if (!result)
            break
    }
    result
}

val EACH = Bloc("each") { params ->
    if (params.size > 2) {
        val src = toCollection(execute(params[0]))
        val key = getKey(params[1])
        for (item in src) {
            Resource(key).putData(item)
            for (p in 2 until params.size)
                execute(params[p])
        }
    }
    null
}

val END = Bloc("end") { params ->
    if (params.isEmpty()) "" else params[0]?.toString() ?: ""
}

private val LOGGER = Logger.getLogger("Function")
private val FUNCTIONS = initFunctions()

private fun initFunctions(): MutableMap<String,UFunction> {
    val map = mutableMapOf<String,UFunction>()
    for (f in arrayOf<UFunction>(
        GET, PUT, POST, DELETE, PRINT, EVAL, SIMPLIFY, EXIT,
        URI, DATA, TEXT, LIST, SET, MAP, NUMBER, INTEGER, BOOLEAN, DATE,
        ADD, EQUAL, LESS, MORE, AND, OR, NOT,
        EACH, END
    )) {
        map[f.name] = f
    }
    return map
}

private fun executeItems(items: Array<Any?>): Array<Any?> {
    return Array<Any?>(items.size) { execute(items[it]) }
}

internal fun getResource(value: Any?): Any? {
    if (value is Expression)
        return getResource(value.execute())
    if (value == null || value is Resource)
        return value
    if (value is java.net.URL || value is java.net.URI || value is java.io.File)
        return Resource(value)
    if (value is CharSequence) {
        val r = Resource(value)
        if (r.type != null)
            return r
    }
    return value
}

internal fun getKey(src: Any?): String {
    if (src is Resource) {
        if (src.uri != null && src.uri.scheme == null)
            return src.uri.path
        return getKey(src.getData())
    }
    if (src is Expression)
        return getKey(src.execute())
    if (src is java.net.URL || src is java.net.URI || src is java.io.File)
        return getKey(Resource(src))
    if (src == null)
        return ""
    if (src is CharSequence)
        return src.toString()
    return toString(src)
}

private fun compare(v1: Any?, v2: Any?): Int {
    if (v1 == null) {
        if (v2 == null)
            return 0
        return -1
    }
    if (v2 == null)
        return 1
    if (v1 is Array<*> || v1 is Collection<*> || v2 is Array<*> || v2 is Collection<*>) {
        val a1 = toArray(v1)
        val a2 = toArray(v2)
        val n = Math.min(a1.size, a2.size)
        for (i in 0 until n) {
            val dif = compare(a1[i], a2[i])
            if (dif != 0)
                return dif
        }
        return a1.size.compareTo(a2.size)
    }
    if (v1 is Number || v2 is Number || v1 is Boolean || v2 is Boolean)
        return toDouble(v1).compareTo(toDouble(v2))
    return toString(v1).compareTo(toString(v2))
}
