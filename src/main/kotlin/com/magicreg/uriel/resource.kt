package com.magicreg.uriel

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import eu.maxschuster.dataurl.DataUrlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.StringWriter
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Enumeration
import java.util.Properties
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.collections.LinkedHashMap
import kotlin.reflect.KClass
import org.apache.commons.beanutils.BeanMap
import org.apache.commons.csv.CSVFormat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.w3c.dom.Node
import javax.sound.midi.MidiSystem

fun readData(input: String, type: String): Any? {
    if (EXTENSIONS_MAP.values.indexOf(type) < 0)
        throw RuntimeException("Unsupported media type: $type")
    return decode(type, ByteArrayInputStream(input.toByteArray(DEFAULT_CHARSET)))
}

fun printData(data: Any?, type: String): String {
    if (EXTENSIONS_MAP.values.indexOf(type) < 0)
        throw RuntimeException("Unsupported media type: $type")
    val output = ByteArrayOutputStream()
    encode(output, type, data)
    return output.toString(DEFAULT_CHARSET)
}

class Resource(private val src: Any?) {

    private var appData: Any? = null
    private var privateUri: URI? = detectUri(src)
    val uri: URI?
        get() { return privateUri }
    private var mimetype: String? = null
    val type: String?
        get() { return getMimetype() }

    constructor(input: InputStream, type: String): this(null) {
        appData = input
        privateUri = URI("io:$input")
        mimetype = type
    }

    constructor(output: OutputStream, type: String): this(null) {
        appData = output
        privateUri = URI("io:$output")
        mimetype = type
    }

    fun getData(): Any? {
        if (privateUri == null) {
            writeLog("not supported uri: $src")
            return null
        }
        if (privateUri?.scheme == null) {
            var value: Any? = currentContext()
            val path = privateUri!!.path.split("/")
            for (key in path) {
                value = getValue(value, key)
                if (value == null)
                    return null
            }
            return value
        }
        if (privateUri?.scheme == "app") {
            if (privateUri?.path == "/")
                return currentContext()
            if (appData is InputStream) {
                if (mimetype == null)
                    mimetype = "text/plain" //TODO: use detectMimetype instead
                appData = decode(mimetype!!, appData as InputStream)
            }
            return appData
        }
        if (privateUri?.scheme == "data") {
            val uridata = DATA_SERIALIZER.unserialize(uri.toString())
            mimetype = uridata.mimeType
            return decode(type!!, ByteArrayInputStream(uridata.data))
        }
        if (privateUri?.scheme == "http" || privateUri?.scheme == "https") {
            val response = httpRequest("get", privateUri!!)
            setMimetype(response)
            if (response.statusCode() < 299)
                return decode(mimetype!!, getInputStream(response.body()))
            return null
        }
        var input: InputStream? = null
        if (privateUri?.scheme == "file") {
            val file = getFile(privateUri!!)
            if (file.isDirectory)
                return file.listFiles()
            if (!file.exists())
                return null
            input = FileInputStream(file)
            val data = decode(type!!, input!!)
            input.close()
            return data
        }
        writeLog("cannot get uri $uri")
        return null
    }

