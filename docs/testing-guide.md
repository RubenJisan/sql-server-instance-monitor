# Manual testing guide

End-to-end walkthrough of the six DBWatch modules against a real SQL Server instance.

**Prerequisites**

- Demo databases created ([`sql/02_demo_databases.sql`](../sql/02_demo_databases.sql)).
- [`sql/03_grant_database_access.sql`](../sql/03_grant_database_access.sql) run **after** creating them.
- [`sql/04_grant_maintenance_permissions.sql`](../sql/04_grant_maintenance_permissions.sql) applied to `DbWatchMaintenanceDemo`.
- Backend and frontend running, and the connection configured (see the main [README](../README.md#getting-started)).

> The UI is in Spanish; English equivalents are given in parentheses.

---

## 1. Instance Status

No data needed. It should show version, edition, uptime, physical CPU and memory, server memory, user sessions and the database list with size and recovery model (system databases listed separately).

## 2. Performance

CPU and memory are read from SQL Server ring buffers, so charts have data immediately. The backend also accumulates in-memory samples (at most one every 30 s, up to 25 h) for the 6 h and 24 h ranges. **That history is lost when the backend restarts**, and longer ranges fill up over time.

The monitor ignores sessions whose `program_name` is `Microsoft JDBC Driver for SQL Server` (its own). SSMS windows are shown.

**CPU load** (in an SSMS window; cancel it with *Stop*):

```sql
USE DSR_PROD;
DECLARE @i int = 0;
WHILE @i < 20
BEGIN
    SELECT COUNT_BIG(*) FROM dbo.Customer a CROSS JOIN dbo.Customer b
    CROSS JOIN (SELECT TOP (4) 1 AS x FROM sys.all_objects) c;
    SET @i += 1;
END
```

The query should appear in the costly-queries ranking and the session in the active processes.

**Blocking and terminate session** — open two SSMS windows:

```sql
-- Window A (leave the transaction open, no COMMIT)
USE DSR_PROD;
BEGIN TRAN;
UPDATE dbo.Customer SET Name = Name WHERE CustomerId = 1;
```

```sql
-- Window B (it will wait)
USE DSR_PROD;
UPDATE dbo.Customer SET Name = N'blocked' WHERE CustomerId = 1;
```

The blocking section should show session B waiting on session A, with the locked object. The *terminate session* button (requires `ALTER ANY CONNECTION`, i.e. `@AllowKill = 1` in script 01) only acts on a session that is blocking others; afterwards window A loses its connection. If you don't use it, finish with `ROLLBACK;` in window A.

**Export:** `GET /api/performance/export?format=pdf|xlsx|csv&range=1h&sections=queries,sessions,blocking,chart` (also available from the screen's button).

## 3. Storage

Select `DSR_PROD`: data and log files with size and growth, log usage and the 10 largest objects (tables/indexes). The **monthly growth** chart is derived from FULL backups in `msdb` over the last 6 months, so a fresh environment only shows the current month, and only after at least one FULL backup (see [section 4](#4-backup--recovery)).

## 4. Backup & Recovery

Run the tests **in order**, starting with the cleanup from [`sql/06_backup_template_and_cleanup.sql`](../sql/06_backup_template_and_cleanup.sql) (block B), and reload the page after each step. Expected values assume `DSR_PROD` is the **only user database**; with more (e.g. the other demos) the "N database(s)" counts and the list of databases without backup change.

Use block A of script 06 as the backup template, changing `@db` and `@type`.

### Test 0 — empty state (no backups yet)

| Panel | Expected |
|---|---|
| Banner | Red: "High risk detected" — no successful full backup exists |
| Last successful backup | "—", "No successful backups recorded" |
| Success rate (14 d) | 0.0 % in red, "0 of 14 correct" |
| History | "No data" |
| 14-day strip | All 14 bars grey |
| Retention policy | No rows; recovery model "MIXED"; destination and verification "—" |

### Test 1 — one FULL of `DSR_PROD` only

Template with `@db = N'DSR_PROD'`, `@type = 'FULL'`.

| Panel | Expected |
|---|---|
| Banner | Yellow: "Moderate risk detected", 3 database(s) without successful full backup: master, model, msdb |
| Last successful backup | Today, "0 h ago", green; type "Full" |
| Success rate | 7.1 % in red, "1 of 14 correct" |
| History | 1 row: `DSR_PROD`, FULL, size in MB, "Successful" |
| Policy | FULL with frequency "Not enough data"; verification "CHECKSUM"; destination = backup folder |

### Test 2 — FULL of the system databases

Repeat with `@db` = `master`, `model` and `msdb` (`FULL`).

| Panel | Expected |
|---|---|
| Banner | Green: "Backups in good state", last FULL "0 h ago", no failures in 14 days |
| History | 4 rows, newest first: `msdb`, `model`, `master`, `DSR_PROD`, all FULL and "Successful" |
| Policy | FULL with frequency in minutes, e.g. "Every ~1 min" |

### Test 3 — "Run FULL now" button

Open *View retention policy* → **Run FULL now** (*Ejecutar FULL ahora*).

| Result | Expected |
|---|---|
| Message | "FULL backup completed (4 database(s))" |
| History | The 10 most recent rows, all FULL and "Successful" (there are now 8 FULLs) |
| Red box | Must not appear. If it does, it lists the reason per database (usually: script 03 missing, or the folder isn't writable by the SQL Server service) |

### Test 4 — DIFF and LOG of `DSR_PROD`

Run the template with `DIFF`, then twice with `LOG`, inserting one row between each:

```sql
INSERT DSR_PROD.dbo.Customer (Name) VALUES (N'New 1');   -- then template with @type = 'DIFF'
INSERT DSR_PROD.dbo.Customer (Name) VALUES (N'New 2');   -- then template with @type = 'LOG'
INSERT DSR_PROD.dbo.Customer (Name) VALUES (N'New 3');   -- then template with @type = 'LOG'
```

| Panel | Expected |
|---|---|
| Last successful backup | Type "Transaction log" |
| History | DIFF and LOG rows for `DSR_PROD` (only the 10 most recent) |
| Policy | Three rows in this order: FULL, DIFF, LOG; LOG with frequency in minutes; DIFF "Not enough data" |
| Banner | Still green |

### Failed backups

They are read from the SQL Server error log (`BACKUP failed to complete`). To produce one, back up to a folder that doesn't exist and reload:

```sql
BACKUP DATABASE DSR_PROD TO DISK = N'C:\FolderThatDoesNotExist\x.bak' WITH CHECKSUM, INIT;
```

A failed row should appear in the history and a red bar for that day. To stop seeing it, cycle the log (`EXEC master.dbo.sp_cycle_errorlog;`).

## 5. Audit

Shows logins (password policy and expiration), server roles with members, privileges and **invalid objects** per user database. With `testAudit` created and script 03 run, `testAudit.dbo.vTmpAudit` must appear under *Audit → Invalid objects* with the broken reference to `dbo.tmpAudit`.

To see logins flagged as weak (no policy, no expiration), create a test login with **no permissions** (local test instances only):

```sql
USE master;
GO
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'dbwatch_demo_weak')
    CREATE LOGIN dbwatch_demo_weak WITH PASSWORD = N'Demo#2026-change-me', CHECK_POLICY = OFF, CHECK_EXPIRATION = OFF;
GO
-- Cleanup:  DROP LOGIN dbwatch_demo_weak;
```

## 6. Preventive Maintenance

1. Select `DbWatchMaintenanceDemo`. A single *Preventive Maintenance* entry should appear in the menu.
2. Review statistics and problem objects: `dbwatch_sample` with pending statistics/modifications; `dbwatch_view` (missing column `extra`) and `dbwatch_proc` (missing table `dbwatch_missing`) as objects with errors. If permission or incomplete-analysis warnings appear, fix them before reading an empty list as "no errors".
3. Click **Update statistics**, cancel, and confirm nothing runs. Repeat and confirm; check the result and the dates. Try FULLSCAN only on a small table (like this one).
4. Click **Fix / recompile** on `dbwatch_view` and `dbwatch_proc` *without repairing anything first*: it must report a failure or pending work, not a definitive success.
5. Run the **repair** script (block 2.4 in [`sql/02_demo_databases.sql`](../sql/02_demo_databases.sql)). On the already-loaded list, run **Fix / recompile** again and check the result and the new analysis. If you refresh the analysis first, resolved problems no longer appear.
6. Check the permission error by temporarily revoking `ALTER`, then restore it:

```sql
USE DbWatchMaintenanceDemo;
REVOKE ALTER ON OBJECT::dbo.dbwatch_sample FROM dbwatch_monitor;   -- the action must fail with a permission error
GRANT  ALTER ON OBJECT::dbo.dbwatch_sample TO   dbwatch_monitor;   -- restore
```

The fix action updates metadata and checks dependencies; it doesn't execute procedure bodies or guarantee that dynamic SQL, external references or all code will run with any data. On a timeout or lost connection, verify the state before repeating — writes are never retried automatically. It does not take backups or restore anything.

### Switching databases in this module

The selector keeps the database catalog while loading or if a query fails. If a database can't be read or becomes inaccessible, a warning with its name appears and you can pick another one without reloading the app. Statistics, references and module errors are handled per section; data that wasn't queried is never shown as a clean analysis.

When switching databases, previous data is discarded and pending reads are cancelled, so stale responses never replace the latest selection. During a write, switching databases is blocked; when it finishes, the database of that operation is re-analyzed. No permission is granted automatically and the module never silently switches to another database to run an action. A new database needs its user and authorized permissions (scripts 03 and 04).
