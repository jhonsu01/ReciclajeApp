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

  // Bug v0.3: con el pago pendiente, el mismo documento NO puede sacar turno nuevo
  r = await fetch(base + '/api/turnos', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tipo_documento: 'CC', numero_documento: '1234567' }),
  });
  const retomado = await j(r);
  if (retomado.id !== t.id) throw new Error(`turno duplicado con pago pendiente (esperaba ${t.id}, creó ${retomado.id})`);
  console.log('retoma turno con pago pendiente OK (no duplica)');

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

  // Tras el pago sí puede sacar un turno nuevo
  r = await fetch(base + '/api/turnos', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tipo_documento: 'CC', numero_documento: '1234567' }),
  });
  const nuevo = await j(r);
  if (nuevo.id === t.id) throw new Error('tras el pago debería poder crear turno nuevo');
  console.log('nuevo turno tras pago OK: numero', nuevo.numero);

  // Historial del cliente por documento (incluye estado de pago)
  r = await fetch(base + '/api/historial?tipo_documento=CC&numero_documento=1234567');
  const historial = await j(r);
  if (r.status !== 200 || historial.length !== 1 || historial[0].pagado !== true) throw new Error('historial falló');
  console.log('historial de recibos OK:', historial.length, 'recibo(s)');

  // Config pública de la marquesina
  r = await fetch(base + '/api/display');
  const displayCfg = await j(r);
  if (r.status !== 200 || !(displayCfg.marquesina_velocidad > 0)) throw new Error('config display falló');
  console.log('config marquesina OK: velocidad', displayCfg.marquesina_velocidad, 's');

  // Emparejamiento por PIN de sesión -> token de dispositivo + revocación
  r = await fetch(base + '/api/config');
  const cfg = await j(r);
  if (!cfg.pines_sesion || !cfg.pines_sesion.pesaje) throw new Error('pines de sesión ausentes');
  r = await fetch(base + '/api/emparejar', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rol: 'pesaje', pin: 'incorrecto', nombre: 'test' }),
  });
  if (r.status !== 401) throw new Error('emparejar con PIN malo no fue rechazado');
  r = await fetch(base + '/api/emparejar', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rol: 'pesaje', pin: cfg.pines_sesion.pesaje, nombre: 'smoke-device' }),
  });
  const emparejado = await j(r);
  if (r.status !== 201 || !emparejado.token) throw new Error('emparejar falló');
  r = await fetch(base + '/api/dispositivos');
  const dispositivos = await j(r);
  if (!dispositivos.length || !dispositivos[0].activo) throw new Error('dispositivo no registrado');
  const pinKioskoAntes = cfg.pines_sesion.kiosko;
  // Emparejar un kiosko para revocarlo y comprobar que regenera SU PIN
  r = await fetch(base + '/api/emparejar', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ rol: 'kiosko', pin: pinKioskoAntes, nombre: 'smoke-tv' }),
  });
  const tv = await j(r);
  if (r.status !== 201 || !tv.token) throw new Error('emparejar kiosko falló');
  const disp = await (await fetch(base + '/api/dispositivos')).json();
  const idTv = disp.find(d => d.rol === 'kiosko' && d.activo).id;
  r = await fetch(`${base}/api/dispositivos/${idTv}/revocar`, { method: 'POST' });
  const rev = await j(r);
  if (r.status !== 200 || !rev.nuevo_pin || rev.nuevo_pin === pinKioskoAntes) {
    throw new Error('revocar kiosko debía regenerar el PIN de ese rol');
  }
  console.log('revocación regenera PIN del rol OK (kiosko:', pinKioskoAntes, '->', rev.nuevo_pin + ')');

  // El dispositivo revocado queda inactivo (en un TV real, no-localhost, su token
  // deja de reconocerse y el ping devuelve rol 'cliente' -> la app vuelve a vincular)
  const dispRev = await (await fetch(base + '/api/dispositivos')).json();
  if (dispRev.find(d => d.id === idTv).activo) throw new Error('el dispositivo revocado seguía activo');
  console.log('dispositivo revocado queda inactivo OK');

  // Regeneración de PIN por un solo rol
  r = await fetch(base + '/api/config/regenerar-pin/pesaje', { method: 'POST' });
  const regen = await j(r);
  if (r.status !== 200 || !regen.pin || regen.pin === cfg.pines_sesion.pesaje) throw new Error('regen PIN por rol falló');
  console.log('regen PIN por rol OK');

  // Materiales personalizados: crear y eliminar
  r = await fetch(base + '/api/materiales', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ familia: 'Prueba', subcategoria: 'Custom', presentacion: 'Suelto', precio_promedio: 999 }),
  });
  const nuevoMat = await j(r);
  if (r.status !== 201 || !nuevoMat.id) throw new Error('crear material falló');
  r = await fetch(`${base}/api/materiales/${nuevoMat.id}`, { method: 'DELETE' });
  if (r.status !== 200) throw new Error('eliminar material falló');
  console.log('materiales personalizados OK');

  // Inventario: el pesaje ya alimentó el stock (2.5 kg de material 1 en Suelto)
  const mat1 = mats[0].id;
  let inv = await (await fetch(base + '/api/inventario')).json();
  const suelto = inv.find(x => x.material_id === mat1 && x.embalaje === 'Suelto');
  if (!suelto || suelto.peso_kg < 2.5) throw new Error('el pesaje no alimentó el inventario');
  console.log('inventario alimentado por pesaje OK:', suelto.peso_kg, 'kg suelto');

  // Entrada manual de inventario previo
  r = await fetch(base + '/api/inventario/entrada', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ material_id: mat1, embalaje: 'Suelto', peso_kg: 100 }),
  });
  if (r.status !== 200) throw new Error('entrada de inventario falló');

  // Embalar 80 kg de Suelto en 2 bultos
  r = await fetch(base + '/api/inventario/embalaje', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ material_id: mat1, embalaje_destino: 'Bulto', peso_kg: 80, unidades: 2 }),
  });
  if (r.status !== 200) throw new Error('embalaje falló');
  inv = await (await fetch(base + '/api/inventario')).json();
  const bulto = inv.find(x => x.material_id === mat1 && x.embalaje === 'Bulto');
  if (!bulto || bulto.peso_kg !== 80 || bulto.unidades !== 2) throw new Error('embalaje no cuadró');
  console.log('entrada + embalaje OK: 2 bultos de 80 kg');

  // Embalar más de lo disponible debe rechazarse
  r = await fetch(base + '/api/inventario/embalaje', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ material_id: mat1, embalaje_destino: 'Bloque', peso_kg: 999999, unidades: 1 }),
  });
  if (r.status !== 400) throw new Error('embalaje sobre stock no fue rechazado');
  console.log('embalaje sobre stock rechazado OK');

  // Salida / despacho con manifiesto: saca 1 bulto (40 kg)
  r = await fetch(base + '/api/salidas', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      fecha_salida: '2026-07-11', despacha: 'Operador', recibe: 'Mayorista SA',
      conductor: 'Juan', vehiculo_placa: 'ABC123', destino: 'Bogotá',
      carga: { naturaleza: 'Reciclable', designacion_mercancia: 'UN 3082' },
      items: [{ material_id: mat1, embalaje: 'Bulto', peso_kg: 40, unidades: 1 }],
    }),
  });
  const salida = await j(r);
  if (r.status !== 201 || !salida.firma || !salida.consecutivo || salida.total_kg !== 40) throw new Error('crear salida falló');
  console.log('salida con manifiesto OK:', salida.consecutivo, '- descuenta', salida.total_kg, 'kg');

  // El inventario se descontó (quedan 40 kg y 1 bulto)
  inv = await (await fetch(base + '/api/inventario')).json();
  const bulto2 = inv.find(x => x.material_id === mat1 && x.embalaje === 'Bulto');
  if (!bulto2 || bulto2.peso_kg !== 40 || bulto2.unidades !== 1) throw new Error('la salida no descontó el inventario');
  console.log('descuento de inventario por salida OK: quedan', bulto2.peso_kg, 'kg,', bulto2.unidades, 'bulto');

  // Salida sobre stock rechazada (no descuenta nada)
  r = await fetch(base + '/api/salidas', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fecha_salida: '2026-07-11', items: [{ material_id: mat1, embalaje: 'Bulto', peso_kg: 99999 }] }),
  });
  if (r.status !== 400) throw new Error('salida sobre stock no fue rechazada');
  console.log('salida sobre stock rechazada OK');

  // Autodescubrimiento UDP
  await new Promise((resolve, reject) => {
    const dgram = require('dgram');
    const cliente = dgram.createSocket('udp4');
    const timer = setTimeout(() => { cliente.close(); reject(new Error('discovery UDP sin respuesta')); }, 4000);
    cliente.on('message', (msg) => {
      const info = JSON.parse(msg.toString());
      if (info.tipo !== 'RECICLAJE_SERVER') return; // ruido de red: seguir esperando
      if (info.puerto !== 3999) {
        // Otra instancia local (la app instalada abierta) también responde: protocolo OK igualmente
        console.log('discovery UDP OK (respondió otra instancia local):', `${info.ip}:${info.puerto}`);
      } else {
        console.log('discovery UDP OK:', info.nombre, `${info.ip}:${info.puerto}`);
      }
      clearTimeout(timer);
      cliente.close();
      resolve();
    });
    cliente.send('RECICLAJE_DISCOVER', 18300, '127.0.0.1');
  });

  s.close();
  console.log('SMOKE TEST OK');
  process.exit(0);
})().catch((e) => { console.error('FALLO:', e.message); process.exit(1); });
