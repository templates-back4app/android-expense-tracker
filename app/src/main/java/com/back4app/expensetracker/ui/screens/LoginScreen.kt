package com.back4app.expensetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.back4app.expensetracker.AppContainer
import com.back4app.expensetracker.ui.LoginViewModel

@Composable
fun LoginScreen(container: AppContainer, onSignedIn: () -> Unit) {
    val vm: LoginViewModel = viewModel { LoginViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Expense Tracker", style = MaterialTheme.typography.headlineMedium)
            Text("Kotlin · Compose · Room · Parse", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            if (!vm.backendConfigured) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "Backend keys missing. Add parse.appId and parse.clientKey to local.properties " +
                            "(or copy assets/backend.properties.example) and rebuild. See README.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = s.email, onValueChange = vm::setEmail, label = { Text("E-mail") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = s.password, onValueChange = vm::setPassword, label = { Text("Password (8+ characters)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            if (s.busy) {
                CircularProgressIndicator()
            } else {
                Button(onClick = { vm.submit(onSignedIn) }, enabled = vm.backendConfigured, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.createAccount) "Create account" else "Sign in")
                }
                TextButton(onClick = vm::toggleCreateAccount) {
                    Text(if (s.createAccount) "I already have an account" else "Create an account instead")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.continueAsGuest(onSignedIn) }, enabled = vm.backendConfigured, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue as guest")
                }
                Text(
                    "Guest = anonymous Parse user on this device only. Data is lost if you sign out or uninstall.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
