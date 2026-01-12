package io.dazzleduck.sql.ducklake;

import io.dazzleduck.sql.commons.ConnectionPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * Demonstrates DuckLake functionality including:
 * - Creating a table
 * - Adding partition column
 * - Inserting data
 * - Deleting data
 * - Updating data
 * - Deleting files with MergeTableOpsUtil.deleteDirectlyFromMetadata()
 * - Replacing files with MergeTableOpsUtil.replace()
 * - Expiring snapshots
 */
public class Visualize {

    private static final String CATALOG = "demo_ducklake";
    private static final String METADATABASE = "__ducklake_metadata_" + CATALOG;
    private static final String TABLE_NAME = "events";

    public static void main(String[] args) throws Exception {
        // Create temp directory for ducklake data
        Path tempDir = Files.createTempDirectory("ducklake_demo");
        Path catalogFile = tempDir.resolve(CATALOG + ".ducklake");
        Path dataPath = tempDir.resolve("data");
        Files.createDirectories(dataPath);

        System.out.println("=".repeat(80));
        System.out.println("DuckLake Demo");
        System.out.println("=".repeat(80));
        System.out.println("Catalog file: " + catalogFile);
        System.out.println("Data path: " + dataPath);
        System.out.println();

        try {
            // Attach ducklake catalog
            String attachDB = "ATTACH 'ducklake:%s' AS %s (DATA_PATH '%s');"
                    .formatted(catalogFile.toAbsolutePath(), CATALOG, dataPath.toAbsolutePath());
            ConnectionPool.execute(attachDB);
            System.out.println("✓ Attached DuckLake catalog: " + CATALOG);

            try (Connection conn = ConnectionPool.getConnection()) {
                // Use the catalog
                ConnectionPool.execute(conn, "USE " + CATALOG);

                // Print schema of ducklake_snapshot
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Schema of ducklake_snapshot:");
                System.out.println("-".repeat(80));
                printTableSchema(conn, "ducklake_snapshot");

                // Print snapshots before creating table
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Before creating table - ducklake_snapshot:");
                System.out.println("-".repeat(80));
                printSnapshots(conn);

                // Step 1: Create a table
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 1: Creating table '" + TABLE_NAME + "'");
                System.out.println("-".repeat(80));
                ConnectionPool.execute(conn,
                    "CREATE TABLE %s (id BIGINT, name VARCHAR, category VARCHAR)".formatted(TABLE_NAME));
                System.out.println("✓ Table created");

                // Print snapshots after creating table
                System.out.println("\nAfter creating table - ducklake_snapshot:");
                printSnapshots(conn);

                // Step 2: Alter table to add partition
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 2: Setting partition column");
                System.out.println("-".repeat(80));
                ConnectionPool.execute(conn,
                    "ALTER TABLE %s SET PARTITIONED BY (category)".formatted(TABLE_NAME));
                System.out.println("✓ Table partitioned by 'category'");

                System.out.println("\nAfter altering table - ducklake_snapshot:");
                printSnapshots(conn);

                // Step 3: Insert 3 entries (each in separate transaction to create separate files)
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 3: Inserting 3 entries");
                System.out.println("-".repeat(80));
                ConnectionPool.execute(conn,
                    "INSERT INTO %s VALUES (1, 'Alice', 'engineering')".formatted(TABLE_NAME));
                System.out.println("  Inserted: (1, 'Alice', 'engineering')");

                ConnectionPool.execute(conn,
                    "INSERT INTO %s VALUES (2, 'Bob', 'marketing')".formatted(TABLE_NAME));
                System.out.println("  Inserted: (2, 'Bob', 'marketing')");

                ConnectionPool.execute(conn,
                    "INSERT INTO %s VALUES (3, 'Charlie', 'sales')".formatted(TABLE_NAME));
                System.out.println("  Inserted: (3, 'Charlie', 'sales')");
                System.out.println("✓ 3 entries inserted");

                // Step 4: Print ducklake_data_file and snapshot contents
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 4: Contents of metadata tables (after inserts)");
                System.out.println("-".repeat(80));
                System.out.println("\nducklake_snapshot:");
                printSnapshots(conn);
                System.out.println("\nducklake_data_file:");
                printDataFiles(conn);
                System.out.println("\nducklake_file_column_stats:");
                printFileColumnStats(conn);

                // Step 5: Delete 1 entry
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 5: Deleting entry where id = 2");
                System.out.println("-".repeat(80));
                ConnectionPool.execute(conn,
                    "DELETE FROM %s WHERE id = 2".formatted(TABLE_NAME));
                System.out.println("✓ Deleted entry with id = 2");

                // Step 6: Print ducklake_data_file and ducklake_files_scheduled_for_deletion
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 6: Contents after delete");
                System.out.println("-".repeat(80));
                System.out.println("\nducklake_snapshot:");
                printSnapshots(conn);
                System.out.println("\nducklake_data_file:");
                printDataFiles(conn);
                System.out.println("\nducklake_files_scheduled_for_deletion:");
                printScheduledForDeletion(conn);

                // Step 7: Update an entry
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 7: Updating entry where id = 1 (changing category)");
                System.out.println("-".repeat(80));
                ConnectionPool.execute(conn,
                    "UPDATE %s SET category = 'management' WHERE id = 1".formatted(TABLE_NAME));
                System.out.println("✓ Updated Alice's category from 'engineering' to 'management'");

                // Print state after update
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 8: Contents after update");
                System.out.println("-".repeat(80));
                System.out.println("\nducklake_snapshot:");
                printSnapshots(conn);
                System.out.println("\nducklake_data_file:");
                printDataFiles(conn);
                System.out.println("\nducklake_files_scheduled_for_deletion:");
                printScheduledForDeletion(conn);

                // Step 9: Demo MergeTableOpsUtil.deleteDirectlyFromMetadata() method
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 9: Using MergeTableOpsUtil.deleteDirectlyFromMetadata() to mark files for deletion");
                System.out.println("-".repeat(80));

                // Get table ID for the drop operation
                String GET_TABLE_ID_QUERY = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
                Long tableId = ConnectionPool.collectFirst(conn,
                        GET_TABLE_ID_QUERY.formatted(METADATABASE, TABLE_NAME), Long.class);
                System.out.println("Table ID: " + tableId);

                // Use drop to mark files matching a filter for deletion
                // This will mark files that could contain category='sales' based on partition pruning
                String dropFilter = "SELECT * FROM %s WHERE category = 'sales'".formatted(TABLE_NAME);
                System.out.println("Filter: " + dropFilter);

                List<String> droppedFiles = MergeTableOpsUtil.deleteDirectlyFromMetadata(tableId, METADATABASE, dropFilter);
                System.out.println("✓ Marked " + droppedFiles.size() + " file(s) for deletion");
                for (String file : droppedFiles) {
                    String shortPath = file.length() > 50 ? "..." + file.substring(file.length() - 47) : file;
                    System.out.println("  - " + shortPath);
                }

                // Print state after deleteDirectlyFromMetadata
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 10: Contents after MergeTableOpsUtil.deleteDirectlyFromMetadata()");
                System.out.println("-".repeat(80));
                System.out.println("\nducklake_snapshot (new snapshot created for delete):");
                printSnapshots(conn);
                System.out.println("\nducklake_data_file (end_snapshot set on deleted files):");
                printDataFiles(conn);
                System.out.println("\nducklake_files_scheduled_for_deletion (still empty - need expire_snapshots):");
                printScheduledForDeletion(conn);

                // Step 11: Demo MergeTableOpsUtil.replace() method
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 11: Using MergeTableOpsUtil.replace() to swap files");
                System.out.println("-".repeat(80));

                // Insert a new entry to have something to replace
                ConnectionPool.execute(conn,
                    "INSERT INTO %s VALUES (4, 'Diana', 'hr')".formatted(TABLE_NAME));
                System.out.println("  Inserted: (4, 'Diana', 'hr')");

                // Create temp directory for merged files
                Path mergeDir = dataPath.resolve("merge_temp");
                Files.createDirectories(mergeDir);

                // Get active files for the table (files with end_snapshot IS NULL)
                List<String> activeFiles = new java.util.ArrayList<>();
                String activeFilesQuery = "SELECT path FROM %s.ducklake_data_file WHERE table_id = %d AND end_snapshot IS NULL"
                        .formatted(METADATABASE, tableId);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(activeFilesQuery)) {
                    while (rs.next()) {
                        activeFiles.add(rs.getString("path"));
                    }
                }
                System.out.println("\nActive files before replace: " + activeFiles.size());

                // Create a temp table with same schema for the replace operation
                String tempTableName = "__temp_replace_" + tableId;
                ConnectionPool.execute(conn,
                    "CREATE OR REPLACE TABLE %s.%s AS SELECT * FROM %s.%s LIMIT 0"
                            .formatted(CATALOG, tempTableName, CATALOG, TABLE_NAME));
                Long tempTableId = ConnectionPool.collectFirst(conn,
                        "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'"
                                .formatted(METADATABASE, tempTableName), Long.class);
                System.out.println("Created temp table: " + tempTableName + " (id=" + tempTableId + ")");

                // Create a new merged parquet file using rewriteWithPartitionNoCommit
                // This merges all active files into one file partitioned by category
                Path mergeOutput = mergeDir.resolve("merged/");
                Files.createDirectories(mergeOutput);

                List<String> fullPaths = activeFiles.stream()
                        .map(f -> dataPath.resolve("main").resolve(TABLE_NAME).resolve(f).toString())
                        .collect(java.util.stream.Collectors.toList());

                List<String> newFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
                        fullPaths,
                        mergeOutput.toString(),
                        List.of("category")  // Partition by category
                );
                System.out.println("Created " + newFiles.size() + " new merged file(s)");
                for (String f : newFiles) {
                    String shortPath = f.length() > 60 ? "..." + f.substring(f.length() - 57) : f;
                    System.out.println("  - " + shortPath);
                }

                // Now use replace to:
                // 1. Add new merged files to temp table
                // 2. Move files from temp table to main table
                // 3. Mark old files with end_snapshot
                long replaceSnapshotId = MergeTableOpsUtil.replace(
                        CATALOG,
                        tableId,
                        tempTableId,
                        METADATABASE,
                        newFiles,          // Files to add
                        activeFiles        // Files to remove (mark with end_snapshot)
                );
                System.out.println("✓ Replace completed with snapshot ID: " + replaceSnapshotId);

                // Print state after replace
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 12: Contents after MergeTableOpsUtil.replace()");
                System.out.println("-".repeat(80));
                System.out.println("\nducklake_snapshot (new snapshot from replace):");
                printSnapshots(conn);
                System.out.println("\nducklake_data_file (old files have end_snapshot, new merged files active):");
                printDataFiles(conn);

                // Verify table contents are preserved
                System.out.println("\nTable contents after replace (should be unchanged):");
                printTableContents(conn);

                // Step 13: Expire old snapshots
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 13: Expiring old snapshots");
                System.out.println("-".repeat(80));
                // Expire snapshots older than now (this will expire all old snapshots)
                ConnectionPool.execute(conn,
                    "CALL ducklake_expire_snapshots('%s', older_than => now())".formatted(CATALOG));
                System.out.println("✓ Expired old snapshots");

                // Step 14: Print final state
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Step 14: Final state after expiring snapshots");
                System.out.println("-".repeat(80));
                System.out.println("\nducklake_snapshot:");
                printSnapshots(conn);
                System.out.println("\nducklake_data_file:");
                printDataFiles(conn);
                System.out.println("\nducklake_files_scheduled_for_deletion:");
                printScheduledForDeletion(conn);

                // Show final table contents
                System.out.println("\n" + "-".repeat(80));
                System.out.println("Final table contents:");
                System.out.println("-".repeat(80));
                printTableContents(conn);

                // Show file column stats
                System.out.println("\n" + "-".repeat(80));
                System.out.println("ducklake_file_column_stats:");
                System.out.println("-".repeat(80));
                printFileColumnStats(conn);
            }

