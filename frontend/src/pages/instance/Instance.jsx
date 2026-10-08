import './Instance.css';
import { useEffect, useRef, useState } from 'react';

const API = 'http://localhost:8080/api';

const emptyOverview = {
    instance: { name: null, status: null, statusLabel: null, version: null, uptime: null, startedAt: null },
    server: { name: null, details: null },
    databases: [],
    memory: { maxServer: null, inUse: null, inUsePercent: null, bufferAllocated: null, bufferInUse: null, bufferPercent: null },
    connection: { host: null, instance: null, user: null },
    sessions: { total: null, allowed: null },
    lastCheck: { ago: null, refreshSeconds: null }
};

const emptyConfig = {
    server: '',
    instance: '',
    port: '',
    authentication: 'sqlServer',
    user: '',
    password: '',
    refreshInterval: '10',
    encrypt: true,
    trustCertificate: false
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

const badgeClasses = { online: 'badgeOk', readOnly: 'badgeWarn', offline: 'badgeErr' };
const ringClasses  = { online: 'ringOk',  readOnly: 'ringWarn',  offline: 'ringErr'  };

const badge = (status, live = false) =>
    `badge 
    ${badgeClasses[status] ?? ''} 
    ${live && status === 'online' ? 'live' : ''}
    `.trim();

const ring = (status) => ringClasses[status] ?? '';

const display = (value, suffix = '') => value == null ? '—' : `${value}${suffix}`;

const usage = (value, percent) => value == null ? '—' : `${value} · ${percent ?? 0}%`;

const barStyle = (percent) => ({ '--w': `${percent ?? 0}%` });

function Instance() {
    const [overview, setOverview] = useState(emptyOverview);
    const [instances, setInstances] = useState([]);
    const [selectedInstance, setSelectedInstance] = useState('');
    const [config, setConfig] = useState(emptyConfig);

    const [loading, setLoading] = useState(true);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState('');
    const [toast, setToast] = useState('');

    const instanceDialogRef = useRef(null);
    const configDialogRef = useRef(null);

    const { instance, server, databases, memory, connection, sessions, lastCheck } = overview;

    async function loadOverview() {
        setOverview(emptyOverview);
        setLoading(false);
    }

    async function loadInstances() {
        setInstances([]);
    }

    async function connectInstance() {
        closeDialog(instanceDialogRef);
        if (selectedInstance) setToast(`Conectado a ${selectedInstance}`);
    }

    async function testConnection() {
        setTesting(true);
        setTestResult('');
        setTesting(false);
    }

    async function saveConfig() {
        closeDialog(configDialogRef);
        setToast('Configuración guardada');
    }

    useEffect(() => {
        (async () => {
            await Promise.all([loadOverview(), loadInstances()]);
        })();
    }, []);

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
                        onClick={() => openDialog(instanceDialogRef)}
                    >
                        <span className={badge(instance.status, true)}>
                            {display(instance.name)}
                        </span>
                        {display(server.name)} ▾
                    </button>
                    <button
                        type="button"
                        className="btn btnGhost"
                        onClick={() => openDialog(configDialogRef)}
                    >
                        Configurar conexión
                    </button>
                </div>
            </header>

            <div className="grid cols3">
                <div className="card statusCard spanTwo">
                    <div className={`statusRing ${ring(instance.status)}`}>
                        <span className="statusDot">●</span>
                    </div>
                    <div>
                        <span className={badge(instance.status, true)}>
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
                                <th>Descripción</th>
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
                                    <td>{db.description}</td>
                                    <td>
                                            <span className={badge(db.status)}>
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
                    <div className="kpiValue">{display(lastCheck.ago)}</div>
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
                        <p>Servidores y instancias SQL Server detectados</p>
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
                    {instances.length === 0 && (
                        <p className="sub">Sin instancias detectadas.</p>
                    )}
                    {instances.map(item => (
                        <label key={item.id} className="option">
                            <input
                                type="radio"
                                name="instance"
                                checked={selectedInstance === item.name}
                                onChange={() => setSelectedInstance(item.name)}
                            />
                            <span>
                                <b>{item.name}</b>
                                <small>{item.details}</small>
                            </span>
                        </label>
                    ))}
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
                    <button type="button" className="btn" onClick={connectInstance}>
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
                    <div className="twoCols">
                        <div className="field">
                            <label htmlFor="configServer">Servidor</label>
                            <input
                                id="configServer"
                                type="text"
                                value={config.server}
                                onChange={e => updateConfig('server', e.target.value)}
                            />
                        </div>
                        <div className="field">
                            <label htmlFor="configInstance">Instancia</label>
                            <input
                                id="configInstance"
                                type="text"
                                value={config.instance}
                                onChange={e => updateConfig('instance', e.target.value)}
                            />
                        </div>
                    </div>

                    <div className="twoCols">
                        <div className="field">
                            <label htmlFor="configPort">Puerto</label>
                            <input
                                id="configPort"
                                type="text"
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
                            <label htmlFor="configUser">Usuario</label>
                            <input
                                id="configUser"
                                type="text"
                                value={config.user}
                                onChange={e => updateConfig('user', e.target.value)}
                            />
                        </div>
                        <div className="field">
                            <label htmlFor="configPassword">Contraseña</label>
                            <input
                                id="configPassword"
                                type="password"
                                value={config.password}
                                onChange={e => updateConfig('password', e.target.value)}
                            />
                        </div>
                    </div>

                    <div className="field">
                        <label htmlFor="configRefresh">Intervalo de refresco</label>
                        <select
                            id="configRefresh"
                            value={config.refreshInterval}
                            onChange={e => updateConfig('refreshInterval', e.target.value)}
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
                            checked={config.trustCertificate}
                            onChange={e => updateConfig('trustCertificate', e.target.checked)}
                        />
                        Confiar en el certificado del servidor
                    </label>

                    {testResult && <div className="testResult">✓ {testResult}</div>}

                    <div className="warnBox">
                        Las credenciales se leen desde variables de entorno o un archivo de
                        configuración fuera del repositorio.
                    </div>
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