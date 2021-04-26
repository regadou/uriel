package com.magicreg.uriel

import java.util.logging.Logger

class Schema(private val sourceFile: String, private val packageName: String) {

    private var definitions: Map<String,TypeDefinition>? = null
    private val imports = mutableSetOf(
        "java.io.*",
        "java.net.*",
        "java.sql.*",
        "javax.persistence.*",
    )
    
    fun getCode(): String {
        if (definitions == null)
            definitions = generateDefinitions(sourceFile)
        val builder = StringBuilder("package ")
                        .append(packageName)
                        .append("\n\n")
        for (path in imports)
            builder.append("import ").append(path).append("\n")
        builder.append("\n")
        val values = definitions?.values ?: listOf<TypeDefinition>()
        for (entity in values)
            builder.append(entity.getCode()).append("\n")
        return builder.toString()
    }

    override fun toString(): String {
        return "(Schema $sourceFile)"
    }
}

private class TypeDefinition(val name: String, private val definition: Map<String,Any?>, private val types: Array<String>) {
    
    fun getCode(): String {
        val type = definition["type"]
        if (type != null && type != "object")
            throw RuntimeException("Expected entity type to be object but found $type")
        val properties = validateProperties(definition["properties"])
        if (properties == null)
            throw RuntimeException("Invalid properties definition: $properties")
        val required = getRequiredProperties(definition, properties.keys.iterator().next())
        val primaryKey = required[0]

        val header = arrayOf(
            "@Entity @Table(name=\""+toSqlCase(name)+"\")",
            "data class "+toClassCase(name)+"("
        ).joinToString("\n")+"\n"
        val lines = mutableListOf<String>()
        for (key in properties.keys)
            lines.add(INDENT+createProperty(name, key, properties[key]!!, key == primaryKey, types))
        lines.add("): Serializable { }")
        return header+lines.joinToString(",\n")+"\n\n"
    }

    override fun toString(): String {
        return "(TypeDefinition $name)"
    }
}

private fun generateDefinitions(src: String): Map<String,TypeDefinition> {
    val map = mutableMapOf<String,TypeDefinition>()
    val data = loadSchemaFile(src)
    val keys = data.keys.toTypedArray()
    for (key in keys) {
        if (TYPES_MAPPING[key.toLowerCase()] != null)
            throw RuntimeException("Illegal type name '$key' that is reserved for java types")
        map[key] = TypeDefinition(key, data[key]!!, keys)
    }
    return map
}

private fun loadSchemaFile(src: String): Map<String,Map<String,Any?>> {
    val resource = Resource(src)
    if (resource.uri == null)
        throw RuntimeException("Cannot read schema from "+src)
    val data = resource.getData()
    if (data == null)
        throw RuntimeException("Schema data is null")
    if (!(data is Map<*,*>))
        throw RuntimeException("Schema data is not a map: "+data::class.qualifiedName)
    return data as Map<String,Map<String,Any?>>
}

private fun validateProperties(properties: Any?): Map<String,Map<String,Any?>>? {
    if (properties == null)
        return null
    if (properties is Map<*,*>) {
        if (properties.isNotEmpty())
            return properties as Map<String,Map<String,Any?>>
    }
    return null
}

private fun getRequiredProperties(definition: Map<String,Any?>, defaultValue: String): List<String> {
    val required = definition.get("required")
    if (required == null)
        return listOf(defaultValue)
    if (required is List<*>)
        return if (required.isEmpty()) listOf(defaultValue) else required as List<String>
    return listOf(required.toString())
}

private fun createProperty(entity: String, name: String, definition: Map<String,Any?>, primary: Boolean, types: Array<String>): String {
    val relation = mutableListOf<String>()
    val type = getType(definition, types, relation)
    if (type == null)
        throw RuntimeException("Invalid type specification for property $name of entity $entity")
    val parts = mutableListOf<String>()
    if (primary)
        parts.add("@Id")
    if (relation.isNotEmpty()) {
        for (part in relation)
            parts.add(part)
    }
    else {
        val maxLength = definition["maxLength"]?.toString()?.toIntOrNull()
        if (maxLength != null)
            parts.add("@Column(name=\""+toSqlCase(name)+"\", length="+maxLength+")")
        else
            parts.add("@Column(name=\""+toSqlCase(name)+"\")")
    }
    val klass = toClassCase(type)
    parts.add("var "+toPropertyCase(name)+": "+klass+" = "+getDefaultValue(klass!!))
    return parts.joinToString(" ")
}

