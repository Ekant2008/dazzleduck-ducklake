package io.dazzleduck.sql.ducklake;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.dazzleduck.sql.commons.ConnectionPool;
import io.dazzleduck.sql.commons.FileStatus;
import io.dazzleduck.sql.commons.ducklake.DucklakePartitionPruning;
import io.dazzleduck.sql.commons.ingestion.CopyResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.stream.Collectors;

public class MergeTableOpsUtil {

    private static final String ADD_FILE_TO_TABLE_QUERY = "CALL ducklake_add_data_files('%s', '%s', '%s', schema => 'main', ignore_extra_columns => true, allow_missing => true);";
    private static final String COPY_TO_NEW_FILE_WITH_PARTITION_QUERY = "COPY (SELECT * FROM read_parquet([%s])) TO '%s' (FORMAT PARQUET,%s RETURN_FILES);";
    private static final String INSERT_INTO_SCHEDULE_FILE_DELETION_QUERY = "INSERT INTO %s.ducklake_files_scheduled_for_deletion(data_file_id, path, path_is_relative, schedule_start) SELECT data_file_id, path, path_is_relative, now() FROM %s.ducklake_data_file WHERE data_file_id IN (%s);";
    private static final String DELETE_FILE_COLUMN_STATS_QUERY = "DELETE FROM %s.ducklake_file_column_stats WHERE data_file_id IN (%s);";
    private static final String DELETE_FILE_PARTITION_VALUE_QUERY = "DELETE FROM %s.ducklake_file_partition_value WHERE data_file_id IN (%s);";
    private static final String DELETE_DATA_FILE_QUERY = "DELETE FROM %s.ducklake_data_file WHERE data_file_id IN (%s);";
    private static final String GET_FILE_ID_BY_PATH_QUERY = "SELECT data_file_id FROM %s.ducklake_data_file WHERE table_id = %s AND path IN (%s);";
    private static final String GET_TABLE_NAME_BY_ID =  "SELECT table_name FROM %s.ducklake_table WHERE table_id = '%s';";
    private static final String UPDATE_TABLE_ID =  "UPDATE %s.ducklake_data_file SET table_id = %s WHERE table_id = %s;";
    private static final String SELECT_DUCKLAKE_DATA_FILES_QUERY = "SELECT path, file_size_bytes, end_snapshot FROM %s.ducklake_data_file WHERE table_id = %s AND file_size_bytes BETWEEN %s AND %s;";
    private static final String GET_TABLE_INFO_BY_ID_QUERY = "SELECT s.schema_name, t.table_name FROM %s.ducklake_table t JOIN %s.ducklake_schema s ON t.schema_id = s.schema_id WHERE t.table_id = %s;";
    private static final String GET_FILE_IDS_BY_TABLE_AND_PATHS_QUERY = "SELECT data_file_id FROM %s.ducklake_data_file WHERE table_id = %s AND path IN (%s);";
    private static final String CREATE_SNAPSHOT_QUERY = "INSERT INTO %s.ducklake_snapshot (snapshot_id, snapshot_time, schema_version, next_catalog_id, next_file_id) SELECT MAX(snapshot_id) + 1, now(), MAX(schema_version), MAX(next_catalog_id), MAX(next_file_id) FROM %s.ducklake_snapshot RETURNING snapshot_id;";
    private static final String SET_END_SNAPSHOT_QUERY = "UPDATE %s.ducklake_data_file SET end_snapshot = %s WHERE data_file_id IN (%s) AND end_snapshot IS NULL;";

