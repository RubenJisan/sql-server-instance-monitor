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
    users: [],
    roles: [],
    privileges: [],
    invalidObjects: [],
    scan: { databases: [], warnings: [] }
};

const tabs = [
    { id: 'users', label: 'Usuarios' },
    { id: 'roles', label: 'Roles' },
    { id: 'privileges', label: 'Privilegios' },
    { id: 'invalidObjects', label: 'Objetos inválidos' }
];

const authLabels = {
    sqlServer: 'SQL Server',
    windows: 'Windows (Integrada)'
};

const badgeClasses = {
    online: 'badgeOk',
    warning: 'badgeWarn',
    offline: 'badgeErr'
};

const badge = (status, live = false) => {
    const tone = badgeClasses[status] ?? '';
    const pulse = live && status === 'online' ? 'live' : '';
    return `badge ${tone} ${pulse}`.trim();
};

const display = (value, suffix = '') =>
    value == null ? '—' : `${value}${suffix}`;

const initial = (name) => (name ? name.trim().charAt(0).toUpperCase() : '?');

const matches = (values, term) => {
    const needle = term.trim().toLowerCase();
    return !needle || values.some(value => value != null && String(value).toLowerCase().includes(needle));
};

const isInternalRole = (name) => name.startsWith('##');

const roleKind = (role) => {
    if (role.name === 'public') return 'Predeterminado';
    return role.fixed ? 'Fijo' : 'Personalizado';
};

const roleMembers = (role) => {
    if (role.name === 'public') return 'Todos los logins';
    return role.members.length > 0 ? role.members.join(', ') : null;
};

const rolePrivileges = (role) => role.privileges ?? (role.fixed ? 'Implícitos del rol fijo' : null);

const profileAddress = (profile) =>
    profile.port ? `${profile.host}:${profile.port}` : profile.host;

const describeProfile = (profile) =>
    [profileAddress(profile), profile.instanceName, authLabels[profile.authentication], profile.username]
        .filter(Boolean)
        .join(' · ');

const formatTime = (date) =>
    date.toLocaleTimeString('es-CR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

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
        try {
            data = text ? JSON.parse(text) : null;
        } catch {
            data = null;
        }

        if (!response.ok) {
            throw new ApiError(data?.message ?? `Error ${response.status} del servidor.`, response.status);
        }
        return data;
    }
}

function EmptyRow({ colSpan, loading, total, emptyText = 'Sin datos' }) {
    let text = emptyText;
    if (loading) text = 'Cargando...';
    else if (total > 0) text = 'Sin resultados para la búsqueda';

    return (
        <tr>
            <td colSpan={colSpan} className="emptyCell">{text}</td>
        </tr>
    );
}

