import './Instance.css';
import { useCallback, useEffect, useRef, useState } from 'react';

const API = 'http://localhost:8080/api';

const emptyOverview = {
    instance: { name: null, status: null, statusLabel: null, version: null, uptime: null, startedAt: null },
    server: { name: null, details: null },
    databases: [],
    memory: { maxServer: null, inUse: null, inUsePercent: null, bufferAllocated: null, bufferInUse: null, bufferPercent: null },
    connection: { host: null, instance: null, user: null },
    sessions: { total: null, allowed: null },
    lastCheck: { collectedAt: null, refreshSeconds: null }
};

const emptyConfig = {
    id: null,
    host: '',
    instanceName: '',
    port: '',
    authentication: 'sqlServer',
    username: '',
    password: '',
    refreshSeconds: '10',
    encrypt: true,
    trustServerCertificate: false
};

const authOptions = [
    { value: 'sqlServer', label: 'SQL Server' },
    { value: 'windows', label: 'Windows (Integrada)' }
];

const refreshOptions = [
    { value: '10', label: '10 segundos' },
    { value: '30', label: '30 segundos' },
    { value: '60', label: '1 minuto' }
];

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

class InstanceUtils {
    static badgeClasses = { online: 'badgeOk', warning: 'badgeWarn', offline: 'badgeErr' };
    static ringClasses = { online: 'ringOk', warning: 'ringWarn', offline: 'ringErr' };

    static badge(status, live = false) {
        const tone = this.badgeClasses[status] ?? '';
        const pulse = live && status === 'online' ? 'live' : '';
        return `badge ${tone} ${pulse}`.trim();
    }

    static ring(status) {
        return this.ringClasses[status] ?? '';
    }

    static display(value, suffix = '') {
        return value == null ? '—' : `${value}${suffix}`;
    }

    static usage(value, percent) {
        return value == null ? '—' : `${value} · ${percent ?? 0}%`;
    }

    static barStyle(percent) {
        return { '--w': `${percent ?? 0}%` };
    }

    static formatAgo(collectedAt, now) {
        if (!collectedAt) return null;
        const seconds = Math.max(0, Math.floor((now - Date.parse(collectedAt)) / 1000));
        if (seconds < 60) return `hace ${seconds} s`;
        if (seconds < 3600) return `hace ${Math.floor(seconds / 60)} min`;
        return `hace ${Math.floor(seconds / 3600)} h`;
    }

    static deriveName(config) {
        const host = config.host.trim();
        const instanceName = config.instanceName.trim();
        return instanceName ? `${host}\\${instanceName}` : host;
    }

    static describeProfile(profile) {
        const address = profile.port ? `${profile.host}:${profile.port}` : profile.host;
        const authentication = authOptions.find(option => option.value === profile.authentication)?.label;
        return [address, profile.instanceName, authentication, profile.username]
            .filter(Boolean)
            .join(' · ');
    }

    static toProfile(config) {
        return {
            id: config.id,
            name: this.deriveName(config),
            host: config.host.trim(),
            instanceName: config.instanceName.trim(),
            port: config.port.toString().trim() === '' ? null : Number(config.port),
            authentication: config.authentication,
            username: config.username.trim(),
            password: config.password,
            encrypt: config.encrypt,
            trustServerCertificate: config.trustServerCertificate,
            refreshSeconds: Number(config.refreshSeconds)
        };
    }

    static fromProfile(profile) {
        return {
            id: profile.id,
            host: profile.host,
            instanceName: profile.instanceName ?? '',
            port: profile.port == null ? '' : String(profile.port),
            authentication: profile.authentication,
            username: profile.username ?? '',
            password: '',
            refreshSeconds: String(profile.refreshSeconds),
            encrypt: profile.encrypt,
            trustServerCertificate: profile.trustServerCertificate
        };
    }
}

function TimeAgo({ since }) {
    const [now, setNow] = useState(() => Date.now());

    useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer);
    }, []);

    return InstanceUtils.display(InstanceUtils.formatAgo(since, now));
}

