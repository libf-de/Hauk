package info.varden.hauk.global.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.ui.theme.HaukIcons
import info.varden.hauk.ui.theme.HaukTheme

/**
 * Activity that is displayed to prompt the user to authorize a broadcast receiver source.
 *
 * @author Marius Lindvall
 */
class AuthorizationActivity : AppCompatActivity() {
    /**
     * A string passed along with the broadcast intent to identify the source of the broadcast.
     */
    private var identifier: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        this.identifier = intent.getStringExtra(Constants.EXTRA_BROADCAST_AUTHORIZATION_IDENTIFIER)

        setContent {
            HaukTheme {
                AuthorizationScreen(
                    identifier = this.identifier.orEmpty(),
                    onAccept = { savePreference(true) },
                    onDeny = { savePreference(false) },
                )
            }
        }
    }

    /**
     * Saves the user's preference on whether or not the given source should be approved and closes
     * the activity.
     *
     * @param preference Whether or not the source was authorized.
     */
    private fun savePreference(preference: Boolean) {
        val prefs = getSharedPreferences(Constants.SHARED_PREFS_AUTHORIZATIONS, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(this@AuthorizationActivity.identifier, preference) }
        finish()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuthorizationScreen(identifier: String, onAccept: () -> Unit, onDeny: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(32.dp))
                Icon(
                    HaukIcons.Policy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    stringResource(R.string.authorize_header),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.authorize_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                // The identifier of the broadcast source.
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            identifier,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                Text(
                    stringResource(R.string.authorize_question),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))

                val height = ButtonDefaults.MediumContainerHeight
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    FilledTonalButton(
                        onClick = onDeny,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        modifier = Modifier.weight(1F).heightIn(min = height),
                    ) {
                        Text(stringResource(R.string.btn_no), style = ButtonDefaults.textStyleFor(height))
                    }
                    Button(
                        onClick = onAccept,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        modifier = Modifier.weight(1F).heightIn(min = height),
                    ) {
                        Text(stringResource(R.string.btn_yes), style = ButtonDefaults.textStyleFor(height))
                    }
                }
            }
        }
    }
}
