//! Route planning through BRouter (OpenStreetMap-based, with elevation) and the famous climbs.
//! Adapted from VeloPopuli (GPLv3).

pub struct Classic {
    pub id: &'static str,
    pub name: &'static str,
    pub region: &'static str,
    pub points: &'static [[f64; 2]],
}

pub const CLASSICS: &[Classic] = &[
    Classic { id: "alpe-dhuez", name: "Alpe d'Huez", region: "French Alps · from Bourg d'Oisans", points: &[[45.0556, 6.0303], [45.0917, 6.0706]] },
    Classic { id: "ventoux", name: "Mont Ventoux", region: "Provence · from Bédoin", points: &[[44.1247, 5.1797], [44.1741, 5.2786]] },
    Classic { id: "tourmalet", name: "Col du Tourmalet", region: "Pyrenees · from Luz-Saint-Sauveur", points: &[[42.8722, -0.0036], [42.8977, 0.0647], [42.9086, 0.1453]] },
    Classic { id: "mortirolo", name: "Passo del Mortirolo", region: "Italian Alps · from Mazzo", points: &[[46.2578, 10.2565], [46.2472, 10.2977]] },
    Classic { id: "zoncolan", name: "Monte Zoncolan", region: "Carnic Alps · from Ovaro", points: &[[46.4830, 12.8648], [46.5007, 12.9271]] },
    Classic { id: "box-hill", name: "Box Hill", region: "Surrey · the zig-zag road", points: &[[51.2535, -0.3236], [51.2495, -0.3110]] },
];

/// Routes through the given `[lat, lon]` waypoints and returns the GPX (with elevation).
pub async fn plan(http: &reqwest::Client, points: &[[f64; 2]]) -> Result<String, String> {
    if points.len() < 2 {
        return Err("Need a start and a finish.".into());
    }
    let lonlats = points.iter().map(|p| format!("{:.6},{:.6}", p[1], p[0])).collect::<Vec<_>>().join("|");
    let url = format!("https://brouter.de/brouter?lonlats={lonlats}&profile=fastbike&alternativeidx=0&format=gpx");
    let res = http.get(url).timeout(std::time::Duration::from_secs(60)).send().await.map_err(|e| format!("Could not reach the route planner: {e}"))?;
    let status = res.status();
    let text = res.text().await.map_err(|e| e.to_string())?;
    if !status.is_success() || !text.contains("<trkpt") {
        return Err(format!("No route found ({status})"));
    }
    Ok(text)
}
