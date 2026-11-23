/* =============================================================================
   01 - Create the monitoring login and grant server-level permissions
   -----------------------------------------------------------------------------
   Creates the `dbwatch_monitor` login (if missing) and grants the server-level
   permissions used by modules 1-5. Idempotent: if the login already exists its
   password is preserved (unless @ResetPassword = 1); it is only re-enabled and
   its permissions are re-applied.

   Run as: sysadmin (SSMS / Azure Data Studio)
   Before running: replace <STRONG_PASSWORD>
   ============================================================================= */
USE master;
GO

DECLARE @Password      nvarchar(128) = N'<STRONG_PASSWORD>'; -- only used when creating / resetting the login
DECLARE @ResetPassword bit = 0;  -- 1 = change the password of an existing login
DECLARE @AllowKill     bit = 1;  -- 1 = allow the "terminate session" button in Performance (KILL)
DECLARE @sql nvarchar(max);

DECLARE @exists bit = CASE WHEN EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dbwatch_monitor')
                           THEN 1 ELSE 0 END;

IF (@exists = 0 OR @ResetPassword = 1) AND @Password LIKE N'<%>'
BEGIN
    RAISERROR(N'Replace the <STRONG_PASSWORD> placeholder with a real password before running.', 16, 1);
    RETURN;
END

IF @exists = 0
BEGIN
    SET @sql = N'CREATE LOGIN [dbwatch_monitor] WITH PASSWORD = N''' + REPLACE(@Password, N'''', N'''''')
             + N''', CHECK_POLICY = ON, DEFAULT_DATABASE = [master];';
    EXEC (@sql);
    PRINT N'Login dbwatch_monitor created.';
END
ELSE IF @ResetPassword = 1
BEGIN
    SET @sql = N'ALTER LOGIN [dbwatch_monitor] WITH PASSWORD = N''' + REPLACE(@Password, N'''', N'''''')
             + N''' UNLOCK;';
    EXEC (@sql);
    PRINT N'Password of dbwatch_monitor updated.';
END
ELSE
BEGIN
    PRINT N'dbwatch_monitor already exists: its password is preserved.';
END

ALTER LOGIN [dbwatch_monitor] ENABLE;

-- Modules 1-5
GRANT VIEW SERVER STATE    TO [dbwatch_monitor];
GRANT VIEW ANY DEFINITION  TO [dbwatch_monitor];
GRANT CONNECT ANY DATABASE TO [dbwatch_monitor];

-- SQL Server 2022+: new granular permissions (they do not exist in earlier versions)
IF TRY_CONVERT(int, SERVERPROPERTY('ProductMajorVersion')) >= 16
BEGIN
    EXEC (N'GRANT VIEW SERVER PERFORMANCE STATE TO [dbwatch_monitor];');
    EXEC (N'GRANT VIEW ANY ERROR LOG TO [dbwatch_monitor];');
END

-- Module 2: terminate sessions that are blocking others
IF @AllowKill = 1
BEGIN
    GRANT ALTER ANY CONNECTION TO [dbwatch_monitor];
END
ELSE
BEGIN
    REVOKE ALTER ANY CONNECTION FROM [dbwatch_monitor];
END

-- Result
SELECT pr.name AS login_name, pe.permission_name, pe.state_desc
FROM sys.server_permissions AS pe
JOIN sys.server_principals  AS pr ON pr.principal_id = pe.grantee_principal_id
WHERE pr.name = N'dbwatch_monitor'
ORDER BY pe.permission_name;
GO
