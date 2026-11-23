/* =============================================================================
   04 - Maintenance permissions (module 6)
   -----------------------------------------------------------------------------
   Monitoring permissions do not allow modifying statistics or metadata. To let
   module 6 run "Update statistics" and "Fix / recompile", dbwatch_monitor needs
   ALTER on the database objects. This script grants it on ALL user tables,
   views, procedures and functions of the database set in @Database.
   Repeatable: re-run it whenever you add new objects.

   Stricter alternative: grant ALTER only on authorized objects, e.g.
       GRANT ALTER ON OBJECT::dbo.MyTable TO dbwatch_monitor;

   What it covers:
     - UPDATE STATISTICS needs ALTER on the table.
     - sp_refreshsqlmodule needs ALTER on the view / procedure / function.
     - DML triggers are covered by ALTER on their table.
     - SELECT on tables lets the module read the date / modification counter of
       statistics (sys.dm_db_stats_properties). It also allows reading table
       data: set @GrantSelectOnTables = 0 if that is not acceptable.
   Notes: some user-defined types / XML schema collections may need extra
   REFERENCES. Signed, encrypted or SCHEMABINDING modules are left for manual
   review.

   Run as: sysadmin.
   ============================================================================= */
USE master;
GO
SET NOCOUNT ON;

DECLARE @Database            sysname = N'DbWatchMaintenanceDemo'; -- database to maintain (change the name)
DECLARE @GrantSelectOnTables bit     = 1; -- 1 = also SELECT on tables (shows statistics date / counter)

IF DB_ID(@Database) IS NULL
BEGIN
    RAISERROR(N'The database specified in @Database does not exist.', 16, 1);
    RETURN;
END

DECLARE @run    nvarchar(400) = QUOTENAME(@Database) + N'.sys.sp_executesql';
DECLARE @grants nvarchar(max);

-- 1) User and visibility (module requirement: VIEW DEFINITION on the database)
EXEC @run N'
IF USER_ID(N''dbwatch_monitor'') IS NULL
BEGIN
    CREATE USER [dbwatch_monitor] FOR LOGIN [dbwatch_monitor];
END
GRANT VIEW DEFINITION TO [dbwatch_monitor];
GRANT SELECT ON sys.sql_expression_dependencies TO [dbwatch_monitor];';

-- 2) ALTER (and optionally SELECT) on user objects
DECLARE @build nvarchar(max) = N'
SELECT @out = (
    SELECT N''GRANT ALTER ON OBJECT::'' + QUOTENAME(SCHEMA_NAME(o.schema_id)) + N''.'' + QUOTENAME(o.name)
           + N'' TO [dbwatch_monitor];'' + NCHAR(10)
         + CASE WHEN @sel = 1 AND o.type = N''U''
                THEN N''GRANT SELECT ON OBJECT::'' + QUOTENAME(SCHEMA_NAME(o.schema_id)) + N''.'' + QUOTENAME(o.name)
                     + N'' TO [dbwatch_monitor];'' + NCHAR(10)
                ELSE N'''' END
    FROM sys.objects AS o
    WHERE o.is_ms_shipped = 0
      AND o.type IN (N''U'', N''V'', N''P'', N''FN'', N''IF'', N''TF'')
    FOR XML PATH(''''), TYPE).value(''.'', ''nvarchar(max)'');';

EXEC @run @build, N'@sel bit, @out nvarchar(max) OUTPUT', @sel = @GrantSelectOnTables, @out = @grants OUTPUT;

IF @grants IS NOT NULL
    EXEC @run @grants;

PRINT N'Maintenance permissions applied on ' + @Database;
GO
