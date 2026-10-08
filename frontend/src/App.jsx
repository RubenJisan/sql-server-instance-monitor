import { Outlet } from 'react-router';
import Navbar from '@/components/Navbar';
import './App.css';

function App() {
    return (
        <>
            <Navbar />
            <main className="appContent">
                <Outlet />
            </main>
        </>
    );
}

export default App;
