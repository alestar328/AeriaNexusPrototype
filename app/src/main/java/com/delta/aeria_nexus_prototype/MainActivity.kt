package com.delta.aeria_nexus_prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.delta.aeria_nexus_prototype.navigation.AppNavHost
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme

/** Actividad unica: toda la app vive en Compose con navegacion propia. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AeriaNexusPrototypeTheme {
                AppNavHost()
            }
        }
    }
}
