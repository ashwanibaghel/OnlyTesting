package com.fintrack.ui.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fintrack.domain.ledger.*
import com.fintrack.engine.Money
import java.time.LocalDate
import java.util.UUID

private fun Money.display() = "INR ${toDecimal().toPlainString()}"

@Composable
private fun Choice(label: String, choices: List<Pair<String, String>>, selected: String,
    enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty()) {
            Text("$label: ${choices.firstOrNull { it.first == selected }?.second ?: "Choose"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}

@Composable
private fun Input(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, enabled = enabled,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

/** Temporary manual ledger exerciser. Every displayed monetary result is repository-derived. */
@Composable
fun LedgerScreen(viewModel: LedgerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    var mode by rememberSaveable { mutableStateOf("Create account") }
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(AccountKind.BANK.name) }
    var accountId by rememberSaveable { mutableStateOf("") }
    var destinationId by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var fee by rememberSaveable { mutableStateOf("0") }
    var category by rememberSaveable { mutableStateOf("") }
    var splits by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var effective by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var clearing by rememberSaveable { mutableStateOf(Clearing.CLEARED.name) }
    var basis by rememberSaveable { mutableStateOf(BalanceBasis.CLEARED.name) }
    var search by rememberSaveable { mutableStateOf("") }
    var formNonce by rememberSaveable { mutableIntStateOf(0) }
    val active = snapshot?.accounts.orEmpty().filter { !it.archived }
    val selected = accountId.takeIf { id -> active.any { it.id == id } } ?: active.firstOrNull()?.id.orEmpty()
    val destination = destinationId.takeIf { id -> active.any { it.id == id && it.id != selected } }
        ?: active.firstOrNull { it.id != selected }?.id.orEmpty()
    val enabled = !state.busy && snapshot != null
    val modes = listOf("Create account", "Income", "Expense", "Transfer", "Reconcile")
    val formKey = rememberSaveable(mode, name, kind, if (mode == "Create account") "" else selected,
        if (mode == "Transfer") destination else "", amount, fee, category, splits, memo, effective, clearing, formNonce) {
        UUID.randomUUID().toString()
    }
    val newAccountId = rememberSaveable(formKey) { UUID.randomUUID().toString() }

    Scaffold { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Text("FinTrack", style = MaterialTheme.typography.headlineLarge)
                Text("Manual accounts • INR • On this device")
            }
            item {
                if (snapshot == null) Text(if (state.error == null) "Opening local storage…" else "Storage unavailable. Existing data was preserved.")
                else {
                    Text("Liquid funds: ${snapshot.liquid.display()}", style = MaterialTheme.typography.titleLarge)
                    Text("Recorded income: ${snapshot.income.display()} • Expenses: ${snapshot.expense.display()}")
                    Text("Totals include pending entries. Opening balances and transfer principal are excluded from income/expenses.")
                }
            }
            if (state.error != null) item {
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::retry, enabled = enabled) { Text("Retry same submission") }
            }
            if (state.busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            item { Text("Accounts", style = MaterialTheme.typography.titleLarge) }
            if (snapshot?.accounts.isNullOrEmpty()) item { Text("No accounts yet. Add your actual opening balance below.") }
            items(snapshot?.accounts.orEmpty(), key = { it.id }) { account ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${account.name} · ${account.kind.name.lowercase()}${if (account.archived) " · archived" else ""}")
                        Text("Posted: ${account.posted.display()} • Cleared: ${account.cleared.display()}")
                        if (!account.liquid) Text("Investment account: excluded from liquid funds.")
                        if (account.posted.minor < 0) Text("Negative balance / overdraft", color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = enabled, onClick = { viewModel.submit { LedgerAction.Archive(account.id, !account.archived) } }) {
                            Text(if (account.archived) "Unarchive" else "Archive")
                        }
                    }
                }
            }
            item { Choice("Operation", modes.map { it to it }, mode, enabled) { mode = it } }
            item { Input("Effective / statement date (YYYY-MM-DD)", effective, enabled) { effective = it } }
            if (mode == "Create account") {
                item { Input("Account name", name, enabled) { name = it } }
                item { Choice("Type", AccountKind.entries.map { it.name to it.name.lowercase() }, kind, enabled) { kind = it } }
                item { Input("Opening balance (INR, e.g. 20000.00; zero allowed)", amount, enabled) { amount = it } }
            } else item { Choice("Account", active.map { it.id to it.name }, selected, enabled) { accountId = it } }
            if (mode != "Create account") item {
                Input(if (mode == "Reconcile") "Statement balance (INR)" else "Amount (INR, two decimal digits maximum)", amount, enabled) { amount = it }
            }
            if (mode == "Income" || mode == "Expense") item { Input("Category", category, enabled) { category = it } }
            if (mode == "Expense") item { Input("Optional splits: one Category=amount per line; must total amount", splits, enabled) { splits = it } }
            if (mode == "Transfer") {
                item { Choice("Destination", active.filter { it.id != selected }.map { it.id to it.name }, destination, enabled) { destinationId = it } }
                item { Input("Transfer fee (INR)", fee, enabled) { fee = it } }
            }
            if (mode in listOf("Income", "Expense", "Transfer")) {
                item { Choice("Status", Clearing.entries.map { it.name to it.name.lowercase() }, clearing, enabled) { clearing = it } }
                item { Input("Memo (optional)", memo, enabled) { memo = it } }
            }
            item { Input("Reason (required for reversal / reconciliation)", reason, enabled) { reason = it } }
            if (mode == "Reconcile") {
                item { Choice("Statement basis", BalanceBasis.entries.map { it.name to it.name.lowercase() }, basis, enabled) { basis = it } }
                item { Button(enabled = enabled, onClick = { viewModel.review(selected, effective, amount, BalanceBasis.valueOf(basis)) }) { Text("Preview statement difference") } }
                state.review?.let { review -> item {
                    val accountName = snapshot?.accounts?.firstOrNull { it.id == review.accountId }?.name.orEmpty()
                    Text("$accountName · ${review.date} · ${review.basis.name.lowercase()}")
                    Text("Statement: ${review.statement.display()}\nLedger: ${review.ledger.display()}\nPending delta: ${review.pending.display()}\nDifference: ${review.difference.display()}")
                    Button(enabled = enabled && reason.isNotBlank(), onClick = { viewModel.applyReview(reason) }) {
                        Text(if (review.difference.minor == 0L) "Record matched statement" else "Record explicit equity adjustment")
                    }
                } }
            } else item {
                Button(enabled = enabled && state.savedCommandId != formKey, onClick = {
                    viewModel.submit(key = formKey) {
                        val date = LocalDate.parse(effective)
                        val value = Money.parse(amount, INR)
                        when (mode) {
                            "Create account" -> LedgerAction.CreateAccount(newAccountId, name.trim(), AccountKind.valueOf(kind), value, date)
                            "Income" -> LedgerAction.Post(TransactionDraft.Income(selected, value, category.trim(), date, Clearing.valueOf(clearing), memo))
                            "Expense" -> {
                                val parts = if (splits.isBlank()) listOf(Split(category.trim(), value)) else splits.lineSequence().filter { it.isNotBlank() }.map {
                                    val separator = it.lastIndexOf('=')
                                    require(separator > 0) { "Each split must be Category=amount" }
                                    Split(it.substring(0, separator).trim(), Money.parse(it.substring(separator + 1).trim(), INR))
                                }.toList()
                                LedgerAction.Post(TransactionDraft.Expense(selected, value, parts, date, Clearing.valueOf(clearing), memo))
                            }
                            else -> LedgerAction.Post(TransactionDraft.Transfer(selected, destination, value, Money.parse(fee, INR), date, Clearing.valueOf(clearing), memo))
                        }
                    }
                }) { Text(if (mode == "Create account") "Create account" else "Record ${mode.lowercase()}") }
                if (state.savedCommandId == formKey) {
                    Text("Saved. Edit the form or start a new entry to record another transaction.")
                    OutlinedButton(enabled = enabled, onClick = { formNonce++ }) { Text("Start a new entry") }
                }
            }
            item { HorizontalDivider(); Text("Transaction history", style = MaterialTheme.typography.titleLarge) }
            item { Input("Search memo, category or transaction type", search, !state.busy) { search = it } }
            val history = snapshot?.history.orEmpty().asReversed().filter {
                search.isBlank() || (listOf(it.kind, it.memo, it.reason) + it.legs.map { leg -> leg.label }).any { text -> text.contains(search, true) }
            }
            if (history.isEmpty()) item { Text("No matching transactions.") }
            items(history.take(100), key = { it.id }) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${entry.effectiveDate} · ${entry.kind.lowercase()} · ${if (entry.clearedDate == null) "pending" else "cleared ${entry.clearedDate}"}")
                        entry.legs.filter { it.role == LedgerRole.ASSET }.forEach { Text("${it.label}: ${it.amount.display()}") }
                        if (entry.memo.isNotBlank()) Text(entry.memo)
                        if (entry.reason.isNotBlank()) Text("Reason: ${entry.reason}")
                        if (entry.reversedBy != null) Text("Reversed; original retained in history.")
                        if (entry.kind != "REVERSAL" && entry.reversedBy == null) {
                            TextButton(enabled = enabled && reason.isNotBlank(), onClick = { viewModel.submit { LedgerAction.Reverse(entry.id, LocalDate.parse(effective), reason) } }) { Text("Reverse using entered date and reason") }
                            if (entry.clearedDate == null) TextButton(enabled = enabled, onClick = { viewModel.submit { LedgerAction.Clear(entry.id, LocalDate.parse(effective)) } }) { Text("Mark cleared on entered date") }
                        }
                    }
                }
            }
            if (history.size > 100) item { Text("Showing latest 100 matching entries. Repository API provides full history.") }
        }
    }
}
