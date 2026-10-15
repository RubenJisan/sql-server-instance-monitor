import './Performance.css';
import { useCallback, useEffect, useRef, useState } from 'react';

const API = 'http://localhost:8080/api';

const rangeOptions = [
    { value: '1h', label: '1 h', description: 'Última hora' },
    { value: '6h', label: '6 h', description: 'Últimas 6 horas' },
    { value: '24h', label: '24 h', description: 'Últimas 24 horas' }
];

const exportFormats = [
    { value: 'pdf', label: 'PDF' },
    { value: 'xlsx', label: 'Excel (.xlsx)' },
    { value: 'csv', label: 'CSV' }
];

const exportSections = [
    { value: 'queries', label: 'Top consultas SQL' },
    { value: 'sessions', label: 'Sesiones activas' },
    { value: 'blocking', label: 'Sesiones bloqueadas' },
    { value: 'chart', label: 'Gráfica de CPU y memoria' }
];

const sortOptions = [
    { value: 'totalMs', label: 'tiempo total' },
    { value: 'averageMs', label: 'tiempo promedio' },
    { value: 'executions', label: 'ejecuciones' },
    { value: 'cpuPercent', label: '% CPU' }
];

const emptyKpi = { value: null, detail: null, status: null };

const emptyOverview = {
    instance: { name: null, serverName: null },
    range: { code: '1h', label: 'Última hora' },
    kpis: {
        cpu: emptyKpi,
        activeSessions: emptyKpi,
        blockedSessions: emptyKpi,
        averageDuration: emptyKpi
    },
    chart: { points: [] },
    processes: [],
    queries: [],
    sessions: { active: null, connected: null, items: [] },
    blocking: { blockedCount: 0, rootCount: 0, chains: [] },
    lastCheck: { collectedAt: null, refreshSeconds: null },
    warnings: []
};

const emptyExport = {
    format: 'pdf',
    range: '1h',
    sections: ['queries', 'sessions', 'blocking']
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

    static async send(path, { method = 'GET', body } = {}) {
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

        if (!response.ok) {
            const data = ApiClient.parse(await response.text());
            throw new ApiError(data?.message ?? `Error ${response.status} del servidor.`, response.status);
        }
        return response;
    }

    static async request(path, options) {
        const response = await ApiClient.send(path, options);
        return ApiClient.parse(await response.text());
    }

    static async download(path) {
        const response = await ApiClient.send(path);
        const disposition = response.headers.get('Content-Disposition') ?? '';
        const fileName = disposition.match(/filename="?([^";]+)"?/)?.[1] ?? null;
        return { blob: await response.blob(), fileName };
    }
}

class PerformanceUtils {
    static badgeClasses = { online: 'badgeOk', warning: 'badgeWarn', offline: 'badgeErr' };
    static toneClasses = { online: 'textOk', warning: 'textWarn', offline: 'textErr' };
    static barClasses = { warning: 'barWarn', offline: 'barErr' };
    static chart = { width: 600, height: 172, left: 36, right: 590, top: 12, bottom: 146 };
    static gridLevels = [0, 25, 50, 75, 100];
    static sqlKeywords = /\b(SELECT|INSERT|UPDATE|DELETE|MERGE|FROM|JOIN|INNER|LEFT|RIGHT|OUTER|WHERE|SET|INTO|VALUES|GROUP BY|ORDER BY|HAVING|AND|OR|ON|AS|TOP|EXEC|BEGIN|COMMIT|ROLLBACK|TRAN|TRANSACTION|WITH)\b/i;

    static badge(status, live = false) {
        const tone = PerformanceUtils.badgeClasses[status] ?? '';
        const pulse = live && status === 'online' ? 'live' : '';
        return `badge ${tone} ${pulse}`.trim();
    }

    static tone(status) {
        return PerformanceUtils.toneClasses[status] ?? '';
    }

    static barClass(status) {
        return PerformanceUtils.barClasses[status] ?? '';
    }

    static display(value, suffix = '') {
        return value == null || value === '' ? '—' : `${value}${suffix}`;
    }

