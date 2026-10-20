USE DbWatchMaintenanceDemo;
GO
IF COL_LENGTH(N'dbo.dbwatch_sample', N'label') IS NULL
    ALTER TABLE dbo.dbwatch_sample ADD label nvarchar(100) NULL;
IF OBJECT_ID(N'dbo.dbwatch_absent', N'U') IS NULL
    CREATE TABLE dbo.dbwatch_absent (id int NOT NULL);
GO
