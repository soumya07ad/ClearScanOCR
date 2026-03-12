package com.example.clearscanocr

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.clearscanocr.presentation.CameraPreviewScreen
import com.example.clearscanocr.presentation.HomeScreen
import com.example.clearscanocr.presentation.OcrUiState
import com.example.clearscanocr.presentation.OcrViewModel
import com.example.clearscanocr.ui.theme.ClearScanOCRTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClearScanOCRTheme {
                OcrApp()
            }
        }
    }
}

/**
 * Root composable that sets up navigation and camera permission handling.
 */
@Composable
fun OcrApp(viewModel: OcrViewModel = viewModel()) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    // Camera permission state
    var cameraPermissionGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraPermissionGranted = isGranted
        if (isGranted) {
            navController.navigate("camera_preview") {
                launchSingleTop = true
            }
        }
    }

    // React to ViewModel state changes for navigation
    LaunchedEffect(uiState) {
        when (uiState) {
            is OcrUiState.CameraPreview -> {
                // Request camera permission before navigating
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            is OcrUiState.Idle -> {
                // Pop back to home if we're not already there
                if (navController.currentBackStackEntry?.destination?.route != "home") {
                    navController.popBackStack("home", inclusive = false)
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onStartOcr = { viewModel.onStartOcr() }
            )
        }
        composable("camera_preview") {
            CameraPreviewScreen(
                viewModel = viewModel,
                onBack = {
                    viewModel.onBackToHome()
                    navController.popBackStack()
                }
            )
        }
    }
}