    fun putData(data: Any?): Boolean {
        if (privateUri == null)
            return writeLog("not supported uri: $src")
        if (privateUri?.scheme == null) {
            var value: Any? = currentContext()
            val path = privateUri!!.path.split("/")
            for (p in 0 until path.size-1) {
                value = getValue(value, path[p])
                if (value == null)
                    return false
            }
            return putValue(value, path[path.size-1], execute(data))
        }
        if (privateUri?.scheme == "app")
            return appDataPutOrPost(data)
        if (privateUri?.scheme == "data") {
            val uridata = DATA_SERIALIZER.unserialize(uri.toString())
            mimetype = uridata.mimeType
            val value = execute(data)
            val output = ByteArrayOutputStream()
            encode(output, mimetype!!, value)
            val header = "data:$mimetype;charset=$DEFAULT_CHARSET"
            if (uridata.headers.containsKey("base64"))
                privateUri = URI("$header;base64,"+BASE64_ENCODER.encodeToString(output.toByteArray()))
            else
                privateUri = URI("$header,"+urlencode(output.toString(DEFAULT_CHARSET)))
            return true
        }
        if (privateUri?.scheme == "http" || privateUri?.scheme == "https") {
            val response = httpRequest("put", privateUri!!, data)
            setMimetype(response)
            return response.statusCode() < 299
        }
        if (privateUri?.scheme == "file") {
            val file = getFile(privateUri!!)
            if (file.isDirectory)
                return writeLog("cannot write a directory: $uri")
            if (!file.exists() && !file.parentFile.exists() && !file.parentFile.mkdirs())
                return writeLog("cannot create parent directory for $uri")
            val output = FileOutputStream(file)
            encode(output, type!!, execute(data))
            output.flush()
            output.close()
            return true
        }
        return writeLog("cannot put to uri $uri")
    }

    fun postData(data: Any?): Any? {
        if (privateUri == null) {
            writeLog("not supported uri: $src")
            return null
        }
        if (privateUri?.scheme == null) {
            var value: Any? = currentContext()
            val path = privateUri!!.path.split("/")
            for (key in path) {
                value = getValue(value, key)
                if (value == null)
                    return null
            }
            if (postValue(value, execute(data)))
                return getValue(value, "last")
            return null
        }
        if (privateUri?.scheme == "app")
            return if (appDataPutOrPost(data)) data else null
        if (privateUri?.scheme == "http" || privateUri?.scheme == "https") {
            val response = httpRequest("post", privateUri!!, data)
            setMimetype(response)
            val status = response.statusCode()
            if (status >= 400)
                return null
            if (status >= 300) {
                val location: String? = response.headers().firstValue("location").orElse(null)
                if (location == null)
                    return this
                return Resource(location)
            }
            return decode(mimetype!!, getInputStream(response.body()))
        }
        //TODO: for file and data schemes, append new data to existing one if it makes sense
        writeLog("cannot post to uri $uri")
        return null
    }

    fun delete(): Boolean {
        if (privateUri == null)
            return writeLog("not supported uri: $src")
        if (privateUri?.scheme == null) {
            var value: Any? = currentContext()
            val path = privateUri!!.path.split("/")
            for (p in 0 until path.size-1) {
                value = getValue(value, path[p])
                if (value == null)
                    return false
            }
            return deleteValue(value, path[path.size-1])
        }
        if (privateUri?.scheme == "http" || privateUri?.scheme == "https") {
            val response = httpRequest("delete", privateUri!!)
            setMimetype(response)
            return response.statusCode() < 299
        }
        if (privateUri?.scheme == "file")
            return deleteFile(getFile(privateUri!!))
        return writeLog("cannot delete uri $uri")
    }

    override fun toString(): String {
        return privateUri?.toString() ?: src?.toString() ?: "data:application/json,null"
    }

    private fun getMimetype(): String? {
        if (mimetype == null) {
            if (privateUri == null)
                return null
            if (privateUri?.scheme == null || privateUri?.scheme == "app")
                mimetype = "text/x-uriel"
            else if (privateUri?.scheme == "data")
                mimetype = DATA_SERIALIZER.unserialize(uri.toString()).mimeType
            else if (privateUri?.scheme == "http" || privateUri?.scheme == "https")
                return DEFAULT_HTTP_RESPONSE_MIMETYPE
            else if (SUPPORTED_SCHEMES.indexOf(privateUri?.scheme) >= 0) {
                val parts = uri.toString().split("#")[0].split("?")[0].split("/")
                val last = parts[parts.size-1]
                val dot = last.lastIndexOf('.')
                if (dot > 0)
                    mimetype = EXTENSIONS_MAP[last.substring(dot+1).toLowerCase()] ?: DEFAULT_FILE_MIMETYPE
                else if (privateUri?.scheme == "file")
                    mimetype = if (getFile(privateUri!!).isDirectory) "inode/directory" else DEFAULT_FILE_MIMETYPE
            }
            if (mimetype == null)
                mimetype = DEFAULT_MIMETYPE
        }
        return mimetype
    }

