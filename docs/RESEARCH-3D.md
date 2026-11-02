# Undersökning: virtuell 3D-värld för Spinhaus

2026-11-02 · Fråga: hur har andra löst en Zwift-liknande värld, och vad är realistiskt för oss?
Kravet är att det ska bli **bra** – annars gör vi det inte.

## Hur andra gör

| Lösning | Hur | Lärdom för oss |
|---|---|---|
| **Zwift, MyWhoosh** (MyWhoosh bygger på Unreal Engine) | Handbyggda fantasivärldar, stora grafikteam | Den nivån är inte nåbar för oss och inte heller målet |
| **Rouvy, Kinomap, FulGaz** | Inspelad video av riktiga vägar. Uppspelningen styrs av din fart; FulGaz räknar fart från watt och vikt | Videon ser fantastisk ut, men all film måste spelas in och licensieras. Inget vi kan producera |
| **Biketerra** | 3D-värld från riktiga rutter (GPX), körs i webbläsaren | Visar att "rutt in, värld ut" fungerar |
| **GlobeRide** (öppen källkod) | CesiumJS och Googles fotorealistiska 3D-kartor, FTMS | Ser realistiskt ut uppifrån men ofta "smält" i markhöjd. Kräver API-nyckel, kostar och strömmar mycket data. Tungt för en platta |
| **CyCARLA** (öppen källkod) | Unreal-baserad bilsimulator | Kräver en kraftig dator. Inte aktuellt |
| **VeloPopuli** (öppen källkod, GPLv3, aktivt 2026) | GPX in → en 3D-värld genereras: verklig terräng (öppna höjddata), byar, skog, sjöar, broar och tunnlar från OpenStreetMap, avspärrad väg och cyklist. Smart trainer via FTMS, ERG-pass, spöken, kända klättringar (Alpe d'Huez, Ventoux, Stelvio m.fl.) | **Exakt det vi vill ha, och det ser bra ut** (se skärmbilderna nedan). Byggt för Windows, macOS och Linux, inte Android |

### VeloPopuli i detalj

![Alpe d'Huez](https://raw.githubusercontent.com/pepperstm/velopopuli/master/docs/images/ride-climb.jpg)

- **Arkitektur:** all logik och all världsgenerering ligger i ett Rust-bibliotek utan I/O, med egna tester (vägen, terrängen, byggnader, träd, staket). Ritningen sköts av ett tunt webblager i three.js (WebGL) som tar emot läget ungefär 20 gånger i sekunden.
- **Data:** terräng från AWS Terrain Tiles (öppna data) och byggnader, skog och vatten från OpenStreetMap. Allt laddas ner första gången en rutt körs och sparas sedan lokalt.
- **Status:** världen, fysiken och passen är testade. Det första testet med riktig hårdvara pågår.
- **Licens:** GPLv3. Tar vi in deras kod måste Spinhaus också bli GPLv3. Det är inget problem för oss: appen är privat och repot är redan publikt.

## Alternativ för Spinhaus

| | Alternativ | Kvalitet | Insats | Risk |
|---|---|---|---|---|
| **A** | **Bygg in VeloPopulis värld i Spinhaus.** Deras Rust-bibliotek kompileras för Android och deras three.js-ritning visas i en WebView. Spinhaus behåller trainer, knappar, pass, planer och historik | ★★★★ (som skärmbilderna) | Medel. Mest integration, ingen nyuppfinning | Prestanda på plattan; att bygga Rust för Android i CI |
| B | Egen värld från grunden i samma stil | ★★–★★★ till en början | Stor. Vi uppfinner om det de redan löst | Hög |
| C | Video (som Rouvy) | ★★★★★ | Omöjlig utan eget filmmaterial och licenser | – |
| D | Googles fotorealistiska 3D-kartor | ★★★ uppifrån, ★★ i markhöjd | Medel | Kostnad, API-nyckel, data, tungt |
| E | Använd VeloPopuli direkt på en dator vid cykeln | ★★★★ | Ingen | Inte på plattan. Zwift Ride-knapparna stöds troligen inte |

## Rekommendation

**Alternativ A, men börja med ett prov.** Ett litet provbygge med Alpe d'Huez i Spinhaus på plattan, med fart och lutning från trainern, svarar på det enda stora frågetecknet: om plattan orkar rita det snyggt.

- Bilden flyter (minst ~30 bilder/s med rimliga inställningar) och ser ut som skärmbilderna → vi bygger ut det fullt.
- Den hackar eller måste förenklas så mycket att det blir fult → vi lägger ner det, enligt "bra eller inte alls".

Det vi behöver från dig: **plattans modellnamn** (Inställningar → Om surfplattan), eftersom det avgör grafikprestandan.

## Källor
- VeloPopuli: https://github.com/pepperstm/velopopuli
- GlobeRide: https://awesome.ecosyste.ms/projects/github.com%2Fmasonwyatt23%2Fgloberide
- CyCARLA: https://awesome.ecosyste.ms/projects/github.com%2Ftensorturtle%2Fcycarla
- MyWhoosh (Unreal Engine): https://en.wikipedia.org/wiki/MyWhoosh
- Rouvy, videosynk: https://en.wikipedia.org/wiki/Rouvy · Kinomap: https://www.cyclistshub.com/kinomap-review/ · FulGaz: https://feedback.fulgaz.com/articles/360043558652-why-doesnt-my-fulgaz-speed-match-my-garmin-wahoo-speed
- Biketerra: https://apps.apple.com/mx/app/biketerra/id6482576528
- Godot som Android-bibliotek (övervägt alternativ till WebView): https://docs.godotengine.org/de/4.4/_sources/tutorials/platform/android/android_library.rst.txt
