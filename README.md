# Proyecto Monitor de SGBD SQL SERVER

Proyecto Integrador EIF402 – Administración de Bases de Datos.
Herramienta web que consulta directamente las vistas administrativas (DMV) de una instancia de Microsoft SQL Server.

---

## 1. Requisitos

- JDK 25 y Maven (configuración original). Con JDK 21, usar `"-Djava.version=21"` en los comandos Maven indicados en la sección 4.
- Node.js 22.12 o posterior compatible con Vite 8; Node.js 24 LTS es adecuado. npm viene incluido.
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

El nombre debe coincidir con la instalación real. Para una instancia predeterminada `MSSQLSERVER`, elegir sus protocolos en Configuration Manager y reiniciar `Restart-Service MSSQLSERVER`. En ese caso, el campo **Instancia** de la aplicación se deja vacío. No usar `SQLEXPRESS` si esa instancia no existe.

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

Para SQL Server 2022 o posterior, las DMV de rendimiento también pueden requerir este permiso:

```sql
IF TRY_CONVERT(int, SERVERPROPERTY('ProductMajorVersion')) >= 16
    EXEC(N'GRANT VIEW SERVER PERFORMANCE STATE TO dbwatch_monitor;');
GO
```


### 2.4 Permisos en cada base de usuario (objetos inválidos)

El Módulo 5 detecta objetos con referencias inexistentes leyendo `sys.sql_expression_dependencies`. Se requiere visibilidad de las definiciones y permiso de lectura de esa vista; no es necesario agregar el usuario a `db_owner`. Este script crea el usuario y concede esos permisos **en todas las bases de usuario en línea**:

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

### 2.6 Permisos del Módulo 6: mantenimiento preventivo

Los permisos de monitoreo no autorizan por sí solos a modificar estadísticas o metadatos. En **cada base que se va a mantener**, ejecutar como administrador, reemplazando `NombreDeLaBase`, `MiTabla`, `MiVista` y `MiProcedimiento` por objetos reales:

```sql
USE [NombreDeLaBase];
GO
IF USER_ID(N'dbwatch_monitor') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
GRANT VIEW DEFINITION TO dbwatch_monitor;
GRANT SELECT ON sys.sql_expression_dependencies TO dbwatch_monitor;
GRANT SELECT ON sys.dm_sql_referenced_entities TO dbwatch_monitor;
GRANT ALTER ON OBJECT::dbo.MiTabla TO dbwatch_monitor;
GRANT ALTER ON OBJECT::dbo.MiVista TO dbwatch_monitor;
GRANT ALTER ON OBJECT::dbo.MiProcedimiento TO dbwatch_monitor;
GO
```

Conceder `ALTER` solamente sobre los objetos autorizados. Para ver todas las propiedades de estadísticas de una tabla, también puede necesitarse `SELECT` sobre sus columnas estadísticas; por ejemplo `GRANT SELECT ON OBJECT::dbo.MiTabla TO dbwatch_monitor;`. Este último permiso permite leer datos de esa tabla: concederlo solo si corresponde. Sin visibilidad de esas propiedades, fecha o contador pueden aparecer como `—`; también pueden ser nulos si todavía no hay datos estadísticos.

La corrección utiliza `sys.sp_refreshsqlmodule`. Algunos tipos definidos por el usuario o colecciones de esquemas XML pueden requerir `REFERENCES` adicional. Los módulos firmados, cifrados o con `SCHEMABINDING` se reservan para revisión manual. Si falta una tabla o columna, el módulo informa el error; no inventa ni restaura automáticamente ese objeto.

No hace falta un archivo `.bak` ni un respaldo previo para abrir el proyecto, analizar dependencias o actualizar estadísticas. Sobre una base importante conviene contar con un respaldo recuperable y conservar las definiciones antes de una intervención manual. El Módulo 6 no hace respaldos ni restauraciones automáticamente. FULLSCAN es opcional y puede consumir más recursos.

### 2.7 Requisitos adicionales de Respaldo y Recuperación (Módulo 4)

Esta configuración es independiente de Mantenimiento. Sin historial de respaldos, se debe informar esa ausencia; no hay que restaurar una copia para que el proyecto arranque.

Para leer el historial que consulta el código actual, crear el usuario en `msdb` y autorizar las tablas usadas:

```sql
USE msdb;
GO
IF USER_ID(N'dbwatch_monitor') IS NULL
    CREATE USER dbwatch_monitor FOR LOGIN dbwatch_monitor;
GRANT SELECT ON dbo.backupset TO dbwatch_monitor;
GRANT SELECT ON dbo.backupmediafamily TO dbwatch_monitor;
GO
```

Para **ejecutar** un respaldo de una base concreta se necesita `GRANT BACKUP DATABASE TO dbwatch_monitor;` dentro de esa base. Esto no es necesario para usar el Módulo 6. La carpeta destino está en el equipo de SQL Server, debe existir y la cuenta del **servicio SQL Server** debe poder escribir en ella. El permiso de Windows del usuario que abre el navegador no sustituye este requisito. La aplicación usa `WITH CHECKSUM, INIT`: revisar el destino y evitar sobrescribir archivos que se quieran conservar.

La lectura del log de errores tiene permisos propios según la versión. En SQL Server 2022 o posterior puede concederse `VIEW ANY ERROR LOG` al login si está autorizado; en versiones anteriores se utiliza `VIEW SERVER STATE`. El código muestra un aviso si no puede consultar ese log.

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

Verificar que quede una conexión **activa**; si guardar no la activa, usar la opción Conectar. Mantenimiento reutiliza esa misma conexión; su enlace Configurar conexión abre el módulo de instancia. El archivo de perfiles puede contener contraseñas: no adjuntarlo al repositorio, capturas, entregas o respaldos de código compartidos.

