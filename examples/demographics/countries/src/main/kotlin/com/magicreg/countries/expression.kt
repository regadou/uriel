package com.magicreg.countries

import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter

class Expression() {

    internal var function: UFunction? = null
    internal var params: Array<Any?> = arrayOf<Any?>()
    val tokens: List<Any?> //TODO: function might be in the middle depending on syntax and params size
        get() { return if (function != null) listOf(function, *params) else listOf(*params) }

    constructor(f: UFunction?, p: Array<Any?>): this() {
        function = f
        params = p
    }

    constructor(txt: String): this() {
        val paramsList = mutableListOf<Any?>()
        val lines = txt.trim().split("\n")
        if (lines.size == 1)
            function = compileTokens(ParsingStatus(parseText(lines[0]).toTypedArray()), paramsList)
        else {
            compileLines(lines, paramsList)
            function = EVAL
        }
        params = paramsList.toTypedArray()
    }

    constructor(target: Any, callable: String, args: Array<Any?>): this() {
        function = findMember(target, callable, args)
        params = arrayOf(target, *args)
    }

    fun execute(): Any? {
        if (function == null)
            return simplify(listOf(*params))
        return (function as UFunction).execute(*params)
    }

    override fun toString(): String {
        return toString(tokens)
    }
}

private const val SPACE_CHAR = '\u0020'      // ' '
private const val DOUBLE_QUOTE = '\u0022'    // '"'
private const val POUND_SIGN = '\u0023'      // '#'
private const val BACK_SLASH = '\u005C'      // '\\'
private const val FIRST_HI_BLANK = '\u007F'
private const val LAST_HI_BLANK = '\u00A0'
private const val FIRST_ACCENT = '\u00C0'
private const val LAST_ACCENT = '\u02AF'
private val KEYWORDS = mapOf<String,Any>(
    "true" to true,
    "false" to false,
    "null" to Expression()
// TODO: the 'all' keyword
)

private class ParsingStatus(val tokens: Array<Any?>) {
    var currentToken: Int = 0

    fun needToken(function: UFunction?, params: List<Any?>): Boolean {
        if (currentToken >= tokens.size)
            return false
        if (function == null || function.parameters == null)
            return true
        return params.size < function?.parameters!!
    }
}

private class ExpressionParams(exp: Expression) {
    val function = exp.function
    val params = mutableListOf<Any?>(*exp.params)
}

private fun compileTokens(status: ParsingStatus, params: MutableList<Any?>): UFunction? {
    var function: UFunction? = null
    while (status.needToken(function, params)) {
        val token = status.tokens[status.currentToken]
        if (function?.type == FunctionType.BLOC)
            params.add(token)
        else if (token is UFunction) {
            if (params.isEmpty() && function == null)
                function = token
            else {
                val subParams = mutableListOf<Any?>()
                val subFunction = compileTokens(status, subParams)
                params.add(Expression(subFunction, subParams.toTypedArray()))
                continue
            }
        }
        else
            params.add(token)
        status.currentToken++
    }
    return function
}

private fun compileLines(lines: List<String>, originalParams: MutableList<Any?>) {
    var params = originalParams
    val blocs = mutableListOf<ExpressionParams>()
    for (line in lines) {
        val txt = line.trim()
        if (txt.isNotEmpty() && txt[0] != POUND_SIGN) {
            val exp = Expression(txt)
            if (exp.function?.type == FunctionType.BLOC) {
                if (exp.function?.name == "end")
                    params = closeBlocs(blocs, getFunctionName(exp.params), originalParams)
                else {
                    blocs.add(ExpressionParams(exp))
                    params = blocs.get(blocs.size-1).params
                }
            }
            else
                params.add(exp)
        }
    }
    closeBlocs(blocs)
}

