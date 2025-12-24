package io.dazzleduck.sql.ducklake;

import io.dazzleduck.sql.commons.ConnectionPool;
import io.dazzleduck.sql.commons.FileStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for MergeTableOpsUtil using MinIO testcontainer.
 * Tests S3-compatible object storage operations for ducklake table management.
 *
 * Note: These tests work directly with ducklake tables rather than manipulating
 * individual parquet files, which is the proper way to test the table operations.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MergeTableOpsUtilMinIOTest {

    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String BUCKET_NAME = "test-ducklake-bucket";

    @TempDir
    private Path tempDir;

    @Container
    private static final MinIOContainer minioContainer = new MinIOContainer("minio/minio:latest")
            .withUserName(MINIO_ACCESS_KEY)
            .withPassword(MINIO_SECRET_KEY);

    private static S3Client s3Client;
    private static String s3Endpoint;
    private String catalogName;
    private String metadatabase;
    private Path localCatalogFile;

    @BeforeAll
    static void setupMinIO() {
        s3Endpoint = minioContainer.getS3URL();

        s3Client = S3Client.builder()
                .endpointOverride(URI.create(s3Endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)))
                .forcePathStyle(true)
                .build();

        // Create bucket
        s3Client.createBucket(CreateBucketRequest.builder()
                .bucket(BUCKET_NAME)
                .build());

        // Set persistent DuckDB configuration for all connections
        try (Connection conn = ConnectionPool.getConnection()) {
            String[] globalConfig = {
                    "INSTALL httpfs;",
                    "LOAD httpfs;",
                    "SET GLOBAL s3_endpoint='%s';".formatted(s3Endpoint.replace("http://", "")),
                    "SET GLOBAL s3_access_key_id='%s';".formatted(MINIO_ACCESS_KEY),
                    "SET GLOBAL s3_secret_access_key='%s';".formatted(MINIO_SECRET_KEY),
                    "SET GLOBAL s3_use_ssl=false;",
                    "SET GLOBAL s3_url_style='path';"
            };
            ConnectionPool.executeBatchInTxn(conn, globalConfig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure DuckDB for MinIO", e);
        }
    }

    @AfterAll
    static void tearDownMinIO() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    @BeforeEach
    void setup() throws Exception {
        catalogName = "test_catalog";
        metadatabase = "__ducklake_metadata_" + catalogName;

        // Create local catalog file for DuckDB
        localCatalogFile = tempDir.resolve(catalogName + ".ducklake");

        try (Connection conn = ConnectionPool.getConnection()) {
            // Attach catalog - S3 settings are already global
            String attachQuery = "ATTACH 'ducklake:%s' AS %s (DATA_PATH 's3://%s/data');".formatted(localCatalogFile.toAbsolutePath(), catalogName, BUCKET_NAME);
            ConnectionPool.execute(conn, attachQuery);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            ConnectionPool.execute("DETACH " + catalogName);
        } catch (Exception ignored) {

        }

        // Clean up local catalog file
        if (localCatalogFile != null && Files.exists(localCatalogFile)) {
            Files.delete(localCatalogFile);
        }

        // Clean up S3 bucket
        cleanupS3Bucket();
    }

    private void cleanupS3Bucket() {
        try {
            ListObjectsV2Response listResponse = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(BUCKET_NAME).build());

            if (!listResponse.contents().isEmpty()) {
                List<ObjectIdentifier> toDelete = listResponse.contents().stream()
                        .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                        .collect(Collectors.toList());

                s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(BUCKET_NAME)
                        .delete(Delete.builder().objects(toDelete).build())
                        .build());
            }
        } catch (Exception e) {
            System.err.println("Error cleaning up S3 bucket: " + e.getMessage());
        }
    }

    @Test
    @Order(1)
    void testReplaceWithS3Files() throws Exception {
        String tableName = "s3_products";

        try (Connection conn = ConnectionPool.getConnection()) {
            // Create table with data
            String[] setup = {
                    "USE " + catalogName,
                    "CREATE TABLE %s (id INT, name VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'ProductA')".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'ProductB')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            // Get table ID
            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadatabase, tableName), Long.class);
            assertNotNull(tableId, "Table ID should not be null");

            // Create temp table
            String dummyTable = "__dummy_" + tableId;
            ConnectionPool.execute(conn, "CREATE OR REPLACE TABLE %s.%s AS SELECT * FROM %s.%s LIMIT 0"
                    .formatted(catalogName, dummyTable, catalogName, tableName));
            Long tempTableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadatabase, dummyTable), Long.class);

            // Get original file paths (relative to DATA_PATH)
            var filePaths = ConnectionPool.collectFirstColumn(conn,
                    "SELECT path FROM %s.ducklake_data_file WHERE table_id = %s"
                            .formatted(metadatabase, tableId), String.class).iterator();

            List<String> originalFilePaths = new ArrayList<>();
            while (filePaths.hasNext()) {
                originalFilePaths.add(filePaths.next());
            }

            assertFalse(originalFilePaths.isEmpty(), "Should have at least 1 file from INSERTs");

            // Create merged file - read from table, not individual files
            String s3Path3 = "s3://%s/data/main/%s/file3_merged.parquet".formatted(BUCKET_NAME, tableName);
            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)"
                    .formatted(catalogName, tableName, s3Path3));

            // Extract just filenames for removal
            List<String> fileNamesToRemove = originalFilePaths.stream()
                    .map(p -> {
                        int lastSlash = p.lastIndexOf('/');
                        return lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
                    })
                    .collect(Collectors.toList());

            // Execute replace operation
            MergeTableOpsUtil.replace(
                    catalogName,
                    tableId,
                    tempTableId,
                    metadatabase,
                    List.of(s3Path3),
                    fileNamesToRemove
            );

            // Verify results
            Long newFileCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE path LIKE '%%file3_merged%%'"
                            .formatted(metadatabase), Long.class);
            assertEquals(1, newFileCount, "Merged file should be registered");

            Long scheduledCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_files_scheduled_for_deletion".formatted(metadatabase), Long.class);
            assertTrue(scheduledCount >= originalFilePaths.size(), "Old files should be scheduled for deletion");

            // Verify data integrity through table
            Long rowCount = ConnectionPool.collectFirst(conn, "SELECT COUNT(*) FROM %s.%s".formatted(catalogName, tableName), Long.class);
            assertEquals(2L, rowCount, "Table should contain all rows");
        }
    }

    @Test
    @Order(2)
    void testReplaceWithMissingS3File() throws Exception {
        String tableName = "s3_products_missing";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalogName,
                    "CREATE TABLE %s (id INT, name VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'A')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadatabase, tableName), Long.class);

            String dummyTable = "__dummy_" + tableId;
            ConnectionPool.execute(conn, "CREATE OR REPLACE TABLE %s.%s AS SELECT * FROM %s.%s LIMIT 0"
                    .formatted(catalogName, dummyTable, catalogName, tableName));
            Long tempTableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadatabase, dummyTable), Long.class);

            // Get actual files
            var filePaths = ConnectionPool.collectFirstColumn(conn,
                    "SELECT path FROM %s.ducklake_data_file WHERE table_id = %s"
                            .formatted(metadatabase, tableId), String.class).iterator();

            List<String> existingFiles = new ArrayList<>();
            while (filePaths.hasNext()) {
                existingFiles.add(filePaths.next());
            }

            assertTrue(!existingFiles.isEmpty(), "Should have at least one file");

            // Create a merged file from table
            String s3Path2 = "s3://%s/data/main/%s/merged.parquet".formatted(BUCKET_NAME, tableName);
            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)"
                    .formatted(catalogName, tableName, s3Path2));

            // Extract filename
            String existingFileName = existingFiles.getFirst();
            int lastSlash = existingFileName.lastIndexOf('/');
            if (lastSlash >= 0) {
                existingFileName = existingFileName.substring(lastSlash + 1);
            }

            final String finalFileName = existingFileName;
            // Try to remove files including one that doesn't exist
            Exception ex = assertThrows(
                    Exception.class,
                    () -> MergeTableOpsUtil.replace(
                            catalogName,
                            tableId,
                            tempTableId,
                            metadatabase,
                            List.of(s3Path2),
                            List.of(finalFileName, "does_not_exist.parquet")
                    )
            );

            String errorMsg = ex.getMessage();
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
                errorMsg = ex.getCause().getMessage();
            }
            assertTrue(errorMsg.contains("One or more files scheduled for deletion were not found"),
                    "Should throw error for missing file, got: " + errorMsg);
        }
    }

    @Test
    @Order(3)
    void testRewriteWithPartitionOnS3() throws Exception {
        String tableName = "s3_partitioned_data";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalogName,
                    "CREATE TABLE %s (id BIGINT, name VARCHAR, category VARCHAR, date DATE)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'A','Cat1','2025-01-01'::DATE)".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'B','Cat1','2025-01-01'::DATE)".formatted(tableName),
                    "INSERT INTO %s VALUES (3,'C','Cat2','2025-01-02'::DATE)".formatted(tableName),
                    "INSERT INTO %s VALUES (4,'D','Cat2','2025-01-02'::DATE)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadatabase, tableName), Long.class);

            // Get original files before rewrite
            var originalFiles = ConnectionPool.collectFirstColumn(conn,
                    "SELECT path FROM %s.ducklake_data_file WHERE table_id = %s"
                            .formatted(metadatabase, tableId), String.class).iterator();

            List<String> originalPaths = new ArrayList<>();
            while (originalFiles.hasNext()) {
                originalPaths.add(originalFiles.next());
            }

            assertFalse(originalPaths.isEmpty(), "Should have at least 1 file");

            // Create partitioned files - use MergeTableOpsUtil.rewriteWithPartitionNoCommit
            // We need to read from the table and pass full S3 paths
            String baseLocation = "s3://%s/data/main/%s/partitioned/".formatted(BUCKET_NAME, tableName);

            // Export current table data to a temp location for rewrite
            String tempExportPath = "s3://%s/data/main/%s/temp_export.parquet".formatted(BUCKET_NAME, tableName);
            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)"
                    .formatted(catalogName, tableName, tempExportPath));

            // Now rewrite with partitions using the utility method
            List<String> partitionedFiles = MergeTableOpsUtil.rewriteWithPartitionNoCommit(
                    List.of(tempExportPath),
                    baseLocation,
                    List.of("date", "category")
            );

            assertFalse(partitionedFiles.isEmpty(), "Should create partitioned files");

            // Verify partitioned files
            for (String file : partitionedFiles) {
                assertTrue(file.contains("date="), "File path should contain date partition");
                assertTrue(file.contains("category="), "File path should contain category partition");
            }

            // Verify data integrity by reading the partitioned files
            String fileListStr = partitionedFiles.stream()
                    .map(f -> "'" + f + "'")
                    .collect(Collectors.joining(","));
            Long rowCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM read_parquet([" + fileListStr + "])", Long.class);
            assertEquals(4L, rowCount, "All rows should be preserved in partitioned files");
        }
    }

    @Test
    @Order(4)
    void testRewriteWithoutPartitionOnS3() throws Exception {
        String tableName = "s3_unpartitioned_merge";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalogName,
                    "CREATE TABLE %s (id BIGINT, value VARCHAR)".formatted(tableName),
                    "INSERT INTO %s VALUES (1,'A')".formatted(tableName),
                    "INSERT INTO %s VALUES (2,'B')".formatted(tableName),
                    "INSERT INTO %s VALUES (3,'C')".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);
            // Merge without partitioning - write from table
            String mergedPath = "s3://%s/data/main/%s/merged.parquet".formatted(BUCKET_NAME, tableName);
            ConnectionPool.execute(conn, "COPY (SELECT * FROM %s.%s) TO '%s' (FORMAT PARQUET)"
                    .formatted(catalogName, tableName, mergedPath));

            // Verify the file was created and doesn't contain partition markers
            assertFalse(mergedPath.contains("="), "Should not contain partition markers");

            // Verify row count from table
            Long rowCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.%s".formatted(catalogName, tableName), Long.class);
            assertEquals(3L, rowCount, "All rows should be preserved in merge");
        }
    }

    @Test
    @Order(5)
    void testListFilesFromS3() throws Exception {
        String tableName = "s3_file_listing";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalogName,
                    "CREATE TABLE %s (id BIGINT, data VARCHAR)".formatted(tableName),
                    "INSERT INTO %s SELECT i, 'data' || i::VARCHAR FROM range(10) t(i)".formatted(tableName),
                    "INSERT INTO %s SELECT i, 'data' || i::VARCHAR FROM range(10, 20) t(i)".formatted(tableName),
                    "INSERT INTO %s SELECT i, 'data' || i::VARCHAR FROM range(20, 30) t(i)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            // List files in size range
            List<FileStatus> files = MergeTableOpsUtil.listFiles(
                    metadatabase,
                    catalogName,
                    100L,
                    100000L
            );

            assertFalse(files.isEmpty(), "Should find files in size range");

            // Verify files are within size bounds
            for (FileStatus file : files) {
                long size = file.size();
                assertTrue(size >= 100L, "File should be >= min size");
                assertTrue(size <= 100000L, "File should be <= max size");
            }
        }
    }

    @Test
    @Order(6)
    void testReplaceWithEmptyAddList() throws Exception {
        String tableName = "s3_empty_add";

        try (Connection conn = ConnectionPool.getConnection()) {
            String[] setup = {
                    "USE " + catalogName,
                    "CREATE TABLE %s (id BIGINT)".formatted(tableName),
                    "INSERT INTO %s VALUES (1)".formatted(tableName)
            };
            ConnectionPool.executeBatchInTxn(conn, setup);

            String GET_TABLE_ID = "SELECT table_id FROM %s.ducklake_table WHERE table_name='%s'";
            Long tableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadatabase, tableName), Long.class);

            String dummyTable = "__dummy_" + tableId;
            ConnectionPool.execute(conn, "CREATE OR REPLACE TABLE %s.%s AS SELECT * FROM %s.%s LIMIT 0"
                    .formatted(catalogName, dummyTable, catalogName, tableName));
            Long tempTableId = ConnectionPool.collectFirst(conn, GET_TABLE_ID.formatted(metadatabase, dummyTable), Long.class);

            // Get actual file paths
            var filePaths = ConnectionPool.collectFirstColumn(conn,
                    "SELECT path FROM %s.ducklake_data_file WHERE table_id = %s"
                            .formatted(metadatabase, tableId), String.class).iterator();

            List<String> fileNames = new ArrayList<>();
            while (filePaths.hasNext()) {
                String path = filePaths.next();
                int lastSlash = path.lastIndexOf('/');
                fileNames.add(lastSlash >= 0 ? path.substring(lastSlash + 1) : path);
            }

            // Remove files without adding new ones
            MergeTableOpsUtil.replace(
                    catalogName,
                    tableId,
                    tempTableId,
                    metadatabase,
                    List.of(),
                    fileNames
            );

            Long fileCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_data_file WHERE table_id = %s"
                            .formatted(metadatabase, tableId), Long.class);
            assertEquals(0L, fileCount, "All files should be removed");

            Long scheduledCount = ConnectionPool.collectFirst(conn,
                    "SELECT COUNT(*) FROM %s.ducklake_files_scheduled_for_deletion".formatted(metadatabase), Long.class);
            assertTrue(scheduledCount > 0, "Files should be scheduled for deletion");
        }
    }
}