import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider, Navigate } from 'react-router';
import App from './App';
import './index.css';

const modules = [
    { path: 'instance', title: 'Estado de la instancia' },
    { path: 'performance', title: 'Rendimiento' },
    { path: 'storage', title: 'Almacenamiento' },
    { path: 'backup', title: 'Respaldo y recuperación' },
    { path: 'audit', title: 'Auditoría' },
    { path: 'maintenance', title: 'Mantenimiento preventivo' }
];

const router = createBrowserRouter([
    {
        path: '/',
        element: <App />,
        children: [
            { index: true, element: <Navigate to="/instance" replace /> },
            ...modules.map(module => ({
                path: module.path,
                element: <h1>{module.title}</h1>
            }))
        ]
    }
]);

createRoot(document.getElementById('root')).render(
    <StrictMode>
        <RouterProvider router={router} />
    </StrictMode>
);