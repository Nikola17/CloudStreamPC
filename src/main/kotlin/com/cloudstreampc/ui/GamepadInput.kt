package com.cloudstreampc.ui

import com.cloudstreampc.core.Logger
import com.cloudstreampc.core.Store
import com.cloudstreampc.player.PlaybackManager
import com.studiohartman.jamepad.ControllerAxis
import com.studiohartman.jamepad.ControllerButton
import com.studiohartman.jamepad.ControllerManager
import java.awt.KeyboardFocusManager
import java.awt.Robot
import java.awt.event.KeyEvent

/**
 * Xbox / PlayStation / Switch controllers (SDL through Jamepad): their buttons become key presses,
 * so they drive both the app (arrows, Enter, Escape) and mpv (pause, seek, skip intro…).
 *
 * D-pad / left stick: arrows · A: Enter · B: Escape (back) · X / Start: Space (pause) ·
 * Y: F (fullscreen) · LB / RB: ← / → in mpv (seek) and page scroll in the app.
 */
object GamepadInput {
    private val mapping = mapOf(
        ControllerButton.DPAD_UP to KeyEvent.VK_UP,
        ControllerButton.DPAD_DOWN to KeyEvent.VK_DOWN,
        ControllerButton.DPAD_LEFT to KeyEvent.VK_LEFT,
        ControllerButton.DPAD_RIGHT to KeyEvent.VK_RIGHT,
        ControllerButton.A to KeyEvent.VK_ENTER,
        ControllerButton.B to KeyEvent.VK_ESCAPE,
        ControllerButton.BACK to KeyEvent.VK_ESCAPE,
        ControllerButton.X to KeyEvent.VK_SPACE,
        ControllerButton.START to KeyEvent.VK_SPACE,
        ControllerButton.Y to KeyEvent.VK_F,
        ControllerButton.LEFTBUMPER to KeyEvent.VK_PAGE_UP,
        ControllerButton.RIGHTBUMPER to KeyEvent.VK_PAGE_DOWN,
    )
    private const val REPEAT_DELAY_MS = 400L
    private const val REPEAT_EVERY_MS = 110L

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread(::loop, "gamepad").apply { isDaemon = true }.start()
    }

    /** Only while CloudStreamPC (or the player it opened) is in front: never in another app's game. */
    private fun ourWindowsInFront() =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null || PlaybackManager.nowPlaying != null

    private fun loop() {
        val manager = runCatching { ControllerManager().apply { initSDLGamepad() } }.getOrElse {
            Logger.info("Manettes indisponibles : ${it.message}")
            return
        }
        val robot = runCatching { Robot() }.getOrNull() ?: return
        val pressedSince = mutableMapOf<Int, Long>()
        val lastRepeat = mutableMapOf<Int, Long>()
        try {
            while (running) {
                Thread.sleep(16)
                if (!Store.settings.gamepadEnabled) continue
                manager.update()
                val keys = mutableSetOf<Int>()
                for (index in 0 until manager.numControllers) {
                    val controller = runCatching { manager.getControllerIndex(index) }.getOrNull() ?: continue
                    if (!controller.isConnected) continue
                    mapping.forEach { (button, key) -> if (runCatching { controller.isButtonPressed(button) }.getOrDefault(false)) keys += key }
                    val x = runCatching { controller.getAxisState(ControllerAxis.LEFTX) }.getOrDefault(0f)
                    val y = runCatching { controller.getAxisState(ControllerAxis.LEFTY) }.getOrDefault(0f)
                    if (x < -0.6f) keys += KeyEvent.VK_LEFT
                    if (x > 0.6f) keys += KeyEvent.VK_RIGHT
                    if (y < -0.6f) keys += KeyEvent.VK_UP
                    if (y > 0.6f) keys += KeyEvent.VK_DOWN
                }
                val now = System.currentTimeMillis()
                (pressedSince.keys - keys).forEach { pressedSince.remove(it); lastRepeat.remove(it) }
                if (!ourWindowsInFront()) continue
                for (key in keys) {
                    val since = pressedSince[key]
                    val repeat = key in setOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT)
                    val fire = when {
                        since == null -> true
                        !repeat -> false
                        now - since > REPEAT_DELAY_MS && now - (lastRepeat[key] ?: 0) > REPEAT_EVERY_MS -> true
                        else -> false
                    }
                    if (since == null) pressedSince[key] = now
                    if (fire) {
                        lastRepeat[key] = now
                        robot.keyPress(key)
                        robot.keyRelease(key)
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.error("Manette", t)
        } finally {
            runCatching { manager.quitSDLGamepad() }
        }
    }
}
