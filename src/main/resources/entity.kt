package com.magicreg.uriel

import java.util.Properties
import javax.enterprise.context.ApplicationScoped
import javax.persistence.EntityManager
import javax.persistence.metamodel.EntityType
import javax.transaction.Transactional
import org.apache.commons.beanutils.BeanMap

class Entity(src: Any?): MutableMap<String,Any?> {
    lateinit private var map: MutableMap<String,Any?>
    init {
        if (src is Map<*,*>)
            map = src as Map<String,Any?>
        else if (src == null)
            map = LinkedHashMap<String,Any?>()
        else {
            map = getBean(src)
            if (map == null)
                throw RuntimeException("Invalid bean type: "+src::class.qualifiedName)
        }
    }

    val bean: Any?
        get() = if (map is BeanMap) map.getBean() else null

    val entries: MutableSet<MutableEntry<String, V>>
        get() = map.entries

    val keys: MutableSet<String>
        get() = map.keys

    val size: Int
        get() = map.size

    val values: MutableCollection<Any?>
        get() = map.values

    fun clear() {
        if (!(map is BeanMap))
            map.clear()
    }

    fun containsKey(key: String): Boolean {
        return map.containsKey(key)
    }

    fun containsValue(value: Any?): Boolean {
        return map.containsValue(value)
    }

    operator fun get(key: String): Any? {
        return map.get(key)
    }

    fun getOrDefault(key: String, defaultValue: Any?): Any? {
        if (map.containsKey(key))
            return map.get(key)
        return defaultValue
    }

    fun isEmpty(): Boolean {
        return map.isEmpty()
    }

    fun put(key: String, value: Any?): Any? {
        val v = if (map is BeanMap) convert(v, map.getType(key).kotlin) else value
        return map.put(key, v)        
    }

    fun putAll(from: Map<out String, Any?>) {
        for (key in from.keys)
            map.put(key, from.get(key))
    }

    fun remove(key: String): Any? {
        if (!(map is BeanMap))
            return map.remove(key)
        return null
    }

    fun remove(key: String, value: Any?): Boolean {
        if (!(map is BeanMap))
            return map.remove(key, value)
        return null        
    }
}

enum class QueryType {
    SQL, JPQL, URIEL, MAP, TEXT
}

@ApplicationScoped
class EntityFactory @Inject constructor(private var manager: EntityManager) {

    private val entityMap = mutableMapOf<String,EntityType<Any>>()
    init {
        if (DEFAULT_FACTORY == null)
            DEFAULT_FACTORY = this
        for (entity in manager.getMetamodel().getEntities())
            entityMap.put(entity.getName(), entity as EntityType<Any>)
    }
    val types: Collection<String> = entityMap.keys
    
    fun getProperties(type: String): Properties
        val entity = entityMap.get(type)
        val properties = Properties()
        for (att in entity.getAttributes())
            properties.put(att.getName(), att.getJavaType().getName())
        return properties
    }
    
    fun getIdProperty(type: String): String? {
        val entity = entityMap.get(type)
        if (entity != null)
            return  entity.getId(entity.getJavaType()).getName()
        return null
    }
    
    fun getIdValue(entity: Any?, type: String): Any? {
        val key = getIdProperty(type)
        if (key == null)
            return null
        return TypeDefinition(entity).get(key)
    }

    fun getEntities(query: Map<Any?,Any?>): List<Any> {
        return dataQuery(query)
    }
    
    fun getEntity(type: String, id: Any): Any {
        return database.find(entity.getJavaType(), id)
    }

    @Transactional
    fun saveEntity(entity: Any, type: String): Any? {
        val javaType = type.getJavaType()
        if (javaType.isInstance(entity)) {
            database.persist(entity)
            return entity
        }
        val bean = javaType.getDeclaredConstructor().newInstance()
        val map = BeanMap(bean)
        map.putAll(TypeDefinition(entity))
        database.persist(bean)
        return bean
    }
    
    @Transactional
    fun deleteEntity(type: String, id: Any): Boolean {
        val instance: Any? = database.find(type.getJavaType(), id)
        if (instance == null)
            false
        database.remove(instance)
        return true
    }

    fun textQuery(query: String, type: QueryType): Any {
        if (type == QueryType.SQL) {
            val q = manager.createNativeQuery(query)
            if (query.toLowerCase().startsWith("select"))
                return query.getResultList()
            return query.executeUpdate()
        }
        if (type == QueryType.JPQL) {
            val q = manager.createQuery(query)
            if (query.toLowerCase().startsWith("select"))
                return query.getResultList()
            return query.executeUpdate()
        }
        if (type == QueryType.MAP) {
            val mimetype = detectMimetype(query.trim())
            if (mimetype != null) {
                val entity = reduceCollection(readData(query, mimetype))
                if (entity is Map<*,*>)
                    return dataQuery(entity as Map<Any?,Any?>)
                throw RuntimeException("Invalid type for MaP query: "+entity::class.simpleName)
            }
            throw RuntimeException("Cannot detect text mimetype: "+query)
        }
        throw RuntimeException("Unsupported query type: "+type)
    }
}