    private fun setMimetype(response: HttpResponse<*>) {
        mimetype = response.headers().firstValue("content-type").orElse(DEFAULT_HTTP_RESPONSE_MIMETYPE)
    }

    private fun appDataPutOrPost(data: Any?): Boolean {
        if (privateUri?.path == "/") {
            val value = execute(data)
            if (value is Map<*,*> && value.isNotEmpty())
                currentContext().putAll(value as Map<String,Any?>)
            else
                return false
            return true
        }
        if (appData is OutputStream) {
            if (mimetype == null)
                mimetype = "text/plain" //TODO: use detectMimetype instead
            encode(appData as OutputStream, mimetype!!, data)
            return true
        }
        return false //TODO: encode if it is output stream or writer
    }
}

private val LOGGER = Logger.getLogger("Resource")
private val JSON = configureMapper(ObjectMapper())
private val YAML = configureMapper(ObjectMapper(YAMLFactory()))
private val DATA_REQUESTS = "put,post,patch".split(",")
private val DATA_SERIALIZER = DataUrlSerializer()
private val BASE64_ENCODER = Base64.getEncoder()
private val DEFAULT_CSV_FORMAT = CSVFormat.EXCEL
private const val CSV_MINIMUM_CONSECUTIVE_FIELDS_SIZE = 5
private const val DEFAULT_MIMETYPE = "text/plain"
private const val DEFAULT_FILE_MIMETYPE = "text/plain"
private const val DEFAULT_HTTP_REQUEST_MIMETYPE = "application/json"
private const val DEFAULT_HTTP_RESPONSE_MIMETYPE = "text/html"
private val DEFAULT_CHARSET = java.nio.charset.StandardCharsets.UTF_8
private val SUPPORTED_SCHEMES = arrayOf("file", "http", "https", "data", "app") //TODO: sftp, mailto, geo, sip, ...
private val EXTENSIONS_MAP = mapOf(
    "json" to "application/json",
    "yaml" to "text/yaml",
    "yml" to "text/yaml",
    "csv" to "text/csv",
    "txt" to "text/plain",
    "sh" to "text/plain",
    "kt" to "text/plain",
    "kts" to "text/plain",
    "uriel" to "text/x-uriel",
    "html" to "text/html",
    "htm" to "text/html",
    "xml" to "application/xml",
    "properties" to "text/x-java-properties",
    "sql" to "application/x-sql",
    "jpql" to "application/x-jpql",
    "mid" to "audio/midi",
    "midi" to "audio/midi",
    "kar" to "audio/midi",
    "sf2" to "audio/x-sf2"
)

private fun decode(type: String, input: InputStream): Any? {
    return when(type) {
        "application/json" -> JSON.readValue(input, Any::class.java)
        "text/yaml" -> YAML.readValue(input, Any::class.java)
        "text/csv" -> decodeCsv(input)
        "text/plain" -> InputStreamReader(input, DEFAULT_CHARSET).readText()
        "text/x-uriel" -> Expression(InputStreamReader(input, DEFAULT_CHARSET).readText())
        "text/html" -> Jsoup.parse(InputStreamReader(input, DEFAULT_CHARSET).readText())
        "application/xml" -> DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        "text/x-java-properties" -> decodeProperties(input)
        "application/x-www-form-urlencoded" -> decodeQueryString(input)
        "application/x-sql" -> InputStreamReader(input, DEFAULT_CHARSET).readText()
        "application/x-jpql" -> InputStreamReader(input, DEFAULT_CHARSET).readText()
        "audio/midi" -> MidiSystem.getSequence(input)
        "audio/x-sf2" -> MidiSystem.getSoundbank(input)
        else -> input.readBytes()
    }
}

