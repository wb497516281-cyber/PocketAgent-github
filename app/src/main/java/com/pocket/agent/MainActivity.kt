package com.pocket.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pocket.agent.ui.AgentApp
import com.pocket.agent.ui.theme.PocketAgentTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AgentApplication).container
        setContent {
            PocketAgentTheme {
                AgentApp(container = container)
            }
        }
    }
}
