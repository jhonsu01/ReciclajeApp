package com.reciclaje.turnero

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-arranque del modo kiosko al encender el TV.
 * Solo actúa si el dispositivo ya quedó vinculado como kiosko; de lo contrario
 * no hace nada (el usuario abrirá la app para vincularla la primera vez).
 * Registrado únicamente en el flavor "kiosko".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("reciclaje", Context.MODE_PRIVATE)
        val vinculado = prefs.getString("token", null) != null &&
            prefs.getString("rol", null) == "kiosko" &&
            prefs.getString("host", null) != null
        if (!vinculado) return
        val launch = Intent(context, KioskLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
}
