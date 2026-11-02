//! Spinhaus 3D world: builds VeloPopuli's world for a route (terrain from open elevation
//! tiles, scenery and buildings from OpenStreetMap) and serves the mesh buffers that the
//! three.js renderer draws. The ride itself (trainer, physics, recording) stays in Spinhaus.
//!
//! Based on VeloPopuli (https://github.com/pepperstm/velopopuli), GPLv3.

pub mod geo;
mod jni_api;
mod mapdata;
pub mod store;
mod terrain;

use std::collections::VecDeque;
use std::path::Path;
use std::sync::{Arc, Mutex, OnceLock};

use serde::Serialize;
use velopopuli_core::gpx::{build_route, demo_route, parse_gpx, Climb, Route};
use velopopuli_core::world::{align_dem_with, landmarks, route_structures, GridMesh, Landmark, SceneryCounts, World, WorldLayout, LAYOUT};

pub fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

static LOG: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());

pub fn log(tag: &str, msg: String) {
    let line = format!("[{tag}] {msg}");
    eprintln!("{line}");
    let mut l = LOG.lock().unwrap();
    l.push_back(line);
    while l.len() > 200 {
        l.pop_front();
    }
}

pub fn drain_log() -> Vec<String> {
    LOG.lock().unwrap().drain(..).collect()
}

fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().expect("tokio runtime"))
}

fn http() -> &'static reqwest::Client {
    static HTTP: OnceLock<reqwest::Client> = OnceLock::new();
    HTTP.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent("Spinhaus/0.1 (home indoor cycling app; world by VeloPopuli)")
            .connect_timeout(std::time::Duration::from_secs(15))
            .build()
            .expect("http client")
    })
}

/// What the renderer needs to set up (the subset of VeloPopuli's RideInit it uses).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Init<'a> {
    name: &'a str,
    step: f64,
    n: usize,
    total: f64,
    climbs: &'a [Climb],
    real_terrain: bool,
    buildings: usize,
    scenery: SceneryCounts,
    covered: &'a [[f64; 2]],
    layout: WorldLayout,
    centre: [f64; 2],
    hour: f64,
    landmarks: &'a [Landmark],
    ascent: f64,
    /// Elevation per sample (m), for the Spinhaus ride engine.
    ele: Vec<f32>,
}

struct Prepared {
    world: Arc<World>,
}

static CURRENT: Mutex<Option<Arc<Prepared>>> = Mutex::new(None);

fn current() -> Result<Arc<Prepared>, String> {
    CURRENT.lock().unwrap().clone().ok_or_else(|| "No world prepared".to_string())
}

/// Options for [prepare].
pub struct PrepareOptions<'a> {
    /// GPX text, or None for VeloPopuli's built-in demo loop.
    pub gpx: Option<&'a str>,
    pub name: &'a str,
    /// Cache key for map data (letters, digits, '-' and '_').
    pub route_key: &'a str,
    pub cache_dir: &'a Path,
    /// Download real terrain and OpenStreetMap data.
    pub online: bool,
    pub hour: f64,
    /// Fewer chunks around the rider = faster on tablets (VeloPopuli default 6).
    pub view_chunks: i32,
}

/// Builds the world for a route and returns the renderer's init JSON.
pub fn prepare(o: PrepareOptions) -> Result<String, String> {
    let mut route: Route = match o.gpx {
        None => demo_route(),
        Some(text) => build_route(o.name, &parse_gpx(text)?.1)?,
    };
    let real_place = o.gpx.is_some() && o.online;
    let store = store::Store::new(o.cache_dir.join("world"));
    let (dem, map) = runtime().block_on(async {
        tokio::join!(
            async {
                if real_place {
                    terrain::load_dem(http(), &route).await
                } else {
                    None
                }
            },
            mapdata::load(http(), &store, o.route_key, &route, real_place, real_place)
        )
    });
    log("world", format!("route {} km, dem {}, buildings {}", (route.total / 1000.0) as i64, dem.is_some(), map.footprints.len()));
    let structures = map.scenery.as_ref().map(|s| route_structures(&route, s)).unwrap_or_default();
    let dem_offset = dem.as_ref().map_or(0.0, |d| align_dem_with(&mut route, d, &structures));
    let route = Arc::new(route);
    let real_terrain = dem.is_some();
    let world = World::with_map(route.clone(), dem, dem_offset, map.scenery.as_ref(), &map.footprints);
    let marks = landmarks(&route, map.scenery.as_ref());
    let mid = route.sample_at(route.total / 2.0);
    let layout = WorldLayout { view_chunks: o.view_chunks.clamp(2, LAYOUT.view_chunks), ..LAYOUT };
    let init = Init {
        name: &route.name,
        step: route.step,
        n: route.n,
        total: route.total,
        climbs: &route.climbs,
        real_terrain,
        buildings: world.buildings.count(),
        scenery: world.scene.counts,
        covered: &world.scene.covered,
        layout,
        centre: [mid.lat, mid.lon],
        hour: o.hour,
        landmarks: &marks,
        ascent: route.ascent,
        ele: route.ele.clone(),
    };
    let json = serde_json::to_string(&init).map_err(|e| e.to_string())?;
    *CURRENT.lock().unwrap() = Some(Arc::new(Prepared { world: Arc::new(world) }));
    Ok(json)
}

