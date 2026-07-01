'use strict';
/**
 * Servidor local: API REST + WebSocket + páginas admin/display.
 * - Rutas de administración: solo conexiones desde localhost.
 * - Rutas de cliente móvil: requieren header X-PIN (PIN de emparejamiento).
 */
const http = require('http');
const os = require('os');
const path = require('path');
const express = require('express');
const { WebSocketServer } = require('ws');
const { Db } = require('./db');

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

  // ---- Guards ----
  const soloAdmin = (req, res, next) => {
    if (!esLocalhost(req)) return res.status(403).json({ error: 'Solo disponible desde el equipo servidor' });
    next();
  };
  const conPin = (req, res, next) => {
    const pin = req.get('X-PIN');
    if (esLocalhost(req)) return next();
    if (pin !== db.getConfig('pin_emparejamiento')) {
      return res.status(401).json({ error: 'PIN de emparejamiento inválido' });
    }
    next();
  };

  // ---- Páginas ----
  app.use(express.static(path.join(__dirname, '..', 'public')));
  app.get('/', (_req, res) => res.redirect('/admin.html'));
  app.get('/admin', (_req, res) => res.redirect('/admin.html'));
  app.get('/display', (_req, res) => res.redirect('/display.html'));

  // ---- API ----
  app.get('/api/ping', conPin, (_req, res) => {
    res.json({ ok: true, nombre: db.getConfig('nombre_centro'), version: require('../package.json').version });
  });

  app.get('/api/materiales', (_req, res) => res.json(db.getMateriales()));

  app.put('/api/materiales/:id', soloAdmin, (req, res) => {
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
    if (esLocalhost(req)) return res.json(turnos);
    res.json(turnos.map(t => ({ ...t, numero_documento: enmascarar(t.numero_documento) })));
  });

  app.get('/api/turnos/:id', conPin, (req, res) => {
    const t = db.turnoCompleto(Number(req.params.id));
    if (!t) return res.status(404).json({ error: 'Turno no encontrado' });
    res.json(t);
  });

  app.put('/api/turnos/:id/estado', soloAdmin, (req, res) => {
    const { estado, modulo_asignado } = req.body || {};
    if (!ESTADOS.includes(estado)) {
      return res.status(400).json({ error: `estado debe ser uno de: ${ESTADOS.join(', ')}` });
    }
    const t = db.setEstadoTurno(Number(req.params.id), estado, modulo_asignado ?? null);
    if (!t) return res.status(404).json({ error: 'Turno no encontrado' });
    broadcast('turnos_updated', { llamado: estado === 'LLAMADO' ? t.numero : undefined });
    res.json(t);
  });

  app.post('/api/pesaje', soloAdmin, (req, res) => {
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

  app.get('/api/pesajes/:turno_id', soloAdmin, (req, res) => {
    res.json(db.getPesajes(Number(req.params.turno_id)));
  });

  app.post('/api/turnos/:id/finalizar', soloAdmin, (req, res) => {
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

  app.get('/api/config', soloAdmin, (_req, res) => {
    const { secreto_firma, ...pub } = db.allConfig();
    res.json({ ...pub, ip: ipLocal(), puerto });
  });

  app.put('/api/config', soloAdmin, (req, res) => {
    const permitidas = ['pin_emparejamiento', 'timeout_minutos', 'num_modulos', 'nombre_centro'];
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

  await new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(puerto, '0.0.0.0', resolve);
  });

  return {
    db,
    puerto,
    ip: ipLocal(),
    close: () => { clearInterval(chequeo); wss.close(); server.close(); },
  };
}

module.exports = { crearServidor, ipLocal };
