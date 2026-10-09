package com.moneyflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moneyflow.app.ui.MoneyFlowApp
import com.moneyflow.app.ui.MoneyFlowViewModel
import com.moneyflow.app.ui.theme.MoneyFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as MoneyFlowApplication).repository
        setContent {
            MoneyFlowTheme {
                val viewModel: MoneyFlowViewModel = viewModel(factory = MoneyFlowViewModel.factory(repository))
                MoneyFlowApp(viewModel)
            }
        }
    }
}
