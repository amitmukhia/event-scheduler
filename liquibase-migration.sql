[INFO] Scanning for projects...
[INFO] 
[INFO] ------------------< com.example:event-scheduler-uuid >------------------
[INFO] Building event-scheduler-uuid 0.1.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- liquibase:4.27.0:updateSQL (default-cli) @ event-scheduler-uuid ---
[INFO] ------------------------------------------------------------------------
[INFO] ####################################################
##   _     _             _ _                      ##
##  | |   (_)           (_) |                     ##
##  | |    _  __ _ _   _ _| |__   __ _ ___  ___   ##
##  | |   | |/ _` | | | | | '_ \ / _` / __|/ _ \  ##
##  | |___| | (_| | |_| | | |_) | (_| \__ \  __/  ##
##  \_____/_|\__, |\__,_|_|_.__/ \__,_|___/\___|  ##
##              | |                               ##
##              |_|                               ##
##                                                ## 
##  Get documentation at docs.liquibase.com       ##
##  Get certified courses at learn.liquibase.com  ## 
##                                                ##
####################################################
Starting Liquibase at 14:44:04 (version 4.27.0 #1525 built at 2024-03-25 17:08+0000)
[INFO] Set default schema name to public
[INFO] Output SQL Migration File: /Users/ameet/Downloads/event-scheduler-pro/target/liquibase/migrate.sql
[INFO] Executing on Database: jdbc:postgresql://localhost:5432/events_db
[INFO] Successfully acquired change log lock
[INFO] Reading from databasechangelog
[INFO] Using deploymentId: 5335645470
[INFO] Reading from databasechangelog
[INFO] Custom SQL executed
[INFO] ChangeSet db/changelog/v1.0/01-fresh-initial-schema.xml::001-enable-uuid-extension::event-scheduler-team ran successfully in 3ms
[INFO] Table customers created
[INFO] ChangeSet db/changelog/v1.0/01-fresh-initial-schema.xml::002-create-customers-table::event-scheduler-team ran successfully in 2ms
[INFO] Table events created
[INFO] Foreign key constraint added to events (customer_id)
[INFO] ChangeSet db/changelog/v1.0/01-fresh-initial-schema.xml::003-create-events-table::event-scheduler-team ran successfully in 3ms
[INFO] Table event_history created
[INFO] ChangeSet db/changelog/v1.0/01-fresh-initial-schema.xml::004-create-event-history-table::event-scheduler-team ran successfully in 5ms
[INFO] Table failed_events created
[INFO] ChangeSet db/changelog/v1.0/01-fresh-initial-schema.xml::005-create-failed-events-table::event-scheduler-team ran successfully in 3ms
[INFO] Custom SQL executed
[INFO] ChangeSet db/changelog/v1.0/01-fresh-initial-schema.xml::006-create-performance-indexes::event-scheduler-team ran successfully in 8ms
[INFO] Custom SQL executed
[INFO] ChangeSet db/changelog/v1.0/01-fresh-initial-schema.xml::007-analyze-tables::event-scheduler-team ran successfully in 1ms
[INFO] Columns callback(JSONB) added to events
[INFO] ChangeSet db/changelog/v1.0/02-add-callback-support.xml::008-add-callback-to-events::event-scheduler-team ran successfully in 1ms
[INFO] Columns callback(JSONB) added to event_history
[INFO] ChangeSet db/changelog/v1.0/02-add-callback-support.xml::009-add-callback-to-event-history::event-scheduler-team ran successfully in 0ms
[INFO] Columns callback(JSONB) added to failed_events
[INFO] ChangeSet db/changelog/v1.0/02-add-callback-support.xml::010-add-callback-to-failed-events::event-scheduler-team ran successfully in 0ms
[INFO] Custom SQL executed
[INFO] ChangeSet db/changelog/v1.0/02-add-callback-support.xml::011-add-callback-index::event-scheduler-team ran successfully in 1ms
[INFO] Custom SQL executed
[INFO] ChangeSet db/changelog/v1.0/02-add-callback-support.xml::012-add-callback-comments::event-scheduler-team ran successfully in 1ms
[INFO] Column events.max_retries dropped
[INFO] ChangeSet db/changelog/v1.0/03-drop-removed-columns.xml::013-drop-max-retries-from-events::event-scheduler-team ran successfully in 1ms
[INFO] Column events.delivery_mechanism dropped
[INFO] ChangeSet db/changelog/v1.0/03-drop-removed-columns.xml::014-drop-delivery-mechanism-from-events::event-scheduler-team ran successfully in 1ms
[INFO] Column event_history.delivery_mechanism dropped
[INFO] ChangeSet db/changelog/v1.0/03-drop-removed-columns.xml::015-drop-delivery-mechanism-from-event-history::event-scheduler-team ran successfully in 1ms
[INFO] Columns version(BIGINT) added to events
[INFO] ChangeSet db/changelog/v1.1/01-add-events-version.xml::016-add-version-column::event-scheduler-team ran successfully in 1ms
[INFO] Index idx_events_status_next_run_time created
[INFO] Index idx_events_next_run_time created
[INFO] Index idx_events_customer_status_v2 created
[INFO] Index idx_event_history_customer_completion created
[INFO] ChangeSet db/changelog/v1.1/02-add-indexes.xml::017-add-additional-performance-indexes::event-scheduler-team ran successfully in 2ms
[INFO] events.cancelled_by datatype was changed to TEXT
[INFO] ChangeSet db/changelog/v1.1/03-cancelled-by-email.xml::018-convert-cancelled-by-to-text::event-scheduler-team ran successfully in 1ms
[INFO] Update command completed successfully.
[INFO] Successfully released change log lock
[INFO] Successfully released change log lock
[INFO] Command execution complete
[INFO] ------------------------------------------------------------------------
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  2.079 s
[INFO] Finished at: 2025-08-16T14:44:05+05:30
[INFO] ------------------------------------------------------------------------
