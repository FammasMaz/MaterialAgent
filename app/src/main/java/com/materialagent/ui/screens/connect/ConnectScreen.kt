package com.materialagent.ui.screens.connect

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialagent.core.AuthMode
import com.materialagent.core.HermesUrl
import com.materialagent.data.ConnectionStatus
import com.materialagent.data.HapticCue
import com.materialagent.data.ServerProfile
import com.materialagent.ui.AgentViewModel
import com.materialagent.ui.components.AgentMark
import com.materialagent.ui.components.ErrorBanner
import com.materialagent.ui.components.MetaPill
import com.materialagent.ui.components.SectionHeader
import com.materialagent.ui.rememberCue
import com.materialagent.ui.theme.ExpressiveMotion
import java.util.UUID

/**
 * First-run setup, and the same screen later for adding or fixing a server.
 *
 * The brief: someone who has just started `hermes serve` should get in with the
 * one thing they already have — the address and the token it printed. Everything
 * else is optional or explained in place, and the hero shrinks out of the way as
 * the form scrolls up behind the keyboard.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectScreen(
    app: AgentViewModel,
    onDone: () -> Unit,
) {
    val cue = rememberCue()
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()

    var address by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(AuthMode.TOKEN) }
    var token by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }

    val status by app.status.collectAsStateWithLifecycle()
    val problem by app.lastProblem.collectAsStateWithLifecycle()
    val profiles by app.profiles.collectAsStateWithLifecycle()

    val collapse = (scroll.value / 320f).coerceIn(0f, 1f)
    val markSize by animateDpAsState(
        targetValue = (96 - 36 * collapse).dp,
        animationSpec = ExpressiveMotion.Specs.cornerRadius,
        label = "markSize",
    )
    val heroAlpha by animateFloatAsState(
        targetValue = 1f - 0.55f * collapse,
        animationSpec = ExpressiveMotion.Specs.alpha,
        label = "heroAlpha",
    )

    val normalized = remember(address) { HermesUrl.normalize(address) }
    val addressValid = normalized != null
    val canSubmit = addressValid && !connecting &&
        when (authMode) {
            AuthMode.TOKEN -> token.isNotBlank()
            AuthMode.PASSWORD -> password.isNotBlank()
        }

    LaunchedEffect(status, connecting) {
        if (!connecting) return@LaunchedEffect
        when (val current = status) {
            is ConnectionStatus.Connected -> {
                connecting = false
                cue(HapticCue.TURN_COMPLETE)
                onDone()
            }

            is ConnectionStatus.Failed -> {
                connecting = false
                cue(HapticCue.TURN_FAILED)
            }

            else -> Unit
        }
    }

    fun submit() {
        val base = normalized ?: return
        focus.clearFocus()
        connecting = true
        cue(HapticCue.SENT)
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            name = label.ifBlank { base.substringAfter("://").substringBefore("/") },
            baseUrl = base,
            authMode = authMode,
            username = username,
        )
        app.saveAndConnect(profile, if (authMode == AuthMode.TOKEN) token else password)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Insets first, then scroll: the viewport itself shrinks when the
            // keyboard opens, so the focused field and the Connect button can
            // always be scrolled into view on a short screen.
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(markSize * 2.05f)
                    .background(
                        brush = Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f * heroAlpha),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f * heroAlpha),
                                Color.Transparent,
                            ),
                        ),
                        shape = RoundedCornerShape(50),
                    ),
            )
            AgentMark(size = markSize, sheen = true, gradient = true)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "MaterialAgent",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Your Hermes agent, on your phone. Point it at the server and talk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )

        Spacer(Modifier.height(18.dp))

        problem?.let { message ->
            ErrorBanner(
                message = message,
                onDismiss = { app.dismissProblem() },
                onRetry = if (profiles.isNotEmpty()) app::retry else null,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Server", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.27:9119") },
                    singleLine = true,
                    isError = address.isNotBlank() && !addressValid,
                    supportingText = {
                        Text(
                            when {
                                address.isBlank() -> "Host and port where `hermes serve` is listening."
                                addressValid -> normalized.orEmpty()
                                else -> "That does not look like an address I can dial."
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Nickname (optional)") },
                    placeholder = { Text("Home server") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Verified, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = authMode == AuthMode.TOKEN,
                        onClick = { authMode = AuthMode.TOKEN },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Access token") }
                    SegmentedButton(
                        selected = authMode == AuthMode.PASSWORD,
                        onClick = { authMode = AuthMode.PASSWORD },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Password") }
                }

                if (authMode == AuthMode.TOKEN) {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Access token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                        supportingText = { Text("The token your server printed when it started.") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Button(
                    onClick = { submit() },
                    enabled = canSubmit,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (connecting) {
                        LoadingIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Connecting…")
                    } else {
                        Text("Connect", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Text(
                    text = "The token path only works on loopback or through a tunnel. For a " +
                        "server exposed to the internet, sign in with the dashboard password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (profiles.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionHeader("Saved servers")
            profiles.forEach { profile ->
                SavedServerRow(
                    profile = profile,
                    active = (status as? ConnectionStatus.Connected)?.profile?.id == profile.id,
                    onConnect = {
                        cue(HapticCue.SENT)
                        connecting = true
                        app.connect(profile)
                    },
                    onForget = { app.forget(profile) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SavedServerRow(
    profile: ServerProfile,
    active: Boolean,
    onConnect: () -> Unit,
    onForget: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = ExpressiveMotion.Specs.color,
        label = "savedContainer",
    )
    Surface(
        onClick = onConnect,
        shape = RoundedCornerShape(22.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = profile.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaPill(
                        text = if (profile.isPasswordAuth) "Password" else "Token",
                        icon = if (profile.isPasswordAuth) Icons.Rounded.Lock else Icons.Rounded.Key,
                    )
                    if (HermesUrl.isLoopback(profile.baseUrl)) {
                        MetaPill(text = "Loopback", icon = Icons.Rounded.Verified)
                    }
                    if (active) MetaPill(text = "Connected", icon = Icons.Rounded.Verified)
                }
            }
            IconButton(onClick = onForget) {
                Icon(Icons.Rounded.Delete, contentDescription = "Forget ${profile.name}")
            }
        }
    }
}