function Instance() {
    const [overview, setOverview] = useState(emptyOverview);
    const [profiles, setProfiles] = useState([]);
    const [activeId, setActiveId] = useState(null);
    const [selectedId, setSelectedId] = useState(null);
    const [config, setConfig] = useState(emptyConfig);

    const [loading, setLoading] = useState(true);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState('');
    const [pageMessage, setPageMessage] = useState(null);
    const [dialogError, setDialogError] = useState('');
    const [toast, setToast] = useState('');

    const instanceDialogRef = useRef(null);
    const configDialogRef = useRef(null);
    const inFlightRef = useRef(false);

    const { instance, server, databases, memory, connection, sessions, lastCheck } = overview;
    const { display, usage, barStyle } = InstanceUtils;
    const windowsAuthentication = config.authentication === 'windows';

    const loadOverview = useCallback(async () => {
        inFlightRef.current = true;
        try {
            const data = await ApiClient.request('/instance/overview');
            setOverview(data);
            setPageMessage(null);
        } catch (e) {
            const noActiveConnection = e.status === 409;
            if (noActiveConnection) setOverview(emptyOverview);
            setPageMessage({ type: noActiveConnection ? 'warn' : 'error', text: e.message });
        } finally {
            inFlightRef.current = false;
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

    async function connectProfile() {
        if (!selectedId) return;
        setDialogError('');
        try {
            const profile = await ApiClient.request(`/connections/${selectedId}/activate`, { method: 'POST' });
            await Promise.all([loadOverview(), loadProfiles()]);
            closeDialog(instanceDialogRef);
            setToast(`Conectado a ${profile.name}`);
        } catch (e) {
            setDialogError(e.message);
        }
    }

    async function testConnection() {
        setTesting(true);
        setTestResult('');
        setDialogError('');
        try {
            const result = await ApiClient.request('/connections/test', {
                method: 'POST',
                body: InstanceUtils.toProfile(config)
            });
            setTestResult(`Conexión exitosa a ${result.serverName} · SQL Server ${result.version}`);
        } catch (e) {
            setDialogError(e.message);
        } finally {
            setTesting(false);
        }
    }

    async function saveConfig() {
        setTestResult('');
        setDialogError('');
        try {
            const saved = await ApiClient.request('/connections', {
                method: 'POST',
                body: InstanceUtils.toProfile(config)
            });
            setConfig(prev => ({ ...prev, id: saved.id }));

            if (saved.id !== activeId) {
                await ApiClient.request(`/connections/${saved.id}/activate`, { method: 'POST' });
            }

            await Promise.all([loadOverview(), loadProfiles()]);
            closeDialog(configDialogRef);
            setToast('Configuración guardada');
        } catch (e) {
            setDialogError(e.message);
            await loadProfiles();
        }
    }

    useEffect(() => {
        (async () => {
            await Promise.all([loadOverview(), loadProfiles()]);
        })();
    }, [loadOverview, loadProfiles]);

    const refreshSeconds = lastCheck.refreshSeconds;

    useEffect(() => {
        if (!refreshSeconds) return;
        const timer = setInterval(() => {
            if (!document.hidden && !inFlightRef.current) loadOverview();
        }, refreshSeconds * 1000);
        return () => clearInterval(timer);
    }, [refreshSeconds, loadOverview]);

    useEffect(() => {
        if (!toast) return;
        const timer = setTimeout(() => setToast(''), 3400);
        return () => clearTimeout(timer);
    }, [toast]);

    function openDialog(ref) {
        ref.current.showModal();
    }

    function closeDialog(ref) {
        ref.current.close();
    }

    function closeOnBackdrop(e) {
        if (e.target === e.currentTarget) e.currentTarget.close();
    }

    function openInstanceDialog() {
        setSelectedId(activeId);
        setDialogError('');
        openDialog(instanceDialogRef);
    }

    function openConfigDialog() {
        const active = profiles.find(profile => profile.id === activeId);
        setConfig(active ? InstanceUtils.fromProfile(active) : emptyConfig);
        setTestResult('');
        setDialogError('');
        openDialog(configDialogRef);
    }

    function startNewConfig() {
        setConfig(emptyConfig);
        setTestResult('');
        setDialogError('');
    }

    function updateConfig(field, value) {
        setConfig(prev => ({ ...prev, [field]: value }));
    }

    return (
        <>
            <header className="head">
                <div>
                    <h1>Estado general de la instancia</h1>
                    <p className="sub">
                        Visión operativa de la instancia monitoreada en DataSolutions Costa Rica S.A.
                    </p>
                </div>
                <div className="controls">
                    <button
                        type="button"
                        className="selector"
                        onClick={openInstanceDialog}
                    >
                        <span className={InstanceUtils.badge(instance.status, true)}>
                            {display(instance.name)}
                        </span>
                        {display(server.name)} ▾
                    </button>
                    <button
                        type="button"
                        className="btn btnGhost"
                        onClick={openConfigDialog}
                    >
                        Configurar conexión
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

            <div className="grid cols3">
                <div className="card statusCard spanTwo">
                    <div className={`statusRing ${InstanceUtils.ring(instance.status)}`}>
                        <span className="statusDot">●</span>
                    </div>
                    <div>
                        <span className={InstanceUtils.badge(instance.status, true)}>
                            {display(instance.statusLabel)}
                        </span>
                        <div className="instanceName">{display(instance.name)}</div>
                        <div className="sub">{display(instance.version)}</div>
                    </div>
                    <div className="uptime">
                        <div className="sub">Tiempo de actividad</div>
                        <div className="uptimeValue">{display(instance.uptime)}</div>
                        <div className="sub mono">Inicio: {display(instance.startedAt)}</div>
                    </div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Servidor</div>
                    <div className="kpiValue">{display(server.name)}</div>
                    <div className="kpiDetail">{display(server.details)}</div>
                </div>
            </div>

            <div className="grid cols21">
                <div className="card">
                    <h2>
                        Bases de datos administradas
                        <small>{databases.length} bases de datos</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Nombre</th>
                                <th>Recuperación</th>
                                <th>Estado</th>
                                <th className="alignRight">Tamaño</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && databases.length === 0 && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {databases.map(db => (
                                <tr key={db.name}>
                                    <td className="mono">{db.name}</td>
                                    <td>{db.recoveryModel}</td>
                                    <td>
                                            <span className={InstanceUtils.badge(db.status)}>
                                                {db.statusLabel}
                                            </span>
                                    </td>
                                    <td className="alignRight mono">{db.size}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>

                <div className="card">
                    <h2>Memoria de la instancia</h2>
                    <div className="row">
                        <span>Max server memory</span>
                        <b>{display(memory.maxServer)}</b>
                    </div>
                    <div className="bar">
                        <i style={barStyle(memory.inUsePercent)}></i>
                    </div>
                    <div className="row">
                        <span>Memoria en uso</span>
                        <b>{usage(memory.inUse, memory.inUsePercent)}</b>
                    </div>
                    <br />
                    <div className="row">
                        <span>Buffer pool asignado</span>
                        <b>{display(memory.bufferAllocated)}</b>
                    </div>
                    <div className="bar barOk">
                        <i style={barStyle(memory.bufferPercent)}></i>
                    </div>
                    <div className="row">
                        <span>Buffer pool en uso</span>
                        <b>{usage(memory.bufferInUse, memory.bufferPercent)}</b>
                    </div>
                </div>
            </div>

            <div className="grid cols3">
                <div className="card">
                    <h2>Conexión activa</h2>
                    <div className="row">
                        <span>Host</span>
                        <span className="mono">{display(connection.host)}</span>
                    </div>
                    <div className="row">
                        <span>Instancia</span>
                        <span className="mono">{display(connection.instance)}</span>
                    </div>
                    <div className="row">
                        <span>Usuario</span>
                        <span className="mono">{display(connection.user)}</span>
                    </div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Sesiones totales</div>
                    <div className="kpiValue textOk">{display(sessions.total)}</div>
                    <div className="kpiDetail">de {display(sessions.allowed)} permitidas</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Último chequeo</div>
                    <div className="kpiValue">
                        <TimeAgo since={lastCheck.collectedAt} />
                    </div>
                    <div className="kpiDetail">
                        Refresco automático cada {display(lastCheck.refreshSeconds, ' s')}
                    </div>
                </div>
            </div>

            <dialog
                ref={instanceDialogRef}
                className="modal"
                aria-labelledby="instanceDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="instanceDialogTitle">Seleccionar instancia</h3>
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
                            Sin conexiones guardadas. Usa «Configurar conexión» para agregar una.
                        </p>
                    )}
                    {profiles.map(item => (
                        <label key={item.id} className="option">
                            <input
                                type="radio"
                                name="instance"
                                checked={selectedId === item.id}
                                onChange={() => setSelectedId(item.id)}
                            />
                            <span>
                                <b>{item.name}{item.id === activeId ? ' (activa)' : ''}</b>
                                <small>{InstanceUtils.describeProfile(item)}</small>
                            </span>
                        </label>
                    ))}
                    {dialogError && <div className="errorBox" role="alert">{dialogError}</div>}
                    <p className="sub">
                        El cambio de instancia no requiere modificar el código fuente.
                    </p>
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

            <dialog
                ref={configDialogRef}
                className="modal"
                aria-labelledby="configDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="configDialogTitle">Configurar conexión</h3>
                        <p>Parámetros para consultar las vistas administrativas (DMV)</p>
                    </div>
                    <button
                        type="button"
                        className="closeBtn"
                        aria-label="Cerrar"
                        onClick={() => closeDialog(configDialogRef)}
                    >
                        ✕
                    </button>
                </div>
                <div className="modalBody">
                    {config.id && (
                        <div className="editBar">
                            <span className="sub">Editando un perfil guardado</span>
                            <button type="button" className="linkBtn" onClick={startNewConfig}>
                                + Nueva conexión
                            </button>
                        </div>
                    )}

                    <div className="twoCols">
                        <div className="field">
                            <label htmlFor="configHost">Servidor</label>
                            <input
                                id="configHost"
                                type="text"
                                value={config.host}
                                onChange={e => updateConfig('host', e.target.value)}
                            />
                        </div>
                        <div className="field">
                            <label htmlFor="configInstanceName">Instancia</label>
                            <input
                                id="configInstanceName"
                                type="text"
                                value={config.instanceName}
                                onChange={e => updateConfig('instanceName', e.target.value)}
                            />
                        </div>
                    </div>

                    <div className="twoCols">
                        <div className="field">
                            <label htmlFor="configPort">Puerto</label>
                            <input
                                id="configPort"
                                type="text"
                                inputMode="numeric"
                                value={config.port}
                                onChange={e => updateConfig('port', e.target.value)}
                            />
                        </div>
                        <div className="field">
                            <label htmlFor="configAuth">Autenticación</label>
                            <select
                                id="configAuth"
                                value={config.authentication}
                                onChange={e => updateConfig('authentication', e.target.value)}
                            >
                                {authOptions.map(option => (
                                    <option key={option.value} value={option.value}>
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </div>
                    </div>

                    <div className="twoCols">
                        <div className="field">
                            <label htmlFor="configUsername">Usuario</label>
                            <input
                                id="configUsername"
                                type="text"
                                disabled={windowsAuthentication}
                                value={config.username}
                                onChange={e => updateConfig('username', e.target.value)}
                            />
                        </div>
                        <div className="field">
                            <label htmlFor="configPassword">Contraseña</label>
                            <input
                                id="configPassword"
                                type="password"
                                disabled={windowsAuthentication}
                                placeholder={config.id ? 'Sin cambios' : ''}
                                value={config.password}
                                onChange={e => updateConfig('password', e.target.value)}
                            />
                        </div>
                    </div>

                    <div className="field">
                        <label htmlFor="configRefresh">Intervalo de refresco</label>
                        <select
                            id="configRefresh"
                            value={config.refreshSeconds}
                            onChange={e => updateConfig('refreshSeconds', e.target.value)}
                        >
                            {refreshOptions.map(option => (
                                <option key={option.value} value={option.value}>
                                    {option.label}
                                </option>
                            ))}
                        </select>
                    </div>

                    <label className="check">
                        <input
                            type="checkbox"
                            checked={config.encrypt}
                            onChange={e => updateConfig('encrypt', e.target.checked)}
                        />
                        Cifrar conexión (Encrypt=True)
                    </label>
                    <label className="check">
                        <input
                            type="checkbox"
                            checked={config.trustServerCertificate}
                            onChange={e => updateConfig('trustServerCertificate', e.target.checked)}
                        />
                        Confiar en el certificado del servidor
                    </label>

                    {testResult && <div className="testResult">✓ {testResult}</div>}
                    {dialogError && <div className="errorBox" role="alert">{dialogError}</div>}

                </div>
                <div className="modalFoot">
                    <button
                        type="button"
                        className="btn btnGhost"
                        onClick={() => closeDialog(configDialogRef)}
                    >
                        Cancelar
                    </button>
                    <button
                        type="button"
                        className="btn btnGhost"
                        disabled={testing}
                        onClick={testConnection}
                    >
                        {testing ? 'Probando…' : 'Probar conexión'}
                    </button>
                    <button type="button" className="btn" onClick={saveConfig}>
                        Guardar
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

export default Instance;