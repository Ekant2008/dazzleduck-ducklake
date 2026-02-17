package io.dazzleduck.sql.ducklake;

import io.dazzleduck.sql.commons.ConnectionPool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MergeTableOpsUtil using PostgreSQL 17.5 testcontainer.
 * Tests PostgreSQL-based ducklake table management operations.
 *
 *  * Key differences from local DuckDB tests:
 * - Metadata is stored in PostgreSQL instead of local DuckDB database
 * - Data files remain local (in tempDir)
 * - Uses ATTACH with postgres: connection string
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MergeTableOpsUtilPostgresTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5").withDatabaseName("testdb").withUsername("test").withPassword("test");

    @TempDir
    Path tempDir;

    private String catalog;
    private String metadataDb;
    private Path catalogFile;
    private Path dataPath;

    @BeforeEach
    void setup() throws Exception {
        catalog = "pg_ducklake";
        metadataDb = "__ducklake_metadata_" + catalog;

        catalogFile = tempDir.resolve(catalog + ".ducklake");
        dataPath = tempDir.resolve("data");
        Files.createDirectories(dataPath);

        // Attach ducklake catalog with PostgreSQL as metadata backend
        // Data files are stored locally, metadata is stored in PostgreSQL
        String attach = "ATTACH 'ducklake:postgres:dbname=%s user=%s password=%s host=%s port=%d'AS %s(DATA_PATH '%s', OVERRIDE_DATA_PATH true);".formatted(postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword(), postgres.getHost(), postgres.getFirstMappedPort(), catalog, dataPath.toAbsolutePath());
        ConnectionPool.execute(attach);
    }

    @AfterEach
    void tearDown() {
            ConnectionPool.execute("DETACH " + catalog);
    }

    @Test
    @Order(1)
    void testReplaceHappyPath_withPostgresPresent() throws Exception {
        String tableName = "products";

        try (Connection conn = ConnectionPool.getConnection()) {
            // Create table with data
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, name VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'A')".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'B')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            // Get table ID from PostgreSQL metadata
            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadataDb, tableName), Long.class);
            assertNotNull(tableId, "Table ID should not be null");

            // Get original file paths from PostgreSQL metadata
            var filePathsIt = ConnectionPool.collectFirstColumn(conn, "SELECT path FROM %s.ducklake_data_file WHERE table_id = %s".formatted(metadataDb, tableId), String.class).iterator();

            List<String> originalFilePaths = new ArrayList<>();
            while (filePathsIt.hasNext()) {
                originalFilePaths.add(filePathsIt.next());
            }

            assertFalse(originalFilePaths.isEmpty(), "Should have at least 1 file from INSERTs");

            // Create merged file - read from table, not individual files
            Path mergedFile = dataPath.resolve("main").resolve(tableName).resolve("merged.parquet");
            Files.createDirectories(mergedFile.getParent());

            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)".formatted(catalog, tableName, mergedFile));

            // Extract just filenames for removal
            List<String> fileNamesToRemove = originalFilePaths.stream()
                    .map(p -> {
                        int lastSlash = p.lastIndexOf('/');
                        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
                    }).toList();

            // Execute replace operation
            long snapshotId = MergeTableOpsUtil.replace(
                    catalog,
                    tableId,
                    metadataDb,
                    List.of(mergedFile.toString()),
                    fileNamesToRemove
            );

            assertTrue(snapshotId > 0, "Should return valid snapshot ID");

            // Verify merged file is registered in PostgreSQL metadata (active - end_snapshot IS NULL)
            Long newFileCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE path LIKE '%%merged.parquet%%' AND end_snapshot IS NULL".formatted(metadataDb), Long.class);
            assertEquals(1L, newFileCount, "Merged file should be registered as active");

            // Verify old files have end_snapshot set (not immediately scheduled for deletion)
            Long oldFilesWithEndSnapshot = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id = %s AND end_snapshot = %s".formatted(metadataDb, tableId, snapshotId), Long.class);
            assertEquals((long) originalFilePaths.size(), oldFilesWithEndSnapshot, "Old files should have end_snapshot set");

            // Files should NOT be scheduled for deletion yet (need expire_snapshots)
            Long scheduledCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM %s.ducklake_files_scheduled_for_deletion".formatted(metadataDb), Long.class);
            assertEquals(0L, scheduledCount, "Files should NOT be scheduled for deletion until expire_snapshots is called");

            // Verify table data integrity
            Long rowCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM %s.%s".formatted(catalog, tableName), Long.class);
            assertEquals(2L, rowCount, "Table should contain all rows");
        }
    }

    @Test
    @Order(2)
    void testReplaceWithMissingFile_postgres() throws Exception {
        String tableName = "products_missing";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, name VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'A')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadataDb, tableName), Long.class);

            // Get existing file from PostgreSQL metadata
            var fileIt = ConnectionPool.collectFirstColumn(conn, "SELECT path FROM %s.ducklake_data_file WHERE table_id = %s".formatted(metadataDb, tableId), String.class).iterator();

            List<String> existingFiles = new ArrayList<>();
            while (fileIt.hasNext()) {
                existingFiles.add(fileIt.next());
            }

            assertFalse(existingFiles.isEmpty(), "Should have at least one file");

            // Create merged file
            Path mergedFile = dataPath.resolve("main").resolve(tableName).resolve("merged.parquet");
            Files.createDirectories(mergedFile.getParent());

            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)".formatted(catalog, tableName, mergedFile));

            // Extract filename
            String existingFileName = existingFiles.getFirst();
            int lastSlash = existingFileName.lastIndexOf('/');
            if (lastSlash >= 0) {
                existingFileName = existingFileName.substring(lastSlash + 1);
            }

            String finalExistingFileName = existingFileName;

            // Try to replace with a non-existent file - should throw exception
            Exception ex = assertThrows(
                    Exception.class,
                    () -> MergeTableOpsUtil.replace(
                            catalog,
                            tableId,
                            metadataDb,
                            List.of(mergedFile.toString()),
                            List.of(finalExistingFileName, "does_not_exist.parquet")
                    )
            );

            String errorMsg = ex.getMessage();
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
                errorMsg = ex.getCause().getMessage();
            }
            assertTrue(errorMsg.contains("One or more files scheduled for deletion were not found"), "Should throw error for missing file");
        }
    }

    @Test
    @Order(3)
    void testRewriteWithPartition_postgres() throws Exception {
        String tableName = "partitioned_products";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, category VARCHAR, date DATE)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'A','2025-01-01')".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'A','2025-01-01')".formatted(tableName),
                    "INSERT INTO %s VALUES (3,'B','2025-01-02')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            // Export table to parquet file
            Path export = dataPath.resolve("main").resolve(tableName).resolve("export.parquet");
            Files.createDirectories(export.getParent());

            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)".formatted(catalog, tableName, export));

            // Rewrite with partitioning
            Path baseLocation = dataPath.resolve("main").resolve(tableName).resolve("partitioned");

            List<String> files = MergeTableOpsUtil.rewriteWithPartitionNoCommit(List.of(export.toString()), baseLocation.toString(), List.of("date", "category"));

            assertFalse(files.isEmpty(), "Should create partitioned files");

            // Verify partition structure
            for (String f : files) {
                assertTrue(f.contains("date="), "File should contain date partition");
                assertTrue(f.contains("category="), "File should contain category partition");
            }

            // Verify row count preserved
            String fileList = files.stream().map(f -> "'" + f + "'").collect(Collectors.joining(","));

            Long rowCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM read_parquet([" + fileList + "])", Long.class);

            assertEquals(3L, rowCount, "All rows should be preserved");
        }
    }

    @Test
    @Order(4)
    void testRewriteWithoutPartition_postgres() throws Exception {
        String tableName = "unpartitioned_products";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT)".formatted(tableName),
                    "INSERT INTO %s VALUES (1)".formatted(tableName),
                    "INSERT INTO %s VALUES (2)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            // Export original parquet
            Path export = dataPath.resolve("main").resolve(tableName).resolve("export.parquet");
            Files.createDirectories(export.getParent());

            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)".formatted(catalog, tableName, export));

            // Rewrite to a different output location without partitioning
            Path output = dataPath.resolve("main").resolve(tableName).resolve("merged.parquet");

            List<String> files = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
                    List.of(export.toString()),
                    output.toString(),
                    List.of() // no partition
            );

            assertFalse(files.isEmpty(), "Should create output file");

            // Verify no partition markers in paths
            for (String f : files) {
                assertFalse(f.contains("="), "Unpartitioned rewrite must not create partitions");
            }

            // Verify row count
            String fileList = files.stream().map(f -> "'" + f + "'").collect(Collectors.joining(","));

            Long rowCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM read_parquet([" + fileList + "])", Long.class);

            assertEquals(2L, rowCount, "All rows should be preserved");
        }
    }

    @Test
    @Order(5)
    void testListFiles_postgres() throws Exception {
        String tableName = "file_listing";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, data VARCHAR)".formatted(tableName),
                    "INSERT INTO %s SELECT i, 'data' || i FROM range(100) t(i)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            // Get table ID
            String GET_TABLE_ID_QUERY = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID_QUERY.formatted(metadataDb, tableName), Long.class);

            // List files from PostgreSQL metadata
            var files = MergeTableOpsUtil.listFiles(
                    metadataDb,
                    tableId,
                    100L,
                    1_000_000L
            );

            assertFalse(files.isEmpty(), "Should find files in size range");

            // Verify all files meet size requirements
            for (var f : files) {
                assertTrue(f.size() >= 100L, "File should be >= min size");
                assertTrue(f.size() <= 1_000_000L, "File should be <= max size");
            }
        }
    }

    @Test
    @Order(6)
    void testReplaceWithEmptyAddList_postgres() throws Exception {
        String tableName = "empty_add";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT)".formatted(tableName),
                    "INSERT INTO %s VALUES (1)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadataDb, tableName), Long.class);

            // Get file paths from PostgreSQL metadata
            var it = ConnectionPool.collectFirstColumn(conn, "SELECT path FROM %s.ducklake_data_file WHERE table_id = %s".formatted(metadataDb, tableId), String.class).iterator();

            List<String> names = new ArrayList<>();
            while (it.hasNext()) {
                String p = it.next();
                names.add(p.substring(p.lastIndexOf('/') + 1));
            }

            // Replace with empty add list (only removal)
            long snapshotId = MergeTableOpsUtil.replace(
                    catalog,
                    tableId,
                    metadataDb,
                    List.of(), // Empty add list
                    names
            );

            assertTrue(snapshotId > 0, "Should return valid snapshot ID");

            // Verify files have end_snapshot set (not deleted)
            Long filesWithEndSnapshot = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id=%s AND end_snapshot = %s".formatted(metadataDb, tableId, snapshotId), Long.class);
            assertEquals((long) names.size(), filesWithEndSnapshot, "All files should have end_snapshot set");

            // Active files count should be 0
            Long activeCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id=%s AND end_snapshot IS NULL".formatted(metadataDb, tableId), Long.class);
            assertEquals(0L, activeCount, "No active files should remain");

            // Files should NOT be scheduled for deletion yet (need expire_snapshots)
            Long scheduledCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM %s.ducklake_files_scheduled_for_deletion".formatted(metadataDb), Long.class);
            assertEquals(0L, scheduledCount, "Files should NOT be scheduled for deletion until expire_snapshots is called");
        }
    }

    @Test
    @Order(7)
    void testReplaceWithNullArguments() {
        // Test all invalid argument combinations
        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.replace(null, 1L, "md", List.of(), List.of()),
                "Null database should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.replace("", 1L, "md", List.of(), List.of()),
                "Blank database should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.replace("db", 1L, null, List.of(), List.of()),
                "Null metadata database should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.replace("db", 1L, "", List.of(), List.of()),
                "Blank metadata database should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.replace("db", 1L, "md", null, List.of()),
                "Null toAdd list should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.replace("db", 1L, "md", List.of(), null),
                "Null toRemove list should throw exception"
        );
    }

    @Test
    @Order(8)
    void testRewriteWithPartitionNullArguments() {
        // Test invalid arguments for rewrite operations
        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.rewriteWithPartitionNoCommit((List<String>) null, "/base", List.of("col")),
                "Null input files should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.rewriteWithPartitionNoCommit(List.of(), "/base", List.of("col")),
                "Empty input files should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.rewriteWithPartitionNoCommit(List.of("/file1"), null, List.of("col")),
                "Null base location should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.rewriteWithPartitionNoCommit(List.of("/file1"), "", List.of("col")),
                "Blank base location should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.rewriteWithPartitionNoCommit(List.of("/file1"), "/base", null),
                "Null partition list should throw exception"
        );
    }

    @Test
    @Order(9)
    void testListFilesInvalidArguments() {
        // Test invalid arguments for listFiles
        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.listFiles(null, 1L, 0L, 1000L),
                "Null metadata database should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.listFiles("", 1L, 0L, 1000L),
                "Blank metadata database should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.listFiles("md", 1L, -1L, 1000L),
                "Negative minSize should throw exception"
        );

        assertThrows(IllegalArgumentException.class, () ->
                        MergeTableOpsUtil.listFiles("md", 1L, 1000L, 500L),
                "maxSize less than minSize should throw exception"
        );
    }

    @Test
    @Order(10)
    void testTransactionRollbackOnFailure() throws Exception {
        String tableName = "rollback_test";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT)".formatted(tableName),
                    "INSERT INTO %s VALUES (1)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadataDb, tableName), Long.class);

            // Count files before failed operation
            Long beforeCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id = %s".formatted(metadataDb, tableId), Long.class);

            // Create a new file for testing
            Path newFile = dataPath.resolve("main").resolve(tableName).resolve("new.parquet");
            Files.createDirectories(newFile.getParent());
            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)".formatted(catalog, tableName, newFile));

            // Try to replace with non-existent file - should rollback
            try {
                MergeTableOpsUtil.replace(
                        catalog,
                        tableId,
                        metadataDb,
                        List.of(newFile.toString()),
                        List.of("existing.parquet", "nonexistent.parquet")
                );
                fail("Should have thrown exception for missing file");
            } catch (IllegalStateException expected) {
                // Expected exception
            }

            // Verify metadata unchanged after rollback in PostgreSQL
            Long afterCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id = %s".formatted(metadataDb, tableId), Long.class);
            assertEquals(beforeCount, afterCount, "Transaction should have rolled back, file count should be unchanged");
        }
    }
}