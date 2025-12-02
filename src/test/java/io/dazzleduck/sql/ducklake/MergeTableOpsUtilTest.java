package io.dazzleduck.sql.ducklake;

import io.dazzleduck.sql.commons.ConnectionPool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class MergeTableOpsUtilTest {
    @TempDir
    Path projectTempDir;
    private Path catalogFile;
    private Path dataPath;
    private final String CATALOG = "test_ducklake";
    private final String METADATABASE = "__ducklake_metadata_" + CATALOG;

    private MergeTableOpsUtil mergeTableOpsUtil;

    @BeforeEach
    void setup() throws Exception {
        catalogFile = projectTempDir.resolve(CATALOG + ".ducklake");
        dataPath = projectTempDir.resolve("data");
        Files.createDirectories(dataPath);
        String attachDB ="ATTACH 'ducklake:%s' AS %s (DATA_PATH '%s');".formatted(catalogFile.toAbsolutePath(), CATALOG, dataPath.toAbsolutePath());
        ConnectionPool.execute(attachDB);
        mergeTableOpsUtil = new MergeTableOpsUtil();
    }

    @AfterEach
    void tearDown() throws Exception {
        ConnectionPool.execute("DETACH " + CATALOG);
    }

    @Test
    void testReplaceHappyPath() throws Exception {
        String tableName = "products";
        Path tableDir = dataPath.resolve("main").resolve(tableName);
        Files.createDirectories(tableDir);
        Path a = tableDir.resolve("a.parquet");
        Path b = tableDir.resolve("b.parquet");
        Path c = tableDir.resolve("c.parquet");
        Path d = tableDir.resolve("d.parquet");

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + CATALOG,
                    "CREATE TABLE %s AS SELECT * FROM (VALUES (1,'A'),(2,'B')) t(id,name)".formatted(tableName),
                    "COPY (SELECT 1 AS id, 'A' AS name) TO '%s' (FORMAT PARQUET)".formatted(a),
                    "COPY (SELECT 2 AS id, 'B' AS name) TO '%s' (FORMAT PARQUET)".formatted(b),
                    "COPY (SELECT 3 AS id, 'C' AS name) TO '%s' (FORMAT PARQUET)".formatted(c),
                    "COPY (SELECT 4 AS id, 'D' AS name) TO '%s' (FORMAT PARQUET)".formatted(d)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID_QUERY = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(GET_TABLE_ID_QUERY.formatted(METADATABASE, tableName), Long.class);
            // Create temp dummy table
            String dummyTable = "__dummy_" + tableId;
            ConnectionPool.execute("CREATE OR REPLACE TABLE %s.%s AS SELECT * FROM %s.%s LIMIT 0".formatted(CATALOG, dummyTable, CATALOG, tableName));
            Long tempTableId = ConnectionPool.collectFirst(GET_TABLE_ID_QUERY.formatted(METADATABASE, dummyTable), Long.class);
            // Register A & B as original files
            String ADD_DATA_FILES_QUERY = "CALL ducklake_add_data_files('%s','%s','%s')";
            ConnectionPool.executeBatchInTxn(conn, new String[] {ADD_DATA_FILES_QUERY.formatted(CATALOG, tableName, a), ADD_DATA_FILES_QUERY.formatted(CATALOG, tableName, b)});

            // Method under test
            mergeTableOpsUtil.replace(
                    tableId,
                    tempTableId,
                    CATALOG,
                    List.of(c.toString(), d.toString()),
                    List.of(a.getFileName().toString(), b.getFileName().toString())
            );
            // Validate new file exists
            Long newFileCount = ConnectionPool.collectFirst("SELECT COUNT(*) FROM %s.ducklake_data_file WHERE path LIKE '%%%s%%'".formatted(METADATABASE, c.getFileName()), Long.class);
            assertEquals(1, newFileCount, "Expected newly created file to be registered");
            // Old files removed
            Long oldCount = ConnectionPool.collectFirst("SELECT COUNT(*) FROM %s.ducklake_data_file WHERE path LIKE '%%%s%%' OR path LIKE '%%%s%%'".formatted(METADATABASE, a.getFileName(), b.getFileName()), Long.class);
            assertEquals(0, oldCount, "Old files must be removed from metadata");
            // Deleted scheduled
            Long scheduled = ConnectionPool.collectFirst("SELECT COUNT(*) FROM %s.ducklake_files_scheduled_for_deletion".formatted(METADATABASE), Long.class);
            assertEquals(2, scheduled, "Expected both files scheduled for deletion");
        }
    }

    // test where file a exist but b does not (unhappy path) it should abort with not all the files are found
    @Test
    void testOneFileNotExistUnhappyPath() throws SQLException, IOException {
        String tableName = "products";
        Path tableDir = dataPath.resolve("main").resolve(tableName);
        Files.createDirectories(tableDir);
        Path a = tableDir.resolve("a.parquet");
        // NO file b
        Path c = tableDir.resolve("c.parquet");

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + CATALOG,
                    "CREATE TABLE %s AS SELECT * FROM (VALUES (1,'A'),(2,'B')) t(id,name)".formatted(tableName),
                    "COPY (SELECT 1 AS id, 'A' AS name) TO '%s' (FORMAT PARQUET)".formatted(a),
                    "COPY (SELECT 3 AS id, 'C' AS name) TO '%s' (FORMAT PARQUET)".formatted(c)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID_QUERY = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(GET_TABLE_ID_QUERY.formatted(METADATABASE, tableName), Long.class);
            // Create temp dummy table
            String dummyTable = "__dummy_" + tableId;
            ConnectionPool.execute("CREATE OR REPLACE TABLE %s.%s AS SELECT * FROM %s.%s LIMIT 0".formatted(CATALOG, dummyTable, CATALOG, tableName));
            Long tempTableId = ConnectionPool.collectFirst(GET_TABLE_ID_QUERY.formatted(METADATABASE, dummyTable), Long.class);
            // Register A & B as original files
            String ADD_DATA_FILES_QUERY = "CALL ducklake_add_data_files('%s','%s','%s')";
            ConnectionPool.executeBatchInTxn(conn, new String[] {ADD_DATA_FILES_QUERY.formatted(CATALOG, tableName, a)});

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> mergeTableOpsUtil.replace(
                            tableId,
                            tempTableId,
                            CATALOG,
                            List.of(c.toString()),
                            List.of(a.getFileName().toString(), "b does not exist")
                    )
            );
            assertTrue(ex.getMessage().contains("One or more files scheduled for deletion were not found"), "Expected missing-file error");
        }
    }


    @Test
    void testRewriteWithPartitionNoCommit() throws Exception {

        String tableName = "products";
        Path tableDir = dataPath.resolve("main").resolve(tableName);
        Files.createDirectories(tableDir);

        try (Connection conn = ConnectionPool.getConnection()) {

            // Setup
            String createTable = "CREATE OR REPLACE TABLE %s (id BIGINT, name VARCHAR, category VARCHAR, created_at DATE);".formatted(tableName);
            String[] inserts = {
                    "USE " + CATALOG,
                    createTable,
                    "INSERT INTO %s VALUES (1,'A','Cat1',DATE '2025-01-01')".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'B','Cat2',DATE '2025-01-01')".formatted(tableName),
                    "INSERT INTO %s VALUES (3,'C','Cat1',DATE '2025-01-02')".formatted(tableName),
                    "INSERT INTO %s VALUES (4,'D','Cat3',DATE '2025-01-02')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, inserts);

            Long tableId = ConnectionPool.collectFirst("SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'".formatted(METADATABASE, tableName), Long.class);
            // Capture original files
            List<String> originalFiles = (List<String>) ConnectionPool.collectFirstColumn(conn, "SELECT CONCAT('%s\\', path) FROM %s.ducklake_data_file WHERE table_id = %s".formatted(tableDir.toString(), METADATABASE, tableId), String.class);
            assertEquals(4, originalFiles.size(), "Expected 4 original parquet files");

            Path baseLocation = tableDir.resolve("rewrite");
            Files.createDirectories(baseLocation);
            List<String> newFiles = mergeTableOpsUtil.rewriteWithPartitionNoCommit(
                    originalFiles,
                    baseLocation.toString(),
                    List.of("created_at", "category")
            );
            // Assert new files created
            assertFalse(newFiles.isEmpty(), "Expected new partitioned files");
            // Partition directories must exist
            assertTrue(Files.list(baseLocation).anyMatch(p -> p.getFileName().toString().contains("created_at=")), "Expected partition folders like created_at=yyyy-mm-dd");
            // Row count preserved
            String fileList = newFiles.stream().map(f -> "'" + f + "'").collect(Collectors.joining(","));

            Long rowCount = ConnectionPool.collectFirst(
                    "SELECT COUNT(*) FROM read_parquet([" + fileList + "])",
                    Long.class
            );

            assertEquals(4L, rowCount, "Total rows must be preserved during rewrite");

            // Metadata NOT changed (no commit)
            Long afterFileCount = ConnectionPool.collectFirst(
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id = %s"
                            .formatted(METADATABASE, tableId),
                    Long.class
            );

            assertEquals(4L, afterFileCount, "Metadata must not change for no-commit rewrite");
        }
    }

    @Test
    void testRewriteWithoutPartitionNoCommit() throws Exception {
        String tableName = "products";
        Path tableDir = dataPath.resolve("main").resolve(tableName);
        Files.createDirectories(tableDir);

        try (Connection conn = ConnectionPool.getConnection()) {
            String createTable = "CREATE OR REPLACE TABLE %s (id BIGINT, name VARCHAR, category VARCHAR, created_at DATE);".formatted(tableName);
            String[] inserts = {
                    "USE " + CATALOG,
                    createTable,
                    "INSERT INTO %s VALUES (1,'A','Cat1',DATE '2025-01-01')".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'B','Cat2',DATE '2025-01-01')".formatted(tableName),
                    "INSERT INTO %s VALUES (3,'C','Cat1',DATE '2025-01-02')".formatted(tableName),
                    "INSERT INTO %s VALUES (4,'D','Cat3',DATE '2025-01-02')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, inserts);

            Long tableId = ConnectionPool.collectFirst("SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'".formatted(METADATABASE, tableName), Long.class);
            List<String> originalFiles = (List<String>) ConnectionPool.collectFirstColumn(conn, "SELECT CONCAT('%s\\', path) FROM %s.ducklake_data_file WHERE table_id=%s".formatted(tableDir.toString(), METADATABASE, tableId), String.class);
            assertEquals(4, originalFiles.size(), "Expected 4 parquet files");

            Path baseLocation = tableDir.resolve("rewrite");
            Files.createDirectories(baseLocation);
            List<String> newFiles = mergeTableOpsUtil.rewriteWithPartitionNoCommit(
                    originalFiles,
                    baseLocation.resolve("merged.parquet").toString(),
                    List.of() // EMPTY PARTITION
            );
            // Files created
            assertFalse(newFiles.isEmpty(), "Expected rewritten parquet files");
            // Files exist
            for (String f : newFiles) assertTrue(Files.exists(Path.of(f)), "Missing output file: " + f);
            // No partition directories should exist
            boolean hasPartitions = Files.list(baseLocation).anyMatch(p -> p.getFileName().toString().contains("="));
            assertFalse(hasPartitions, "No PARTITION_BY should create no subfolders");
            // Row count preserved
            String fileList = newFiles.stream().map(f -> "'" + f + "'").collect(Collectors.joining(","));
            Long rowCount = ConnectionPool.collectFirst("SELECT COUNT(*) FROM read_parquet([" + fileList + "])", Long.class);
            assertEquals(4L, rowCount, "Row-count must remain unchanged");

            // Metadata unchanged (NoCommit)
            Long afterFileCount = ConnectionPool.collectFirst("SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id=%s".formatted(METADATABASE, tableId), Long.class);
            assertEquals(4L, afterFileCount, "No commit should modify metadata");
        }
    }

}
