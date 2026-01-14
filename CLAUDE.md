# CLAUDE.md - AI Assistant Guide for dazzleduck-ducklake

## Project Overview

**Name:** dazzleduck-ducklake
**Version:** 0.0.14
**Purpose:** Java utilities for DuckLake merge operations with snapshot-based file management

This library provides utilities to manage and manipulate DuckLake tables, including operations for merging, partitioning, and replacing files with proper snapshot management.

## Quick Reference

### Build Commands

```bash
# Build without tests
./mvnw clean compile

# Run unit tests only (no Docker required)
./mvnw test -Dtest=MergeTableOpsUtilTest

# Run all tests (requires Docker for PostgreSQL/MinIO containers)
./mvnw test

# Package as JAR
./mvnw clean package

# Package without tests
./mvnw clean package -DskipTests
```

### Prerequisites

- Java 21+
- Maven 3.9.9 (included via wrapper)
- Docker (for integration tests only)

## Project Structure

```
src/
├── main/java/io/dazzleduck/sql/ducklake/
│   ├── MergeTableOpsUtil.java      # Core utility class - main functionality
│   └── Visualize.java              # Demo/visualization class
└── test/java/io/dazzleduck/sql/ducklake/
    ├── MergeTableOpsUtilTest.java          # Local DuckDB unit tests
    ├── MergeTableOpsUtilPostgresTest.java  # PostgreSQL integration tests
    └── MergeTableOpsUtilMinIOTest.java     # S3/MinIO integration tests
```

## Key Classes and Utilities

### MergeTableOpsUtil.java

The main utility class. Location: `src/main/java/io/dazzleduck/sql/ducklake/MergeTableOpsUtil.java`

#### Key Methods

| Method | Purpose |
|--------|---------|
| `replace()` | Replace files in a DuckLake table using snapshot mechanism |
| `rewriteWithPartitionNoCommit()` | Rewrite Parquet files with optional partitioning |
| `deleteDirectlyFromMetadata()` | Mark files for deletion via metadata partition pruning |
| `listFiles()` | List files in a table within a size range |

#### Method Details

**`replace(Connection conn, String database, long tableId, long tempTableId, String mdDatabase, List<String> toAdd, List<String> toRemove)`**
- Two-phase approach: adds files to temp table, then moves to main table in transaction
- Returns new snapshot ID (or -1 if both lists empty)
- Idempotent with respect to file additions (safe for retry)

**`rewriteWithPartitionNoCommit(Connection conn, List<String> inputFiles, String baseLocation, List<String> partition)`**
- Rewrites Parquet files with optional partitioning
- Does NOT commit metadata changes (caller responsible)
- Returns list of newly created file paths

**`deleteDirectlyFromMetadata(Connection conn, long tableId, String mdDatabase, String filter)`**
- Marks files for deletion using SQL filter
- Only works with partitioned files (requires partition directory structure)
- Returns list of deleted file paths

**`listFiles(Connection conn, String mdDatabase, long tableId, long minSize, long maxSize)`**
- Returns `List<FileStatus>` with file metadata

### Visualize.java

Demo class showing all DuckLake operations. Location: `src/main/java/io/dazzleduck/sql/ducklake/Visualize.java`

Run to see functionality demonstration with detailed output.

## Dependencies from dazzleduck-sql-commons

This project depends on `dazzleduck-sql-commons` (version 0.0.13-SNAPSHOT). Key classes:

| Class | Purpose |
|-------|---------|
| `ConnectionPool` | Database connection management |
| `FileStatus` | File metadata record (fileName, size) |
| `DucklakePartitionPruning` | Partition-based file filtering |
| `CopyResult` | COPY operation results |

### ConnectionPool Usage

```java
pool.getConnection()           // Get connection from pool
pool.execute(sql)              // Execute SQL statement
pool.collectFirst(sql, Class)  // Get single result
pool.collectFirstColumn(sql)   // Get column values as list
pool.collectAll(sql, Class)    // Get all results mapped to class
pool.executeBatchInTxn(sqls)   // Execute multiple statements in transaction
```

## Testing

### Test Categories

