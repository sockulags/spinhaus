package se.spinhaus.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.LocalLanguage
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.Routes
import se.spinhaus.app.ui.components.CenteredMessage
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SectionTitle
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.components.WorkoutChart
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.app.ui.tr
import se.spinhaus.core.plan.PlanState
import se.spinhaus.core.plan.SlotStatus
import se.spinhaus.core.plan.SlotView
import se.spinhaus.core.plan.TrainingPlan
import se.spinhaus.core.plan.TrainingPlans
import se.spinhaus.core.ride.RideMode
import se.spinhaus.core.workout.ExpandedWorkout
import se.spinhaus.core.workout.Workout
import se.spinhaus.core.workout.WorkoutLibrary
import java.time.DayOfWeek
import java.time.LocalDate

private fun workoutName(id: String, all: List<Workout>, lang: String): String =
    (all.firstOrNull { it.id == id } ?: WorkoutLibrary.byId(id))?.displayName(lang) ?: id

private fun workoutDuration(id: String, all: List<Workout>): Int =
    (all.firstOrNull { it.id == id } ?: WorkoutLibrary.byId(id))?.totalDurationS ?: 0

/** Starts the workout of a plan slot. */
private fun startSlot(app: AppContainer, nav: Nav, profile: ProfileEntity, state: PlanState, v: SlotView, lang: String) {
    app.scope.launch {
        val w = app.workouts.get(v.workoutId) ?: return@launch
        val title = "${state.plan.displayName(lang)} " + tr(lang, "W", "v") + "${v.slot.week} · ${w.displayName(lang)}"
        nav.startRide(profile, RideMode.Structured(w), title, if (w.ftpTest != null) "TEST" else "WORKOUT", v.slot)
    }
}

@Composable
private fun statusLabel(s: SlotStatus): Pair<String, Color> = when (s) {
    SlotStatus.DONE -> t("Done", "Klar") to SpinColors.Good
    SlotStatus.SKIPPED -> t("Skipped", "Överhoppad") to SpinColors.Muted
    SlotStatus.MISSED -> t("Missed", "Missad") to SpinColors.Bad
    SlotStatus.NEXT -> t("Next", "Nästa") to SpinColors.Primary
    SlotStatus.AVAILABLE -> t("This week", "Denna vecka") to SpinColors.OnSurface
    SlotStatus.UPCOMING -> t("Upcoming", "Kommande") to SpinColors.Muted
}

/** Card on the home screen: the next workout of the active plan, or an invitation to pick one. */
@Composable
fun PlanHomeCard(app: AppContainer, nav: Nav, profile: ProfileEntity, modifier: Modifier = Modifier) {
    val lang = LocalLanguage.current
    val stateFlow = remember(profile.id) { app.plans.observeState(profile.id) }
    val state by stateFlow.collectAsState(initial = null)
    val all by app.workouts.observeAll().collectAsState(initial = emptyList())
    val s = state
    if (s == null) {
        SpinCard(modifier, onClick = { nav.go(Routes.PLANS) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Training plans", "Träningsplaner"), style = MaterialTheme.typography.titleLarge)
                    Text(t("Pick a plan and always know which workout is next.", "Välj en plan och vet alltid vilket pass som är nästa."), color = SpinColors.Muted)
                }
                Text(t("Choose ›", "Välj ›"), color = SpinColors.Primary)
            }
        }
        return
    }
    SpinCard(modifier, onClick = { nav.go(Routes.MY_PLAN) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.plan.displayName(lang), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (s.finished) t("Plan complete!", "Planen är klar!")
                    else t("Week ${s.currentWeek} of ${s.plan.weekCount} · ${s.currentWeekDone} of ${s.currentWeekTotal} done · ${s.daysLeftInWeek(app.plans.today())} days left",
                        "Vecka ${s.currentWeek} av ${s.plan.weekCount} · ${s.currentWeekDone} av ${s.currentWeekTotal} klara · ${s.daysLeftInWeek(app.plans.today())} dagar kvar"),
                    color = SpinColors.Muted,
                )
            }
            Text(t("My plan ›", "Min plan ›"), color = SpinColors.Primary)
        }
        val n = s.next
        if (n != null) {
            Spacer(Modifier.height(12.dp))
            val w = all.firstOrNull { it.id == n.workoutId } ?: WorkoutLibrary.byId(n.workoutId)
            if (w != null) {
                Text(t("Next: ", "Nästa: ") + w.displayName(lang) + " · " + Fmt.minutes(w.totalDurationS), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                WorkoutChart(ExpandedWorkout.of(w).steps, profile.ftp, w.absolutePower, height = 44.dp)
                Spacer(Modifier.height(10.dp))
                var swapOpen by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startSlot(app, nav, profile, s, n, lang) }) { Text(t("Start", "Starta")) }
                    OutlinedButton(onClick = { app.scope.launch { app.plans.skip(profile.id, n.slot) } }) { Text(t("Skip", "Hoppa över")) }
                    OutlinedButton(onClick = { swapOpen = true }) { Text(t("Swap", "Byt")) }
                }
                if (swapOpen) SwapDialog(app, all, lang, onDismiss = { swapOpen = false }) { id ->
                    swapOpen = false
                    app.scope.launch { app.plans.swap(profile.id, n.slot, id) }
                }
            }
        } else if (!s.finished) {
            Spacer(Modifier.height(8.dp))
            Text(t("All of this week's workouts are handled. Next week starts soon.", "Veckans pass är avklarade. Nästa vecka börjar snart."), color = SpinColors.Good)
        }
    }
}

