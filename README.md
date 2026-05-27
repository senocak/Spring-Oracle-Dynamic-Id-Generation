# Spring Kotlin Oracle Dynamic ID Generator

## Overview

This project is a **Spring Boot + Kotlin + Oracle** sample that demonstrates how to generate entity IDs dynamically using a **custom Hibernate identifier generator** backed by **Oracle sequences**.

The core idea is simple:

- use a custom `IdentifierGenerator`
- determine the Oracle sequence name dynamically from the entity/table
- fetch IDs either **one by one** or **in batches**
- keep a small in-memory pool of prefetched IDs to reduce database round trips during bulk inserts

The application compares two approaches:

1. **Dynamic custom sequence generation** for `User`
2. **Standard JPA `@SequenceGenerator`** for `Role`

This makes the project useful as a reference for teams that want flexible Oracle ID generation without hardcoding a sequence generator on every entity.

---

## Problem the Project Solves

When using Oracle with JPA/Hibernate, sequence-based IDs are common. However, the default approach usually requires every entity to declare its own fixed configuration, for example:

```kotlin
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SEQ_USER_GENERATOR")
@SequenceGenerator(name = "SEQ_USER_GENERATOR", sequenceName = "users_seq", allocationSize = 1)
```

That works, but becomes repetitive when many entities follow a naming convention such as:

- table `users` -> sequence `users_seq`
- table `orders` -> sequence `orders_seq`
- table `customers` -> sequence `customers_seq`

This project centralizes that logic in a reusable generator so the application can:

- derive the sequence name automatically
- support bulk insert scenarios more efficiently
- keep entity definitions simpler
- still work with Oracle sequence semantics

---

## Core Solution

### 1. Custom Hibernate ID Generator

The heart of the application is `DynamicSequenceGenerator`.

Its responsibilities are:

- inspect the entity being persisted
- resolve the Oracle sequence name dynamically
- fetch the next sequence value from Oracle
- optionally fetch multiple sequence values at once for batch inserts
- cache prefetched IDs in an in-memory queue

The generator is used by the `User` entity through:

```kotlin
@GeneratedValue(generator = "dynamic-seq")
@GenericGenerator(name = "dynamic-seq", strategy = "com.github.senocak.DynamicSequenceGenerator")
```

### 2. Dynamic Sequence Name Resolution

The generator determines the sequence name with this convention:

- if the entity has `@Table(name = "...")`, use `<table_name>_seq`
- otherwise, use `<entity_class_name_lowercase>_seq`

Examples:

- `@Table(name = "users")` -> `users_seq`
- entity `Invoice` without table annotation -> `invoice_seq`

This means the sequence naming rule is defined once in the generator rather than repeated across all entities.

### 3. Batch-Aware ID Fetching

For bulk persistence, the application can prefetch multiple sequence values in one Oracle query:

```sql
SELECT users_seq.NEXTVAL FROM dual CONNECT BY LEVEL <= ?
```

Fetched IDs are stored in a local queue and then consumed one by one during entity persistence.

This reduces the number of database round trips compared with calling `NEXTVAL` for every single insert.

### 4. Thread-Local Batch Context

`IdGenerationContext` stores a per-thread `batchSize` value.

Before calling `saveAll`, the app sets the batch size for the current work unit. The generator reads that value and decides whether to:

- fetch a single ID
- or fetch a batch of IDs into the local pool

After persistence, the thread-local state is cleared to avoid leaking configuration into later operations.

---

## Application Structure

### Main Components

#### `SpringKotlinApplication.kt`
Contains nearly the whole sample application:

- Spring Boot startup
- Oracle datasource configuration using UCP
- REST controller endpoints
- entities and repositories
- thread-local batch context
- the custom Hibernate ID generator

#### `OracleConfiguration`
Builds the datasource from `spring.datasource.*` properties and configures **Oracle UCP (Universal Connection Pool)**.

#### `User`
Represents the entity that uses the **dynamic custom generator**.

#### `Role`
Represents the entity that uses the **standard JPA sequence generator** approach.

#### `DynamicSequenceGenerator`
Implements the custom Oracle sequence-based ID generation strategy.

#### `IdGenerationContext`
Supplies the current desired batch size to the generator via `ThreadLocal`.

---

## How the Flow Works

On application startup:

1. the app starts Spring Boot
2. Oracle datasource and JPA are initialized
3. schema is created automatically because `ddl-auto` is set to `create-drop`
4. `applicationReadyEvent()` runs after startup
5. the app creates sample `User` records in chunks
6. before each chunk, it sets `IdGenerationContext.batchSize`
7. when Hibernate persists each `User`, `DynamicSequenceGenerator` is called
8. the generator fetches one or many IDs from Oracle sequence `users_seq`
9. generated IDs are assigned to entities and the rows are inserted
10. batch context is cleared after each chunk

The application also exposes simple read endpoints to inspect stored data.

---

## Entities

### User

`User` uses the dynamic generator and maps to the `users` table.

Purpose:
- demonstrates convention-based sequence resolution
- demonstrates batched ID prefetching

### Role

`Role` demonstrates the traditional fixed sequence configuration using:

- `@GeneratedValue(strategy = GenerationType.SEQUENCE, ...)`
- `@SequenceGenerator(sequenceName = "users_seq", allocationSize = 1)`

