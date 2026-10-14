import './Storage.css';
import { useCallback, useEffect, useRef, useState } from 'react';

const API = 'http://localhost:8080/api';

const emptyOverview = {
    instance: { name: null, serverName: null },
    database: null,
    databases: [],
    summary: {
        size: null,
        sizeTrend: null,
        allocated: null,
        free: null,
        freePercent: null,
        freeStatus: null,
        monthlyGrowth: null,
        runway: null,
        growthStatus: null
    },
    filegroups: [],
    files: [],
    objects: [],
    growth: { source: null, points: [] },
    warnings: []
};

class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

class ApiClient {
    static parse(text) {
        try {
            return text ? JSON.parse(text) : null;
        } catch {
            return null;
        }
    }

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

        const data = ApiClient.parse(await response.text());

        if (!response.ok) {
            throw new ApiError(data?.message ?? `Error ${response.status} del servidor.`, response.status);
        }
        return data;
    }
}

class StorageUtils {
    static badgeClasses = { online: 'badgeOk', warning: 'badgeWarn', offline: 'badgeErr' };
    static toneClasses = { online: 'textOk', warning: 'textWarn', offline: 'textErr' };
    static monthNames = ['Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun', 'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic'];
    static chart = { width: 600, baseline: 150, maxHeight: 125, barWidth: 52, step: 92 };

    static badge(status, live = false) {
        const tone = StorageUtils.badgeClasses[status] ?? '';
        const pulse = live && status === 'online' ? 'live' : '';
        return `badge ${tone} ${pulse}`.trim();
    }

    static tone(status) {
        return StorageUtils.toneClasses[status] ?? '';
    }

    static display(value, suffix = '') {
        return value == null ? '—' : `${value}${suffix}`;
    }

    static barStyle(percent) {
        return { '--w': `${percent ?? 0}%` };
    }

    static barClass(percent) {
        if (percent == null) return '';
        if (percent >= 90) return 'barErr';
        if (percent >= 75) return 'barWarn';
        return percent < 50 ? 'barOk' : '';
    }

    static monthLabel(month) {
        return StorageUtils.monthNames[Number(month.slice(5, 7)) - 1] ?? month;
    }

    static describeProfile(profile) {
        const address = profile.port ? `${profile.host}:${profile.port}` : profile.host;
        return [address, profile.instanceName, profile.username].filter(Boolean).join(' · ');
    }

    static chartBars(points) {
        const { width, baseline, maxHeight, barWidth, step } = StorageUtils.chart;
        const peak = Math.max(0, ...points.map(point => point.sizeMb));
        const startX = (width - ((points.length - 1) * step + barWidth)) / 2;
        return points.map((point, index) => {
            const height = peak > 0 ? Math.max(4, Math.round((point.sizeMb / peak) * maxHeight)) : 0;
            const x = startX + index * step;
            return {
                key: point.month,
                x,
                y: baseline - height,
                width: barWidth,
                height,
                center: x + barWidth / 2,
                label: StorageUtils.monthLabel(point.month),
                value: point.size
            };
        });
    }
}

