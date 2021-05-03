package com.magicreg.uriel

import java.util.logging.Logger
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.system.exitProcess
import org.apache.commons.beanutils.BeanMap

fun getFunction(name: String): UFunction? {
    return FUNCTIONS[name] ?: checkClassOrFunction(name)
}

fun addFunction(function: UFunction): Boolean {
    val name = function.name
    if (FUNCTIONS[name] != null)
        return false
    FUNCTIONS[name] = function
    return true
}

fun addType(type: KClass<*>, name: String? = null): UFunction? {
    val fname = name ?: type.qualifiedName!!
    if (FUNCTIONS[fname] != null)
        return null
    val function = createConstructorFunction(type, fname)
    if (function != null)
        FUNCTIONS[fname] = function
    return function
}

fun getResource(value: Any?): Any? {
    if (value is Expression)
        return getResource(value.execute())
    if (value == null || value is Resource)
        return value
    if (value is java.net.URL || value is java.net.URI || value is java.io.File)
        return Resource(value)
    if (value is CharSequence) {
        val r = Resource(value)
        if (r.uri != null)
            return r
    }
    return value
}

fun getKey(src: Any?): String {
    if (src is Resource) {
        val uri = src.uri
        if (uri != null && uri.scheme == null)
            return uri.path
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

interface UFunction {
    val name: String
    val type: FunctionType
    val parameters: Int?
    fun execute(vararg params: Any?): Any?
}

enum class FunctionType {
    BLOC, COMMAND, STATE, LOGIC, RELATION, QUALIFIER
}

open class BaseFunction(
        override val name: String,
        override val type: FunctionType,
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
): BaseFunction(name, FunctionType.BLOC, null, function) {}

class Action(
        name: String,
        parameters: Int?,
        function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionType.COMMAND, parameters, function) {}

class Type(
    name: String,
    parameters: Int?,
    val klass: KClass<*>,
    function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionType.COMMAND, parameters, function) {}

class State(
        name: String,
        function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionType.STATE, 2, function) {}

class Relation(
        name: String,
        function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionType.RELATION, 2, function) {}

class Conjunction(
    name: String,
    function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionType.LOGIC, 2, function) {}

class Unary(
    name: String,
    function: (Array<Any?>) -> Any?
): BaseFunction(name, FunctionType.QUALIFIER, 1, function) {}

val GET = Action("get", 1) { params ->
    if (params.isEmpty()) null else getResource(params[0])
}

val PUT = Action("put", 2) { params ->
    val target = if (params.isEmpty()) null else getResource(params[0])
    if (target is Resource)
        target.putData(if (params.size < 2) null else params[1])
    target
}

val POST = Action("post", 2) { params ->
    val target = if (params.isEmpty()) null else getResource(params[0])
    val values = if (params.size < 2) listOf<Any?>(null) else listOf<Any?>(*params).subList(1, params.size)
    var result: Any? = null
    for (value in values) {
        if (target is Resource)
            result = target.postData(value)
        else if (postValue(target, execute(value)))
            result = getValue(target, "last")
    }
    result
}

val DELETE = Action("delete", 1) { params ->
    val target = if (params.isEmpty()) null else getResource(params[0])
    if (target is Resource)
        target.delete()
    else
        false
}

val URI = Type("uri", 1, Resource::class) { params ->
    val value = if (params.isEmpty()) null else execute(params[0])
    if (value is Resource) value else Resource(value)
}

val STRING = Type("string", 1, String::class) { params ->
    if (params.isEmpty()) "" else toString(execute(params[0]))
}

val LIST = Type("list", null, List::class) { params ->
    mutableListOf<Any?>(*executeItems(params))
}

val SET = Type("set", null, Set::class) { params ->
    mutableSetOf<Any?>(*executeItems(params))
}

val MAP = Type("map", null, Map::class) { params -> createMap(params) }

val NUMBER = Type("number", 1, Number::class) { params ->
    if (params.isEmpty()) 0 else toNumber(execute(params[0]))
}

val REAL = Type("real", 1, Double::class) { params ->
    if (params.isEmpty()) 0 else toDouble(execute(params[0]))
}

val INTEGER = Type("integer", 1, Long::class) { params ->
    if (params.isEmpty()) 0 else toLong(execute(params[0]))
}

val BOOLEAN = Type("boolean", 1, Boolean::class) { params ->
    if (params.isEmpty()) false else toBoolean(execute(params[0]))
}

val DATE = Type("date", null, java.util.Date::class) { params ->
    if (params.isEmpty())
        java.util.Date()
    else
        toDate(params.map { execute(it) })
}

val PRINT = Action("print", null) { params ->
    println(params.map { toString(execute(it)) }.joinToString(""))
    null
}

val SHELL = Action("shell", 1) { params ->
    val cmd = params.map { toString(execute(it)) }.joinToString(" ")
    Runtime.getRuntime().exec(cmd, null, null).waitFor()
    //TODO: redirect stdout to caller that can use it as a resource
}

val DO = Action("do", null) { params ->
    var result: Any? = null
    if (params.size > 1) {
        val target = execute(params[0])
        if (target != null) {
            val callable = getKey(params[1])
            val args = listOf(*params).subList(2, params.size).toTypedArray()
            result = execute(Expression(target, callable, args))
        }
    }
    result
}

val EXIT = Action("exit", 1) { params ->
    val status: Int = (if (params.isEmpty()) null else toInt(execute(params[0]))) ?: 0
    exitProcess(status)
}

val EVAL = Action("eval", 1) { params ->
    var result: Any? = null
    for (param in params) {
        val exp = if (param is CharSequence) Expression(param.toString()) else param
        result = execute(exp)
    }
    result
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

val WHILE = Bloc("while") { params ->
    if (params.size > 1) {
        while (toBoolean(execute(params[0]))) {
            for (p in 1 until params.size)
                execute(params[p])
        }
    }
    null
}

val END = Bloc("end") { params ->
    throw RuntimeException("End function called with "+params.joinToString(" "))
}

val IS = State("is") { params ->
    when (params.size) {
        0 -> false
        1 -> checkState(execute(params[0]), true)
        else -> checkState(execute(params[0]), execute(params[1]))
    }
}

val HAS = State("has") { params ->
    val keep = mutableListOf<Any?>()
    if (params.isNotEmpty()) {
        val parent = toCollection(execute(params[0]))
        val condition = if (params.size > 2) Expression(AND, params) else params[1]
        //TODO: apply condition on each item of the collection
    }
    keep
}

val OF = Relation("of") { params ->
    var value: Any? = null
    for (p in params.size-1 downTo 0) {
        val param = execute(params[p])
        if (value == null)
            value = param
        else
            value = getValue(value, toString(param))
        if (value == null)
            break
    }
    value
}

private val LOGGER = Logger.getLogger("Function")
private val FUNCTIONS = initFunctions()

private fun initFunctions(): MutableMap<String,UFunction> {
    val map = mutableMapOf<String,UFunction>()
    for (f in arrayOf<UFunction>(
        GET, PUT, POST, DELETE, DO, IS, HAS,
        EQUAL, LESS, MORE, AND, OR, NOT, OF, // IN, AT, FROM, TO, OUT, BETWEEN
        ADD, // REMOVE, MULTIPLY, DIVIDE, MODULO, EXPONENT, ROOT, LOGARITHM
        URI, STRING, NUMBER, REAL, INTEGER, BOOLEAN, DATE, LIST, SET, MAP,
        PRINT, EVAL, SHELL, EXIT, EACH, WHILE, END // IF, ELSE, FUNCTION
    )) {
        map[f.name] = f
    }
    map["lesser"] = LESS
    map["greater"] = MORE
    map["<"] = LESS
    map[">"] = MORE
    map["="] = EQUAL
    map["that"] = HAS
    map["with"] = HAS
    map["sum"] = ADD
    return map
}

private fun executeItems(items: Array<Any?>): Array<Any?> {
    return Array<Any?>(items.size) { execute(items[it]) }
}

private fun evalExpression(value: Any?): Any? {
    if (value is Expression)
        return evalExpression(value.execute())
    if (value is java.net.URL || value is java.net.URI || value is java.io.File)
        return Resource(value)
    return value
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

private fun checkState(parent: Any?, value: Any?): Boolean {
    return false //TODO: implement IS function in here
}

private fun checkClassOrFunction(name: String): UFunction? {
    var function: UFunction? = null
    val parts = name.split("#")
    if (parts.size > 2)
        return null
    try {
        val klass = Class.forName(parts[0]).kotlin
        function = if (parts.size == 1)
            createConstructorFunction(klass, klass.qualifiedName!!)
        else
            createStaticFunction(klass, parts[1])
    }
    catch(e: Throwable) { return null }
    if (function != null)
        FUNCTIONS[name] = function
    return function
}

private fun createConstructorFunction(klass: KClass<*>, name: String): UFunction? {
    var params: Int? = null
    for (cons in klass.constructors) {
        if (cons.visibility != KVisibility.PUBLIC)
            continue
        if (hasVararg(cons))
            return createClassType(klass, null, name)
        val size = cons.parameters.size
        if (params == null || params < size)
            params = size
    }
    if (params == null)
        return null
    return createClassType(klass, params, name)
}

private fun createStaticFunction(klass: KClass<*>, name: String): UFunction? {
    return null // TODO: get all static functions by those name for this class
}

private fun createClassType(klass: KClass<*>, params: Int?, name: String): UFunction {
    var selected: KFunction<*>? = null
    return Type(name, params, klass) { params ->
        for (cons in klass.constructors) {
            if (cons.visibility != KVisibility.PUBLIC)
                continue
            if (cons.parameters.size == params.size) {
                selected = cons
                break
            }
            if (hasVararg(cons))
                selected = cons
        }
        if (selected != null) {
            val f = selected!!
            if (f.parameters.size == 1 && f.parameters[0].isVararg)
                f.call(params.map { execute(it) }.toTypedArray())
            else
                f.call(*params.map { execute(it) }.toTypedArray())
        }
        else
            null
    }
}

private fun hasVararg(function: KFunction<*>): Boolean {
    for (param in function.parameters) {
        if (param.isVararg)
            return true
    }
    return false
}