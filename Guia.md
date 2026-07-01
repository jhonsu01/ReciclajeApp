# 🧠 Guía para IA: Desarrollo de Sistema de Gestión de Reciclaje con Turnero Inteligente

## 1. 🎯 Objetivo del Sistema

Construir un ecosistema completo compuesto por:

* 🖥️ Aplicación de escritorio (Windows MSI) → Servidor + Panel Admin
* 📱 Aplicación Android (APK) → Cliente usuario reciclador
* 📺 Pantallas informativas (modo oscuro) → Visualización tipo turnero
* 🔗 Comunicación local → PIN + Bluetooth + WiFi (offline-first)

---

## 2. 🧱 Arquitectura General

### Modelo: **Offline-First + Sincronización Local**

```
[ANDROID APP] ←→ [SERVIDOR LOCAL (DESKTOP)]
        ↑                ↓
 Bluetooth / WiFi     API + DB
        ↓                ↓
     [PANTALLAS TURNERO DISPLAY]
```

---

## 3. ⚙️ Tecnologías Recomendadas

### Backend (Servidor)

* Node.js + NestJS o FastAPI
* SQLite (local) + sincronización opcional futura
* WebSocket (tiempo real)
* Bluetooth LE / WiFi Direct

### Desktop (MSI)

* Electron + React
* Empaquetado: electron-builder (.msi)

### Android

* Kotlin (recomendado) o Flutter
* Room DB (offline local)
* WorkManager (sync)

### Pantallas

* Web App en modo kiosk (Chromium fullscreen)
* Tema oscuro (Tailwind / CSS custom)

---

## 4. 📊 Modelo de Datos (Basado en PDF)

## Fuente:

Tabla 1: Precios promedio de reciclaje Mayo 2025

### Entidades:

#### 🧩 Material

```
id
familia (Vidrio, Papel, Plástico, Metal)
subcategoria (Ej: PET, Cartón)
presentacion (Ej: Compactado, Suelto)
precio_promedio
precio_min
precio_max
```

#### 👤 Usuario

```
id
tipo_documento:
  - CC
  - CE
  - NIT
  - PASAPORTE
  - TI
numero_documento
```

#### 🎫 Turno

```
id
usuario_id
estado:
  - ESPERANDO
  - LLAMADO
  - EN_PESAJE
  - FINALIZADO
  - NO_PRESENTADO
modulo_asignado
codigo_pin
qr_code
timestamp
```

#### ⚖️ Pesaje

```
id
turno_id
material_id
peso_kg
precio_kg
valor_total
usuario_pesador
fecha
```

#### 🧾 Recibo (JSON)

```
{
  turno_id,
  usuario,
  material,
  kg,
  precio_unitario,
  total,
  fecha
}
```

---

## 5. 🔄 Flujo del Sistema

### 1. Registro de Turno (Cliente)

* Usuario abre app
* Ingresa:

  * Tipo documento
  * Número documento
  * (Opcional) material
* Genera:

  * QR + PIN
* Se sincroniza:

  * Online → API
  * Offline → Bluetooth/WiFi

---

### 2. Visualización (Pantallas)

* Lista tipo turnero:

```
Turno 001 → Módulo 2
Turno 002 → Esperando
Turno 003 → Llamado
```

---

### 3. Llamado de Turno

* Admin selecciona turno
* Sistema:

  * Cambia estado → LLAMADO
  * Envía notificación:

    * Vibración móvil
    * Mensaje local

---

### 4. Pesaje

* Pesador:

  * Puede modificar material
  * Ingresa kg
  * Sistema calcula valor

---

### 5. Finalización

* Se genera recibo JSON
* Se guarda en:

  * Servidor
  * App cliente

---

### 6. Manejo de Ausencias

* Timeout configurable
* Estado → NO_PRESENTADO
* Pasa al siguiente turno

---

## 6. 🔌 Sincronización Offline

### Métodos:

#### Bluetooth

* Emparejamiento por PIN
* Transferencia JSON

#### WiFi Local

* Hotspot servidor
* API local: `http://192.168.0.1:3000`

#### Estrategia

* Cola local de eventos
* Sync automático cuando haya conexión

---

## 7. 🔐 Seguridad

* Emparejamiento por PIN único
* Validación de sesiones
* Firma simple de JSON (hash SHA256)

---

## 8. 🎨 UI / UX

### Pantallas Turnero

* Fondo negro (#0D0D0D)
* Tipografía grande
* Colores:

  * Verde → Disponible
  * Amarillo → Llamado
  * Rojo → No presentado

---

## 9. 🧠 Uso de IA en el Sistema

### Recomendado:

* Clasificación automática de materiales (visión futura)
* Predicción de precios
* Optimización de flujo de turnos

---

## 10. 🚀 API REST Base

### Turnos

```
POST /turnos
GET /turnos
PUT /turnos/:id/estado
```

### Materiales

```
GET /materiales
PUT /materiales
```

### Pesaje

```
POST /pesaje
```

---

## 11. 📦 Deploy

### Desktop

```
npm run build
electron-builder --win
```

### Android

```
./gradlew assembleRelease
```

---

## 12. 🔥 Mejores Prácticas

* Diseñar como **offline-first**
* Usar eventos en lugar de estados rígidos
* Mantener JSON como formato universal
* Separar lógica de negocio del UI
* Implementar logs locales

---

## 13. 📌 Consideraciones del Mercado

Los precios del PDF:

* Son referenciales
* Cambian por región, calidad y volumen

---

## 14. 🧩 Extensiones Futuras

* Integración con pagos
* Dashboard analytics
* Multi-sede
* Blockchain para trazabilidad

---

## 15. ✅ Resultado Esperado

Sistema robusto capaz de:

* Operar sin internet
* Gestionar filas de reciclaje
* Automatizar pesaje
* Informar precios en tiempo real
* Mejorar eficiencia operativa

---

# 🧠 Prompt sugerido para IA

"Construye un sistema completo basado en esta arquitectura, incluyendo backend, frontend, app Android y sincronización offline, utilizando mejores prácticas de escalabilidad, seguridad y UX."
