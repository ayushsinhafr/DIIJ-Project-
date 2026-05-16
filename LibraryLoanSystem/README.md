# Library Loan Management System
## JDBC + Apache Derby | Transaction Management & Performance Evaluation

---

## Overview

A complete, console-driven **Library Loan Management System** built with:
- **Java 11** — core language
- **Apache Derby 10.15** (embedded mode) — database engine
- **Maven** — dependency management and build tool

Demonstrates:
- Explicit ACID transaction management (commit / rollback / savepoints)
- PreparedStatement best practices
- Resource management via `try-with-resources`
- Comprehensive JDBC performance benchmarking across 4 test categories

---

## Prerequisites

| Requirement | Version | Check |
|-------------|---------|-------|
| Java JDK    | 11+     | `java -version` |
| Apache Maven | 3.6+   | `mvn -version` |

Apache Derby is automatically downloaded by Maven — **no manual installation required**.

---

## Project Structure

```
LibraryLoanSystem/
├── pom.xml                              ← Maven build (Derby 10.15 dep)
├── README.md
├── analysis.md                          ← Transaction & performance analysis
└── src/main/java/library/
    ├── connection/
    │   └── ConnectionManager.java       ← DB init, schema, seed data, shutdown
    ├── transaction/
    │   └── TransactionService.java      ← commit/rollback/savepoint logic
    ├── business/
    │   └── BusinessLogic.java           ← CRUD: members, books, loans
    ├── benchmark/
    │   └── PerformanceEvaluator.java    ← 4 benchmark suites + CSV report
    └── ui/
        └── MainApp.java                 ← CLI menu orchestration
```

---

## Build & Run

### Option 1 — Maven (recommended)

```bash
# 1. Navigate to project root
cd LibraryLoanSystem

# 2. Compile
mvn compile

# 3. Run
mvn exec:java

# Or: build fat JAR and run it
mvn package
java -jar target/LibraryLoanSystem-fat.jar
```

### Option 2 — Fat JAR (portable)

```bash
mvn package
java -jar target/LibraryLoanSystem-fat.jar
```

The Derby database files will be created in a `lab10db/` directory under the current working directory.

---

## CLI Session Sample

```
════════════════════════════════════════════════════════════
   Library Loan Management System — Apache Derby + JDBC
   Transaction Management & Performance Evaluation Edition
════════════════════════════════════════════════════════════

[DB] Connected to embedded Derby: lab10db
[DB] Schema verified and seed data loaded.

  MAIN MENU
  ────────────────────────────────────────────────────────
  MEMBERS
    [1] View all members
    [2] Register new member

  BOOKS
    [3] View all books
    [4] Add new book
   [10] Find book by ISBN

  LOANS
    [5] View all loans
    [6] Active loans by member
    [7] View overdue loans
    [8] Process loan (borrow)
    [9] Process return

  DEMONSTRATIONS & BENCHMARKS
   [11] Demo: transaction rollback
   [12] Run performance benchmarks
    [0] Exit

  Enter choice: 8

  == Process Loan (Borrow Book) ==
  Enter Book ID   : 5
  Enter Member ID : 3

[TXN] Loan #4 committed: Book 5 → Member 3
  ✓ Loan #4 created successfully!
```

---

## Database Schema

```sql
MEMBERS  (MemberID PK, Name, Email UNIQUE, ActiveLoans CHECK(0-5), JoinDate)
BOOKS    (BookID PK, ISBN UNIQUE, Title, Author, Available CHECK(0,1))
LOANS    (LoanID PK, MemberID FK, BookID FK, LoanDate, DueDate, ReturnDate)

Indexes:
  IDX_BOOKS_ISBN         ON BOOKS(ISBN)
  IDX_LOANS_MEMBERID     ON LOANS(MemberID)
  IDX_LOANS_RETURNDATE   ON LOANS(ReturnDate)
```

---

## Performance Benchmarks (Menu Option 12)

Four test suites, each run 5× (first run is warm-up and discarded):

| Suite | What is measured |
|-------|-----------------|
| **Insert Strategy** | Individual `executeUpdate()` vs `addBatch()/executeBatch()` — 1K & 10K rows |
| **Query Strategy** | Full-table scan vs indexed lookup on `LOANS` table — 200 repetitions |
| **Statement Type** | Raw `Statement` (string concat) vs `PreparedStatement` — 500 SELECTs |
| **Transaction Granularity** | Per-operation commit vs single batched commit — 100 inserts |

Results are printed as a console table **and** saved as `performance_report_<timestamp>.csv`.

---

## Dependency List

| Dependency | Version | Purpose |
|------------|---------|---------|
| `org.apache.derby:derby` | 10.15.2.0 | Embedded Derby engine |
| `org.apache.derby:derbytools` | 10.15.2.0 | Derby utilities |
| `org.slf4j:slf4j-simple` | 2.0.9 | Suppress boot messages |

---

## Derby-Specific Notes

- **URL format**: `jdbc:derby:lab10db;create=true` — creates DB if absent
- **Shutdown**: `jdbc:derby:lab10db;shutdown=true` — releases file locks on exit  
  *(Derby intentionally throws `SQLState=XJ015` on clean shutdown — this is expected)*
- **Database location**: `./lab10db/` in the directory where you run the JAR
- **Indexes**: Verified at startup via `DatabaseMetaData.getTables()`
- **Runtime statistics**: Enable with `CALL SYSCS_UTIL.SYSCS_SET_RUNTIMESTATISTICS(1)` in `derby.log`

---

## Cleanup

To reset the database, stop the application (option 0) and delete the `lab10db/` directory. The next run will recreate and re-seed everything automatically.
