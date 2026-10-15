package com.marco.obsremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marco.obsremote.data.ConnectionSettings
import com.marco.obsremote.obs.AudioInput
import com.marco.obsremote.obs.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObsRemoteApp(viewModel: ObsViewModel = viewModel()) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var editingSettings by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBS Remote") },
                actions = {
                    ConnectionBadge(connection)
                    IconButton(onClick = { editingSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (connection is ConnectionState.Connected) {
                ConnectedContent(viewModel)
            } else {
                ConnectionScreen(
                    settings = settings,
                    state = connection,
                    onConnect = { host, port, password ->
                        viewModel.connect(
                            settings.copy(host = host, port = port, password = password)
                        )
                    }
                )
            }
        }
    }

    if (editingSettings) {
        SettingsDialog(
            settings = settings,
            onDismiss = { editingSettings = false },
            onDisconnect = {
                editingSettings = false
                viewModel.disconnect()
            },
            onSave = { updated ->
                editingSettings = false
                viewModel.connect(updated)
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Indicador de estado
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionBadge(state: ConnectionState) {
    val color: Color = when (state) {
        is ConnectionState.Connected -> Color(0xFF2E7D32)
        is ConnectionState.Connecting -> Color(0xFFF9A825)
        is ConnectionState.Error -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> Color(0xFF9E9E9E)
    }
    val label = when (state) {
        is ConnectionState.Connected -> "Conectado"
        is ConnectionState.Connecting -> "Conectando"
        is ConnectionState.Error -> "Error"
        ConnectionState.Disconnected -> "Sin conexion"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------------------
// Pantalla de conexion
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionScreen(
    settings: ConnectionSettings,
    state: ConnectionState,
    onConnect: (String, Int, String) -> Unit
) {
    var host by rememberSaveable { mutableStateOf(settings.host) }
    var portText by rememberSaveable { mutableStateOf(settings.port.toString()) }
    var password by rememberSaveable { mutableStateOf(settings.password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    // Cuando terminan de cargarse los ajustes guardados, rellenamos los campos.
    LaunchedEffect(settings) {
        host = settings.host
        portText = settings.port.toString()
        password = settings.password
    }

    val port = portText.toIntOrNull()
    val canConnect = host.isNotBlank() && port != null && state !is ConnectionState.Connecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Conectar con OBS", style = MaterialTheme.typography.headlineSmall)

        Text(
            "En OBS abre Herramientas > Configuracion del servidor WebSocket. " +
                "Ahi encontraras la IP y la contrasena, y podras activar el servidor si esta apagado.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("IP del PC") },
            placeholder = { Text("192.168.1.50") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Puerto") },
            placeholder = { Text("4455") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contrasena") },
            singleLine = true,
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Ocultar" else "Ver")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (state is ConnectionState.Error) {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = {
                onConnect(
                    host.trim(),
                    port ?: ConnectionSettings.DEFAULT_PORT,
                    password
                )
            },
            enabled = canConnect,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state is ConnectionState.Connecting) "Conectando..." else "Conectar")
        }

        if (state is ConnectionState.Connecting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------------------
// Contenido conectado: Escenas | Audio
// ---------------------------------------------------------------------------

@Composable
private fun ConnectedContent(viewModel: ObsViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val refreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Escenas") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Audio") }
            )
        }

        if (refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when (selectedTab) {
            0 -> ScenesTab(viewModel)
            else -> AudioTab(viewModel)
        }
    }
}

@Composable
private fun ScenesTab(viewModel: ObsViewModel) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val current by viewModel.currentScene.collectAsStateWithLifecycle()

    if (scenes.isEmpty()) {
        EmptyState("Todavia no hay escenas.\nComprueba que OBS este abierto y conectado.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(scenes, key = { it }) { scene ->
            SceneTile(
                name = scene,
                active = scene == current,
                onClick = { viewModel.selectScene(scene) }
            )
        }
    }
}

@Composable
private fun SceneTile(name: String, active: Boolean, onClick: () -> Unit) {
    val colors = if (active) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    } else {
        CardDefaults.cardColors()
    }

    Card(
        colors = colors,
        modifier = Modifier
            .height(80.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AudioTab(viewModel: ObsViewModel) {
    val audioInputs by viewModel.audioInputs.collectAsStateWithLifecycle()

    if (audioInputs.isEmpty()) {
        EmptyState("No se encontraron fuentes de audio.")
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        audioInputs.forEach { input ->
            AudioRow(
                input = input,
                onToggleMute = { viewModel.toggleMute(input) },
                onVolumeChange = { viewModel.setVolume(input.name, it) }
            )
        }
    }
}

@Composable
private fun AudioRow(
    input: AudioInput,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = input.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = onToggleMute) {
                    Text(if (input.muted) "Silenciado" else "Activo")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = input.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${(input.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogo de ajustes
// ---------------------------------------------------------------------------

@Composable
private fun SettingsDialog(
    settings: ConnectionSettings,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit,
    onSave: (ConnectionSettings) -> Unit
) {
    var host by remember { mutableStateOf(settings.host) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var password by remember { mutableStateOf(settings.password) }
    var autoConnect by remember { mutableStateOf(settings.autoConnect) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conexion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP del PC") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Puerto") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contrasena") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { autoConnect = !autoConnect }
                ) {
                    Checkbox(
                        checked = autoConnect,
                        onCheckedChange = { autoConnect = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Conectar automaticamente al abrir")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        settings.copy(
                            host = host.trim(),
                            port = portText.toIntOrNull() ?: ConnectionSettings.DEFAULT_PORT,
                            password = password,
                            autoConnect = autoConnect
                        )
                    )
                }
            ) {
                Text("Guardar y conectar")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDisconnect) { Text("Desconectar") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}
