package com.magicreg.uriel

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.util.Properties
import java.util.StringTokenizer
import java.util.logging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element

class Service() {

    private val urielProperties = Properties()
    private val quarkusProperties = initQuarkusProperties()
    val name: String
        get() { return getProperty("name") ?: hashCode().toString() }

    constructor(properties: Map<Any?,Any?>): this() {
        for (key in properties.keys) {
            if (key == null)
                continue
            val value = properties[key]
            if (value != null)
                setProperty(key.toString(), value.toString())
        }
    }

    fun getProperty(name: String): String? {
        return urielProperties.getProperty(name) ?: quarkusProperties.getProperty(name)
    }
    
    fun setProperty(name: String, value: String) {
        if (name.startsWith("quarkus.")) {
            quarkusProperties.setProperty(name, value)
            return
        }
        if (name == "database") {
            if (!value.startsWith("jdbc:"))
                throw RuntimeException("Invalid database url $value")
            val vendor = value.split(":")[1]
            quarkusProperties.setProperty("quarkus.datasource.jdbc.url", value)
            if (JDBC_VENDORS.contains(vendor))
                quarkusProperties.setProperty("quarkus.datasource.db-kind", vendor)
            else {
                quarkusProperties.setProperty("quarkus.datasource.db-kind", "other")
                val driver = JDBC_DRIVERS.get(vendor)
                if (driver != null)
                    quarkusProperties.setProperty("quarkus.datasource.jdbc.driver", driver)
            }
        }
        urielProperties.setProperty(name, value)
    }

    fun validate(): List<String> {
        val missing = mutableListOf<String>()
        for (property in MANDATORY_PROPERTIES) {
            val value = urielProperties.getProperty(property)
            if (value == null || value.trim().isEmpty())
                missing.add(property)
        }
        return missing
    }

    fun delete() {
        LOGGER.info("Deleting the service ...")
        val serviceName = urielProperties.getProperty("name")
        if (serviceName == null)
            LOGGER.info("Service name has not been configured")
        else {
            val file = File(serviceName)
            if (!file.exists())
                LOGGER.info("Service $serviceName has not been created")
            else if (!file.isDirectory)
                LOGGER.info("File $serviceName is not a folder")
            else if (Resource("./$serviceName").delete())
                LOGGER.info("Service $serviceName has been successfully deleted")
            else
                LOGGER.info("Service $serviceName could not be deleted")
        }
    }

    fun create() {
        LOGGER.info("Starting creation of the service ...")
        val missing = validate()
        if (missing.isNotEmpty())
            throw RuntimeException("Missing or empty mandatory properties: "+missing.joinToString(" "))

        val operations = assertValidOperations(urielProperties)
        val schemaFile = urielProperties.getProperty("schema")
        val groupId = urielProperties.getProperty("group")
        val serviceName = urielProperties.getProperty("name")
        val packageName = firstNonEmpty(urielProperties.getProperty("package"), "$groupId.$serviceName")
        val currentFolder = System.getProperty("user.dir")
        val projectFolder = "$currentFolder/$serviceName"
        val packageFolder = projectFolder+"/src/main/kotlin/"+packageName.split(".").joinToString("/")

        LOGGER.info("Loading schema file $schemaFile ...")
        val schema = Schema(schemaFile, packageName).getCode()

        LOGGER.info("Creating project folder $projectFolder ...")
        createService(groupId, serviceName, packageName)
        Resource("$projectFolder/src/test/kotlin").delete()
        Resource("$projectFolder/src/main/resources/META-INF").delete()
        Resource("$packageFolder/resteasyjackson").delete()
        Resource("$packageFolder/schema.kt").putData(schema)

        quarkusProperties.setProperty("uriel.document.root", firstNonEmpty(urielProperties.getProperty("document.root"), "./"))
        quarkusProperties.setProperty("uriel.shutdown.path", urielProperties.getProperty("shutdown.path") ?: "")
        for (op in Operation.values())
            quarkusProperties.setProperty("uriel.allow."+op.name.toLowerCase(), operations.contains(op).toString())
        Resource("$projectFolder/src/main/resources/application.properties").putData(quarkusProperties)

        LOGGER.info("Copying kotlin source files ...")
        val dataPath = urielProperties.getProperty("data.path")
        for (filename in KOTLIN_RESOURCES)
            copyResource(filename, packageFolder, packageName, dataPath)

        LOGGER.info("Service $serviceName has been successfully created")
    }

