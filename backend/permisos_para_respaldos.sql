USE master;
IF USER_ID(N'dbwatch_monitor') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
GO
GRANT EXECUTE ON xp_readerrorlog TO dbwatch_monitor;
GRANT VIEW ANY ERROR LOG TO dbwatch_monitor;
GO


EXECUTE AS LOGIN = 'dbwatch_monitor';
EXEC master.dbo.xp_readerrorlog 0, 1, N'BACKUP failed to complete';
REVERT;


USE master;
GO
DECLARE @sql NVARCHAR(MAX) = N'';

SELECT @sql += N'
USE ' + QUOTENAME(name) + N';
IF USER_ID(N''dbwatch_monitor'') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
ALTER ROLE db_backupoperator ADD MEMBER dbwatch_monitor;
'
FROM sys.databases
WHERE database_id <> 2
  AND state_desc = 'ONLINE';

EXEC (@sql);
GO

USE msdb;
ALTER ROLE db_datareader ADD MEMBER dbwatch_monitor;
GO

EXECUTE AS LOGIN = 'dbwatch_monitor';
SELECT permission_name FROM fn_my_permissions(NULL, 'SERVER')
WHERE permission_name IN ('VIEW ANY ERROR LOG', 'VIEW SERVER STATE');
REVERT;