@Composable
private fun SwapDialog(app: AppContainer, all: List<Workout>, lang: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = all
        .filter { query.isBlank() || it.displayName(lang).contains(query, ignoreCase = true) }
        .sortedBy { it.category.ordinal }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Swap workout", "Byt pass")) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(t("Search", "Sök")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.id }) { w ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(w.id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(w.displayName(lang), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Fmt.minutes(w.totalDurationS), color = SpinColors.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "Avbryt")) } },
    )
}

@Composable
fun PlansScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val lang = LocalLanguage.current
    val active = remember(profile.settingsJson) { se.spinhaus.app.data.ProfileSettings.decode(profile.settingsJson).activePlan }
    ScreenScaffold(title = t("Training plans", "Träningsplaner"), onBack = nav::back) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(TrainingPlans.all, key = { it.id }) { p ->
                SpinCard(onClick = { nav.go(Routes.plan(p.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.displayName(lang), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (active?.planId == p.id) Text(t("Active", "Aktiv"), color = SpinColors.Primary, fontWeight = FontWeight.Bold)
                    }
                    Text(planSummary(p, lang), color = SpinColors.Muted)
                    Spacer(Modifier.height(8.dp))
                    Text(p.displayDescription(lang))
                }
            }
        }
    }
}

private fun planSummary(p: TrainingPlan, lang: String): String {
    val totalS = p.weeks.flatten().sumOf { WorkoutLibrary.byId("builtin-$it")?.totalDurationS ?: 0 }
    val perWeek = p.weeks.maxOf { it.size }
    return tr(lang, "${p.weekCount} weeks · up to $perWeek rides/week · ${Fmt.minutes(totalS)} total",
        "${p.weekCount} veckor · upp till $perWeek pass/vecka · ${Fmt.minutes(totalS)} totalt")
}

