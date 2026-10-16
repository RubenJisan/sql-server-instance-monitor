import './Backup.css';
import { useCallback, useEffect, useRef, useState } from 'react';

const API = 'http://localhost:8080/api';

const emptyBackup = {
    metrics: { lastSuccessAt: null, lastType: null, lastDurationMin: null },
    history: [],
    days: [],
    assessment: null,
    policy: null,
    scan: { databases: [], warnings: [] }
};

const backupTypes = {
    FULL: { label: 'Completo (FULL)',            name: 'Completo',             command: 'BACKUP DATABASE · FULL' },
    DIFF: { label: 'Diferencial (DIFF)',         name: 'Diferencial',          command: 'BACKUP DATABASE · DIFFERENTIAL' },
    LOG:  { label: 'Log de transacciones (LOG)', name: 'Log de transacciones', command: 'BACKUP LOG' }
};

const statusLabels = { online: 'Exitoso', warning: 'Con advertencias', offline: 'Fallido', none: 'Sin respaldo' };
const badgeClasses = { online: 'badgeOk', warning: 'badgeWarn', offline: 'badgeErr' };
const toneClasses  = { online: 'textOk',  warning: 'textWarn',  offline: 'textErr' };
const dayClasses   = { online: 'dayOk',   warning: 'dayWarn',   offline: 'dayErr' };
const riskClasses  = { online: 'riskOk',  warning: 'riskWarn',  offline: 'riskErr' };
const riskIcons    = { online: '✓',       warning: '⚠',         offline: '⛔' };

const badge = (status, live = false) => {
    const tone  = badgeClasses[status] ?? '';
    const pulse = live && status === 'online' ? 'live' : '';
    return `badge ${tone} ${pulse}`.trim();
};

const display = (value, suffix = '') =>
    value == null ? '—' : `${value}${suffix}`;

const pad = (n) => String(n).padStart(2, '0');

// 'YYYY-MM-DD' se interpreta como fecha local (no UTC) para evitar desfase de un día
const parseDate = (value) =>
    new Date(value.length === 10 ? `${value}T00:00:00` : value);

const formatDate = (value) => {
    const d = parseDate(value);
    return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
};

