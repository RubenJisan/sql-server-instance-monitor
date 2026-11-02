import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import './Maintenance.css';

const API = `${import.meta.env.VITE_API_URL || 'http://localhost:8080/api'}/maintenance`;

async function request(path, body, signal) {
    const response = await fetch(`${API}${path}`, {
        method: body ? 'POST' : 'GET',
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
        signal
    });
    const text = await response.text();
    let data;
    try {
        data = text ? JSON.parse(text) : null;
    } catch {
        data = null;
    }
    if (!response.ok) {
        const error = new Error(data?.message || `Error ${response.status} del servidor.`);
        error.status = response.status;
        throw error;
    }
    if (!data) throw new Error('El servidor devolvió una respuesta vacía o inválida.');
    return data;
}

function Maintenance() {
    const [overview, setOverview] = useState(null);
    const [database, setDatabase] = useState('');
    const [databases, setDatabases] = useState([]);
    const [profileName, setProfileName] = useState('');
    const [search, setSearch] = useState('');
    const [loading, setLoading] = useState(true);
    const [running, setRunning] = useState(false);
    const [error, setError] = useState('');
    const [result, setResult] = useState(null);
    const [pending, setPending] = useState(null);
    const [fullScan, setFullScan] = useState(false);
    const dialog = useRef(null);
    const abort = useRef(null);
    const operationLock = useRef(false);

    const acceptOverview = useCallback(data => {
        if (!['databases', 'tables', 'objects', 'warnings'].every(key => Array.isArray(data[key]))) {
            throw new Error('El análisis recibido no tiene el formato esperado. Actualice el backend.');
        }
        setOverview(data);
        setDatabases(data.databases);
        setDatabase(data.database || '');
        setProfileName(data.profileName || '');
    }, []);

    const reportLoadError = useCallback(exception => {
        if (exception.name === 'AbortError') return;
        if (exception.status === 409) {
            setDatabases([]);
            setDatabase('');
            setProfileName('');
        }
        setError(`No se pudo cargar el análisis. ${exception.message}. Puede elegir otra base o reintentar; revise la conexión y los permisos.`);
    }, []);

    const load = useCallback(async (selected = '') => {
        abort.current?.abort();
        const controller = new AbortController();
        abort.current = controller;
        setLoading(true);
        setError('');
        setOverview(null);
        try {
            const data = await request(`/overview${selected ? `?database=${encodeURIComponent(selected)}` : ''}`,
                null, controller.signal);
            if (!controller.signal.aborted && abort.current === controller) acceptOverview(data);
        } catch (exception) {
            if (!controller.signal.aborted && abort.current === controller) reportLoadError(exception);
        } finally {
            if (!controller.signal.aborted && abort.current === controller) setLoading(false);
        }
    }, [acceptOverview, reportLoadError]);

    useEffect(() => {
        const controller = new AbortController();
        abort.current = controller;
        request('/overview', null, controller.signal).then(data => {
            if (!controller.signal.aborted && abort.current === controller) acceptOverview(data);
        }).catch(exception => {
            if (!controller.signal.aborted && abort.current === controller) reportLoadError(exception);
        }).finally(() => {
            if (!controller.signal.aborted && abort.current === controller) setLoading(false);
        });
        return () => abort.current?.abort();
    }, [acceptOverview, reportLoadError]);

    function confirm(action, target) {
        if (loading || operationLock.current || !overview || overview.database !== database || error) return;
        setPending({ action, ...target, profileId: overview.profileId, database: overview.database });
        setFullScan(false);
        dialog.current.showModal();
    }

    async function execute() {
        if (!pending || operationLock.current || loading || pending.database !== overview?.database
            || pending.profileId !== overview?.profileId) return;
        operationLock.current = true;
        setRunning(true);
        setResult(null);
        dialog.current.close();
        try {
            const response = await request(`/${pending.action}`, {
                profileId: pending.profileId,
                database: pending.database,
                objectId: pending.objectId,
                schema: pending.schema,
                name: pending.name,
                confirmed: true,
                fullScan
            });
            setResult(response);
        } catch (exception) {
            setResult({ ok: false, message: `No se pudo confirmar el resultado. ${exception.message}. Actualice el análisis antes de reintentar.` });
        } finally {
            setPending(null);
            await load(pending.database);
            setRunning(false);
            operationLock.current = false;
        }
    }

    const matches = item => `${item.schema}.${item.name}`.toLowerCase().includes(search.toLowerCase().trim());
    const tables = overview?.tables.filter(matches) || [];
    const objects = overview?.objects.filter(matches) || [];
    const disabled = loading || running;
    const unverified = result?.status === 'unverified';

    return (
        <section className="maintenance" aria-busy={disabled}>
            <header className="head">
                <div>
                    <h1>Mantenimiento preventivo</h1>
                    <p className="sub">Estadísticas y dependencias de SQL Server</p>
                    <p className="sub">Instancia: {profileName || 'Sin análisis disponible'} · <Link to="/instance">Configurar conexión</Link></p>
                </div>
                <div className="controls">
                    <label>Base de datos
                        <select value={database} disabled={running || !databases.length}
                                onChange={event => { setDatabase(event.target.value); setResult(null); void load(event.target.value); }}>
                            {!databases.length && <option value="">Seleccione una conexión</option>}
                            {databases.map(name => <option key={name}>{name}</option>)}
                        </select>
                    </label>
                    <button className="btn btnGhost" disabled={disabled} onClick={() => { setResult(null); void load(database); }}>
                        {loading ? 'Analizando…' : 'Actualizar análisis'}
                    </button>
                </div>
            </header>

            {error && <div className="errorBox pageMessage" role="alert">{error}</div>}
            {running && <div className="warnBox pageMessage" role="status">Ejecutando mantenimiento. Espere la confirmación del servidor.</div>}
            {result && <div className={`${result.ok ? 'maintenanceSuccess' : unverified ? 'warnBox' : 'errorBox'} pageMessage`} role="status">
                <strong>{result.ok ? 'Operación verificada' : unverified ? 'Comando ejecutado · resultado no verificable' : 'Operación con errores o pendiente de verificar'}</strong>
                {result.object && <p>{result.database} · {result.object}</p>}
                <p>{result.message}</p>
            </div>}
            {overview?.warnings.map(warning => <div key={warning} className="warnBox pageMessage" role="alert">{warning}</div>)}

            <div className="grid maintenanceMetrics">
                <div className="card kpi"><div className="kpiLabel">Tablas disponibles</div><div className="kpiValue">{overview?.tables.length ?? '—'}</div></div>
                <div className="card kpi"><div className="kpiLabel">Objetos con problemas</div><div className="kpiValue">{overview?.objects.length ?? '—'}</div></div>
                <div className="card kpi"><div className="kpiLabel">Avisos del análisis</div><div className="kpiValue">{overview?.warnings.length ?? '—'}</div></div>
            </div>

            {overview && <>
                <p className="sub maintenanceNote">Análisis: {new Date(overview.checkedAt).toLocaleString('es-CR')}.
                    {overview.warnings.length > 0 ? ' El análisis tiene limitaciones: revise los avisos.' : ' Consulte el alcance del diagnóstico más abajo.'}</p>
                <label className="maintenanceSearch">Buscar tabla u objeto
                    <input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Esquema u objeto" />
                </label>
                <div className="card maintenanceCard">
                    <h2>Actualizar estadísticas <small>{tables.length} tablas</small></h2>
                    <p className="sub maintenanceNote">Actualiza las estadísticas existentes: su cantidad no aumenta al actualizarlas. La fecha es la más antigua entre las disponibles y los cambios muestran el máximo por estadística; 0 es normal después de actualizar. Sin fecha puede indicar falta de filas o permisos, no necesariamente un error.</p>
                    <div className="tableWrap"><table>
                        <thead><tr><th>Tabla</th><th>Estadísticas</th><th>Fecha estadística más antigua</th><th>Cambios</th><th>Disponibilidad de fechas</th><th>Acción</th></tr></thead>
                        <tbody>
                        {!tables.length && <tr><td colSpan={6}>{overview.warnings.length
                            ? 'No hay tablas disponibles para mostrar; revise los avisos del análisis y los filtros.'
                            : 'No hay tablas que coincidan. Las tablas optimizadas en memoria se excluyen.'}</td></tr>}
                        {tables.map(table => <tr key={table.objectId}>
                            <td className="mono">{table.schema}.{table.name}</td><td>{table.statistics}</td>
                            <td>{table.lastUpdated ? new Date(table.lastUpdated).toLocaleString('es-CR') : 'Sin fecha disponible'}</td>
                            <td>{table.modifications ?? 'No disponible'}</td>
                            <td className="maintenanceProblem">{!table.statistics ? 'Sin estadísticas existentes'
                                : table.datedStatistics == null ? 'Actualice backend y análisis para consultar el detalle'
                                    : table.datedStatistics === table.statistics ? `${table.datedStatistics} de ${table.statistics} con fecha`
                                        : `${table.datedStatistics} de ${table.statistics} con fecha. Puede faltar información por ausencia de filas o permisos.`}</td>
                            <td><button className="btn" disabled={disabled || !table.statistics} onClick={() => confirm('statistics', table)}>Actualizar estadísticas</button></td>
                        </tr>)}
                        </tbody>
                    </table></div>
                </div>

                <div className="card maintenanceCard">
                    <h2>Objetos inválidos o con errores <small>{objects.length} objetos</small></h2>
                    <p className="sub maintenanceNote">SQL Server actualiza los metadatos del módulo con sp_refreshsqlmodule y vuelve a validar las dependencias. Las referencias inexistentes requieren corregir el código o restaurar el objeto faltante.</p>
                    <div className="tableWrap"><table>
                        <thead><tr><th>Objeto</th><th>Tipo</th><th>Diagnóstico</th><th>Acción</th></tr></thead>
                        <tbody>
                        {!objects.length && <tr><td colSpan={4}>{overview.warnings.length ? 'Sin hallazgos en la parte analizada; revise las limitaciones.' : 'No se detectaron problemas en los módulos analizados.'}</td></tr>}
                        {objects.map(object => <tr key={object.objectId}>
                            <td className="mono">{object.schema}.{object.name}</td><td>{object.type}</td>
                            <td className="maintenanceProblem">{object.problem}</td>
                            <td><button className="btn" disabled={disabled || !object.repairable} onClick={() => confirm('refresh', object)}>
                                {object.repairable ? 'Corregir / recompilar' : 'Revisión manual'}
                            </button></td>
                        </tr>)}
                        </tbody>
                    </table></div>
                </div>
                <p className="sub maintenanceNote">Alcance: vistas, procedimientos, funciones y triggers DML de usuario. No ejecuta el código de los módulos. SQL dinámico, referencias externas, objetos cifrados y errores de ejecución requieren revisión adicional. Un análisis sin hallazgos no garantiza que todo el código pueda ejecutarse.</p>
            </>}

            <dialog ref={dialog} className="modal" aria-labelledby="maintenanceConfirmTitle" onClose={() => setPending(null)}>
                <div className="modalHead"><h3 id="maintenanceConfirmTitle">Confirmar mantenimiento</h3></div>
                <div className="modalBody">
                    <p><strong>{overview?.profileName} · {pending?.database}</strong></p>
                    <p className="mono">{pending?.schema}.{pending?.name}</p>
                    <p>{pending?.action === 'statistics' ? 'Se actualizarán las estadísticas existentes. Puede consumir CPU y lecturas y provocar recompilación de consultas.' : 'Se actualizarán los metadatos del módulo y se comprobarán sus dependencias. Si faltan objetos o columnas, se informará del error para su corrección manual.'}</p>
                    {pending?.action === 'statistics' && <label className="check maintenanceNote"><input type="checkbox" checked={fullScan} onChange={event => setFullScan(event.target.checked)} /> Usar FULLSCAN (lee todas las filas; mayor costo)</label>}
                </div>
                <div className="modalFoot">
                    <button className="btn btnGhost" onClick={() => dialog.current.close()}>Cancelar</button>
                    <button className="btn" disabled={running} onClick={execute}>Confirmar y ejecutar</button>
                </div>
            </dialog>
        </section>
    );
}

export default Maintenance;
