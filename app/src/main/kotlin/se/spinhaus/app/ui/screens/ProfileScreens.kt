package se.spinhaus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.Routes
import se.spinhaus.app.ui.components.Avatar
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SectionTitle
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.core.training.HrZoneModel

@Composable
fun ProfilePickerScreen(app: AppContainer, nav: Nav) {
    val profiles by app.profiles.observeAll().collectAsState(initial = null)
    ScreenScaffold(title = "Spinhaus") { pad ->
        Column(Modifier.padding(pad).padding(24.dp).fillMaxSize()) {
            Text(t("Who's riding?", "Vem cyklar?"), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            val list = profiles
            if (list != null && list.isEmpty()) {
                Text(
                    t("Welcome! Create a profile for each person who rides. FTP, weight and heart rate are stored per profile.",
                        "Välkommen! Skapa en profil för varje person som cyklar. FTP, vikt och puls sparas per profil."),
                    color = SpinColors.Muted,
                )
                Spacer(Modifier.height(16.dp))
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(220.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(list ?: emptyList(), key = { it.id }) { p ->
                    SpinCard(onClick = {
                        app.selectProfile(p.id)
                        nav.home()
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.name, Color(p.colorArgb), size = 64)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { nav.go(Routes.profileEdit(p.id)) }) {
                                Icon(Icons.Filled.Edit, contentDescription = t("Edit", "Redigera"), tint = SpinColors.Muted)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(p.name, style = MaterialTheme.typography.titleLarge)
                        Text("FTP ${p.ftp} W", color = SpinColors.Muted)
                    }
                }
                item {
                    SpinCard(onClick = { nav.go(Routes.profileEdit(0)) }, color = SpinColors.SurfaceHigh) {
                        Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, SpinColors.Muted, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, null, tint = SpinColors.Muted)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(t("Add profile", "Lägg till profil"), style = MaterialTheme.typography.titleLarge)
                        Text(" ", color = SpinColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileEditScreen(app: AppContainer, nav: Nav, profileId: Long) {
    var loaded by remember { mutableStateOf(profileId == 0L) }
    var existing by remember { mutableStateOf<ProfileEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(SpinColors.profileColors[0].toArgb()) }
    var language by remember { mutableStateOf("sv") }
    var ftp by remember { mutableStateOf("200") }
    var weight by remember { mutableStateOf("75") }
    var maxHr by remember { mutableStateOf("185") }
    var restHr by remember { mutableStateOf("60") }
    var birthYear by remember { mutableStateOf("") }
    var hrModel by remember { mutableStateOf(HrZoneModel.PERCENT_MAX) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(profileId) {
        if (profileId > 0) {
            app.profiles.get(profileId)?.let { p ->
                existing = p
                name = p.name; color = p.colorArgb; language = p.language; ftp = p.ftp.toString()
                weight = p.weightKg.toString().removeSuffix(".0"); maxHr = p.maxHr.toString(); restHr = p.restHr.toString()
                birthYear = p.birthYear?.toString() ?: ""
                hrModel = runCatching { HrZoneModel.valueOf(p.hrModel) }.getOrDefault(HrZoneModel.PERCENT_MAX)
            }
            loaded = true
        } else {
            color = SpinColors.profileColors[(app.profiles.all().size) % SpinColors.profileColors.size].toArgb()
        }
    }

    val ftpV = ftp.toIntOrNull()
    val weightV = weight.replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && ftpV != null && ftpV in 50..600 && weightV != null && weightV in 25.0..200.0 &&
        (maxHr.toIntOrNull() ?: 0) in 100..230 && (restHr.toIntOrNull() ?: 0) in 30..120

    ScreenScaffold(
        title = if (profileId == 0L) t("New profile", "Ny profil") else t("Edit profile", "Redigera profil"),
        onBack = nav::back,
    ) { pad ->
        if (!loaded) return@ScreenScaffold
        Column(Modifier.padding(pad).padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text(t("Name", "Namn")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionTitle(t("Colour", "Färg"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in SpinColors.profileColors) {
                    val selected = c.toArgb() == color
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c)
                            .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { color = c.toArgb() },
                    )
                }
            }
            SectionTitle(t("Language", "Språk"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = language == "sv", onClick = { language = "sv" }, label = { Text("Svenska") })
                FilterChip(selected = language == "en", onClick = { language = "en" }, label = { Text("English") })
            }
            SectionTitle(t("Training", "Träning"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("FTP (W)", ftp, { ftp = it }, Modifier.weight(1f))
                NumberField(t("Weight (kg)", "Vikt (kg)"), weight, { weight = it }, Modifier.weight(1f), decimal = true)
            }
            Text(
                t("Don't know your FTP? Enter a guess (e.g. 2.5 × your weight) and take an FTP test.",
                    "Vet du inte din FTP? Gissa (t.ex. 2,5 × din vikt) och gör ett FTP-test."),
                color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
            SectionTitle(t("Heart rate", "Puls"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(t("Max HR", "Maxpuls"), maxHr, { maxHr = it }, Modifier.weight(1f))
                NumberField(t("Resting HR", "Vilopuls"), restHr, { restHr = it }, Modifier.weight(1f))
                NumberField(t("Birth year", "Födelseår"), birthYear, { birthYear = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = hrModel == HrZoneModel.PERCENT_MAX, onClick = { hrModel = HrZoneModel.PERCENT_MAX },
                    label = { Text(t("% of max HR", "% av maxpuls")) })
                FilterChip(selected = hrModel == HrZoneModel.KARVONEN, onClick = { hrModel = HrZoneModel.KARVONEN },
                    label = { Text(t("Heart rate reserve", "Pulsreserv (Karvonen)")) })
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = valid, onClick = {
                    app.scope.launch {
                        val base = existing ?: ProfileEntity(name = name.trim(), colorArgb = color)
                        val p = base.copy(
                            name = name.trim(), colorArgb = color, language = language, ftp = ftpV!!, weightKg = weightV!!,
                            maxHr = maxHr.toInt(), restHr = restHr.toInt(), birthYear = birthYear.toIntOrNull(), hrModel = hrModel.name,
                        )
                        if (existing == null) {
                            val id = app.profiles.create(p)
                            app.selectProfile(id)
                            nav.home()
                        } else {
                            app.profiles.update(p)
                            nav.back()
                        }
                    }
                }) { Text(t("Save", "Spara")) }
                if (existing != null) {
                    OutlinedButton(onClick = { confirmDelete = true }) { Text(t("Delete profile", "Ta bort profil"), color = SpinColors.Bad) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("Delete profile?", "Ta bort profilen?")) },
            text = { Text(t("The profile is removed. Its rides stay in the history.", "Profilen tas bort. Åkturerna finns kvar i historiken."), textAlign = TextAlign.Start) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.scope.launch {
                        app.profiles.delete(profileId)
                        if (app.currentProfileId.value == profileId) app.selectProfile(null)
                        nav.go(Routes.PROFILES)
                    }
                }) { Text(t("Delete", "Ta bort"), color = SpinColors.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(t("Cancel", "Avbryt")) } },
        )
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == '.' || it == ',')) || it == '-' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}