private fun parseText(text: String): List<Any?> {
    val tokens = mutableListOf<Any?>()
    var start: Int? = null
    var inQuote = false
    for (i in 0..text.length) {
        val c = if (i < text.length) text[i] else SPACE_CHAR
        if (inQuote) {
            if (c == DOUBLE_QUOTE && text[i-1] != BACK_SLASH) {
                tokens.add(readData(text.substring(start!!, i+1), "application/json"))
                start = null
                inQuote = false
            }
        }
        else if (c <= SPACE_CHAR || (c >= FIRST_HI_BLANK && c <= LAST_HI_BLANK)) {
            if (start != null) {
                tokens.add(evalToken(text.substring(start, i)))
                start = null
            }
        }
        else if (start != null)
            continue
        else if (c == DOUBLE_QUOTE) {
            if (start != null)
                tokens.add(evalToken(text.substring(start, i)))
            inQuote = true
            start = i
        }
        else if (c == POUND_SIGN)
        else
            start = i
    }
    if (inQuote)
        throw RuntimeException("Missing closing quote for string literal:\n"+text)
    return tokens
}

private fun findMember(target: Any, callable: String, args: Array<Any?>): UFunction {
    val members = target::class.members.filter{it.name == callable}
    if (members.isEmpty())
        throw RuntimeException("No member found named '$callable' for type "+target::class.qualifiedName)
    val selected = mutableListOf<KCallable<*>>()
    val size = args.size + 1
    for (member in members) {
        if (member.parameters.size == size && isAssignable(member.parameters[0], target))
            selected.add(member)
    }
    if (selected.isEmpty())
        throw RuntimeException("Cannot find a member named '$callable' for target: $target")
    if (selected.size == 1)
        return Callable(selected[0])
    return findBestMatch(selected, args)
}

private fun isAssignable(param: KParameter, target: Any): Boolean {
    return (param.type.classifier as KClass<*>).isInstance(target)
}

private fun findBestMatch(callables: MutableList<KCallable<*>>, args: Array<Any?>): Callable {
    return Callable(callables[0]) //TODO: create algo to find best match
}

private fun evalToken(token: String): Any? {
    return KEYWORDS.get(token)
        ?: getFunction(token)
        ?: token.toLongOrNull()
        ?: token.toDoubleOrNull()
        ?: toDate(token)
        ?: checkResource(token)
}

private fun checkResource(src: String): Resource? {
    val r = Resource(src)
    if (r.uri == null)
        return null
    return r
}

private fun getFunctionName(params: Array<Any?>): String {
    if (params.isEmpty())
        return ""
    val param = params[0]
    if (param is UFunction)
        return param.name
    if (param is Expression)
        return param.function?.name ?: ""
    if (param is Resource)
        return param.uri?.path?.split("/")?.get(0) ?: ""
    return toString(param)
}

private fun closeBlocs(blocs: MutableList<ExpressionParams>, function: String = "", originalParams: MutableList<Any?> = mutableListOf<Any?>()): MutableList<Any?> {
    val closing = findBloctoClose(blocs, function)
    for (b in blocs.size-1 downTo closing) {
        val bloc = blocs[b]
        val exp = Expression(bloc.function, bloc.params.toTypedArray())
        blocs.removeAt(b)
        if (blocs.size > 0)
            blocs[blocs.size-1].params.add(exp)
        else
            originalParams.add(exp)
    }
    if (closing > 0)
        return blocs[closing-1].params
    return originalParams
}

private fun findBloctoClose(blocs: MutableList<ExpressionParams>, function: String): Int {
    if (function.isEmpty())
        return 0
    for (b in blocs.size-1 downTo 0) {
        val f = blocs[b].function
        if (f != null && f.name == function)
            return b
    }
    throw RuntimeException("Cannot fin bloc function to close: "+function)
}

class Callable(private val callable: KCallable<*>): UFunction {
    override val name = callable.name
    override val type = FunctionType.COMMAND
    override val parameters = callable.parameters.size + 1

    override fun execute(vararg params: Any?): Any? {
        val converted = Array<Any?>(params.size) {null}
        for (i in 0 until params.size)
            converted[i] = convert(params[i], callable.parameters[i].type.classifier as KClass<*>)
        return callable.call(*converted)
    }

    override fun toString(): String {
        return (callable.parameters[0].type.classifier as KClass<*>).qualifiedName + "." + name
    }
}