    static barStyle(percent) {
        return { '--w': `${Math.max(0, Math.min(100, percent ?? 0))}%` };
    }

    static formatCount(value) {
        return value == null ? '—' : value.toLocaleString('en-US');
    }

    static describeProfile(profile) {
        const address = profile.port ? `${profile.host}:${profile.port}` : profile.host;
        return [address, profile.instanceName, profile.username].filter(Boolean).join(' · ');
    }

    static rangeDescription(code) {
        return rangeOptions.find(option => option.value === code)?.description ?? '';
    }

    static sortQueries(queries, key) {
        return [...queries].sort((first, second) => (second[key] ?? 0) - (first[key] ?? 0));
    }

    static chartX(index, total) {
        const { left, right } = PerformanceUtils.chart;
        return total > 1 ? left + (index * (right - left)) / (total - 1) : (left + right) / 2;
    }

    static chartY(percent) {
        const { top, bottom } = PerformanceUtils.chart;
        return bottom - (Math.max(0, Math.min(100, percent)) / 100) * (bottom - top);
    }

    static segments(points, key) {
        return points
            .map((point, index) => point[key] == null
                ? null
                : [PerformanceUtils.chartX(index, points.length), PerformanceUtils.chartY(point[key])])
            .reduce((groups, coordinate) => {
                if (coordinate == null) return groups.at(-1)?.length ? [...groups, []] : groups;
                const current = groups.at(-1) ?? [];
                return [...groups.slice(0, -1), [...current, coordinate]];
            }, [[]])
            .filter(group => group.length > 0);
    }

    static linePoints(segment) {
        return segment.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
    }

    static areaPoints(segment) {
        const { bottom } = PerformanceUtils.chart;
        const first = segment[0][0].toFixed(1);
        const last = segment.at(-1)[0].toFixed(1);
        return `${first},${bottom} ${PerformanceUtils.linePoints(segment)} ${last},${bottom}`;
    }

