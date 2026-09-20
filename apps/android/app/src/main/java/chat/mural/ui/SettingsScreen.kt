package chat.mural.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.mural.R
import java.util.Locale

/** Account, notices and the version. Rooms have nothing to configure yet. */
@Composable
fun SettingsScreen(onAccount: (() -> Unit)? = null, onDismiss: () -> Unit = {}) {
    var notices by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    Column(Modifier.fillMaxSize()) {
        SettingsSheetHeader(stringResource(R.string.settings_title), onDismiss)
        LazyColumn(Modifier.weight(1f).testTag("settings-screen"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (onAccount != null) item {
                SettingsGroup {
                    SettingsRow(stringResource(R.string.account_title), symbol = SettingsSymbol.ACCOUNT, chevron = true,
                        modifier = Modifier.testTag("managed-account-settings"), onClick = onAccount)
                }
            }
            // The app speaks the phone's language. Android 13 and later can also set it for this app alone.
            if (Build.VERSION.SDK_INT >= 33) item {
                SettingsGroup(footer = stringResource(R.string.settings_language_footer)) {
                    SettingsRow(stringResource(R.string.settings_language), Locale.getDefault().displayLanguage.replaceFirstChar { it.uppercase() },
                        chevron = true, modifier = Modifier.testTag("settings-language"), onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            }
                        })
                }
            }
            item {
                SettingsGroup {
                    SettingsRow(stringResource(R.string.settings_open_source_notices), chevron = true, onClick = { notices = true })
                    SettingsDivider()
                    Text(stringResource(R.string.settings_app_version_footer, version), style = MaterialTheme.typography.bodySmall,
                        color = MuralColors.Secondary, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
    if (notices) NoticesDialog { notices = false }
}
