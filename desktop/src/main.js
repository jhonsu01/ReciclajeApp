'use strict';
const path = require('path');
const { app, BrowserWindow, Menu, shell, dialog } = require('electron');
const { crearServidor } = require('./server');

const PUERTO = Number(process.env.RECICLAJE_PUERTO) || 3000;
let servidor = null;

async function iniciar() {
  const dbPath = path.join(app.getPath('userData'), 'reciclaje.db');
  try {
    servidor = await crearServidor({ dbPath, puerto: PUERTO });
  } catch (e) {
    dialog.showErrorBox('Reciclaje Turnero',
      `No se pudo iniciar el servidor en el puerto ${PUERTO}.\n\n${e.message}\n\n` +
      '¿Hay otra instancia abierta? Cierre la otra aplicación o defina la variable RECICLAJE_PUERTO.');
    app.quit();
    return;
  }

  const win = new BrowserWindow({
    width: 1280,
    height: 800,
    title: 'Reciclaje Turnero — Panel de Administración',
    autoHideMenuBar: true,
  });
  win.loadURL(`http://127.0.0.1:${PUERTO}/admin.html`);

  // Las ventanas locales (manifiesto de carga) se abren dentro de la app; los
  // enlaces externos (Ko-fi, repositorio) se abren en el navegador del sistema.
  win.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const u = new URL(url);
      if (u.hostname === '127.0.0.1' || u.hostname === 'localhost') return { action: 'allow' };
    } catch (e) { /* url relativa/no parseable */ }
    if (/^https?:\/\//.test(url)) { shell.openExternal(url); return { action: 'deny' }; }
    return { action: 'allow' };
  });
}

Menu.setApplicationMenu(null);
app.whenReady().then(iniciar);

app.on('window-all-closed', () => {
  if (servidor) servidor.close();
  app.quit();
});
