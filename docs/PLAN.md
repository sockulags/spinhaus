# Spinhaus – Komplett plan

En egen träningsapp för Zwift Ride (Wahoo Kickr Core) på en Lenovo Android-platta,
för två användare. Ersätter Zwift-prenumerationen för strukturerad träning och fri cykling.

Status: **Planering** · Senast uppdaterad: 2026-11-01

---

## 1. Mål och avgränsning

### Krav (från dig)
| # | Krav | Kommentar |
|---|------|-----------|
| K1 | Två användare (fler möjligt) | Profilväljare utan PIN |
| K2 | Koppla upp mot hela cykeln | Trainer (FTMS) + styrkontroller (Zwift Ride-protokoll) |
| K3 | ERG-läge och fritt läge | ERG ska bete sig som i Zwift |
| K4 | Pass anpassade efter FTP | Bibliotek + editor + import av .zwo |
| K5 | Pulsmätare | Polar H-serie, Garmin-band, Garmin-klocka (sänd puls) |
| K6 | Historik | Lokalt på plattan |
| K7 | FTP-tester | Flera varianter |
| K8 | Konfigurerbar dashboard och ljud | Per användare |
| K9 | Svenska och engelska | Väljs per användare |
| K10 | Förberett för träningsplaner, export, moln | Byggs inte i v1, men datamodellen tillåter det |

### Inte med i första versionen (men arkitekturen tillåter det)
- Träningsplaner (flera veckor) – datamodell finns, UI senare
- Export till Strava/Garmin/intervals.icu (.FIT-export först)
- Molnsynk/backup (Supabase) och åtkomst från mobil
- 2D-karta/spöken, GPX-import av riktiga rutter
- Multiplayer/grupp

---

## 2. Hårdvara och protokoll

| Enhet | Protokoll | Vad vi använder | Risk |
|-------|-----------|-----------------|------|
| Wahoo Kickr Core (Zwift One/Ride-varianten) | Bluetooth LE **FTMS** (Fitness Machine Service, 0x1826) + Cycling Power (0x1818) som reserv | Effekt, kadens, hastighet. Styrning: ERG (target power), simulering (lutning/vind/rullmotstånd), motståndsnivå | Låg – öppen standard |
| Zwift Ride styrkontroller | Zwifts egna BLE-tjänst, "RideOn"-handskakning, protobuf-meddelanden | Knappar: växla upp/ned, styrknappar, A/B/Y/Z m.fl. | **Medel/hög** – proprietärt, kan ändras med firmware |
| Pulsband (Polar H7/H9/H10, Garmin HRM) | BLE Heart Rate Service (0x180D) | Puls, ev. RR-intervall | Låg |
| Garmin-klocka | BLE Heart Rate ("Sänd puls" / "Broadcast HR") | Puls | Låg (modellberoende) |

### Viktiga fakta
- **Ride har ingen fysisk kassett.** Växlingen är virtuell. Zwifts egen virtuella växling på trainern
  är låst till Zwift-appen, så **vi bygger egen virtuell växling** i mjukvaran via FTMS-simulering
  (se 6.3). Det är samma princip som andra appar använder.