private fun getType(definition: Map<String,Any?>, types: Array<String>, relation: MutableList<String>): String? {
    val value: String? = definition["type"]?.toString() ?: definition["\$ref"]?.toString()
    if (value == null)
        return logTypeError("Missing type key and \$ref key")
    val parts = value.split("/")
    val type = parts[parts.size-1].replace("#", "")
    val isEntity = types.contains(type)
    var javaName = TYPES_MAPPING[type]?.simpleName ?: if (isEntity) type else null
    if (javaName == null)
        return logTypeError("Cannot find java type for $type")
    if (javaName == "String") {
        val format = TYPES_MAPPING[definition["format"]?.toString()]?.simpleName
        return format ?: javaName
    }
    if (javaName == "List" || javaName == "Set") {
        val item = definition["items"]?.toString()
        if (item == null)
            return logTypeError("Missing items key for type $javaName")
        val itemType = TYPES_MAPPING[item]?.simpleName ?: if (types.contains(item)) toClassCase(item) else null
        if (itemType == null)
            return logTypeError("Cannot find java type for item type $item")
        javaName += "<$itemType>"
    }
    val relationType = definition["relation"]?.toString() ?: if (isEntity) "many-one" else DEFAULT_RELATION[javaName.split("<")[0]]
    if (relationType == null) //TODO: check for other constraints like min and max values or ...
        return javaName
    when (relationType) {
        "one-one" -> relation.add("@OneToOne")
        "one-many" -> relation.add("@OneToMany")
        "many-one" -> relation.add("@ManyToOne")
        "many-many" -> relation.add("@ManyToMany")
        else -> return logTypeError("Invalid relation value: $relationType")
    }
    return javaName
}

private fun logTypeError(message: String): String? {
    LOGGER.severe(message)
    return null
}

private fun getDefaultValue(type: String): String {
    return when(type.split('<')[0]) {
        "Float", "Double", "Number" -> "0.0"
        "Long" -> "0L"
        "Int", "Short", "Byte" -> "0"
        "Boolean" -> "false"
        "String" -> "\"\""
        "Date" -> "Date(0)"
        "Time" -> "Time(0)"
        "Timestamp" -> "Timestamp(0)"
        "File" -> "File(\"\")"
        "URI" -> "URI(\"http://localhost\")"
        "URL" -> "URL(\"http://localhost\")"
        "Map" -> "mapOf()"
        "List" -> "listOf()"
        "Set" -> "setOf()"
        else -> "$type()"
    }
}

private val LOGGER = Logger.getLogger("Schema")
private const val INDENT = "  "
private val TYPES_MAPPING = mapOf(
    "number" to Number::class,
    "double" to Double::class,
    "float" to Float::class,
    "long" to Long::class,
    "integer" to Integer::class,
    "int" to Integer::class,
    "short" to Short::class,
    "byte" to Byte::class,
    "boolean" to Boolean::class,
    "string" to String::class,
    "char" to String::class,
    "varchar" to String::class,
    "text" to String::class,
    "date" to java.sql.Date::class,
    "time" to java.sql.Time::class,
    "date-time" to java.sql.Timestamp::class,
    "datetime" to java.sql.Timestamp::class,
    "timestamp" to java.sql.Timestamp::class,
    "file" to java.io.File::class,
    "uri" to java.net.URI::class,
    "url" to java.net.URL::class,
    "object" to Map::class,
    "map" to Map::class,
    "entity" to Map::class,
    "array" to List::class,
    "collection" to List::class,
    "list" to List::class,
    "set" to Set::class
)
private val DEFAULT_RELATION = mapOf(
    "Map" to "many-one",
    "List" to "one-many",
    "Set" to "one-many"
)
