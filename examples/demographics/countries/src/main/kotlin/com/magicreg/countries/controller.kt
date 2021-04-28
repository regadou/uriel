package com.magicreg.countries

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Comparator
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
import javax.ws.rs.core.Response
import kotlin.system.exitProcess
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

@Path("/data")
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
                results.add(factory.getIdValue(entity, type)!!)
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
        val entity = factory.saveEntity(setIdValue(data, idkey!!, id), type)
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

    private fun getQueryType(inputType: String, query: String? = null): QueryType? {
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
        val allowedStatement = when (StringTokenizer(query).nextToken().toLowerCase()) {
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
                else -> throw WebApplicationException(Response.Status.BAD_REQUEST)
            }
            if (!allowedStatement)
                throw WebApplicationException(Response.Status.FORBIDDEN)
        }
        return QueryType.URIEL
    }
}

private fun getMap(src: Any?): Map<Any?,Any?> {
    return when (src) {
        is Collection<*> -> mergeCollection(src as Collection<Any?>, mutableMapOf<Any?,Any?>())
        is Array<*> -> mergeCollection(listOf<Any?>(*src), mutableMapOf<Any?,Any?>())
        is Map<*,*> -> src as Map<Any?,Any?>
        else -> throw WebApplicationException(Response.Status.BAD_REQUEST)
    }
}

private fun mergeCollection(src: Collection<Any?>, map: MutableMap<Any?,Any?>): MutableMap<Any?,Any?> {
    for (item in src)
        map.putAll(getMap(item))
    return map
}

private fun setIdValue(src: Map<String,Any?>, key: String, value: Any?): Map<String,Any?> {
    if (src[key] == value)
        return src
    if (src is MutableMap<String,Any?>) {
        src[key] = value
        return src
    }
    val map = mutableMapOf<String,Any?>()
    map.putAll(src)
    map[key] = value
    return map
}

private fun getContentType(request: HttpRequest): String {
    return request.getHttpHeaders().getMediaType().toString().split(";")[0]
}

private fun sendResponse(data: Any?, type: String): Response {
    if (data is InputStream)
        return Response.ok(data, type).build()
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
        if (file.isDirectory)
            continue
        if (file.name.toLowerCase().startsWith("index.")) {
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
            "name" to if (html) getLink(file, documentRoot) else file.name,
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
        Resource(null).putData(null)
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
        f1.name.toLowerCase().compareTo(f2.name.toLowerCase())
    })
    return files
}

private fun getFileType(file: File): String {
    if (file.isDirectory)
        return "inode/directory"
    val filename = file.name
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
    exitProcess(0)
}

