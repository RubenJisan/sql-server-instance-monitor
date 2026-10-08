import { NavLink } from 'react-router';
import navItems from '@/components/NavItems.jsx';
import './Navbar.css';

function Navbar() {
    return (
        <nav className="navbar">
            {navItems.map(({path, label, icon}) => (
                <NavLink
                    key={path}
                    to={path}
                    className="navLink"
                    aria-label={label}
                >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        {icon}
                    </svg>
                    <span className="navLabel">{label}</span>
                </NavLink>
            ))}
        </nav>
    );
}

export default Navbar;