1. **Unit Tests** (`MergeTableOpsUtilTest.java`)
   - No external dependencies
   - Uses temporary directories
   - Fast execution

2. **PostgreSQL Integration** (`MergeTableOpsUtilPostgresTest.java`)
   - Requires Docker
   - PostgreSQL 17.5 container for metadata
   - Tests cross-platform compatibility

3. **S3/MinIO Integration** (`MergeTableOpsUtilMinIOTest.java`)
   - Requires Docker
   - MinIO container for S3-compatible storage
   - Tests cloud storage scenarios

### Running Specific Tests

```bash
# Unit tests only
./mvnw test -Dtest=MergeTableOpsUtilTest

# PostgreSQL tests
./mvnw test -Dtest=MergeTableOpsUtilPostgresTest

# MinIO/S3 tests
./mvnw test -Dtest=MergeTableOpsUtilMinIOTest
```

## Architecture Concepts

### Snapshot-Based File Management

- DuckLake uses snapshots to track table state over time
- Files have `begin_snapshot` and `end_snapshot` markers
- Old files are marked with `end_snapshot` (not immediately deleted)
- `ducklake_expire_snapshots()` moves files to scheduled deletion

### Two-Phase Replace Operation

1. **Phase 1:** Add files to temp table (auto-commits)
   - Checks existing files to prevent duplicates
2. **Phase 2:** Single transaction
   - Create new snapshot
   - Move files from temp to main table
   - Mark files to remove with `end_snapshot`
   - Atomic commit or rollback

## Code Conventions

### SQL Injection Prevention
- Single quotes are escaped in file paths
- Use parameterized queries where possible

### Error Handling
- Null/blank argument validation with `IllegalArgumentException`
- Transaction rollback on failure
- Exception suppression during rollback cleanup

### Naming Conventions
- Utility classes end with `Util`
- Test classes end with `Test`
- Integration tests include service name (`PostgresTest`, `MinIOTest`)

## Key Dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| junit-jupiter | 5.10.1 | Test framework |
| testcontainers | 1.21.4 | Docker-based testing |
| postgresql | 42.7.4 | PostgreSQL JDBC driver |
| aws-sdk-s3 | 2.20.160 | S3 operations |
| jackson-databind | 2.15.3 | JSON serialization |
| dazzleduck-sql-commons | 0.0.13-SNAPSHOT | Custom SQL utilities |

## Common Tasks

### Adding a New Table Operation

1. Add method to `MergeTableOpsUtil.java`
2. Add tests to `MergeTableOpsUtilTest.java`
3. Add integration tests if needed
4. Update `Visualize.java` to demonstrate

### Running Demo

```bash
# Compile and run Visualize class
./mvnw compile exec:java -Dexec.mainClass="io.dazzleduck.sql.ducklake.Visualize"
```

### Debugging SQL Queries

Key SQL functions used by DuckLake:
- `ducklake_add_data_files()` - Add files to table
- `ducklake_expire_snapshots()` - Expire old snapshots
- `ducklake_snapshot_ids()` - Get snapshot IDs

Metadata tables:
- `ducklake_data_file` - File metadata
- `ducklake_snapshot` - Snapshot history
- `ducklake_scheduled_for_deletion` - Files pending deletion

## File Paths

| File | Line Range | Content |
|------|------------|---------|
| `MergeTableOpsUtil.java` | 1-200 | Core utility methods |
| `Visualize.java` | 1-150 | Demo code |
| `MergeTableOpsUtilTest.java` | 1-200 | Unit tests |
| `MergeTableOpsUtilPostgresTest.java` | 1-300 | PostgreSQL tests |
| `MergeTableOpsUtilMinIOTest.java` | 1-250 | S3 tests |
| `pom.xml` | 1-150 | Maven configuration |

## DuckLake SQL Reference

### SQL Queries Used Internally