function Storage() {
    const [overview, setOverview] = useState(emptyOverview);
    const [profiles, setProfiles] = useState([]);
    const [activeId, setActiveId] = useState(null);
    const [selectedId, setSelectedId] = useState(null);

    const [loading, setLoading] = useState(true);
    const [pageMessage, setPageMessage] = useState(null);
    const [dialogError, setDialogError] = useState('');
    const [toast, setToast] = useState('');

    const instanceDialogRef = useRef(null);

    const { instance, database, databases, summary, filegroups, files, objects, growth, warnings } = overview;
    const { display, barStyle } = StorageUtils;
    const bars = StorageUtils.chartBars(growth.points);

    const loadOverview = useCallback(async (selectedDatabase = null) => {
        setLoading(true);
        try {
            const query = selectedDatabase ? `?database=${encodeURIComponent(selectedDatabase)}` : '';
            const data = await ApiClient.request(`/storage/overview${query}`);
            setOverview(data);
            setPageMessage(null);
        } catch (e) {
            const noActiveConnection = e.status === 409;
            if (noActiveConnection) setOverview(emptyOverview);
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

    useEffect(() => {
        (async () => {
            await Promise.all([loadOverview(), loadProfiles()]);
        })();
    }, [loadOverview, loadProfiles]);

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

    return (
        <div className="storagePage">
            <header className="head">
                <div>
                    <h1>Gestión del almacenamiento</h1>
                    <p className="sub">Espacio físico, archivos y crecimiento de la base de datos.</p>
                </div>
                <div className="controls">
                    <select
                        className="databaseSelect"
                        aria-label="Base de datos"
                        value={database ?? ''}
                        disabled={databases.length === 0}
                        onChange={e => loadOverview(e.target.value)}
                    >
                        {databases.map(name => (
                            <option key={name} value={name}>{name}</option>
                        ))}
                    </select>
                    <button type="button" className="selector" onClick={openInstanceDialog}>
                        <span className={StorageUtils.badge(instance.name ? 'online' : null, true)}>
                            {display(instance.name)}
                        </span>
                        {display(instance.serverName)} ▾
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

            {warnings.map(warning => (
                <div key={warning} className="warnBox pageMessage" role="alert">{warning}</div>
            ))}

            <div className="grid cols4">
                <div className="card kpi">
                    <div className="kpiLabel">Tamaño de la base de datos</div>
                    <div className="kpiValue">{display(summary.size)}</div>
                    <div className="kpiDetail">{display(summary.sizeTrend)}</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Espacio asignado</div>
                    <div className="kpiValue">{display(summary.allocated)}</div>
                    <div className="kpiDetail">Archivos de datos + log</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Espacio libre</div>
                    <div className={`kpiValue ${StorageUtils.tone(summary.freeStatus)}`}>
                        {display(summary.free)}
                    </div>
                    <div className="kpiDetail">{display(summary.freePercent, '% disponible')}</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Crecimiento mensual</div>
                    <div className={`kpiValue ${StorageUtils.tone(summary.growthStatus)}`}>
                        {display(summary.monthlyGrowth)}
                    </div>
                    <div className="kpiDetail">{display(summary.runway)}</div>
                </div>
            </div>

            <div className="grid cols21">
                <div className="card">
                    <h2>
                        Filegroups
                        <small>utilizado vs. disponible</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Filegroup</th>
                                <th className="alignRight">Usado</th>
                                <th className="alignRight">Total</th>
                                <th>Ocupación</th>
                                <th className="alignRight">%</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={5} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && filegroups.length === 0 && (
                                <tr>
                                    <td colSpan={5} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {filegroups.map(group => (
                                <tr key={group.name}>
                                    <td className="mono">{group.name}</td>
                                    <td className="alignRight">{display(group.used)}</td>
                                    <td className="alignRight">{display(group.total)}</td>
                                    <td className="barCell">
                                        <div className={`bar ${StorageUtils.barClass(group.usedPercent)}`}>
                                            <i style={barStyle(group.usedPercent)}></i>
                                        </div>
                                    </td>
                                    <td className="alignRight"><b>{display(group.usedPercent, '%')}</b></td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>

                <div className="card">
                    <h2>
                        Crecimiento
                        <small>tamaño por mes</small>
                    </h2>
                    {bars.length === 0 ? (
                        <p className="emptyCell">
                            {loading ? 'Cargando...' : 'Sin historial de respaldos FULL para esta base de datos.'}
                        </p>
                    ) : (
                        <svg
                            className="chart"
                            viewBox="0 0 600 180"
                            role="img"
                            aria-label="Tamaño de la base de datos por mes"
                        >
                            <defs>
                                <linearGradient id="storageGrowthGradient" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="0" stopColor="#38bdf8" />
                                    <stop offset="1" stopColor="#a78bfa" stopOpacity="0.5" />
                                </linearGradient>
                            </defs>
                            {bars.map(bar => (
                                <g key={bar.key}>
                                    <rect
                                        x={bar.x}
                                        y={bar.y}
                                        width={bar.width}
                                        height={bar.height}
                                        rx="8"
                                        fill="url(#storageGrowthGradient)"
                                    />
                                    <text x={bar.center} y={bar.y - 6} textAnchor="middle" className="chartValue">
                                        {bar.value}
                                    </text>
                                    <text x={bar.center} y="168" textAnchor="middle">{bar.label}</text>
                                </g>
                            ))}
                        </svg>
                    )}
                    {growth.source && <p className="sub chartSource">Fuente: {growth.source}</p>}
                </div>
            </div>

            <div className="grid cols2">
                <div className="card">
                    <h2>Objetos de mayor tamaño</h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Objeto</th>
                                <th>Tipo</th>
                                <th>Base de datos</th>
                                <th className="alignRight">Tamaño</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && objects.length === 0 && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {objects.map(object => (
                                <tr key={`${object.parent ?? ''}|${object.name}|${object.type}`}>
                                    <td className="mono">
                                        {object.name}
                                        {object.parent && <small className="objectParent">en {object.parent}</small>}
                                    </td>
                                    <td>{object.type}</td>
                                    <td>{object.database}</td>
                                    <td className="alignRight"><b>{object.size}</b></td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>

                <div className="card">
                    <h2>Archivos de datos</h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Archivo</th>
                                <th className="alignRight">Tamaño</th>
                                <th>Auto-crec.</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={3} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && files.length === 0 && (
                                <tr>
                                    <td colSpan={3} className="emptyCell">Sin datos</td>
                                </tr>
                            )}
                            {files.map(file => (
                                <tr key={file.path}>
                                    <td className="mono">
                                        {file.path}
                                        <small className="objectParent">
                                            {file.name} · {file.kind}{file.fileGroup ? ` · ${file.fileGroup}` : ''}
                                        </small>
                                    </td>
                                    <td className="alignRight">{display(file.size)}</td>
                                    <td>
                                        <span className={`badge ${file.autoGrowth ? 'badgeOk' : ''}`.trim()}>
                                            {file.autoGrowth ? `ON · ${file.growth}` : 'OFF'}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <dialog
                ref={instanceDialogRef}
                className="modal"
                aria-labelledby="storageInstanceDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="storageInstanceDialogTitle">Seleccionar instancia</h3>
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
                            Sin conexiones guardadas. Configura una desde el módulo de Estado de la instancia.
                        </p>
                    )}
                    {profiles.map(item => (
                        <label key={item.id} className="option">
                            <input
                                type="radio"
                                name="storageInstance"
                                checked={selectedId === item.id}
                                onChange={() => setSelectedId(item.id)}
                            />
                            <span>
                                <b>{item.name}{item.id === activeId ? ' (activa)' : ''}</b>
                                <small>{StorageUtils.describeProfile(item)}</small>
                            </span>
                        </label>
                    ))}
                    {dialogError && <div className="errorBox" role="alert">{dialogError}</div>}
                    <p className="sub">El cambio de instancia no requiere modificar el código fuente.</p>
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

export default Storage;
