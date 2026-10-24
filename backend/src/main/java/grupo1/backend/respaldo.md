## Qué verifica el módulo

- Último respaldo exitoso: fecha, tipo, estado y duración.
- Historial de los últimos 10 respaldos, con base de datos, tipo, tamaño, duración y estado.
- Tira de los últimos 14 días y tasa de éxito.
- Interpretación administrativa: banner verde, amarillo o rojo según el riesgo.
- Política de retención observada y botón **Ejecutar FULL ahora**.

Los datos salen de `msdb.dbo.backupset`. Los respaldos fallidos se leen del log de errores de SQL Server con `xp_readerrorlog`.


### 1.2 Permisos de servidor y lectura de `msdb`

```sql
USE master;
IF USER_ID(N'dbwatch_monitor') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
GRANT EXECUTE ON xp_readerrorlog TO dbwatch_monitor;
GO
GRANT VIEW ANY ERROR LOG TO dbwatch_monitor;   -- solo SQL Server 2022; en versiones anteriores da error: ignóralo
GO
USE msdb;
IF USER_ID(N'dbwatch_monitor') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
ALTER ROLE db_datareader ADD MEMBER dbwatch_monitor;
GO
```

### 1.3 Base de pruebas `DSR_PROD`

El modelo de recuperación FULL es necesario para poder hacer respaldos de LOG.

```sql
USE master;
IF DB_ID(N'DSR_PROD') IS NULL
BEGIN
    CREATE DATABASE DSR_PROD;
    ALTER DATABASE DSR_PROD SET RECOVERY FULL;
END
GO
USE DSR_PROD;
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

### 1.4 Permiso de respaldo en todas las bases

El botón **Ejecutar FULL ahora** respalda todas las bases en línea, incluidas las de sistema, así que el login necesita `db_backupoperator` en cada una. **Repite este bloque cada vez que crees una base nueva.**

```sql
USE master;
GO
DECLARE @sql nvarchar(max) = N'';
SELECT @sql += N'
USE ' + QUOTENAME(name) + N';
IF USER_ID(N''dbwatch_monitor'') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
ALTER ROLE db_backupoperator ADD MEMBER dbwatch_monitor;
'
FROM sys.databases
WHERE database_id <> 2 AND state_desc = 'ONLINE';
EXEC (@sql);
GO
```

### 1.5 Verificar los permisos

```sql
EXECUTE AS LOGIN = 'dbwatch_monitor';
SELECT COUNT(*) AS lectura_backupset_ok FROM msdb.dbo.backupset;
EXEC master.dbo.xp_readerrorlog 0, 1, N'BACKUP failed to complete';   -- puede dar 0 filas, pero sin error
REVERT;

SELECT CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512)) AS carpeta_respaldos;
```

- `carpeta_respaldos` no debe ser `NULL`. Si lo es (versiones anteriores a 2019), usa una carpeta que exista y donde SQL Server pueda escribir.
- La cuenta del servicio de SQL Server debe poder escribir en esa carpeta. En Docker ya puede.

## 3. Limpiar los respaldos

Úsalo para volver al estado inicial antes de repetir las pruebas.

**a) Historial de `msdb` (lo que lee el módulo):**

```sql
EXEC msdb.dbo.sp_delete_backuphistory @oldest_date = '2099-01-01';
SELECT COUNT(*) AS filas_restantes FROM msdb.dbo.backupset;   -- debe dar 0
```

**b) Fallos anteriores del log de errores.** Abre un log nuevo, y los fallos viejos dejan de verse:

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

Es irreversible: después no podrás restaurar con esos respaldos.

## 4. Pruebas

Haz las pruebas **en orden**, empezando por la limpieza del paso 3. Recarga la página después de cada paso.

Los respaldos de la prueba son de hoy, así que en la tira de 14 días solo cambia la última barra. Por eso la tasa de éxito se mueve entre 0.0% y 7.1% (1 de 14 días).

### Prueba 0: estado vacío

No hagas ningún respaldo todavía.

| Panel | Valor esperado |
|---|---|
| Banner | Rojo: "Riesgo alto detectado", no existe ningún respaldo completo exitoso |
| Último respaldo exitoso | "—", "Sin respaldos exitosos registrados" |
| Tipo / Estado | "—" |
| Tasa de éxito (14 d) | 0.0% en rojo, "0 de 14 correctos" |
| Historial | "Sin datos" |
| Tira de 14 días | Las 14 barras grises |
| Política de retención | Sin filas; modelo de recuperación "MIXTO"; destino y verificación "—" |
| Aviso amarillo | No debe aparecer |

### Prueba 1: un FULL solo de `DSR_PROD`

```sql
USE master;
DECLARE @dir nvarchar(512) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512));
DECLARE @sep nvarchar(1)   = CASE WHEN @dir LIKE '%/%' THEN '/' ELSE '\' END;
DECLARE @stamp varchar(15) = FORMAT(SYSDATETIME(), 'yyyyMMdd_HHmmss');
DECLARE @f nvarchar(600)   = @dir + @sep + N'DSR_PROD_FULL_' + @stamp + N'.bak';
BACKUP DATABASE DSR_PROD TO DISK = @f WITH CHECKSUM, INIT;
```

| Panel | Valor esperado |
|---|---|
| Banner | Amarillo: "Riesgo moderado detectado", 3 base(s) sin respaldo completo exitoso: master, model, msdb |
| Último respaldo exitoso | Hoy, hora actual, "hace 0 h", en verde |
| Tipo | "Completo", "BACKUP DATABASE · FULL" |
| Estado | "Exitoso", "Duración 1 min" |
| Tasa de éxito | 7.1% en rojo, "1 de 14 correctos" |
| Historial | 1 fila: `DSR_PROD`, FULL, tamaño en MB, "Exitoso" |
| Tira | Solo la última barra en verde. Leyenda: Exitoso 1, Advertencia 0, Fallido 0 |
| Política | FULL con frecuencia "Sin datos suficientes" y retención "Menos de 1 día en historial"; verificación "CHECKSUM"; destino igual a la carpeta de respaldos; aviso con la recomendación al pie |

### Prueba 2: FULL de las bases de sistema

```sql
USE master;
DECLARE @dir nvarchar(512) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512));
DECLARE @sep nvarchar(1)   = CASE WHEN @dir LIKE '%/%' THEN '/' ELSE '\' END;
DECLARE @stamp varchar(15) = FORMAT(SYSDATETIME(), 'yyyyMMdd_HHmmss');
DECLARE @f nvarchar(600);

