# Spinhaus v2 – Träningsplaner och film under passet

Status: **Planering** · 2026-11-02

Omfattning (ditt val):
1. **Träningsplaner** med "nästa pass" på hemskärmen
2. ~~Uppladdning till Strava/intervals.icu/Garmin~~ – **avvaktar**
3. **Film under passet**: flytande ruta med siffror ovanpå Netflix/YouTube

---

## 1. Träningsplaner

### Hur det fungerar
- Varje profil kan ha **en aktiv plan** i taget. Ni kan alltså köra olika planer samtidigt.
- En plan består av **veckor**. Varje vecka har ett antal pass i en bestämd **ordning**.
- Passen görs **i ordning, vilken dag du vill** under veckan. Hemskärmen visar alltid nästa pass.
- **Planveckan är 7 dagar räknat från startdagen** (du väljer startdag, t.ex. på måndag).
- **Nästa vecka börjar ändå.** Pass som inte hunnits markeras som *missade*, och planen
  fortsätter med nästa veckas första pass.
- Ett pass räknas som gjort när det **startas från planen och sparas**. Startar du samma pass från
  biblioteket räknas det också, om det är nästa pass i planen.
- Du kan **hoppa över** ett pass (räknas som missat) eller **byta** det mot ett annat pass från
  biblioteket (bara för den gången).
- Passen är i % av FTP. När planens FTP-test sparar ny FTP blir resten av planen automatiskt
  anpassad.
- Under passet syns "Plan: FTP-bygge · vecka 2 · pass 2/3" i titeln.
- När planen är klar visas en sammanfattning: gjorda och missade pass, total tid, TSS och
  FTP-förändring.

### Planerna (använder befintliga pass i biblioteket)

**FTP-bygge – 6 veckor, 3 pass/vecka**
| Vecka | Pass 1 | Pass 2 | Pass 3 |
|---|---|---|---|
| 1 | Sweet spot 3×10 | Uthållighet 60 | Tempo 3×10 |
| 2 | Sweet spot 2×15 | Uthållighet 60 | Tröskel 4×8 |
| 3 | Sweet spot 3×15 | Uthållighet 90 | Över/under 3×9 |
| 4 *(lätt)* | Lätt rull | Sweet spot 3×10 | Uthållighet 45 |
| 5 | Sweet spot 2×20 | Uthållighet 90 | Tröskel 3×12 |
| 6 | Tröskel 2×20 | Lätt rull | **Ramptest** |

**Grundträning – 8 veckor, 3 pass/vecka**
| Vecka | Pass 1 | Pass 2 | Pass 3 |
|---|---|---|---|
| 1 | Uthållighet 45 | Kadenspyramid | Uthållighet 60 |
| 2 | Uthållighet 60 | Hög kadens | Uthållighetsbygge |
| 3 | Uthållighet 60 | Tempoblock | Uthållighet 90 |
| 4 *(lätt)* | Lätt rull | Uthållighet 45 | Fikarunda 30 |
| 5 | Uthållighetsbygge | Tempo 3×10 | Uthållighet 90 |
| 6 | Uthållighet 60 | Styrka låg kadens | Uthållighet 90 |
| 7 | Tempo 2×20 | Uthållighet 60 | Uthållighet 90 |
| 8 *(lätt)* | Lätt rull | Uthållighet 45 | **Ramptest** |

**VO2max-block – 4 veckor, 3 pass/vecka**
| Vecka | Pass 1 | Pass 2 | Pass 3 |
|---|---|---|---|
| 1 | VO2max 5×3 | Uthållighet 60 | 30/30 |
| 2 | VO2max 4×4 | Uthållighet 60 | 40/20 |
| 3 | VO2max-pyramid | Uthållighet 45 | VO2max 6×2 |
| 4 | Lätt rull | Uthållighet 45 | **Ramptest** |

**Komma igång – 4 veckor, 2–3 pass/vecka**
| Vecka | Pass 1 | Pass 2 | Pass 3 |
|---|---|---|---|
| 1 | Fikarunda 30 | Lätt rull | – |
| 2 | Fikarunda 30 | Kadenslyft 20 | Uthållighet 45 |
| 3 | Snabba 20 | Uthållighet 45 | Lunchpass 30 |
| 4 | Lunchpass 30 | Uthållighet 45 | **Ramptest** |

Planerna ligger i koden precis som passbiblioteket. Fler planer (eller egna) kan läggas till
senare utan databasändringar.

