package com.cloudstreampc

import com.cloudstreampc.core.Store
import com.cloudstreampc.player.ExternalPlayer
import com.cloudstreampc.player.PlayerSurface
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Lot7Test {
    @Test
    fun keysTypedOnTheVideoBecomeMpvKeys() {
        assertEquals("SPACE", PlayerSurface.mpvKey(KeyEvent.VK_SPACE, ' ', ctrl = false, alt = false, shift = false))
        assertEquals("Shift+RIGHT", PlayerSurface.mpvKey(KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED, ctrl = false, alt = false, shift = true))
        assertEquals("Ctrl+p", PlayerSurface.mpvKey(KeyEvent.VK_P, '\u0010', ctrl = true, alt = false, shift = false))
        // The character keeps the keyboard layout (AZERTY) and Shift.
        assertEquals("Z", PlayerSurface.mpvKey(KeyEvent.VK_W, 'Z', ctrl = false, alt = false, shift = true))
        assertEquals("F11", PlayerSurface.mpvKey(KeyEvent.VK_F11, KeyEvent.CHAR_UNDEFINED, ctrl = false, alt = false, shift = false))
        assertEquals(null, PlayerSurface.mpvKey(KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, ctrl = false, alt = false, shift = true))
    }

    @Test
    fun subtitleLook() {
        Store.updateSettings {
            it.copy(subtitleSize = 68, subtitleColor = 0xFFFFEB3B, subtitlePosition = 94, subtitleBold = true, subtitleDelay = -0.3, subtitleBackground = 75)
        }
        val args = ExternalPlayer.subtitleArgs()
        assertEquals(
            listOf("--sub-font-size=68", "--sub-color=#FFFFEB3B", "--sub-pos=94", "--sub-bold=yes", "--sub-delay=-0.3"),
            args,
        )
        val commands = ExternalPlayer.subtitleStyleCommands()
        assertTrue(listOf("set_property", "sub-back-color", "#BF000000") in commands)
        assertTrue(listOf("set_property", "sub-border-style", "background-box") in commands)
        Store.updateSettings { com.cloudstreampc.core.Settings().copy(repositories = it.repositories) }
    }
}
