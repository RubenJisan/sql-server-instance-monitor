# DBWatch – Monitor de SGBD para SQL Server

Proyecto Integrador EIF402 – Administración de Bases de Datos.
Herramienta web que consulta directamente las vistas administrativas (DMV) y `msdb` de una instancia de Microsoft SQL Server. **No crea ni necesita tablas propias ni migraciones**: todo el estado de la aplicación es el archivo `~/.dbwatch/profiles.json` (perfiles de conexión).

Este README parte de un repositorio recién clonado, sin datos, y deja todo funcionando. Todos los scripts SQL son **idempotentes**: se pueden volver a ejecutar sin romper nada. Si el login `dbwatch_monitor` ya existe, se conserva (y se corrige lo que le falte).

---

## Índice

1. [Resumen y módulos](#1-resumen-y-módulos)
2. [Orden de ejecución (resumen rápido)](#2-orden-de-ejecución-resumen-rápido)
3. [Requisitos](#3-requisitos)
4. [Preparar SQL Server](#4-preparar-sql-server)
5. [Script 1 – Login y permisos de servidor](#5-script-1--login-y-permisos-de-servidor)
6. [Script 2 – Bases de demostración (opcional)](#6-script-2--bases-de-demostración-opcional)
7. [Script 3 – Usuario y permisos en cada base](#7-script-3--usuario-y-permisos-en-cada-base)
8. [Script 4 – Permisos de mantenimiento (Módulo 6)](#8-script-4--permisos-de-mantenimiento-módulo-6)
9. [Script 5 – Verificar permisos](#9-script-5--verificar-permisos)
10. [Ejecutar backend y frontend](#10-ejecutar-backend-y-frontend)
11. [Conectar la aplicación a la instancia](#11-conectar-la-aplicación-a-la-instancia)
12. [Probar cada módulo](#12-probar-cada-módulo)
13. [SQL durante el uso: cuándo volver a ejecutar qué](#13-sql-durante-el-uso-cuándo-volver-a-ejecutar-qué)
14. [Respaldos manuales y limpieza](#14-respaldos-manuales-y-limpieza)
15. [Pruebas y compilación](#15-pruebas-y-compilación)
16. [Diagnóstico rápido](#16-diagnóstico-rápido)
17. [Seguridad](#17-seguridad)

---

## 1. Resumen y módulos

Arquitectura: **frontend** React + Vite (`http://localhost:5173`) → **backend** Spring Boot (`http://localhost:8080`) → **SQL Server** por JDBC, con un único login de monitoreo: `dbwatch_monitor`.

| # | Módulo (pantalla) | Endpoints del backend | De dónde lee | Permiso que necesita |
|---|---|---|---|---|
| 1 | Estado de la instancia (`/instance`) | `GET /api/instance/overview`, `/api/connections` | `SERVERPROPERTY`, `sys.dm_os_sys_info`, `sys.dm_os_host_info`, `sys.dm_os_process_memory`, `sys.dm_os_performance_counters`, `sys.databases`, `sys.master_files` | `VIEW SERVER STATE`, `VIEW ANY DEFINITION` |
| 2 | Rendimiento (`/performance`) | `GET /api/performance/overview?range=1h\|6h\|24h`, `POST /api/performance/sessions/{id}/kill`, `GET /api/performance/export` | `sys.dm_os_ring_buffers`, `sys.dm_os_sys_memory`, `sys.dm_exec_sessions/requests/query_stats`, `sys.dm_tran_locks`, `sys.dm_tran_active_transactions` | `VIEW SERVER STATE` (+ `VIEW SERVER PERFORMANCE STATE` en 2022+); `ALTER ANY CONNECTION` solo para el botón de terminar sesión |
| 3 | Almacenamiento (`/storage`) | `GET /api/storage/overview?database=` | `sys.database_files`, `sys.filegroups`, `sys.dm_db_log_space_usage`, `sys.dm_db_partition_stats`, `msdb.dbo.backupset` (crecimiento) | `VIEW SERVER STATE`, `CONNECT ANY DATABASE`, `SELECT` en `msdb.dbo.backupset` |
| 4 | Respaldo y recuperación (`/backup`) | `GET /api/backups/overview?database=`, `POST /api/backups/full?database=` | `msdb.dbo.backupset`, `msdb.dbo.backupmediafamily`, `xp_readerrorlog`, `SERVERPROPERTY('InstanceDefaultBackupPath')` | `SELECT` en `msdb`, `EXECUTE` en `xp_readerrorlog`, `db_backupoperator` en cada base (para **Ejecutar FULL ahora**) |
| 5 | Auditoría (`/audit`) | `GET /api/audit/overview` | `sys.server_principals`, `sys.sql_logins`, `sys.server_role_members`, `sys.server_permissions`, `sys.sql_expression_dependencies` (por base) | `VIEW ANY DEFINITION`; usuario + `VIEW DEFINITION` en cada base |
| 6 | Mantenimiento preventivo (`/maintenance`) | `GET /api/maintenance/overview?database=`, `POST /api/maintenance/statistics`, `POST /api/maintenance/refresh` | `sys.tables`, `sys.stats`, `sys.dm_db_stats_properties`, `sys.sql_modules`, `sys.dm_sql_referenced_entities`; ejecuta `UPDATE STATISTICS` y `sp_refreshsqlmodule` | `VIEW DEFINITION` + `ALTER` sobre los objetos a mantener |

---

## 2. Orden de ejecución (resumen rápido)

Desde un clon limpio:

| Paso | Qué | Dónde |
|---|---|---|
| 1 | Habilitar TCP/IP, fijar puerto y autenticación mixta | [Sección 4](#4-preparar-sql-server) |
| 2 | **Script 1**: login `dbwatch_monitor` y permisos de servidor | [Sección 5](#5-script-1--login-y-permisos-de-servidor) |
| 3 | *(Opcional)* **Script 2**: crear bases de demostración | [Sección 6](#6-script-2--bases-de-demostración-opcional) |
| 4 | **Script 3**: usuario y permisos en cada base (ejecutar **después** de crear bases) | [Sección 7](#7-script-3--usuario-y-permisos-en-cada-base) |
| 5 | **Script 4**: permisos de mantenimiento en la base a mantener | [Sección 8](#8-script-4--permisos-de-mantenimiento-módulo-6) |
| 6 | **Script 5**: verificar | [Sección 9](#9-script-5--verificar-permisos) |
| 7 | Levantar backend y frontend | [Sección 10](#10-ejecutar-backend-y-frontend) |
| 8 | Configurar la conexión en la pantalla *Estado de la instancia* | [Sección 11](#11-conectar-la-aplicación-a-la-instancia) |
| 9 | Probar los módulos | [Sección 12](#12-probar-cada-módulo) |

Todos los scripts se ejecutan en SSMS o Azure Data Studio con una sesión **sysadmin** (por ejemplo, autenticación de Windows del propio equipo). Si no quieres demos, omite el paso 3 y en el paso 5 usa tu propia base.

---

## 3. Requisitos

- JDK 25 y Maven (configuración original). Con JDK 21, usar `"-Djava.version=21"` en los comandos Maven de la sección 10.
- Node.js 22.12 o posterior compatible con Vite 8; Node.js 24 LTS es adecuado. npm viene incluido.
- Microsoft SQL Server 2017 o superior (Express o Developer). Las consultas usan `sys.dm_os_host_info`, que no existe en versiones anteriores.
- SQL Server Management Studio (SSMS) o Azure Data Studio.

Estructura esperada del repositorio (la que usan los comandos de este README):

```
proyecto_bd2/
├── backend/    ← proyecto Maven (src/main/java/grupo1/backend/..., src/main/resources/application.properties)
└── frontend/   ← proyecto Vite/React (src/pages/<modulo>/...)
```

---

## 4. Preparar SQL Server

### 4.1 Habilitar TCP/IP y fijar el puerto

SQL Server Express trae TCP/IP deshabilitado y usa un puerto dinámico. La aplicación (Java) necesita TCP/IP.

1. Abrir **SQL Server Configuration Manager**.
2. *Configuración de red de SQL Server* → *Protocolos de SQLEXPRESS* → **TCP/IP** → *Habilitado*.
3. Propiedades de TCP/IP → pestaña *Direcciones IP* → sección **IPAll**:
   - *Puertos dinámicos TCP*: dejar **vacío**.
   - *Puerto TCP*: `1433`.
4. Reiniciar el servicio (PowerShell como administrador):

```powershell
Restart-Service 'MSSQL$SQLEXPRESS'
```

El nombre debe coincidir con la instalación real. Para una instancia predeterminada `MSSQLSERVER`, reiniciar con `Restart-Service MSSQLSERVER`; en ese caso, el campo **Instancia** de la aplicación se deja vacío.

5. Comprobar que el puerto responde:

```powershell
Test-NetConnection localhost -Port 1433
```

Debe mostrar `TcpTestSucceeded : True`.

Si prefieres no fijar el puerto, averigua el que usa tu instancia y úsalo en la aplicación:

```sql
EXEC sys.xp_readerrorlog 0, 1, N'listening on';
```

### 4.2 Autenticación mixta

El usuario de monitoreo usa autenticación de SQL Server.

```sql
SELECT SERVERPROPERTY('IsIntegratedSecurityOnly') AS soloWindows;
```

- `0`: la autenticación mixta ya está activa.
- `1`: cambiarla en SSMS (*Propiedades del servidor → Seguridad → Modo de autenticación de SQL Server y Windows*) y reiniciar el servicio.

### 4.3 (Alternativa) SQL Server en Docker

Si no quieres instalar SQL Server en el equipo:

```powershell
docker run -e "ACCEPT_EULA=Y" -e "MSSQL_SA_PASSWORD=<SA_PASSWORD_FUERTE>" -p 1433:1433 --name sqlserver -d mcr.microsoft.com/mssql/server:2022-latest
```

Conéctate desde SSMS con `localhost,1433` y el usuario `sa` para ejecutar los scripts de este README. En la aplicación usa servidor `localhost`, instancia vacía, puerto `1433`, y activa *Confiar en el certificado*. Autenticación mixta ya viene activa en el contenedor. La carpeta de respaldos por defecto es `/var/opt/mssql/data`.

---

## 5. Script 1 – Login y permisos de servidor

Crea el login `dbwatch_monitor` si no existe y concede los permisos de servidor que usan los módulos 1 a 5. **Si el login ya existe, no cambia su contraseña** (salvo que pongas `@ResetPassword = 1`), solo lo habilita y asegura los permisos.

Antes de ejecutar, reemplaza `<CONTRASEÑA_SEGURA>`.

```sql
USE master;
GO

DECLARE @Password      nvarchar(128) = N'<CONTRASEÑA_SEGURA>'; -- solo se usa al crear el login o al resetearlo
DECLARE @ResetPassword bit = 0;  -- 1 = cambia la contraseña de un login que ya existe
DECLARE @AllowKill     bit = 1;  -- 1 = permite el botón "terminar sesión" de Rendimiento (KILL)
DECLARE @sql nvarchar(max);

DECLARE @exists bit = CASE WHEN EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dbwatch_monitor')
                           THEN 1 ELSE 0 END;

IF (@exists = 0 OR @ResetPassword = 1) AND @Password LIKE N'<%>'
BEGIN
    RAISERROR(N'Reemplace <CONTRASEÑA_SEGURA> por una contraseña real antes de ejecutar.', 16, 1);
    RETURN;
END

IF @exists = 0
BEGIN
    SET @sql = N'CREATE LOGIN [dbwatch_monitor] WITH PASSWORD = N''' + REPLACE(@Password, N'''', N'''''')
             + N''', CHECK_POLICY = ON, DEFAULT_DATABASE = [master];';
    EXEC (@sql);
    PRINT N'Login dbwatch_monitor creado.';
END
ELSE IF @ResetPassword = 1
BEGIN
    SET @sql = N'ALTER LOGIN [dbwatch_monitor] WITH PASSWORD = N''' + REPLACE(@Password, N'''', N'''''')
             + N''' UNLOCK;';
    EXEC (@sql);
    PRINT N'Contraseña de dbwatch_monitor actualizada.';
END
ELSE
BEGIN
    PRINT N'dbwatch_monitor ya existe: se conserva su contraseña.';
END

ALTER LOGIN [dbwatch_monitor] ENABLE;

-- Módulos 1 a 5
GRANT VIEW SERVER STATE    TO [dbwatch_monitor];
GRANT VIEW ANY DEFINITION  TO [dbwatch_monitor];
GRANT CONNECT ANY DATABASE TO [dbwatch_monitor];

-- SQL Server 2022 o posterior: permisos nuevos (en versiones anteriores no existen)
IF TRY_CONVERT(int, SERVERPROPERTY('ProductMajorVersion')) >= 16
BEGIN
    EXEC (N'GRANT VIEW SERVER PERFORMANCE STATE TO [dbwatch_monitor];');
    EXEC (N'GRANT VIEW ANY ERROR LOG TO [dbwatch_monitor];');
END

-- Módulo 2: terminar sesiones que bloquean a otras
IF @AllowKill = 1
BEGIN
    GRANT ALTER ANY CONNECTION TO [dbwatch_monitor];
END
ELSE
BEGIN
    REVOKE ALTER ANY CONNECTION FROM [dbwatch_monitor];
END

-- Resultado
SELECT pr.name AS login, pe.permission_name, pe.state_desc
FROM sys.server_permissions AS pe
JOIN sys.server_principals  AS pr ON pr.principal_id = pe.grantee_principal_id
WHERE pr.name = N'dbwatch_monitor'
ORDER BY pe.permission_name;
GO
```

Notas:

- Con `CHECK_POLICY = ON`, SQL Server rechaza contraseñas que no cumplan la política de Windows (longitud, complejidad).
- `ALTER ANY CONNECTION` permite terminar cualquier sesión. Si no necesitas el botón de terminar sesión, pon `@AllowKill = 0`: el resto de Rendimiento sigue funcionando.
- Si prefieres otro nombre de login, reemplaza `dbwatch_monitor` en todos los scripts y en la pantalla de conexión.

---

## 6. Script 2 – Bases de demostración (opcional)

Sirven para ver cada módulo funcionando sin datos reales. Cada bloque es independiente y se puede repetir: si la base o los objetos ya existen, los deja en el estado esperado.

> **Después de crear cualquier base, ejecuta (o vuelve a ejecutar) el [Script 3](#7-script-3--usuario-y-permisos-en-cada-base)** para que `dbwatch_monitor` tenga acceso a ella.

### 6.1 `DSR_PROD` – respaldos, almacenamiento y rendimiento (Módulos 2, 3, 4)

El modelo de recuperación FULL es necesario para poder hacer respaldos de LOG.

```sql
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
IF OBJECT_ID(N'dbo.Cliente') IS NULL
BEGIN
    CREATE TABLE dbo.Cliente (
        ClienteId INT IDENTITY(1,1) PRIMARY KEY,
        Nombre NVARCHAR(100) NOT NULL,
        FechaRegistro DATETIME2 NOT NULL DEFAULT SYSDATETIME()
    );
    INSERT dbo.Cliente (Nombre)
    SELECT TOP (5000) CONCAT(N'Cliente ', ROW_NUMBER() OVER (ORDER BY (SELECT NULL)))
    FROM sys.all_objects a CROSS JOIN sys.all_objects b;
END
GO
```

### 6.2 `testAudit` – objeto inválido (Módulo 5)

Una vista que depende de una tabla que luego se elimina queda con una referencia rota.

```sql
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
```

### 6.3 `DbWatchMaintenanceDemo` – estadísticas y objetos rotos (Módulo 6)

Crea una tabla `dbo.dbwatch_sample` con estadísticas desactualizadas, una vista `dbo.dbwatch_view` cuya columna fue eliminada y un procedimiento `dbo.dbwatch_proc` que referencia una tabla inexistente `dbo.dbwatch_missing`.

```sql
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
SELECT TOP (2000) CONCAT(N'Fila ', ROW_NUMBER() OVER (ORDER BY (SELECT NULL))), 1
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
-- Estadísticas desactualizadas: modifica filas sin actualizar estadísticas
UPDATE dbo.dbwatch_sample SET name = name + N'.' WHERE id % 2 = 0;
-- Rompe la vista: elimina la columna que usa
ALTER TABLE dbo.dbwatch_sample DROP COLUMN extra;
GO
```

Para **reparar** los objetos (úsalo en la prueba de la sección 12.6, después de haber visto los errores):

```sql
USE DbWatchMaintenanceDemo;
GO
IF COL_LENGTH(N'dbo.dbwatch_sample', N'extra') IS NULL
    ALTER TABLE dbo.dbwatch_sample ADD extra INT NULL;
IF OBJECT_ID(N'dbo.dbwatch_missing', N'U') IS NULL
    CREATE TABLE dbo.dbwatch_missing (id INT NOT NULL PRIMARY KEY, name NVARCHAR(100) NULL);
GO
```

### 6.4 Eliminar las bases de demostración

```sql
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
```

---

## 7. Script 3 – Usuario y permisos en cada base

Recorre **todas las bases en línea** (excepto `tempdb`, solo lectura y snapshots) y, en cada una:

- crea el usuario `dbwatch_monitor` si no existe, o lo **reasocia al login** si quedó huérfano (por ejemplo, tras restaurar la base en otro servidor);
- concede `VIEW DEFINITION` y `SELECT` sobre `sys.sql_expression_dependencies` (Módulos 5 y 6: detección de objetos inválidos);
- lo agrega al rol `db_backupoperator` (Módulo 4: botón **Ejecutar FULL ahora**, que respalda todas las bases en línea, incluidas las de sistema).

Además concede en `msdb` la lectura del historial (`backupset`, `backupmediafamily`) y en `master` el `EXECUTE` sobre `xp_readerrorlog` (para mostrar respaldos fallidos).

```sql
USE master;
GO
SET NOCOUNT ON;

IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dbwatch_monitor')
BEGIN
    RAISERROR(N'El login dbwatch_monitor no existe. Ejecute primero el Script 1.', 16, 1);
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
```

> **Vuelve a ejecutarlo cada vez que crees o restaures una base de datos** que quieras monitorear, auditar o respaldar desde la aplicación. Una base de solo lectura no se procesa (no se pueden crear usuarios en ella); el módulo de respaldos la informará como fallo al respaldarla.

No se agrega el login a `db_owner` ni a `sysadmin`: es más poder del necesario.

---

## 8. Script 4 – Permisos de mantenimiento (Módulo 6)

Los permisos de monitoreo no autorizan a modificar estadísticas ni metadatos. Para que el Módulo 6 pueda ejecutar **Actualizar estadísticas** y **Corregir / recompilar**, `dbwatch_monitor` necesita `ALTER` sobre los objetos de la base. Este script los concede sobre **todas las tablas, vistas, procedimientos y funciones de usuario** de la base indicada (en `@Database`). Es repetible: ejecútalo de nuevo cuando agregues objetos nuevos.

```sql
USE master;
GO
SET NOCOUNT ON;

DECLARE @Database            sysname = N'DbWatchMaintenanceDemo'; -- base a mantener (cambie el nombre)
DECLARE @GrantSelectOnTables bit     = 1; -- 1 = también SELECT en las tablas (ver fecha/contador de estadísticas)

IF DB_ID(@Database) IS NULL
BEGIN
    RAISERROR(N'La base indicada en @Database no existe.', 16, 1);
    RETURN;
END

DECLARE @run    nvarchar(400) = QUOTENAME(@Database) + N'.sys.sp_executesql';
DECLARE @grants nvarchar(max);

-- 1) Usuario y visibilidad (requisito del módulo: VIEW DEFINITION en la base)
EXEC @run N'
IF USER_ID(N''dbwatch_monitor'') IS NULL
BEGIN
    CREATE USER [dbwatch_monitor] FOR LOGIN [dbwatch_monitor];
END
GRANT VIEW DEFINITION TO [dbwatch_monitor];
GRANT SELECT ON sys.sql_expression_dependencies TO [dbwatch_monitor];';

-- 2) ALTER (y opcionalmente SELECT) sobre los objetos de usuario
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

PRINT N'Permisos de mantenimiento aplicados en ' + @Database;
GO
```

Qué cubre y qué no:

- `UPDATE STATISTICS` necesita `ALTER` sobre la tabla. `sp_refreshsqlmodule` necesita `ALTER` sobre la vista, procedimiento o función. Los triggers DML se cubren con el `ALTER` de su tabla.
- `SELECT` sobre las tablas permite leer **fecha y contador de modificaciones** de las estadísticas (`sys.dm_db_stats_properties`); sin él esas columnas aparecen como `—`. Ese permiso también permite leer los datos de la tabla: ponlo en `0` si no corresponde.
- Si quieres un criterio más estricto, en lugar de este script concede `ALTER` solo a los objetos autorizados: `GRANT ALTER ON OBJECT::dbo.MiTabla TO dbwatch_monitor;`.
- Algunos tipos definidos por el usuario o colecciones de esquemas XML pueden requerir `REFERENCES` adicional. Los módulos firmados, cifrados o con `SCHEMABINDING` se reservan para revisión manual. Si falta una tabla o columna, el módulo informa el error y no la restaura.

---

## 9. Script 5 – Verificar permisos

Ejecuta las comprobaciones **como** `dbwatch_monitor` (usa `EXECUTE AS`, que requiere una sesión sysadmin).

```sql
USE master;
GO
EXECUTE AS LOGIN = N'dbwatch_monitor';

SELECT SUSER_NAME() AS ejecutandoComo;

-- Permisos de servidor efectivos (esperados: los 3 primeros siempre; los demás según versión y @AllowKill)
SELECT permission_name
FROM sys.fn_my_permissions(NULL, N'SERVER')
WHERE permission_name IN (N'VIEW SERVER STATE', N'VIEW ANY DEFINITION', N'CONNECT ANY DATABASE',
                          N'ALTER ANY CONNECTION', N'VIEW SERVER PERFORMANCE STATE', N'VIEW ANY ERROR LOG')
ORDER BY permission_name;

-- Módulos 1 y 2
SELECT cpu_count FROM sys.dm_os_sys_info;
SELECT COUNT(*) AS sesiones FROM sys.dm_exec_sessions;

-- Módulos 3 y 4
SELECT COUNT(*) AS lectura_backupset_ok      FROM msdb.dbo.backupset;
SELECT COUNT(*) AS lectura_backupmedia_ok    FROM msdb.dbo.backupmediafamily;
EXEC master.dbo.xp_readerrorlog 0, 1, N'BACKUP failed to complete';   -- puede dar 0 filas, pero sin error

-- Por base: NULL o 0 significa que falta el usuario o el permiso (vuelva a ejecutar el Script 3)
SELECT d.name,
       HAS_PERMS_BY_NAME(d.name, N'DATABASE', N'VIEW DEFINITION') AS verDefinicion,
       HAS_PERMS_BY_NAME(d.name, N'DATABASE', N'BACKUP DATABASE') AS puedeRespaldar
FROM sys.databases AS d
WHERE d.database_id <> 2 AND d.state_desc = N'ONLINE'
ORDER BY d.name;

REVERT;

SELECT CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512)) AS carpeta_respaldos;
GO
```

- `carpeta_respaldos` no debe ser `NULL`. Si lo es (versiones anteriores a 2019), usa una carpeta que exista y donde SQL Server pueda escribir.
- La carpeta destino está en el equipo de SQL Server y la cuenta del **servicio SQL Server** debe poder escribir en ella. El permiso de Windows de quien abre el navegador no sustituye este requisito. En Docker ya puede.
- Para ver con certeza si un permiso falla, la prueba definitiva es usar el módulo correspondiente: los avisos de la pantalla indican qué consulta no se pudo ejecutar.

---

## 10. Ejecutar backend y frontend

Desde `proyecto_bd2`, abrir dos terminales. Con JDK 25:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Con JDK 21, usar en su lugar:

```powershell
cd backend
.\mvnw.cmd "-Djava.version=21" spring-boot:run
```

En la segunda terminal:

```powershell
cd frontend
npm ci
npm run dev
```

Abrir http://localhost:5173 y mantener ambas terminales abiertas. El backend escucha en http://localhost:8080. No ejecutar `NavItems.jsx` ni otros archivos JSX con Node directamente: Vite transforma React y resuelve los imports.

Configuración del backend (`backend/src/main/resources/application.properties`):

| Propiedad | Valor | Qué hace |
|---|---|---|
| `server.address` | `127.0.0.1` | El backend solo acepta conexiones locales |
| `server.port` | `8080` | Puerto del backend |
| `app.allowedOrigin` | `http://localhost:5173` | Origen permitido por CORS; debe coincidir con la URL real del frontend (ajústalo si Vite anuncia otro puerto) |
| `app.configDir` | `${user.home}/.dbwatch` | Carpeta donde se guarda `profiles.json` |
| `app.maintenance.timeoutSeconds` | `300` | Timeout de cada acción de mantenimiento, sin tocar el de los otros módulos |

El frontend llama a `http://localhost:8080/api`. La pantalla de Mantenimiento acepta cambiarlo con la variable `VITE_API_URL`; las demás tienen la URL fija en cada `*.jsx`.

---

## 11. Conectar la aplicación a la instancia

En *Estado de la instancia* → **Configurar conexión**:

| Campo | Valor |
|---|---|
| Nombre | el que quieras (ej. `Local`) |
| Servidor | `localhost` (solo letras, números, `.`, `_` y `-`: **no** escribas `localhost\SQLEXPRESS` aquí) |
| Instancia | `SQLEXPRESS` (según tu instalación; vacío si es la predeterminada) |
| Puerto | `1433` o `49355` (o el que uses). **Si indicas puerto, el nombre de instancia se ignora** |
| Autenticación | SQL Server |
| Usuario | `dbwatch_monitor` |
| Contraseña | la definida en el Script 1 |
| Cifrar conexión | activado |
| Confiar en el certificado | activado (certificado autofirmado local) |
| Intervalo de refresco | el deseado (mínimo 5 s) |

Pulsa **Probar conexión** y luego **Guardar**. La conexión queda guardada en `~/.dbwatch/profiles.json` y se reactiva sola al reiniciar el backend. Para cambiar de instancia sin tocar el código, usa el selector de la parte superior de cada pantalla.

Verificar que quede una conexión **activa**; si guardar no la activa, usar la opción Conectar. Mantenimiento reutiliza esa misma conexión; su enlace Configurar conexión abre el módulo de instancia.

Los pasos anteriores usan autenticación de **SQL Server** y no requieren una DLL de autenticación de Windows. Si se elige Windows integrada, el código usa `integratedSecurity=true`: instalar la DLL `mssql-jdbc_auth` correspondiente a la versión del driver y a la arquitectura de la JVM, disponible para el proceso Java mediante PATH o `java.library.path`. Sin esa biblioteca, elegir Windows puede fallar aunque la conexión funcione en SSMS. Referencia: [autenticación integrada del driver JDBC de Microsoft](https://learn.microsoft.com/en-us/sql/connect/jdbc/building-the-connection-url).

---

## 12. Probar cada módulo

Con las bases demo creadas (sección 6), el Script 3 ejecutado después de crearlas y el Script 4 aplicado a `DbWatchMaintenanceDemo`.

### 12.1 Módulo 1 – Estado de la instancia

No requiere datos. Debe mostrar versión, edición, tiempo activo, CPU y memoria física, memoria del servidor, sesiones de usuario y la lista de bases con tamaño y modelo de recuperación (las de sistema aparecen aparte).

### 12.2 Módulo 2 – Rendimiento

CPU y memoria se leen de los *ring buffers* de SQL Server, así que hay gráfica desde el primer momento. El backend además acumula en memoria muestras (una cada 30 s como mínimo, hasta 25 h) para los rangos 6 h y 24 h; **esa historia se pierde si reinicias el backend** y los rangos largos se llenan con el tiempo.

El monitor ignora las sesiones cuyo `program_name` es `Microsoft JDBC Driver for SQL Server` (son las suyas). Las ventanas de SSMS sí se ven.

**Carga de CPU** (en una ventana de SSMS; se cancela con *Detener*):

```sql
USE DSR_PROD;
DECLARE @i int = 0;
WHILE @i < 20
BEGIN
    SELECT COUNT_BIG(*) FROM dbo.Cliente a CROSS JOIN dbo.Cliente b
    CROSS JOIN (SELECT TOP (4) 1 AS x FROM sys.all_objects) c;
    SET @i += 1;
END
```

Debe aparecer la consulta en el ranking de consultas costosas y la sesión en los procesos activos.

**Bloqueo y terminar sesión.** Abre dos ventanas de SSMS:

```sql
-- Ventana A (deja la transacción abierta, sin COMMIT)
USE DSR_PROD;
BEGIN TRAN;
UPDATE dbo.Cliente SET Nombre = Nombre WHERE ClienteId = 1;
```

```sql
-- Ventana B (queda esperando)
USE DSR_PROD;
UPDATE dbo.Cliente SET Nombre = N'bloqueado' WHERE ClienteId = 1;
```

En la sección de bloqueos debe verse la sesión de B esperando a la de A, con el objeto bloqueado. El botón de terminar sesión (requiere `ALTER ANY CONNECTION`, `@AllowKill = 1` en el Script 1) solo actúa sobre una sesión que esté bloqueando a otras. Al terminar, la ventana A pierde la conexión. Si no lo usas, cierra con `ROLLBACK;` en la ventana A.

**Exportar:** `GET /api/performance/export?format=pdf|xlsx|csv&range=1h&sections=queries,sessions,blocking,chart` (también desde el botón de la pantalla).

### 12.3 Módulo 3 – Almacenamiento

Elige `DSR_PROD` en el selector: archivos de datos y log con tamaño y crecimiento, uso del log, y los 10 objetos (tablas/índices) más grandes. La gráfica de **crecimiento mensual** sale de los respaldos FULL de `msdb` de los últimos 6 meses: en un entorno recién creado solo tendrá el mes actual, y solo después de hacer al menos un FULL (sección 14).

### 12.4 Módulo 4 – Respaldo y recuperación

Haz las pruebas **en orden**, empezando por la limpieza de la sección 14.2, y recarga la página después de cada paso. Los valores esperados suponen que `DSR_PROD` es la **única base de usuario**; si tienes más (por ejemplo las otras demos), cambian los conteos de "N base(s)" y la lista de bases sin respaldo.

**Prueba 0 – estado vacío** (no hagas ningún respaldo todavía)

| Panel | Valor esperado |
|---|---|
| Banner | Rojo: "Riesgo alto detectado", no existe ningún respaldo completo exitoso |
| Último respaldo exitoso | "—", "Sin respaldos exitosos registrados" |
| Tasa de éxito (14 d) | 0.0 % en rojo, "0 de 14 correctos" |
| Historial | "Sin datos" |
| Tira de 14 días | Las 14 barras grises |
| Política de retención | Sin filas; modelo de recuperación "MIXTO"; destino y verificación "—" |

**Prueba 1 – un FULL solo de `DSR_PROD`**: ejecuta la [plantilla de respaldo](#141-plantilla-respaldar-una-base-full--diff--log) con `@db = N'DSR_PROD'` y `@tipo = 'FULL'`.

| Panel | Valor esperado |
|---|---|
| Banner | Amarillo: "Riesgo moderado detectado", 3 base(s) sin respaldo completo exitoso: master, model, msdb |
| Último respaldo exitoso | Hoy, "hace 0 h", en verde; tipo "Completo" |
| Tasa de éxito | 7.1 % en rojo, "1 de 14 correctos" |
| Historial | 1 fila: `DSR_PROD`, FULL, tamaño en MB, "Exitoso" |
| Política | FULL con frecuencia "Sin datos suficientes"; verificación "CHECKSUM"; destino = carpeta de respaldos |

**Prueba 2 – FULL de las bases de sistema**: repite la plantilla con `@db` = `master`, `model` y `msdb` (tipo `FULL`).

| Panel | Valor esperado |
|---|---|
| Banner | Verde: "Respaldos en buen estado", último FULL "hace 0 h", sin fallos en 14 días |
| Historial | 4 filas, la más reciente primero: `msdb`, `model`, `master`, `DSR_PROD`, todas FULL y "Exitoso" |
| Política | FULL con frecuencia en minutos, por ejemplo "Cada ~1 min" |

**Prueba 3 – botón "Ejecutar FULL ahora"**: abre *Ver política de retención* → **Ejecutar FULL ahora**.

| Resultado | Valor esperado |
|---|---|
| Mensaje | "Respaldo FULL completado (4 base(s))" |
| Historial | Las 10 filas más recientes, todas FULL y "Exitoso" (ya hay 8 FULL) |
| Caja roja | No debe aparecer. Si aparece, muestra el motivo por base (casi siempre: falta el Script 3 o la carpeta no es escribible por el servicio de SQL Server) |

**Prueba 4 – DIFF y LOG de `DSR_PROD`**: con la plantilla, ejecuta `DIFF` y luego dos veces `LOG` (inserta una fila entre cada uno, como se muestra en la plantilla).

| Panel | Valor esperado |
|---|---|
| Último respaldo exitoso | Tipo "Log de transacciones" |
| Historial | Aparecen filas DIFF y LOG de `DSR_PROD` (solo los 10 más recientes) |
| Política | Tres filas en este orden: FULL, DIFF, LOG; LOG con frecuencia en minutos; DIFF "Sin datos suficientes" |
| Banner | Sigue verde |

**Respaldos fallidos:** se leen del log de errores de SQL Server (mensaje `BACKUP failed to complete`). Para ver uno, lanza un respaldo a una carpeta inexistente y recarga:

```sql
BACKUP DATABASE DSR_PROD TO DISK = N'C:\CarpetaQueNoExiste\x.bak' WITH CHECKSUM, INIT;
```

Debe aparecer una fila fallida en el historial y una barra roja ese día. Para dejar de verlo, ciclar el log (sección 14.2).

### 12.5 Módulo 5 – Auditoría

Muestra logins (con política de contraseña y vencimiento), roles de servidor con sus miembros, privilegios y **objetos inválidos** por base de usuario. Con `testAudit` creada y el Script 3 ejecutado, `testAudit.dbo.vTmpAudit` debe aparecer en *Auditoría → Objetos inválidos*, con la referencia rota a `dbo.tmpAudit`.

Para ver logins marcados como débiles (sin política ni vencimiento), puedes crear uno de prueba **sin ningún permiso**:

```sql
USE master;
GO
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dbwatch_demo_debil')
    CREATE LOGIN dbwatch_demo_debil WITH PASSWORD = N'Demo#2026-cambiar', CHECK_POLICY = OFF, CHECK_EXPIRATION = OFF;
GO
-- Limpieza:  DROP LOGIN dbwatch_demo_debil;
```

### 12.6 Módulo 6 – Mantenimiento preventivo

1. Selecciona `DbWatchMaintenanceDemo`. Debe verse una sola opción *Mantenimiento preventivo* en el menú.
2. Revisa estadísticas y objetos con problemas: `dbwatch_sample` con estadísticas y modificaciones pendientes; `dbwatch_view` (columna `extra` inexistente) y `dbwatch_proc` (tabla `dbwatch_missing` faltante) como objetos con error. Si aparecen avisos de permisos o análisis incompleto, corrígelos antes de interpretar una lista vacía como ausencia de errores.
3. Pulsa **Actualizar estadísticas**, cancela y comprueba que no se ejecuta. Repite confirmando y comprueba el resultado y las fechas. Prueba FULLSCAN solo en una tabla pequeña (como esta).
4. Pulsa **Corregir / recompilar** sobre `dbwatch_view` y `dbwatch_proc` *sin reparar nada antes*: debe informar un fallo o trabajo pendiente, no un éxito definitivo.
5. Ejecuta el script de **reparación** de la sección 6.3. Sobre la lista ya cargada, ejecuta **Corregir / recompilar** de nuevo y comprueba el resultado y el nuevo análisis. Si actualizas el análisis primero, los problemas ya resueltos dejan de aparecer.
6. Comprueba el error al retirar temporalmente el permiso y restáuralo después:

```sql
USE DbWatchMaintenanceDemo;
REVOKE ALTER ON OBJECT::dbo.dbwatch_sample FROM dbwatch_monitor;   -- la acción debe fallar con error de permisos
GRANT  ALTER ON OBJECT::dbo.dbwatch_sample TO   dbwatch_monitor;   -- restaurar
```

La corrección actualiza metadatos y comprueba dependencias; no ejecuta el cuerpo de procedimientos ni garantiza que SQL dinámico, referencias externas o todo el código puedan ejecutarse con cualquier dato. Si hay timeout o se pierde la conexión, verifica el estado antes de repetir: no se reintentan escrituras automáticamente. No hace respaldos ni restauraciones.

---

## 13. SQL durante el uso: cuándo volver a ejecutar qué

| Situación | Qué ejecutar |
|---|---|
| Creaste o restauraste una base de datos nueva | **Script 3** (usuario + permisos en todas las bases) |
| Quieres mantener (estadísticas/recompilar) una base | **Script 3** y luego **Script 4** con `@Database` apuntando a esa base |
| Agregaste tablas, vistas o procedimientos a una base ya mantenida | **Script 4** otra vez |
| Cambiaste la contraseña del login | **Script 1** con `@ResetPassword = 1` y actualiza el perfil en la aplicación (pantalla de conexión) |
| Actualizaste SQL Server a 2022 o posterior | **Script 1** (agrega `VIEW SERVER PERFORMANCE STATE` y `VIEW ANY ERROR LOG`) |
| Quieres quitar el botón de terminar sesión | **Script 1** con `@AllowKill = 0` |
| Restauraste una base y el usuario quedó huérfano | **Script 3** (lo reasocia al login automáticamente) |
| Algo no se ve en un módulo | **Script 5** (verificación) y los avisos de la pantalla |
| Quieres repetir las pruebas de respaldo desde cero | Limpieza de la sección 14.2 |
| Quieres reiniciar las demos | Eliminar demos (6.4) y volver a ejecutar 6.1–6.3, luego Script 3 y 4 |

---

## 14. Respaldos manuales y limpieza

### 14.1 Plantilla: respaldar una base (FULL / DIFF / LOG)

Escribe en el historial de `msdb` exactamente lo que lee el Módulo 4. Cambia `@db` y `@tipo`. Usa `WITH CHECKSUM, INIT`, igual que el botón **Ejecutar FULL ahora**: **`INIT` sobrescribe el archivo si ya existe**; cada ejecución lleva fecha y hora en el nombre para no pisar los anteriores.

```sql
USE master;
DECLARE @db    sysname     = N'DSR_PROD';   -- base a respaldar
DECLARE @tipo  varchar(4)  = 'FULL';        -- FULL | DIFF | LOG (DIFF y LOG requieren un FULL previo; LOG requiere recuperación FULL)

DECLARE @dir   nvarchar(512) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512));
DECLARE @sep   nvarchar(1)   = CASE WHEN @dir LIKE '%/%' THEN N'/' ELSE N'\' END;
DECLARE @stamp varchar(15)   = FORMAT(SYSDATETIME(), 'yyyyMMdd_HHmmss');
DECLARE @ext   nvarchar(4)   = CASE WHEN @tipo = 'LOG' THEN N'.trn' ELSE N'.bak' END;
DECLARE @f     nvarchar(600) = @dir + @sep + @db + N'_' + @tipo + N'_' + @stamp + @ext;

IF @tipo = 'FULL'
    BACKUP DATABASE @db TO DISK = @f WITH CHECKSUM, INIT;
ELSE IF @tipo = 'DIFF'
    BACKUP DATABASE @db TO DISK = @f WITH DIFFERENTIAL, CHECKSUM, INIT;
ELSE IF @tipo = 'LOG'
    BACKUP LOG @db TO DISK = @f WITH CHECKSUM, INIT;

PRINT N'Respaldo escrito en: ' + @f;
```

Para la Prueba 4, ejecuta en este orden (cambiando `@tipo` y el insert entre ellos):

```sql
INSERT DSR_PROD.dbo.Cliente (Nombre) VALUES (N'Nuevo 1');   -- luego plantilla con @tipo = 'DIFF'
INSERT DSR_PROD.dbo.Cliente (Nombre) VALUES (N'Nuevo 2');   -- luego plantilla con @tipo = 'LOG'
INSERT DSR_PROD.dbo.Cliente (Nombre) VALUES (N'Nuevo 3');   -- luego plantilla con @tipo = 'LOG'
```

Notas sobre el botón **Ejecutar FULL ahora**:

- Respalda **todas las bases en línea** (usuario y sistema, excepto `tempdb` y snapshots), cada una en la carpeta del último respaldo conocido de esa base, o en la carpeta por defecto de la instancia si no tiene historial.
- Requiere `db_backupoperator` en cada base (Script 3) y que la cuenta del **servicio SQL Server** pueda escribir en la carpeta.
- Cada consulta del backend tiene un **timeout de 30 s** (`QUERY_TIMEOUT_SECONDS` en `ActiveConnection`). Un FULL de una base grande puede superarlo y cancelarse desde la aplicación; para bases grandes, ejecuta el respaldo desde SSMS con la plantilla.

### 14.2 Limpiar los respaldos

Úsalo para volver al estado inicial antes de repetir las pruebas.

**a) Historial de `msdb` (lo que lee el módulo):**

```sql
EXEC msdb.dbo.sp_delete_backuphistory @oldest_date = '2099-01-01';
SELECT COUNT(*) AS filas_restantes FROM msdb.dbo.backupset;   -- debe dar 0
```

**b) Fallos anteriores del log de errores.** Abre un log nuevo y los fallos viejos dejan de verse:

```sql
EXEC master.dbo.sp_cycle_errorlog;
```

**c) Archivos del disco (opcional).** Borrar el historial no elimina los `.bak` ni los `.trn`:

```sql
DECLARE @dir nvarchar(512) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512));
EXEC master.dbo.xp_delete_file 0, @dir, N'bak', N'2099-01-01T00:00:00', 0;
EXEC master.dbo.xp_delete_file 0, @dir, N'trn', N'2099-01-01T00:00:00', 0;
```

Si `xp_delete_file` falla, bórralos a mano:

- Docker: `docker exec sqlserver sh -c "rm -f /var/opt/mssql/data/*.bak /var/opt/mssql/data/*.trn"`
- Windows: la carpeta `...\MSSQL\Backup` de tu instalación.

Es irreversible: después no podrás restaurar con esos respaldos. **Sobre una base importante, no ejecutes estas limpiezas** y conserva un respaldo recuperable antes de cualquier intervención manual.

---

## 15. Pruebas y compilación

```powershell
cd backend
.\mvnw.cmd "-Djava.version=21" test
```

Con JDK 25 puede omitirse `"-Djava.version=21"`. Se incluyen pruebas de lógica, SQL/timeout y rutas del módulo de mantenimiento, además de la prueba de contexto original. Mockito se inicia como agente mediante Surefire para evitar la conexión dinámica del agente en JDK recientes.

```powershell
cd frontend
npm run build
npx eslint src/pages/maintenance/Maintenance.jsx
```

Estas pruebas verifican el código y la integración de las rutas, pero no sustituyen la demostración de la sección 12 sobre un SQL Server real con el usuario y permisos de este README.

---

## 16. Diagnóstico rápido

| Síntoma | Causa y solución |
|---|---|
| Error de extensión `.jsx` | Usar `npm run dev`, no ejecutar un componente con Node |
| "Sin conexión activa" | Configurar y conectar el perfil desde *Estado de la instancia* |
| Puerto 1433 cerrado (`TcpTestSucceeded : False`) | Comprobar servicio, TCP/IP y puerto real; no se arregla cambiando código React |
| La app no conecta por nombre de instancia | Con puerto vacío se usa el nombre de instancia (requiere el servicio *SQL Server Browser*, UDP 1434); más simple: fijar el puerto (4.1) e indicarlo |
| Nombre de instancia | En SSMS: `SELECT SERVERPROPERTY('ServerName'), SERVERPROPERTY('InstanceName'), SERVERPROPERTY('ProductVersion');`. `InstanceName` nulo = instancia predeterminada: dejar ese campo vacío |
| Login rechazado | Comprobar autenticación mixta, login habilitado y contraseña vigente (Script 1 con `@ResetPassword = 1` si hace falta) |
| Error de CORS en el navegador | `app.allowedOrigin` debe coincidir con la URL exacta del frontend (puerto de Vite) |
| Módulos 1–3 con avisos de "No se pudo obtener…" | Falta `VIEW SERVER STATE` / `VIEW ANY DEFINITION` / `CONNECT ANY DATABASE` → Script 1 |
| Rendimiento: terminar sesión falla | Falta `ALTER ANY CONNECTION` → Script 1 con `@AllowKill = 1`. Solo se puede terminar una sesión que esté bloqueando a otras |
| Respaldo: banner rojo con "No se pudo leer el log de errores" | Falta `EXECUTE` en `xp_readerrorlog` (Script 3) o `VIEW ANY ERROR LOG` en 2022+ (Script 1) |
| Respaldo: "Ejecutar FULL ahora" falla en una base | Falta `db_backupoperator` ahí (Script 3), la carpeta no existe o el servicio de SQL Server no puede escribir en ella, o la base es de solo lectura |
| Auditoría: "No se pudo analizar la base X" | Falta el usuario o `VIEW DEFINITION` en esa base → Script 3 |
| Mantenimiento: "Se requiere VIEW DEFINITION" o falta `ALTER` | Script 3 y Script 4 sobre la base seleccionada; no agregar `sysadmin` como solución general |
| Mantenimiento: sin fecha o contador no disponible | Puede faltar visibilidad de las propiedades o no existir datos estadísticos; consultar la sección 18. No significa necesariamente que la actualización haya fallado |
| Mantenimiento: "no tiene estadísticas existentes" | Una tabla sin estadísticas no tiene nada que actualizar; la acción no crea estadísticas nuevas |
| Mantenimiento: lista de objetos vacía | Distinguir "sin hallazgos" de "análisis incompleto": revisar los avisos |
| El Script 3 imprime `ERROR <base>` | Mensaje de SQL Server en la misma línea; casos típicos: base en modo restringido o sin permiso de la sesión que ejecuta el script |

Referencias: [UPDATE STATISTICS](https://learn.microsoft.com/en-us/sql/t-sql/statements/update-statistics-transact-sql), [sp_refreshsqlmodule](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-refreshsqlmodule-transact-sql), [propiedades de estadísticas](https://learn.microsoft.com/en-us/sql/relational-databases/system-dynamic-management-objects/sys-dm-db-stats-properties-transact-sql), [BACKUP](https://learn.microsoft.com/en-us/sql/t-sql/statements/backup-transact-sql) y [permisos del log](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-readerrorlog-transact-sql).

---

## 17. Seguridad

- `~/.dbwatch/profiles.json` puede contener contraseñas en claro: **no lo adjuntes al repositorio, capturas, entregas ni respaldos de código compartidos**.
- No guardes contraseñas reales en este README ni en los scripts versionados: `<CONTRASEÑA_SEGURA>` es un marcador.
- `dbwatch_monitor` no es `sysadmin` ni `db_owner`. Sus permisos de escritura se limitan a: `db_backupoperator` (respaldos completos), `ALTER` sobre los objetos que autorices (Mantenimiento) y, opcionalmente, `ALTER ANY CONNECTION` (terminar sesiones).
- El backend escucha solo en `127.0.0.1` y no tiene autenticación propia: cualquiera con acceso a esa máquina puede usar la aplicación con los permisos del login configurado.

## 18. Estadísticas sin fecha y verificación de la actualización

Una tabla puede tener un objeto de estadísticas y no disponer de fecha ni contador visible. SQL Server puede no generar datos estadísticos si no hay filas para esa estadística, y los permisos también limitan la consulta de sus propiedades. El módulo muestra «Sin fecha disponible» y cuántas estadísticas tienen fecha; no da por hecho que la tabla esté vacía ni inventa un valor. La fecha mostrada es la más antigua entre las disponibles: si falta alguna, esa fecha no representa todas las estadísticas.

Actualizar una estadística no aumenta la cantidad de objetos de estadísticas. Un contador de cambios igual a 0 puede ser normal tras actualizar. El módulo no agrega registros ficticios, no concede permisos automáticamente y no hace reintentos de escritura.

Después de confirmar una actualización se consultan las fechas individuales antes y después del comando, sin compararlas con el reloj del backend. La API distingue:

- `status: verified`, `ok: true`: la identidad y cantidad de estadísticas se conservan y todas tienen una fecha posterior; una fecha antes ausente y ahora disponible también cuenta como avance.
- `status: unverified`, `ok: false`: el comando terminó sin error SQL, pero faltan fechas, alguna no avanzó, cambió la lista o falló la consulta posterior. La pantalla muestra un aviso amarillo de resultado no verificable. No es un éxito confirmado ni un fallo SQL demostrado. Una repetición muy rápida también puede no producir una diferencia de fecha observable.
- `status: failed`, `ok: false`: ocurrió un error de ejecución o de lectura previa. Un timeout no prueba que se haya revertido una operación ya iniciada.

Si no se puede verificar, revisar el aviso y actualizar el análisis antes de repetir. No cambiar datos para obligar a que aparezca una fecha. Si el problema es de permisos, usar el Script 4 con el alcance autorizado; si no hay filas para generar estadísticas, una fecha ausente puede ser normal. Los clientes de la API deben comprobar `status` además de `ok`.

Para instalar esta corrección en otra máquina, actualizar backend y frontend conjuntamente y reiniciarlos. Un backend antiguo no proporciona `datedStatistics`; la pantalla pedirá actualizar backend y análisis si falta ese detalle. Se incluyen pruebas para fechas nulas, iguales y anteriores, actualización parcial, cambios de lista y fallos antes y después del comando. No se necesita un respaldo nuevo ni permisos adicionales respecto de los documentados para el módulo.

## 19. Cambiar de base en Mantenimiento

El selector conserva el catálogo de bases mientras carga o si una consulta falla. Si una base no permite leer sus definiciones o queda inaccesible, se muestra un aviso con el nombre de la base y se puede elegir otra sin recargar toda la aplicación. Los errores de estadísticas, referencias y módulos se gestionan por sección, siguiendo el patrón de Almacenamiento; los datos no consultados no se presentan como un análisis completo sin problemas.

Al cambiar de base se descartan los datos anteriores y se cancelan las consultas de lectura pendientes. Las respuestas antiguas no reemplazan la selección más reciente. Una respuesta vacía, inválida, un error HTTP o una caída de red muestran un mensaje sin inutilizar el selector. Si no hay conexión activa, se limpia el catálogo y se solicita configurar una conexión.

Las acciones de estadísticas y corrección conservan sus confirmaciones y validaciones de perfil, base y objeto. Durante una escritura se bloquea el cambio de base; al finalizar se vuelve a analizar la base de esa operación. Una base que no pudo analizarse no muestra acciones sobre datos de la anterior. No se concede ningún permiso automáticamente ni se cambia silenciosamente a otra base para ejecutar una acción.

Para instalar, actualizar backend y frontend conjuntamente y reiniciarlos. Se mantienen los requisitos y permisos ya documentados; una base nueva debe tener su usuario y los permisos autorizados, aunque ahora el módulo permite salir de ella cuando estos faltan.
