package com.github.senocak

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.apache.catalina.Context
import org.apache.catalina.startup.Tomcat
import org.hibernate.annotations.GenericGenerator
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.id.IdentifierGenerator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.data.repository.CrudRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import java.io.File
import java.io.Serializable
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.LinkedList
import java.util.Locale
import java.util.Properties
import java.util.Queue

fun main(args: Array<String>) {
    val yaml = YamlPropertiesFactoryBean()
    yaml.setResources(ClassPathResource("application.yml"))
    val properties: Properties? = yaml.getObject()
    val portStr: String = properties?.getProperty("server.port") ?: "8083"
    val port: Int = resolvePort(portStr)
    
    val tomcat = Tomcat()
    tomcat.setPort(port)
    tomcat.connector.setProperty("relaxedPathChars", "<>[\\]^`{|} ")
    tomcat.connector.setProperty("relaxedQueryChars", "<>[\\]^`{|} ")
    
    val baseDir: File = File(System.getProperty("java.io.tmpdir"), "tomcat-tmp-$port").apply {
        if (!exists()) mkdirs()
    }
    tomcat.setBaseDir(baseDir.absolutePath)
    
    val context: Context = tomcat.addContext("", baseDir.absolutePath)
    
    val ac = AnnotationConfigWebApplicationContext()
    if (properties != null) {
        ac.environment.propertySources.addLast(
            org.springframework.core.env.PropertiesPropertySource("yamlProperties", properties)
        )
    }
    ac.register(AppConfig::class.java)
    
    val dispatcherServlet = DispatcherServlet(ac)
    tomcat.addServlet("", "dispatcher", dispatcherServlet)
    context.addServletMappingDecoded("/*", "dispatcher")
    
    tomcat.start()
    tomcat.server.await()
}

private fun resolvePort(portStr: String): Int {
    if (portStr.startsWith("\${") && portStr.endsWith("}")) {
        val inner = portStr.substring(2, portStr.length - 1)
        val parts = inner.split(":")
        val envVar = parts[0]
        val defaultVal = parts.getOrNull(1) ?: "8083"
        return System.getenv(envVar)?.toIntOrNull() ?: defaultVal.toInt()
    }
    return portStr.toIntOrNull() ?: 8083
}

@RestController
@RequestMapping(value = ["/v1"])
class SpringKotlinApplication(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
) {
    @EventListener(value = [ContextRefreshedEvent::class])
    fun applicationReadyEvent(event: ContextRefreshedEvent) {
        if (event.applicationContext.parent != null) {
            return
        }
        var current = 1
        val total = 12
        val chunkSize = 5 // choose per-run chunking
        var remaining: Int = total
        while (remaining > 0) {
            val currentBatch: Int = minOf(a = remaining, b = chunkSize)
            // Tell generator how many IDs we need for this chunk
            IdGenerationContext.batchSize = currentBatch
            try {
                val users: MutableList<User> = mutableListOf()
                repeat(times = currentBatch) { i: Int ->
                    users.add(element = User(name = "John $current $i"))
                    current++
                }
                userRepository.saveAll(users)
            } finally {
                // Important: clear thread-local to avoid leaking the batch size to later operations
                IdGenerationContext.clear()
            }
            remaining -= currentBatch
        }
        roleRepository.saveAll(mutableListOf<Role>()
            .also { it: MutableList<Role> ->
                repeat(times = 12) { i: Int ->
                    it.add(element = Role(name = "John $i"))
                }
            })
    }

    @GetMapping(value = ["/users/findAll"]) fun getAllUsers(): Iterable<User> = userRepository.findAll()
    @GetMapping(value = ["/roles/findAll"]) fun getAllRoles(): Iterable<Role> = roleRepository.findAll()
}

@Entity
@Table(name = "users")
class User(
    @Column(name = "name", nullable = false, length = 50) var name: String? = null,
) {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @GeneratedValue(generator = "dynamic-seq")
    @GenericGenerator(name = "dynamic-seq", strategy = "com.github.senocak.DynamicSequenceGenerator")
    //@IdGeneratorType(value = DynamicSequenceGenerator::class)
    var id: Long? = null
}
interface UserRepository: CrudRepository<User, Long>

