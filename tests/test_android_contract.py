import hashlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
APP_GRADLE = ANDROID / "app" / "build.gradle.kts"
MANIFEST = ANDROID / "app" / "src" / "main" / "AndroidManifest.xml"


def test_android_release_is_offline_first_and_gms_free():
    gradle = APP_GRADLE.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")

    assert 'versionName = "0.0.10"' in gradle
    assert "versionCode = 10" in gradle
    assert "minSdk = 26" in gradle
    assert 'abiFilters += "arm64-v8a"' in gradle
    assert 'applicationId = "app.whatfits"' in gradle
    assert '"../../data"' in gradle
    assert "tesseract4android:4.9.0" in gradle
    assert "onnxruntime-android:1.21.1" in gradle
    assert "opencv:4.5.3" in gradle
    assert "preparePpOcrModels" in gradle
    assert "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8" in gradle
    assert "camera-camera2:1.6.1" in gradle
    assert "android.permission.CAMERA" in manifest
    assert "android.permission.INTERNET" not in manifest


def test_android_displays_local_ocr_diagnostics():
    source = (ANDROID / "app" / "src" / "main" / "java" / "app" / "whatfits" / "MainActivity.kt").read_text(encoding="utf-8")

    assert "Тестовый режим · распознано на фото" in source
    assert "recognizedText = recognized.ifBlank" in source
    assert "никуда не отправляется" in source


def test_android_ocr_uses_upscaled_overlapping_crops():
    source = (ANDROID / "app" / "src" / "main" / "java" / "app" / "whatfits" / "ocr" / "TesseractOcrEngine.kt").read_text(encoding="utf-8")

    assert "MAX_UPSCALE = 4f" in source
    assert "Crop(0.42f, 0f, 1f, 0.58f, 2000)" in source
    assert '"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789- "' in source
    assert "MODEL_TOKEN.containsMatchIn(text)" in source


def test_android_uses_ppocr_with_tesseract_fallback():
    source = (ANDROID / "app" / "src" / "main" / "java" / "app" / "whatfits" / "ocr" / "OfflineOcrEngine.kt").read_text(encoding="utf-8")
    aar = ANDROID / "app" / "libs" / "ppocr-sdk-release.aar"

    assert "PaddleOCR.create" in source
    assert "paddleEngine().recognize(source)" in source
    assert "tesseract.recognize(source)" in source
    assert hashlib.sha256(aar.read_bytes()).hexdigest() == "6c04d77fc40d14341ec70d5341cd4998a037eea9d8ed60856273fc3a88add203"


def test_android_build_has_runtime_dependency_guard():
    gradle = APP_GRADLE.read_text(encoding="utf-8")

    assert 'tasks.register("verifyNoGoogleRuntime")' in gradle
    assert '"com.google.android.gms"' in gradle
    assert '"com.google.firebase"' in gradle
    assert '"com.android.billingclient"' in gradle