private fun encode(output: OutputStream, type: String, data: Any?) {
    when(type) {
        "application/json" -> JSON.writeValue(output, data)
        "text/yaml" -> YAML.writeValue(output, data)
        "text/csv" -> writeBytes(output, encodeCsv(data))
        "text/plain" -> writeBytes(output, stringify(data))
        "text/x-uriel" -> writeBytes(output, stringify(data))
        "text/html" -> writeBytes(output, encodeHtml(data, null, null))
        "application/xml" -> encodeXml(output, data)
        "text/x-java-properties" -> encodeProperties(output, data)
        "application/x-www-form-urlencoded" -> writeBytes(output, encodeQueryString(data))
        "audio/midi" -> saveMusic(output, data)
        "audio/x-sf2" -> throw RuntimeException("Writing soundfont files is not supported")
        else -> writeBytes(output, stringify(data))
    }
}

private fun configureMapper(mapper: ObjectMapper): ObjectMapper {
    mapper.configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(SerializationFeature.WRITE_CHAR_ARRAYS_AS_JSON_ARRAYS, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .configure(SerializationFeature.FAIL_ON_SELF_REFERENCES, false)
            .configure(SerializationFeature.FAIL_ON_UNWRAPPED_TYPE_IDENTIFIERS, false)
            .configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, true)
            .configure(SerializationFeature.WRITE_DATE_KEYS_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.WRITE_DATES_WITH_ZONE_ID, false)
            .configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
            .configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, true)
            .configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true)
            .configure(SerializationFeature.WRITE_ENUMS_USING_INDEX, false)
            .configure(SerializationFeature.WRITE_NULL_MAP_VALUES, true)
            .configure(SerializationFeature.WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED, false)
            .setDateFormat(SimpleDateFormat("yyyy-MM-dd hh:mm:ss"))
    val module = SimpleModule()
    module.addSerializer(Class::class.java, ClassSerializer())
    module.addSerializer(KClass::class.java, KClassSerializer())
    mapper.registerModule(module)
    return mapper
}

private class ClassSerializer(): JsonSerializer<Class<*>>() {
    override fun serialize(value: Class<*>, jgen: JsonGenerator, provider: SerializerProvider) {
        jgen.writeString(value.name)
    }
}

private class KClassSerializer(): JsonSerializer<KClass<*>>() {
    override fun serialize(value: KClass<*>, jgen: JsonGenerator, provider: SerializerProvider) {
        jgen.writeString(value.qualifiedName)
    }
}

private fun decodeProperties(input: InputStream): Any? {
    val props = Properties()
    props.load(InputStreamReader(input, DEFAULT_CHARSET))
    return props
}

private fun encodeProperties(output: OutputStream, value: Any?) {
    var p: Properties?
    if (value is Properties)
        p = value
    else if (value == null)
        p = Properties()
    // TODO: else if value is Iterable or Array
    else {
        val map = if (value is Map<*,*>) value else BeanMap(value)
        p = Properties()
        for (key in map.keys)
            p.setProperty(stringify(key, "null"), stringify(map[key]))
    }
    p.store(output, null)
}

private fun decodeQueryString(input: InputStream): Any? {
    val map = mutableMapOf<String,Any?>()
    var entries = InputStreamReader(input, DEFAULT_CHARSET).readText().split("&")
    for (entry in entries) {
        val eq = entry.indexOf('=')
        if (eq < 0)
            setValue(map, entry, true)
        else
            setValue(map, entry.substring(0,eq), entry.substring(eq+1))
    }
    return map
}

