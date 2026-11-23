/* =============================================================================
   06 - Manual backup template and cleanup helpers
   -----------------------------------------------------------------------------
   A) Backup template (FULL / DIFF / LOG): writes to msdb history exactly what
      module 4 reads. Same options as the "Run FULL now" button
      (WITH CHECKSUM, INIT). INIT overwrites an existing file; every run embeds
      a timestamp in the file name so earlier backups are not overwritten.
   B) Cleanup: returns the backup module to its initial state before repeating
      the walkthrough. IRREVERSIBLE: do NOT run on an important database.
   ============================================================================= */

/* ---- A) Backup template ---------------------------------------------------- */
USE master;
DECLARE @db    sysname     = N'DSR_PROD';   -- database to back up
DECLARE @type  varchar(4)  = 'FULL';        -- FULL | DIFF | LOG  (DIFF and LOG require a previous FULL; LOG requires FULL recovery)

DECLARE @dir   nvarchar(512) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512));
DECLARE @sep   nvarchar(1)   = CASE WHEN @dir LIKE '%/%' THEN N'/' ELSE N'\' END;
DECLARE @stamp varchar(15)   = FORMAT(SYSDATETIME(), 'yyyyMMdd_HHmmss');
DECLARE @ext   nvarchar(4)   = CASE WHEN @type = 'LOG' THEN N'.trn' ELSE N'.bak' END;
DECLARE @f     nvarchar(600) = @dir + @sep + @db + N'_' + @type + N'_' + @stamp + @ext;

IF @type = 'FULL'
    BACKUP DATABASE @db TO DISK = @f WITH CHECKSUM, INIT;
ELSE IF @type = 'DIFF'
    BACKUP DATABASE @db TO DISK = @f WITH DIFFERENTIAL, CHECKSUM, INIT;
ELSE IF @type = 'LOG'
    BACKUP LOG @db TO DISK = @f WITH CHECKSUM, INIT;

PRINT N'Backup written to: ' + @f;
GO

/* ---- B) Cleanup (run each block on its own) ------------------------------------

-- B.1  msdb backup history (what the module reads)
EXEC msdb.dbo.sp_delete_backuphistory @oldest_date = '2099-01-01';
SELECT COUNT(*) AS remaining_rows FROM msdb.dbo.backupset;   -- must return 0

-- B.2  Old failures in the error log: cycle the log so they stop showing
EXEC master.dbo.sp_cycle_errorlog;

-- B.3  Backup files on disk (optional): deleting history does not delete .bak / .trn files
DECLARE @dir nvarchar(512) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512));
EXEC master.dbo.xp_delete_file 0, @dir, N'bak', N'2099-01-01T00:00:00', 0;
EXEC master.dbo.xp_delete_file 0, @dir, N'trn', N'2099-01-01T00:00:00', 0;
-- If xp_delete_file fails, delete them manually:
--   Docker : docker exec sqlserver sh -c "rm -f /var/opt/mssql/data/*.bak /var/opt/mssql/data/*.trn"
--   Windows: the ...\MSSQL\Backup folder of your installation

*/