    fun build() {
        runScript("build", true)
    }

    fun run() {
        runScript("run", false)
    }

    fun deploy() {
        throw RuntimeException("Service deploy is not implemented yet")
    }

    override fun toString(): String {
        return "service $name"
    }

    private fun createService(groupId: String, serviceName: String, packageName: String) {
        val runtime = Runtime.getRuntime()
        val version = urielProperties.getProperty("version")
        val extensions = arrayOf(
            "io.quarkus:quarkus-arc",
            "io.quarkus:quarkus-resteasy",
            "io.quarkus:quarkus-resteasy-jackson",
            "io.quarkus:quarkus-jackson",
            "io.quarkus:quarkus-hibernate-orm",
            "io.quarkus:quarkus-jdbc-"+quarkusProperties.getProperty("quarkus.datasource.db-kind"),
            "io.quarkus:quarkus-kotlin",
            "io.quarkus:quarkus-smallrye-openapi"
        )
        val command = arrayOf(
            "mvn",
            "io.quarkus:quarkus-maven-plugin:1.12.2.Final:create",
            "-DprojectGroupId=$groupId",
            "-DprojectArtifactId=$serviceName",
            "-DprojectVersion=$version",
            "-Dextensions="+extensions.joinToString(","),
            "-DclassName=$packageName.controller"
        )
        val status = runtime.exec(command, null, null).waitFor()
        if (status != 0)
            throw RuntimeException("Project $serviceName creation terminated with status $status")

        val servicePath = "./$serviceName"
        val pom = Resource("$servicePath/pom.xml")
        val doc = pom.getData() as Document
        val nodes = doc.getElementsByTagName("project").item(0).childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) {
                if (node.tagName.toLowerCase() == "dependencies") {
                    for (dependency in POM_DEPENDENCIES)
                        node.appendChild(createDependency(doc, dependency))
                }
            }
        }
        pom.putData(doc)

        Resource("$servicePath/build.sh").putData(BUILD_SCRIPT)
        runtime.exec("chmod 755 $servicePath/build.sh", null, null).waitFor()
        val debugPort = firstNonEmpty(urielProperties.getProperty("debug"), DEFAULT_DEBUG_PORT)
        val runScript = RUN_SCRIPT.replace("$(app)", "$serviceName-$version")
                                  .replace("$(port)", debugPort)
        Resource("$servicePath/run.sh").putData(runScript)
        runtime.exec("chmod 755 $servicePath/run.sh", null, null).waitFor()
    }

    private fun runScript(scriptName: String, waitfor: Boolean) {
        LOGGER.info("Executing the $scriptName service script ...")
        val serviceName = urielProperties.getProperty("name")
        if (serviceName == null)
            LOGGER.info("Service name has not been configured")
        val script = File.createTempFile("uriel-$scriptName-", ".sh")
        Resource(script).putData("cd $serviceName\n./$scriptName.sh")
        val process = Runtime.getRuntime().exec("sh $script", null, null)
        if (waitfor) {
            val status = process.waitFor()
            if (status != 0)
                throw RuntimeException("Service $serviceName $scriptName terminated with status $status")
            else
                LOGGER.info("Service $serviceName $scriptName has been successful")
        }
        else
            LOGGER.info("Service $serviceName $scriptName running in the background ...")
    }
}

private enum class Operation {
    SQL, JPQL, URIEL, TEXT, SELECT, INSERT, UPDATE, DELETE
}