```sql
-- Add data files to a table
CALL ducklake_add_data_files('catalog', 'table_name', 'file_path',
     schema => 'main', ignore_extra_columns => true, allow_missing => true);

-- Copy data to partitioned Parquet files
COPY (SELECT * FROM read_parquet([files])) TO 'path'
     (FORMAT PARQUET, PARTITION_BY (col1, col2), RETURN_FILES);

-- Create a new snapshot
INSERT INTO {mdDatabase}.ducklake_snapshot
     (snapshot_id, snapshot_time, schema_version, next_catalog_id, next_file_id)
     SELECT MAX(snapshot_id) + 1, now(), MAX(schema_version),
            MAX(next_catalog_id), MAX(next_file_id)
     FROM {mdDatabase}.ducklake_snapshot;

-- Mark files as deleted (set end_snapshot)
UPDATE {mdDatabase}.ducklake_data_file
     SET end_snapshot = {snapshotId}
     WHERE data_file_id IN (ids) AND end_snapshot IS NULL;

-- Expire old snapshots (moves files to scheduled deletion)
CALL ducklake_expire_snapshots('catalog', older_than => now());

-- Attach DuckLake catalog
ATTACH 'ducklake:/path/to/catalog.ducklake' AS catalog_name
       (DATA_PATH '/path/to/data');
```

### Metadata Tables Schema

**ducklake_snapshot**
| Column | Type | Description |
|--------|------|-------------|
| snapshot_id | BIGINT | Unique snapshot identifier |
| snapshot_time | TIMESTAMP | When snapshot was created |
| schema_version | BIGINT | Schema version at snapshot |
| next_catalog_id | BIGINT | Next available catalog ID |
| next_file_id | BIGINT | Next available file ID |

**ducklake_data_file**
| Column | Type | Description |
|--------|------|-------------|
| data_file_id | BIGINT | Unique file identifier |
| table_id | BIGINT | Table this file belongs to |
| path | VARCHAR | File path (relative to data path) |
| file_size_bytes | BIGINT | File size in bytes |
| record_count | BIGINT | Number of records in file |
| begin_snapshot | BIGINT | Snapshot when file was added |
| end_snapshot | BIGINT | Snapshot when file was removed (NULL if active) |

**ducklake_files_scheduled_for_deletion**
| Column | Type | Description |
|--------|------|-------------|
| data_file_id | BIGINT | File identifier |
| path | VARCHAR | File path |
| schedule_start | TIMESTAMP | When deletion was scheduled |

**ducklake_table**
| Column | Type | Description |
|--------|------|-------------|
| table_id | BIGINT | Unique table identifier |
| schema_id | BIGINT | Schema this table belongs to |
| table_name | VARCHAR | Table name |

**ducklake_file_column_stats**
- Stores min/max statistics for each column in each file
- Used for partition pruning and query optimization

## Troubleshooting

### Common Errors

**"One or more files scheduled for deletion were not found"**
- Cause: Files in `toRemove` list don't exist in the table
- Solution: Verify file paths match exactly what's stored in `ducklake_data_file.path`

**"database cannot be null or blank"**
- Cause: Missing required parameter
- Solution: Ensure all required parameters are provided to utility methods

**Transaction rollback during replace**
- Cause: Error during Phase 2 of replace operation
- Solution: Check if files exist, temp table has correct schema

**Partition pruning returns empty list**
- Cause: Files don't have partition directory structure
- Solution: Use `rewriteWithPartitionNoCommit()` to create partitioned files first

### Docker Issues (Integration Tests)

```bash
# Check Docker is running
docker info

# Pull required images manually if needed
docker pull postgres:17.5
docker pull minio/minio
```

## Known Limitations

1. **deleteDirectlyFromMetadata()** only works with files that have partition directory structure in their paths (e.g., `category=sales/data_0.parquet`). Files created by normal INSERT statements don't have this structure.

2. **ducklake_add_data_files()** auto-commits internally, which is why the two-phase approach is needed in `replace()`.

3. **Temp table requirement**: The `replace()` method requires a temporary table with the same schema as the main table.

4. **File paths in toRemove**: Must match the path stored in metadata (usually just filename, not full path).

## Code Examples

### Basic Replace Operation