private fun encodeQueryString(value: Any?): String {
    if (value == null || !(value is Map<*,*>))
        throw RuntimeException("Query string data to encode must be a map")
    val entries = mutableListOf<String>()
    val map = value as Map<Any?,Any?>
    for (key in map.keys) {
        entries.add(urlencode(key?.toString() ?: "null")+"="+
                    urlencode(map[key]?.toString() ?: "null"))
    }
    return entries.joinToString("&")
}

private fun encodeXml(output: OutputStream, value: Any?) {
    if (value == null || value !is Node)
        throw RuntimeException("XML data to encode must be of type "+Node::class.qualifiedName)
    TransformerFactory.newInstance().newTransformer().transform(DOMSource(value as Node), StreamResult(output))
}

private fun encodeHtml(value: Any?, separator: String?, fields: MutableList<String>?): String {
    if (value == null)
        return ""
    if (value is Document)
        return value.outerHtml()
    if (value is Map<*,*>)
        return encodeHtmlMap(value as Map<Any?,Any?>, separator, fields)
    if (value is Collection<*>)
        return encodeHtmlCollection(value, separator, fields)
    if (value is Array<*>)
        return encodeHtmlCollection(listOf(*value), separator, fields)
    return value.toString()
}

private fun encodeHtmlMap(map: Map<Any?,Any?>, separator: String?, fields: MutableList<String>?): String {
    val cells = mutableListOf<String>()
    if (fields != null) {
        if (separator == null)
            throw RuntimeException("Bad programming: fields is not null but separator is null")
        for (key in map.keys) {
            val txt = stringify(key, "null")
            if (!fields.contains(txt))
                fields.add(txt)
        }
        for (field in fields)
            cells.add(stringify(map[field]))
    }
    else if (separator != null) {
        for (key in map.keys)
            cells.add(stringify(key, "null")+" = "+stringify(map[key]))
    }
    else {
        cells.add("<table border=1 cellspacing=2 cellpadding=2>\n<tr>")
        for (key in map.keys)
            cells.add("<td>"+stringify(key, "null")+"</td><td>"+stringify(map[key])+"</td>")
        cells.add("</tr>\n</table>")
    }
    return cells.joinToString(separator ?: "</tr>\n<tr>")
}

private fun encodeHtmlCollection(col: Collection<Any?>, separator: String?, fields: MutableList<String>?): String {
    if (col.isEmpty())
        return ""
    if (separator != null)
        return col.joinToString(separator)
    if (fields == null) {
        val first = col.iterator().next()
        if (first is Map<*,*>) {
            val headers = mutableListOf<String>()
            val rows = mutableListOf("<table border=1 cellspacing=2 cellpadding=2>\n<tr>", "")
            for (item in col)
                rows.add("<td>"+encodeHtmlMap(getMap(item), "</td><td>", headers)+"</td>")
            rows.add("</tr>\n</table>")
            rows[1] = "<th>"+headers.joinToString("</th><th>")+"</th>"
            return rows.joinToString("</tr>\n<tr>")
        }
        if (first is Collection<*>) {//TODO

        }
        if (first is Array<*>) {//TODO

        }
        return encodeHtmlCollection(col, "<br>\n", fields)
    }
    return col.joinToString("<br>\n")
}

private fun decodeCsv(input: InputStream): Any {
    val dst = mutableListOf<Map<String,Any>>()
    val records = DEFAULT_CSV_FORMAT.parse(InputStreamReader(input))
    val fields = mutableListOf<String>()
    for (record in records) {
       if (fields.isEmpty()) {
          val it = record.iterator()
          while (it.hasNext())
             fields.add(it.next())
       }
       else {
          val map = mutableMapOf<String,Any>()
          val n = Math.min(fields.size, record.size())
          for (f in 0..n-1)
             map.put(fields.get(f), record.get(f))
          dst.add(map)
       }
    }
    return dst
}

