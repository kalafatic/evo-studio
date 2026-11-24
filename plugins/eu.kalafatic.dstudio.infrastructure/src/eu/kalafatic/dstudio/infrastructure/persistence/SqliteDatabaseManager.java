package eu.kalafatic.dstudio.infrastructure.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class SqliteDatabaseManager implements AutoCloseable {

    private final Path databaseFile;
    private final Connection connection;

    public SqliteDatabaseManager(Path databaseFile) {
        this.databaseFile = databaseFile;
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA busy_timeout=30000");
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to open EVO-Studio database " + databaseFile + ": " + e.getMessage(), e);
        }
    }

    public Connection connection() {
        return connection;
    }

    public Path getDatabaseFile() {
        return databaseFile;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception e) {
            System.err.println("Failed to close EVO-Studio database: " + e.getMessage());
        }
    }
}
