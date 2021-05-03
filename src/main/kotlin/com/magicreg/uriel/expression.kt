package com.magicreg.uriel

import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.isSuperclassOf

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
        if (lines.size == 1 && lines[0].isNotEmpty() && lines[0][0] != POUND_SIGN)
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
        return "("+toString(tokens)+")"
    }
}

class Callable(private val callable: KCallable<*>): UFunction {
    override val name = callable.name
    override val type = FunctionType.COMMAND
    override val parameters = callable.parameters.size + 1

    override fun execute(vararg params: Any?): Any? {
        val converted = Array<Any?>(params.size) {null}
        for (i in 0 until params.size)
            converted[i] = convertExecuted(params[i], callable.parameters[i])
        return callable.call(*converted)
    }

    override fun toString(): String {
        return (callable.parameters[0].type.classifier as KClass<*>).qualifiedName + "#" + name
    }
}

fun isArrayExpression(value: Any?): Boolean {
    if (value is Expression) {
        if (value.function == null)
            return value.params.size > 1
        if (value.function is Type) {
            val klass = (value.function as Type).klass
            return Collection::class.isSuperclassOf(klass) || Array::class.isSuperclassOf(klass)
        }
        return false
    }
    if (value is Resource) {
        if (value?.uri?.scheme != null)
            return isArrayExpression(value.getData())
        return false
    }
    return value is Collection<*> || value is Array<*>
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

    override fun toString(): String {
        return "(ParsingStatus at $currentToken of $tokens)"
    }
}

private class ExpressionParams(exp: Expression) {
    val function = exp.function
    val params = mutableListOf<Any?>(*exp.params)

    fun toExpression(): Expression {
        return Expression(function, params.toTypedArray())
    }

    override fun toString(): String {
        return "(ExpressionParams $function $params)"
    }
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
            else if (token.type == FunctionType.BLOC && token.name == "end") {
                status.currentToken++
                return function
            }
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
            else {
                val subexp = lastParamMultilineFunction(exp.params)
                if (subexp == null)
                    params.add(exp)
                else {
                    val bloc = ExpressionParams(exp)
                    bloc.params.removeAt(bloc.params.size-1)
                    blocs.add(bloc)
                    blocs.add(ExpressionParams(subexp))
                    params = blocs[blocs.size-1].params
                }
            }
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
        ?: checkNumeric(token)
        ?: getMusicalNote(token)
        ?: checkResource(token)
        //TODO: if still null, do we throw exception or it might be something else ?
}

private fun checkNumeric(token: String): Any? {
    if (token.isEmpty())
        return null
    var first = token[0]
    if ((first == '+' || first == '-') && token.length > 1)
        first = token[1]
    if (first == '.')
        return token.toDoubleOrNull()
    if (first < '0' || first > '9')
        return null
    if (token.startsWith("0x"))
        return token.toLongOrNull(16)
    return token.toLongOrNull() ?: token.toDoubleOrNull() ?: toDate(token)
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
    val closing = findBlocToClose(blocs, function)
    for (b in blocs.size-1 downTo closing) {
        val exp = blocs.removeAt(b).toExpression()
        if (blocs.size > 0)
            blocs[blocs.size-1].params.add(exp)
        else
            originalParams.add(exp)
    }
    if (closing > 0) {
        val bloc = blocs[closing - 1]
        if (bloc.function?.type == FunctionType.BLOC)
            return bloc.params
        val exp = blocs.removeAt(closing-1).toExpression()
        val params = if (closing > 1) blocs[closing - 2].params else originalParams
        params.add(exp)
        return params
    }
    return originalParams
}

private fun findBlocToClose(blocs: MutableList<ExpressionParams>, function: String): Int {
    if (function.isEmpty())
        return 0
    for (b in blocs.size-1 downTo 0) {
        val f = blocs[b].function
        if (f != null && f.name == function)
            return b
    }
    throw RuntimeException("Cannot find bloc function to close: "+function+"\n"+blocs.joinToString("\n"))
}

private fun lastParamMultilineFunction(params: Array<Any?>): Expression? {
    if (params.isEmpty())
        return null
    val last = params[params.size-1]
    if (last is Expression) {
        val function = last.function
        if (function != null && function.parameters == null && last.params.isEmpty())
            return last
    }
    return null
}

private fun convertExecuted(value: Any?, param: KParameter): Any? {
    return convert(execute(value), param.type.classifier as KClass<*>)
}