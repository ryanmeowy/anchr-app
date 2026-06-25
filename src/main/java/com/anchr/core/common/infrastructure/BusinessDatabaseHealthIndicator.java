package com.anchr.core.common.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Health indicator for the business database and Flyway baseline.
 */
@Component("businessDatabase")
public class BusinessDatabaseHealthIndicator extends AbstractHealthIndicator {

    private final DataSource dataSource;
    private final Flyway flyway;

    public BusinessDatabaseHealthIndicator(DataSource dataSource, Flyway flyway) {
        this.dataSource = dataSource;
        this.flyway = flyway;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            MigrationInfo current = flyway.info().current();
            builder.up()
                    .withDetail("database", connection.getMetaData().getDatabaseProductName())
                    .withDetail("migrationVersion", current == null ? "none" : current.getVersion().getVersion())
                    .withDetail("migrationDescription", current == null ? "none" : current.getDescription());
        }
    }
}
