package com.miraglio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.miraglio.ui.navigation.MiraglioNavGraph
import com.miraglio.ui.theme.MiraglioTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiraglioTheme {
                val navController = rememberNavController()
                MiraglioNavGraph(navController = navController)
            }
        }
    }
}
