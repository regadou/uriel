package com.magicreg.uriel

import java.util.Properties
import javax.enterprise.context.ApplicationScoped
import javax.inject.Inject
import javax.persistence.EntityManager
import javax.persistence.Tuple
import javax.persistence.metamodel.EntityType
import javax.transaction.Transactional
import org.apache.commons.beanutils.BeanMap

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
    
    fun getProperties(type: String): Properties {
        val entity = entityMap.get(type)
        val properties = Properties()
        if (entity != null) {
            for (att in entity.getAttributes())
                properties[att.getName()] = att.getJavaType().getSimpleName().toLowerCase()
        }
        return properties
    }
    
    fun getIdProperty(type: String): String? {
        val entity = entityMap.get(type)
        if (entity != null)
            return  entity.getId(entity.getIdType().getJavaType()).getName()
        return null
    }
    
    fun getIdValue(entity: Any?, type: String): Any? {
        val key = getIdProperty(type)
        if (key == null)
            return null
        return getMap(entity, entityMap.get(type), manager)[key]
    }

    fun getEntities(query: Map<Any?,Any?>): List<Any> {
        return dataQuery(query)
    }
    
    fun getEntity(type: String, id: Any): Any {
        return manager.find(getJavaType(type), id)
    }

    @Transactional
    fun saveEntity(entity: Any, type: String): Any? {
        val javaType = getJavaType(type)
        if (javaType.isInstance(entity)) {
            manager.persist(entity)
            return entity
        }
        val bean = javaType.getDeclaredConstructor().newInstance()
        val map = BeanMap(bean)
        map.putAll(getMap(entity, entityMap.get(type), manager))
        manager.persist(bean)
        return bean
    }
    
    @Transactional
    fun deleteEntity(type: String, id: Any): Boolean {
        val instance: Any? = manager.find(getJavaType(type), id)
        if (instance == null)
            return false
        manager.remove(instance)
        return true
    }

    fun textQuery(query: String, type: QueryType): Any {
        when (type) {
            QueryType.SQL -> {
                if (query.toLowerCase().startsWith("select")) {
                    return manager.createNativeQuery(query, Tuple::class.java)
                            .getResultList().map { getMap(it) }
                }
                return manager.createNativeQuery(query).executeUpdate()
            }
            QueryType.JPQL -> {
                val q = manager.createQuery(query)
                if (query.toLowerCase().startsWith("select"))
                    return q.getResultList()
                return q.executeUpdate()
            }
            QueryType.MAP -> {
                val mimetype = detectMimetype(query.trim())
                if (mimetype == null)
                    throw RuntimeException("Cannot detect text mimetype: $query")
                val entity = execute(Expression(null, toArray(readData(query, mimetype))))
                if (entity is Map<*, *>)
                    return dataQuery(entity as Map<Any?, Any?>)
                throw RuntimeException("Invalid type for MAP query: " + entity!!::class.simpleName)
            }
            QueryType.URIEL -> return Expression(query).execute() ?: Expression()
            QueryType.TEXT -> throw RuntimeException("TEXT query type is not implemented yet")
            else -> throw RuntimeException("Configuration problem for query type $type")
        }
    }

    private fun dataQuery(query: Map<Any?,Any?>): List<Any> {
        if (query.isEmpty())
            return listOf<Any>()
        val type = getClassType(query["@type"], entityMap)
        if (type != null) {
            if (query.size == 2 && query.containsKey("@id")) {
                val id = query["@id"]
                val entity: Any? = if (id == null) null else manager.find(type, id)
                if (entity == null)
                    return listOf<Any>()
                return listOf(entity)
            }
            return executeQuery(manager, type, query)
        }
        val results = mutableListOf<Any>()
        for (entity in entityMap.values) {
            if (entityHasAllProperties(entity, query.keys))
                results.addAll(executeQuery(manager, entity.getJavaType(), query))
        }
        return results
    }

    private fun getJavaType(type: String): Class<Any> {
        return entityMap[type]?.javaType ?: Any::class.java
    }
}

