USE master;
GO
IF DB_ID(N'DbWatchMaintenanceDemo') IS NULL
    CREATE DATABASE DbWatchMaintenanceDemo;
GO
USE DbWatchMaintenanceDemo;
GO
IF OBJECT_ID(N'dbo.dbwatch_sample', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.dbwatch_sample (id int NOT NULL PRIMARY KEY, label nvarchar(100) NULL);
    INSERT dbo.dbwatch_sample(id, label)
        SELECT TOP (1000) ROW_NUMBER() OVER (ORDER BY object_id), N'demo'
        FROM sys.all_objects;
    CREATE STATISTICS dbwatch_id_stats ON dbo.dbwatch_sample(id);
END;
GO
IF COL_LENGTH(N'dbo.dbwatch_sample', N'label') IS NULL
    ALTER TABLE dbo.dbwatch_sample ADD label nvarchar(100) NULL;
GO
CREATE OR ALTER VIEW dbo.dbwatch_view AS SELECT id, label FROM dbo.dbwatch_sample;
GO
CREATE OR ALTER PROCEDURE dbo.dbwatch_missing
AS
    SELECT id FROM dbo.dbwatch_absent;
GO
ALTER TABLE dbo.dbwatch_sample DROP COLUMN label;
GO