            // Cleanup
            ConnectionPool.execute("DETACH " + CATALOG);
            System.out.println("\n" + "=".repeat(80));
            System.out.println("Demo completed successfully!");
            System.out.println("=".repeat(80));

        } finally {
            // Clean up temp directory
            deleteDirectory(tempDir);
        }
    }

    private static void printTableSchema(Connection conn, String tableName) throws Exception {
        String query = "DESCRIBE %s.%s".formatted(METADATABASE, tableName);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            System.out.printf("  %-25s %-30s %-10s%n", "column_name", "column_type", "null");
            System.out.println("  " + "-".repeat(65));
            while (rs.next()) {
                System.out.printf("  %-25s %-30s %-10s%n",
                        rs.getString("column_name"),
                        rs.getString("column_type"),
                        rs.getString("null"));
            }
        }
    }

    private static void printSnapshots(Connection conn) throws Exception {
        String query = "SELECT snapshot_id, snapshot_time, schema_version, next_catalog_id " +
                       "FROM %s.ducklake_snapshot ORDER BY snapshot_id".formatted(METADATABASE);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            System.out.printf("  %-12s %-30s %-15s %-15s%n",
                    "snapshot_id", "snapshot_time", "schema_version", "next_catalog_id");
            System.out.println("  " + "-".repeat(75));
            int count = 0;
            while (rs.next()) {
                System.out.printf("  %-12d %-30s %-15d %-15d%n",
                        rs.getLong("snapshot_id"),
                        rs.getTimestamp("snapshot_time"),
                        rs.getLong("schema_version"),
                        rs.getLong("next_catalog_id"));
                count++;
            }
            if (count == 0) {
                System.out.println("  (no entries)");
            }
        }
    }

    private static void printDataFiles(Connection conn) throws Exception {
        String query = "SELECT data_file_id, table_id, begin_snapshot, end_snapshot, path, file_size_bytes, record_count " +
                       "FROM %s.ducklake_data_file ORDER BY data_file_id".formatted(METADATABASE);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            System.out.printf("  %-10s %-8s %-12s %-10s %-40s %-12s %-10s%n",
                    "file_id", "table_id", "begin_snap", "end_snap", "path", "size_bytes", "records");
            System.out.println("  " + "-".repeat(105));
            int count = 0;
            while (rs.next()) {
                String path = rs.getString("path");
                // Truncate path for display
                if (path.length() > 38) {
                    path = "..." + path.substring(path.length() - 35);
                }
                Long endSnapshot = rs.getLong("end_snapshot");
                String endSnapshotStr = rs.wasNull() ? "NULL" : String.valueOf(endSnapshot);
                System.out.printf("  %-10d %-8d %-12d %-10s %-40s %-12d %-10d%n",
                        rs.getLong("data_file_id"),
                        rs.getLong("table_id"),
                        rs.getLong("begin_snapshot"),
                        endSnapshotStr,
                        path,
                        rs.getLong("file_size_bytes"),
                        rs.getLong("record_count"));
                count++;
            }
            if (count == 0) {
                System.out.println("  (no entries)");
            }
        }
    }

    private static void printScheduledForDeletion(Connection conn) throws Exception {
        String query = "SELECT data_file_id, path, schedule_start " +
                       "FROM %s.ducklake_files_scheduled_for_deletion ORDER BY data_file_id".formatted(METADATABASE);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            System.out.printf("  %-12s %-60s %-25s%n",
                    "data_file_id", "path", "schedule_start");
            System.out.println("  " + "-".repeat(100));
            int count = 0;
            while (rs.next()) {
                String path = rs.getString("path");
                // Truncate path for display
                if (path.length() > 58) {
                    path = "..." + path.substring(path.length() - 55);
                }
                System.out.printf("  %-12d %-60s %-25s%n",
                        rs.getLong("data_file_id"),
                        path,
                        rs.getTimestamp("schedule_start"));
                count++;
            }
            if (count == 0) {
                System.out.println("  (no entries)");
            }
        }
    }

    private static void printTableContents(Connection conn) throws Exception {
        String query = "SELECT * FROM %s ORDER BY id".formatted(TABLE_NAME);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            System.out.printf("  %-10s %-20s %-20s%n", "id", "name", "category");
            System.out.println("  " + "-".repeat(50));
            while (rs.next()) {
                System.out.printf("  %-10d %-20s %-20s%n",
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("category"));
            }
        }
    }

    private static void printFileColumnStats(Connection conn) throws Exception {
        String query = "SELECT * FROM %s.ducklake_file_column_stats ORDER BY data_file_id, column_id".formatted(METADATABASE);
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            var metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            // Print header
            StringBuilder header = new StringBuilder("  ");
            for (int i = 1; i <= columnCount; i++) {
                header.append(String.format("%-15s ", metaData.getColumnName(i)));
            }
            System.out.println(header);
            System.out.println("  " + "-".repeat(columnCount * 16));

            int count = 0;
            while (rs.next()) {
                StringBuilder row = new StringBuilder("  ");
                for (int i = 1; i <= columnCount; i++) {
                    String val = rs.getString(i);
                    if (val != null && val.length() > 13) {
                        val = val.substring(0, 10) + "...";
                    }
                    row.append(String.format("%-15s ", val != null ? val : "NULL"));
                }
                System.out.println(row);
                count++;
            }
            if (count == 0) {
                System.out.println("  (no entries)");
            }
        }
    }

    private static void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            // Ignore cleanup errors
                        }
                    });
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}
