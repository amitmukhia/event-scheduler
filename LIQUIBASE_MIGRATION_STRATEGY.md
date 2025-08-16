# Liquibase Migration Strategy

## Overview
This document outlines the strategy for migrating from Flyway to Liquibase for database schema management in the event-scheduler-pro project.

## Migration Approach

### For New/Clean Databases
- Liquibase will create the `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` tables
- All changesets will be executed in order
- No special handling needed

### For Existing Databases with Flyway History
If you have an existing database that was previously managed by Flyway, you need to handle the migration carefully to avoid re-executing already applied changes.

#### Option 1: Mark Changesets as Already Executed (Recommended)
```bash
# Run this for existing databases to mark all changesets as already executed
mvn liquibase:changelogSync
```

This command will:
1. Create the Liquibase changelog tables
2. Mark all existing changesets as already executed without running them
3. Future migrations will execute normally

#### Option 2: Manual Changeset Sync
If you need more control, you can manually mark specific changesets:

```bash
# Mark specific changesets as executed
mvn liquibase:changelogSyncSQL > sync.sql
# Review the generated SQL and execute manually
```

## Changeset Mapping

### Flyway → Liquibase Changeset Mapping
| Flyway File | Liquibase Changeset ID | Description |
|-------------|------------------------|-------------|
| V1__fresh_initial_schema.sql | 001-007 | Initial schema and indexes |
| V2__add_callback_support.sql | 008-012 | Callback support |
| V3__drop_removed_columns.sql | 013-015 | Drop removed columns |
| V1001__add_events_version.sql | 016 | Add version column |
| V1002__add_indexes.sql | 017 | Additional indexes |
| V1003__cancelled_by_email.sql | 018 | Convert cancelled_by to TEXT |

## Validation Steps

### 1. Backup Your Database
```bash
pg_dump events_db > backup_before_liquibase.sql
```

### 2. Test Migration on Development Environment
- Use a copy of production data
- Run the migration
- Validate schema matches expected state

### 3. Verify Changeset Status
```bash
mvn liquibase:status
```

This will show you which changesets have been executed and which are pending.

### 4. Generate Migration Report
```bash
mvn liquibase:updateSQL > pending_migrations.sql
```

Review the generated SQL to ensure it matches your expectations.

## Production Deployment Strategy

### Pre-deployment Steps
1. **Create database backup**
2. **Test migration on staging environment with production data copy**
3. **Generate and review migration SQL**
4. **Coordinate downtime window if necessary**

### Deployment Steps
1. **Deploy application with Liquibase configuration**
2. **For existing databases, run changeset sync first:**
   ```bash
   mvn liquibase:changelogSync
   ```
3. **Start application - it will handle future migrations automatically**
4. **Verify migration success:**
   ```bash
   mvn liquibase:status
   ```

### Post-deployment Validation
1. Check that `DATABASECHANGELOG` table exists and contains expected entries
2. Verify schema matches expected state
3. Run application tests to ensure functionality is preserved
4. Clean up old Flyway `flyway_schema_history` table if desired (optional)

## Rollback Strategy

### Automated Rollback (if supported)
```bash
# Rollback to specific changeset
mvn liquibase:rollback -Dliquibase.rollbackTag=v1.0

# Rollback specific number of changesets
mvn liquibase:rollback -Dliquibase.rollbackCount=3
```

### Manual Rollback
If automated rollback is not available:
1. Restore from database backup
2. Re-apply only the necessary changesets

## Troubleshooting

### Common Issues and Solutions

#### Checksum Mismatch
If you get checksum validation errors:
```bash
# Clear checksums and recalculate
mvn liquibase:clearCheckSums
```

#### Locked Database
If migration is stuck:
```bash
# Force unlock (use with caution)
mvn liquibase:releaseLocks
```

#### Schema Validation Errors
Ensure your JPA entities match the expected schema after migration.

## Environment-Specific Configuration

### Development
- `contexts: development`
- Include test data setup changesets

### Staging  
- `contexts: staging`
- Mirror production setup

### Production
- `contexts: production` (default in application.yml)
- Only structural changes, no test data

## Benefits of This Migration

1. **Better Team Collaboration**: Changeset-based approach prevents version conflicts
2. **Rollback Capability**: Built-in rollback support for safe deployments  
3. **Database Portability**: XML-based changesets work across different databases
4. **Enhanced Validation**: Better change tracking and validation
5. **Company Standardization**: Aligns with company tooling standards

## Next Steps

1. Test the migration on development environment
2. Validate schema integrity
3. Update CI/CD pipelines to use Liquibase
4. Train team on Liquibase best practices
5. Plan production migration during maintenance window
