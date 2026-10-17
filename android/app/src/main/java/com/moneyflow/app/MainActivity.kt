package com.moneyflow.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneyflow.app.ui.MoneyFlowApp
import com.moneyflow.app.ui.MoneyFlowViewModel
import com.moneyflow.app.ui.theme.MoneyFlowTheme

class MainActivity : ComponentActivity() {
    private var openPlanning by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPlanning = intent?.getBooleanExtra("open_planning", false) == true
        val repository = (application as MoneyFlowApplication).repository
        setContent {
            MoneyFlowTheme {
                val viewModel: MoneyFlowViewModel = viewModel(factory = MoneyFlowViewModel.factory(repository))
                MoneyFlowApp(viewModel, openPlanning) {
                    intent?.removeExtra("open_planning")
                    openPlanning = false
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_planning", false)) openPlanning = true
    }
}
