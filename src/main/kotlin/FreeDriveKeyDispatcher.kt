import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

class FreeDriveKeyDispatcher(
    private val isEnabled: () -> Boolean,
    private val onAccelerate: () -> Unit,
    private val onDecelerate: () -> Unit,
    private val onTurnLeft: () -> Unit,
    private val onTurnRight: () -> Unit
) : KeyEventDispatcher {
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!isEnabled() || e.id != KeyEvent.KEY_PRESSED) {
            return false
        }

        return when (e.keyCode) {
            KeyEvent.VK_UP -> {
                onAccelerate()
                true
            }
            KeyEvent.VK_DOWN -> {
                onDecelerate()
                true
            }
            KeyEvent.VK_LEFT -> {
                onTurnLeft()
                true
            }
            KeyEvent.VK_RIGHT -> {
                onTurnRight()
                true
            }
            else -> false
        }
    }
}
