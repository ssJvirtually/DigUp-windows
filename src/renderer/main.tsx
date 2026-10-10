import React, { useState, useEffect } from 'react';
import ReactDOM from 'react-dom/client';
import { SearchPanel } from './components/SearchPanel.js';
import { SearchWindow } from './components/SearchWindow.js';
import { SettingsWindow } from './components/SettingsWindow.js';
import { Onboarding } from './components/Onboarding.js';
import './styles.css';

const App: React.FC = () => {
  const [route, setRoute] = useState(window.location.hash || '#/panel');

  useEffect(() => {
    const handleHashChange = () => setRoute(window.location.hash || '#/panel');
    window.addEventListener('hashchange', handleHashChange);
    return () => window.removeEventListener('hashchange', handleHashChange);
  }, []);

  if (route.startsWith('#/window')) {
    return <SearchWindow />;
  }
  if (route.startsWith('#/settings')) {
    return <SettingsWindow />;
  }
  if (route.startsWith('#/onboarding')) {
    return <Onboarding />;
  }

  return <SearchPanel />;
};

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