@Entity
@Table(name = "roles")
class Role(
    @Column(name = "name", nullable = false, length = 50) var name: String? = null,
) {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    //@IdGeneratorType(value = DynamicSequenceGenerator::class)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SEQ_USER_GENERATOR")
    @SequenceGenerator(name = "SEQ_USER_GENERATOR", sequenceName = "users_seq", allocationSize = 1)
    var id: Long? = null
}
interface RoleRepository: CrudRepository<Role, Long>

object IdGenerationContext {
    private val BATCH_SIZE: ThreadLocal<Int> = ThreadLocal<Int>().also { it: ThreadLocal<Int> ->
        it.set(1)
    }
    var batchSize: Int
        get() = BATCH_SIZE.get()
        set(size: Int) {
            BATCH_SIZE.set(size)
        }

    fun clear() {
        BATCH_SIZE.remove()
    }
}

// CREATE SEQUENCE my_seq MINVALUE 1 MAXVALUE 999999999999999999 INCREMENT BY 1 START WITH 1;
class DynamicSequenceGenerator : IdentifierGenerator {
    private val log: Logger = LoggerFactory.getLogger(javaClass)
    private val idPool: Queue<Long?> = LinkedList<Long?>()

    @Synchronized
    override fun generate(session: SharedSessionContractImplementor, `object`: Any): Serializable? {
        synchronized (lock = this) {
            log.debug("generate() called - pool size: ${idPool.size}")
            if (idPool.isNotEmpty()) {
                val poll: Long? = idPool.poll()
                log.info("Returning id from local pool: $poll")
                return poll
            }
            val seqName: String = determineSequenceName(entity = `object`)
            val batchSize: Int = IdGenerationContext.batchSize
            if (batchSize <= 1) {
                val id: Long? = getSingleId(session = session, seqName = seqName)
                log.info("Fetched single id: $id")
                return id
            }
            val fetched: Int = try {
                fetchBatch(session = session, batchSize = batchSize, seqName = seqName)
            } catch (ex: Exception) {
                log.error("Error while fetching batch of size {}: {}", batchSize, ex.message, ex)
                throw ex
            }
            val id: Long? = idPool.poll()
            log.info("Fetched $fetched ids into local pool (requested $batchSize). Returning id after batch fetch: $id")
            return id
        }
    }

    private fun getSingleId(session: SharedSessionContractImplementor, seqName: String): Long? {
        return session.doReturningWork { connection: Connection? ->
            connection!!.prepareStatement("SELECT $seqName.NEXTVAL FROM dual").use { ps: PreparedStatement ->
                ps.executeQuery().use { rs: ResultSet ->
                    rs.next()
                    return@doReturningWork (rs.getObject(1) as BigDecimal).toLong()
                }
            }
        }
    }

    /**
     * Fetches exactly `batchSize` ids and returns how many were added to the local pool.
     */
    private fun fetchBatch(session: SharedSessionContractImplementor, batchSize: Int, seqName: String): Int {
        var count = 0
        session.doWork { connection: Connection? ->
            connection!!.prepareStatement("SELECT $seqName.NEXTVAL FROM dual CONNECT BY LEVEL <= ?").use { ps: PreparedStatement ->
                ps.setInt(1, batchSize)
                ps.executeQuery().use { rs: ResultSet ->
                    while (rs.next()) {
                        val id: Long = (rs.getObject(1) as BigDecimal).toLong()
                        idPool.add(id)
                        count++
                    }
                }
            }
        }
        log.debug("fetchBatch added $count ids to pool")
        return count
    }

    private fun determineSequenceName(entity: Any): String {
        // Prefer @Table(name = "...") if present on the entity class
        val clazz: Class<Any> = entity.javaClass
        val tableAnnot: Table = clazz.getAnnotation(Table::class.java)
        if (tableAnnot.name.isNotBlank()) {
            // Example convention: table "users" -> sequence "users_seq"
            return "${tableAnnot.name}_seq"
        }
        // Fallback: use the simple class name in lower case with _seq
        return "${clazz.simpleName.lowercase(Locale.ROOT)}_seq"
    }
}