Los pasos anteriores usan autenticación de **SQL Server** y no requieren una DLL de autenticación de Windows. Si se elige Windows integrada, el código usa `integratedSecurity=true`: instalar la DLL `mssql-jdbc_auth` correspondiente a la versión del driver y arquitectura de la JVM, disponible para el proceso Java mediante PATH o `java.library.path`. Sin esa biblioteca, elegir Windows puede fallar aunque la conexión funcione en SSMS.

Referencia: [autenticación integrada del driver JDBC de Microsoft](https://learn.microsoft.com/en-us/sql/connect/jdbc/building-the-connection-url).

---

## 4. Ejecutar backend y frontend

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

Configuración del backend: `backend/src/main/resources/application.properties`. `app.allowedOrigin` debe coincidir con la URL del frontend; usar el puerto 5173 configurado o ajustar el origen si Vite anuncia otro. `app.maintenance.timeoutSeconds=300` limita cada acción de mantenimiento sin modificar el timeout de los otros módulos.

No se necesitan migraciones ni tablas de aplicación para el Módulo 6. Para probarlo se incluye una base de demostración opcional en `backend/sql/maintenance-demo.sql`; leer la sección 5 antes de ejecutarla.

## 5. Comprobar el Módulo 6

1. Conectarse a la instancia real y elegir una base de usuario en línea. Debe existir una sola opción Mantenimiento preventivo en el menú.
2. Revisar estadísticas y objetos con problemas. Si aparecen avisos de permisos o análisis incompleto, corregirlos antes de interpretar una lista vacía como ausencia de errores.
3. Pulsar Actualizar estadísticas, cancelar y comprobar que no se ejecuta. Repetir confirmando y comprobar el resultado y las fechas. Probar FULLSCAN solo en una tabla pequeña de demostración.
4. Para una demostración aislada, un administrador puede ejecutar `backend/sql/maintenance-demo.sql`. Crea `DbWatchMaintenanceDemo` con una vista cuya columna fue eliminada y un procedimiento que referencia una tabla faltante. Conceder los permisos de 2.6 sobre esa base y sus objetos `dbwatch_sample`, `dbwatch_view` y `dbwatch_missing`.
5. Analizar esa base y comprobar los errores. Intentar corregir sin restaurar dependencias debe informar un fallo o trabajo pendiente, no un éxito definitivo.
6. Ejecutar `backend/sql/maintenance-demo-repair.sql` en SSMS. Restaura la columna y la tabla faltantes. Sobre la lista ya cargada, ejecutar Corregir / recompilar y comprobar el resultado y el nuevo análisis. Si se actualiza el análisis primero, los problemas ya resueltos podrían dejar de aparecer.
7. En la base de demostración, comprobar el error al retirar temporalmente `ALTER`, y restaurar el permiso después.

La corrección de SQL Server actualiza metadatos y comprueba dependencias; no ejecuta el cuerpo de procedimientos ni garantiza que SQL dinámico, referencias externas o todo el código puedan ejecutarse con cualquier dato. Los avisos delimitan el análisis. Si hay timeout o se pierde la conexión, verificar el estado antes de repetir: no se reintentan escrituras automáticamente.

## 6. Pruebas y compilación

```powershell
cd backend
.\mvnw.cmd "-Djava.version=21" test
```

Con JDK 25 puede omitirse `"-Djava.version=21"`. Se incluyen pruebas de lógica, SQL/timeout y rutas del módulo, además de la prueba de contexto original. Mockito se inicia como agente mediante Surefire para evitar la conexión dinámica del agente en JDK recientes.

```powershell
cd frontend
npm run build
npx eslint src/pages/maintenance/Maintenance.jsx
```

Estas pruebas verifican el código y la integración de las rutas, pero no sustituyen la demostración de la sección 5 sobre SQL Server real con el usuario y permisos del grupo.

## 7. Diagnóstico rápido

- **Error de extensión `.jsx`:** usar `npm run dev`, no ejecutar un componente con Node.
- **Sin conexión activa:** configurar y conectar el perfil desde Estado de la instancia.
- **Puerto 1433 cerrado:** comprobar servicio, TCP/IP y puerto real. `TcpTestSucceeded : False` no se arregla cambiando código React.
- **Nombre de instancia:** ejecutar en SSMS `SELECT SERVERPROPERTY('ServerName'), SERVERPROPERTY('InstanceName'), SERVERPROPERTY('ProductVersion');`. `InstanceName` nulo indica instancia predeterminada: dejar ese campo vacío en la aplicación.
- **Login rechazado:** comprobar autenticación mixta, login habilitado y contraseña vigente. No guardar contraseñas reales en el README.
- **Falta ALTER o VIEW DEFINITION:** aplicar 2.6 en la base seleccionada; no agregar `sysadmin` como solución general.
- **Sin estadísticas:** una tabla sin estadísticas existentes no tiene nada que actualizar; la acción no crea estadísticas nuevas.
- **Lista de objetos vacía:** distinguir sin hallazgos de análisis incompleto y revisar los avisos.

Referencias: [UPDATE STATISTICS](https://learn.microsoft.com/en-us/sql/t-sql/statements/update-statistics-transact-sql), [sp_refreshsqlmodule](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-refreshsqlmodule-transact-sql), [propiedades de estadísticas](https://learn.microsoft.com/en-us/sql/relational-databases/system-dynamic-management-objects/sys-dm-db-stats-properties-transact-sql), [BACKUP](https://learn.microsoft.com/en-us/sql/t-sql/statements/backup-transact-sql) y [permisos del log](https://learn.microsoft.com/en-us/sql/relational-databases/system-stored-procedures/sp-readerrorlog-transact-sql).

