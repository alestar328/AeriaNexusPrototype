package com.delta.aeria_nexus_prototype

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.delta.aeria_nexus_prototype.navigation.AppNavHost
import com.delta.aeria_nexus_prototype.ui.theme.AeriaNexusPrototypeTheme

/** Actividad unica: toda la app vive en Compose con navegacion propia. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La app muestra evidencia y datos de agentes: FLAG_SECURE bloquea las
        // capturas y la grabacion de pantalla en toda la aplicacion, y ademas
        // oculta la vista previa en el selector de apps recientes.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        setContent {
            AeriaNexusPrototypeTheme {
                AppNavHost()
            }
        }
    }
}
