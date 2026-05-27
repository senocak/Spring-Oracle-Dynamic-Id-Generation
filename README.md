# Spring Kotlin Oracle Dynamic ID Generator

## Overview
This project is a **Spring Boot + Kotlin + Oracle** sample application that demonstrates how to generate entity IDs dynamically using a **custom Hibernate identifier generator** backed by **Oracle sequences**.

The main idea is to:
- use a reusable custom `IdentifierGenerator`
- determine Oracle sequence names dynamically from entity metadata
- fetch IDs either **one by one** or **in batches**
- keep a small in-memory pool of prefetched IDs to reduce database round trips during bulk inserts
- support JDBC batching efficiently

The project compares two different approaches side-by-side:

1. **Dynamic custom sequence generation** (`User`)
2. **Standard JPA `@SequenceGenerator`** (`Role`)

This makes the project useful as a reference for teams that want flexible Oracle ID generation without hardcoding a sequence generator on every entity.

---

## Problem the Project Solves
When using Oracle with JPA/Hibernate, sequence-based IDs are common. However, the default approach usually requires every entity to declare its own fixed configuration, for example:
```kotlin
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SEQ_USER_GENERATOR")
@SequenceGenerator(name = "SEQ_USER_GENERATOR", sequenceName = "users_seq", allocationSize = 1)
```
This works well but becomes repetitive when many entities follow a naming convention such as:
- table `users` -> sequence `users_seq`
- table `orders` -> sequence `orders_seq`
- table `customers` -> sequence `customers_seq`

This project centralizes sequence handling in a reusable generator so that the application can:
- derive the sequence name automatically
- support bulk insert scenarios more efficiently
- keep entity definitions simpler
- still work with Oracle sequence semantics

---

## Core Solution
### 1. Custom Hibernate ID Generator
The core component is `DynamicSequenceGenerator`.

Its responsibilities are:
- inspect the entity being persisted
- resolve the Oracle sequence name dynamically
- fetch the next sequence value from Oracle
- optionally fetch multiple IDs in a single SQL query
- cache prefetched IDs in an in-memory queue

The generator is attached to the `User` entity through:
```kotlin
@GeneratedValue(generator = "dynamic-seq")
@GenericGenerator(name = "dynamic-seq", strategy = "com.github.senocak.DynamicSequenceGenerator")
```
## 2. Dynamic Sequence Name Resolution
The generator determines the sequence name using conventions:

- if the entity contains `@Table(name = "...")` use `<table_name>_seq`
- otherwise, use `<entity_class_name_lowercase>_seq`

Examples:

| Entity/Table | Derived Sequence |
|---|---|
| `@Table(name = "users")` | `users_seq` |
| `Invoice` | `invoice_seq` |
This means the sequence naming rule is defined once in the generator rather than repeated across all entities.

---

## 3. Batch-Aware ID Fetching
For bulk insert operations, the generator can fetch multiple IDs in one Oracle query:
```sql
SELECT users_seq.NEXTVAL FROM dual CONNECT BY LEVEL <= ?
```
Returned IDs are stored in a temporary in-memory queue and reused during persistence.  This reduces database round trips significantly compared with fetching IDs one-by-one.

---

## 4. Thread-Local Batch Context
`IdGenerationContext` stores the desired batch size using `ThreadLocal`.  Before calling `saveAll`, the app sets the batch size for the current work unit. The generator reads that value and decides whether to:
- fetch a single ID
- or fetch a batch of IDs into the local pool

After persistence, the thread-local state is cleared to avoid leaking configuration into later operations.

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

## Summary

This application demonstrates a **dynamic Oracle ID generation strategy** for Spring Boot and Kotlin.

Its main contribution is a custom Hibernate generator that:

- derives sequence names dynamically
- supports bulk ID prefetching
- uses Oracle efficiently during batched persistence
- keeps entity configuration simpler when a naming convention is available

In short, it is a compact reference implementation of **dynamic sequence-based ID generation with Spring Boot, Kotlin, Hibernate, and Oracle**.
