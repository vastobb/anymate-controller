package com.anymate.controller.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anymate.controller.ChatLine
import com.anymate.controller.ControllerViewModel
import com.anymate.controller.LineRole
import com.anymate.controller.ui.theme.AccentGradient
import com.anymate.controller.ui.theme.Cyan
import com.anymate.controller.ui.theme.Danger
import com.anymate.controller.ui.theme.Hairline
import com.anymate.controller.ui.theme.Ink700
import com.anymate.controller.ui.theme.Ink800
import com.anymate.controller.ui.theme.Ink850
import com.anymate.controller.ui.theme.Ink900
import com.anymate.controller.ui.theme.Ink950
import com.anymate.controller.ui.theme.Mono
import com.anymate.controller.ui.theme.Success
import com.anymate.controller.ui.theme.TextMuted
import com.anymate.controller.ui.theme.TextPrimary
import com.anymate.controller.ui.theme.TextSecondary
import com.anymate.controller.ui.theme.Violet

private enum class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CHAT("Director", Icons.Rounded.AutoAwesome),
    CONSOLE("Console", Icons.Rounded.Terminal),
    FILES("Files", Icons.Rounded.FolderOpen),
    SETTINGS("Settings", Icons.Rounded.Settings)
}

@Composable
fun ControllerScreen(vm: ControllerViewModel) {
    var tab by remember { mutableStateOf(Tab.CHAT) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF101116), Ink950))
            )
    ) {
        // Navigation rail
        Column(
            modifier = Modifier
                .width(78.dp)
                .fillMaxHeight()
                .background(Ink900)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Bolt, null, tint = Color(0xFF0B0714), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(6.dp))
            Tab.entries.forEach { entry ->
                val selected = tab == entry
                Column(
                    modifier = Modifier
                        .width(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Violet.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { tab = entry }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        entry.icon, null,
                        tint = if (selected) Violet else TextMuted,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        entry.label,
                        color = if (selected) TextPrimary else TextMuted,
                        fontSize = 8.sp,
                        letterSpacing = 0.4.sp
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Status bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Ink900)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (vm.connected) Success else TextMuted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    vm.status,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = Mono,
                    modifier = Modifier.weight(1f)
                )
                ModelChip(vm)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${vm.registry.all().size} tools",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = Mono
                )
                Spacer(Modifier.width(10.dp))
                SmallButton(
                    label = if (vm.connected) "Connected" else "Connect",
                    icon = Icons.Rounded.Hub,
                    active = vm.connected,
                    onClick = {
                        vm.saveSettings()
                        vm.connect()
                    }
                )
            }

            when (tab) {
                Tab.CHAT -> ChatPane(vm)
                Tab.CONSOLE -> ConsolePane(vm)
                Tab.FILES -> FilesPane(vm)
                Tab.SETTINGS -> SettingsPane(vm)
            }
        }

        if (vm.connected) {
            PreviewPane(vm, Modifier.width(300.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun ChatPane(vm: ControllerViewModel) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.chat.size, vm.thinking) {
        if (vm.chat.isNotEmpty()) {
            listState.animateScrollToItem(vm.chat.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(vm.chat, key = { it.id }) { line -> ChatRow(line) }
        }
        if (vm.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                vm.attachments.forEachIndexed { index, attachment ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink800)
                            .border(1.dp, Hairline, RoundedCornerShape(10.dp))
                            .clickable { vm.removeAttachment(index) }
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Image, null, tint = Cyan, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            attachment.label.take(18),
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                SmallButton(label = "Clear", onClick = vm::clearAttachments)
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallButton(label = "Image", icon = Icons.Rounded.Image, onClick = { picker = "image" })
            SmallButton(label = "Video", icon = Icons.Rounded.Movie, onClick = { picker = "video" })
            SmallButton(label = "Canvas", icon = Icons.Rounded.Hub, onClick = { vm.attachCanvas() })
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ComposerField(
                value = input,
                onValueChange = { input = it },
                placeholder = if (vm.thinking) "Working…" else "Ask the director to animate something…",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (vm.thinking || input.isBlank()) Brush.linearGradient(listOf(Ink700, Ink700)) else AccentGradient)
                    .clickable(enabled = !vm.thinking && input.isNotBlank()) {
                        vm.send(input)
                        input = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = "Send",
                    tint = if (vm.thinking || input.isBlank()) TextMuted else Color(0xFF0B0714),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    picker?.let { kind ->
        FilePickerDialog(vm, kind) { picker = null }
    }
}

@Composable
private fun ChatRow(line: ChatLine) {
    when (line.role) {
        LineRole.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF3F2A7A), Color(0xFF2A1E52))))
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text(line.text, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        LineRole.AGENT -> Row(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AccentGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFF0B0714), modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .background(Ink800)
                    .border(1.dp, Hairline, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text(line.text, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        LineRole.TOOL -> Text(
            "  ${line.text}",
            color = Cyan,
            fontFamily = Mono,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 32.dp)
        )
        LineRole.SYSTEM -> Text(
            line.text,
            color = TextMuted,
            fontSize = 11.sp,
            fontFamily = Mono,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
private fun ConsolePane(vm: ControllerViewModel) {
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(vm.consoleText) {
        scroll.scrollTo(scroll.maxValue)
    }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF07070A))
                .border(1.dp, Hairline, RoundedCornerShape(14.dp))
                .padding(12.dp)
                .verticalScroll(scroll)
        ) {
            Text(
                vm.consoleText.ifEmpty {
                    "AnyMate Controller console\n" +
                        "python  ·  py <code>   run Python on device\n" +
                        "ls / cat / mkdir / grep / tree   file commands\n" +
                        "mcp anymate_get_state {}         call a tool on the phone\n" +
                        "help                            every command\n"
                },
                color = TextSecondary,
                fontFamily = Mono,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            ComposerField(
                value = input,
                onValueChange = { input = it },
                placeholder = "sh, py, mcp …",
                mono = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (input.isBlank()) Brush.linearGradient(listOf(Ink700, Ink700)) else AccentGradient)
                    .clickable(enabled = input.isNotBlank()) {
                        vm.runConsole(input)
                        input = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ArrowUpward, "Run",
                    tint = if (input.isBlank()) TextMuted else Color(0xFF0B0714),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "help" to "help",
                "tree" to "tree",
                "py print(6*7)" to "python",
                "mcp anymate_get_state {}" to "mcp"
            ).forEach { (command, label) ->
                SmallButton(label = label, onClick = { vm.runConsole(command) })
            }
        }
    }
}

@Composable
private fun FilesPane(vm: ControllerViewModel) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Workspace")
            Spacer(Modifier.weight(1f))
            SmallButton(label = "Refresh", onClick = vm::refreshFiles)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(vm.files) { _, path ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.readFile(path) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        path,
                        color = TextSecondary,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Text(
            vm.workspace.absolutePath,
            color = TextMuted,
            fontFamily = Mono,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun SettingsPane(vm: ControllerViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle("AnyMate device")
        SettingField(
            label = "MCP URL",
            value = vm.anymateUrl,
            onValueChange = { vm.anymateUrl = it },
            placeholder = "http://192.168.1.20:8420/mcp"
        )
        SettingField(
            label = "Bearer token",
            value = vm.anymateToken,
            onValueChange = { vm.anymateToken = it },
            placeholder = "am_…"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(label = "Save & connect", icon = Icons.Rounded.Wifi, onClick = {
                vm.saveSettings()
                vm.connect()
            })
        }

        SectionTitle("AI agent")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(label = "OpenAI", onClick = {
                applyEndpointPreset(vm, "https://api.openai.com/v1", "gpt-4o-mini")
            })
            SmallButton(label = "NVIDIA", onClick = {
                applyEndpointPreset(vm, "https://integrate.api.nvidia.com/v1", "meta/llama-3.1-8b-instruct")
            })
            SmallButton(label = "OpenRouter", onClick = {
                applyEndpointPreset(vm, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini")
            })
            SmallButton(label = "Groq", onClick = {
                applyEndpointPreset(vm, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
            })
            SmallButton(label = "Ollama", onClick = {
                applyEndpointPreset(vm, "http://127.0.0.1:11434/v1", "llama3.2")
            })
        }
        SettingField(
            label = "Endpoint",
            value = vm.modelEndpoint,
            onValueChange = { vm.modelEndpoint = it },
            placeholder = "https://api.openai.com/v1"
        )
        SettingField(
            label = "Model",
            value = vm.modelName,
            onValueChange = { vm.modelName = it },
            placeholder = "gpt-4o-mini"
        )
        SettingField(
            label = "API key",
            value = vm.apiKey,
            onValueChange = { vm.apiKey = it },
            placeholder = "sk-… (stored on this device)"
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (vm.sendImages) Violet else Ink700)
                    .clickable {
                        vm.sendImages = !vm.sendImages
                        vm.saveSettings()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (vm.sendImages) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFF0B0714), modifier = Modifier.size(11.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("Send tool images back to the model (vision)", color = TextSecondary, fontSize = 12.sp)
        }
        if (com.anymate.controller.agent.ModelCatalog.isNvidia(vm.modelEndpoint)) {
            Text(
                "NVIDIA lists every model on /v1/models, but chat only works for models your " +
                    "organisation is entitled to. If a model returns \"Function … not found for " +
                    "account\", press Find working model.",
                color = TextMuted,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton(label = "Fetch models", icon = Icons.Rounded.Refresh, onClick = { vm.fetchModels() })
            SmallButton(label = "Find working model", icon = Icons.Rounded.Bolt, onClick = { vm.findWorkingModel() })
            SmallButton(label = "Test endpoint", icon = Icons.Rounded.NetworkCheck, onClick = { vm.testEndpoint() })
            SmallButton(label = "Save agent settings", onClick = { vm.saveSettings() })
        }
        if (vm.probeBusy) {
            Text("Probing models…", color = TextMuted, fontSize = 11.sp)
        }
        vm.modelProbe?.let {
            Text(
                it,
                color = if (it.startsWith("Found")) Success else Amber,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        if (vm.modelsLoading) {
            Text("Fetching models…", color = TextMuted, fontSize = 11.sp)
        }
        if (vm.endpointTesting) {
            Text("Testing the endpoint…", color = TextMuted, fontSize = 11.sp)
        }
        vm.endpointTest?.let {
            Text(
                it,
                color = if (it.startsWith("Endpoint OK")) Success else Danger,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        vm.modelsError?.let {
            Text(it, color = Danger, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (vm.models.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                vm.models.take(40).forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.updateAgentModel(model) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (model == vm.modelName) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = Cyan, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(model, color = TextSecondary, fontSize = 11.sp, fontFamily = Mono)
                    }
                }
                if (vm.models.size > 40) {
                    Text(
                        "+${vm.models.size - 40} more — type a name above",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }

        SectionTitle("Toolbox")
        vm.registry.groups().forEach { (group, tools) ->
            Column {
                Text(
                    group.uppercase(),
                    color = TextMuted,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))
                tools.forEach { tool ->
                    Text(
                        "• ${tool.name} — ${tool.description.take(70)}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun PreviewPane(vm: ControllerViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Ink900)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionTitle("AnyMate canvas")
            Spacer(Modifier.weight(1f))
            LiveDot()
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0B0B10))
                .border(1.dp, Hairline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val image = vm.preview
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "AnyMate canvas",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
            } else {
                Text("waiting for the phone…", color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FilePickerDialog(
    vm: ControllerViewModel,
    kind: String,
    onDismiss: () -> Unit
) {
    val extensions = if (kind == "video") setOf("mp4", "mov", "mkv", "webm", "3gp", "avi") else setOf("png", "jpg", "jpeg", "webp", "bmp")
    val files = vm.files.filter { path ->
        val extension = path.substringAfterLast('.', "").lowercase()
        extension in extensions
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(18.dp),
            color = Ink850,
            border = androidx.compose.foundation.BorderStroke(1.dp, Hairline),
            modifier = Modifier.width(420.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (kind == "video") "Attach a video" else "Attach an image",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                if (files.isEmpty()) {
                    Text(
                        "No ${kind} files in the workspace yet. Put one there with the console or the Files tab.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    LazyColumn(Modifier.height(280.dp)) {
                        items(files) { path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (kind == "video") vm.attachVideo(path) else vm.attachImage(path)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (kind == "video") Icons.Rounded.Movie else Icons.Rounded.Image,
                                    null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(path, color = TextSecondary, fontSize = 11.sp, fontFamily = Mono)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SmallButton(label = "Close", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ModelChip(vm: ControllerViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        SmallButton(
            label = vm.modelName.ifBlank { "model" },
            icon = Icons.Rounded.AutoAwesome,
            onClick = { open = true }
        )
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Ink800)
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Fetch models from the endpoint", color = TextPrimary, fontSize = 12.sp) },
                onClick = {
                    open = false
                    vm.fetchModels()
                }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Find a working model", color = TextPrimary, fontSize = 12.sp) },
                onClick = {
                    open = false
                    vm.findWorkingModel()
                }
            )
            vm.models.forEach { model ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(model, color = TextSecondary, fontSize = 11.sp, fontFamily = Mono) },
                    onClick = {
                        vm.updateAgentModel(model)
                        open = false
                    }
                )
            }
        }
    }
}

// ---- small components ------------------------------------------------------
@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = TextMuted,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun SmallButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Violet.copy(alpha = 0.16f) else Ink800)
            .border(1.dp, if (active) Violet.copy(alpha = 0.4f) else Hairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (active) Violet else TextSecondary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = if (active) TextPrimary else TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Ink850)
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = TextMuted, fontSize = 13.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Violet),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)
        )
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = ""
) {
    Column {
        Text(label.uppercase(), color = TextMuted, fontSize = 9.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Ink850)
                .border(1.dp, Hairline, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            if (value.isEmpty()) {
                Text(placeholder, color = TextMuted.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = Mono),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Violet),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }
    }
}

@Composable
private fun LiveDot() {
    Box(Modifier.size(6.dp).clip(CircleShape).background(Success))
}



private fun applyEndpointPreset(vm: ControllerViewModel, url: String, model: String) {
    vm.modelEndpoint = url
    if (vm.modelName.isBlank() || !vm.modelName.contains("/")) vm.modelName = model
}
