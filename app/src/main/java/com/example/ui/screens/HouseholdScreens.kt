package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.FirebaseService
import kotlinx.coroutines.launch

/**
 * Shown after sign-in when the user is not in a household yet.
 * One partner creates the household; the other is invited by email and joins automatically.
 */
@Composable
fun HouseholdSetupScreen() {
    val scope = rememberCoroutineScope()
    val firstName by FirebaseService.currentUserFirstName.collectAsState()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val invite by FirebaseService.pendingInvite.collectAsState()

    fun act(action: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome${firstName?.let { ", $it" } ?: ""}",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Set up your household. Everything you track is shared with the people in it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Household name") },
                placeholder = { Text("${firstName ?: "My"}'s family") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("household_name_input")
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { act({ FirebaseService.createHousehold(name) }) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("create_household_btn"),
                shape = RoundedCornerShape(12.dp)
            ) { Text(if (busy) "Please wait…" else "Create household") }

            Spacer(Modifier.height(24.dp))
            val inv = invite
            if (inv != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "${inv.invitedByName.ifBlank { "Someone" }} invited you to join ${inv.householdName.ifBlank { "their household" }}.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { act({ FirebaseService.acceptInvite() }) },
                                enabled = !busy,
                                modifier = Modifier.testTag("accept_invite_btn")
                            ) { Text("Join") }
                            OutlinedButton(
                                onClick = { act({ FirebaseService.declineInvite() }) },
                                enabled = !busy
                            ) { Text("Decline") }
                        }
                    }
                }
            } else {
                Text(
                    text = "Joining your partner's household instead? Ask them to invite ${FirebaseService.getCurrentUserEmail()} from Settings, then tap below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        act({ if (!FirebaseService.checkForInvite()) message = "No invite found for this email yet." })
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("check_invite_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Check for invite") }
            }

            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { FirebaseService.logout() }) { Text("Use a different account") }
        }
    }
}

/** Settings card: shows the household and lets a member invite their partner by email. */
@Composable
fun HouseholdInviteCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val household by FirebaseService.householdName.collectAsState()
    val partner by FirebaseService.spouseUserFirstName.collectAsState()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = household ?: "Household",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = partner?.let { "Shared with $it." } ?: "Only you are in this household. Invite your partner by email; they join when they sign in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (partner == null) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Partner's email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().testTag("invite_email_input")
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                FirebaseService.invitePartner(email)
                                Toast.makeText(context, "Invite saved. Ask them to sign in with $email.", Toast.LENGTH_LONG).show()
                                email = ""
                            } catch (e: Exception) {
                                Toast.makeText(context, e.message ?: "Could not send the invite.", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && email.contains("@"),
                    modifier = Modifier.fillMaxWidth().testTag("invite_partner_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Invite partner") }
            }
        }
    }
}