private fun encodeCsv(value: Any?): String {
    val output = StringWriter()
    val printer = DEFAULT_CSV_FORMAT.print(output)
    val records = getMapArray(value)
    val fields = mutableListOf<String>()
    var lastSize = 0
    var consecutives = 0
    for (record in records) {
        for (key in record.keys) {
            if (fields.indexOf(key) < 0)
                fields.add(key.toString())
        }
        if (lastSize == fields.size)
            consecutives++
        else {
            consecutives = 0
            lastSize = fields.size
        }
        if (consecutives >= CSV_MINIMUM_CONSECUTIVE_FIELDS_SIZE)
            break
    }               
    printer.printRecord(fields)
    for (record in records) {
        for (field in fields) {
            val cell = record[field]
            printer.print(stringify(cell))
        }
        printer.println()
    }
    printer.flush()
    return output.toString()
}

private fun decodeValue(src: Any?): Any? {
    if (src is CharSequence) {
        val value = URLDecoder.decode(src.toString(), DEFAULT_CHARSET).trim()
        return when (value) {
            "null" -> null
            "true" -> true
            "false" -> false
            else -> value.toIntOrNull() ?: value.toDoubleOrNull() ?: getExpression(value.trim())
        }
    }
    return src
}

private fun setValue(map: MutableMap<String,Any?>, name: String, value: Any?) {
    val key = URLDecoder.decode(name, DEFAULT_CHARSET)
    val old = map[key]
    if (old == null)
        map[key] = decodeValue(value)
    else if (old is MutableCollection<*>)
        (old as MutableCollection<Any?>).add(decodeValue(value))
    else
        map[key] = mutableListOf<Any?>(old, decodeValue(value))
}

private fun stringify(value: Any?, nullValue: String = ""): String {
    if (value == null)
        return nullValue
    if (value is File)
        return value.canonicalFile.toURI().toString()
    if (value is CharArray)
        return value.joinToString("")
    if (value is java.util.Date) {
        if (value is java.sql.Timestamp || value is java.sql.Time || value is java.sql.Date)
            return value.toString()
        return java.sql.Timestamp(value.time).toString()
    }
    if (value is KClass<*>)
        return value.qualifiedName!!
    if (value is Class<*>)
        return value.name
    if (value::class.java.isArray || value is Iterable<*> || value is Iterator<*> || value is Enumeration<*>)
        return toCollection(value).joinToString(" ") { toString(it) }
    return value.toString()
}

private fun writeBytes(output: OutputStream, txt: String) {
    output.write(txt.toByteArray(DEFAULT_CHARSET))
}

private fun urlencode(txt: String): String {
    return URLEncoder.encode(txt, DEFAULT_CHARSET).replace("+", "%20")
}

private fun getMap(value: Any?): Map<Any?,Any?> {
    if (value is Map<*,*>)
        return value as Map<Any?,Any?>
    if (value is File || value is URI || value is URL)
        return getMap(Resource(value).getData())
    if (value is Array<*> || value is Collection<*> || value is CharSequence || value is Number || value is Boolean)
        return mapOf<Any?,Any?>("value" to value)
    if (value == null)
        return mapOf<Any?,Any?>()
    return BeanMap(value)
}

private fun getMapArray(value: Any?): Array<Map<Any?,Any?>> {
    if (value is Array<*>)
        return value.map {  getMap(it)  }.toTypedArray()
    if (value is Collection<*>)
        return value.map { getMap(it) }.toTypedArray()
    if (value == null)
        return arrayOf<Map<Any?,Any?>>()
    return arrayOf<Map<Any?,Any?>>(getMap(value))
}

private fun getExpression(value: String): Any? {
    if (value.isEmpty())
        return ""
    if ((value[0] == '[' && value[value.length-1] == ']')
     || (value[0] == '{' && value[value.length-1] == '}')
     || (value[0] == '"' && value[value.length-1] == '"')
    )
        return JSON.readValue(ByteArrayInputStream(value.toByteArray()), Any::class.java)
    val comma = value.indexOf(',')
    if (comma < 0)
        return value
    return value.split(",")
}