- **Bara en app i taget** kan styra trainern. Zwift-appen/Companion får inte vara ansluten samtidigt.
- **ANT+ används inte.** Allt går över Bluetooth.
- Kontrollernas protokoll är baklängesanalyserat av communityn (t.ex.
  [RideToWoosh](https://github.com/TheInGoF/RideToWoosh), [zwiftplay](https://github.com/ajchellew/zwiftplay)).
  Vi hittar tjänster/karakteristika efter roll istället för hårdkodade UUID och loggar allt rått,
  så att en firmware-ändring går att felsöka.

---

## 3. Teknikval

| Område | Val | Varför |
|--------|-----|--------|
| Plattform | **Native Android-app (APK)** | Stabilast Bluetooth med 3 enheter samtidigt, skärm hålls tänd, bakgrundstjänst |
| Språk | Kotlin | Standard för Android |
| UI | Jetpack Compose (Material 3, mörkt tema) | Modernt, snabbt att bygga konfigurerbar dashboard |
| Bluetooth | Android BLE API via Nordic "Android-BLE-Library" | Beprövat, hanterar köer/återanslutning |
| Databas | Room (SQLite) | Lokalt, robust, migreringar |
| Inställningar | DataStore | Små inställningar |
| Arkitektur | MVVM + Kotlin Coroutines/Flow, Hilt för DI | Testbart, tydliga lager |
| Grafer | Egen Compose Canvas (passprofil, live-graf, historik) | Full kontroll, lätt och snabbt |
| Ljud | Android SoundPool + TextToSpeech (sv/en) | Pip och röst |
| Översättning | Android string resources (`values/` + `values-sv/`) | Inbyggt |
| Bygge | Gradle + **GitHub Actions** bygger APK automatiskt | Du behöver aldrig bygga själv |
| Distribution | **GitHub Releases** – du laddar ner APK på plattan | Enkelt; signerad med fast nyckel så uppdateringar installeras över gamla |
| Minsta Android | Android 10 (API 29) | Plattan är ny, så den klarar det med marginal |

---

## 4. Arkitektur

```
┌──────────────────────── UI (Compose) ────────────────────────┐
│ Profiler · Hem · Enheter · Pass-bibliotek · Editor · Åktur   │
│ Sammanfattning · Historik · Inställningar · Diagnostik       │
└──────────────┬───────────────────────────────────────────────┘
               │ ViewModels (StateFlow)
┌──────────────▼──────────────── Domän ────────────────────────┐
│ RideSession (tillståndsmaskin, 1 Hz-klocka, inspelning)      │
│ ├─ WorkoutEngine   (intervall, mål, ±%, hoppa, ERG av/på)    │
│ ├─ ErgController   (mjuka övergångar, kadensskydd)           │
│ ├─ SimController   (rutt, lutning, virtuell växellåda, fart) │
│ ├─ FtpTestEngine   (ramp, 20 min, 2×8 min, …)                │
│ └─ Metrics         (NP, IF, TSS, zoner, toppvärden, kcal)    │
│ ControllerMapper   (knapp → åtgärd, konfigurerbart)          │
│ AudioCues          (pip/röst vid händelser)                  │
└──────────────┬───────────────────────────────┬───────────────┘
               │                               │
┌──────────────▼──── Enheter (BLE) ───┐ ┌──────▼──── Data ─────┐
│ DeviceManager (skanna, minnas,      │ │ Room: profiler, pass,│
│   återanslut, status)               │ │ aktiviteter, samples │
│ FtmsTrainer · CpsTrainer (reserv)   │ │ Parsers: .zwo/.erg/  │
│ HeartRateSensor                     │ │   .mrc/JSON          │
│ ZwiftRideController                 │ │ Export: .FIT (fas 6) │
│ SimulatedTrainer (för mina tester)  │ │                      │
│ BleLogger (rålogg → fil)            │ │                      │
└─────────────────────────────────────┘ └──────────────────────┘
```

All hårdvara sitter bakom gränssnitt (`Trainer`, `HeartRateSource`, `Controller`). Det gör att
andra trainers, ANT+, en smart fläkt med mera kan läggas till senare utan att röra resten.

Åkturen körs i en **Foreground Service** med wakelock. Därför fortsätter inspelning och
anslutningar även om appen tappar fokus, och skärmen hålls tänd under åkturen.

---

## 5. Användare och profiler

- **Profilväljare** vid start: stora kort med namn, färg och initialer. "Lägg till profil".
- Per profil sparas:
  - Namn, färg, språk (sv/en)
  - FTP (med historik: datum, värde, källa = test/manuellt/uppskattat)
  - Vikt (för W/kg och fart i fritt läge), födelseår (för kcal-uppskattning, valfritt)
  - Maxpuls, vilopuls → pulszoner (5 zoner, %maxpuls eller Karvonen)
  - Effektzoner (Coggan 7 zoner, standard; Zwift-färger)
  - Föredragen pulsmätare (minns enhetens adress)
  - Dashboard-layout, ljudinställningar, enheter (km/mi)
  - Kontrollerknappar (mappning)
- Trainer och styrkontroller är **gemensamma** (sitter på cykeln). Pulsmätare är **per person**.
- Ingen inloggning, ingen PIN. Alla kan se all historik.

---

## 6. Träningslägen

### 6.1 Strukturerat pass (ERG) – "som i Zwift"
- Trainern får målwatt = `segmentets %FTP × profilens FTP × (1 + justering)`.
- **Mjuka övergångar:** mål ändras med ramp (~1–2 s) och ramp-segment interpoleras varje sekund.
- **Justering ±5 %** under passet (knappar på styret eller skärmen), gäller resten av passet.
- **Hoppa över intervall**, **pausa/fortsätt**.
- **ERG av/på** under passet. Segment av typen "fritt" (t.ex. sprinter) stänger av ERG automatiskt
  och växlar till simulering/växling, precis som FreeRide-segment i Zwift.
- **Kadensskydd (anti-spiral):** om kadens < 40 rpm i > 3 s sänks målet tillfälligt till 50 %. När
  kadensen är tillbaka (> 60 rpm) rampas målet upp igen under 5 s.
- **Paus vid stopp:** kadens 0 i 3 s → passklockan pausas automatiskt; den fortsätter när du trampar.
- **Kadensmål och textmeddelanden** per segment visas om passet har dem (.zwo stöder detta).
- **Nedräkning:** pip 3-2-1 före intervallbyte, visning av nästa intervall.
- Inget ERG under uppvärmningens första sekunder om kadens saknas (undvik att låsa benen vid start).

### 6.2 Fri cykling
- Välj **rutt** (inbyggda fiktiva höjdprofiler: platt, kuperad, backig, "Alpe-liknande" klättring,
  intervallbackar) eller **manuell lutning** (styrs med knappar).
- Fart och distans räknas fram med en fysikmodell (vikt, CdA, rullmotstånd, lutning), så
  höjdprofilen och var du är på den blir realistisk.
- Trainern körs i FTMS-**simuleringsläge**: appen skickar lutning, vind och rullmotstånd.
- **Virtuell växling** med styrkontrollerna (se 6.3).
- Valfritt mål: tid, distans eller "bara cykla".

### 6.3 Virtuell växling (egen implementation)
- 24 virtuella växlar (utväxling ca 34×28 till 50×11), konfigurerbar tabell.
- Växeln påverkar motståndet. Vi skickar en **justerad effektiv lutning/rullmotstånd** till
  trainern utifrån förhållandet mellan vald växel och en referensväxel. Tung växel = mer motstånd
  vid samma kadens, och då blir farten i fysikmodellen högre.
- Reservmetod om simuleringsläget känns trögt på just din trainer: beräkna önskad effekt ur
  fysikmodellen (kadens × utväxling → fart → watt) och skicka den som ERG-mål. Vi väljer metod
  efter testerna i fas 0/2 och kan låta det vara en inställning.
- Växelindikator på skärmen och ett kort "klick"-ljud.

### 6.4 FTP-tester
| Test | Upplägg | FTP-beräkning |
|------|---------|---------------|
| **Ramptest** (standard) | Uppvärmning, sedan +20 W/min tills du inte orkar | 75 % av bästa 1-minutseffekt |
| **Kort ramptest** | +25 W/min, snabbare | 75 % av bästa 1 min |
| **20-minuterstest** | Uppvärmning, 5 min hårt, vila, 20 min max | 95 % av 20-min-snitt |
| **2×8 minuter** | Två 8-minutersintervaller med vila | 90 % av snittet |
| **Uppskattning från åkturer** | Automatisk | 95 % av bästa 20 min senaste 6 veckorna. Föreslås, sparas aldrig utan att du godkänner |

- Testerna körs i ERG (ramptest) eller fritt läge med målvisning (20 min / 8 min).
- Avslutas när kadensen sjunker under 50 rpm i 5 s (ramp) eller manuellt.
- Efter testet: "Din nya FTP: 243 W (tidigare 230). Spara?" → FTP-historiken uppdateras.

---

## 7. Pass

### 7.1 Passformat (internt, JSON)
Ett pass är en lista av segment:
- `steady` (varaktighet, %FTP)
- `ramp` (varaktighet, från–till %FTP)
- `intervals` (antal × [på, av]); vecklas ut till steady-segment
- `free` (varaktighet, ERG av)
- `maxEffort` (varaktighet)

Valfritt per segment: kadensmål, textmeddelande vid tidpunkt.
Metadata: namn, beskrivning, kategori, taggar, författare, källa (inbyggd/egen/importerad).

### 7.2 Inbyggt bibliotek (ca 40 egna originalpass)
Kategorier: Återhämtning · Uthållighet (Z2) · Tempo · Sweet Spot · Tröskel · VO2max ·
Anaerob/sprint · Kadensdrillar · Korta pass (≤ 30 min) · FTP-tester.
Varje pass visar längd, uppskattad TSS/IF och en minigraf. Passet skalas automatiskt efter FTP.

### 7.3 Editor
- Bygg pass med block: lägg till, dra för att ändra ordning, kopiera, ta bort.
- Live-förhandsvisning med zonfärger, total tid och TSS.
- "Duplicera och ändra" på inbyggda pass.

### 7.4 Import
- **.zwo** (Zwift), **.erg / .mrc** (vanliga textformat). Filväljare på plattan.
- Okända element ignoreras och listas i en varning.

---

## 8. Skärmen under åkturen

- **Mörkt, sportigt tema.** Zonfärger enligt Zwift-konventionen (grå, blå, grön, gul, orange, röd).
  Hela bakgrundsaccenten färgas efter aktuell zon.
- **Liggande läge** (plattan på styret) med stöd även för stående.
- Layout: överst ett rutnät med **rutor** och nedtill en **passprofil/höjdprofil** med markör och en
  live-kurva över faktisk effekt och puls.
- **Konfigurerbar** (per profil): välj vilka rutor som visas, i vilken ordning och i vilken storlek
  (stor/medel/liten). Färdiga förval: "Minimal", "Standard", "Data-nörd".
- Tillgängliga rutor: Effekt (3 s), Effekt (momentan), Mål, % av mål, Puls, Pulszon, Kadens,
  Varvtid, Total tid, Tid kvar i intervall, Nästa intervall, Fart, Distans, Lutning, Växel,
  NP, snittwatt, kJ, kcal, W/kg, TSS hittills, Klocka.
- Statusrad: anslutningsikoner för trainer/kontroller/puls (grön/gul/röd) och återanslutning.
- Stora tryckytor (svettiga fingrar). De viktigaste åtgärderna nås också via knapparna på styret.

### Ljud (konfigurerbart per profil)
- Av / Bara pip / Pip + röst
- Händelser: intervallbyte, nedräkning 3-2-1, halvvägs, pass klart, tappad anslutning,
  kadens för låg, ny personlig toppnotering.
- Volym separat från systemet.

### Knappar på styret (standardmappning, konfigurerbar)
| Knapp | ERG-pass | Fritt läge |
|-------|----------|------------|
| Växla upp/ned | Intensitet +/−5 % | Växla upp/ned |
| Vänster/höger | Byt dashboard-sida | Byt dashboard-sida |
| A | Hoppa över intervall (håll 2 s) | Varv |
| B | ERG av/på | – |
| Y | Paus/fortsätt | Paus/fortsätt |
| Z | Visa/dölj graf | Visa/dölj graf |

Den exakta knappuppsättningen bekräftas i fas 0.

---

## 9. Historik och statistik

### Per aktivitet (sparas)
- Råserie 1 Hz: tid, effekt, mål, puls, kadens, fart, lutning, växel
- Sammanfattning: tid, distans, snitt/max effekt, **NP**, **IF**, **TSS**, snitt/max puls,
  snitt kadens, kJ, kcal, höjdmeter, tid i effektzoner, tid i pulszoner
- Toppvärden: 5 s, 1 min, 5 min, 20 min, 60 min
- Typ (pass/fritt/test), pass-ID, FTP vid tillfället, anteckning och "känsla" (1–5)

### Vyer
- **Lista** med filter (person, typ, period) och veckosummering (tid, TSS, antal pass).
- **Detalj:** graf med effekt/puls/kadens, zonfördelning, toppvärden, intervalltabell.
- **Trender:** FTP över tid, veckovolym (tid/TSS), **effektkurva** (bästa effekt per varaktighet,
  senaste 6 veckor jämfört med hela historiken), fitness/trötthet (CTL/ATL/TSB).
- **Personliga rekord** markeras.

### Säkerhet för data
- Pågående åktur sparas löpande (var 10:e s) och kan återställas efter krasch.
- **Backup:** exportera/importera hela databasen som fil (t.ex. till Google Drive manuellt). Det
  skyddar mot att plattan går sönder utan att något moln behövs.

---

## 10. Datamodell (Room)

```
Profile(id, name, color, locale, weightKg, birthYear?, maxHr, restHr,
        hrZoneModel, powerZoneModel, units, theme, createdAt)
FtpEntry(id, profileId, watts, date, source[TEST|MANUAL|ESTIMATE], activityId?)
ProfileSettings(profileId, dashboardJson, soundJson, controllerMapJson, gearTableJson)
KnownDevice(address, name, type[TRAINER|HR|CONTROLLER], profileId?, lastSeen)
Workout(id, name, description, category, tags, segmentsJson, source, createdAt, updatedAt)
Activity(id, profileId, type, workoutId?, routeId?, startedAt, durationS, movingS,
         distanceM, elevationM, avgW, maxW, npW, ifactor, tss, avgHr, maxHr, avgCad,
         kj, kcal, ftpAtTime, feel?, note?, zoneTimesJson, bestsJson, status)
ActivitySample(activityId, t, power, target?, hr?, cadence?, speed?, grade?, gear?)
Route(id, name, profileJson)                       -- inbyggda + framtida GPX
TrainingPlan(id, name, description, weeks)         -- förberett, inget UI i v1
PlanItem(id, planId, week, day, workoutId, note)   -- förberett
ScheduledWorkout(id, profileId, date, workoutId, planItemId?, activityId?) -- förberett
```

Alla tabeller har versionerade migreringar från dag 1, så att data aldrig går förlorad vid
uppdateringar.

---

## 11. Skärmar (flöde)

1. **Profilväljare** → 2. **Hem**: "Kör pass", "Fri cykling", "FTP-test", "Historik", "Pass",
   "Inställningar". Anslutningsstatus överst.
3. **Enheter**: skanna och para trainer, kontroller och puls. Enheterna minns och ansluts
   automatiskt nästa gång.
4. **Passbibliotek** → **Passdetalj** (graf, watt för din FTP) → Starta.
5. **Före start**: kontroll att enheterna är anslutna, val av dashboard-förval.
6. **Åktur** (se §8).
7. **Sammanfattning**: nyckeltal, toppnoteringar, ev. ny FTP, känsla och anteckning → Spara/Kasta.
8. **Historik** → **Aktivitetsdetalj**, **Trender**.
9. **Editor**, **Import**.
10. **Inställningar**: profil, zoner, dashboard, ljud, knappar, växling, språk, backup.
11. **Diagnostik**: rålogg från Bluetooth, enhetsinfo, "Exportera logg". Du skickar loggen till mig
    när något strular.

---

## 12. Testning och kvalitet

Du testar på riktigt med cykeln. Jag säkrar kvaliteten innan varje leverans:

- **Enhetstester:** FTMS-parsning, protobuf-avkodning av kontrollerna, passmotor, ERG-logik,
  NP/TSS/zoner, .zwo-parser, fysikmodell, databasmigreringar.
- **Simulerad trainer** (internt, dold i diagnostiken): kör hela pass utan cykel i CI.
  Den är för mig, inte för dig.
- **Inspelade BLE-loggar** från dina tester spelas upp i tester, så att buggar med riktig
  hårdvara går att reproducera.
- **CI (GitHub Actions):** varje ändring byggs och testas. Releaser taggas och publiceras som APK.

### Ditt testflöde per version
1. Öppna GitHub Releases på plattan → ladda ner senaste APK → installera
   (första gången: tillåt "Installera okända appar" för webbläsaren).
2. Följ **testchecklistan** som följer med varje release (kort, punktvis).
3. Om något strular: Diagnostik → Exportera logg → skicka till mig (eller lägg upp i ett GitHub-issue)
   med en mening om vad som hände.

---

## 13. Faser och leveranser

Varje fas avslutas med en APK och en testchecklista. Du testar när du har tid. Nästa fas
påbörjas när föregående fungerar på din cykel.

### Fas 0 – Hårdvarusondering (liten app, viktigast först)
Syfte: bekräfta att allt går att prata med **innan** resten byggs.
- Skanna och visa alla BLE-enheter
- Ansluta till trainern via FTMS: visa watt/kadens, testa att sätta ERG 150 W och lutning 5 %
- Ansluta till Zwift Ride-kontrollerna: handskakning, visa knapptryck live
- Ansluta till pulsband/Garmin-klocka: visa puls
- Rålogg och export
- **Du testar:** ~15 min. Checklista: "Ser du watt? Ändras motståndet? Visas knapptrycken?"
- **Beslutspunkt:** metod för virtuell växling och knappmappning

### Fas 1 – Grund
- Projektstruktur, CI, signering, releaser
- Profiler (skapa/välj/redigera), FTP och zoner, språk sv/en
- Enhetshantering med minne och automatisk återanslutning
- **Du testar:** skapa två profiler, para enheterna, starta om appen → ansluts de automatiskt?

### Fas 2 – Första riktiga åkturen
- Fri cykling med manuell lutning, effekt/puls/kadens på dashboard (standardlayout)
- Inspelning, sammanfattning, enkel historiklista
- Virtuell växling (första version)
- Foreground service, skärm tänd, kraschåterställning
- **Du testar:** 20–30 min fri cykling, växla, se att åkturen sparas

### Fas 3 – ERG-pass
- Passmotor, ERG-kontroll med mjuka övergångar och kadensskydd, ±%, hoppa över, paus
- Inbyggt bibliotek (alla ~40 pass), passdetalj
- Passprofil-graf på åkskärmen, nedräkning, ljud (pip)
- Knappmappning i ERG
- **Du testar:** ett kort pass och ett längre intervallpass, styrknapparna i ERG

### Fas 4 – FTP-tester och statistik
- Alla FTP-tester + automatisk uppskattning
- NP/IF/TSS, zoner, toppvärden, aktivitetsdetalj med grafer
- Trender: FTP, veckovolym, effektkurva, CTL/ATL/TSB
- **Du testar:** ett ramptest. Hon testar med sin profil och Garmin.

### Fas 5 – Anpassning
- Konfigurerbar dashboard (rutor, ordning, storlek, förval)
- Ljudinställningar inklusive röst (sv/en)
- Konfigurerbara knappar och växeltabell
- Fri cykling med rutter och höjdprofil, fysikmodell för fart/distans
- **Du testar:** gör egen layout, kör en rutt

### Fas 6 – Editor, import, backup
- Passeditor, import av .zwo/.erg/.mrc
- Backup/återställning, .FIT-export av aktivitet (kan laddas upp manuellt till Strava/Garmin)
- **Du testar:** bygg ett pass, importera ett .zwo, gör en backup

### Senare (separata beslut)
- Träningsplaner (UI ovanpå befintlig datamodell)
- Direktexport Strava/intervals.icu
- Molnsynk (Supabase) och mobilvy för historik
- 2D-karta med spöken från tidigare åkturer, GPX-rutter

---

## 14. Risker

| Risk | Sannolikhet | Konsekvens | Åtgärd |
|------|-------------|------------|--------|
| Kontrollernas protokoll ändras av Zwift-firmware | Medel | Knapparna slutar fungera | Undvik att uppdatera Ride via Zwift. Rålogg för snabb felsökning. Allt går att styra från skärmen också |
| Trainerns FTMS är begränsat i Zwift One-firmware | Låg | Ingen styrning | Testas i fas 0. Reserv: Wahoos egna BLE-protokoll (dokumenterat av communityn) |
| Virtuell växling känns onaturlig | Medel | Sämre känsla i fritt läge | Två metoder, justerbar tabell, testas tidigt |
| Garmin-klockan sänder inte puls | Låg–medel | Ingen puls för henne | Använd hennes Garmin-band istället |
| Android dödar appen i bakgrunden (Lenovo-energisparläge) | Medel | Avbruten åktur | Foreground service, guide för att undanta appen från batterioptimering, kraschåterställning |
| Zwift-appen ansluter till trainern samtidigt | Medel | Konflikt | Avinstallera eller logga ut ur Zwift/Companion. Appen varnar om anslutningen nekas |

---

## 15. Det du behöver göra

1. **Innan fas 0:** byt batteri i Polar-bandet. Kolla vilken modell det är (står på baksidan: H7/H9/H10).
2. Ta reda på plattans modell: Inställningar → Om surfplattan. Skicka mig modell och Android-version.
3. Stäng av Zwift (och Zwift Companion) när ni testar, så att de inte tar trainern.
4. **Uppdatera inte** Ride-kontrollernas firmware via Zwift efter att fas 0 fungerar, om du inte vill.
5. Hon: kolla om klockan har "Sänd puls" (Broadcast HR) i menyn.

---

## 16. Beslutslogg

| Datum | Beslut |
|-------|--------|
| 2026-11-01 | Native Android (Kotlin/Compose), APK via GitHub Releases |
| 2026-11-01 | Lokal lagring (Room). Molnet skjuts till senare |
| 2026-11-01 | Dashboard + passprofil/höjdprofil i 2D, mörkt sportigt tema |
| 2026-11-01 | Profiler utan PIN, gemensam historik |
| 2026-11-01 | Svenska och engelska |
| 2026-11-01 | Egen virtuell växling; metod väljs efter fas 0 |
| 2026-11-01 | Inbyggt bibliotek med egna originalpass + import av .zwo |
