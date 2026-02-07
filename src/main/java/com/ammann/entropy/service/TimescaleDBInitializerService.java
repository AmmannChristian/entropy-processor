package com.ammann.entropy.service;

import com.ammann.entropy.exception.SomeThingWentWrongException;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;


/**
 * Service for initializing TimescaleDB-specific schema objects.
 *
 * <p>Creates the {@code entropy_data} hypertable (partitioned by {@code server_received})
 * and associated performance indexes if they do not already exist. Intended to be called
 * once during application startup.
 */
@ApplicationScoped
public class TimescaleDBInitializerService
{
    private static final Logger LOG = Logger.getLogger(TimescaleDBInitializerService.class);

    private AgroalDataSource dataSource;

    @Inject
    public TimescaleDBInitializerService(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Creates the TimescaleDB hypertable and performance indexes for the entropy_data table.
     *
     * @throws SomeThingWentWrongException if the initialization SQL fails
     */
    public void initializeTimescaleDB() {
        try (Connection connection = dataSource.getConnection()) {
            PreparedStatement checkStmt = connection.prepareStatement(
                    "SELECT * FROM timescaledb_information.hypertables WHERE hypertable_name = 'entropy_data'"
            );
            ResultSet rs = checkStmt.executeQuery();

            if (!rs.next()) {
                PreparedStatement createStmt = connection.prepareStatement(
                        "SELECT create_hypertable('entropy_data', 'server_received', if_not_exists => true)"
                );
                createStmt.execute();
                LOG.info("TimescaleDB Hypertable 'entropy_data' created");

                PreparedStatement indexStmt = connection.prepareStatement(
                        "CREATE INDEX IF NOT EXISTS idx_hw_timestamp_ns ON entropy_data (hw_timestamp_ns)"
                );

                indexStmt.execute();

                LOG.info("Performance index created");
            } else {
                LOG.info("TimescaleDB Hypertable 'entropy_data' already exists");
            }

        } catch (Exception e) {
            LOG.error("Failed to initialize TimescaleDB", e);
            throw new SomeThingWentWrongException(e);
        }
    }
}
