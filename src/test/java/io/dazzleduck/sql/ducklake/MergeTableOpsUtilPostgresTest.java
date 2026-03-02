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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

        MetadataConfig.initPostgres(postgres.getHost(), postgres.getFirstMappedPort(),
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterEach
    void tearDown() {
        ConnectionPool.execute("DETACH " + catalog);
        MetadataConfig.reset();
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

    /**
     * Verifies that concurrent calls to replace() each receive a unique snapshot ID.
     * Each thread adds its own unique file with no removals; the INSERT...RETURNING path
     * in MetadataConfig.isPostgres() mode guarantees the IDs are distinct even under
     * simultaneous writers.
     */
    @Test
    @Order(11)
    void testConcurrentReplaceProducesUniqueSnapshotIds() throws Exception {
        String tableName = "concurrent_test";
        int threadCount = 5;

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, val VARCHAR)".formatted(tableName),
                    "INSERT INTO %s SELECT i, 'v'||i FROM range(10) t(i)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            Long tableId = ConnectionPool.collectFirst(conn,
                    "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'".formatted(metadataDb, tableName),
                    Long.class);
            assertNotNull(tableId);

            // Pre-create N unique parquet files (one per thread) outside the race window
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Path f = dataPath.resolve("main").resolve(tableName).resolve("extra_" + i + ".parquet");
                Files.createDirectories(f.getParent());
                ConnectionPool.execute(conn,
                        "COPY (SELECT %d AS id, 'extra' AS val) TO '%s' (FORMAT PARQUET)".formatted(i, f));
                files.add(f);
            }

            // Launch N threads, all waiting on startLatch before calling replace()
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch  = new CountDownLatch(threadCount);
            Set<Long>        snapshotIds = ConcurrentHashMap.newKeySet();
            List<Exception>  errors      = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                final Path file = files.get(i);
                final long tid  = tableId;
                Thread t = new Thread(() -> {
                    try {
                        startLatch.await();
                        long sid = MergeTableOpsUtil.replace(
                                catalog, tid, metadataDb,
                                List.of(file.toString()),
                                List.of()  // each thread only adds; no removals
                        );
                        snapshotIds.add(sid);
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
                t.start();
            }

            startLatch.countDown();  // release all threads simultaneously
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads should complete within 30 s");

            assertTrue(errors.isEmpty(), "No thread should have thrown: " + errors);
            assertEquals(threadCount, snapshotIds.size(),
                    "Every concurrent replace() must return a distinct snapshot ID; got: " + snapshotIds);
        }
    }

    /**
     * End-to-end interoperability test: DuckDB inserts rows (native snapshot path),
     * then the library calls replace() via the direct Postgres JDBC connection,
     * and DuckDB subsequently reads back the table without loss of data.
     */
    @Test
    @Order(12)
    void testDuckDbWritesThenLibraryReplaceIsVisibleToDuckDb() throws Exception {
        String tableName = "interop_test";

        try (Connection conn = ConnectionPool.getConnection()) {
            // Phase 1: DuckDB creates snapshots via its native write path
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, name VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'alice')".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'bob')".formatted(tableName),
                    "INSERT INTO %s VALUES (3,'carol')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            Long tableId = ConnectionPool.collectFirst(conn,
                    "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'".formatted(metadataDb, tableName),
                    Long.class);
            assertNotNull(tableId);

            // Confirm DuckDB sees all 3 rows before the library touches anything
            Long beforeCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.%s".formatted(catalog, tableName), Long.class);
            assertEquals(3L, beforeCount, "DuckDB should see 3 rows before replace");

            // Collect the active file paths that the library will retire
            List<String> existingPaths = new ArrayList<>();
            ConnectionPool.collectFirstColumn(conn,
                    "SELECT path FROM %s.ducklake_data_file WHERE table_id=%s AND end_snapshot IS NULL"
                            .formatted(metadataDb, tableId),
                    String.class
            ).forEach(existingPaths::add);
            assertFalse(existingPaths.isEmpty(), "Should have active files from DuckDB inserts");

            // Phase 2: compact all rows into a single file using DuckDB COPY, then commit via library
            Path merged = dataPath.resolve("main").resolve(tableName).resolve("merged.parquet");
            Files.createDirectories(merged.getParent());
            ConnectionPool.execute(conn,
                    "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)".formatted(catalog, tableName, merged));

            List<String> toRemove = existingPaths.stream()
                    .map(p -> p.substring(p.lastIndexOf('/') + 1))
                    .toList();

            long snapshotId = MergeTableOpsUtil.replace(
                    catalog, tableId, metadataDb,
                    List.of(merged.toString()),
                    toRemove
            );
            assertTrue(snapshotId > 0, "Library replace() should return a valid snapshot ID");

            // Phase 3: DuckDB reads the table again — must still see all 3 rows
            Long afterCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.%s".formatted(catalog, tableName), Long.class);
            assertEquals(3L, afterCount,
                    "DuckDB must see all rows after library replace(); got " + afterCount);

            // Snapshot written by the library must exist in Postgres metadata
            Long snapshotExists = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_snapshot WHERE snapshot_id=%s".formatted(metadataDb, snapshotId), Long.class);
            assertEquals(1L, snapshotExists,
                    "Library snapshot ID " + snapshotId + " must exist in Postgres ducklake_snapshot table");

            // Merged file must be active (end_snapshot IS NULL), scoped to this table
            Long mergedActive = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id=%s AND path LIKE '%%merged.parquet' AND end_snapshot IS NULL"
                            .formatted(metadataDb, tableId), Long.class);
            assertEquals(1L, mergedActive, "Merged file should be active in metadata");

            // Original DuckDB-written files must be retired (end_snapshot = snapshotId)
            Long retiredCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id=%s AND end_snapshot=%s"
                            .formatted(metadataDb, tableId, snapshotId), Long.class);
            assertEquals((long) existingPaths.size(), retiredCount,
                    "All DuckDB-written files should be retired at the library snapshot");
        }
    }

    /**
     * NONE case: all input files are plain INSERT-generated files that lack
     * {@code _ducklake_internal_row_id} / {@code _ducklake_internal_snapshot_id}.
     * Verifies that {@code rewriteWithPartitionNoCommit} synthesises both columns
     * correctly from {@code ducklake_data_file} metadata.
     */
    @Test
    @Order(13)
    void testRewriteSynthesisesInternalColumns_noneCase() throws Exception {
        String tableName = "synthesis_none";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, val VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (1, 'alpha')".formatted(tableName),
                    "INSERT INTO %s VALUES (2, 'beta')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            Long tableId = ConnectionPool.collectFirst(conn,
                    "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'".formatted(metadataDb, tableName),
                    Long.class);
            assertNotNull(tableId);

            // DuckLake writes data files to DATA_PATH/main/<table>/; walk that directory for absolute paths
            List<String> inputPaths = Files.walk(dataPath.resolve("main").resolve(tableName))
                    .filter(p -> p.toString().endsWith(".parquet"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertFalse(inputPaths.isEmpty(), "Should have active DuckLake-managed files");

            // Pre-condition: INSERT-generated files have no internal columns
            String inList = inputPaths.stream().map(f -> "'" + f + "'").collect(Collectors.joining(", "));
            Long internalInInput = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM parquet_schema([%s]) WHERE name = '_ducklake_internal_row_id'".formatted(inList),
                    Long.class);
            assertEquals(0L, internalInInput, "INSERT files must not have _ducklake_internal_row_id before synthesis");

            // Rewrite with synthesis
            Path outDir = dataPath.resolve("out_none");
            List<String> outFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
                    inputPaths, outDir.toString(), List.of(), metadataDb);
            assertFalse(outFiles.isEmpty(), "Should produce output files");

            String outList = outFiles.stream().map(f -> "'" + f + "'").collect(Collectors.joining(", "));

            // Every output file must now carry the internal columns
            Long filesWithCols = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(DISTINCT file_name) FROM parquet_schema([%s]) WHERE name = '_ducklake_internal_row_id'"
                            .formatted(outList),
                    Long.class);
            assertEquals((long) outFiles.size(), filesWithCols, "Every output file must have _ducklake_internal_row_id");

            // No NULLs in synthesised values
            Long nullSnapshots = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s]) WHERE _ducklake_internal_snapshot_id IS NULL".formatted(outList),
                    Long.class);
            assertEquals(0L, nullSnapshots, "No row may have a NULL snapshot ID");

            Long nullRowIds = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s]) WHERE _ducklake_internal_row_id IS NULL".formatted(outList),
                    Long.class);
            assertEquals(0L, nullRowIds, "No row may have a NULL row ID");

            // Row IDs must be unique across all output rows
            Long totalRows = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s])".formatted(outList), Long.class);
            Long distinctRowIds = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(DISTINCT _ducklake_internal_row_id) FROM read_parquet([%s])".formatted(outList),
                    Long.class);
            assertEquals(totalRows, distinctRowIds, "Every output row must have a unique _ducklake_internal_row_id");
            assertEquals(2L, totalRows, "Row count must be preserved");

            // Snapshot IDs must match begin_snapshot values from the source input files
            Long invalidSnapshots = ConnectionPool.collectFirst(conn,
                    ("SELECT COUNT(*) FROM read_parquet([%s]) r "
                     + "WHERE r._ducklake_internal_snapshot_id NOT IN ("
                     + "  SELECT begin_snapshot FROM %s.ducklake_data_file "
                     + "  WHERE table_id=%s AND end_snapshot IS NULL)")
                            .formatted(outList, metadataDb, tableId),
                    Long.class);
            assertEquals(0L, invalidSnapshots, "Snapshot IDs must match begin_snapshot from ducklake_data_file");
        }
    }

    /**
     * ALL case: every input file already carries {@code _ducklake_internal_*} columns.
     * Verifies that existing values pass through the rewrite unchanged.
     */
    @Test
    @Order(14)
    void testRewritePassesThroughInternalColumns_allCase() throws Exception {
        try (Connection conn = ConnectionPool.getConnection()) {
            // Create two Parquet files with hard-coded internal column values (not registered in DuckLake)
            Path srcDir = dataPath.resolve("src_all");
            Files.createDirectories(srcDir);
            Path fileA = srcDir.resolve("wc_a.parquet");
            Path fileB = srcDir.resolve("wc_b.parquet");

            ConnectionPool.execute(conn,
                    ("COPY (SELECT 1 AS id, 'x' AS val, "
                     + "CAST(7 AS BIGINT) AS _ducklake_internal_snapshot_id, "
                     + "CAST(100 AS BIGINT) AS _ducklake_internal_row_id) "
                     + "TO '%s' (FORMAT PARQUET)").formatted(fileA));
            ConnectionPool.execute(conn,
                    ("COPY (SELECT 2 AS id, 'y' AS val, "
                     + "CAST(7 AS BIGINT) AS _ducklake_internal_snapshot_id, "
                     + "CAST(101 AS BIGINT) AS _ducklake_internal_row_id) "
                     + "TO '%s' (FORMAT PARQUET)").formatted(fileB));

            // Rewrite — ALL case: no metadata lookup required
            Path outDir = dataPath.resolve("out_all");
            List<String> outFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
                    List.of(fileA.toString(), fileB.toString()), outDir.toString(), List.of(), metadataDb);
            assertFalse(outFiles.isEmpty(), "Should produce output files");

            String outList = outFiles.stream().map(f -> "'" + f + "'").collect(Collectors.joining(", "));

            // Internal column values must be identical to the input
            Long wrongSnapshot = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s]) WHERE _ducklake_internal_snapshot_id != 7".formatted(outList),
                    Long.class);
            assertEquals(0L, wrongSnapshot, "Snapshot IDs must pass through unchanged (expected 7)");

            Long wrongRowId = ConnectionPool.collectFirst(conn,
                    ("SELECT COUNT(*) FROM read_parquet([%s]) "
                     + "WHERE _ducklake_internal_row_id NOT IN (100, 101)").formatted(outList),
                    Long.class);
            assertEquals(0L, wrongRowId, "Row IDs must pass through unchanged (expected 100 and 101)");

            Long rowCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s])".formatted(outList), Long.class);
            assertEquals(2L, rowCount, "Row count must be preserved");
        }
    }

    /**
     * MIXED case: some input files have {@code _ducklake_internal_*} columns, some do not.
     * Verifies that existing values pass through and missing values are synthesised from metadata.
     */
    @Test
    @Order(15)
    void testRewriteHandlesMixedFiles_mixedCase() throws Exception {
        String tableName = "synthesis_mixed";

        try (Connection conn = ConnectionPool.getConnection()) {
            // INSERT-generated DuckLake files — no internal columns, but registered in ducklake_data_file
            String[] setup = {
                    "USE " + catalog,
                    "CREATE TABLE %s (id INT, val VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (10, 'ten')".formatted(tableName),
                    "INSERT INTO %s VALUES (20, 'twenty')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            Long tableId = ConnectionPool.collectFirst(conn,
                    "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'".formatted(metadataDb, tableName),
                    Long.class);
            assertNotNull(tableId);

            // DuckLake writes data files to DATA_PATH/main/<table>/; walk that directory for absolute paths
            List<String> insertPaths = Files.walk(dataPath.resolve("main").resolve(tableName))
                    .filter(p -> p.toString().endsWith(".parquet"))
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertFalse(insertPaths.isEmpty(), "Should have active DuckLake-managed files");

            // Manually create an extra file that already has internal column values (not in DuckLake metadata)
            Path extraDir = dataPath.resolve("extra_mixed");
            Files.createDirectories(extraDir);
            Path fileWithCols = extraDir.resolve("with_internal.parquet");
            ConnectionPool.execute(conn,
                    ("COPY (SELECT 99 AS id, 'existing' AS val, "
                     + "CAST(42 AS BIGINT) AS _ducklake_internal_snapshot_id, "
                     + "CAST(9999 AS BIGINT) AS _ducklake_internal_row_id) "
                     + "TO '%s' (FORMAT PARQUET)").formatted(fileWithCols));

            // Build mixed input: INSERT files (no internal cols) + manually created file (has internal cols)
            List<String> mixedInput = new ArrayList<>(insertPaths);
            mixedInput.add(fileWithCols.toString());

            // Rewrite — MIXED case: synthesise for INSERT files, pass through for fileWithCols
            Path outDir = dataPath.resolve("out_mixed");
            List<String> outFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
                    mixedInput, outDir.toString(), List.of(), metadataDb);
            assertFalse(outFiles.isEmpty(), "Should produce output files");

            String outList = outFiles.stream().map(f -> "'" + f + "'").collect(Collectors.joining(", "));

            // All rows must have non-null internal columns
            Long nullSnapshots = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s]) WHERE _ducklake_internal_snapshot_id IS NULL".formatted(outList),
                    Long.class);
            assertEquals(0L, nullSnapshots, "No row may have a NULL snapshot ID");

            Long nullRowIds = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s]) WHERE _ducklake_internal_row_id IS NULL".formatted(outList),
                    Long.class);
            assertEquals(0L, nullRowIds, "No row may have a NULL row ID");

            // The pre-existing row (id=99) must keep its original internal column values unchanged
            Long preservedRow = ConnectionPool.collectFirst(conn,
                    ("SELECT COUNT(*) FROM read_parquet([%s]) "
                     + "WHERE id=99 AND _ducklake_internal_snapshot_id=42 AND _ducklake_internal_row_id=9999")
                            .formatted(outList),
                    Long.class);
            assertEquals(1L, preservedRow, "Pre-existing internal column values must pass through unchanged");

            // INSERT rows must have synthesised snapshot IDs from ducklake_data_file
            Long invalidSnapshots = ConnectionPool.collectFirst(conn,
                    ("SELECT COUNT(*) FROM read_parquet([%s]) r "
                     + "WHERE id != 99 "
                     + "AND r._ducklake_internal_snapshot_id NOT IN ("
                     + "  SELECT begin_snapshot FROM %s.ducklake_data_file "
                     + "  WHERE table_id=%s AND end_snapshot IS NULL)")
                            .formatted(outList, metadataDb, tableId),
                    Long.class);
            assertEquals(0L, invalidSnapshots, "INSERT rows must have synthesised snapshot IDs from metadata");

            // Row IDs must be globally unique across all output rows
            Long totalRows = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([%s])".formatted(outList), Long.class);
            Long distinctRowIds = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(DISTINCT _ducklake_internal_row_id) FROM read_parquet([%s])".formatted(outList),
                    Long.class);
            assertEquals(totalRows, distinctRowIds, "Every output row must have a unique _ducklake_internal_row_id");
            assertEquals(3L, totalRows, "Row count must be preserved (2 INSERT rows + 1 pre-existing row)");
        }
    }
}