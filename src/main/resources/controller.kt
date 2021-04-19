package com.magicreg.uriel

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import java.util.StringTokenizer
import java.util.logging.Logger
import javax.inject.Inject
import javax.ws.rs.Consumes
import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.WebApplicationException
import javax.ws.rs.core.Context
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.resteasy.spi.HttpRequest

@Path("/")
class FileController {

    @ConfigProperty(name = "uriel.document.root")
    var documentRoot: String = ""

    @ConfigProperty(name = "uriel.shutdown.path")
    var shutdownPath: String = ""

    @GET
    @Path("{path:.*}")
    fun getResource(@Context request: HttpRequest, @PathParam("path") path: String): Response {
        if (!shutdownPath.isEmpty() && (path == shutdownPath || path.startsWith(shutdownPath+"/")))
            shutdownApp(path.split("/"))
        val file = File(documentRoot+"/"+path)
        if (file.isDirectory()) {
            val response = needRedirect(request)
            if (response != null)
                return response
            val index = getIndexFile(file)
            if (index == null)
                return listFolderFiles(file, selectMimetype(request, FOLDER_LISTING_TYPES), documentRoot)
            return sendFile(index, request)
        }
        if (file.exists())
            return sendFile(file, request)
        return Response.status(Response.Status.NOT_FOUND).build()
    }
}

@Path("$(path)")
class DataController @Inject constructor(private var factory: EntityFactory) {

    @ConfigProperty(name = "uriel.allow.sql")
    var allowSql: Boolean = false
    @ConfigProperty(name = "uriel.allow.jpql")
    var allowJpql: Boolean = false
    @ConfigProperty(name = "uriel.allow.uriel")
    var allowUriel: Boolean = false
    @ConfigProperty(name = "uriel.allow.text")
    var allowText: Boolean = false
    @ConfigProperty(name = "uriel.allow.select")
    var allowSelect: Boolean = false
    @ConfigProperty(name = "uriel.allow.insert")
    var allowInsert: Boolean = false
    @ConfigProperty(name = "uriel.allow.update")
    var allowUpdate: Boolean = false
    @ConfigProperty(name = "uriel.allow.delete")
    var allowDelete: Boolean = false

    @GET
    fun tables(@Context request: HttpRequest): Response {
        if (!allowSelect)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        return sendResponse(factory.types, selectMimetype(request, QUERY_RESPONSE_TYPES))
    }

