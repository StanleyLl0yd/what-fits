from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
APP_GRADLE = ANDROID / "app" / "build.gradle.kts"
MANIFEST = ANDROID / "app" / "src" / "main" / "AndroidManifest.xml"


def test_android_release_is_offline_first_and_gms_free():
    gradle = APP_GRADLE.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")

    assert 'versionName = "0.0.8"' in gradle
    assert 'applicationId = "app.whatfits"' in gradle
    assert '"../../data"' in gradle
    assert "tesseract4android:4.9.0" in gradle
    assert "camera-camera2:1.6.1" in gradle
    assert "android.permission.CAMERA" in manifest
    assert "android.permission.INTERNET" not in manifest


def test_android_displays_local_ocr_diagnostics():
    source = (ANDROID / "app" / "src" / "main" / "java" / "app" / "whatfits" / "MainActivity.kt").read_text(encoding="utf-8")

    assert "Тестовый режим · распознано на фото" in source
    assert "recognizedText = recognized.ifBlank" in source
    assert "никуда не отправляется" in source


def test_android_build_has_runtime_dependency_guard():
    gradle = APP_GRADLE.read_text(encoding="utf-8")

    assert 'tasks.register("verifyNoGoogleRuntime")' in gradle
    assert '"com.google.android.gms"' in gradle
    assert '"com.google.firebase"' in gradle
    assert '"com.android.billingclient"' in gradle
