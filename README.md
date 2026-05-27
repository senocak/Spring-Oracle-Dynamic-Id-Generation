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

## Log
```
2026-05-27T23:21:33.780+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:4, Time:75, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["SELECT users_seq.NEXTVAL FROM dual CONNECT BY LEVEL <= ?"]
Params:[(5)]
2026-05-27T23:21:33.783+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Fetched 5 ids into local pool (requested 5). Returning id after batch fetch: 1
2026-05-27T23:21:33.800+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 2
2026-05-27T23:21:33.801+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 3
2026-05-27T23:21:33.801+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 4
2026-05-27T23:21:33.802+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 5
2026-05-27T23:21:33.836+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:4, Time:14, Success:True
Type:Prepared, Batch:True, QuerySize:1, BatchSize:5
Query:["insert into users (name,id) values (?,?)"]
Params:[(John 1 0,1),(John 2 1,2),(John 3 2,3),(John 4 3,4),(John 5 4,5)]
2026-05-27T23:21:33.849+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:5, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["SELECT users_seq.NEXTVAL FROM dual CONNECT BY LEVEL <= ?"]
Params:[(5)]
2026-05-27T23:21:33.849+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Fetched 5 ids into local pool (requested 5). Returning id after batch fetch: 6
2026-05-27T23:21:33.850+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 7
2026-05-27T23:21:33.850+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 8
2026-05-27T23:21:33.850+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 9
2026-05-27T23:21:33.850+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 10
2026-05-27T23:21:33.858+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:5, Time:7, Success:True
Type:Prepared, Batch:True, QuerySize:1, BatchSize:5
Query:["insert into users (name,id) values (?,?)"]
Params:[(John 6 0,6),(John 7 1,7),(John 8 2,8),(John 9 3,9),(John 10 4,10)]
2026-05-27T23:21:33.875+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:6, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["SELECT users_seq.NEXTVAL FROM dual CONNECT BY LEVEL <= ?"]
Params:[(2)]
2026-05-27T23:21:33.875+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Fetched 2 ids into local pool (requested 2). Returning id after batch fetch: 11
2026-05-27T23:21:33.876+03:00  INFO 49079 --- [SpringKotlinOracle] [           main] c.g.senocak.DynamicSequenceGenerator     : Returning id from local pool: 12
2026-05-27T23:21:33.882+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:6, Time:4, Success:True
Type:Prepared, Batch:True, QuerySize:1, BatchSize:2
Query:["insert into users (name,id) values (?,?)"]
Params:[(John 11 0,11),(John 12 1,12)]








2026-05-27T23:21:38.967+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:20, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.971+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.973+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.974+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.976+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.977+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.978+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.979+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.991+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:11, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.992+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.993+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:38.994+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:False, QuerySize:1, BatchSize:0
Query:["select users_seq.nextval"]
Params:[()]
2026-05-27T23:21:39.009+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:12, Success:True
Type:Prepared, Batch:True, QuerySize:1, BatchSize:10
Query:["insert into roles (name,id) values (?,?)"]
Params:[(John 0,13),(John 1,14),(John 2,15),(John 3,16),(John 4,17),(John 5,18),(John 6,19),(John 7,20),(John 8,21),(John 9,22)]
2026-05-27T23:21:39.010+03:00 DEBUG 49079 --- [SpringKotlinOracle] [           main] n.t.d.l.l.SLF4JQueryLoggingListener      : 
Name:dataSource, Connection:7, Time:0, Success:True
Type:Prepared, Batch:True, QuerySize:1, BatchSize:2
Query:["insert into roles (name,id) values (?,?)"]
Params:[(John 10,23),(John 11,24)]
```
### Performance Comparison
Here is a breakdown of the database calls required to insert 12 records in both scenarios:

| Metric                    | Scenario 1 (users table)      | Scenario 2 (roles table)  |
|---------------------------|-------------------------------|---------------------------|
| Total Records Inserted    | 12                            | 12                        |
| ID Generation Strategy    | Bulk Fetch (CONNECT BY LEVEL) | Single Fetch (NEXTVAL)    |
| ID Fetch Queries Executed | 3 (Fetching 5, 5, and 2)      | 12 (Fetching 1 at a time) |
| Insert Queries Executed   | 3 (Batches of 5, 5, and 2)    | 2 (Batches of 10 and 2)   |
| Total Database Round-trips| 6                             | 14                        |

### Key Takeaways
- The Cost of `NEXTVAL`: Scenario 2 clearly illustrates the N+1 problem associated with default database sequences. Making 12 separate network calls just to generate IDs is highly inefficient and will severely degrade performance at scale.
- Effective JDBC Batching: Both scenarios successfully execute batched `INSERT` statements. However, the performance gains of batching inserts in Scenario 2 are largely negated by the overhead of fetching the IDs individually beforehand.
- The Custom Generator is Superior: The `DynamicSequenceGenerator` used in Scenario 1 is highly effective. By aligning the sequence fetch size with the batch insert size, it drastically reduces network round-trips, making it the optimal pattern for bulk data operations in Oracle.