'use strict';
/**
 * Servidor local: API REST + WebSocket + páginas admin/display.
 * - Rutas de administración: solo conexiones desde localhost.
 * - Rutas de cliente móvil: requieren header X-PIN (PIN de emparejamiento).
 */
const http = require('http');
const os = require('os');
const path = require('path');
const dgram = require('dgram');
const express = require('express');
const { WebSocketServer } = require('ws');
const { Db } = require('./db');

const PUERTO_DISCOVERY = 18300;

const ESTADOS = ['ESPERANDO', 'LLAMADO', 'EN_PESAJE', 'FINALIZADO', 'NO_PRESENTADO'];
const TIPOS_DOC = ['CC', 'CE', 'NIT', 'PASAPORTE', 'TI'];

function ipLocal() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const i of ifaces || []) {
      if (i.family === 'IPv4' && !i.internal) return i.address;
    }
  }
  return '127.0.0.1';
}

function esLocalhost(req) {
  const a = req.socket.remoteAddress || '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

function enmascarar(doc) {
  if (!doc) return '';
  return doc.length <= 3 ? '***' : '*'.repeat(doc.length - 3) + doc.slice(-3);
}

async function crearServidor({ dbPath, puerto = 3000 }) {
  const db = await Db.open(dbPath);
  const app = express();
  app.use(express.json());

  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: '/ws' });

  function broadcast(tipo, data = {}) {
    const msg = JSON.stringify({ type: tipo, ...data, ts: Date.now() });
    for (const c of wss.clients) {
      if (c.readyState === 1) c.send(msg);
    }
  }

  // ---- Guards por rol ----
  // Cada rol tiene su PIN (configurable en el panel). El PIN de administrador
  // habilita cualquier operación; las rutas locales del panel no requieren PIN.
  const rolDePin = (pin) => {
    if (!pin) return null;
    if (pin === db.getConfig('pin_admin')) return 'admin';
    if (pin === db.getConfig('pin_pesaje')) return 'pesaje';
    if (pin === db.getConfig('pin_emparejamiento')) return 'cliente';
    return null;
  };
  const soloAdmin = (req, res, next) => {
    if (!esLocalhost(req)) return res.status(403).json({ error: 'Solo disponible desde el equipo servidor' });
    next();
  };
  const conRol = (...roles) => (req, res, next) => {
    if (esLocalhost(req)) return next();
    const rol = rolDePin(req.get('X-PIN'));
    if (rol === 'admin' || roles.includes(rol)) return next();
    return res.status(401).json({ error: 'PIN inválido para esta operación' });
  };
  const conPin = conRol('cliente', 'pesaje');
  const esOperador = (req) => {
    if (esLocalhost(req)) return true;
    const rol = rolDePin(req.get('X-PIN'));
    return rol === 'admin' || rol === 'pesaje';
  };

  // ---- Páginas ----
  app.use(express.static(path.join(__dirname, '..', 'public')));
  app.get('/', (_req, res) => res.redirect('/admin.html'));
  app.get('/admin', (_req, res) => res.redirect('/admin.html'));
  app.get('/display', (_req, res) => res.redirect('/display.html'));

  // ---- API ----
  // Ping público (usado por el kiosko y el autodescubrimiento); si trae un PIN
  // válido devuelve el rol correspondiente, para que la app valide el emparejamiento.
  app.get('/api/ping', (req, res) => {
    const rol = esLocalhost(req) ? 'admin' : rolDePin(req.get('X-PIN'));
    res.json({
      ok: true,
      nombre: db.getConfig('nombre_centro'),
      version: require('../package.json').version,
      rol: rol || 'kiosko',
    });
  });

  app.get('/api/materiales', (_req, res) => res.json(db.getMateriales()));

  app.put('/api/materiales/:id', conRol(), (req, res) => {
    const m = db.updateMaterial(Number(req.params.id), req.body || {});
    if (!m) return res.status(400).json({ error: 'Nada que actualizar o material inexistente' });
    broadcast('materiales_updated');
    res.json(m);
  });

  app.post('/api/turnos', conPin, (req, res) => {
    const { tipo_documento, numero_documento, material_id } = req.body || {};
    if (!TIPOS_DOC.includes(tipo_documento)) {
      return res.status(400).json({ error: `tipo_documento debe ser uno de: ${TIPOS_DOC.join(', ')}` });
    }
    if (!numero_documento || !/^[A-Za-z0-9-]{3,20}$/.test(String(numero_documento))) {
      return res.status(400).json({ error: 'numero_documento inválido' });
    }
    const turno = db.crearTurno({
      tipo_documento,
      numero_documento: String(numero_documento),
      material_id: material_id ? Number(material_id) : null,
    });
    broadcast('turnos_updated');
    res.status(201).json(turno);
  });

  app.get('/api/turnos', (req, res) => {
    const turnos = db.getTurnos({ estado: req.query.estado || null, fecha: req.query.fecha || null });
    // Operadores (pesaje/admin) ven el documento completo; el público (display) lo ve enmascarado
    if (esOperador(req)) return res.json(turnos);
    res.json(turnos.map(t => ({ ...t, numero_documento: enmascarar(t.numero_documento) })));
  });

  app.get('/api/turnos/:id', conPin, (req, res) => {
    const t = db.turnoCompleto(Number(req.params.id));
    if (!t) return res.status(404).json({ error: 'Turno no encontrado' });
    res.json(t);
  });

  app.put('/api/turnos/:id/estado', conRol('pesaje'), (req, res) => {
    const { estado, modulo_asignado } = req.body || {};
    if (!ESTADOS.includes(estado)) {
      return res.status(400).json({ error: `estado debe ser uno de: ${ESTADOS.join(', ')}` });
    }
    const t = db.setEstadoTurno(Number(req.params.id), estado, modulo_asignado ?? null);
    if (!t) return res.status(404).json({ error: 'Turno no encontrado' });
    broadcast('turnos_updated', { llamado: estado === 'LLAMADO' ? t.numero : undefined });
    res.json(t);
  });

  app.post('/api/pesaje', conRol('pesaje'), (req, res) => {
    const { turno_id, material_id, peso_kg, usuario_pesador } = req.body || {};
    const kg = Number(peso_kg);
    if (!turno_id || !material_id || !(kg > 0)) {
      return res.status(400).json({ error: 'turno_id, material_id y peso_kg (> 0) son obligatorios' });
    }
    try {
      db.setEstadoTurno(Number(turno_id), 'EN_PESAJE');
      const pesajes = db.crearPesaje({
        turno_id: Number(turno_id), material_id: Number(material_id),
        peso_kg: kg, usuario_pesador: usuario_pesador || 'admin',
      });
      broadcast('turnos_updated');
      res.status(201).json(pesajes);
    } catch (e) {
      res.status(400).json({ error: e.message });
    }
  });

  app.get('/api/pesajes/:turno_id', conRol('pesaje'), (req, res) => {
    res.json(db.getPesajes(Number(req.params.turno_id)));
  });

  app.post('/api/turnos/:id/finalizar', conRol('pesaje'), (req, res) => {
    const id = Number(req.params.id);
    const recibo = db.generarRecibo(id);
    if (!recibo) return res.status(404).json({ error: 'Turno no encontrado' });
    db.setEstadoTurno(id, 'FINALIZADO');
    broadcast('turnos_updated');
    res.json(recibo);
  });

  app.get('/api/recibos/:turno_id', conPin, (req, res) => {
    const r = db.getRecibo(Number(req.params.turno_id));
    if (!r) return res.status(404).json({ error: 'Recibo no disponible' });
    res.json(r);
  });

  // Lista de recibos (rol administrador): ?pendientes=1 para los no pagados
  app.get('/api/recibos', conRol(), (req, res) => {
    res.json(db.getRecibos({ pendientes: req.query.pendientes === '1' }));
  });

  // Autorizar desembolso del efectivo (rol administrador)
  app.post('/api/recibos/:turno_id/pagar', conRol(), (req, res) => {
    const r = db.pagarRecibo(Number(req.params.turno_id), (req.body || {}).autorizado_por || 'admin');
    if (r.error) return res.status(400).json(r);
    broadcast('turnos_updated');
    res.json(r);
  });

  app.get('/api/config', soloAdmin, (_req, res) => {
    const { secreto_firma, ...pub } = db.allConfig();
    res.json({ ...pub, ip: ipLocal(), puerto });
  });

  app.put('/api/config', soloAdmin, (req, res) => {
    const permitidas = ['pin_emparejamiento', 'pin_pesaje', 'pin_admin', 'timeout_minutos', 'num_modulos', 'nombre_centro'];
    for (const k of permitidas) {
      if (req.body[k] !== undefined) db.setConfig(k, req.body[k]);
    }
    broadcast('config_updated');
    const { secreto_firma, ...pub } = db.allConfig();
    res.json({ ...pub, ip: ipLocal(), puerto });
  });

  // Timeout de ausencias: LLAMADO -> NO_PRESENTADO
  const chequeo = setInterval(() => {
    const timeout = Number(db.getConfig('timeout_minutos')) || 5;
    const vencidos = db.expirarLlamados(timeout);
    if (vencidos.length) broadcast('turnos_updated', { no_presentados: vencidos });
  }, 30 * 1000);

  // Autodescubrimiento: la app Android envía "RECICLAJE_DISCOVER" por broadcast UDP
  // al puerto 18300 y el servidor responde con su IP, puerto y nombre.
  const udp = dgram.createSocket('udp4');
  udp.on('message', (msg, rinfo) => {
    if (!msg.toString().startsWith('RECICLAJE_DISCOVER')) return;
    const respuesta = JSON.stringify({
      tipo: 'RECICLAJE_SERVER',
      nombre: db.getConfig('nombre_centro'),
      ip: ipLocal(),
      puerto,
      version: require('../package.json').version,
    });
    udp.send(respuesta, rinfo.port, rinfo.address);
  });
  udp.on('error', (e) => console.warn('Discovery UDP no disponible:', e.message));
  try { udp.bind(PUERTO_DISCOVERY); } catch (e) { console.warn('Discovery UDP:', e.message); }

  await new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(puerto, '0.0.0.0', resolve);
  });

  return {
    db,
    puerto,
    ip: ipLocal(),
    close: () => {
      clearInterval(chequeo);
      wss.close();
      server.close();
      try { udp.close(); } catch (e) { /* ya cerrado */ }
    },
  };
}

module.exports = { crearServidor, ipLocal };
