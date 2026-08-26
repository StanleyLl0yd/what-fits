package app.whatfits

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import app.whatfits.camera.CameraCapture
import app.whatfits.catalog.CatalogMatcher
import app.whatfits.catalog.CatalogRepository
import app.whatfits.catalog.Device
import app.whatfits.catalog.FitResult
import app.whatfits.catalog.Replacement
import app.whatfits.ocr.ImageLoader
import app.whatfits.ocr.OfflineOcrEngine
import app.whatfits.ui.WhatFitsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WhatFitsTheme {
                WhatFitsApp()
            }
        }
    }
}

@Composable
private fun WhatFitsApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalogResult = remember { runCatching { CatalogRepository.load(context) } }
    val devices = catalogResult.getOrDefault(emptyList())
    val matcher = remember(devices) { CatalogMatcher(devices) }
    val ocrEngine = remember { OfflineOcrEngine(context.applicationContext) }

    var query by rememberSaveable { mutableStateOf("") }
    var fitResult by remember { mutableStateOf<FitResult?>(null) }
    var cameraOpen by rememberSaveable { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recognizedText by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(ocrEngine) {
        onDispose(ocrEngine::close)
    }

    fun search(value: String) {
        query = value.trim()
        errorMessage = null
        recognizedText = null
        fitResult = matcher.resolve(query)
    }

    fun processBitmap(bitmap: Bitmap) {
        if (processing) {
            bitmap.recycle()
            return
        }
        processing = true
        errorMessage = null
        recognizedText = null
        scope.launch {
            val outcome = runCatching {
                ocrEngine.recognize(bitmap)
            }
            bitmap.recycle()
            processing = false
            outcome.onSuccess { recognized ->
                recognizedText = recognized.ifBlank { "[текст не распознан]" }
                fitResult = matcher.resolve(recognized)
                cameraOpen = false
            }.onFailure {
                errorMessage = it.message ?: "Не удалось распознать фотографию."
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            processing = true
            errorMessage = null
            scope.launch {
                val decoded = runCatching {
                    withContext(Dispatchers.IO) { ImageLoader.decode(context, uri) }
                }
                processing = false
                decoded.onSuccess(::processBitmap).onFailure {
                    errorMessage = it.message ?: "Не удалось открыть фотографию."
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (cameraOpen) {
            CameraCapture(
                processing = processing,
                onBack = { if (!processing) cameraOpen = false },
                onCaptured = ::processBitmap,
                onError = {
                    errorMessage = it
                    cameraOpen = false
                },
            )
        } else {
            SearchScreen(
                query = query,
                catalogSize = devices.size,
                result = fitResult,
                processing = processing,
                recognizedText = recognizedText,
                startupError = catalogResult.exceptionOrNull()?.let {
                    "Встроенный каталог не загрузился."
                },
                errorMessage = errorMessage,
                onQueryChange = { query = it },
                onSearch = { search(query) },
                onCandidate = ::search,
                onCamera = { cameraOpen = true },
                onGallery = { galleryLauncher.launch("image/*") },
            )
        }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    catalogSize: Int,
    result: FitResult?,
    processing: Boolean,
    recognizedText: String?,
    startupError: String?,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCandidate: (String) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "WF",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text("What Fits?", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(
                        "Android · RuStore · без Google Play Services",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            Text(
                "Что купить для вашего устройства?",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Введите модель или сфотографируйте шильдик. Поиск и OCR работают прямо на телефоне.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        label = { Text("Модель принтера") },
                        placeholder = { Text("Например, P2500W") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onSearch,
                        enabled = query.trim().length >= 2 && !processing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Найти")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("P2500W", "P2502W", "P3305DN", "BM5100FDW").forEach { model ->
                            OutlinedButton(onClick = { onCandidate(model) }) {
                                Text(model)
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Button(
                        onClick = onCamera,
                        enabled = !processing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Сфотографировать шильдик")
                    }
                    OutlinedButton(
                        onClick = onGallery,
                        enabled = !processing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Выбрать готовое фото")
                    }
                    Text(
                        "Фото и распознанный текст не отправляются на сервер. В APK встроено $catalogSize проверенных моделей для рынка РФ.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (processing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Распознаём на устройстве…", modifier = Modifier.padding(start = 12.dp))
                }
            }

            recognizedText?.let { OcrTextCard(it) }

            startupError?.let { ErrorCard(it) }
            errorMessage?.let { ErrorCard(it) }
            result?.let { ResultSection(it, onCandidate) }

            Text(
                "What Fits? v0.0.10 · Offline-first Android prototype",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun OcrTextCard(text: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Тестовый режим · распознано на фото", fontWeight = FontWeight.Bold)
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace)
            }
            Text(
                "Этот текст показывается только на телефоне и никуда не отправляется.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ResultSection(result: FitResult, onCandidate: (String) -> Unit) {
    when (result) {
        is FitResult.Exact -> ExactCard(result.device)
        is FitResult.Ambiguous -> AmbiguousCard(result.candidates, onCandidate)
        FitResult.NotFound -> StatusCard(
            label = "Не найдено",
            title = "Пока не знаем эту модель",
            body = "Введите код точнее или сделайте другой снимок. Приложение не будет угадывать совместимость.",
            isError = true,
        )
    }
}

@Composable
private fun ExactCard(device: Device) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("✓ Точно определено", color = ColorSuccess, fontWeight = FontWeight.Bold)
            Text(device.canonicalName, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Модель ${device.modelCode} · рынок ${device.market}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Подходящие расходники", fontWeight = FontWeight.Bold)
            device.replacements.forEach { ReplacementCard(it) }
        }
    }
}

@Composable
private fun ReplacementCard(replacement: Replacement) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(replacement.partNumber, style = MaterialTheme.typography.headlineSmall)
            Text(replacementTypeLabel(replacement.type))
            replacement.yieldPages?.let { Text("≈ $it стр.") }
            Text(
                if (replacement.status == "VERIFIED") "✓ Проверено" else replacement.status,
                color = ColorSuccess,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, replacement.source.url.toUri()))
                },
            ) {
                Text("Источник: ${replacement.source.publisher} ↗")
            }
        }
    }
}

@Composable
private fun AmbiguousCard(candidates: List<Device>, onCandidate: (String) -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Нужно уточнить", color = ColorWarning, fontWeight = FontWeight.Bold)
            Text("Какой именно у вас принтер?", style = MaterialTheme.typography.headlineSmall)
            candidates.forEach { device ->
                OutlinedButton(
                    onClick = { onCandidate(device.modelCode) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(device.canonicalName)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    label: String,
    title: String,
    body: String,
    isError: Boolean = false,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                color = if (isError) MaterialTheme.colorScheme.error else ColorWarning,
                fontWeight = FontWeight.Bold,
            )
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    StatusCard(
        label = "Ошибка",
        title = "Не удалось завершить действие",
        body = message,
        isError = true,
    )
}

private fun replacementTypeLabel(type: String): String = when (type) {
    "toner_cartridge" -> "Тонер-картридж"
    "ink_cartridge" -> "Чернильный картридж"
    "drum_unit" -> "Фотобарабан"
    "maintenance_kit" -> "Комплект обслуживания"
    "printhead" -> "Печатающая головка"
    "waste_toner" -> "Контейнер отработанного тонера"
    else -> type
}

private val ColorSuccess = androidx.compose.ui.graphics.Color(0xFF087443)
private val ColorWarning = androidx.compose.ui.graphics.Color(0xFFB54708)