    /**
     * @param database database of the table
     * @param tableId id of the table
     * @param tempTableId temporary  table id which will be used to calculate the metadata
     * @param mdDatabase metadata database
     * @param toAdd Files to be added
     * @param toRemove Files to be deleted
     *  The function will update metadata table inside a transaction. It will abort if any of the file  in the remove is missing
     * Example we have written a file 'c' which  is combination of   file a and b. So this function will be called as
     * replace(1, 10, __test_database, c, List.of(a,  b))
     * add all the files to dummy table
     * begin transaction
     * check all the files in the remove list exist in the tableId
     * change the table id's for the files in the dummy table
     * delete the files in the remove
     * commit the transaction.
     * open conn --> begin transaction --> execute changes with connectionPool.executeBatch --> commit; close conn.
     */
    public static void replace(String database,
                        long tableId,
                        long tempTableId,
                        String mdDatabase,
                        List<String> toAdd,
                        List<String> toRemove) throws SQLException {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database cannot be null or blank");
        }
        if (mdDatabase == null || mdDatabase.isBlank()) {
            throw new IllegalArgumentException("mdDatabase cannot be null or blank");
        }
        if (toAdd == null) {
            throw new IllegalArgumentException("toAdd cannot be null");
        }
        if (toRemove == null) {
            throw new IllegalArgumentException("toRemove cannot be null");
        }

