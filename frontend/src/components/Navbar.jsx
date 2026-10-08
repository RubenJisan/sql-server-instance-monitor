import { NavLink } from 'react-router';
import navItems from '@/components/NavItems.jsx';
import './Navbar.css';

function Navbar() {
    return (
        <nav className="navbar">
            {navItems.map(item => (
                <NavLink
                    key={item.path}
                    to={item.path}
                    className="navLink"
                    aria-label={item.label}
                >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        {item.icon}
                    </svg>
                    <span className="navLabel">{item.label}</span>
                </NavLink>
            ))}
        </nav>
    );
}

export default Navbar;