function Audit() {
    const [audit, setAudit] = useState(emptyAudit);
    const [profiles, setProfiles] = useState([]);
    const [activeId, setActiveId] = useState(null);
    const [selectedId, setSelectedId] = useState(null);

    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [loadedAt, setLoadedAt] = useState(null);
    const [tab, setTab] = useState('users');
    const [searchTerm, setSearchTerm] = useState('');
    const [showInternalRoles, setShowInternalRoles] = useState(false);
    const [pageMessage, setPageMessage] = useState(null);
    const [dialogError, setDialogError] = useState('');
    const [toast, setToast] = useState('');

    const instanceDialogRef = useRef(null);

    const { metrics, users, roles, privileges, invalidObjects, scan } = audit;
    const activeProfile = profiles.find(profile => profile.id === activeId) ?? null;

    const scanIncomplete = scan.warnings.length > 0;
    const scanFailed = scan.databases.length > 0 && scan.warnings.length === scan.databases.length;
    const invalidTone = scanIncomplete ? 'textWarn' : metrics.invalidObjects > 0 ? 'textErr' : 'textOk';
    const connectionStatus = activeProfile ? (pageMessage?.type === 'error' ? 'offline' : 'online') : undefined;

    const filteredUsers = users.filter(user =>
        matches([user.name, user.type, user.principalRole, user.statusLabel, user.defaultDatabase], searchTerm)
    );
    const filteredRoles = roles.filter(role =>
        (showInternalRoles || !isInternalRole(role.name))
        && matches([role.name, roleKind(role), role.privileges, ...role.members], searchTerm)
    );
    const filteredPrivileges = privileges.filter(privilege =>
        matches([privilege.grantee, privilege.privilege, privilege.object, privilege.grantor,
            privilege.source, privilege.statusLabel], searchTerm)
    );
    const filteredInvalidObjects = invalidObjects.filter(object =>
        matches([object.database, object.schema, object.name, object.type, object.problem], searchTerm)
    );

    const loadAudit = useCallback(async () => {
        try {
            const data = await ApiClient.request('/audit/overview');
            setAudit(data);
            setLoadedAt(new Date());
            setPageMessage(null);
        } catch (e) {
            const noActiveConnection = e.status === 409;
            if (noActiveConnection) setAudit(emptyAudit);
            setPageMessage({ type: noActiveConnection ? 'warn' : 'error', text: e.message });
        } finally {
            setLoading(false);
        }
    }, []);

    const loadProfiles = useCallback(async () => {
        try {
            const data = await ApiClient.request('/connections');
            setProfiles(data.profiles);
            setActiveId(data.activeId);
        } catch (e) {
            setPageMessage({ type: 'error', text: e.message });
        }
    }, []);

    async function refreshAll() {
        setRefreshing(true);
        await Promise.all([loadAudit(), loadProfiles()]);
        setRefreshing(false);
    }

    async function connectProfile() {
        if (!selectedId) return;
        setDialogError('');
        try {
            const profile = await ApiClient.request(`/connections/${selectedId}/activate`, { method: 'POST' });
            await Promise.all([loadAudit(), loadProfiles()]);
            closeDialog(instanceDialogRef);
            setToast(`Conectado a ${profile.name}`);
        } catch (e) {
            setDialogError(e.message);
        }
    }

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

    function openDialog(ref) {
        ref.current?.showModal();
    }

    function closeDialog(ref) {
        ref.current?.close();
    }

    function closeOnBackdrop(e) {
        if (e.target === e.currentTarget) e.currentTarget.close();
    }

    function openInstanceDialog() {
        setSelectedId(activeId);
        setDialogError('');
        openDialog(instanceDialogRef);
    }

    function selectTab(id) {
        setTab(id);
        setSearchTerm('');
    }

    return (
        <>
            <header className="head">
                <div>
                    <h1>Auditoría</h1>
                    <p className="sub">
                        Usuarios, roles, privilegios y objetos con errores.
                        {loadedAt && ` Actualizado a las ${formatTime(loadedAt)}`}
                    </p>
                </div>
                <div className="controls">
                    <button
                        type="button"
                        className="selector"
                        onClick={openInstanceDialog}
                    >
                        <span className={badge(connectionStatus, true)}>
                            {display(activeProfile?.name)}
                        </span>
                        {display(activeProfile ? profileAddress(activeProfile) : null)} ▾
                    </button>
                    <button
                        type="button"
                        className="btn btnGhost"
                        disabled={refreshing}
                        onClick={refreshAll}
                    >
                        {refreshing ? 'Actualizando…' : 'Actualizar'}
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
                    <div className={`kpiValue ${invalidTone}`}>
                        {display(scanFailed ? null : metrics.invalidObjects)}
                    </div>
                    <div className="kpiDetail">
                        {scanIncomplete
                            ? `análisis incompleto: ${scan.warnings.length} de ${scan.databases.length} bases con avisos`
                            : `en ${display(metrics.invalidSchemas)} esquemas`}
                    </div>
                </div>
            </div>

            <div className="tabs" role="tablist" aria-label="Secciones de auditoría">
                {tabs.map(t => (
                    <button
                        key={t.id}
                        type="button"
                        role="tab"
                        aria-selected={tab === t.id}
                        className={tab === t.id ? 'on' : ''}
                        onClick={() => selectTab(t.id)}
                    >
                        {t.label}
                    </button>
                ))}
            </div>

            <div className="filters">
                <input
                    type="search"
                    className="searchInput"
                    placeholder="Buscar en esta sección"
                    aria-label="Buscar en esta sección"
                    value={searchTerm}
                    onChange={e => setSearchTerm(e.target.value)}
                />
                {tab === 'roles' && (
                    <label className="check">
                        <input
                            type="checkbox"
                            checked={showInternalRoles}
                            onChange={e => setShowInternalRoles(e.target.checked)}
                        />
                        Mostrar roles internos (##MS_…##)
                    </label>
                )}
            </div>

            {tab === 'users' && (
                <div className="card">
                    <h2>
                        Logins registrados
                        <small>{filteredUsers.length} de {users.length} usuarios</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Usuario</th>
                                <th>Tipo</th>
                                <th>Rol principal</th>
                                <th>Estado</th>
                                <th>Vence</th>
                            </tr>
                            </thead>
                            <tbody>
                            {filteredUsers.length === 0 && (
                                <EmptyRow colSpan={5} loading={loading} total={users.length} />
                            )}
                            {filteredUsers.map(user => (
                                <tr key={user.name}>
                                    <td>
                                            <span className="userCell">
                                                <span className="av">{initial(user.name)}</span>
                                                <span className="mono">{display(user.name)}</span>
                                            </span>
                                    </td>
                                    <td>{display(user.type)}</td>
                                    <td
                                        className="mono"
                                        title={user.roles.length > 1 ? user.roles.join(', ') : undefined}
                                    >
                                        {display(user.principalRole)}
                                    </td>
                                    <td>
                                            <span className={badge(user.status)}>
                                                {display(user.statusLabel)}
                                            </span>
                                    </td>
                                    <td className="mono">{display(user.expiresAt)}</td>
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
                        <small>{filteredRoles.length} de {roles.length} roles</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Rol</th>
                                <th>Tipo</th>
                                <th>Privilegios</th>
                                <th>Miembros</th>
                                <th className="alignRight">Usuarios</th>
                            </tr>
                            </thead>
                            <tbody>
                            {filteredRoles.length === 0 && (
                                <EmptyRow colSpan={5} loading={loading} total={roles.length} />
                            )}
                            {filteredRoles.map(role => (
                                <tr key={role.name}>
                                    <td className="mono">
                                        {role.critical && (
                                            <span className="badge badgeErr" style={{ marginRight: 8 }}>
                                                    ADMIN
                                                </span>
                                        )}
                                        {display(role.name)}
                                    </td>
                                    <td>{roleKind(role)}</td>
                                    <td className="mono wrapCell">{display(rolePrivileges(role))}</td>
                                    <td className="mono wrapCell">{display(roleMembers(role))}</td>
                                    <td className="alignRight mono">{display(role.userCount)}</td>
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
                        <small>{filteredPrivileges.length} de {privileges.length} concesiones</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Otorgado a</th>
                                <th>Privilegio</th>
                                <th>Objeto</th>
                                <th>Otorgante</th>
                                <th>Origen</th>
                                <th>Estado</th>
                            </tr>
                            </thead>
                            <tbody>
                            {filteredPrivileges.length === 0 && (
                                <EmptyRow colSpan={6} loading={loading} total={privileges.length} />
                            )}
                            {filteredPrivileges.map((privilege, index) => (
                                <tr key={`${privilege.grantee}-${privilege.privilege}-${privilege.source}-${index}`}>
                                    <td className="mono">{display(privilege.grantee)}</td>
                                    <td className="mono">{display(privilege.privilege)}</td>
                                    <td className="mono">{display(privilege.object)}</td>
                                    <td className="mono">{display(privilege.grantor)}</td>
                                    <td>{display(privilege.source)}</td>
                                    <td>
                                            <span className={badge(privilege.status)}>
                                                {display(privilege.statusLabel)}
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
                <>
                    {scan.warnings.map(warning => (
                        <div key={warning} className="warnBox pageMessage" role="alert">
                            {warning}
                        </div>
                    ))}
                    <div className="card">
                        <h2>
                            Objetos inválidos o con errores
                            <small>
                                {filteredInvalidObjects.length} de {invalidObjects.length} objetos ·{' '}
                                {scan.databases.length} bases analizadas
                            </small>
                        </h2>
                        <div className="tableWrap">
                            <table>
                                <thead>
                                <tr>
                                    <th>Base de datos</th>
                                    <th>Esquema</th>
                                    <th>Objeto</th>
                                    <th>Tipo</th>
                                    <th>Problema</th>
                                </tr>
                                </thead>
                                <tbody>
                                {filteredInvalidObjects.length === 0 && (
                                    <EmptyRow
                                        colSpan={5}
                                        loading={loading}
                                        total={invalidObjects.length}
                                        emptyText={scanIncomplete
                                            ? 'Sin resultados: el análisis no se completó'
                                            : 'No se encontraron objetos inválidos'}
                                    />
                                )}
                                {filteredInvalidObjects.map((object, index) => (
                                    <tr key={`${object.database}-${object.schema}-${object.name}-${index}`}>
                                        <td className="mono">{display(object.database)}</td>
                                        <td className="mono">{display(object.schema)}</td>
                                        <td className="mono">{display(object.name)}</td>
                                        <td className="mono">{display(object.type)}</td>
                                        <td>
                                                <span className={badge('offline')}>
                                                    {display(object.problem)}
                                                </span>
                                        </td>
                                    </tr>
                                ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </>
            )}

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
                                <small>{describeProfile(item)}</small>
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