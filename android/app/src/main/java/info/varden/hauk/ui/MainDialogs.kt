package info.varden.hauk.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import info.varden.hauk.Constants
import info.varden.hauk.R
import info.varden.hauk.dialog.HaukDialog
import info.varden.hauk.dialog.HostedDialog
import info.varden.hauk.ui.theme.HaukIcons

/**
 * Dialog that prompts the user for whether a new sharing link should be adoptable.
 *
 * @param onExplainAdoption Called when the user asks what adoption is.
 * @param onCreate          Called with the user's adoption choice when the link should be created.
 */
internal class CreateLinkDialog(
    allowAdoption: Boolean,
    private val onExplainAdoption: () -> Unit,
    private val onCreate: (allowAdoption: Boolean) -> Unit,
) : HostedDialog() {
    private var allowAdoption by mutableStateOf(allowAdoption)

    @Composable
    override fun Content() {
        HaukDialog(
            title = stringResource(R.string.create_link_title),
            icon = HaukIcons.AddLink,
            buttons = {
                TextButton(onClick = ::dismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
                TextButton(onClick = {
                    dismiss()
                    this@CreateLinkDialog.onCreate(this@CreateLinkDialog.allowAdoption)
                }) {
                    Text(stringResource(R.string.btn_create))
                }
            },
        ) {
            Text(stringResource(R.string.create_link_body))
            Spacer(Modifier.height(16.dp))
            AdoptionSwitch(
                checked = this@CreateLinkDialog.allowAdoption,
                onCheckedChange = { this@CreateLinkDialog.allowAdoption = it },
                onExplain = this@CreateLinkDialog.onExplainAdoption,
            )
        }
    }
}

/**
 * Dialog with two input boxes for adopting another share into a group share.
 *
 * @param serverURL The URL of the Hauk backend, shown as an example of a share link.
 * @param onAdopt   Called with the share ID and nickname entered by the user.
 */
internal class AdoptDialog(
    private val serverURL: String,
    private val onAdopt: (adoptID: String, nickname: String) -> Unit,
) : HostedDialog() {
    private var shareID by mutableStateOf("")
    private var nickname by mutableStateOf("")

    /**
     * Replaces the entered text with the corresponding link ID if a view link is entered or pasted
     * into the share ID box.
     */
    private fun onShareIDChanged(value: String) {
        val match = LINK_ID_PATTERN.find(value)
        this.shareID = if (match != null) match.groupValues[1] else value
    }

    @Composable
    override fun Content() {
        HaukDialog(
            title = stringResource(R.string.adopt_title),
            icon = HaukIcons.CallMerge,
            buttons = {
                TextButton(onClick = ::dismiss) {
                    Text(stringResource(R.string.btn_cancel))
                }
                TextButton(
                    enabled = this@AdoptDialog.shareID.isNotBlank() && this@AdoptDialog.nickname.isNotBlank(),
                    onClick = {
                        dismiss()
                        this@AdoptDialog.onAdopt(this@AdoptDialog.shareID.trim(), this@AdoptDialog.nickname.trim())
                    },
                ) {
                    Text(stringResource(R.string.btn_ok))
                }
            },
        ) {
            Text(stringResource(R.string.adopt_body, this@AdoptDialog.serverURL))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = this@AdoptDialog.shareID,
                onValueChange = ::onShareIDChanged,
                label = { Text(stringResource(R.string.label_share_url).asLabel()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = this@AdoptDialog.nickname,
                onValueChange = { this@AdoptDialog.nickname = it },
                label = { Text(stringResource(R.string.label_nickname).asLabel()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private companion object {
        val LINK_ID_PATTERN = Regex(Constants.REGEX_ADOPT_ID_FROM_LINK)
    }
}

/**
 * Converts a form label string such as "Nickname:" into a text field label by removing the trailing
 * colon.
 */
internal fun String.asLabel(): String = trim().trimEnd(':', '：').trim()