private fun detectUri(src: Any?): URI? {
    if (src == null || src == "")
        return URI("app:/")
    if (src is URI)
        return src
    if (src is URL)
        return src.toURI()
    if (src is File)
        return src.canonicalFile.toURI()
    if (src is CharSequence) {
        val txt = src.toString().trim()
        if (isRelativeFile(txt))
            return File(txt).canonicalFile.toURI()
        val scheme = getUriScheme(txt)
        if (scheme != null) {
            if (SUPPORTED_SCHEMES.contains(scheme))
                return URI(txt)
        }
        else if (isValidPath(txt))
            return URI(txt)
    }
    return null
}

private fun isRelativeFile(txt: String): Boolean {
    return txt.startsWith("/") || txt.startsWith("./") || txt.startsWith("../")
}

private fun getUriScheme(txt: String): String? {
    for (i in txt.indices) {
        val c = txt[i]
        if (c == ':' && i > 1)
            return txt.substring(0, i)
        if (c in 'a'..'z')
            continue
        if (c in 'A'..'Z')
            continue
        return null
    }
    return null
}

private fun isValidPath(txt: String): Boolean {
    var top = true
    for (part in txt.split("/")) {
        if (part.isEmpty())
            return false
        for (i in part.indices) {
            val c = part[i]
            if (c in 'a'..'z')
                continue
            if (c in 'A'..'Z')
                continue
            if (i == 0 && top)
                return false
            if (c in '0'..'9')
                continue
            if (i == 0 || i == part.length-1)
                return false
            if (c == '_' || c == '-')
                continue
            return false
        }
        top = false
    }
    return true
}

private fun getFile(uri: URI): File {
    if (uri.scheme != "file")
        throw RuntimeException("URI is not a file: $uri")
    if (uri.path == null) {
        val path = uri.toString().split('#')[0].split('?')[0].split(':')[1]
        return File(path).canonicalFile
    }
    return File(uri.path)
}

private fun deleteFile(file: File): Boolean {
    if (!file.exists())
        return false
    if (file.isDirectory) {
        for (f in file.listFiles())
            deleteFile(f)
    }
    return file.delete()
}

private fun httpRequest(method: String, uri: URI, data: Any? = null, headers: Properties? = null): HttpResponse<InputStream> {
    var type: String? = null
    var request = HttpRequest.newBuilder().uri(uri)
    if (headers != null) {
        for (name in headers.stringPropertyNames()) {
            val value = headers.getProperty(name)
            request = request.setHeader(name, value)
            if (name.toLowerCase() == "content-type")
                type = value
        }
    }
    var value = data
    while (value is Expression)
        value = value.execute()
    if (DATA_REQUESTS.contains(method) && type == null) {
        if (value is java.net.URL || value is java.net.URI || value is java.io.File)
            value = Resource(value)
        if (value is Resource)
            type = value.type
        if (type == null)
            type = DEFAULT_HTTP_REQUEST_MIMETYPE
        request = request.setHeader("content-type", type)
    }
    request = when (method) {
        "get" -> request.GET()
        "put" -> request.PUT(HttpRequest.BodyPublishers.ofString(printData(execute(value), type!!)))
        "post" -> request.POST(HttpRequest.BodyPublishers.ofString(printData(execute(value), type!!)))
        "delete" -> request.DELETE()
        else -> throw RuntimeException("Invalid HTTP method: $method")
    }
    return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build().send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
}

private fun getInputStream(src: Any?): InputStream {
    if (src is InputStream)
        return src
    if (src is ByteArray)
        return ByteArrayInputStream(src)
    if (src is CharSequence)
        return ByteArrayInputStream(src.toString().toByteArray())
    if (src is File)
        return FileInputStream(src)
    if (src is Path)
        return FileInputStream(src.toFile())
    return ByteArrayInputStream(stringify(src, "").toByteArray())
}

private fun writeLog(msg: String): Boolean {
    LOGGER.warning(msg)
    return false
}
