// Smoke test de la API del servidor (usado por CI y ejecutable localmente):
//   node .ci/smoke-test.js
const path = require('path');
const os = require('os');
const fs = require('fs');
const { crearServidor } = require(path.join(__dirname, '..', 'desktop', 'src', 'server'));

(async () => {
  const dbPath = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'reciclaje-')), 'test.db');
  const s = await crearServidor({ dbPath, puerto: 3999 });
  const base = 'http://127.0.0.1:3999';
  const j = (r) => r.json();

  let r = await fetch(base + '/api/materiales');
  const mats = await j(r);
  if (r.status !== 200 || !mats.length) throw new Error('materiales falló');
  console.log('materiales OK:', mats.length);

  r = await fetch(base + '/api/turnos', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tipo_documento: 'CC', numero_documento: '1234567' }),
  });
  const t = await j(r);
  if (r.status !== 201 || !t.codigo_pin || !t.qr_code) throw new Error('crear turno falló');
  console.log('crear turno OK: numero', t.numero);

  r = await fetch(`${base}/api/turnos/${t.id}/estado`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ estado: 'LLAMADO', modulo_asignado: 2 }),
  });
  if ((await j(r)).estado !== 'LLAMADO') throw new Error('llamar falló');
  console.log('llamar OK');

  r = await fetch(base + '/api/pesaje', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ turno_id: t.id, material_id: mats[0].id, peso_kg: 2.5 }),
  });
  if (r.status !== 201) throw new Error('pesaje falló');
  console.log('pesaje OK');

  r = await fetch(`${base}/api/turnos/${t.id}/finalizar`, { method: 'POST' });
  const rec = await j(r);
  if (r.status !== 200 || !rec.firma || rec.total <= 0) throw new Error('recibo falló');
  console.log('recibo OK: total', rec.total);

  // Pago de recibos (rol administrador)
  r = await fetch(base + '/api/recibos?pendientes=1');
  const pendientes = await j(r);
  if (r.status !== 200 || pendientes.length !== 1) throw new Error('recibos pendientes falló');
  r = await fetch(`${base}/api/recibos/${t.id}/pagar`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ autorizado_por: 'smoke-test' }),
  });
  const pagado = await j(r);
  if (r.status !== 200 || !pagado.pagado) throw new Error('pagar recibo falló');
  r = await fetch(`${base}/api/recibos/${t.id}/pagar`, { method: 'POST' });
  if (r.status !== 400) throw new Error('doble pago no fue rechazado');
  console.log('pago de recibo OK (y doble pago rechazado)');

  // Autodescubrimiento UDP
  await new Promise((resolve, reject) => {
    const dgram = require('dgram');
    const cliente = dgram.createSocket('udp4');
    const timer = setTimeout(() => { cliente.close(); reject(new Error('discovery UDP sin respuesta')); }, 4000);
    cliente.on('message', (msg) => {
      const info = JSON.parse(msg.toString());
      clearTimeout(timer);
      cliente.close();
      if (info.tipo !== 'RECICLAJE_SERVER' || info.puerto !== 3999) return reject(new Error('respuesta discovery inválida'));
      console.log('discovery UDP OK:', info.nombre, `${info.ip}:${info.puerto}`);
      resolve();
    });
    cliente.send('RECICLAJE_DISCOVER', 18300, '127.0.0.1');
  });

  s.close();
  console.log('SMOKE TEST OK');
  process.exit(0);
})().catch((e) => { console.error('FALLO:', e.message); process.exit(1); });
