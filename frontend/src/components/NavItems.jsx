import Instance from '@/pages/instance/Instance';
import Audit from "@/pages/audit/Audit.jsx";
import Backup from "@/pages/backup/Backup.jsx";
import Storage from "@/pages/storage/Storage.jsx";

const navItems = [
    {
        path: '/instance',
        label: 'Estado de la instancia',
        element: <Instance />,
        icon: (
            <>
                <rect x="3" y="3" width="18" height="7" rx="2" />
                <rect x="3" y="14" width="18" height="7" rx="2" />
                <path d="M7 6.5h.01M7 17.5h.01" />
            </>
        )
    },
    {
        path: '/performance',
        label: 'Rendimiento',
        element: <h1>Rendimiento</h1>,
        icon: <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
    },
    {
        path: '/storage',
        label: 'Almacenamiento',
        element: <Storage />,
        icon: (
            <>
                <ellipse cx="12" cy="5" rx="9" ry="3" />
                <path d="M3 5v14c0 1.7 4 3 9 3s9-1.3 9-3V5M3 12c0 1.7 4 3 9 3s9-1.3 9-3" />
            </>
        )
    },
    {
        path: '/backup',
        label: 'Respaldo y recuperación',
        element: <Backup />,
        icon: <path d="M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l4 2" />
    },
    {
        path: '/audit',
        label: 'Auditoría',
        element: <Audit />,
        icon: <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10zM9 12l2 2 4-4" />
    },
    {
        path: '/maintenance',
        label: 'Mantenimiento preventivo',
        element: <h1>Mantenimiento preventivo</h1>,
        icon: (
            <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.8-3.8a6 6 0 0 1-8 8l-6.9 6.9a2.1 2.1 0 0 1-3-3l6.9-6.9a6 6 0 0 1 8-8z" />
        )
    }
];

export default navItems;
