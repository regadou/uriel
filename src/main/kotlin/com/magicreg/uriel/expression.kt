package com.magicreg.uriel

fun execute(value: Any?): Any? {
    if (value is Expression)
        return execute(value.execute())
    if (value is Resource)
        return execute(value.getData())
    if (value is java.net.URL || value is java.net.URI || value is java.io.File) {
        val res = Resource(value)
        if (res.type != null)
            return execute(res.getData())
        if (value is java.io.File)
            return value.canonicalFile.toURI().toString()
        return value.toString()
    }
    return value
}

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

    fun execute(): Any? {
        if (function != null)
            return (function as UFunction).execute(*params)
        return SIMPLIFY.execute(*params)
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
        if (function != null && function.syntax == FunctionSyntax.BLOC)
            params.add(token)
        else if (token is UFunction) {
            if (params.isEmpty() && function == null)
                function = token
            else if (token == function && token.parameters == null)
                break
            else {
                val subParams = mutableListOf<Any?>()
                val subFunction = compileTokens(status, subParams)
                params.add(Expression(subFunction, subParams.toTypedArray()))
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
            if (exp.function != null && exp.function?.syntax == FunctionSyntax.BLOC) {
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

private fun evalToken(token: String): Any? {
    return KEYWORDS.get(token)
        ?: getFunction(token)
        ?: token.toLongOrNull()
        ?: token.toDoubleOrNull()
        ?: toDate(token)
        ?: getResource(token)
}

private fun getResource(src: String?): Resource? {
    val r = Resource(src)
    if (r.type == null)
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
    for (b in blocs.size-1..closing) {
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
    for (b in blocs.size-1..0) {
        val f = blocs[b].function
        if (f != null && f.name == function)
            return b
    }
    throw RuntimeException("Cannot fin bloc function to close: "+function)
}