SET @f = @dir + @sep + N'master_FULL_' + @stamp + N'.bak';
BACKUP DATABASE master TO DISK = @f WITH CHECKSUM, INIT;
SET @f = @dir + @sep + N'model_FULL_' + @stamp + N'.bak';
BACKUP DATABASE model TO DISK = @f WITH CHECKSUM, INIT;
SET @f = @dir + @sep + N'msdb_FULL_' + @stamp + N'.bak';
BACKUP DATABASE msdb TO DISK = @f WITH CHECKSUM, INIT;
```

| Panel | Valor esperado |
|---|---|
| Banner | Verde: "Respaldos en buen estado", el último FULL fue "hace 0 h" y no hay fallos en 14 días |
| Historial | 4 filas, la más reciente primero: `msdb`, `model`, `master`, `DSR_PROD`, todas FULL y "Exitoso" |
| Tira | Igual que antes: solo la última barra en verde |
| Política | FULL con frecuencia en minutos (aproximada), por ejemplo "Cada ~1 min" |

### Prueba 3: botón "Ejecutar FULL ahora"

Abre **Ver política de retención** → **Ejecutar FULL ahora**.

| Resultado | Valor esperado |
|---|---|
| Mensaje | "Respaldo FULL completado (4 base(s))" |
| Historial | Las 10 filas más recientes, todas FULL y "Exitoso". Ya hay 8 FULL en total |
| Caja roja | No debe aparecer. Si aparece, muestra el motivo de cada base que falló |

### Prueba 4: DIFF y LOG de `DSR_PROD`

```sql
USE master;
DECLARE @dir nvarchar(512) = CAST(SERVERPROPERTY('InstanceDefaultBackupPath') AS nvarchar(512));
DECLARE @sep nvarchar(1)   = CASE WHEN @dir LIKE '%/%' THEN '/' ELSE '\' END;
DECLARE @stamp varchar(15) = FORMAT(SYSDATETIME(), 'yyyyMMdd_HHmmss');
DECLARE @f nvarchar(600);

INSERT DSR_PROD.dbo.Cliente (Nombre) VALUES (N'Nuevo 1');
SET @f = @dir + @sep + N'DSR_PROD_DIFF_' + @stamp + N'.bak';
BACKUP DATABASE DSR_PROD TO DISK = @f WITH DIFFERENTIAL, CHECKSUM, INIT;

INSERT DSR_PROD.dbo.Cliente (Nombre) VALUES (N'Nuevo 2');
SET @f = @dir + @sep + N'DSR_PROD_LOG1_' + @stamp + N'.trn';
BACKUP LOG DSR_PROD TO DISK = @f WITH CHECKSUM, INIT;

INSERT DSR_PROD.dbo.Cliente (Nombre) VALUES (N'Nuevo 3');
SET @f = @dir + @sep + N'DSR_PROD_LOG2_' + @stamp + N'.trn';
BACKUP LOG DSR_PROD TO DISK = @f WITH CHECKSUM, INIT;
```

| Panel | Valor esperado |
|---|---|
| Último respaldo exitoso | Tipo "Log de transacciones", "BACKUP LOG" |
| Historial | Aparecen filas "Diferencial (DIFF)" y "Log de transacciones (LOG)" de `DSR_PROD`. Solo se muestran los 10 más recientes |
| Política | Tres filas en este orden: FULL, DIFF, LOG. LOG con frecuencia en minutos; DIFF con "Sin datos suficientes" (solo hay uno) |
| Banner | Sigue verde |