    @POST
    fun query(@Context request: HttpRequest, body: String): Response {
        val inputType = getContentType(request)
        val outputType = selectMimetype(request, QUERY_RESPONSE_TYPES)
        val queryType = getQueryType(inputType, body)
        if (queryType != null) {
            if (queryType == QueryType.TEXT) { //TODO: support for natural language
                val langs = request.getHttpHeaders().getAcceptableLanguages().map { it.getLanguage() }
                if (langs.indexOf("en") < 0)
                    return Response.status(Response.Status.NOT_ACCEPTABLE).build()
                return Response.status(Response.Status.NOT_IMPLEMENTED).build()
            }
            if (queryType == QueryType.URIEL)
                return sendResponse(Expression(body).execute(), outputType)
            return sendResponse(factory.textQuery(body, queryType), outputType)
        }

        if (!allowSelect)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        val data = getMap(readData(body, inputType))
        return sendResponse(factory.getEntities(data), outputType)
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{type}")
    fun getType(@PathParam("type") type: String): Properties {
        if (!allowSelect)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        val properties = factory.getProperties(type)
        if (properties == null)
            throw WebApplicationException(Response.Status.NOT_FOUND)
        return properties
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{type}")
    fun addInstance(@PathParam("type") type: String, @Context request: HttpRequest, body: String): List<Any> {
        if (!allowInsert)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        if (factory.types.indexOf(type) < 0)
            throw WebApplicationException(Response.Status.NOT_FOUND)
        val data = readData(body, getContentType(request))
        val instances: Collection<Any?> = if (data is Collection<*>) data as Collection<Any?> else listOf<Any?>(data)
        val results = mutableListOf<Any>()
        for (instance in instances) {
            if (instance != null) {
                val entity = factory.saveEntity(instance, type)
                results.add(factory.getIdValue(entity)!!)
            }
        }
        return results
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{type}/{id}")
    fun getInstance(@PathParam("type") type: String, @PathParam("id") id: String): Any {
        if (!allowSelect)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        if (factory.types.indexOf(type) < 0)
            throw WebApplicationException(Response.Status.NOT_FOUND)
        val entity = factory.getEntity(type, id)
        if (entity == null)
            throw WebApplicationException(Response.Status.NOT_FOUND)
        return entity
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{type}/{id}")
    fun putInstance(@PathParam("type") type: String, @PathParam("id") id: String, data: Map<String,Any?>): Any {
        if (!allowInsert || !allowUpdate)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        if (factory.types.indexOf(type) < 0)
            throw WebApplicationException(Response.Status.NOT_FOUND)
        val idkey = factory.getIdProperty(type)
        data.put(idkey, id)
        val entity = factory.saveEntity(data, type)
        if (entity == null)
            throw WebApplicationException(Response.Status.CONFLICT)
        return entity
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{type}/{id}")
    fun deleteEntity(@PathParam("type") type: String, @PathParam("id") id: String): Response {
        if (!allowDelete)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        if (factory.types.indexOf(type) < 0)
            throw WebApplicationException(Response.Status.NOT_FOUND)
        if (factory.deleteEntity(type, id))
            Response.status(Response.Status.NO_CONTENT).build()
        throw WebApplicationException(Response.Status.NOT_FOUND)
    }

    private fun getQueryType(inputType: String, query: String? = null): QueryType {
        val queryType: QueryType? = when (inputType) {
            "application/x-sql" -> if (allowSql) QueryType.SQL else null
            "application/x-jpql" -> if (allowJpql) QueryType.JPQL else null
            "text/x-uriel" -> if (allowUriel) QueryType.URIEL else null
            "text/plain" -> if (allowText) QueryType.TEXT else null
            else -> return null
        }
        if (queryType == QueryType.TEXT)
            return queryType // TODO: could we check the statement type ?
        if (queryType == null)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        if (query == null)
            return queryType // statement check was not desired
        if (queryType == QueryType.URIEL)
            return validateUrielQuery(query)
        val statement = StringTokenizer(query).nextToken().toLowerCase()
        val allowedStatement = when (statement) {
            "select" -> allowSelect
            "insert" -> allowInsert
            "update" -> allowUpdate
            "delete" -> allowDelete
            else -> throw WebApplicationException(Response.Status.BAD_REQUEST)
        }
        if (!allowedStatement)
            throw WebApplicationException(Response.Status.FORBIDDEN)
        return queryType
    }

    private fun validateUrielQuery(query: String): QueryType {
        val tokenizer = StringTokenizer(query)
        while (tokenizer.hasMoreTokens()) {
            val allowedStatement = when(tokenizer.nextToken().toLowerCase()) {
                "get" -> allowSelect
                "post" -> allowInsert
                "put" -> allowInsert && allowUpdate
                "delete" -> allowDelete
            }
            if (!allowedStatement)
                throw WebApplicationException(Response.Status.FORBIDDEN)
        }
        return QueryType.URIEL
    }
}

private fun getMap(src: Any?): Map<Any?,Any?> {
    return when (src) {
        is Collection<*> -> mergeCollection(src as Collection<Any?, mapOf<Any?,Any?>())
        is Array<*> -> mergeCollection(listOf<Any?>(*src), mapOf<Any?,Any?>())
        is Map<*,*> -> src as Map<Any?,Any?>
        else -> throw WebApplicationException(Response.Status.BAD_REQUEST)
    }
}

private fun mergeCollection(src: Collection<Any?>, map: Map<Any?,Any?>): Map<Any?,Any?> {
    for (item in src)
        map.putAll(getMap(item))
    return map
}

private fun getContentType(request: HttpRequest): String {
    return request.getHttpHeaders().getMediaType().toString().split(";")[0]
}

private fun sendResponse(data: Any?, type: String): Response {
    return Response.ok(printData(data, type), type).build()
}

private fun needRedirect(request: HttpRequest): Response? {
    val uri = request.getUri().getPath()
    if (uri.endsWith("/"))
        return null
    return Response.status(Response.Status.MOVED_PERMANENTLY).location(URI(uri+"/")).build()
}

private fun getIndexFile(folder: File): File? {
    var indexFile: File? = null
    var priority: Int = INDEX_TYPES.size
    for (file in folder.listFiles()) {
        if (file.isDirectory())
            continue
        if (file.getName().toLowerCase().startsWith("index.")) {
            val index = INDEX_TYPES.indexOf(getFileType(file))
            if (index >= 0 && index < priority) {
                indexFile = file
                priority = index
            }
        }
    }
    return indexFile
}

private fun listFolderFiles(folder: File, type: String, documentRoot: String): Response {
    val html = type == "text/html"
    val files = mutableListOf<Map<String,Any?>>()
    for (file in sortFiles(folder.listFiles())) {
        files.add(mapOf<String,Any?>(
            "name" to if (html) getLink(file, documentRoot) else file.getName(),
            "size" to file.length(),
            "date" to java.sql.Timestamp(file.lastModified()),
            "type" to getFileType(file),
            "permissions" to getFilePermissions(file)
        ))
    }
    val value = if (files.isEmpty()) "Directory is empty" else files
    return sendResponse(printData(value, type), type)
}

private fun sendFile(file: File, request: HttpRequest): Response {
    val type = getFileType(file)
    if (type == URIEL_SCRIPT) {
        val result = Expression(file.readText()).execute()
        if (result is Response)
            return result
        return sendResponse(result, selectMimetype(request, QUERY_RESPONSE_TYPES))
    }
    return sendResponse(FileInputStream(file), type)
}

private fun selectMimetype(request: HttpRequest, serverTypes: List<String>): String {
    val clientTypes = request.getHttpHeaders().getAcceptableMediaTypes()
    if (serverTypes.size > 1) {
        for (type in clientTypes) {
            if (serverTypes.contains(type.toString()))
                return type.toString()
        }
    }
    return serverTypes[0]
}

private fun sortFiles(files: Array<File>): Array<File> {
    files.sortWith(Comparator<File>{ f1, f2 ->
        f1.getName().toLowerCase().compareTo(f2.getName().toLowerCase())
    })
    return files
}

private fun getFileType(file: File): String {
    if (file.isDirectory())
        return "inode/directory"
    val filename = file.getName()
    val index = filename.lastIndexOf('.')
    val ext = if (index < 1) "txt" else filename.substring(index+1).toLowerCase()
    val type = EXTENSIONS_MAP[ext]
    return type ?: "application/octet-stream"
}

private fun getFilePermissions(file: File): String {
    return arrayOf(
        if (file.canRead()) "r" else "-",
        if (file.canWrite()) "w" else "-",
        if (file.canExecute()) "x" else "-"
    ).joinToString("")
}

private fun getLink(file: File, documentRoot: String): String {
    val link = encodeUriParts(file.toString().substring(documentRoot.length))
    return "<a href='"+link+"'>"+file.getName()+"</a>"
}

private fun encodeUriParts(uri: String): String {
    val parts = uri.split("/").toTypedArray()
    for (i in 0..parts.size-1)
        parts[i] = URLEncoder.encode(parts[i], java.nio.charset.StandardCharsets.UTF_8)
    return parts.joinToString("/")
}

private fun shutdownApp(parts: List<String>) {
    LOGGER.info("Shutting down the service ...")
    System.exit(0)
}

private val LOGGER = Logger.getLogger("Controller")
private val INDEX_TYPES = listOf<String>("text/html", "application/xhtml+xml", "text/plain")
private val FOLDER_LISTING_TYPES = listOf("text/html", "application/json", "text/yaml", "text/csv", "text/plain")
private val QUERY_RESPONSE_TYPES = listOf("application/json", "text/yaml", "text/csv", "text/html")
private val URIEL_SCRIPT = "text/x-uriel"
private val EXTENSIONS_MAP = mapOf(
    $(extensions)
)
