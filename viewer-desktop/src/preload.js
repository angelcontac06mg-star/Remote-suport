const { contextBridge } = require('electron');
contextBridge.exposeInMainWorld('cfg', {
  signalingUrl: process.env.SIGNALING_URL || 'wss://remoteassist.onrender.com/ws',
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    ...(process.env.TURN_URL && process.env.TURN_USERNAME && process.env.TURN_CREDENTIAL
      ? [{ urls: process.env.TURN_URL, username: process.env.TURN_USERNAME, credential: process.env.TURN_CREDENTIAL }]
      : [])
  ]
});