    static axisLabels(points) {
        if (points.length === 0) return [];
        const indexes = [...new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])];
        return indexes.map(index => ({
            key: index,
            x: PerformanceUtils.chartX(index, points.length),
            label: points[index].time,
            anchor: index === 0 ? 'start' : index === points.length - 1 ? 'end' : 'middle'
        }));
    }

    static exportQuery(options) {
        const params = new URLSearchParams({
            format: options.format,
            range: options.range,
            sections: options.sections.join(',')
        });
        return `?${params.toString()}`;
    }

    static exportFileName(instanceName, format) {
        const pad = value => String(value).padStart(2, '0');
        const now = new Date();
        const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}`;
        const instance = (instanceName ?? 'instancia').replace(/[^A-Za-z0-9_-]/g, '_');
        return `Reporte_Rendimiento_${instance}_${stamp}.${format}`;
    }

    static saveFile(blob, fileName) {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = fileName;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
    }
}

function SqlText({ sql }) {
    if (!sql) return '—';
    return sql.split(PerformanceUtils.sqlKeywords).map((part, index) => (
        index % 2 === 1 ? <span key={index} className="sqlKeyword">{part}</span> : part
    ));
}

function MetricChart({ points, loading }) {
    const { width, height, left, right } = PerformanceUtils.chart;
    const cpuSegments = PerformanceUtils.segments(points, 'cpuPercent');
    const memorySegments = PerformanceUtils.segments(points, 'memoryPercent');
    const hasData = cpuSegments.length > 0 || memorySegments.length > 0;

    const series = [
        { key: 'memory', className: 'memorySeries', segments: memorySegments },
        { key: 'cpu', className: 'cpuSeries', segments: cpuSegments }
    ];

    return (
        <div className="chartWrap">
            <svg
                className="chart"
                viewBox={`0 0 ${width} ${height}`}
                role="img"
                aria-label="Consumo de CPU y memoria en el rango seleccionado"
            >
                {PerformanceUtils.gridLevels.map(level => (
                    <g key={level}>
                        <line
                            className="gridLine"
                            x1={left}
                            x2={right}
                            y1={PerformanceUtils.chartY(level)}
                            y2={PerformanceUtils.chartY(level)}
                        />
                        <text x={left - 8} y={PerformanceUtils.chartY(level) + 4} textAnchor="end">
                            {level}%
                        </text>
                    </g>
                ))}
                {series.map(item => (
                    <g key={item.key} className={item.className}>
                        {item.segments.map(segment => (
                            segment.length === 1 ? (
                                <circle key={segment[0][0]} cx={segment[0][0]} cy={segment[0][1]} r="3.5" />
                            ) : (
                                <g key={segment[0][0]}>
                                    <polygon className="area" points={PerformanceUtils.areaPoints(segment)} />
                                    <polyline className="line" points={PerformanceUtils.linePoints(segment)} />
                                </g>
                            )
                        ))}
                    </g>
                ))}
                {PerformanceUtils.axisLabels(points).map(label => (
                    <text key={label.key} x={label.x} y={height - 2} textAnchor={label.anchor}>
                        {label.label}
                    </text>
                ))}
            </svg>
            {!hasData && (
                <div className="chartEmpty">{loading ? 'Cargando...' : 'Sin datos en el rango seleccionado'}</div>
            )}
        </div>
    );
}

function Performance() {
    const [overview, setOverview] = useState(emptyOverview);
    const [range, setRange] = useState(emptyOverview.range.code);
    const [sortKey, setSortKey] = useState(sortOptions[0].value);
    const [chainIndex, setChainIndex] = useState(0);
    const [exportOptions, setExportOptions] = useState(emptyExport);
    const [profiles, setProfiles] = useState([]);
    const [activeId, setActiveId] = useState(null);
    const [selectedId, setSelectedId] = useState(null);

    const [loading, setLoading] = useState(true);
    const [exporting, setExporting] = useState(false);
    const [killing, setKilling] = useState(false);
    const [pageMessage, setPageMessage] = useState(null);
    const [dialogError, setDialogError] = useState('');
    const [toast, setToast] = useState('');

    const instanceDialogRef = useRef(null);
    const exportDialogRef = useRef(null);
    const blockingDialogRef = useRef(null);
    const inFlightRef = useRef(false);

    const { instance, kpis, chart, processes, queries, sessions, blocking, lastCheck, warnings } = overview;
    const { display, barStyle, tone } = PerformanceUtils;
    const sortedQueries = PerformanceUtils.sortQueries(queries, sortKey);
    const primaryChain = blocking.chains[0] ?? null;
    const selectedChain = blocking.chains[Math.min(chainIndex, blocking.chains.length - 1)] ?? null;
    const refreshSeconds = lastCheck.refreshSeconds;

    const loadOverview = useCallback(async (selectedRange) => {
        inFlightRef.current = true;
        try {
            const data = await ApiClient.request(`/performance/overview?range=${encodeURIComponent(selectedRange)}`);
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

    async function changeRange(value) {
        if (value === range) return;
        setRange(value);
        await loadOverview(value);
    }

    async function connectProfile() {
        if (!selectedId) return;
        setDialogError('');
        try {
            const profile = await ApiClient.request(`/connections/${selectedId}/activate`, { method: 'POST' });
            await Promise.all([loadOverview(range), loadProfiles()]);
            closeDialog(instanceDialogRef);
            setToast(`Conectado a ${profile.name}`);
        } catch (e) {
            setDialogError(e.message);
        }
    }

    async function runExport() {
        setExporting(true);
        setDialogError('');
        try {
            const { blob, fileName } = await ApiClient.download(
                `/performance/export${PerformanceUtils.exportQuery(exportOptions)}`
            );
            const name = fileName ?? PerformanceUtils.exportFileName(instance.name, exportOptions.format);
            PerformanceUtils.saveFile(blob, name);
            closeDialog(exportDialogRef);
            setToast(`${name} generado`);
        } catch (e) {
            setDialogError(e.message);
        } finally {
            setExporting(false);
        }
    }

    async function killBlocker() {
        const sessionId = selectedChain?.blocker.sessionId;
        if (sessionId == null) return;
        setKilling(true);
        setDialogError('');
        try {
            const result = await ApiClient.request(`/performance/sessions/${sessionId}/kill`, { method: 'POST' });
            closeDialog(blockingDialogRef);
            setToast(result.message);
            await loadOverview(range);
        } catch (e) {
            setDialogError(e.message);
        } finally {
            setKilling(false);
        }
    }

    useEffect(() => {
        (async () => {
            await Promise.all([loadOverview(emptyOverview.range.code), loadProfiles()]);
        })();
    }, [loadOverview, loadProfiles]);

    useEffect(() => {
        if (!refreshSeconds) return;
        const timer = setInterval(() => {
            if (!document.hidden && !inFlightRef.current) loadOverview(range);
        }, refreshSeconds * 1000);
        return () => clearInterval(timer);
    }, [refreshSeconds, range, loadOverview]);

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

    function openExportDialog() {
        setExportOptions(previous => ({ ...previous, range }));
        setDialogError('');
        openDialog(exportDialogRef);
    }

    function openBlockingDialog() {
        setChainIndex(0);
        setDialogError('');
        openDialog(blockingDialogRef);
    }

    function updateExport(field, value) {
        setExportOptions(previous => ({ ...previous, [field]: value }));
    }

    function toggleSection(section, checked) {
        setExportOptions(previous => ({
            ...previous,
            sections: checked
                ? exportSections.map(option => option.value)
                    .filter(value => value === section || previous.sections.includes(value))
                : previous.sections.filter(value => value !== section)
        }));
    }

    return (
        <div className="performancePage">
            <header className="head">
                <div>
                    <h1>Monitoreo de rendimiento</h1>
                    <p className="sub">Consultas costosas, sesiones y consumo de recursos en tiempo real.</p>
                </div>
                <div className="controls">
                    <div className="tabs" role="tablist" aria-label="Rango de tiempo">
                        {rangeOptions.map(option => (
                            <button
                                key={option.value}
                                type="button"
                                role="tab"
                                aria-selected={range === option.value}
                                className={range === option.value ? 'on' : ''}
                                onClick={() => changeRange(option.value)}
                            >
                                {option.label}
                            </button>
                        ))}
                    </div>
                    <button type="button" className="btn btnGhost" onClick={openExportDialog}>
                        Exportar
                    </button>
                    <button type="button" className="selector" onClick={openInstanceDialog}>
                        <span className={PerformanceUtils.badge(instance.name ? 'online' : null, true)}>
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
                    <div className="kpiLabel">CPU de la instancia</div>
                    <div className={`kpiValue ${tone(kpis.cpu.status)}`}>{display(kpis.cpu.value)}</div>
                    <div className="kpiDetail">{display(kpis.cpu.detail)}</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Sesiones activas</div>
                    <div className={`kpiValue ${tone(kpis.activeSessions.status)}`}>
                        {display(kpis.activeSessions.value)}
                    </div>
                    <div className="kpiDetail">{display(kpis.activeSessions.detail)}</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Sesiones bloqueadas</div>
                    <div className={`kpiValue ${tone(kpis.blockedSessions.status)}`}>
                        {display(kpis.blockedSessions.value)}
                    </div>
                    <div className="kpiDetail">{display(kpis.blockedSessions.detail)}</div>
                </div>

                <div className="card kpi">
                    <div className="kpiLabel">Tiempo prom. ejecución</div>
                    <div className={`kpiValue ${tone(kpis.averageDuration.status)}`}>
                        {display(kpis.averageDuration.value)}
                    </div>
                    <div className="kpiDetail">{display(kpis.averageDuration.detail)}</div>
                </div>
            </div>

            <div className="grid cols21">
                <div className="card">
                    <h2>
                        CPU y memoria
                        <small className="legend">
                            <span className="legendCpu">● CPU</span>
                            <span className="legendMemory">● Memoria</span>
                        </small>
                    </h2>
                    <MetricChart points={chart.points} loading={loading} />
                </div>

                <div className="card">
                    <h2>
                        Top procesos
                        <small>CPU / MEM</small>
                    </h2>
                    {processes.length === 0 && (
                        <p className="emptyCell">{loading ? 'Cargando...' : 'Sin procesos con actividad en el rango'}</p>
                    )}
                    {processes.map(process => (
                        <div key={process.sessionId} className="processItem">
                            <div className="row">
                                <span className="mono processName">{process.name}</span>
                                <b>{process.cpu}</b>
                            </div>
                            <div className={`bar ${PerformanceUtils.barClass(process.status)}`}>
                                <i style={barStyle(process.cpuPercent)}></i>
                            </div>
                            <small className="processDetail">{process.detail}</small>
                        </div>
                    ))}
                </div>
            </div>

            <div className="card stacked">
                <h2>
                    Consultas SQL con mayor consumo
                    <small>
                        <label className="sortLabel">
                            ordenado por
                            <select
                                className="sortSelect"
                                value={sortKey}
                                onChange={e => setSortKey(e.target.value)}
                            >
                                {sortOptions.map(option => (
                                    <option key={option.value} value={option.value}>{option.label}</option>
                                ))}
                            </select>
                        </label>
                    </small>
                </h2>
                <div className="tableWrap">
                    <table>
                        <thead>
                        <tr>
                            <th>query_hash</th>
                            <th>Sentencia</th>
                            <th className="alignRight">Ejecuciones</th>
                            <th className="alignRight">Prom.</th>
                            <th className="alignRight">Total</th>
                            <th>% CPU</th>
                        </tr>
                        </thead>
                        <tbody>
                        {loading && (
                            <tr>
                                <td colSpan={6} className="emptyCell">Cargando...</td>
                            </tr>
                        )}
                        {!loading && sortedQueries.length === 0 && (
                            <tr>
                                <td colSpan={6} className="emptyCell">Sin consultas ejecutadas en el rango</td>
                            </tr>
                        )}
                        {sortedQueries.map(query => (
                            <tr key={query.hash}>
                                <td className="mono">{query.hash}</td>
                                <td className="sql" title={query.fullStatement}>{query.statement}</td>
                                <td className="alignRight">{PerformanceUtils.formatCount(query.executions)}</td>
                                <td className="alignRight">
                                    <span className={PerformanceUtils.badge(query.averageStatus)}>{query.average}</span>
                                </td>
                                <td className="alignRight">{query.total}</td>
                                <td className="cpuCell" title={`${query.cpuPercent}% del CPU del rango`}>
                                    <div className={`bar ${PerformanceUtils.barClass(query.cpuStatus)}`}>
                                        <i style={barStyle(query.cpuPercent)}></i>
                                    </div>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            </div>

            <div className="grid cols2">
                <div className="card">
                    <h2>
                        Sesiones activas
                        <small>{display(sessions.active, ' en total')}</small>
                    </h2>
                    <div className="tableWrap">
                        <table>
                            <thead>
                            <tr>
                                <th>Programa</th>
                                <th>Usuario</th>
                                <th>Operación</th>
                                <th className="alignRight">Duración</th>
                            </tr>
                            </thead>
                            <tbody>
                            {loading && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Cargando...</td>
                                </tr>
                            )}
                            {!loading && sessions.items.length === 0 && (
                                <tr>
                                    <td colSpan={4} className="emptyCell">Sin sesiones ejecutando consultas</td>
                                </tr>
                            )}
                            {sessions.items.map((session, index) => (
                                <tr key={`${session.sessionId}-${index}`}>
                                    <td className="mono">
                                        {session.program}
                                        <small className="cellDetail">
                                            SPID {session.sessionId}{session.database ? ` · ${session.database}` : ''}
                                        </small>
                                    </td>
                                    <td>{display(session.login)}</td>
                                    <td>{display(session.operation)}</td>
                                    <td className="alignRight">
                                        <span className={PerformanceUtils.badge(session.status)}>{session.duration}</span>
                                    </td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </div>

                <div className="card">
                    <h2>
                        Sesiones bloqueadas
                        {blocking.blockedCount > 0 ? (
                            <span className="badge badgeErr">{blocking.blockedCount} detectadas</span>
                        ) : (
                            <span className="badge badgeOk">{loading ? 'Cargando' : 'Sin bloqueos'}</span>
                        )}
                    </h2>
                    {!primaryChain && (
                        <p className="emptyCell">
                            {loading ? 'Cargando...' : 'No hay sesiones esperando por bloqueos en este momento.'}
                        </p>
                    )}
                    {primaryChain && (
                        <>
                            <div className="row">
                                <span>Bloqueador</span>
                                <b className="mono">
                                    SPID {primaryChain.blocker.sessionId} · {display(primaryChain.blocker.login)}
                                </b>
                            </div>
                            {primaryChain.waiters.map((waiter, index) => (
                                <div key={waiter.sessionId} className="row">
                                    <span>Bloqueada {index + 1}</span>
                                    <span className="mono">SPID {waiter.sessionId} · espera {waiter.waitTime}</span>
                                </div>
                            ))}
                            <div className="row">
                                <span>Objeto</span>
                                <span className="mono">{display(primaryChain.object ?? primaryChain.resource)}</span>
                            </div>
                            {blocking.rootCount > 1 && (
                                <p className="sub extraChains">
                                    +{blocking.rootCount - 1} cadena(s) de bloqueo adicional(es)
                                </p>
                            )}
                            <button type="button" className="btn detailBtn" onClick={openBlockingDialog}>
                                Ver detalle del bloqueo
                            </button>
                        </>
                    )}
                </div>
            </div>

            <dialog
                ref={instanceDialogRef}
                className="modal"
                aria-labelledby="performanceInstanceDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="performanceInstanceDialogTitle">Seleccionar instancia</h3>
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
                                name="performanceInstance"
                                checked={selectedId === item.id}
                                onChange={() => setSelectedId(item.id)}
                            />
                            <span>
                                <b>{item.name}{item.id === activeId ? ' (activa)' : ''}</b>
                                <small>{PerformanceUtils.describeProfile(item)}</small>
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

            <dialog
                ref={exportDialogRef}
                className="modal"
                aria-labelledby="performanceExportDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="performanceExportDialogTitle">Exportar reporte</h3>
                        <p>Rendimiento · {PerformanceUtils.rangeDescription(exportOptions.range).toLowerCase()}</p>
                    </div>
                    <button
                        type="button"
                        className="closeBtn"
                        aria-label="Cerrar"
                        onClick={() => closeDialog(exportDialogRef)}
                    >
                        ✕
                    </button>
                </div>
                <div className="modalBody">
                    <div className="field">
                        <label htmlFor="performanceExportFormat">Formato</label>
                        <select
                            id="performanceExportFormat"
                            value={exportOptions.format}
                            onChange={e => updateExport('format', e.target.value)}
                        >
                            {exportFormats.map(option => (
                                <option key={option.value} value={option.value}>{option.label}</option>
                            ))}
                        </select>
                    </div>
                    <div className="field">
                        <span className="fieldLabel">Contenido</span>
                        {exportSections.map(option => (
                            <label key={option.value} className="check">
                                <input
                                    type="checkbox"
                                    checked={exportOptions.sections.includes(option.value)}
                                    onChange={e => toggleSection(option.value, e.target.checked)}
                                />
                                {option.label}
                            </label>
                        ))}
                    </div>
                    <div className="field">
                        <label htmlFor="performanceExportRange">Rango</label>
                        <select
                            id="performanceExportRange"
                            value={exportOptions.range}
                            onChange={e => updateExport('range', e.target.value)}
                        >
                            {rangeOptions.map(option => (
                                <option key={option.value} value={option.value}>{option.description}</option>
                            ))}
                        </select>
                    </div>
                    {dialogError && <div className="errorBox" role="alert">{dialogError}</div>}
                </div>
                <div className="modalFoot">
                    <button
                        type="button"
                        className="btn btnGhost"
                        onClick={() => closeDialog(exportDialogRef)}
                    >
                        Cancelar
                    </button>
                    <button
                        type="button"
                        className="btn"
                        disabled={exporting || exportOptions.sections.length === 0}
                        onClick={runExport}
                    >
                        {exporting ? 'Exportando...' : 'Exportar'}
                    </button>
                </div>
            </dialog>

            <dialog
                ref={blockingDialogRef}
                className="modal wide"
                aria-labelledby="performanceBlockingDialogTitle"
                onClick={closeOnBackdrop}
            >
                <div className="modalHead">
                    <div>
                        <h3 id="performanceBlockingDialogTitle">Detalle del bloqueo</h3>
                        <p>
                            {selectedChain?.object
                                ? `Cadena de bloqueo detectada en ${selectedChain.object.split('.')[0]}`
                                : 'Cadena de bloqueo detectada'}
                        </p>
                    </div>
                    <button
                        type="button"
                        className="closeBtn"
                        aria-label="Cerrar"
                        onClick={() => closeDialog(blockingDialogRef)}
                    >
                        ✕
                    </button>
                </div>
                <div className="modalBody">
                    {blocking.chains.length > 1 && (
                        <div className="tabs chainTabs" role="tablist" aria-label="Cadenas de bloqueo">
                            {blocking.chains.map((chain, index) => (
                                <button
                                    key={chain.blocker.sessionId}
                                    type="button"
                                    role="tab"
                                    aria-selected={chain === selectedChain}
                                    className={chain === selectedChain ? 'on' : ''}
                                    onClick={() => setChainIndex(index)}
                                >
                                    SPID {chain.blocker.sessionId}
                                </button>
                            ))}
                        </div>
                    )}
                    {!selectedChain && <p className="emptyCell">El bloqueo ya no está activo.</p>}
                    {selectedChain && (
                        <>
                            <div className="row">
                                <span>Bloqueador raíz</span>
                                <b className="mono">
                                    SPID {selectedChain.blocker.sessionId} · {display(selectedChain.blocker.login)}
                                </b>
                            </div>
                            <div className="row">
                                <span>Programa</span>
                                <span className="mono">
                                    {[selectedChain.blocker.program, selectedChain.blocker.host, selectedChain.blocker.status]
                                        .filter(Boolean)
                                        .join(' · ') || '—'}
                                </span>
                            </div>
                            <div className="row">
                                <span>Tipo de espera</span>
                                <span className="mono">{display(selectedChain.waitType)}</span>
                            </div>
                            <div className="row">
                                <span>Recurso</span>
                                <span className="mono">{display(selectedChain.resource)}</span>
                            </div>
                            <div className="row">
                                <span>Sesiones afectadas</span>
                                <b>{selectedChain.waiters.length} · espera máx. {selectedChain.maxWait}</b>
                            </div>
                            <div className="row">
                                <span>Transacción abierta</span>
                                <b className={tone(selectedChain.transactionStatus)}>{selectedChain.transactionAge}</b>
                            </div>
                            <div className="sub logTitle">Última sentencia del bloqueador</div>
                            <div className="log">
                                <SqlText sql={selectedChain.lastStatement} />
                            </div>
                        </>
                    )}
                    {dialogError && <div className="errorBox" role="alert">{dialogError}</div>}
                </div>
                <div className="modalFoot">
                    <button
                        type="button"
                        className="btn btnGhost"
                        onClick={() => closeDialog(blockingDialogRef)}
                    >
                        Cerrar
                    </button>
                    {selectedChain && (
                        <button
                            type="button"
                            className="btn btnDanger"
                            disabled={killing}
                            onClick={killBlocker}
                        >
                            {killing ? 'Terminando...' : `Terminar sesión (KILL ${selectedChain.blocker.sessionId})`}
                        </button>
                    )}
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

export default Performance;