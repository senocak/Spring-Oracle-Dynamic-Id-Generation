package com.github.senocak

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.GeneratedValue
import jakarta.persistence.Id
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Table
import oracle.ucp.jdbc.PoolDataSource
import oracle.ucp.jdbc.PoolDataSourceFactory
import oracle.ucp.jdbc.PoolDataSourceImpl
import org.hibernate.annotations.GenericGenerator
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.id.IdentifierGenerator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.repository.support.SimpleJpaRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import javax.sql.DataSource

fun main(args: Array<String>) {
    runApplication<SpringKotlinApplication>(*args)
}

@Configuration
class OracleConfiguration {
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    class DataSourceConfigs: DataSourceProperties() {
        lateinit var ddl: String
        lateinit var ucp: PoolDataSourceImpl
    }

    @Bean
    fun dataSource(dataSourceProperties: DataSourceConfigs): DataSource =
        PoolDataSourceFactory.getPoolDataSource()
            .also {dataSource: PoolDataSource ->
                dataSource.url = dataSourceProperties.url
                dataSource.user = dataSourceProperties.username
                dataSource.password = dataSourceProperties.password
                // UCP-specific configurations
                dataSource.connectionFactoryClassName = dataSourceProperties.ucp.connectionFactoryClassName
                dataSource.initialPoolSize = dataSourceProperties.ucp.initialPoolSize
                dataSource.minPoolSize = dataSourceProperties.ucp.minPoolSize
                dataSource.maxPoolSize = dataSourceProperties.ucp.maxPoolSize
                dataSource.timeoutCheckInterval = dataSourceProperties.ucp.timeoutCheckInterval
                dataSource.inactiveConnectionTimeout = dataSourceProperties.ucp.inactiveConnectionTimeout
                dataSource.sqlForValidateConnection = dataSourceProperties.ucp.sqlForValidateConnection
                dataSource.validateConnectionOnBorrow = dataSourceProperties.ucp.validateConnectionOnBorrow
                dataSource.secondsToTrustIdleConnection = dataSourceProperties.ucp.secondsToTrustIdleConnection
            }
}

@RestController
@RequestMapping(value = ["/v1"])
@ConfigurationPropertiesScan
@SpringBootApplication
class SpringKotlinApplication(
    @PersistenceContext private val entityManager: EntityManager,
): SimpleJpaRepository<User, Long>(User::class.java, entityManager) {
    @Transactional
    @EventListener(value = [ApplicationReadyEvent::class])
    fun applicationReadyEvent() {
        var current = 1
        val total = 52
        val chunkSize = 25 // choose per-run chunking
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
                // If you're using SimpleJpaRepository.saveAll(this) call it here:
                saveAll(users) // or persist each with entityManager.persist(user)
                // Flush/clear to control first-level cache growth if necessary
                entityManager.flush()
                entityManager.clear()
            } finally {
                // Important: clear thread-local to avoid leaking the batch size to later operations
                IdGenerationContext.clear()
            }
            remaining -= currentBatch
        }
    }

    @GetMapping(value = ["/findAll"]) fun getAll(): List<User> = findAll()
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