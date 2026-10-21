package info.varden.hauk.system.preferences.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import info.varden.hauk.ui.theme.HaukTheme

/**
 * Settings activity that allows the user to change app preferences.
 *
 * @author Marius Lindvall
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = SettingsStore(this)
        setContent {
            HaukTheme {
                SettingsScreen(store, onNavigateUp = ::finish)
            }
        }
    }
}
