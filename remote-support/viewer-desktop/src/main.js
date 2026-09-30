const { app, BrowserWindow } = require('electron');
const path = require('path');
app.whenReady().then(() => {
  const w = new BrowserWindow({ width: 560, height: 900, title: 'RemoteAssist Viewer',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    } });
  w.setMenuBarVisibility(false);
  w.loadFile(path.join(__dirname, 'index.html'));
});
app.on('window-all-closed', () => app.quit());
