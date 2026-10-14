package fr.descentecanyon.app.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.descentecanyon.app.core.ui.R
import fr.descentecanyon.app.domain.model.AuthState
import fr.descentecanyon.app.ui.common.asString
import fr.descentecanyon.app.ui.design.LocalDcColors
import fr.descentecanyon.app.ui.design.LocalDcShapes

@Composable
fun LoginDialog(
    uiState: AuthUiState,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDcColors.current
    val shapes = LocalDcShapes.current
    val authState = uiState.authState

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = shapes.xl,
            color = colors.surfaceBase,
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(colors.primaryAction.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = colors.primaryAction,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.user_account),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = when (authState) {
                                is AuthState.Connected -> stringResource(R.string.connected_as, authState.username)
                                is AuthState.Loading -> stringResource(R.string.loading)
                                else -> stringResource(R.string.debit_login_prompt_title)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }

                when (authState) {
                    is AuthState.Connected -> {
                        Surface(
                            shape = shapes.lg,
                            color = colors.secondaryAction.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, colors.secondaryAction.copy(alpha = 0.34f)),
                        ) {
                            Text(
                                text = stringResource(R.string.connected_as, authState.username),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }

                    is AuthState.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                            )
                        }
                    }

                    else -> {
                        uiState.message?.let { message ->
                            Surface(
                                shape = shapes.lg,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f)),
                            ) {
                                Text(
                                    text = message.asString(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = uiState.username,
                            onValueChange = onUsernameChanged,
                            label = { Text(stringResource(R.string.username)) },
                            leadingIcon = {
                                Icon(Icons.Default.AccountCircle, contentDescription = null)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = uiState.password,
                            onValueChange = onPasswordChanged,
                            label = { Text(stringResource(R.string.password)) },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onLogin() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.back))
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    when (authState) {
                        is AuthState.Connected -> {
                            Button(onClick = onLogout, shape = shapes.xl) {
                                Text(stringResource(R.string.logout))
                            }
                        }

                        is AuthState.Loading -> Unit

                        else -> {
                            Button(
                                onClick = onLogin,
                                enabled = uiState.username.isNotBlank() && uiState.password.isNotBlank(),
                                shape = shapes.xl,
                            ) {
                                Text(stringResource(R.string.login))
                            }
                        }
                    }
                }
            }
        }
    }
}
