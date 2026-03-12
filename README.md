# ClearScanOCR

ClearScanOCR is a production-ready Android application built with Jetpack Compose and CameraX, integrating Google ML Kit Text Recognition v2 for high-accuracy, on-device raw text extraction from documents.

## Features

- **Jetpack Compose UI**: A fully reactive, modern user interface built without XML.
- **CameraX Integration**: Live camera preview with lifecycle-aware resource management.
- **Manual Scan Mode**: High-accuracy single-frame capture triggered by the user to avoid continuous processing overhead and improve precision.
- **Smart Alignment Guide**: An on-screen document alignment guide (4:3 aspect ratio) to help users position their documents perfectly.
- **Precision Cropping**: OCR runs *only* on the region inside the alignment guide, ensuring background noise and text outside the frame are ignored. Includes rotation and `PreviewView` FILL_CENTER compensation.
- **Image Preprocessing**: Raw camera frames are converted to grayscale and have their contrast enhanced (1.3x) via `ColorMatrix` to maximize OCR accuracy prior to processing.
- **Confidence Filtering**: Text lines with an ML Kit confidence score below 0.7 are filtered out.
- **Text Cleanup**: Automatically trims whitespace, removes duplicate blank lines, and merges fragmented lines that lack sentence-ending punctuation.
- **Bounding Box Overlay**: Visual feedback displaying green rectangles around detected text blocks in the scan result view.
- **Clipboard & Share**: Easily copy the extracted text to the clipboard or share it via Android intents.

## Architecture

The project strictly follows the **MVVM (Model-View-ViewModel)** architecture:

- **presentation/**: `OcrScreen.kt` and `OcrViewModel.kt` manage the UI state, user intent, and CameraX interactions.
- **domain/**: `OcrUseCase.kt` acts as the bridge coordinating preprocessing and text extraction.
- **data/**: `OcrProcessor.kt`, `ImagePreprocessor.kt`, and `TextCleaner.kt` handle the ML Kit client, Bitmap manipulation (cropping & contrast), and string formatting.

## Setup & Requirements

- Minimum SDK: 23
- Target SDK: 36
- Kotlin: 2.0.21
- Jetpack Compose BOM: 2025.02.00

To build the project:
1. Open the project in Android Studio.
2. Build and run on a physical device or emulator (camera access required).

## Libraries Used

- **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`)
- **Google ML Kit Text Recognition v2** (Bundled model)
- **Jetpack Compose** (`material3`, `ui`, `ui-tooling`, `navigation-compose`, etc.)
- **Kotlin Coroutines**

## License

This project is created for demonstration and portfolio purposes.