```java
// Get table and temp table IDs
String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
Long tableId = ConnectionPool.collectFirst(GET_TABLE_ID.formatted(mdDatabase, tableName), Long.class);

// Create temp table with same schema
String tempTableName = "__temp_" + tableId;
ConnectionPool.execute("CREATE OR REPLACE TABLE %s.%s AS SELECT * FROM %s.%s LIMIT 0"
        .formatted(catalog, tempTableName, catalog, tableName));
Long tempTableId = ConnectionPool.collectFirst(GET_TABLE_ID.formatted(mdDatabase, tempTableName), Long.class);

// Perform replace
long snapshotId = MergeTableOpsUtil.replace(
    catalog,           // Database/catalog name
    tableId,           // Main table ID
    tempTableId,       // Temp table ID
    mdDatabase,        // Metadata database name
    newFiles,          // List<String> files to add
    oldFiles           // List<String> files to remove
);
```

### Rewrite Files with Partitioning

```java
// Rewrite files partitioned by date and category
List<String> newFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
    originalFiles,                    // Input files
    "/path/to/output/",               // Base output location
    List.of("date", "category")       // Partition columns
);

// Note: Must call replace() to update metadata
```

### Delete via Partition Filter

```java
// Mark files for deletion where category = 'sales'
String filter = "SELECT * FROM table WHERE category = 'sales'";
List<String> deletedFiles = MergeTableOpsUtil.deleteDirectlyFromMetadata(
    tableId,
    mdDatabase,
    filter
);

// Then expire snapshots to schedule actual deletion
ConnectionPool.execute("CALL ducklake_expire_snapshots('%s', older_than => now())".formatted(catalog));
```

### Setting Up a DuckLake Catalog

```java
// Create catalog file and data directory
Path catalogFile = Paths.get("/tmp/my_catalog.ducklake");
Path dataPath = Paths.get("/tmp/my_data");
Files.createDirectories(dataPath);

// Attach catalog
String attachDB = "ATTACH 'ducklake:%s' AS my_catalog (DATA_PATH '%s');"
        .formatted(catalogFile, dataPath);
ConnectionPool.execute(attachDB);

// Use catalog
ConnectionPool.execute("USE my_catalog");

// Create partitioned table
ConnectionPool.execute("CREATE TABLE events (id BIGINT, name VARCHAR, category VARCHAR)");
ConnectionPool.execute("ALTER TABLE events SET PARTITIONED BY (category)");
```

## Environment Setup

### IDE Configuration (IntelliJ IDEA)

1. Import as Maven project
2. Set Project SDK to Java 21
3. Enable annotation processing for Lombok (if used)
4. Mark `src/main/java` as Sources Root
5. Mark `src/test/java` as Test Sources Root

### Windows-Specific Notes

- Use `mvnw.cmd` instead of `./mvnw`
- File paths use backslashes but DuckDB accepts forward slashes
- Docker Desktop must be running for integration tests

### Local Development

```bash
# Clone the commons dependency if needed
git clone <dazzleduck-sql-commons-repo>
cd dazzleduck-sql-commons
./mvnw install -DskipTests

# Then build this project
cd ../dazzleduck-ducklake
./mvnw clean compile
```

## Contribution Guidelines

1. **Branch from main** for all changes
2. **Write tests** for new functionality
3. **Follow existing patterns** for error handling and SQL injection prevention
4. **Update Visualize.java** to demonstrate new features
5. **Run full test suite** before submitting PR: `./mvnw test`

### Code Style

- Use `String.formatted()` for SQL queries
- Validate all public method parameters
- Use try-with-resources for connections
- Add JavaDoc for public methods
- Escape single quotes in SQL strings: `value.replace("'", "''")`

## Related Projects

- **dazzleduck-sql-commons** (v0.0.13-SNAPSHOT) - Shared utilities including ConnectionPool, FileStatus, DucklakePartitionPruning
- **DuckDB** - Embedded analytical database
- **DuckLake** - DuckDB extension for lakehouse functionality

## Notes for AI Assistants

- Always run tests after making changes: `./mvnw test`
- The project uses DuckDB as embedded database
- Integration tests require Docker to be running
- Main branch is used for PRs
- Recent commits show active refactoring work
- The metadata database name follows pattern: `__ducklake_metadata_{catalog_name}`
- Files in `toRemove` use relative paths (filename only), files in `toAdd` use full paths
- When debugging, check `ducklake_data_file` table for file state
- Snapshot IDs are monotonically increasing
