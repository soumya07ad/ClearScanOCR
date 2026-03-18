# ClearScanOCR

ClearScanOCR is a production-ready Android application that transforms your smartphone into a high-precision document scanner. It combines real-time **OpenCV** edge detection, **Auto-Capture** intelligence, and **ML Kit** structured data extraction.

## ✨ Advanced Features

- **Real-Time Edge Detection**: Live document tracking with vertex smoothing and stability logic.
- **Auto-Capture**: Intelligent "Ready to Capture" engine that triggers only when the document is stable, well-lit, and perfectly aligned.
- **Perspective Transformation**: Automatically flattens angled documents into a top-down, rectangular view.
- **Image Enhancement**: Adaptive thresholding and grayscale processing to maximize OCR readability in any lighting.
- **ROI Data Extraction**: Automatically identifies and extracts **Dates**, **Times**, and **Temperature** readings using regional analysis and regex.
- **Modern Jetpack Compose UI**: A premium, responsive interface with live guidance messages and a "flash" capture effect.

## 🏗️ Architecture

The project follows a modular **Clean Architecture** (MVVM):

- **Presentation Layer**: Reactive UI with guidance badges and structured result cards.
- **Domain Layer**: Core geometry and CV logic (Perspective warp, Laplacian blur detection).
- **Data Layer**: ML Kit integration, ROI regional analysis, and regex extraction engine.

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