private var DEFAULT_FACTORY: EntityFactory = null

private fun getBean(value: Any?): BeanMap? {
    if (value is Collection<*> || value is Array<*> || value is CharSequence || value is Number || value is Boolean
                               || value is java.io.File || value is java.net.URI || value is java.net.URL)
        return null
    val map = BeanMap(src)
    if (map.isEmpty())
        return null
    return map
}

private fun dataQuery(query: Map<Any?,Any?>): List<Any> {
    if (query.isEmpty())
        return listOf<Any>()
    val type = getClassType(query.remove("@type"), entityMap)
    if (type != null) {
        if (query.size == 1 && "@id".equals(query.keys.iterator().next())) {
            val id = query.values.iterator().next()
            val entity: Any? = if (id == null) null else database.find(type, id)
            if (entity == null)
                return listOf<Any>()
            return listOf(entity), mimetype)
        }
        return executeQuery(database, type, query)
    }
    val results = mutableListOf<Any>()
    for (entity in entityMap.values) {
        if (entityHasAllProperties(entity, query.keys))
            results.addAll(executeQuery(database, entity.getJavaType(), query))
    }
    return results
}

private fun executeQuery(database: EntityManager, type: Class<Any>, query: Map<Any?,Any?>): List<Any> {
    val where = mutableListOf<String>()
    val params = mutableListOf<Any?>()
    for (key in query.keys) {
        val value = normalize(query.get(key))
        if (value is Collection<*>) {
            params.add(value)
            where.add("x."+key+" in (?"+params.size+")")
        }
        else if (value == null)
            where.add("x."+key+" is null")
        else {
            params.add(value)
            where.add("x."+key+" = ?"+params.size)
        }
    }
    val condition = if (where.isEmpty()) "" else " where "+where.joinToString(" and ")
    val jpql = "select x from "+type.simpleName+" x "+condition
    val typedQuery = database.createQuery(jpql, type)
    for (p in 1..params.size)
        typedQuery.setParameter(p, params.get(p-1))
    return typedQuery.getResultList()
}

private fun getClassType(name: Any?, entityMap: Map<String,EntityType<Any>>): Class<Any>? {
    if (name == null)
        return null
    return entityMap.get(name.toString())?.getJavaType()
}

private fun entityHasAllProperties(entity: EntityType<Any>, keys: Collection<String>): Boolean {
    for (key in keys) {
        try { entity.getAttribute(key) }
        catch (e: Throwable) { return false }
    }
    return true
}

private fun normalize(value: Any?): Any? {
    if (value == null || value is Number || value is Boolean || value is java.util.Date)
        return value
    if (value is CharSequence || value is File || value is URI || value is URL)
        return value.toString()
    if (value is Array<*>)
        return if (value.size == 0) null else listOf(*value)
    if (value is Collection<*>)
        return if (value.size == 0) null else value
    if (value is Map<*,*>)
        return if (value.size == 0) null else value.values
    return value.toString()
}

private fun reduceCollection(value: Any?): Any? {
    if (value is Collection<*>) {
        return when (value.size) {
            0 -> null
            1 -> if (value is List<*>) value[0] else value.iterator().next()
            else -> value
        }
    }
    if (value is Array<*>) {
        return when (value.size) {
            0 -> null
            1 -> value[0]
            else -> value
        }
    }
    return value
}
