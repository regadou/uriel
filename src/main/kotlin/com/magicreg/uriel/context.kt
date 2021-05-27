package com.magicreg.uriel

import java.io.BufferedReader
import java.io.InputStreamReader

fun currentContext(vararg values: Any?): Context {
    var existingContext = CURRENT_CONTEXT.get()
    if (existingContext != null) {
        if (values.isEmpty())
            return existingContext
        throw RuntimeException("Context already initialized on this thread")
    }

    var parent: Context? = null
    var script: String? = null
    val arguments = mutableListOf<Any?>()
    var constants: Map<String,Any?> = mapOf()
    var variables: MutableMap<String,Any?> = mutableMapOf()
    for (value in values) {
        var txt: String? = null
        if (value is Array<*>)
            txt = addArgs(arguments, listOf(*value))
        else if (value is Collection<*>)
            txt = addArgs(arguments, value)
        else if (value is MutableMap<*,*>)
            variables = values as MutableMap<String,Any?>
        else if (value is Map<*,*>)
            constants = values as Map<String,Any?>
        else if (value is Context)
            parent = value
        if (txt != null)
            script = txt
    }

    val cx = Context(parent, script, arguments, constants, variables)
    CURRENT_CONTEXT.set(cx)
    return cx
}

class Context(
    private val parent: Context?,
    val script: String?,
    val arguments: List<Any?>,
    private val constants: Map<String,Any?>,
    private val variables: MutableMap<String, Any?>
): MutableMap<String,Any?> {
    override val size: Int
        get() { return constants.size + variables.size }
    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = variables.entries //TODO: we should also add constant entries
    override val keys: MutableSet<String>
        get() {
            val keyset = mutableSetOf<String>()
            keyset.addAll(constants.keys)
            keyset.addAll(variables.keys)
            return keyset
        }
    override val values: MutableCollection<Any?>
        get() {
            val vals = mutableListOf<Any?>()
            vals.addAll(constants.values)
            vals.addAll(variables.values)
            return vals
        }

    fun childContext(arguments: List<Any?>, constants: Map<String,Any?>): Context {
        if (CURRENT_CONTEXT.get() != this)
            throw RuntimeException("This context is not the current thread context")
        val cx = Context(this, null, arguments, constants, mutableMapOf())
        CURRENT_CONTEXT.set(parent)
        return cx
    }

    fun close() {
        if (CURRENT_CONTEXT.get() != this)
            throw RuntimeException("This context is not the current thread context")
        CURRENT_CONTEXT.set(parent)
    }

    fun isConstant(key: String): Boolean {
        return constants.containsKey(key)
    }

    fun isVariable(key: String): Boolean {
        return variables.containsKey(key)
    }

    override fun toString(): String {
        val map = java.util.TreeMap<String,Any?>()
        if (parent != null)
            map.putAll(parent)
        map.putAll(constants)
        map.putAll(variables)
        return map.toString()
    }

    override fun containsKey(key: String): Boolean {
        return constants.containsKey(key) || variables.containsKey(key)
                || parent?.containsKey(key) ?: false
    }

    override fun containsValue(value: Any?): Boolean {
        return constants.containsValue(value) || variables.containsValue(value)
                || parent?.containsValue(value) ?: false
    }

    override fun get(key: String): Any? {
        if (constants.containsKey(key))
            return constants[key]
        if (variables.containsKey(key))
            return variables[key]
        return parent?.get(key)
    }

    override fun isEmpty(): Boolean {
        return constants.isEmpty() && variables.isEmpty()
    }

    override fun clear() {
        variables.clear()
    }

    override fun put(key: String, value: Any?): Any? {
        if (constants.containsKey(key))
            return constants[key]
        return variables.put(key, value)
    }

    override fun putAll(from: Map<out String, Any?>) {
        for (key in from.keys)
            put(key, from[key])
    }

    override fun remove(key: String): Any? {
        if (variables.containsKey(key))
            return variables.remove(key)
        return null
    }
}

private val CURRENT_CONTEXT = ThreadLocal<Context>()

private fun addArgs(arguments: MutableList<Any?>, values: Collection<Any?>): String? {
    var script: String? = null
    for (value in values) {
        if (arguments.isNotEmpty())
            arguments.add(value)
        else if (value == "debug") {
            println("*** press enter after starting the debugger ***")
            BufferedReader(InputStreamReader(System.`in`)).readLine()
        }
        else if (script != null)
            arguments.add(value)
        else {
            script = getUrielScript(value)
            if (script == null)
                arguments.add(value)
        }
    }
    return script
}

private fun getUrielScript(token: Any?): String? {
    val r = Resource(token)
    if (r.uri != null && r.type == "text/x-uriel") {
        val input = InputStreamReader((r.uri as java.net.URI).toURL().openStream())
        val txt = input.readText()
        input.close()
        return txt
    }
    return null
}