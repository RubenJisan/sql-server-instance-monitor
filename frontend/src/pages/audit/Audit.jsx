import './Audit.css';
import { useCallback, useEffect, useRef, useState } from 'react';

const API = 'http://localhost:8080/api';

const emptyMetrics = {
    logins: null,
    enabledLogins: null,
    disabledLogins: null,
    roles: null,
    sysadminRoles: null,
    expiringAccounts: null,
    expiringDaysAhead: null,
    invalidObjects: null,
    invalidSchemas: null
};

const emptyAudit = {
    metrics: emptyMetrics,
    users: [],           // { name, principalRole, status, statusLabel, expiresAt }
    roles: [],           // { name, privileges, userCount, critical }
    privileges: [],      // { grantee, privilege, object, grantor, state, stateLabel }
    invalidObjects: []   // { schema, name, type, problem }
};

const tabs = [
    { id: 'users',          label: 'Usuarios' },
    { id: 'roles',          label: 'Roles' },
    { id: 'privileges',     label: 'Privilegios' },
    { id: 'invalidObjects', label: 'Objetos inválidos' }
];

/* ------------------------------------------------------------------ */
/*  Utilidades (lambdas, sin clases)                                   */
/* ------------------------------------------------------------------ */

const badgeClasses = {
    online:  'badgeOk',
    warning: 'badgeWarn',
    offline: 'badgeErr'
};

const ringClasses = {
    online:  'ringOk',
    warning: 'ringWarn',
    offline: 'ringErr'
};

const badge = (status, live = false) => {
    const tone   = badgeClasses[status] ?? '';
    const pulse  = live && status === 'online' ? 'live' : '';
    return `badge ${tone} ${pulse}`.trim();
};

const ring = (status) => ringClasses[status] ?? '';

const display = (value, suffix = '') =>
    value == null ? '—' : `${value}${suffix}`;

const initial = (name) => {
    if (!name) return '?';
    return name.trim().charAt(0).toUpperCase();
};

/* ------------------------------------------------------------------ */
/*  Cliente HTTP (listo para cuando exista el backend)                 */
/* ------------------------------------------------------------------ */

class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

class ApiClient {
    static async request(path, { method = 'GET', body } = {}) {
        let response;
        try {
            response = await fetch(`${API}${path}`, {
                method,
                headers: body ? { 'Content-Type': 'application/json' } : undefined,
                body: body ? JSON.stringify(body) : undefined
            });
        } catch {
            throw new ApiError('No se pudo contactar con el servidor backend.', 0);
        }

        const text = await response.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch { data = null; }

        if (!response.ok) {
            throw new ApiError(
                data?.message ?? `Error ${response.status} del servidor.`,
                response.status
            );
        }
        return data;
    }
}

/* ------------------------------------------------------------------ */
/*  Componente                                                         */
/* ------------------------------------------------------------------ */