private val LOGGER = Logger.getLogger("Service")
private val MANDATORY_PROPERTIES = "group,name,version,schema,database,data.path,operations".split(",")
private val JDBC_VENDORS = "db2,derby,h2,mariadb,mssql,mysql,postgresql".split(",")
private val JDBC_DRIVERS = mapOf(
    "derby"      to "org.apache.derby.jdbc.EmbeddedDriver",
    "h2"         to "org.h2.Driver",
    "hsqldb"     to "org.hsqldb.jdbcDriver",
    "mysql"      to "com.mysql.jdbc.Driver",
    "oracle"     to "oracle.jdbc.driver.OracleDriver",
    "postgresql" to "org.postgresql.Driver",
    "access"     to "org.regadou.jmdb.MDBDriver",
    "sqlserver"  to "com.microsoft.sqlserver.jdbc.SQLServerDriver",
    "sqlite"     to "org.sqlite.JDBC"
)
private val KOTLIN_RESOURCES = "controller,converter,entity,expression,function,resource,utils".split(",")
private val DEPENDENCY_NODES = "groupId,artifactId,version".split(",")
private val POM_DEPENDENCIES = arrayOf(
    "com.fasterxml.jackson.dataformat,jackson-dataformat-yaml,2.11.1",
    "commons-beanutils,commons-beanutils,1.9.1",
    "org.apache.commons,commons-csv,1.5",
    "org.jsoup,jsoup,1.11.3",
    "eu.maxschuster,dataurl,2.0.0"
)
private const val BUILD_SCRIPT = "#!/bin/sh\n./mvnw package $@\n"
private const val RUN_SCRIPT = """#!/bin/bash
FOLDER=$(dirname $(readlink -f "$0"))
if [ "${'$'}1" = "debug" ]; then
    DEBUG="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=$(port)"
fi
if [ -f ${'$'}FOLDER/target/$(app)-runner ]; then
    ${'$'}FOLDER/target/$(app)-runner $@
elif [ -f ${'$'}FOLDER/target/quarkus-app/quarkus-run.jar ]; then
    java ${'$'}DEBUG -jar ${'$'}FOLDER/target/quarkus-app/quarkus-run.jar $@
else
    echo "Application is not compiled or you are in the wrong directory"
fi
"""
private const val DEFAULT_DEBUG_PORT = "7777"

private fun initQuarkusProperties(): Properties {
    val properties = Properties()
    properties.load(StringReader(getInternalResource("/application.properties")))
    properties.setProperty("quarkus.hibernate-orm.database.generation", "update")
    properties.setProperty("quarkus.log.console.enable", "true")
    return properties
}

private fun firstNonEmpty(vararg values: String?): String {
    for (value in values) {
        if (value == null)
            continue
        val trimmed = value.trim()
        if (trimmed.isNotEmpty())
            return trimmed
    }
    return ""
}

private fun assertValidOperations(properties: Properties): List<Operation> {
    val invalids = mutableSetOf<String>()
    val operations = properties.getProperty("operations")!!.toString().trim().split(",")
                                .map { getOperation(it, invalids) }
                                .filterNotNull()
                                .map { it!! }
    if (invalids.isNotEmpty())
        throw RuntimeException("Invalid operations: $invalids")
    return operations
}

private fun getOperation(name: String, invalids: MutableCollection<String>? = null): Operation? {
    try { return Operation.valueOf(name.toUpperCase()) }
    catch (e: Throwable) {
        if (invalids != null)
            invalids.add(name)
        return  null
    }
}

private fun createDependency(doc: Document, dependency: String): Element {
    val parts = dependency.split(",")
    val dep = doc.createElement("dependency")
    for (d in DEPENDENCY_NODES.indices) {
        val node = doc.createElement(DEPENDENCY_NODES[d])
        node.appendChild(doc.createTextNode(parts[d]))
        dep.appendChild(node)
    }
    return dep
}

private fun createExtensionsMap(): String {
    val map = mutableMapOf<String,String>()
    val reader = BufferedReader(InputStreamReader(object {}.javaClass.getResourceAsStream("/mimetypes.txt")))
    reader.lines().forEach { line ->
        val escape = line.indexOf('#')
        val txt = if (escape >= 0) line.substring(0, escape) else line
        var mimetype: String? = null
        val tokens = StringTokenizer(txt)
        while (tokens.hasMoreElements()) {
            val token = tokens.nextElement().toString()
            if (mimetype == null) {
                mimetype = token
                continue
            }
            val old = map[token]
            if (old == null || mimetype.indexOf("/x-") < 0 || old.indexOf("/x-") > 0)
                map[token] = mimetype
        }
    }
    reader.close()
    val lines = mutableListOf<String>()
    for (key in map.keys)
        lines.add("\""+key+"\" to \""+ map[key] +"\"")
    lines.sort()
    return lines.joinToString(",\n    ")
}

private fun copyResource(filename: String, packageFolder: String, packageName: String, dataPath: String) {
    val file = "/$filename.kt"
    var txt = getInternalResource(file).replace("com.magicreg.uriel", packageName)
    if (filename == "controller")
        txt = txt.replace("$(path)", dataPath)
                 .replace("$(extensions)", createExtensionsMap())
    Resource(packageFolder+file).putData(txt)
}
