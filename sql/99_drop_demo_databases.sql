/* =============================================================================
   99 - Drop the demo databases (and their backup history)
   -----------------------------------------------------------------------------
   To restart the demos: run this, then 02 -> 03 -> 04 again.
   ============================================================================= */
USE master;
GO
DECLARE @demo TABLE (name sysname);
INSERT @demo VALUES (N'testAudit'), (N'DbWatchMaintenanceDemo'), (N'DSR_PROD');

DECLARE @n sysname, @sql nvarchar(max);
DECLARE c CURSOR LOCAL FAST_FORWARD FOR SELECT name FROM @demo WHERE DB_ID(name) IS NOT NULL;
OPEN c; FETCH NEXT FROM c INTO @n;
WHILE @@FETCH_STATUS = 0
BEGIN
    SET @sql = N'ALTER DATABASE ' + QUOTENAME(@n) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE ' + QUOTENAME(@n) + N';';
    EXEC (@sql);
    EXEC msdb.dbo.sp_delete_database_backuphistory @database_name = @n;
    FETCH NEXT FROM c INTO @n;
END
CLOSE c; DEALLOCATE c;
GO