const formatDateTime = (value, withYear = true) => {
    const d = parseDate(value);
    const day = `${pad(d.getDate())}/${pad(d.getMonth() + 1)}${withYear ? `/${d.getFullYear()}` : ''}`;
    return `${day} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

const formatDuration = (min) => (min == null ? '—' : `${min} min`);

const hoursSince = (value) => (Date.now() - parseDate(value).getTime()) / 36e5;

const timeAgo = (value) => {
    const total = Math.max(0, Math.floor(hoursSince(value)));
    const days  = Math.floor(total / 24);
    const hours = total % 24;
    if (days === 0) return `hace ${hours} h`;
    return `hace ${days} ${days === 1 ? 'día' : 'días'} ${hours} h`;
};

// Antigüedad del último respaldo exitoso → semáforo
const ageStatus = (hours) => (hours <= 26 ? 'online' : hours <= 72 ? 'warning' : 'offline');

// Tasa de éxito (%) → semáforo
const rateStatus = (rate) => {
    if (rate == null) return null;
    return rate >= 95 ? 'online' : rate >= 80 ? 'warning' : 'offline';
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

function Backup() {
    const [backup, setBackup] = useState(emptyBackup);
    const [profiles, setProfiles] = useState([]);
    const [activeId, setActiveId] = useState(null);
    const [selectedId, setSelectedId] = useState(null);

    const [loading, setLoading] = useState(true);
    const [runningFull, setRunningFull] = useState(false);
    const [pageMessage, setPageMessage] = useState(null);
    const [dialogError, setDialogError] = useState('');
    const [toast, setToast] = useState('');

    const instanceDialogRef = useRef(null);
    const policyDialogRef = useRef(null);

    const { metrics, history, days, assessment, policy } = backup;
    const activeProfile = profiles.find(p => p.id === activeId) ?? null;

    /* ----------------------- Datos derivados ----------------------- */

    const okDays   = days.filter(d => d.status === 'online').length;
    const warnDays = days.filter(d => d.status === 'warning').length;
    const errDays  = days.filter(d => d.status === 'offline').length;
    const successRate = days.length ? (okDays / days.length) * 100 : null;

    const lastHours  = metrics.lastSuccessAt ? hoursSince(metrics.lastSuccessAt) : null;
    const lastStatus = lastHours == null ? 'offline' : ageStatus(lastHours);
    const lastType   = backupTypes[metrics.lastType];
    const rateTone   = toneClasses[rateStatus(successRate)] ?? '';

    /* ----------------------- Carga de datos ----------------------- */

    const loadBackup = useCallback(async () => {
        try {
            const data = await ApiClient.request('/backups/overview');
            setBackup(data);
            setPageMessage(null);
        } catch (e) {
            const noActiveConnection = e.status === 409;
            if (noActiveConnection) setBackup(emptyBackup);
            setPageMessage({
                type: noActiveConnection ? 'warn' : 'error',
                text: e.message
            });
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

    async function connectProfile() {
        if (!selectedId) return;
        setDialogError('');
        try {
            await ApiClient.request(`/connections/${selectedId}/activate`, { method: 'POST' });
            setActiveId(selectedId);
            await Promise.all([loadBackup(), loadProfiles()]);
            closeDialog(instanceDialogRef);
            const p = profiles.find(x => x.id === selectedId);
            if (p) setToast(`Conectado a ${p.name}`);
        } catch (e) {
            setDialogError(e.message);
        }
    }

    async function runFullBackup() {
        closeDialog(policyDialogRef);
        setRunningFull(true);
        try {
            const result = await ApiClient.request('/backups/full', { method: 'POST' });
            const failed = result.results.filter(r => !r.ok);
            await loadBackup();   // recarga primero: loadBackup limpia pageMessage
            if (failed.length === 0) {
                setToast(`Respaldo FULL completado (${result.results.length} base(s))`);
            } else {
                setPageMessage({
                    type: 'error',
                    text: failed.map(f => `${f.database}: ${f.message}`).join(' | ')
                });
            }
        } catch (e) {
            setToast(`No se pudo iniciar el respaldo FULL: ${e.message}`);
        } finally {
            setRunningFull(false);
        }
    }

    /* ----------------------- Efectos ----------------------- */

    useEffect(() => {
        (async () => {
            await Promise.all([loadBackup(), loadProfiles()]);
        })();
    }, [loadBackup, loadProfiles]);

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
        <div className="backupPage">
            <header className="head">
                <div>
                    <h1>Respaldo y recuperación</h1>
                    <p className="sub">
                        Estado de las copias de seguridad de la instancia {display(activeProfile?.name)} (msdb.dbo.backupset).
                    </p>
                </div>
                <div className="controls">
                    <button type="button" className="selector" onClick={openInstanceDialog}>
                        <span className={badge(activeProfile?.status, true)}>
                            {display(activeProfile?.name)}
                        </span>
                        {display(activeProfile?.serverName)} ▾
                    </button>
                    <button
                        type="button"
                        className="btn"
                        disabled={!policy}
                        onClick={() => openDialog(policyDialogRef)}
                    >
                        Ver política de retención
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
            {backup.scan?.warnings?.length > 0 && (
                <div className="warnBox pageMessage" role="status">
                    {backup.scan.warnings.join(' ')}
                </div>
            )}
            {assessment && (
                <div
                    className={`riskBanner ${riskClasses[assessment.level] ?? 'riskWarn'}`}
                    role="status"
                >
                    <span className="riskIcon" aria-hidden="true">
                        {riskIcons[assessment.level] ?? '⚠'}
                    </span>
                    <div>
                        <strong>{assessment.title}</strong>
                        <span>{assessment.detail}</span>
                    </div>
                </div>
            )}

            {/* ----- KPIs ----- */}
            <div className="grid cols4">
                <div className="card kpi">
                    <div className="kpiLabel">Último respaldo exitoso</div>
                    <div className={`kpiValue ${toneClasses[lastStatus]}`}>
                        {metrics.lastSuccessAt ? formatDateTime(metrics.lastSuccessAt, false) : '—'}
                    </div>
                    <div className="kpiDetail">
                        {metrics.lastSuccessAt ? timeAgo(metrics.lastSuccessAt) : 'Sin respaldos exitosos registrados'}
                    </div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Tipo</div>
                    <div className="kpiValue">{display(lastType?.name)}</div>
                    <div className="kpiDetail">{display(lastType?.command)}</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Estado</div>
                    <div className="kpiValue textOk">
                        {metrics.lastSuccessAt ? statusLabels.online : '—'}
                    </div>
                    <div className="kpiDetail">
                        Duración {formatDuration(metrics.lastDurationMin)}
                    </div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Tasa de éxito ({days.length} d)</div>
                    <div className={`kpiValue ${rateTone}`}>
                        {successRate == null ? '—' : `${successRate.toFixed(1)}%`}
                    </div>
                    <div className="kpiDetail">
                        {display(days.length ? okDays : null)} de {display(days.length || null)} correctos
                    </div>
                </div>
            </div>

            <div className="grid split">
                {/* ----- Historial ----- */}
                <div className="card">
                    <h2>
                        Historial de respaldos
                        <small>últimos {history.length}</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Fecha</th>
                                <th>Base de datos</th>
                                <th>Tipo</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={6} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && history.length === 0 && (
                                <tr>
                                    <td colSpan={6} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {history.map(h => (
                                <tr key={h.id}>
                                    <td className="mono">{formatDateTime(h.startedAt)}</td>
                                    <td className="alignRight">{display(h.size)}</td>
                                    <td className="alignRight">{formatDuration(h.durationMin)}</td>
                                    <td>
                                        <span className={badge(h.status)}>
                                            {statusLabels[h.status] ?? display(h.status)}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>

                {/* ----- Últimos 14 días ----- */}
                <div className="card">
                    <h2>Últimos {days.length || 14} días</h2>
                    <div
                        className="daysStrip"
                        role="img"
                        aria-label={`${okDays} de ${days.length} días con respaldo exitoso`}
                    >
                        {days.map(d => (
                            <span
                                key={d.date}
                                className={`day ${dayClasses[d.status] ?? ''}`}
                                title={`${formatDate(d.date)} · ${statusLabels[d.status] ?? d.status}`}
                            />
                        ))}
                    </div>
                    <div className="legend">
                        <div className="legendRow">
                            <span className="textOk">● Exitoso</span>
                            <b>{display(days.length ? okDays : null)}</b>
                        </div>
                        <div className="legendRow">
                            <span className="textWarn">● Advertencia</span>
                            <b>{display(days.length ? warnDays : null)}</b>
                        </div>
                        <div className="legendRow">
                            <span className="textErr">● Fallido</span>
                            <b>{display(days.length ? errDays : null)}</b>
                        </div>
                    </div>
                </div>
            </div>

            {/* ----- Diálogo de política de retención ----- */}
            <dialog
                ref={policyDialogRef}
                className="modal"
                aria-labelledby="backupPolicyDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="backupPolicyDialogTitle">Política de retención</h3>
                        <p>Plan de mantenimiento de respaldos · {display(activeProfile?.name)}</p>
                    </div>
                    <button
                        type="button"
                        className="closeBtn"
                        aria-label="Cerrar"
                        onClick={() => closeDialog(policyDialogRef)}
                    >
                        ✕
                    </button>
                </div>
                <div className="modalBody">
                    <table>
                        <thead>
                        <tr>
                            <th>Tipo</th>
                            <th>Frecuencia</th>
                            <th className="alignRight">Retención</th>
                        </tr>
                        </thead>
                        <tbody>
                        {(policy?.rows ?? []).map(r => (
                            <tr key={r.type}>
                                <td>{backupTypes[r.type]?.label ?? display(r.type)}</td>
                                <td>{display(r.frequency)}</td>
                                <td className="alignRight">{display(r.retention)}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>

                    <div className="policyInfo">
                        <div className="legendRow">
                            <span>Modelo de recuperación</span>
                            <b>{display(policy?.recoveryModel)}</b>
                        </div>
                        <div className="legendRow">
                            <span>Destino</span>
                            <span className="mono">{display(policy?.destination)}</span>
                        </div>
                        <div className="legendRow">
                            <span>Verificación</span>
                            <span className="mono">{display(policy?.verification)}</span>
                        </div>
                    </div>

                    {assessment?.recommendation && (
                        <div className="warnBox">{assessment.recommendation}</div>
                    )}
                </div>
                <div className="modalFoot">
                    <button
                        type="button"
                        className="btn btnGhost"
                        onClick={() => closeDialog(policyDialogRef)}
                    >
                        Cerrar
                    </button>
                    <button
                        type="button"
                        className="btn"
                        disabled={runningFull}
                        onClick={runFullBackup}
                    >
                        Ejecutar FULL ahora
                    </button>
                </div>
            </dialog>

            {/* ----- Diálogo de selección de instancia ----- */}
            <dialog
                ref={instanceDialogRef}
                className="modal"
                aria-labelledby="backupInstanceDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="backupInstanceDialogTitle">Seleccionar instancia</h3>
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
                                name="backupInstance"
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
        </div>
    );
}

export default Backup;