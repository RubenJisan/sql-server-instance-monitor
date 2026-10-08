import { Navigate, Route, Routes } from 'react-router';
import Navbar from '@/components/Navbar';
import navItems from '@/components/navItems';
import './App.css';

function App() {
    return (
        <>
            <Navbar />
            <main className="appContent">
                <Routes>
                    {navItems.map(item => (
                        <Route key={item.path} path={item.path} element={item.element} />
                    ))}
                    <Route path="*" element={<Navigate to={navItems[0].path} replace />} />
                </Routes>
            </main>
        </>
    );
}

export default App;
