package info.varden.hauk.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.system.preferences.PreferenceManager
import info.varden.hauk.ui.formatDuration
import info.varden.hauk.utils.TimeUtils
import java.util.Locale

/**
 * Lets the driver pick the duration of the next share. The duration is saved to the same
 * preference that the phone app uses.
 */
internal class DurationScreen(carContext: CarContext) : Screen(carContext) {
    private data class Duration(val amount: Int, val unit: Int) {
        val seconds: Int
            get() = try {
                TimeUtils.timeUnitsToSeconds(this.amount, this.unit)
            } catch (ex: ArithmeticException) {
                Int.MAX_VALUE
            }
    }

    override fun onGetTemplate(): Template {
        val prefs = PreferenceManager(carContext)
        val current = Duration(prefs.get(Constants.PREF_DURATION), prefs.get(Constants.PREF_DURATION_UNIT))
        val options = durationOptions(current)

        val list = ItemList.Builder()
        for (option in options) {
            list.addItem(Row.Builder().setTitle(formatDuration(option.amount, option.unit, Locale.getDefault())).build())
        }
        list.setSelectedIndex(options.indexOf(current))
        list.setOnSelectedListener { index ->
            val selected = options[index]
            prefs.set(Constants.PREF_DURATION, selected.amount)
            prefs.set(Constants.PREF_DURATION_UNIT, selected.unit)
            screenManager.pop()
        }

        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setHeader(Header.Builder().setTitle(carContext.getString(R.string.car_duration_title)).setStartHeaderAction(Action.BACK).build())
            .build()
    }

    /**
     * Returns the preset durations plus the currently saved duration, shortest first, limited to
     * the number of rows the host can display.
     */
    private fun durationOptions(current: Duration): List<Duration> {
        // The saved duration replaces an equivalent preset, e.g. 60 minutes replaces 1 hour.
        val options = PRESETS.filter { it.seconds != current.seconds }.plus(current).sortedBy { it.seconds }.toMutableList()

        // Drop presets from the long end if the host cannot show all rows, but keep the saved
        // duration so that it remains selected.
        val limit = if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } else {
            options.size
        }
        while (options.size > limit) {
            options.removeAt(options.indexOfLast { it != current })
        }
        return options
    }

    private companion object {
        val PRESETS = listOf(
            Duration(15, Constants.DURATION_UNIT_MINUTES),
            Duration(30, Constants.DURATION_UNIT_MINUTES),
            Duration(1, Constants.DURATION_UNIT_HOURS),
            Duration(2, Constants.DURATION_UNIT_HOURS),
            Duration(4, Constants.DURATION_UNIT_HOURS),
            Duration(8, Constants.DURATION_UNIT_HOURS),
        )
    }
}
