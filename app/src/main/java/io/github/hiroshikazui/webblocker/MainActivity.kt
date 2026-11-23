package io.github.hiroshikazui.webblocker

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hiroshikazui.webblocker.core.Outcome
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val state: BlockerState
        get() = (application as WebBlockerApp).state

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(state, onStartVpn = ::startVpn)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Protection.enforce(this)
        state.refresh()
    }

    private fun startVpn() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else startVpnService()
    }

    private fun startVpnService() {
        startService(Intent(this, BlockerVpnService::class.java))
    }
}

private const val ADB_COMMAND =
    "adb shell dpm set-device-owner io.github.hiroshikazui.webblocker/.AdminReceiver"

@Composable
private fun MainScreen(state: BlockerState, onStartVpn: () -> Unit) {
    val snap by state.snapshot.collectAsState()
    var vpnRunning by remember { mutableStateOf(BlockerVpnService.running) }
    var deviceOwner by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Outcome?>(null) }
    var siteInput by remember { mutableStateOf("") }
    var permanent by remember { mutableStateOf(false) }
    var confirmPermanent by remember { mutableStateOf(false) }
    var startInput by remember(snap.blockStart) { mutableStateOf(snap.blockStart) }
    var endInput by remember(snap.blockEnd) { mutableStateOf(snap.blockEnd) }
    val context = LocalContext.current

    // VPN と保護の状態は OS 側で変わるので定期的に読み直す
    LaunchedEffect(Unit) {
        while (true) {
            vpnRunning = BlockerVpnService.running
            deviceOwner = Protection.isDeviceOwner(context)
            state.refresh()
            delay(2000)
        }
    }

    fun addSites() {
        val outcome = state.add(splitInputs(siteInput), permanent)
        message = outcome
        if (outcome.ok) siteInput = ""
    }

    if (confirmPermanent) {
        AlertDialog(
            onDismissRequest = { confirmPermanent = false },
            title = { Text("恒久ブロックに追加") },
            text = { Text("恒久ブロックに入れたサイトは二度と外せません。追加しますか？\n\n${siteInput.trim()}") },
            confirmButton = {
                TextButton(onClick = { confirmPermanent = false; addSites() }) { Text("追加する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanent = false }) { Text("やめる") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Section("状態") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vpnRunning) "ブロック: 動作中" else "ブロック: 停止中", Modifier.weight(1f))
                    if (!vpnRunning) Button(onClick = onStartVpn) { Text("開始") }
                }
                Text(
                    "時間帯ブロック: ${snap.blockStart}〜${snap.blockEnd}" +
                        if (snap.inBlockHours) " (現在ブロック時間中)" else " (現在は時間外)",
                )
                Text(if (snap.ntpSynced) "時刻: ネットの時刻と同期済み" else "時刻: 未同期 (端末の時計を使用)")
                if (deviceOwner) {
                    Text("保護: 有効 (アンインストール・VPN 解除・初期化・日時変更・アプリのインストールを禁止)")
                } else {
                    Text("保護: 未設定。PC から次のコマンドを実行してください (README 参照)")
                    SelectionContainer { Text(ADB_COMMAND, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (deviceOwner) {
            item {
                Section("アプリの更新") {
                    if (snap.updateRemainingMs > 0) {
                        val minutes = (snap.updateRemainingMs + 59_999) / 60_000
                        Text("更新を許可中: 残り約 $minutes 分。この間はファイルや PC からインストールできます")
                    } else {
                        Text("更新するときは、ブロック時間外にここで許可してください (15 分間)", style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = !snap.inBlockHours,
                            onClick = { message = state.openUpdateWindow() },
                        ) { Text("更新を許可") }
                        if (snap.inBlockHours) Text("ブロック時間中は許可できません", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        message?.let { m ->
            item {
                Text(
                    m.message,
                    color = if (m.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            Section("サイトを追加") {
                OutlinedTextField(
                    value = siteInput,
                    onValueChange = { siteInput = it },
                    label = { Text("ドメイン (例: example.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = permanent, onCheckedChange = { permanent = it })
                    Text("恒久ブロック (二度と外せない)", Modifier.weight(1f))
                    Button(
                        enabled = siteInput.isNotBlank(),
                        onClick = { if (permanent) confirmPermanent = true else addSites() },
                    ) { Text("追加") }
                }
            }
        }
        item {
            Section("ブロック時間の変更") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = { startInput = it },
                        label = { Text("開始") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = { endInput = it },
                        label = { Text("終了") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    enabled = !snap.inBlockHours,
                    onClick = { message = state.setHours(startInput, endInput) },
                ) { Text("変更") }
                if (snap.inBlockHours) Text("ブロック時間中は変更できません", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Heading("時間帯ブロック (${snap.timed.size})") }
        items(snap.timed, key = { "t:$it" }) { site ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(site, Modifier.weight(1f))
                TextButton(
                    enabled = !snap.inBlockHours,
                    onClick = { message = state.remove(listOf(site)) },
                ) { Text("外す") }
            }
        }
        item { Heading("恒久ブロック (${snap.permanent.size})") }
        items(snap.permanent, key = { "p:$it" }) { site -> Text(site) }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** 空白・カンマ・改行区切りで複数入力を受け付ける。 */
private fun splitInputs(text: String): List<String> =
    text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
