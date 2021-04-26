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
import java.util.Properties
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.reflect.KClass
import org.apache.commons.beanutils.BeanMap
import org.apache.commons.csv.CSVFormat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.w3c.dom.Node
import java.util.*
import kotlin.collections.LinkedHashMap

fun readData(input: InputStream, type: String): Any? {
    if (EXTENSIONS_MAP.values.indexOf(type) < 0)
        throw RuntimeException("Unsupported media type: $type")
    return decode(type, input)
}

fun readData(input: String, type: String): Any? {
    if (EXTENSIONS_MAP.values.indexOf(type) < 0)
        throw RuntimeException("Unsupported media type: $type")
    return decode(type, ByteArrayInputStream(input.toByteArray(DEFAULT_CHARSET)))
}

fun printData(data: Any?, type: String): String {
    if (EXTENSIONS_MAP.values.indexOf(type) < 0)
        throw RuntimeException("Unsupported media type: $type")
    return encode(type, data)
}
 
fun createDataUri(value: Any?, mimetype: String = "text/plain", base64: Boolean = false): String {
    val bytes = encode(mimetype, value)
    val header = "data:$mimetype;charset=$DEFAULT_CHARSET"
    if (base64)
        return "$header;base64,"+BASE64_ENCODER.encodeToString(bytes.toByteArray(DEFAULT_CHARSET))
    return "$header,"+urlencode(bytes)
}

