/* =============================================================================
   02 - Demo databases (OPTIONAL)
   -----------------------------------------------------------------------------
   Lets you see every module working without real data. Each block is
   independent and repeatable.

   !! After creating any database, run 03_grant_database_access.sql so that
   !! dbwatch_monitor can access it.
   ============================================================================= */

/* ---- 2.1  DSR_PROD: backups, storage and performance (modules 2, 3, 4) ------
        FULL recovery model is required to take transaction-log backups.      */
USE master;
GO
IF DB_ID(N'DSR_PROD') IS NULL
    CREATE DATABASE DSR_PROD;
GO
IF (SELECT recovery_model_desc FROM sys.databases WHERE name = N'DSR_PROD') <> N'FULL'
    ALTER DATABASE DSR_PROD SET RECOVERY FULL;
GO
USE DSR_PROD;
GO
IF OBJECT_ID(N'dbo.Customer') IS NULL
BEGIN
    CREATE TABLE dbo.Customer (
        CustomerId   INT IDENTITY(1,1) PRIMARY KEY,
        Name         NVARCHAR(100) NOT NULL,
        RegisteredAt DATETIME2 NOT NULL DEFAULT SYSDATETIME()
    );
    INSERT dbo.Customer (Name)
    SELECT TOP (5000) CONCAT(N'Customer ', ROW_NUMBER() OVER (ORDER BY (SELECT NULL)))
    FROM sys.all_objects a CROSS JOIN sys.all_objects b;
END
GO

/* ---- 2.2  testAudit: invalid object (module 5) ------------------------------
        A view that depends on a table that is later dropped ends up with a
        broken reference.                                                     */
USE master;
GO
IF DB_ID(N'testAudit') IS NULL
    CREATE DATABASE testAudit;
GO
USE testAudit;
GO
IF OBJECT_ID(N'dbo.vTmpAudit', N'V') IS NOT NULL DROP VIEW dbo.vTmpAudit;
IF OBJECT_ID(N'dbo.tmpAudit',  N'U') IS NOT NULL DROP TABLE dbo.tmpAudit;
GO
CREATE TABLE dbo.tmpAudit (id INT);
GO
CREATE VIEW dbo.vTmpAudit AS SELECT id FROM dbo.tmpAudit;
GO
DROP TABLE dbo.tmpAudit;
GO

/* ---- 2.3  DbWatchMaintenanceDemo: stale statistics + broken objects (module 6)
        - dbo.dbwatch_sample : table with outdated statistics
        - dbo.dbwatch_view   : view whose underlying column was dropped
        - dbo.dbwatch_proc   : procedure referencing a missing table
                               (dbo.dbwatch_missing)                          */
USE master;
GO
IF DB_ID(N'DbWatchMaintenanceDemo') IS NULL
    CREATE DATABASE DbWatchMaintenanceDemo;
GO
USE DbWatchMaintenanceDemo;
GO
IF OBJECT_ID(N'dbo.dbwatch_proc',    N'P') IS NOT NULL DROP PROCEDURE dbo.dbwatch_proc;
IF OBJECT_ID(N'dbo.dbwatch_view',    N'V') IS NOT NULL DROP VIEW dbo.dbwatch_view;
IF OBJECT_ID(N'dbo.dbwatch_missing', N'U') IS NOT NULL DROP TABLE dbo.dbwatch_missing;
IF OBJECT_ID(N'dbo.dbwatch_sample',  N'U') IS NOT NULL DROP TABLE dbo.dbwatch_sample;
GO
CREATE TABLE dbo.dbwatch_sample (
    id    INT IDENTITY(1,1) CONSTRAINT PK_dbwatch_sample PRIMARY KEY,
    name  NVARCHAR(100) NOT NULL,
    extra INT NULL
);
INSERT dbo.dbwatch_sample (name, extra)
SELECT TOP (2000) CONCAT(N'Row ', ROW_NUMBER() OVER (ORDER BY (SELECT NULL))), 1
FROM sys.all_objects a CROSS JOIN sys.all_objects b;
GO
CREATE VIEW dbo.dbwatch_view AS
    SELECT id, name, extra FROM dbo.dbwatch_sample;
GO
CREATE PROCEDURE dbo.dbwatch_proc AS
BEGIN
    SET NOCOUNT ON;
    SELECT id, name FROM dbo.dbwatch_missing;
END;
GO
-- Stale statistics: modify rows without updating statistics
UPDATE dbo.dbwatch_sample SET name = name + N'.' WHERE id % 2 = 0;
-- Break the view: drop the column it uses
ALTER TABLE dbo.dbwatch_sample DROP COLUMN extra;
GO

/* ---- 2.4  REPAIR script for the maintenance demo ----------------------------
        Run it during the module 6 walkthrough (docs/testing-guide.md), after
        you have seen the errors.

USE DbWatchMaintenanceDemo;
GO
IF COL_LENGTH(N'dbo.dbwatch_sample', N'extra') IS NULL
    ALTER TABLE dbo.dbwatch_sample ADD extra INT NULL;
IF OBJECT_ID(N'dbo.dbwatch_missing', N'U') IS NULL
    CREATE TABLE dbo.dbwatch_missing (id INT NOT NULL PRIMARY KEY, name NVARCHAR(100) NULL);
GO
*/
