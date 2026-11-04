package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AuthState
import com.example.data.FirebaseService
import com.example.ui.viewmodel.ExpenseViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    viewModel: ExpenseViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authState by FirebaseService.authStateVal.collectAsState()

    var isRegisterMode by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var authLoading by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                val email = account.email ?: ""
                val displayName = account.displayName ?: ""
                val uid = account.id ?: "google_uid"
                authLoading = true
                authErrorMessage = null
                coroutineScope.launch {
                    try {
                        FirebaseService.authenticateWithGoogle(uid, displayName, email)
                        Toast.makeText(context, "Google Authorization Successful!", Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        authErrorMessage = e.message ?: "Authentication failed."
                        FirebaseService.authStateVal.value = AuthState.DENIED
                    } finally {
                        authLoading = false
                    }
                }
            }
        } catch (e: Throwable) {
            val errText = e.message ?: "Google Sign-In service unavailable in this environment."
            authErrorMessage = "Google Sign-In: $errText"
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Feature Header
                Card(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo_emblem_1786874063889),
                            contentDescription = "Family Expense Tracker Logo",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Family Ledger",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Collaborative family finance, secured & whitelisted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                AnimatedContent(
                    targetState = authState,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "AuthFormTransition"
                ) { state ->
                    if (state == AuthState.DENIED) {
                        // Strict ACL Block Option UI
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("access_denied_card"),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = "Blocked",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Access Denied",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = authErrorMessage ?: "Your email is not whitelisted by the family administrator. Please contact your household owner to grant email access.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        authErrorMessage = null
                                        FirebaseService.authStateVal.value = AuthState.UNAUTHENTICATED
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("retry_auth_btn")
                                ) {
                                    Text("Try Another Account")
                                }
                            }
                        }
                    } else {
                        // Login & Register Panel
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_panel_card"),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp)
                            ) {
                                Text(
                                    text = if (isRegisterMode) "Create Authorized Account" else "Authorized Login",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                if (isRegisterMode) {
                                    OutlinedTextField(
                                        value = displayNameInput,
                                        onValueChange = { displayNameInput = it },
                                        label = { Text("Your First Name") },
                                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                        modifier = Modifier.fillMaxWidth().testTag("auth_display_name_input"),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                OutlinedTextField(
                                    value = emailInput,
                                    onValueChange = { emailInput = it },
                                    label = { Text("Whitelisted Email Address") },
                                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth().testTag("auth_email_input"),
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = { Text("Password") },
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth().testTag("auth_password_input"),
                                    shape = RoundedCornerShape(12.dp),
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true
                                )

                                authErrorMessage?.let { errMsg ->
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = errMsg,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                if (authLoading) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                    }
                                } else {
                                // Primary Google SSO Button
                                Button(
                                    onClick = {
                                        authErrorMessage = null
                                        try {
                                            val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                                .requestEmail()
                                                .build()
                                            val googleSignInClient = GoogleSignIn.getClient(context, googleSignInOptions)
                                            val intent = googleSignInClient.signInIntent
                                            googleSignInLauncher.launch(intent)
                                        } catch (e: Throwable) {
                                            authErrorMessage = "Google Sign-In initialization failed: ${e.message ?: "Service error"}."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("google_sso_btn"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Login,
                                        contentDescription = "Google SSO",
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Sign-in with Google SSO", fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(16.dp))

                                // Email/Password Alternative Option
                                OutlinedButton(
                                    onClick = {
                                        if (emailInput.trim().isEmpty() || passwordInput.trim().isEmpty() || (isRegisterMode && displayNameInput.trim().isEmpty())) {
                                            authErrorMessage = "All fields are required for email authorization."
                                            return@OutlinedButton
                                        }
                                        authLoading = true
                                        authErrorMessage = null
                                        coroutineScope.launch {
                                            try {
                                                if (isRegisterMode) {
                                                    FirebaseService.signUpWithEmail(emailInput.trim(), passwordInput, displayNameInput.trim())
                                                    Toast.makeText(context, "Account Created Successfully!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    FirebaseService.signInWithEmail(emailInput.trim(), passwordInput)
                                                    Toast.makeText(context, "Welcome back!", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                authErrorMessage = e.message ?: "Authentication failed."
                                                FirebaseService.authStateVal.value = AuthState.DENIED
                                            } finally {
                                                authLoading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("auth_submit_btn"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(if (isRegisterMode) "Register via Email" else "Login via Email")
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                TextButton(
                                    onClick = {
                                        isRegisterMode = !isRegisterMode
                                        authErrorMessage = null
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (isRegisterMode) "Already have an account? Log In" else "Create collaborative partner account"
                                    )
                                }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Footer details
                Text(
                    text = "Nestwise Dev Environment v2.10",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