fun isMimetype(src: String): Boolean {
    val parts = src.split("/")
    if (parts.size == 2 && TOP_LEVEL_TYPES.contains(parts[0]))
        return validSubtype(parts[1])
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

fun initVariables(map: Map<String,Any?>? = null) {
    CURRENT_VARIABLES.set(null)
    if (map != null && map.isNotEmpty())
        getVariables().putAll(map)
}

class Resource(private val src: Any?) {

    val uri: URI? = detectUri(src)
    private var mimetype: String? = null
    val type: String?
        get() { return getMimetype() }

    fun getData(): Any? {
        if (uri == null) {
            writeLog("not supported uri: $src")
            return null
        }
        if (uri.scheme == null) {
            var value: Any? = getVariables()
            val path = uri.path.split("/")
            for (key in path) {
                value = getValue(value, key)
                if (value == null)
                    return null
            }
            return value
        }
        if (uri.scheme == "data") {
            val bytes = DATA_SERIALIZER.unserialize(uri.toString()).data
            return decode(type!!, ByteArrayInputStream(bytes))
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            val response = httpRequest("get", uri)
            setMimetype(response)
            if (response.statusCode() < 299)
                return decode(mimetype!!, getInputStream(response.body()))
            return null
        }
        var input: InputStream? = null
        if (uri.scheme == "file") {
            val file = getFile(uri)
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
        if (uri == null)
            return writeLog("not supported uri: $src")
        if (uri.scheme == null) {
            var value: Any? = getVariables()
            val path = uri.path.split("/")
            for (p in 0 until path.size-1) {
                value = getValue(value, path[p])
                if (value == null)
                    return false
            }
            return putValue(value, path[path.size-1], execute(data))
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            val response = httpRequest("put", uri, data)
            setMimetype(response)
            return response.statusCode() < 299
        }
        if (uri.scheme == "file") {
            val file = getFile(uri)
            if (file.isDirectory)
                return writeLog("cannot write a directory: $uri")
            if (!file.exists() && !file.parentFile.exists() && !file.parentFile.mkdirs())
                return writeLog("cannot create parent directory for $uri")
            val output = FileOutputStream(file)
            output.write(encode(type!!, execute(data)).toByteArray())
            output.flush()
            output.close()
            return true
        }
        return writeLog("cannot put to uri $uri")
    }

    fun postData(data: Any?): Any? {
        if (uri == null) {
            writeLog("not supported uri: $src")
            return null
        }
        if (uri.scheme == null) {
            var value: Any? = getVariables()
            val path = uri.path.split("/")
            for (key in path) {
                value = getValue(value, key)
                if (value == null)
                    return null
            }
            if (postValue(value, execute(data)))
                return getValue(value, "last")
            return null
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            val response = httpRequest("post", uri, data)
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
        //TODO: for file scheme, do a append bytes if it makes sense
        writeLog("cannot post to uri $uri")
        return null
    }

    fun delete(): Boolean {
        if (uri == null)
            return writeLog("not supported uri: $src")
        if (uri.scheme == null) {
            var value: Any? = getVariables()
            val path = uri.path.split("/")
            for (p in 0 until path.size-1) {
                value = getValue(value, path[p])
                if (value == null)
                    return false
            }
            return deleteValue(value, path[path.size-1])
        }
        if (uri.scheme == "http" || uri.scheme == "https") {
            val response = httpRequest("delete", uri)
            setMimetype(response)
            return response.statusCode() < 299
        }
        if (uri.scheme == "file")
            return deleteFile(getFile(uri))
        return writeLog("cannot delete uri $uri")
    }

    override fun toString(): String {
        return uri?.toString() ?: src?.toString() ?: "data:application/json,null"
    }

    private fun getMimetype(): String? {
        if (mimetype == null) {
            if (uri == null)
                return null
            if (uri.scheme == null)
                mimetype = "text/x-uriel"
            else if (uri.scheme == "data")
                mimetype = DATA_SERIALIZER.unserialize(uri.toString()).mimeType
            else if (uri.scheme == "http" || uri.scheme == "https")
                return DEFAULT_HTTP_RESPONSE_MIMETYPE
            else if (SUPPORTED_SCHEMES.indexOf(uri.scheme) >= 0) {
                val parts = uri.toString().split("#")[0].split("?")[0].split("/")
                val last = parts[parts.size-1]
                val dot = last.lastIndexOf('.')
                if (dot > 0)
                    mimetype = EXTENSIONS_MAP[last.substring(dot+1).toLowerCase()] ?: DEFAULT_FILE_MIMETYPE
                else if (uri.scheme == "file")
                    mimetype = if (getFile(uri).isDirectory) "inode/directory" else DEFAULT_FILE_MIMETYPE
            }
            if (mimetype == null)
                mimetype = DEFAULT_MIMETYPE
        }
        return mimetype
    }

    private fun setMimetype(response: HttpResponse<*>) {
        mimetype = response.headers().firstValue("content-type").orElse(DEFAULT_HTTP_RESPONSE_MIMETYPE)
    }
}

private class Variables(): LinkedHashMap<String,Any?>() {}
private val LOGGER = Logger.getLogger("Resource")
private val CURRENT_VARIABLES = ThreadLocal<Variables>()
private val JSON = configureMapper(ObjectMapper())
private val YAML = configureMapper(ObjectMapper(YAMLFactory()))
private val DATA_REQUESTS = "put,post,patch".split(",")
private val DATA_SERIALIZER = DataUrlSerializer()
private val BASE64_ENCODER = Base64.getEncoder()
private val DEFAULT_CSV_FORMAT = CSVFormat.EXCEL
private const val CSV_MINIMUM_CONSECUTIVE_FIELDS_SIZE = 5
private val HTML_TAGS = arrayOf("html", "head", "title", "style", "script", "link", "body", "div", "img", "audio", "video")
private const val DEFAULT_MIMETYPE = "text/plain"
private const val DEFAULT_FILE_MIMETYPE = "text/plain"
private const val DEFAULT_HTTP_REQUEST_MIMETYPE = "application/json"
private const val DEFAULT_HTTP_RESPONSE_MIMETYPE = "text/html"
private val TOP_LEVEL_TYPES = "text,image,audio,video,model,chemical,application,message,inode,multipart".split(",")
private val DEFAULT_CHARSET = java.nio.charset.StandardCharsets.UTF_8
private val SUPPORTED_SCHEMES = arrayOf("file", "http", "https", "data") //TODO: sftp, mailto, app, geo, sip, ...
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
    "jpql" to "application/x-jpql"
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
        else -> input.readBytes()
    }
}

private fun encode(type: String, data: Any?): String {
    return when(type) {
        "application/json" -> JSON.writeValueAsString(data)
        "text/yaml" -> YAML.writeValueAsString(data)
        "text/csv" -> encodeCsv(data)
        "text/plain" -> stringify(data)
        "text/x-uriel" -> YAML.writeValueAsString(data)
        "text/html" -> encodeHtml(data, null, null)
        "application/xml" -> encodeXml(data)
        "text/x-java-properties" -> encodeProperties(data)
        "application/x-www-form-urlencoded" -> encodeQueryString(data)
        else -> data?.toString() ?: ""
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

private fun encodeProperties(value: Any?): String {
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
    val output = StringWriter()
    p.store(output, null)
    return output.toString()
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

private fun encodeXml(value: Any?): String {
    if (value == null || !(value is Node))
        throw RuntimeException("XML data to encode must be of type "+Node::class.qualifiedName)
    val output = ByteArrayOutputStream()
    TransformerFactory.newInstance().newTransformer().transform(
        DOMSource(value as Node),
        StreamResult(output)
    )
    return output.toString(DEFAULT_CHARSET)
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
    if (src is URI)
        return src
    if (src is URL)
        return src.toURI()
    if (src is File)
        return src.getCanonicalFile().toURI()
    if (src is CharSequence) {
        val txt = src.toString().trim()
        if (isRelativeFile(txt))
            return File(txt).getCanonicalFile().toURI()
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
    for (i in 0..txt.length-1) {
        val c = txt.get(i)
        if (c == ':' && i > 1)
            return txt.substring(0, i)
        if (c >= 'a' && c <= 'z')
            continue
        if (c >= 'A' && c <= 'Z')
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
        for (i in 0..part.length-1) {
            val c = part.get(i)
            if (c >= 'a' && c <= 'z')
                continue
            if (c >= 'A' && c <= 'Z')
                continue
            if (i == 0 && top)
                return false
            if (c >= '0' && c <= '9')
                continue
            if (i == 0 || i == txt.length-1)
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
    if (file.isDirectory()) {
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
        "put" -> request.PUT(HttpRequest.BodyPublishers.ofString(encode(type!!, execute(value))))
        "post" -> request.POST(HttpRequest.BodyPublishers.ofString(encode(type!!, execute(value))))
        "delete" -> request.DELETE()
        else -> throw RuntimeException("Invalid HTTP method: "+method)
    }
    return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build().send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
}

private fun getVariables(): Variables {
    var variables = CURRENT_VARIABLES.get()
    if (variables == null) {
        variables = Variables()
        CURRENT_VARIABLES.set(variables)
    }
    return variables
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

private fun validSubtype(txt: String): Boolean {
    for (i in txt.indices) {
        val c = txt[i]
        if (c in 'a'..'z' || c == '.' || c == '-' || c == '+')
            continue
        return false
    }
    return true
}

private fun writeLog(msg: String): Boolean {
    LOGGER.warning(msg)
    return false
}
