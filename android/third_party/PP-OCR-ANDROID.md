# PaddleOCR Android SDK

`android/app/libs/ppocr-sdk-release.aar` is built without modifications from the official
PaddleOCR Android SDK at commit `2661c7c0ef5c613e8f93c6e93b2e052399f0f854`:

- source: `deploy/ppocr-android/ppocr-sdk` in <https://github.com/PaddlePaddle/PaddleOCR>;
- AAR SHA-256: `6c04d77fc40d14341ec70d5341cd4998a037eea9d8ed60856273fc3a88add203`;
- license: Apache-2.0, copied to `PaddleOCR-LICENSE.txt`.

The application build downloads the official `PP-OCRv6_tiny` ONNX detector, recognizer,
and recognizer configuration from PaddlePaddle's Hugging Face organization, verifies
their pinned SHA-256 values, and packages them into the APK. The installed application
does not download models or require a network connection for OCR.