This side-by-side setup helps compare custom and standard approaches.

> Note: in the current code, `Role` is also mapped to table `users`, which is unusual for a real application and appears to be for demonstration only.

---

## REST API

Base path:

```text
/v1
```

Available endpoints:

### Get all users

```http
GET /v1/users/findAll
```

### Get all roles

```http
GET /v1/roles/findAll
```

A helper HTTP file is included at:

```text
src/main/resources/public-oracle-monitoring.http
```

---

## Oracle and Connection Pooling

The project uses:

- **Oracle JDBC Driver** (`ojdbc11`)
- **Oracle UCP** (`ucp11`)
- **Spring Data JPA / Hibernate**

UCP settings are configured under `spring.datasource.ucp` in `application.yml`, including:

- pool size
- validation query
- idle trust seconds
- borrow validation

This makes the sample closer to a production-style Oracle setup than a minimal datasource configuration.

---

## Configuration

Main configuration file:

```text
src/main/resources/application.yml
```

Default values:

- server port: `8083`
- Oracle URL: `jdbc:oracle:thin:@//localhost:1522/FREEPDB1`
- Oracle username: `system`
- Oracle password: `testpassword`
- JPA DDL mode: `create-drop`
- Hibernate JDBC batch size: `10`
- virtual threads: enabled

### Important Properties

#### Server

```yaml
server:
  port: 8083
```

#### Oracle datasource

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1522/FREEPDB1
    username: system
    password: testpassword
```

#### JPA / Hibernate

```yaml
spring:
  jpa:
    database-platform: org.hibernate.dialect.OracleDialect
    hibernate:
      ddl-auto: create-drop
```

Because `ddl-auto` is `create-drop`, schema objects are recreated on startup and dropped on shutdown.

---

## Build and Runtime Stack

- **Java 21**
- **Kotlin 1.9.23**
- **Spring Boot 3.5.14**
- **Spring Data JPA**
- **Oracle JDBC / UCP**
- **Hibernate**

---

## Running the Project

### Prerequisites

You need:

- Java 21
- an Oracle database instance
- Oracle sequences matching the naming convention used by the entities

Based on the current code, you will typically need a sequence like:

```sql
CREATE SEQUENCE users_seq START WITH 1 INCREMENT BY 1;
```

### Start the application

```bash
./gradlew bootRun
```

Or build first:

```bash
./gradlew clean build
java -jar build/libs/*.jar
```

---

## Dynamic ID Generation Details

### Single ID path

If `batchSize <= 1`, the generator executes:

```sql
SELECT <sequence>.NEXTVAL FROM dual
```

This is the simple path for one-at-a-time inserts.

### Batch path

If `batchSize > 1`, the generator executes:

```sql
SELECT <sequence>.NEXTVAL FROM dual CONNECT BY LEVEL <= ?
```

The returned values are stored in an internal queue and reused by later `generate()` calls.

### Why this is useful

Benefits:

- fewer Oracle calls during bulk insert operations
- reusable convention-based ID strategy
- less annotation repetition across entities
- easy to adapt for multiple tables following the same sequence naming rule

---

## Example Startup Behavior

At startup, the app creates sample users in chunks.

Current logic:

- total entities to create: `12`
- chunk size per run: `5`
- names look like `John <current> <index>`

This is mainly there to exercise the batch-oriented ID generation behavior.

---

## Known Caveats / Observations

A few things to be aware of in the current sample:

1. **`Role` maps to `users` table**  
   In a real application, `Role` would likely map to its own table such as `roles`.

2. **Generator pool is in-memory**  
   Prefetched IDs are cached in the generator instance, so the optimization is local to the application instance.

3. **Sequence gaps are normal**  
   As with standard Oracle sequences, gaps can happen. This design is for uniqueness and efficiency, not gapless numbering.

4. **Thread-local batch size must be cleared**  
   The current code correctly clears the context after each batch; this is important to avoid unintended behavior.

5. **README generation note**  
   This README is based on the code currently present in the repository and describes the sample as implemented today.

---

## Why This Project Is Useful

This repository is a practical sample for developers who want to learn or demonstrate:

- custom Hibernate identifier generation in Kotlin
- Oracle sequence integration with Spring Boot
- batched sequence prefetching
- Oracle UCP configuration
- a convention-over-configuration approach for database sequence naming

---

## Possible Improvements

If you continue evolving this project, good next steps would be:

- move entities, repositories, configuration, and generator into separate files
- create a dedicated `roles` table and `roles_seq`
- add integration tests for generator behavior
- externalize chunk/batch settings
- expose a create endpoint to demonstrate runtime inserts
- document required Oracle DDL explicitly
- support configurable sequence naming strategies
- measure performance difference between single-fetch and batch-fetch generation

---

## Summary

This application demonstrates a **dynamic Oracle ID generation strategy** for Spring Boot and Kotlin.

Its main contribution is a custom Hibernate generator that:

- derives sequence names dynamically
- supports bulk ID prefetching
- uses Oracle efficiently during batched persistence
- keeps entity configuration simpler when a naming convention is available

In short, it is a compact reference implementation of **dynamic sequence-based ID generation with Spring Boot, Kotlin, Hibernate, and Oracle**.
