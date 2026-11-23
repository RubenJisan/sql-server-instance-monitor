/* =============================================================================
   03 - Create the user and grant permissions in every database
   -----------------------------------------------------------------------------
   Loops over ALL online databases (except tempdb, read-only databases and
   snapshots) and, in each one:
     - creates the `dbwatch_monitor` user, or re-maps it to the login if it is
       orphaned (e.g. after restoring the database on another server);
     - grants VIEW DEFINITION and SELECT on sys.sql_expression_dependencies
       (modules 5 and 6: invalid-object detection);
     - adds it to db_backupoperator (module 4: "Run FULL now" button).
   Additionally grants read access to backup history in msdb and EXECUTE on
   xp_readerrorlog in master (to surface failed backups).

   Re-run it every time you create or restore a database you want to monitor,
   audit or back up from the application.

   Run as: sysadmin. Requires 01_create_monitor_login.sql first.
   Deliberately NOT granted: db_owner / sysadmin (more power than needed).
   ============================================================================= */
USE master;
GO
SET NOCOUNT ON;

IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dbwatch_monitor')
BEGIN
    RAISERROR(N'Login dbwatch_monitor does not exist. Run 01_create_monitor_login.sql first.', 16, 1);
    RETURN;
END

DECLARE @db sysname, @sql nvarchar(max);

DECLARE dbs CURSOR LOCAL FAST_FORWARD FOR
    SELECT name
    FROM sys.databases
    WHERE database_id <> 2
      AND state_desc = N'ONLINE'
      AND is_read_only = 0
      AND source_database_id IS NULL
    ORDER BY database_id;

OPEN dbs;
FETCH NEXT FROM dbs INTO @db;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @sql = N'USE ' + QUOTENAME(@db) + N';
IF USER_ID(N''dbwatch_monitor'') IS NULL
BEGIN
    CREATE USER [dbwatch_monitor] FOR LOGIN [dbwatch_monitor];
END
ELSE IF EXISTS (SELECT 1
                FROM sys.database_principals AS dp
                JOIN sys.server_principals   AS sp ON sp.name = dp.name
                WHERE dp.name = N''dbwatch_monitor'' AND dp.sid <> sp.sid)
BEGIN
    ALTER USER [dbwatch_monitor] WITH LOGIN = [dbwatch_monitor];
END
GRANT VIEW DEFINITION TO [dbwatch_monitor];
GRANT SELECT ON sys.sql_expression_dependencies TO [dbwatch_monitor];
ALTER ROLE db_backupoperator ADD MEMBER [dbwatch_monitor];';

    IF @db = N'master'
        SET @sql += N'
GRANT EXECUTE ON xp_readerrorlog TO [dbwatch_monitor];';

    IF @db = N'msdb'
        SET @sql += N'
GRANT SELECT ON dbo.backupset TO [dbwatch_monitor];
GRANT SELECT ON dbo.backupmediafamily TO [dbwatch_monitor];';

    BEGIN TRY
        EXEC (@sql);
        PRINT N'OK     ' + @db;
    END TRY
    BEGIN CATCH
        PRINT N'ERROR  ' + @db + N': ' + ERROR_MESSAGE();
    END CATCH;

    FETCH NEXT FROM dbs INTO @db;
END
CLOSE dbs;
DEALLOCATE dbs;
GO
