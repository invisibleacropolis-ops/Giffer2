package com.example.giffer2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.giffer2.feature.home.HomeRoute
import com.example.giffer2.ui.theme.Giffer2Theme

/** Hosts the application UI and delegates rendering to the feature layer. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Giffer2Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeRoute(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
