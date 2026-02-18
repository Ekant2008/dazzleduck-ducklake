# dazzleduck-ducklake

Utilities for DuckLake merge operations in Java. This library provides utilities for managing data file operations in DuckDB tables with DuckLake metadata.

## Features

### Filter Pushdown
Optimize queries by pushing filters down to the data level, reducing the amount of data scanned and processed.

### Project Pushdown
Push down projection operations to read only the required columns from data files, improving I/O efficiency.

### Aggregation Pushdown
Execute aggregation operations at the data source level, minimizing data transfer and leveraging native file format capabilities.

### Query Splitting
Split complex queries into smaller, more manageable sub-queries that can be executed in parallel or optimized independently.

## Installation

### Download from Community

You can download the extension directly from the community repository:

```bash
# Add the dependency to your project
<dependency>
    <groupId>io.dazzleduck.sql</groupId>
    <artifactId>ducklake</artifactId>
    <version>0.0.5</version>
</dependency>
```

### Build from Source

```bash
./mvnw clean install
```

## Usage

### Merge Table Operations

The `MergeTableOpsUtil` class provides utilities for replacing data files in DuckDB tables:

```java
// Replace old files with new files
MergeTableOpsUtil.replace(
    database,       // Database name
    tableId,        // Target table ID
    tempTableId,    // Temporary table ID for new files
    mdDatabase,     // Metadata database name
    toAdd,          // List of files to add
    toRemove        // List of files to remove
);
```

### Rewrite with Partitioning

Rewrite data files with specified partitioning:

```java
// Rewrite multiple input files with partitioning
List<String> newFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
    inputFiles,     // List of input file paths
    baseLocation,   // Base location for output files
    partition       // List of partition columns
);

// Rewrite single input file with partitioning
List<String> newFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
    inputFile,      // Input file path
    baseLocation,   // Base location for output files
    partition       // List of partition columns
);
```

### List Files

List files within a specified size range:

```java
List<FileStatus> files = MergeTableOpsUtil.listFiles(
    mdDatabase,     // Metadata database name
    catalog,        // Catalog name
    minSize,        // Minimum file size in bytes
    maxSize         // Maximum file size in bytes
);
```

## Development

### Requirements
- Java 21
- Maven 3.6+

### Running Tests

```bash
./mvnw test
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

[Add your license information here]
