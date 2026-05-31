package com.github.senocak

import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder
import oracle.ucp.jdbc.PoolDataSource
import oracle.ucp.jdbc.PoolDataSourceFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.core.env.getProperty
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.util.Locale
import java.util.Properties
import javax.sql.DataSource

@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = ["com.github.senocak"])
class OracleConfiguration(
    private val env: Environment,
) {

    @Bean
    @Primary
    fun dataSource(): DataSource {
        val ucpDataSource: PoolDataSource = PoolDataSourceFactory.getPoolDataSource().also { dataSource: PoolDataSource ->
            dataSource.url = env.getRequiredProperty("spring.datasource.url")
            dataSource.user = env.getRequiredProperty("spring.datasource.username")
            dataSource.password = env.getRequiredProperty("spring.datasource.password")
            
            dataSource.connectionFactoryClassName = env.getProperty("spring.datasource.ucp.connection-factory-class-name", "oracle.jdbc.pool.OracleDataSource")
            dataSource.initialPoolSize = env.getProperty<Int>("spring.datasource.ucp.initial-pool-size", 15)
            dataSource.minPoolSize = env.getProperty<Int>("spring.datasource.ucp.min-pool-size", 10)
            dataSource.maxPoolSize = env.getProperty<Int>("spring.datasource.ucp.max-pool-size", 30)
            dataSource.timeoutCheckInterval = env.getProperty<Int>("spring.datasource.ucp.timeout-check-interval", 30)
            dataSource.inactiveConnectionTimeout = env.getProperty<Int>("spring.datasource.ucp.inactive-connection-timeout", 60)
            dataSource.sqlForValidateConnection = env.getProperty("spring.datasource.ucp.sql-for-validate-connection", "select * from dual")
            dataSource.validateConnectionOnBorrow = env.getProperty<Boolean>("spring.datasource.ucp.validate-connection-on-borrow", true)
            dataSource.secondsToTrustIdleConnection = env.getProperty<Int>("spring.datasource.ucp.seconds-to-trust-idle-connection", 1)
        }
        val enableProxyLogging: Boolean = env.getProperty<Boolean>("decorator.datasource.datasource-proxy.query.enable-logging", false)
        if (enableProxyLogging) {
            val levelStr: String = env.getProperty("decorator.datasource.datasource-proxy.query.log-level", "DEBUG").uppercase(Locale.ROOT)
            val logLevel: SLF4JLogLevel = try {
                SLF4JLogLevel.valueOf(levelStr)
            } catch (e: Exception) {
                SLF4JLogLevel.DEBUG
            }
            return ProxyDataSourceBuilder.create(ucpDataSource)
                .name(env.getProperty("spring.datasource.ucp.connection-pool-name", "SpringKotlinOracleConnectionPoolName1"))
                .logQueryBySlf4j(logLevel)
                .build()
        }
        return ucpDataSource
    }

    @Bean
    fun entityManagerFactory(dataSource: DataSource): LocalContainerEntityManagerFactoryBean {
        val em = LocalContainerEntityManagerFactoryBean()
        em.dataSource = dataSource
        em.setPackagesToScan("com.github.senocak")

        val vendorAdapter = HibernateJpaVendorAdapter()
        em.jpaVendorAdapter = vendorAdapter

        val properties = Properties()
        properties.setProperty("hibernate.hbm2ddl.auto", env.getProperty("spring.jpa.hibernate.ddl-auto", "create-drop"))
        properties.setProperty("hibernate.dialect", env.getProperty("spring.jpa.database-platform", "org.hibernate.dialect.OracleDialect"))
        properties.setProperty("hibernate.show_sql", env.getProperty("spring.jpa.show-sql", "false"))
        properties.setProperty("hibernate.order_updates", env.getProperty("spring.jpa.properties.hibernate.order_updates", "true"))
        properties.setProperty("hibernate.order_inserts", env.getProperty("spring.jpa.properties.hibernate.order_inserts", "true"))
        properties.setProperty("hibernate.jdbc.batch_size", env.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size", "10"))
        properties.setProperty("hibernate.jdbc.fetch_size", env.getProperty("spring.jpa.properties.hibernate.jdbc.fetch_size", "10"))

        em.setJpaProperties(properties)
        return em
    }

    @Bean
    fun transactionManager(entityManagerFactory: LocalContainerEntityManagerFactoryBean): PlatformTransactionManager {
        val transactionManager = JpaTransactionManager()
        transactionManager.entityManagerFactory = entityManagerFactory.`object`
        return transactionManager
    }
}
