package com.alad.app.presentation.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alad.app.R
import com.alad.app.core.network.GroqDubbingManager
import com.alad.app.data.repository.UserPreferencesRepository
import com.alad.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val apiKey by viewModel.userApiKey.collectAsState()
    val selectedVoice by viewModel.voicePersona.collectAsState()
    val selectedMode by viewModel.dubbingMode.collectAsState()
    val showDualSubtitles by viewModel.showDualSubtitles.collectAsState()
    // FIX22: dubbing provider (Gemini Live / Groq) + Groq settings.
    val dubbingProvider by viewModel.dubbingProvider.collectAsState()
    val groqApiKey by viewModel.groqApiKey.collectAsState()
    val deepseekApiKey by viewModel.deepseekApiKey.collectAsState()
    val groqTranslateModel by viewModel.groqTranslateModel.collectAsState()
    val groqVoiceGender by viewModel.groqVoiceGender.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var apiKeyVisible by remember { mutableStateOf(false) }
    var groqKeyVisible by remember { mutableStateOf(false) }
    var deepseekKeyVisible by remember { mutableStateOf(false) }

    AmbientBackground {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        tint = Color.White
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. API Key Configuration
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.25f
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = stringResource(R.string.gemini_api_key),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "Google AI Studio Live API",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = viewModel::updateApiKey,
                            placeholder = { Text("AIzaSy...", color = TextSecondary.copy(alpha = 0.6f)) },
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0x25FFFFFF),
                                unfocusedContainerColor = Color(0x12FFFFFF),
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            trailingIcon = {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (apiKeyVisible) stringResource(R.string.hide) else stringResource(R.string.show),
                                        tint = if (apiKeyVisible) NeonCyan else TextSecondary
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.api_key_guide),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            NeonCyan.copy(alpha = 0.15f),
                                            NeonPurple.copy(alpha = 0.15f)
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        Brush.horizontalGradient(
                                            listOf(
                                                NeonCyan.copy(alpha = 0.4f),
                                                NeonPurple.copy(alpha = 0.4f)
                                            )
                                        )
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    uriHandler.openUri("https://aistudio.google.com/app/apikey")
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.get_api_key),
                                    color = NeonCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // FIX22: Dubbing Provider Card (Gemini Live / Groq)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonBlue,
                    borderAlpha = 0.25f
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonBlue.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = NeonBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "Nguồn lồng tiếng",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "Gemini Live hoặc Groq (Whisper + AI dịch)",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val providers = listOf(
                            Triple(
                                UserPreferencesRepository.PROVIDER_GEMINI,
                                "Gemini Live",
                                "Nghe + dịch + nói trong một lần gọi"
                            ),
                            Triple(
                                UserPreferencesRepository.PROVIDER_GROQ,
                                "Groq",
                                "Whisper nghe, AI dịch có nhớ ngữ cảnh, giọng máy đọc"
                            )
                        )

                        providers.forEach { (providerId, providerLabel, providerDesc) ->
                            val isSelected = dubbingProvider == providerId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.horizontalGradient(
                                                listOf(NeonBlue.copy(alpha = 0.25f), NeonCyan.copy(alpha = 0.15f))
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f))
                                            )
                                        }
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) NeonCyan else Color.White.copy(alpha = 0.08f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.updateDubbingProvider(providerId) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = providerLabel,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = providerDesc,
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Đổi nguồn lồng tiếng thì dừng phiên đang chạy rồi bắt đầu lại để áp dụng.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                }

                // FIX22: Groq Settings Card (keys, translate model, voice)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonPurple,
                    borderAlpha = 0.25f
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonPurple.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = NeonPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "Groq — Nghe & Dịch",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "Whisper + GPT-OSS / Qwen / DeepSeek",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Key Groq (bắt buộc khi dùng Groq)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = groqApiKey,
                            onValueChange = viewModel::updateGroqApiKey,
                            placeholder = { Text("gsk_...", color = TextSecondary.copy(alpha = 0.6f)) },
                            visualTransformation = if (groqKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0x25FFFFFF),
                                unfocusedContainerColor = Color(0x12FFFFFF),
                                focusedBorderColor = NeonPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            trailingIcon = {
                                IconButton(onClick = { groqKeyVisible = !groqKeyVisible }) {
                                    Icon(
                                        imageVector = if (groqKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (groqKeyVisible) stringResource(R.string.hide) else stringResource(R.string.show),
                                        tint = if (groqKeyVisible) NeonPurple else TextSecondary
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Key Groq miễn phí (không cần thẻ): Whisper nghe tiếng, model chat dịch. Lấy tại console.groq.com.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            NeonPurple.copy(alpha = 0.15f),
                                            NeonBlue.copy(alpha = 0.15f)
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        Brush.horizontalGradient(
                                            listOf(
                                                NeonPurple.copy(alpha = 0.4f),
                                                NeonBlue.copy(alpha = 0.4f)
                                            )
                                        )
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    uriHandler.openUri("https://console.groq.com/keys")
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Lấy key Groq miễn phí",
                                    color = NeonPurple,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = NeonPurple,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Key DeepSeek (chỉ cần khi chọn dịch bằng DeepSeek)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = deepseekApiKey,
                            onValueChange = viewModel::updateDeepseekApiKey,
                            placeholder = { Text("sk-...", color = TextSecondary.copy(alpha = 0.6f)) },
                            visualTransformation = if (deepseekKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0x25FFFFFF),
                                unfocusedContainerColor = Color(0x12FFFFFF),
                                focusedBorderColor = NeonPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            trailingIcon = {
                                IconButton(onClick = { deepseekKeyVisible = !deepseekKeyVisible }) {
                                    Icon(
                                        imageVector = if (deepseekKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (deepseekKeyVisible) stringResource(R.string.hide) else stringResource(R.string.show),
                                        tint = if (deepseekKeyVisible) NeonPurple else TextSecondary
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "DeepSeek dịch hay nhất tiếng Trung (~$0,14/1tr token, nạp tối thiểu $2). DeepSeek lỗi thì tự dịch tạm bằng Groq.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            NeonPurple.copy(alpha = 0.15f),
                                            NeonBlue.copy(alpha = 0.15f)
                                        )
                                    )
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        Brush.horizontalGradient(
                                            listOf(
                                                NeonPurple.copy(alpha = 0.4f),
                                                NeonBlue.copy(alpha = 0.4f)
                                            )
                                        )
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    uriHandler.openUri("https://platform.deepseek.com/api_keys")
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Lấy key DeepSeek",
                                    color = NeonPurple,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = NeonPurple,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Dịch bằng",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        GroqDubbingManager.TRANSLATE_MODELS.forEach { model ->
                            val isSelected = groqTranslateModel == model.key
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.horizontalGradient(
                                                listOf(NeonPurple.copy(alpha = 0.25f), NeonBlue.copy(alpha = 0.15f))
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f))
                                            )
                                        }
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) NeonPurple else Color.White.copy(alpha = 0.08f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.updateGroqTranslateModel(model.key) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = model.label,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonPurple,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Giọng đọc (Groq không nhận biết nhân vật nên dùng một giọng)",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val groqGenders = listOf(
                            Pair(UserPreferencesRepository.GROQ_GENDER_FEMALE, "Giọng nữ — ấm, truyền cảm"),
                            Pair(UserPreferencesRepository.GROQ_GENDER_MALE, "Giọng nam — trầm, chắc")
                        )

                        groqGenders.forEach { (genderId, genderLabel) ->
                            val isSelected = groqVoiceGender == genderId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.horizontalGradient(
                                                listOf(NeonPurple.copy(alpha = 0.25f), NeonBlue.copy(alpha = 0.15f))
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f))
                                            )
                                        }
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) NeonPurple else Color.White.copy(alpha = 0.08f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.updateGroqVoiceGender(genderId) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = genderLabel,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonPurple,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. AI Voice Persona Selection Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonPurple,
                    borderAlpha = 0.25f
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonPurple.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = NeonPurple,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = stringResource(R.string.voice_persona),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = stringResource(R.string.voice_persona_desc),
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val voices = listOf(
                            Triple("Puck", stringResource(R.string.voice_puck), "Energetic"),
                            Triple("Charon", stringResource(R.string.voice_charon), "Deep"),
                            Triple("Aoede", stringResource(R.string.voice_aoede), "Melodic"),
                            Triple("Fenrir", stringResource(R.string.voice_fenrir), "Authoritative"),
                            Triple("Kore", stringResource(R.string.voice_kore), "Calm")
                        )

                        voices.forEach { (voiceId, voiceLabel, badge) ->
                            val isSelected = selectedVoice == voiceId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.horizontalGradient(
                                                listOf(NeonPurple.copy(alpha = 0.25f), NeonBlue.copy(alpha = 0.15f))
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f))
                                            )
                                        }
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) NeonPurple else Color.White.copy(alpha = 0.08f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.updateVoicePersona(voiceId) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = voiceLabel,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }

                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(NeonPurple.copy(alpha = 0.3f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = badge.uppercase(),
                                                color = NeonCyan,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Dual Subtitles Option Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonCyan,
                    borderAlpha = 0.20f,
                    onClick = { viewModel.updateShowDualSubtitles(!showDualSubtitles) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dual_subtitles),
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.dual_subtitles_desc),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Switch(
                            checked = showDualSubtitles,
                            onCheckedChange = { viewModel.updateShowDualSubtitles(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NeonCyan,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }

                // 5. Dubbing Mode Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    borderColor = NeonBlue,
                    borderAlpha = 0.25f
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(NeonBlue.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = NeonBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = stringResource(R.string.dubbing_mode),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "Choose audio, subtitles, or both",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val modes = listOf(
                            Triple(UserPreferencesRepository.MODE_HYBRID, stringResource(R.string.mode_hybrid), Icons.Default.GraphicEq),
                            Triple(UserPreferencesRepository.MODE_AUDIO_ONLY, stringResource(R.string.mode_audio_only), Icons.AutoMirrored.Filled.VolumeUp),
                            Triple(UserPreferencesRepository.MODE_SUBTITLE_ONLY, stringResource(R.string.mode_subtitle_only), Icons.Default.Subtitles)
                        )

                        modes.forEach { (modeId, modeLabel, icon) ->
                            val isSelected = selectedMode == modeId
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) {
                                            Brush.horizontalGradient(
                                                listOf(NeonBlue.copy(alpha = 0.25f), NeonCyan.copy(alpha = 0.15f))
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f))
                                            )
                                        }
                                    )
                                    .border(
                                        BorderStroke(
                                            1.dp,
                                            if (isSelected) NeonCyan else Color.White.copy(alpha = 0.08f)
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.updateDubbingMode(modeId) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (isSelected) NeonCyan else TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = modeLabel,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = NeonCyan,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Save Settings Button
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.98f else 1.0f,
                    label = "save_btn_scale"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .scale(scale)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(NeonCyan, NeonBlue, NeonViolet)
                            )
                        )
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                viewModel.saveSettings()
                                val savedMessage = context.getString(R.string.settings_saved)
                                scope.launch {
                                    try {
                                        snackbarHostState.showSnackbar(savedMessage)
                                    } catch (e: Exception) {
                                        android.util.Log.e("SettingsScreen", "Failed to display snackbar", e)
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.save_settings),
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