pub fn release() {
    *CURRENT.lock().unwrap() = None;
}

/// GPX for one of the famous climbs, via BRouter (network).
pub fn plan_classic(id: &str) -> Result<String, String> {
    let c = geo::CLASSICS.iter().find(|c| c.id == id).ok_or("Unknown climb")?;
    runtime().block_on(geo::plan(http(), c.points))
}

fn f32_bytes(header: &[u32], parts: &[&[f32]]) -> Vec<u8> {
    let mut out = Vec::with_capacity(header.len() * 4 + parts.iter().map(|p| p.len() * 4).sum::<usize>());
    header.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
    parts.iter().flat_map(|p| p.iter()).for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
    out
}

fn grid_bytes(mesh: GridMesh) -> Vec<u8> {
    let (b, r) = (&mesh.buildings, &mesh.roads);
    f32_bytes(
        &[(mesh.positions.len() / 3) as u32, (mesh.trees.len() / velopopuli_core::world::TREE_FLOATS) as u32, b.vertices() as u32, (mesh.water.len() / 3) as u32, r.vertices() as u32],
        &[&mesh.positions, &mesh.normals, &mesh.colors, &mesh.trees, &b.positions, &b.normals, &b.colors, &b.uvs, &mesh.water, &mesh.water_colors, &r.positions, &r.normals, &r.colors, &r.uvs],
    )
}

/// Mesh buffers in VeloPopuli's binary layouts (see its commands.rs).
pub fn blob(name: &str, a: f64, b: f64) -> Result<Vec<u8>, String> {
    if name == "trees" {
        use velopopuli_core::world::{sprite_frame, tree_model, TREE_KINDS};
        let models: Vec<_> = (0..TREE_KINDS as u8).map(tree_model).collect();
        let frames: Vec<f32> = (0..TREE_KINDS as u8).flat_map(|k| sprite_frame(k).map(|v| v as f32)).collect();
        let mut header = vec![models.len() as u32];
        header.extend(models.iter().map(|m| m.vertices() as u32));
        let mut parts: Vec<&[f32]> = vec![&frames];
        parts.extend(models.iter().flat_map(|m| [&m.positions[..], &m.normals[..], &m.colors[..]]));
        return Ok(f32_bytes(&header, &parts));
    }
    let p = current()?;
    let world = &p.world;
    Ok(match name {
        "route" => {
            let r = &world.route;
            let mut out = Vec::with_capacity(r.n * 72);
            for block in [&r.x, &r.z, &r.lat, &r.lon] {
                block.iter().for_each(|v| out.extend_from_slice(&v.to_le_bytes()));
            }
            for block in [&r.ele, &r.grade] {
                block.iter().for_each(|v| out.extend_from_slice(&(*v as f64).to_le_bytes()));
            }
            (0..r.n).for_each(|i| out.extend_from_slice(&(world.half_width(i) * 2.0).to_le_bytes()));
            (0..r.n).for_each(|i| out.extend_from_slice(&world.bank(i).to_le_bytes()));
            (0..r.n).for_each(|i| out.extend_from_slice(&world.dip(i).to_le_bytes()));
            out
        }
        "road" => {
            let mesh = world.road();
            let looks: Vec<f32> = mesh.looks.iter().map(|&s| s as f32).collect();
            f32_bytes(&[], &[&mesh.positions, &mesh.uvs, &looks])
        }
        "structures" => {
            let s = world.structures();
            f32_bytes(&[s.lit.vertices() as u32, s.unlit.vertices() as u32], &[&s.lit.positions, &s.lit.normals, &s.lit.colors, &s.unlit.positions, &s.unlit.colors])
        }
        "barriers" => {
            let models: Vec<_> = (0..velopopuli_core::world::BARRIER_LOOKS).map(velopopuli_core::world::barrier_model).collect();
            let sections = world.barriers();
            let mut header = vec![sections.len() as u32, models.len() as u32];
            header.extend(models.iter().map(|m| m.vertices() as u32));
            let flat: Vec<f32> = sections.iter().flatten().copied().collect();
            let mut parts: Vec<&[f32]> = vec![&flat];
            for m in &models {
                parts.extend([&m.positions[..], &m.normals[..], &m.colors[..], &m.uvs[..]]);
            }
            f32_bytes(&header, &parts)
        }
        "chunk" => grid_bytes(world.chunk(a as i32, b as i32)),
        "far" => grid_bytes(world.far(a, b)),
        _ => return Err(format!("unknown blob {name}")),
    })
}
