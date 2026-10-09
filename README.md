# Proyecto Monitor de SGBD SQL SERVER

Proyecto Integrador EIF402 – Administración de Bases de Datos.
Herramienta web que consulta directamente las vistas administrativas (DMV) de una instancia de Microsoft SQL Server.

---

## 1. Requisitos

- Java 25 o superior (JDK) y Maven.
- Node.js LTS.
- Microsoft SQL Server 2017 o superior (Express o Developer). Las consultas usan `sys.dm_os_host_info`, que no existe en versiones anteriores.
- SQL Server Management Studio (SSMS) o Azure Data Studio.

---

## 2. Preparar SQL Server

Los pasos 2.1 y 2.2 se hacen en el equipo donde corre SQL Server. Los scripts de 2.3 en adelante se ejecutan en SSMS con una sesión de **administrador (sysadmin)**, por ejemplo la autenticación de Windows del propio equipo.

### 2.1 Habilitar TCP/IP y fijar el puerto

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

5. Comprobar que el puerto responde:

```powershell
Test-NetConnection localhost -Port 1433
```

Debe mostrar `TcpTestSucceeded : True`.

Si prefieres no fijar el puerto, averigua el que usa tu instancia con este script y úsalo en la aplicación:

```sql
EXEC sys.xp_readerrorlog 0, 1, N'listening on';
```

### 2.2 Autenticación mixta

El usuario de monitoreo usa autenticación de SQL Server.

```sql
SELECT SERVERPROPERTY('IsIntegratedSecurityOnly') AS soloWindows;
```

- `0`: la autenticación mixta ya está activa.
- `1`: cambiarla en SSMS (*Propiedades del servidor → Seguridad → Modo de autenticación de SQL Server y Windows*) y reiniciar el servicio.

### 2.3 Crear el login de monitoreo

Reemplace `<CONTRASEÑA_SEGURA>` por una contraseña propia.

```sql
USE master;
GO

IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dbwatch_monitor')
    CREATE LOGIN dbwatch_monitor
        WITH PASSWORD = N'<CONTRASEÑA_SEGURA>', CHECK_POLICY = ON;
GO

GRANT VIEW SERVER STATE      TO dbwatch_monitor;
GRANT VIEW ANY DEFINITION    TO dbwatch_monitor;
GRANT CONNECT ANY DATABASE   TO dbwatch_monitor;
GO
```


### 2.4 Permisos en cada base de usuario (objetos inválidos)

El Módulo 5 detecta objetos con referencias a objetos inexistentes leyendo `sys.sql_expression_dependencies`. Por defecto, esa vista solo la puede leer el rol `db_owner` de cada base. Este script crea el usuario y concede lo mínimo necesario **en todas las bases de usuario en línea**:

```sql
USE master;
GO

DECLARE @sql NVARCHAR(MAX) = N'';

SELECT @sql += N'
USE ' + QUOTENAME(name) + N';
IF USER_ID(N''dbwatch_monitor'') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
GRANT VIEW DEFINITION TO dbwatch_monitor;
GRANT SELECT ON sys.sql_expression_dependencies TO dbwatch_monitor;
'
FROM sys.databases
WHERE database_id > 4
  AND state_desc = 'ONLINE';

EXEC (@sql);
GO
```

> Ejecútalo de nuevo cada vez que se cree una base de datos nueva que quieras auditar.

Para una sola base:

```sql
USE NombreDeLaBase;
GO
CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
GRANT VIEW DEFINITION TO dbwatch_monitor;
GRANT SELECT ON sys.sql_expression_dependencies TO dbwatch_monitor;
GO
```

No se recomienda agregar el login al rol `db_owner`: es más poder del necesario.

### 2.5 (Opcional) Base de prueba con un objeto inválido

Sirve para demostrar la detección de objetos inválidos del Módulo 5. Una vista que depende de una tabla que luego se elimina queda con una referencia rota.

```sql
CREATE DATABASE testAudit;
GO
USE testAudit;
GO
CREATE TABLE dbo.tmpAudit (id INT);
GO
CREATE VIEW dbo.vTmpAudit AS SELECT id FROM dbo.tmpAudit;
GO
DROP TABLE dbo.tmpAudit;
GO
```

Después de ejecutar el script de 2.4 para esta base, la vista `testAudit.dbo.vTmpAudit` debe aparecer en *Auditoría → Objetos inválidos*.

Limpieza:

```sql
USE master;
GO
DROP DATABASE testAudit;
GO
```



---

## 3. Configurar la conexión desde la aplicación

En *Estado de la instancia* → **Configurar conexión**:

| Campo | Valor |
|---|---|
| Servidor | `localhost` |
| Instancia | `SQLEXPRESS` (según tu instalación) |
| Puerto | `1433` (o el que uses) |
| Autenticación | SQL Server |
| Usuario | `dbwatch_monitor` |
| Contraseña | la definida en 2.3 |
| Cifrar conexión | activado |
| Confiar en el certificado | activado (certificado autofirmado local) |
| Intervalo de refresco | el deseado |

Pulsa **Probar conexión** y luego **Guardar**. La conexión queda guardada en `~/.dbwatch/profiles.json` y se reactiva sola al reiniciar el backend. Para cambiar de instancia sin tocar el código, usa el selector de la parte superior de cada pantalla.

---

