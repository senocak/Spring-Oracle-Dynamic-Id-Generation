import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.23"
    kotlin("plugin.spring") version "1.9.23"
    kotlin("plugin.jpa") version "1.9.23"
    kotlin("plugin.allopen") version "1.9.23"
}

group = "com.github.senocak"
version = "0.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Core & Context
    implementation("org.springframework:spring-context:6.1.10")
    
    // Spring Web MVC
    implementation("org.springframework:spring-web:6.1.10")
    implementation("org.springframework:spring-webmvc:6.1.10")
    
    // Spring ORM & Data JPA
    implementation("org.springframework:spring-orm:6.1.10")
    implementation("org.springframework.data:spring-data-jpa:3.3.1")
    
    // Hibernate (JPA provider)
    implementation("org.hibernate.orm:hibernate-core:6.5.2.Final")
    
    // Embedded Tomcat
    implementation("org.apache.tomcat.embed:tomcat-embed-core:10.1.24")
    implementation("org.apache.tomcat.embed:tomcat-embed-el:10.1.24")
    
    // Jakarta APIs
    implementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
    implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    
    // Database
    implementation("com.oracle.database.jdbc:ojdbc11:23.6.0.24.10") {
        description = "Oracle JDBC Driver"
    }
    implementation("com.oracle.database.jdbc:ucp11:23.6.0.24.10") {
        description = "Oracle Universal Connection Pool"
    }
    implementation("net.ttddyy:datasource-proxy:1.10") {
        description = "Query Execution Logging"
    }
    
    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.slf4j:slf4j-api:2.0.13")
    
    // YAML
    implementation("org.yaml:snakeyaml:2.2")
    
    // Kotlin utilities
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "21"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1G"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.github.senocak.SpringKotlinApplicationKt"
    }
    val dependencies = configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

allOpen {
    annotation("javax.persistence.Entity")
    annotation("javax.persistence.Embeddable")
    annotation("javax.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.Embeddable")
    annotation("jakarta.persistence.MappedSuperclass")
}