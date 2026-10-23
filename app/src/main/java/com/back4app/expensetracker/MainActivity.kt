package com.back4app.expensetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.back4app.expensetracker.ui.AppNav
import com.back4app.expensetracker.ui.theme.AppTheme

/** The only Activity. Navigation between screens is done in Compose (AppNav). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ExpenseApp).container
        setContent {
            AppTheme {
                AppNav(container)
            }
        }
    }
}