private val LOGGER = Logger.getLogger("Controller")
private val INDEX_TYPES = listOf<String>("text/html", "application/xhtml+xml", "text/plain")
private val FOLDER_LISTING_TYPES = listOf("text/html", "application/json", "text/yaml", "text/csv", "text/plain")
private val QUERY_RESPONSE_TYPES = listOf("application/json", "text/yaml", "text/csv", "text/html")
private val URIEL_SCRIPT = "text/x-uriel"
private val EXTENSIONS_MAP = mapOf(
    "323" to "text/h323",
    "3ds" to "application/x-3ds",
    "3gp" to "video/3gpp",
    "7z" to "application/x-7z-compressed",
    "abw" to "application/x-abiword",
    "ai" to "application/postscript",
    "aif" to "audio/x-aiff",
    "aifc" to "audio/x-aiff",
    "aiff" to "audio/x-aiff",
    "alc" to "chemical/x-alchemy",
    "amr" to "audio/amr",
    "anx" to "application/annodex",
    "apk" to "application/vnd.android.package-archive",
    "art" to "image/x-jg",
    "asc" to "text/plain",
    "asf" to "video/x-ms-asf",
    "asn" to "chemical/x-ncbi-asn1-spec",
    "aso" to "chemical/x-ncbi-asn1-binary",
    "asx" to "video/x-ms-asf",
    "atom" to "application/atom+xml",
    "atomcat" to "application/atomcat+xml",
    "atomsrv" to "application/atomserv+xml",
    "au" to "audio/basic",
    "avi" to "video/x-msvideo",
    "awb" to "audio/amr-wb",
    "axa" to "audio/annodex",
    "axv" to "video/annodex",
    "b" to "chemical/x-molconn-Z",
    "bak" to "application/x-trash",
    "bat" to "application/x-msdos-program",
    "bcpio" to "application/x-bcpio",
    "bib" to "text/x-bibtex",
    "bin" to "application/octet-stream",
    "bmp" to "image/x-ms-bmp",
    "boo" to "text/x-boo",
    "book" to "application/x-maker",
    "brf" to "text/plain",
    "bsd" to "chemical/x-crossfire",
    "bvh" to "model/x-bvh",
    "c" to "text/x-csrc",
    "c++" to "text/x-c++src",
    "c3d" to "chemical/x-chem3d",
    "cab" to "application/x-cab",
    "cac" to "chemical/x-cache",
    "cache" to "chemical/x-cache",
    "cap" to "application/cap",
    "cascii" to "chemical/x-cactvs-binary",
    "cat" to "application/vnd.ms-pki.seccat",
    "cbin" to "chemical/x-cactvs-binary",
    "cbr" to "application/x-cbr",
    "cbz" to "application/x-cbz",
    "cc" to "text/x-c++src",
    "cda" to "application/x-cdf",
    "cdf" to "application/x-cdf",
    "cdr" to "image/x-coreldraw",
    "cdt" to "image/x-coreldrawtemplate",
    "cdx" to "chemical/x-cdx",
    "cdy" to "application/vnd.cinderella",
    "cef" to "chemical/x-cxf",
    "cer" to "chemical/x-cerius",
    "chm" to "chemical/x-chemdraw",
    "chrt" to "application/x-kchart",
    "cif" to "chemical/x-cif",
    "class" to "application/java-vm",
    "cls" to "text/x-tex",
    "cmdf" to "chemical/x-cmdf",
    "cml" to "chemical/x-cml",
    "cod" to "application/vnd.rim.cod",
    "com" to "application/x-msdos-program",
    "cpa" to "chemical/x-compass",
    "cpio" to "application/x-cpio",
    "cpp" to "text/x-c++src",
    "cpt" to "application/mac-compactpro",
    "cr2" to "image/x-canon-cr2",
    "crl" to "application/x-pkcs7-crl",
    "crt" to "application/x-x509-ca-cert",
    "crw" to "image/x-canon-crw",
    "csd" to "audio/csound",
    "csf" to "chemical/x-cache-csf",
    "csh" to "text/x-csh",
    "csm" to "chemical/x-csml",
    "csml" to "chemical/x-csml",
    "css" to "text/css",
    "csv" to "text/csv",
    "ctab" to "chemical/x-cactvs-binary",
    "ctx" to "chemical/x-ctx",
    "cu" to "application/cu-seeme",
    "cub" to "chemical/x-gaussian-cube",
    "cxf" to "chemical/x-cxf",
    "cxx" to "text/x-c++src",
    "d" to "inode/directory",
    "dae" to "model/vnd.collada+xml",
    "damai" to "text/damai",
    "dat" to "application/x-ns-proxy-autoconfig",
    "davmount" to "application/davmount+xml",
    "dcr" to "application/x-director",
    "deb" to "application/x-debian-package",
    "dif" to "video/dv",
    "diff" to "text/x-diff",
    "dir" to "application/x-director",
    "djv" to "image/vnd.djvu",
    "djvu" to "image/vnd.djvu",
    "dl" to "video/dl",
    "dll" to "application/x-msdos-program",
    "dmg" to "application/x-apple-diskimage",
    "dms" to "application/x-dms",
    "doc" to "application/msword",
    "docm" to "application/vnd.ms-word.document.macroEnabled.12",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "dot" to "application/msword",
    "dotm" to "application/vnd.ms-word.template.macroEnabled.12",
    "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
    "dv" to "video/dv",
    "dvi" to "application/x-dvi",
    "dx" to "chemical/x-jcamp-dx",
    "dxf" to "application/x-autocad",
    "dxr" to "application/x-director",
    "emb" to "chemical/x-embl-dl-nucleotide",
    "embl" to "chemical/x-embl-dl-nucleotide",
    "eml" to "message/rfc822",
    "ent" to "chemical/x-pdb",
    "eps" to "application/postscript",
    "eps2" to "application/postscript",
    "eps3" to "application/postscript",
    "epsf" to "application/postscript",
    "epsi" to "application/postscript",
    "erf" to "image/x-epson-erf",
    "es" to "application/ecmascript",
    "etx" to "text/x-setext",
    "exe" to "application/x-msdos-program",
    "ez" to "application/andrew-inset",
    "fb" to "application/x-maker",
    "fbdoc" to "application/x-maker",
    "fch" to "chemical/x-gaussian-checkpoint",
    "fchk" to "chemical/x-gaussian-checkpoint",
    "fig" to "application/x-xfig",
    "flac" to "audio/flac",
    "fli" to "video/fli",
    "flv" to "video/x-flv",
    "fm" to "application/x-maker",
    "frame" to "application/x-maker",
    "frm" to "application/x-maker",
    "gal" to "chemical/x-gaussian-log",
    "gam" to "chemical/x-gamess-input",
    "gamin" to "chemical/x-gamess-input",
    "gan" to "application/x-ganttproject",
    "gau" to "chemical/x-gaussian-input",
    "gcd" to "text/x-pcs-gcd",
    "gcf" to "application/x-graphing-calculator",
    "gcg" to "chemical/x-gcg8-sequence",
    "gen" to "chemical/x-genbank",
    "gf" to "application/x-tex-gf",
    "gif" to "image/gif",
    "gjc" to "chemical/x-gaussian-input",
    "gjf" to "chemical/x-gaussian-input",
    "gl" to "video/gl",
    "gltf" to "model/gltf+json",
    "gnumeric" to "application/x-gnumeric",
    "gpt" to "chemical/x-mopac-graph",
    "gsf" to "application/x-font",
    "gsm" to "audio/x-gsm",
    "gtar" to "application/x-gtar",
    "h" to "text/x-chdr",
    "h++" to "text/x-c++hdr",
    "hdf" to "application/x-hdf",
    "hh" to "text/x-c++hdr",
    "hin" to "chemical/x-hin",
    "hpp" to "text/x-c++hdr",
    "hqx" to "application/mac-binhex40",
    "hs" to "text/x-haskell",
    "hta" to "application/hta",
    "htc" to "text/x-component",
    "htm" to "text/html",
    "html" to "text/html",
    "hxx" to "text/x-c++hdr",
    "ica" to "application/x-ica",
    "ice" to "x-conference/x-cooltalk",
    "ico" to "image/x-icon",
    "ics" to "text/calendar",
    "icz" to "text/calendar",
    "ief" to "image/ief",
    "iges" to "model/iges",
    "igs" to "model/iges",
    "iii" to "application/x-iphone",
    "info" to "application/x-info",
    "inp" to "chemical/x-gamess-input",
    "ins" to "application/x-internet-signup",
    "iso" to "application/x-iso9660-image",
    "isp" to "application/x-internet-signup",
    "ist" to "chemical/x-isostar",
    "istr" to "chemical/x-isostar",
    "jad" to "text/vnd.sun.j2me.app-descriptor",
    "jam" to "application/x-jam",
    "jar" to "application/java-archive",
    "java" to "text/x-java",
    "jd" to "model/x-json3d",
    "jdx" to "chemical/x-jcamp-dx",
    "jmz" to "application/x-jmol",
    "jng" to "image/x-jng",
    "jnlp" to "application/x-java-jnlp-file",
    "jpe" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
    "js" to "application/javascript",
    "json" to "application/json",
    "jsonld" to "application/ld+json",
    "jsons" to "text/jsonscript",
    "kar" to "audio/midi",
    "key" to "application/pgp-keys",
    "kil" to "application/x-killustrator",
    "kin" to "chemical/x-kinemage",
    "kml" to "application/vnd.google-earth.kml+xml",
    "kmz" to "application/vnd.google-earth.kmz",
    "kpr" to "application/x-kpresenter",
    "kpt" to "application/x-kpresenter",
    "ksp" to "application/x-kspread",
    "kt" to "text/x-kotlin",
    "kts" to "text/x-kotlin",
    "kwd" to "application/x-kword",
    "kwt" to "application/x-kword",
    "latex" to "application/x-latex",
    "lha" to "application/x-lha",
    "lhs" to "text/x-literate-haskell",
    "lin" to "application/bbolin",
    "log" to "text/x-log",
    "lsf" to "video/x-la-asf",
    "lsx" to "video/x-la-asf",
    "ltx" to "text/x-tex",
    "lyx" to "application/x-lyx",
    "lzh" to "application/x-lzh",
    "lzx" to "application/x-lzx",
    "m3g" to "application/m3g",
    "m3u" to "audio/mpegurl",
    "m3u8" to "application/x-mpegURL",
    "m4a" to "audio/mpeg",
    "maker" to "application/x-maker",
    "man" to "application/x-troff-man",
    "manifest" to "text/cache-manifest",
    "mcif" to "chemical/x-mmcif",
    "mcm" to "chemical/x-macmolecule",
    "md" to "text/markdown",
    "mdb" to "application/msaccess",
    "me" to "application/x-troff-me",
    "mesh" to "model/mesh",
    "mid" to "audio/midi",
    "midi" to "audio/midi",
    "mif" to "application/x-mif",
    "mkv" to "video/x-matroska",
    "mm" to "application/x-freemind",
    "mmd" to "chemical/x-macromodel-input",
    "mmf" to "application/vnd.smaf",
    "mml" to "text/mathml",
    "mmod" to "chemical/x-macromodel-input",
    "mmp" to "audio/x-lmms",
    "mng" to "video/x-mng",
    "moc" to "text/x-moc",
    "mol" to "chemical/x-mdl-molfile",
    "mol2" to "chemical/x-mol2",
    "moo" to "chemical/x-mopac-out",
    "mop" to "chemical/x-mopac-input",
    "mopcrt" to "chemical/x-mopac-input",
    "mov" to "video/quicktime",
    "movie" to "video/x-sgi-movie",
    "mp2" to "audio/mpeg",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
    "mpc" to "chemical/x-mopac-input",
    "mpe" to "video/mpeg",
    "mpeg" to "video/mpeg",
    "mpega" to "audio/mpeg",
    "mpg" to "video/mpeg",
    "mpga" to "audio/mpeg",
    "mph" to "application/x-comsol",
    "mpv" to "video/x-matroska",
    "ms" to "application/x-troff-ms",
    "msh" to "model/mesh",
    "msi" to "application/x-msi",
    "mvb" to "chemical/x-mopac-vib",
    "mxf" to "application/mxf",
    "mxu" to "video/vnd.mpegurl",
    "n3" to "text/n3",
    "nb" to "application/mathematica",
    "nbp" to "application/mathematica",
    "nc" to "application/x-netcdf",
    "nef" to "image/x-nikon-nef",
    "nquads" to "text/nquads",
    "nwc" to "application/x-nwc",
    "o" to "application/x-object",
    "obj" to "model/x-obj",
    "oda" to "application/oda",
    "odb" to "application/vnd.oasis.opendocument.database",
    "odc" to "application/vnd.oasis.opendocument.chart",
    "odf" to "application/vnd.oasis.opendocument.formula",
    "odg" to "application/vnd.oasis.opendocument.graphics",
    "odi" to "application/vnd.oasis.opendocument.image",
    "odm" to "application/vnd.oasis.opendocument.text-master",
    "odp" to "application/vnd.oasis.opendocument.presentation",
    "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    "odt" to "application/vnd.oasis.opendocument.text",
    "off" to "model/x-off",
    "oga" to "audio/ogg",
    "ogg" to "audio/ogg",
    "ogv" to "video/ogg",
    "ogx" to "application/ogg",
    "old" to "application/x-trash",
    "one" to "application/onenote",
    "onepkg" to "application/onenote",
    "onetmp" to "application/onenote",
    "onetoc2" to "application/onenote",
    "orc" to "audio/csound",
    "orf" to "image/x-olympus-orf",
    "otg" to "application/vnd.oasis.opendocument.graphics-template",
    "oth" to "application/vnd.oasis.opendocument.text-web",
    "otp" to "application/vnd.oasis.opendocument.presentation-template",
    "ots" to "application/vnd.oasis.opendocument.spreadsheet-template",
    "ott" to "application/vnd.oasis.opendocument.text-template",
    "oza" to "application/x-oz-application",
    "p" to "text/x-pascal",
    "p7r" to "application/x-pkcs7-certreqresp",
    "pac" to "application/x-ns-proxy-autoconfig",
    "pas" to "text/x-pascal",
    "pat" to "image/x-coreldrawpattern",
    "patch" to "text/x-diff",
    "pbm" to "image/x-portable-bitmap",
    "pcap" to "application/cap",
    "pcf" to "application/x-font",
    "pcf.Z" to "application/x-font",
    "pcx" to "image/pcx",
    "pdb" to "chemical/x-pdb",
    "pdf" to "application/pdf",
    "pfa" to "application/x-font",
    "pfb" to "application/x-font",
    "pgm" to "image/x-portable-graymap",
    "pgn" to "application/x-chess-pgn",
    "pgp" to "application/pgp-signature",
    "php" to "application/x-httpd-php",
    "php3" to "application/x-httpd-php3",
    "php3p" to "application/x-httpd-php3-preprocessed",
    "php4" to "application/x-httpd-php4",
    "php5" to "application/x-httpd-php5",
    "phps" to "application/x-httpd-php-source",
    "pht" to "application/x-httpd-php",
    "phtml" to "application/x-httpd-php",
    "pk" to "application/x-tex-pk",
    "pl" to "text/x-perl",
    "pls" to "audio/x-scpls",
    "pm" to "text/x-perl",
    "png" to "image/png",
    "pnm" to "image/x-portable-anymap",
    "pot" to "text/plain",
    "potm" to "application/vnd.ms-powerpoint.template.macroEnabled.12",
    "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
    "ppam" to "application/vnd.ms-powerpoint.addin.macroEnabled.12",
    "ppm" to "image/x-portable-pixmap",
    "pps" to "application/vnd.ms-powerpoint",
    "ppsm" to "application/vnd.ms-powerpoint.slideshow.macroEnabled.12",
    "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "prf" to "application/pics-rules",
    "properties" to "text/x-java-properties",
    "prt" to "chemical/x-ncbi-asn1-ascii",
    "ps" to "application/postscript",
    "psd" to "image/x-photoshop",
    "py" to "text/x-python",
    "pyc" to "application/x-python-code",
    "pyo" to "application/x-python-code",
    "qgs" to "application/x-qgis",
    "qt" to "video/quicktime",
    "qtl" to "application/x-quicktimeplayer",
    "ra" to "audio/x-realaudio",
    "ram" to "audio/x-pn-realaudio",
    "rar" to "application/rar",
    "ras" to "image/x-cmu-raster",
    "rb" to "application/x-ruby",
    "rd" to "chemical/x-mdl-rdfile",
    "rdf" to "application/rdf+xml",
    "rdfb" to "application/x-binary-rdf",
    "rdfj" to "application/rdf+json",
    "rdfn" to "text/rdf+n3",
    "rdp" to "application/x-rdp",
    "rgb" to "image/x-rgb",
    "rhtml" to "application/x-httpd-eruby",
    "rm" to "audio/x-pn-realaudio",
    "roff" to "application/x-troff",
    "ros" to "chemical/x-rosdal",
    "rpm" to "application/x-redhat-package-manager",
    "rss" to "application/rss+xml",
    "rtf" to "application/rtf",
    "rtx" to "text/richtext",
    "rxn" to "chemical/x-mdl-rxnfile",
    "scala" to "text/x-scala",
    "sce" to "application/x-scilab",
    "sci" to "application/x-scilab",
    "sco" to "audio/csound",
    "scr" to "application/x-silverlight",
    "sct" to "text/scriptlet",
    "sd" to "chemical/x-mdl-sdfile",
    "sd2" to "audio/x-sd2",
    "sda" to "application/vnd.stardivision.draw",
    "sdc" to "application/vnd.stardivision.calc",
    "sdd" to "application/vnd.stardivision.impress",
    "sdf" to "application/vnd.stardivision.math",
    "sds" to "application/vnd.stardivision.chart",
    "sdw" to "application/vnd.stardivision.writer",
    "ser" to "application/java-serialized-object",
    "sf2" to "audio/x-sf2",
    "sfv" to "text/x-sfv",
    "sgf" to "application/x-go-sgf",
    "sgl" to "application/vnd.stardivision.writer-global",
    "sh" to "text/x-sh",
    "shar" to "application/x-shar",
    "shp" to "application/x-qgis",
    "shtml" to "text/html",
    "shx" to "application/x-qgis",
    "sid" to "audio/prs.sid",
    "sik" to "application/x-trash",
    "silo" to "model/mesh",
    "sis" to "application/vnd.symbian.install",
    "sisx" to "x-epoc/x-sisx-app",
    "sit" to "application/x-stuffit",
    "sitx" to "application/x-stuffit",
    "skd" to "application/x-koan",
    "skm" to "application/x-koan",
    "skp" to "application/x-koan",
    "skt" to "application/x-koan",
    "sldm" to "application/vnd.ms-powerpoint.slide.macroEnabled.12",
    "sldx" to "application/vnd.openxmlformats-officedocument.presentationml.slide",
    "smi" to "application/smil",
    "smil" to "application/smil",
    "snd" to "audio/basic",
    "spc" to "chemical/x-galactic-spc",
    "spl" to "application/futuresplash",
    "spx" to "audio/ogg",
    "sql" to "application/x-sql",
    "src" to "application/x-wais-source",
    "stc" to "application/vnd.sun.xml.calc.template",
    "std" to "application/vnd.sun.xml.draw.template",
    "sti" to "application/vnd.sun.xml.impress.template",
    "stl" to "application/sla",
    "stw" to "application/vnd.sun.xml.writer.template",
    "sty" to "text/x-tex",
    "sv4cpio" to "application/x-sv4cpio",
    "sv4crc" to "application/x-sv4crc",
    "svg" to "image/svg+xml",
    "svgz" to "image/svg+xml",
    "sw" to "chemical/x-swissprot",
    "swf" to "application/x-shockwave-flash",
    "swfl" to "application/x-shockwave-flash",
    "sxc" to "application/vnd.sun.xml.calc",
    "sxd" to "application/vnd.sun.xml.draw",
    "sxg" to "application/vnd.sun.xml.writer.global",
    "sxi" to "application/vnd.sun.xml.impress",
    "sxm" to "application/vnd.sun.xml.math",
    "sxw" to "application/vnd.sun.xml.writer",
    "t" to "application/x-troff",
    "tar" to "application/x-tar",
    "taz" to "application/x-gtar-compressed",
    "tcl" to "text/x-tcl",
    "tex" to "text/x-tex",
    "texi" to "application/x-texinfo",
    "texinfo" to "application/x-texinfo",
    "text" to "text/plain",
    "tga" to "image/x-targa",
    "tgf" to "chemical/x-mdl-tgf",
    "tgz" to "application/x-gtar-compressed",
    "thmx" to "application/vnd.ms-officetheme",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
    "tk" to "text/x-tcl",
    "tm" to "text/texmacs",
    "torrent" to "application/x-bittorrent",
    "tr" to "application/x-troff",
    "trig" to "application/trig",
    "trix" to "application/trix",
    "ts" to "video/MP2T",
    "tsp" to "application/dsptype",
    "tsv" to "text/tab-separated-values",
    "ttl" to "text/turtle",
    "txt" to "text/plain",
    "udeb" to "application/x-debian-package",
    "uls" to "text/iuls",
    "uri" to "text/uri-list",
    "uriel" to "text/x-uriel",
    "ustar" to "application/x-ustar",
    "val" to "chemical/x-ncbi-asn1-binary",
    "vcd" to "application/x-cdlink",
    "vcf" to "text/x-vcard",
    "vcs" to "text/x-vcalendar",
    "vmd" to "chemical/x-vmd",
    "vms" to "chemical/x-vamas-iso14976",
    "vrm" to "x-world/x-vrml",
    "vrml" to "model/vrml",
    "vsd" to "application/vnd.visio",
    "wad" to "application/x-doom",
    "wav" to "audio/x-wav",
    "wax" to "audio/x-ms-wax",
    "wbmp" to "image/vnd.wap.wbmp",
    "wbxml" to "application/vnd.wap.wbxml",
    "webm" to "video/webm",
    "wk" to "application/x-123",
    "wm" to "video/x-ms-wm",
    "wma" to "audio/x-ms-wma",
    "wmd" to "application/x-ms-wmd",
    "wml" to "text/vnd.wap.wml",
    "wmlc" to "application/vnd.wap.wmlc",
    "wmls" to "text/vnd.wap.wmlscript",
    "wmlsc" to "application/vnd.wap.wmlscriptc",
    "wmv" to "video/x-ms-wmv",
    "wmx" to "video/x-ms-wmx",
    "wmz" to "application/x-ms-wmz",
    "wp5" to "application/vnd.wordperfect5.1",
    "wpd" to "application/vnd.wordperfect",
    "wrl" to "model/vrml",
    "wsc" to "text/scriptlet",
    "wvx" to "video/x-ms-wvx",
    "wz" to "application/x-wingz",
    "x3d" to "model/x3d+xml",
    "x3db" to "model/x3d+binary",
    "x3dv" to "model/x3d+vrml",
    "xbm" to "image/x-xbitmap",
    "xcf" to "application/x-xcf",
    "xht" to "application/xhtml+xml",
    "xhtml" to "application/xhtml+xml",
    "xlam" to "application/vnd.ms-excel.addin.macroEnabled.12",
    "xlb" to "application/vnd.ms-excel",
    "xls" to "application/vnd.ms-excel",
    "xlsb" to "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
    "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "xlt" to "application/vnd.ms-excel",
    "xltm" to "application/vnd.ms-excel.template.macroEnabled.12",
    "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
    "xml" to "application/xml",
    "xpi" to "application/x-xpinstall",
    "xpm" to "image/x-xpixmap",
    "xsd" to "application/xml",
    "xsl" to "application/xml",
    "xspf" to "application/xspf+xml",
    "xtel" to "chemical/x-xtel",
    "xul" to "application/vnd.mozilla.xul+xml",
    "xwd" to "image/x-xwindowdump",
    "xyz" to "chemical/x-xyz",
    "yaml" to "text/yaml",
    "yml" to "text/yaml",
    "zip" to "application/zip",
    "zmt" to "chemical/x-mopac-input"
)
