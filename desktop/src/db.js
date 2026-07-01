'use strict';
/**
 * Capa de datos: SQLite via sql.js (WASM, sin dependencias nativas).
 * La base se persiste a disco tras cada mutación.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const initSqlJs = require('sql.js');

const SCHEMA = `
CREATE TABLE IF NOT EXISTS config (
  clave TEXT PRIMARY KEY,
  valor TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS materiales (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  familia TEXT NOT NULL,
  subcategoria TEXT NOT NULL,
  presentacion TEXT NOT NULL,
  precio_promedio REAL NOT NULL,
  precio_min REAL NOT NULL,
  precio_max REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS usuarios (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  tipo_documento TEXT NOT NULL CHECK (tipo_documento IN ('CC','CE','NIT','PASAPORTE','TI')),
  numero_documento TEXT NOT NULL,
  UNIQUE (tipo_documento, numero_documento)
);
CREATE TABLE IF NOT EXISTS turnos (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  usuario_id INTEGER NOT NULL REFERENCES usuarios(id),
  numero INTEGER NOT NULL,
  fecha TEXT NOT NULL,
  estado TEXT NOT NULL DEFAULT 'ESPERANDO'
    CHECK (estado IN ('ESPERANDO','LLAMADO','EN_PESAJE','FINALIZADO','NO_PRESENTADO')),
  modulo_asignado INTEGER,
  material_id INTEGER REFERENCES materiales(id),
  codigo_pin TEXT NOT NULL,
  qr_code TEXT NOT NULL,
  timestamp TEXT NOT NULL,
  llamado_en TEXT
);
CREATE TABLE IF NOT EXISTS pesajes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  turno_id INTEGER NOT NULL REFERENCES turnos(id),
  material_id INTEGER NOT NULL REFERENCES materiales(id),
  peso_kg REAL NOT NULL,
  precio_kg REAL NOT NULL,
  valor_total REAL NOT NULL,
  usuario_pesador TEXT,
  fecha TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS recibos (
  turno_id INTEGER PRIMARY KEY REFERENCES turnos(id),
  json TEXT NOT NULL,
  firma TEXT NOT NULL,
  fecha TEXT NOT NULL
);
`;

// Precios referenciales COP/kg (Mayo 2025). Editables desde el panel admin.
const MATERIALES_SEED = [
  ['Vidrio', 'Casco blanco', 'Suelto', 150, 100, 200],
  ['Vidrio', 'Casco verde/ámbar', 'Suelto', 100, 70, 140],
  ['Papel', 'Archivo blanco', 'Suelto', 700, 600, 800],
  ['Papel', 'Periódico', 'Suelto', 350, 250, 450],
  ['Papel', 'Cartón', 'Compactado', 450, 380, 520],
  ['Papel', 'Cartón', 'Suelto', 350, 280, 420],
  ['Plástico', 'PET transparente', 'Compactado', 1600, 1400, 1800],
  ['Plástico', 'PET transparente', 'Suelto', 1300, 1100, 1500],
  ['Plástico', 'PEAD (soplado)', 'Suelto', 1100, 900, 1300],
  ['Plástico', 'Plástico flexible (PEBD)', 'Suelto', 500, 350, 650],
  ['Metal', 'Chatarra', 'Suelto', 700, 550, 850],
  ['Metal', 'Aluminio (latas)', 'Compactado', 4500, 4000, 5000],
  ['Metal', 'Cobre', 'Suelto', 25000, 22000, 28000],
];

const CONFIG_DEFAULTS = {
  pin_emparejamiento: '1234',
  timeout_minutos: '5',
  num_modulos: '3',
  nombre_centro: 'Centro de Reciclaje',
  secreto_firma: '', // se genera al iniciar
};

class Db {
  constructor(sqlDb, filePath) {
    this._db = sqlDb;
    this._file = filePath;
  }

  static async open(filePath) {
    const wasmBinary = fs.readFileSync(require.resolve('sql.js/dist/sql-wasm.wasm'));
    const SQL = await initSqlJs({ wasmBinary });
    const sqlDb = fs.existsSync(filePath)
      ? new SQL.Database(fs.readFileSync(filePath))
      : new SQL.Database();
    const db = new Db(sqlDb, filePath);
    db._db.run(SCHEMA);
    db._seed();
    db.save();
    return db;
  }

  _seed() {
    for (const [clave, valor] of Object.entries(CONFIG_DEFAULTS)) {
      const actual = clave === 'secreto_firma' && !this.getConfig(clave)
        ? crypto.randomBytes(16).toString('hex')
        : valor;
      this.run('INSERT OR IGNORE INTO config (clave, valor) VALUES (?, ?)', [clave, actual]);
    }
    const n = this.query('SELECT COUNT(*) AS c FROM materiales')[0].c;
    if (n === 0) {
      for (const m of MATERIALES_SEED) {
        this.run(
          `INSERT INTO materiales (familia, subcategoria, presentacion, precio_promedio, precio_min, precio_max)
           VALUES (?, ?, ?, ?, ?, ?)`, m);
      }
    }
  }

  query(sql, params = []) {
    const stmt = this._db.prepare(sql);
    stmt.bind(params);
    const rows = [];
    while (stmt.step()) rows.push(stmt.getAsObject());
    stmt.free();
    return rows;
  }

  run(sql, params = []) {
    this._db.run(sql, params);
  }

  save() {
    fs.mkdirSync(path.dirname(this._file), { recursive: true });
    fs.writeFileSync(this._file, Buffer.from(this._db.export()));
  }

  // ---- Config ----
  getConfig(clave) {
    const r = this.query('SELECT valor FROM config WHERE clave = ?', [clave]);
    return r.length ? r[0].valor : null;
  }

  setConfig(clave, valor) {
    this.run('INSERT INTO config (clave, valor) VALUES (?, ?) ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor',
      [clave, String(valor)]);
    this.save();
  }

  allConfig() {
    const out = {};
    for (const { clave, valor } of this.query('SELECT clave, valor FROM config')) out[clave] = valor;
    return out;
  }

  // ---- Materiales ----
  getMateriales() {
    return this.query('SELECT * FROM materiales ORDER BY familia, subcategoria, presentacion');
  }

  updateMaterial(id, campos) {
    const permitidos = ['precio_promedio', 'precio_min', 'precio_max', 'familia', 'subcategoria', 'presentacion'];
    const sets = [];
    const params = [];
    for (const k of permitidos) {
      if (campos[k] !== undefined) { sets.push(`${k} = ?`); params.push(campos[k]); }
    }
    if (!sets.length) return null;
    params.push(id);
    this.run(`UPDATE materiales SET ${sets.join(', ')} WHERE id = ?`, params);
    this.save();
    return this.query('SELECT * FROM materiales WHERE id = ?', [id])[0] || null;
  }

  // ---- Turnos ----
  crearTurno({ tipo_documento, numero_documento, material_id = null }) {
    let usuario = this.query(
      'SELECT * FROM usuarios WHERE tipo_documento = ? AND numero_documento = ?',
      [tipo_documento, numero_documento])[0];
    if (!usuario) {
      this.run('INSERT INTO usuarios (tipo_documento, numero_documento) VALUES (?, ?)',
        [tipo_documento, numero_documento]);
      usuario = this.query('SELECT * FROM usuarios ORDER BY id DESC LIMIT 1')[0];
    }
    const hoy = new Date().toISOString().slice(0, 10);
    // Un solo turno abierto por usuario y día
    const abierto = this.query(
      `SELECT * FROM turnos WHERE usuario_id = ? AND fecha = ? AND estado IN ('ESPERANDO','LLAMADO','EN_PESAJE')`,
      [usuario.id, hoy])[0];
    if (abierto) return this.turnoCompleto(abierto.id);

    const nro = (this.query('SELECT COALESCE(MAX(numero),0) AS m FROM turnos WHERE fecha = ?', [hoy])[0].m) + 1;
    const pin = String(Math.floor(1000 + Math.random() * 9000));
    const ts = new Date().toISOString();
    const qr = `RECICLAJE|${hoy}|${nro}|${pin}`;
    this.run(
      `INSERT INTO turnos (usuario_id, numero, fecha, estado, material_id, codigo_pin, qr_code, timestamp)
       VALUES (?, ?, ?, 'ESPERANDO', ?, ?, ?, ?)`,
      [usuario.id, nro, hoy, material_id, pin, qr, ts]);
    const id = this.query('SELECT id FROM turnos ORDER BY id DESC LIMIT 1')[0].id;
    this.save();
    return this.turnoCompleto(id);
  }

  turnoCompleto(id) {
    const t = this.query(
      `SELECT t.*, u.tipo_documento, u.numero_documento,
              m.familia, m.subcategoria, m.presentacion
       FROM turnos t
       JOIN usuarios u ON u.id = t.usuario_id
       LEFT JOIN materiales m ON m.id = t.material_id
       WHERE t.id = ?`, [id])[0];
    return t || null;
  }

  getTurnos({ fecha = null, estado = null } = {}) {
    const f = fecha || new Date().toISOString().slice(0, 10);
    let sql = `SELECT t.*, u.tipo_documento, u.numero_documento,
                      m.familia, m.subcategoria, m.presentacion
               FROM turnos t
               JOIN usuarios u ON u.id = t.usuario_id
               LEFT JOIN materiales m ON m.id = t.material_id
               WHERE t.fecha = ?`;
    const params = [f];
    if (estado) { sql += ' AND t.estado = ?'; params.push(estado); }
    sql += ' ORDER BY t.numero ASC';
    return this.query(sql, params);
  }

  setEstadoTurno(id, estado, modulo = null) {
    const turno = this.turnoCompleto(id);
    if (!turno) return null;
    const llamadoEn = estado === 'LLAMADO' ? new Date().toISOString() : turno.llamado_en;
    this.run('UPDATE turnos SET estado = ?, modulo_asignado = COALESCE(?, modulo_asignado), llamado_en = ? WHERE id = ?',
      [estado, modulo, llamadoEn, id]);
    this.save();
    return this.turnoCompleto(id);
  }

  /** Turnos LLAMADO cuyo tiempo de espera venció -> NO_PRESENTADO. Devuelve ids afectados. */
  expirarLlamados(timeoutMin) {
    const limite = new Date(Date.now() - timeoutMin * 60 * 1000).toISOString();
    const vencidos = this.query(
      `SELECT id FROM turnos WHERE estado = 'LLAMADO' AND llamado_en IS NOT NULL AND llamado_en < ?`, [limite]);
    for (const { id } of vencidos) {
      this.run(`UPDATE turnos SET estado = 'NO_PRESENTADO' WHERE id = ?`, [id]);
    }
    if (vencidos.length) this.save();
    return vencidos.map(v => v.id);
  }

  // ---- Pesajes ----
  crearPesaje({ turno_id, material_id, peso_kg, usuario_pesador = 'admin' }) {
    const material = this.query('SELECT * FROM materiales WHERE id = ?', [material_id])[0];
    if (!material) throw new Error('Material no encontrado');
    const precio = material.precio_promedio;
    const valor = Math.round(precio * peso_kg);
    this.run(
      `INSERT INTO pesajes (turno_id, material_id, peso_kg, precio_kg, valor_total, usuario_pesador, fecha)
       VALUES (?, ?, ?, ?, ?, ?, ?)`,
      [turno_id, material_id, peso_kg, precio, valor, usuario_pesador, new Date().toISOString()]);
    this.save();
    return this.getPesajes(turno_id);
  }

  getPesajes(turno_id) {
    return this.query(
      `SELECT p.*, m.familia, m.subcategoria, m.presentacion
       FROM pesajes p JOIN materiales m ON m.id = p.material_id
       WHERE p.turno_id = ? ORDER BY p.id`, [turno_id]);
  }

  // ---- Recibos ----
  generarRecibo(turno_id) {
    const turno = this.turnoCompleto(turno_id);
    if (!turno) return null;
    const pesajes = this.getPesajes(turno_id);
    const recibo = {
      turno_id,
      numero_turno: turno.numero,
      fecha_turno: turno.fecha,
      usuario: { tipo_documento: turno.tipo_documento, numero_documento: turno.numero_documento },
      detalle: pesajes.map(p => ({
        material: `${p.familia} / ${p.subcategoria} (${p.presentacion})`,
        kg: p.peso_kg,
        precio_unitario: p.precio_kg,
        total: p.valor_total,
      })),
      total: pesajes.reduce((s, p) => s + p.valor_total, 0),
      fecha: new Date().toISOString(),
    };
    const json = JSON.stringify(recibo);
    const firma = crypto.createHmac('sha256', this.getConfig('secreto_firma') || 'reciclaje')
      .update(json).digest('hex');
    this.run(
      `INSERT INTO recibos (turno_id, json, firma, fecha) VALUES (?, ?, ?, ?)
       ON CONFLICT(turno_id) DO UPDATE SET json = excluded.json, firma = excluded.firma, fecha = excluded.fecha`,
      [turno_id, json, firma, recibo.fecha]);
    this.save();
    return { ...recibo, firma };
  }

  getRecibo(turno_id) {
    const r = this.query('SELECT * FROM recibos WHERE turno_id = ?', [turno_id])[0];
    if (!r) return null;
    return { ...JSON.parse(r.json), firma: r.firma };
  }
}

module.exports = { Db };
