/* =============================================================================
   05 - Verify permissions
   -----------------------------------------------------------------------------
   Runs the checks AS dbwatch_monitor (EXECUTE AS requires a sysadmin session).
   The definitive test is still the application itself: its on-screen warnings
   tell you which query could not be executed.
   ============================================================================= */
USE master;
GO
EXECUTE AS LOGIN = N'dbwatch_monitor';

SELECT SUSER_NAME() AS running_as;

-- Effective server permissions (expected: the first 3 always; the rest depend on version and @AllowKill)
SELECT permission_name
FROM sys.fn_my_permissions(NULL, N'SERVER')
WHERE permission_name IN (N'VIEW SERVER STATE', N'VIEW ANY DEFINITION', N'CONNECT ANY DATABASE',
                          N'ALTER ANY CONNECTION', N'VIEW SERVER PERFORMANCE STATE', N'VIEW ANY ERROR LOG')
ORDER BY permission_name;

-- Modules 1 and 2
SELECT cpu_count FROM sys.dm_os_sys_info;
SELECT COUNT(*) AS session_count FROM sys.dm_exec_sessions;

-- Modules 3 and 4
SELECT COUNT(*) AS backupset_read_ok   FROM msdb.dbo.backupset;
SELECT COUNT(*) AS backupmedia_read_ok FROM msdb.dbo.backupmediafamily;
EXEC master.dbo.xp_readerrorlog 0, 1, N'BACKUP failed to complete';   -- may return 0 rows, but must not error

-- Per database: NULL or 0 means the user or the permission is missing (re-run script 03)
SELECT d.name,
       HAS_PERMS_BY_NAME(d.name, N'DATABASE', N'VIEW DEFINITION') AS can_view_definition,
       HAS_PERMS_BY_NAME(d.name, N'DATABASE', N'BACKUP DATABASE') AS can_backup
FROM sys.databases AS d
WHERE d.database_id <> 2 AND d.state_desc = N'ONLINE'
ORDER BY d.name;

REVERT;

-- Must not be NULL. If it is (versions before 2019), use a folder that exists and
-- where the SQL Server service account can write.
SELECT CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512)) AS backup_folder;
GO