        try (Connection conn = ConnectionPool.getConnection()) {
            if (!toAdd.isEmpty()) {
                String tempTableName = ConnectionPool.collectFirst(conn, GET_TABLE_NAME_BY_ID.formatted(mdDatabase, tempTableId), String.class);
                for (String file : toAdd) {
                    ConnectionPool.execute(ADD_FILE_TO_TABLE_QUERY.formatted(database, tempTableName, file));
                }
            }
            if (!toRemove.isEmpty()) {
                boolean transactionStarted = false;
                try {
                    ConnectionPool.execute(conn, "BEGIN TRANSACTION;");
                    transactionStarted = true;
                    String filePaths = toRemove.stream().map(fp -> "'" + fp + "'").collect(Collectors.joining(", "));
                    var t = ConnectionPool.collectFirstColumn(conn, GET_FILE_ID_BY_PATH_QUERY.formatted(mdDatabase, tableId, filePaths), Long.class).iterator();
                    var fileIds = new ArrayList<Long>();
                    while (t.hasNext()) {
                        fileIds.add(t.next());
                    }
                    if (fileIds.size() != toRemove.size()) {
                        throw new IllegalStateException("One or more files scheduled for deletion were not found for tableId=" + tableId);
                    }
                    String fileIdsString = fileIds.stream().map(String::valueOf).collect(Collectors.joining(", "));

                    String updateNewFileTableId = UPDATE_TABLE_ID.formatted(mdDatabase, tableId, tempTableId);
                    var queries = getQueries(mdDatabase, fileIdsString, updateNewFileTableId);
                    for (String query : queries) {
                        ConnectionPool.execute(conn, query);
                    }
                } catch (Exception e) {
                    if (transactionStarted) {
                        ConnectionPool.execute(conn, "ROLLBACK;");
                    }
                    throw e;
                }
            }
        }
    }

    private static String[] getQueries(String mdDatabase, String fileIdsString, String updateNewFileTableId) {
        String deleteStatsQuery = DELETE_FILE_COLUMN_STATS_QUERY.formatted(mdDatabase, fileIdsString);
        String deletePartitionQuery = DELETE_FILE_PARTITION_VALUE_QUERY.formatted(mdDatabase, fileIdsString);
        String deleteFileQuery = DELETE_DATA_FILE_QUERY.formatted(mdDatabase, fileIdsString);
        String scheduleDeletesQuery = INSERT_INTO_SCHEDULE_FILE_DELETION_QUERY.formatted(mdDatabase, mdDatabase, fileIdsString);

        return new String[]{
                scheduleDeletesQuery,    // Mark old files for deletion
                updateNewFileTableId,    // Move merged file(s) to main table
                deleteStatsQuery,        // Remove stats of old files
                deletePartitionQuery,    // Remove partition values of old files
                deleteFileQuery,         // Remove old file entries
                "COMMIT"
        };
    }

    /**
     * Replaces files in a table atomically using proper DuckLake snapshot mechanism.
     * This method performs add and remove operations within a single transaction,
     * creating a new snapshot and setting end_snapshot on removed files instead of
     * directly deleting them.
     *
     * @param database the database/catalog name
     * @param tableName the table name to modify
     * @param mdDatabase metadata database name
     * @param toAdd list of file paths to add to the table
     * @param toRemove list of file paths to mark for deletion
     * @return the snapshot ID created for this replace operation
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if required parameters are null or blank
     * @throws IllegalStateException if files to remove are not found
     */
    public static long replaceV2(String database,
                                  String tableName,
                                  String mdDatabase,
                                  List<String> toAdd,
                                  List<String> toRemove) throws SQLException {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database cannot be null or blank");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName cannot be null or blank");
        }
        if (mdDatabase == null || mdDatabase.isBlank()) {
            throw new IllegalArgumentException("mdDatabase cannot be null or blank");
        }
        if (toAdd == null) {
            throw new IllegalArgumentException("toAdd cannot be null");
        }
        if (toRemove == null) {
            throw new IllegalArgumentException("toRemove cannot be null");
        }
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            throw new IllegalArgumentException("Both toAdd and toRemove cannot be empty");
        }

        // Get table ID
        String GET_TABLE_ID_QUERY = "SELECT table_id FROM %s.ducklake_table WHERE table_name = '%s'";
        Long tableId = ConnectionPool.collectFirst(GET_TABLE_ID_QUERY.formatted(mdDatabase, tableName), Long.class);
        if (tableId == null) {
            throw new IllegalStateException("Table not found: " + tableName);
        }

        try (Connection conn = ConnectionPool.getConnection()) {
            boolean transactionStarted = false;
            try {
                ConnectionPool.execute(conn, "BEGIN TRANSACTION;");
                transactionStarted = true;

                // Create a new snapshot for this replace operation
                Long newSnapshotId = ConnectionPool.collectFirst(conn,
                        CREATE_SNAPSHOT_QUERY.formatted(mdDatabase, mdDatabase), Long.class);

                // Add new files within the transaction
                for (String file : toAdd) {
                    // Escape single quotes in file path to prevent SQL injection
                    String escapedFile = file.replace("'", "''");
                    String addQuery = ADD_FILE_TO_TABLE_QUERY.formatted(database, tableName, escapedFile);
                    ConnectionPool.execute(conn, addQuery);
                }

                // Set end_snapshot on files to remove (if any)
                if (!toRemove.isEmpty()) {
                    // Escape single quotes in file paths to prevent SQL injection
                    String filePaths = toRemove.stream()
                            .map(fp -> "'" + fp.replace("'", "''") + "'")
                            .collect(Collectors.joining(", "));

                    // Get file IDs for files to remove
                    var fileIdsIterator = ConnectionPool.collectFirstColumn(conn,
                            GET_FILE_IDS_BY_TABLE_AND_PATHS_QUERY.formatted(mdDatabase, tableId, filePaths), Long.class).iterator();
                    List<Long> fileIds = new ArrayList<>();
                    while (fileIdsIterator.hasNext()) {
                        fileIds.add(fileIdsIterator.next());
                    }

                    if (fileIds.size() != toRemove.size()) {
                        throw new IllegalStateException("One or more files scheduled for deletion were not found. Expected: "
                                + toRemove.size() + ", Found: " + fileIds.size() + " for tableId=" + tableId);
                    }

                    String fileIdsString = fileIds.stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(", "));

                    // Set end_snapshot on files to mark them as deleted in this snapshot
                    String setEndSnapshotQuery = SET_END_SNAPSHOT_QUERY.formatted(mdDatabase, newSnapshotId, fileIdsString);
                    ConnectionPool.execute(conn, setEndSnapshotQuery);
                }

                ConnectionPool.execute(conn, "COMMIT;");
                return newSnapshotId;

            } catch (Exception e) {
                if (transactionStarted) {
                    ConnectionPool.execute(conn, "ROLLBACK;");
                }
                throw e;
            }
        }
    }

    /**
     *
     * @param inputFiles input files. Partitioned or un-partitioned.
     * @param partition
     * @return the list of newly created files. Note this will not update the metadata. It needs to be combined with replace function to make this changes visible to the table.
     *
     * input -> /data/log/a, /data/log/b
     * baseLocation -> /data/log
     * partition -> List.Of('date', applicationid).
     */
    public static List<String> rewriteWithPartitionNoCommit(List<String> inputFiles,
                                                     String baseLocation,
                                                     List<String> partition) throws SQLException {
        if (inputFiles == null || inputFiles.isEmpty()) {
            throw new IllegalArgumentException("inputFiles cannot be null or empty");
        }
        if (baseLocation == null || baseLocation.isBlank()) {
            throw new IllegalArgumentException("baseLocation cannot be null or blank");
        }
        if (partition == null) {
            throw new IllegalArgumentException("partition cannot be null");
        }
        try (Connection conn = ConnectionPool.getConnection()) {
            String sources = inputFiles.stream().map(s -> "'" + s + "'").collect(Collectors.joining(","));
            return getStrings(sources, baseLocation, partition, conn);
        }
    }

    /**
     *
     * @param inputFile input file..
     * @param partition
     * @return the list of newly created files. Note this will not update the metadata. It needs to be combined with replace function to make this changes visible to the table.
     *
     * input -> /data/log/a, /data/log/b
     * baseLocation -> /data/log
     * partition -> List.Of('date', applicationid).
     */
    public static List<String> rewriteWithPartitionNoCommit(String inputFile,
                                                            String baseLocation,
                                                            List<String> partition) throws SQLException {
        if (inputFile == null || inputFile.isBlank()) {
            throw new IllegalArgumentException("inputFile cannot be null or blank");
        }
        if (baseLocation == null || baseLocation.isBlank()) {
            throw new IllegalArgumentException("baseLocation cannot be null or blank");
        }
        if (partition == null) {
            throw new IllegalArgumentException("partition cannot be null");
        }
        try (Connection conn = ConnectionPool.getConnection()) {
            Path p = Paths.get(inputFile);
            Path fileName = p.getFileName();
            var targetPath = baseLocation + fileName.toString();
            return getStrings("'" + inputFile + "'", targetPath, partition, conn);
        }
    }

    private static List<String> getStrings(String inputFile, String baseLocation, List<String> partition, Connection conn) {
        String partitionClause = partition.isEmpty() ? "" : "PARTITION_BY (" + String.join(", ", partition) + "),";
        String copyQuery = COPY_TO_NEW_FILE_WITH_PARTITION_QUERY.formatted(inputFile, baseLocation, partitionClause);

        List<String> files = new ArrayList<>();
        for (CopyResult r : ConnectionPool.collectAll(conn, copyQuery, CopyResult.class)) {
            files.addAll(Arrays.stream(r.files()).map(Object::toString).toList());
        }
        return files;
    }

    /**
     * Marks files matching the filter for deletion by setting their end_snapshot.
     * This follows DuckLake's proper snapshot mechanism - files are not immediately deleted
     * but marked with an end_snapshot. After calling ducklake_expire_snapshots(), the files
     * will be moved to ducklake_files_scheduled_for_deletion.
     *
     * @param tableId the table ID whose files should be marked for deletion
     * @param mdDatabase metadata database name
     * @param filter SQL SELECT statement with WHERE clause to identify files to delete.
     *               Files matching this filter (based on partition pruning) will be marked for deletion.
     * @return List of the file paths which are marked for deletion.
     * @throws SQLException if a database access error occurs
     * @throws JsonProcessingException if the filter SQL cannot be parsed
     * @throws IllegalArgumentException if filter or mdDatabase is null or blank
     */
    public static List<String> drop(long tableId, String mdDatabase, String filter) throws SQLException, JsonProcessingException {
        if (mdDatabase == null || mdDatabase.isBlank()) {
            throw new IllegalArgumentException("mdDatabase cannot be null or blank");
        }
        if (filter == null || filter.isBlank()) {
            throw new IllegalArgumentException("filter cannot be null or blank");
        }

        try (Connection conn = ConnectionPool.getConnection()) {
            // Get table schema and name for partition pruning
            var tableInfoIterator = ConnectionPool.collectAll(conn,
                    GET_TABLE_INFO_BY_ID_QUERY.formatted(mdDatabase, mdDatabase, tableId), TableInfo.class).iterator();
            if (!tableInfoIterator.hasNext()) {
                throw new IllegalStateException("Table not found for tableId=" + tableId);
            }
            TableInfo tableInfo = tableInfoIterator.next();

            // Use DucklakePartitionPruning to get files matching the filter (files to delete)
            DucklakePartitionPruning pruning = new DucklakePartitionPruning(mdDatabase);
            List<FileStatus> filesToDelete = pruning.pruneFiles(tableInfo.schemaName(), tableInfo.tableName(), filter);
            Set<String> filesToRemove = filesToDelete.stream()
                    .map(FileStatus::fileName)
                    .collect(Collectors.toSet());

            if (filesToRemove.isEmpty()) {
                return List.of();
            }

            // Get file IDs for files to remove - escape single quotes to prevent SQL injection
            String filePaths = filesToRemove.stream()
                    .map(fp -> "'" + fp.replace("'", "''") + "'")
                    .collect(Collectors.joining(", "));
            var fileIdsIterator = ConnectionPool.collectFirstColumn(conn,
                    GET_FILE_IDS_BY_TABLE_AND_PATHS_QUERY.formatted(mdDatabase, tableId, filePaths), Long.class).iterator();
            List<Long> fileIds = new ArrayList<>();
            while (fileIdsIterator.hasNext()) {
                fileIds.add(fileIdsIterator.next());
            }

            if (fileIds.isEmpty()) {
                return List.of();
            }

            // Execute within a transaction
            boolean transactionStarted = false;
            try {
                ConnectionPool.execute(conn, "BEGIN TRANSACTION;");
                transactionStarted = true;

                // Create a new snapshot for this drop operation
                Long newSnapshotId = ConnectionPool.collectFirst(conn,
                        CREATE_SNAPSHOT_QUERY.formatted(mdDatabase, mdDatabase), Long.class);

                String fileIdsString = fileIds.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", "));

                // Set end_snapshot on files to mark them as deleted in this snapshot
                // Only update files that don't already have an end_snapshot (i.e., active files)
                String setEndSnapshotQuery = SET_END_SNAPSHOT_QUERY.formatted(mdDatabase, newSnapshotId, fileIdsString);
                ConnectionPool.execute(conn, setEndSnapshotQuery);

                ConnectionPool.execute(conn, "COMMIT;");
            } catch (Exception e) {
                if (transactionStarted) {
                    ConnectionPool.execute(conn, "ROLLBACK;");
                }
                throw e;
            }

            return new ArrayList<>(filesToRemove);
        }
    }

    /**
     * Record to hold table schema and name information.
     */
    public record TableInfo(String schemaName, String tableName) {}

    public static List<FileStatus> listFiles(String mdDatabase,
                                             long tableId,
                                             long minSize,
                                             long maxSize) throws SQLException {
        if (mdDatabase == null || mdDatabase.isBlank()) {
            throw new IllegalArgumentException("mdDatabase cannot be null or blank");
        }
        if (minSize < 0) {
            throw new IllegalArgumentException("minSize cannot be negative");
        }
        if (maxSize < minSize) {
            throw new IllegalArgumentException("maxSize cannot be less than minSize");
        }
        List<FileStatus> filesToCompact = new ArrayList<>();
        String selectQuery = SELECT_DUCKLAKE_DATA_FILES_QUERY.formatted(mdDatabase, tableId, minSize, maxSize);
        try (Connection conn = ConnectionPool.getConnection()){
            for (FileStatus file : ConnectionPool.collectAll(conn, selectQuery, FileStatus.class)) {
                filesToCompact.add(file);
            }
        }
        return filesToCompact;
    }
}