@Composable
fun PlanDetailScreen(app: AppContainer, nav: Nav, profile: ProfileEntity, planId: String) {
    val lang = LocalLanguage.current
    val plan = TrainingPlans.byId(planId)
    var confirmStart by remember { mutableStateOf<Long?>(null) }
    val active = remember(profile.settingsJson) { se.spinhaus.app.data.ProfileSettings.decode(profile.settingsJson).activePlan }
    if (plan == null) {
        CenteredMessage(t("Plan not found", "Planen hittades inte"))
        return
    }
    val today = LocalDate.now()
    val nextMonday = if (today.dayOfWeek == DayOfWeek.MONDAY) today else today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))

    fun begin(day: Long) {
        app.scope.launch {
            app.plans.start(profile.id, plan.id, day)
            nav.go(Routes.MY_PLAN)
        }
    }

    ScreenScaffold(title = plan.displayName(lang), onBack = nav::back) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            item {
                Text(plan.displayDescription(lang))
                Text(planSummary(plan, lang), color = SpinColors.Muted, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    t("Do the workouts in order, on any days you like. Each plan week is 7 days from the start day; workouts not done by then are marked missed (you can still do them later).",
                        "Gör passen i ordning, vilka dagar du vill. Varje planvecka är 7 dagar från startdagen; pass som inte hunnits markeras som missade (de går att göra i efterhand)."),
                    color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (active != null) confirmStart = today.toEpochDay() else begin(today.toEpochDay()) }) {
                        Text(t("Start today", "Starta idag"))
                    }
                    if (nextMonday != today) OutlinedButton(onClick = {
                        if (active != null) confirmStart = nextMonday.toEpochDay() else begin(nextMonday.toEpochDay())
                    }) { Text(t("Start on Monday", "Starta på måndag")) }
                }
            }
            plan.weeks.forEachIndexed { wi, ids ->
                item {
                    SectionTitle(t("Week ${wi + 1}", "Vecka ${wi + 1}") + if (wi + 1 in plan.easyWeeks) t(" · easy", " · lätt") else "")
                    SpinCard(Modifier.fillMaxWidth()) {
                        ids.forEachIndexed { i, id ->
                            val wid = "builtin-$id"
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${i + 1}. " + workoutName(wid, emptyList(), lang), modifier = Modifier.weight(1f))
                                Text(Fmt.minutes(workoutDuration(wid, emptyList())), color = SpinColors.Muted)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    confirmStart?.let { day ->
        AlertDialog(
            onDismissRequest = { confirmStart = null },
            title = { Text(t("Replace your current plan?", "Byta ut din nuvarande plan?")) },
            text = { Text(t("Your current plan ends. Rides you've done stay in the history.", "Din nuvarande plan avslutas. Åkturerna finns kvar i historiken.")) },
            confirmButton = { TextButton(onClick = { confirmStart = null; begin(day) }) { Text(t("Start new plan", "Starta ny plan")) } },
            dismissButton = { TextButton(onClick = { confirmStart = null }) { Text(t("Cancel", "Avbryt")) } },
        )
    }
}

@Composable
fun MyPlanScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val lang = LocalLanguage.current
    val stateFlow = remember(profile.id) { app.plans.observeState(profile.id) }
    val state by stateFlow.collectAsState(initial = null)
    val all by app.workouts.observeAll().collectAsState(initial = emptyList())
    var confirmEnd by remember { mutableStateOf(false) }
    var swapFor by remember { mutableStateOf<SlotView?>(null) }

    ScreenScaffold(
        title = state?.plan?.displayName(lang) ?: t("My plan", "Min plan"),
        onBack = nav::back,
        actions = {
            if (state != null) TextButton(onClick = { confirmEnd = true }) { Text(t("End plan", "Avsluta plan"), color = SpinColors.Bad) }
        },
    ) { pad ->
        val s = state
        if (s == null) {
            Column(Modifier.padding(pad).padding(24.dp)) {
                Text(t("You have no active plan.", "Du har ingen aktiv plan."))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { nav.go(Routes.PLANS) }) { Text(t("Choose a plan", "Välj en plan")) }
            }
            return@ScreenScaffold
        }
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            item {
                val total = s.slots.size
                SpinCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (s.finished) t("Plan complete!", "Planen är klar!")
                        else t("Week ${s.currentWeek} of ${s.plan.weekCount}", "Vecka ${s.currentWeek} av ${s.plan.weekCount}"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { s.doneCount.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        t("${s.doneCount} of $total done · ${s.missedCount} missed/skipped", "${s.doneCount} av $total klara · ${s.missedCount} missade/överhoppade"),
                        color = SpinColors.Muted,
                    )
                    if (s.finished) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.go(Routes.TRENDS) }) { Text(t("See your progress", "Se din utveckling")) }
                            OutlinedButton(onClick = { nav.go(Routes.PLANS) }) { Text(t("Next plan", "Nästa plan")) }
                        }
                    }
                }
            }
            for (week in 1..s.plan.weekCount) {
                item {
                    val current = week == s.currentWeek
                    SectionTitle(
                        t("Week $week", "Vecka $week") +
                            (if (week in s.plan.easyWeeks) t(" · easy", " · lätt") else "") +
                            (if (current) t(" · this week", " · denna vecka") else ""),
                    )
                }
                items(s.week(week), key = { it.slot.key }) { v ->
                    SlotRow(app, nav, profile, s, v, all, lang, onSwap = { swapFor = v })
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    swapFor?.let { v ->
        SwapDialog(app, all, lang, onDismiss = { swapFor = null }) { id ->
            swapFor = null
            app.scope.launch { app.plans.swap(profile.id, v.slot, id) }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(t("End the plan?", "Avsluta planen?")) },
            text = { Text(t("Rides you've done stay in the history.", "Åkturerna finns kvar i historiken.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    app.scope.launch { app.plans.end(profile.id); nav.home() }
                }) { Text(t("End plan", "Avsluta plan"), color = SpinColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(t("Cancel", "Avbryt")) } },
        )
    }
}

@Composable
private fun SlotRow(
    app: AppContainer, nav: Nav, profile: ProfileEntity, s: PlanState, v: SlotView,
    all: List<Workout>, lang: String, onSwap: () -> Unit,
) {
    val (label, color) = statusLabel(v.status)
    val highlight = v.status == SlotStatus.NEXT
    SpinCard(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = if (highlight) SpinColors.Primary.copy(alpha = 0.15f) else SpinColors.Surface,
        onClick = v.activityId?.let { id -> { nav.go(Routes.activity(id)) } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${v.slot.index + 1}. " + workoutName(v.workoutId, all, lang) + if (v.swapped) t(" (swapped)", " (bytt)") else "",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(Fmt.minutes(workoutDuration(v.workoutId, all)) + " · " + label, color = color, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            when (v.status) {
                SlotStatus.NEXT, SlotStatus.AVAILABLE, SlotStatus.MISSED -> {
                    TextButton(onClick = onSwap) { Text(t("Swap", "Byt")) }
                    if (v.status != SlotStatus.MISSED) {
                        TextButton(onClick = { app.scope.launch { app.plans.skip(profile.id, v.slot) } }) { Text(t("Skip", "Hoppa över")) }
                    }
                    Button(onClick = { startSlot(app, nav, profile, s, v, lang) }) {
                        Text(if (v.status == SlotStatus.MISSED) t("Do now", "Gör nu") else t("Start", "Starta"))
                    }
                }
                SlotStatus.SKIPPED -> TextButton(onClick = { app.scope.launch { app.plans.unskip(profile.id, v.slot) } }) { Text(t("Undo", "Ångra")) }
                SlotStatus.DONE, SlotStatus.UPCOMING -> {}
            }
        }
    }
}
