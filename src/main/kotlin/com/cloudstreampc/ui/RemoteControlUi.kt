package com.cloudstreampc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.RemoteControl
import io.nayuki.qrcodegen.QrCode

/** "Télécommande avec le téléphone": switch, then the QR code and the code to type. */
@Composable
fun RemoteControlItem() {
    val s = Store.settings
    var show by remember { mutableStateOf(false) }
    SettingsSwitch(
        "Télécommande avec le téléphone",
        if (s.remoteControl) "Activée · touchez pour afficher le code QR" else "Le téléphone pilote la lecture (même réseau Wi-Fi)",
        s.remoteControl,
    ) { v ->
        Store.updateSettings { it.copy(remoteControl = v) }
        RemoteControl.applySettings()
        if (v) show = true
    }
    if (s.remoteControl) {
        SettingsItem("Afficher le code QR de la télécommande", null) { show = true }
    }
    if (show) RemoteControlDialog { show = false }
}

@Composable
fun RemoteControlDialog(onDismiss: () -> Unit) {
    if (!RemoteControl.running) RemoteControl.start()
    val withKey = RemoteControl.address(withKey = true)
    val plain = RemoteControl.address(withKey = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Télécommande avec le téléphone") },
        text = {
            Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (withKey == null || plain == null) {
                    Text("Aucun réseau local trouvé : connectez l'ordinateur au Wi-Fi ou au réseau de la box.", color = CsColors.TextMuted)
                } else {
                    Text("Scannez ce code avec l'appareil photo du téléphone (connecté au même réseau) :", color = CsColors.TextMuted, fontSize = 14.sp)
                    QrImage(withKey, Modifier.size(240.dp))
                    Text("Ou ouvrez cette adresse puis tapez le code :", color = CsColors.TextMuted, fontSize = 14.sp)
                    SelectionContainer { Text(plain, fontWeight = FontWeight.SemiBold) }
                    Text(RemoteControl.pin, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                    Text(
                        "Windows peut demander d'autoriser CloudStreamPC sur le réseau : acceptez pour les réseaux privés.",
                        color = CsColors.TextMuted, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** QR code drawn module by module, black on a white quiet zone. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp)) {
        val cell = size.minDimension / qr.size
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.getModule(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