### Skärmar
- **Hemskärmen:** kortet *Din plan* visar "FTP-bygge · vecka 2 av 6 · 1 av 3 klara", nästa pass med
  minigraf och tid, samt knapparna **Starta**, **Hoppa över** och **Byt**. Utan plan visas
  "Välj en träningsplan".
- **Planer** (ny ruta på hemskärmen): de fyra planerna med längd, pass/vecka, total tid och en
  kort beskrivning. Tryck på en plan för att se veckorna och välja startdag ("Starta idag" eller
  "Starta på måndag").
- **Min plan:** alla veckor med status per pass (klar ✓, missad ✗, nästa ▶, kommande). Härifrån
  kan du byta pass och avsluta planen.
- **Historik:** åkturer gjorda i en plan får en liten planetikett.

### Data
- Den aktiva planen sparas i profilens inställningar (plan-id och startdatum).
- Planens pass och deras status sparas i den befintliga tabellen `scheduled_workouts`, som skapades
  för just detta i v1. **Ingen databasmigrering behövs**, så historiken är säker.
- Planlogiken (vilken vecka det är, nästa pass, missade pass) skrivs i `core` och testas med
  enhetstester (vecko-övergångar, hoppa över, byta, avsluta).

---

## 3. Film under passet

### Hur det fungerar
1. Starta ett pass i Spinhaus som vanligt.
2. Tryck **🎬 Film** på åkskärmen. Där finns genvägar till Netflix, YouTube och SVT Play, eller så
   öppnar du filmappen själv.
3. När Spinhaus hamnar i bakgrunden visas en **flytande ruta** ovanpå filmen.
4. Allt fortsätter som vanligt: trainern styrs, knapparna på styret fungerar och passet spelas in.
5. Tryck på rutan (eller välj Spinhaus bland senaste appar) för att komma tillbaka. Då försvinner
   rutan.

### Rutan
- **Kompakt läge:** watt (i zonfärg), mål, puls och nedräkning för intervallet.
- **Utökat läge:** dessutom kadens, nästa intervall, total tid och en mini-passgraf.
- **Dubbeltryck** växlar läge. **Dra** för att flytta rutan; positionen sparas.
- Genomskinligheten går att ställa in (Inställningar → Film).
- Rutan **blinkar** vid intervallbyte och vid "låg kadens".
- Första gången frågar Android om tillåtelse för **"Visa över andra appar"**. Det behövs en gång.

### Ljud och knappar
- Röst och pip hörs över filmen: filmljudet **sänks kort** medan rösten pratar (audio ducking).
- **Pausa filmen från styret:** en ny knappfunktion "Spela/pausa media" skickar en vanlig
  mediaknapp till den app som spelar, vilket fungerar i Netflix och YouTube. Som standard ligger den
  på **höger power-up**. Det går att ändra under Knappar.
- Valfritt: pausar du passet (Y) pausas filmen också.

### Teknik
- Rutan ritas av åkturens förgrundstjänst med `TYPE_APPLICATION_OVERLAY`. Den lever alltså så länge
  åkturen pågår, oavsett vilken app som syns.
- Rutan visas bara när en åktur pågår **och** Spinhaus inte syns på skärmen.

---

## Faser och leveranser

| Fas | Innehåll | Du testar |
|---|---|---|
| **A** | Planlogik i core + tester; plandefinitioner | – (automatiska tester) |
| **B** | Plan-UI: hemkortet, Planer, Min plan, koppling till åkturer | Starta en plan, kör ett pass, hoppa över ett, byt ett |
| **C** | Flytande rutan + 🎬 Film-knappen + behörighetsflöde | Kör ett pass med YouTube/Netflix |
| **D** | Mediaknapp från styret, audio ducking, pausa film med passet | Pausa filmen med styret, hör rösten över filmen |

Varje fas kommer som automatisk uppdatering i appen. Checklistan i `TESTING.md` utökas med
punkterna ovan.

## Risker

| Risk | Åtgärd |
|---|---|
| Lenovos energisparläge stänger rutan eller tjänsten | Rutan ligger i förgrundstjänsten, och appen ber redan om att slippa batterioptimering |
| Någon filmapp ignorerar mediaknappen | Pausa på skärmen som vanligt; passet påverkas inte |
| Det blir rörigt med en plan per person | Varje profil har sin egen plan, och hemskärmen visar den inloggade profilens plan |
