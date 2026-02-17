package io.dazzleduck.sql.ducklake;

public class MetadataConfig {
    private static String qualifier = "."; // duckdb default

    public static void init(String backend) {
        qualifier = backend.equalsIgnoreCase("postgres") ? ".main." : ".";
    }

    public static String q() { return qualifier; }
}