function Audit() {
    const [audit, setAudit] = useState(emptyAudit);
    const [profiles, setProfiles] = useState([]);
    const [activeId, setActiveId] = useState(null);
    const [selectedId, setSelectedId] = useState(null);

    const [loading, setLoading] = useState(true);
    const [tab, setTab] = useState('users');
    const [pageMessage, setPageMessage] = useState(null);
    const [dialogError, setDialogError] = useState('');
    const [toast, setToast] = useState('');

    const instanceDialogRef = useRef(null);
    const inFlightRef = useRef(false);

    const { metrics, users, roles, privileges, invalidObjects } = audit;
    const activeProfile = profiles.find(p => p.id === activeId) ?? null;

    /* ----------------------- Carga de datos ----------------------- */

    const loadAudit = useCallback(async () => {
        inFlightRef.current = true;
        try {
            // TODO: cuando el backend esté listo:
            // const data = await ApiClient.request('/audit/overview');
            // setAudit(data);
            setAudit(emptyAudit);
            setPageMessage(null);
        } catch (e) {
            const noActiveConnection = e.status === 409;
            if (noActiveConnection) setAudit(emptyAudit);
            setPageMessage({
                type: noActiveConnection ? 'warn' : 'error',
                text: e.message
            });
        } finally {
            inFlightRef.current = false;
            setLoading(false);
        }
    }, []);

    const loadProfiles = useCallback(async () => {
        try {
            // TODO: cuando el backend esté listo:
            // const data = await ApiClient.request('/connections');
            // setProfiles(data.profiles);
            // setActiveId(data.activeId);
            setProfiles([]);
            setActiveId(null);
        } catch (e) {
            setPageMessage({ type: 'error', text: e.message });
        }
    }, []);

    /* ----------------------- Acciones ----------------------- */

    async function connectProfile() {
        if (!selectedId) return;
        setDialogError('');
        try {
            // TODO: await ApiClient.request(`/connections/${selectedId}/activate`, { method: 'POST' });
            // const profile = ...
            // setActiveId(selectedId);
            setActiveId(selectedId);
            await Promise.all([loadAudit(), loadProfiles()]);
            closeDialog(instanceDialogRef);
            const p = profiles.find(x => x.id === selectedId);
            if (p) setToast(`Conectado a ${p.name}`);
        } catch (e) {
            setDialogError(e.message);
        }
    }

    /* ----------------------- Efectos ----------------------- */

    useEffect(() => {
        (async () => {
            await Promise.all([loadAudit(), loadProfiles()]);
        })();
    }, [loadAudit, loadProfiles]);

    useEffect(() => {
        if (!toast) return;
        const timer = setTimeout(() => setToast(''), 3400);
        return () => clearTimeout(timer);
    }, [toast]);

    /* ----------------------- Diálogos ----------------------- */

    function openDialog(ref)  { ref.current?.showModal(); }
    function closeDialog(ref) { ref.current?.close(); }

    function closeOnBackdrop(e) {
        if (e.target === e.currentTarget) e.currentTarget.close();
    }

    function openInstanceDialog() {
        setSelectedId(activeId);
        setDialogError('');
        openDialog(instanceDialogRef);
    }

    /* ----------------------- Render ----------------------- */

    return (
        <>
            <header className="head">
                <div>
                    <h1>Auditoría</h1>
                    <p className="sub">
                        Usuarios, roles, privilegios y objetos con errores.
                    </p>
                </div>
                <div className="controls">
                    <button
                        type="button"
                        className="selector"
                        onClick={openInstanceDialog}
                    >
                        <span className={badge(activeProfile?.status, true)}>
                            {display(activeProfile?.name)}
                        </span>
                        {display(activeProfile?.serverName)} ▾
                    </button>
                </div>
            </header>

            {pageMessage && (
                <div
                    className={`${pageMessage.type === 'warn' ? 'warnBox' : 'errorBox'} pageMessage`}
                    role="alert"
                >
                    {pageMessage.text}
                </div>
            )}

            {/* ----- KPIs ----- */}
            <div className="grid cols4">
                <div className="card kpi">
                    <div className="kpiLabel">Logins registrados</div>
                    <div className="kpiValue">{display(metrics.logins)}</div>
                    <div className="kpiDetail">
                        {display(metrics.enabledLogins)} habilitados ·{' '}
                        {display(metrics.disabledLogins)} deshabilitados
                    </div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Roles</div>
                    <div className="kpiValue">{display(metrics.roles)}</div>
                    <div className="kpiDetail">
                        {display(metrics.sysadminRoles)} con rol sysadmin
                    </div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Cuentas por expirar</div>
                    <div className="kpiValue textWarn">{display(metrics.expiringAccounts)}</div>
                    <div className="kpiDetail">
                        en los próximos {display(metrics.expiringDaysAhead, ' días')}
                    </div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Objetos inválidos</div>
                    <div className="kpiValue textErr">{display(metrics.invalidObjects)}</div>
                    <div className="kpiDetail">
                        en {display(metrics.invalidSchemas)} esquemas
                    </div>
                </div>
            </div>

            {/* ----- Tabs ----- */}
            <div className="tabs" role="tablist" aria-label="Secciones de auditoría">
                {tabs.map(t => (
                    <button
                        key={t.id}
                        type="button"
                        role="tab"
                        aria-selected={tab === t.id}
                        className={tab === t.id ? 'on' : ''}
                        onClick={() => setTab(t.id)}
                    >
                        {t.label}
                    </button>
                ))}
            </div>

            {/* ----- Paneles por pestaña ----- */}
            {tab === 'users' && (
                <div className="card">
                    <h2>
                        Logins registrados
                        <small>{users.length} usuarios</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Usuario</th>
                                <th>Rol principal</th>
                                <th>Estado</th>
                                <th>Vence</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && users.length === 0 && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {users.map(u => (
                                <tr key={u.name}>
                                    <td>
                                            <span className="userCell">
                                                <span className="av">{initial(u.name)}</span>
                                                <span className="mono">{display(u.name)}</span>
                                            </span>
                                    </td>
                                    <td className="mono">{display(u.principalRole)}</td>
                                    <td>
                                            <span className={badge(u.status)}>
                                                {display(u.statusLabel)}
                                            </span>
                                    </td>
                                    <td className="mono">{display(u.expiresAt)}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {tab === 'roles' && (
                <div className="card">
                    <h2>
                        Roles y privilegios
                        <small>{roles.length} roles</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Rol</th>
                                <th>Privilegios</th>
                                <th className="alignRight">Usuarios</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={3} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && roles.length === 0 && (
                                <tr>
                                    <td colSpan={3} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {roles.map(r => (
                                <tr key={r.name}>
                                    <td className="mono">
                                        {r.critical && (
                                            <span className="badge badgeErr" style={{ marginRight: 8 }}>
                                                    CRÍTICO
                                                </span>
                                        )}
                                        {display(r.name)}
                                    </td>
                                    <td className="mono">{display(r.privileges)}</td>
                                    <td className="alignRight mono">{display(r.userCount)}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {tab === 'privileges' && (
                <div className="card">
                    <h2>
                        Privilegios asignados
                        <small>{privileges.length} concesiones</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Otorgado a</th>
                                <th>Privilegio</th>
                                <th>Objeto</th>
                                <th>Otorgante</th>
                                <th>Estado</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={5} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && privileges.length === 0 && (
                                <tr>
                                    <td colSpan={5} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {privileges.map((p, i) => (
                                <tr key={`${p.grantee}-${p.privilege}-${i}`}>
                                    <td className="mono">{display(p.grantee)}</td>
                                    <td className="mono">{display(p.privilege)}</td>
                                    <td className="mono">{display(p.object)}</td>
                                    <td className="mono">{display(p.grantor)}</td>
                                    <td>
                                            <span className={badge(p.status)}>
                                                {display(p.statusLabel)}
                                            </span>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {tab === 'invalidObjects' && (
                <div className="card">
                    <h2>
                        Objetos inválidos o con errores
                        <small>requieren intervención</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Esquema</th>
                                <th>Objeto</th>
                                <th>Tipo</th>
                                <th>Problema</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && invalidObjects.length === 0 && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {invalidObjects.map((o, i) => (
                                <tr key={`${o.schema}-${o.name}-${i}`}>
                                    <td className="mono">{display(o.schema)}</td>
                                    <td className="mono">{display(o.name)}</td>
                                    <td className="mono">{display(o.type)}</td>
                                    <td>
                                            <span className={badge('offline')}>
                                                {display(o.problem)}
                                            </span>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            {/* ----- Diálogo de selección de instancia ----- */}
            <dialog
                ref={instanceDialogRef}
                className="modal"
                aria-labelledby="auditInstanceDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="auditInstanceDialogTitle">Seleccionar instancia</h3>
                        <p>Conexiones guardadas en este equipo</p>
                    </div>
                    <button
                        type="button"
                        className="closeBtn"
                        aria-label="Cerrar"
                        onClick={() => closeDialog(instanceDialogRef)}
                    >
                        ✕
                    </button>
                </div>
                <div className="modalBody">
                    {profiles.length === 0 && (
                        <p className="sub">
                            Sin conexiones guardadas. Configura una desde el módulo de
                            Estado de la instancia.
                        </p>
                    )}
                    {profiles.map(item => (
                        <label key={item.id} className="option">
                            <input
                                type="radio"
                                name="auditInstance"
                                checked={selectedId === item.id}
                                onChange={() => setSelectedId(item.id)}
                            />
                            <span>
                                <b>{item.name}{item.id === activeId ? ' (activa)' : ''}</b>
                                <small>{item.details}</small>
                            </span>
                        </label>
                    ))}
                    {dialogError && (
                        <div className="errorBox" role="alert">{dialogError}</div>
                    )}
                </div>
                <div className="modalFoot">
                    <button
                        type="button"
                        className="btn btnGhost"
                        onClick={() => closeDialog(instanceDialogRef)}
                    >
                        Cancelar
                    </button>
                    <button
                        type="button"
                        className="btn"
                        disabled={!selectedId}
                        onClick={connectProfile}
                    >
                        Conectar
                    </button>
                </div>
            </dialog>

            {toast && (
                <div className="toast" role="status">
                    {toast}
                </div>
            )}
        </>
    );
}

export default Audit;