private var DEFAULT_FACTORY: EntityFactory? = null

private fun getBean(value: Any?): MutableMap<String,Any?> {
    if (value is Collection<*>) {
        val map = mutableMapOf<String,Any?>("size" to value.size)
        val it = value.iterator()
        for (i in value.indices)
            map[i.toString()] = it.next()
        return map
    }
    if (value is Array<*>) {
        val map = mutableMapOf<String,Any?>("size" to value.size)
        for (i in value.indices)
            map[i.toString()] = value[i]
        return map
    }
    if (value is CharSequence || value is Number || value is Boolean
                             || value is java.io.File || value is java.net.URI || value is java.net.URL)
        return mutableMapOf<String,Any?>("value" to value)
    val map = BeanMap(value)
    if (map.isEmpty())
        return mutableMapOf<String,Any?>("value" to value)
    return map as MutableMap<String,Any?>
}

private fun executeQuery(manager: EntityManager, type: Class<Any>, query: Map<Any?,Any?>): List<Any> {
    val where = mutableListOf<String>()
    val params = mutableListOf<Any?>()
    for (key in query.keys) {
        if (key == "@type")
            continue
        val value = normalize(query[key])
        if (value is Collection<*>) {
            params.add(value)
            where.add("x."+key+" in (?"+params.size+")")
        }
        else if (value == null)
            where.add("x.$key is null")
        else {
            params.add(value)
            where.add("x."+key+" = ?"+params.size)
        }
    }
    val condition = if (where.isEmpty()) "" else " where "+where.joinToString(" and ")
    val jpql = "select x from "+type.simpleName+" x "+condition
    val typedQuery = manager.createQuery(jpql, type)
    for (p in 1..params.size)
        typedQuery.setParameter(p, params[p-1])
    return typedQuery.getResultList()
}

private fun getClassType(name: Any?, entityMap: Map<String,EntityType<Any>>): Class<Any>? {
    if (name == null)
        return null
    return entityMap[name.toString()]?.getJavaType()
}

private fun entityHasAllProperties(entity: EntityType<Any>, keys: Collection<Any?>): Boolean {
    for (key in keys) {
        try { entity.getAttribute(key.toString()) }
        catch (e: Throwable) { return false }
    }
    return true
}

private fun normalize(value: Any?): Any? {
    if (value == null || value is Number || value is Boolean || value is java.util.Date)
        return value
    if (value is CharSequence || value is java.io.File || value is java.net.URI || value is java.net.URL)
        return value.toString()
    if (value is Array<*>)
        return if (value.size == 0) null else listOf(*value)
    if (value is Collection<*>)
        return if (value.size == 0) null else value
    if (value is Map<*,*>)
        return if (value.size == 0) null else value.values
    return value.toString()
}

private fun getMap(src: Any?, type: EntityType<Any>? = null, manager: EntityManager? = null): Map<String,Any?> {
    var map: MutableMap<String, Any?>? = null
    if (src is Map<*,*>)
        map = src as MutableMap<String,Any?>
    else if (src is Tuple) {
        map = mutableMapOf<String, Any?>()
        for (e in src.getElements()) {
            val name = e.getAlias()
            map[name] = src.get(name)
        }
    }
    else if (src == null)
        return mutableMapOf<String, Any?>()
    else
        return BeanMap(src)as Map<String,Any?>

    if (type != null) {
        for (key in map.keys) {
            val name = toPropertyCase(key)!!
            val att = type.getAttribute(name)
            if (att == null || name != key) {
                map.remove(key)
                if (att == null)
                    continue
            }
            val javaType = att.getJavaType()
            val klass = javaType.kotlin
            val value = map[key]
            if (klass.isInstance(value)) {
                if (name != key)
                    map[name] = value
            }
            else if (klass.isData)
                map[name] = manager?.find(javaType, value)
            else
                map[name] = convert(value, klass)
        }
    }
    return map
}