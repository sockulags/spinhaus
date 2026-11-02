//! Local preview server: serves the renderer (world-web/dist) and the same /api endpoints the
//! Android app provides, so the world can be checked in a desktop browser.
//! Usage: world-preview <dist dir> [gpx file]

use std::io::Read as _;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dist = args.get(1).cloned().unwrap_or_else(|| "world-web/dist".into());
    let gpx = args.get(2).map(|p| std::fs::read_to_string(p).expect("gpx"));
    let cache = std::env::temp_dir().join("spinhaus-world-preview");
    let init = spinhaus_world::prepare(spinhaus_world::PrepareOptions {
        gpx: gpx.as_deref(),
        name: "Preview",
        route_key: "preview",
        cache_dir: &cache,
        online: true,
        hour: 13.0,
        view_chunks: 4,
    })
    .expect("prepare");
    let server = tiny_http::Server::http("127.0.0.1:8765").unwrap();
    eprintln!("http://127.0.0.1:8765/index.html");
    for mut req in server.incoming_requests() {
        let mut body = String::new();
        let _ = req.as_reader().read_to_string(&mut body);
        let url = req.url().to_string();
        let path = url.split_once('?').map_or(url.as_str(), |(p, _)| p);
        let resp = if path == "/api/init" {
            tiny_http::Response::from_data(init.clone().into_bytes())
        } else if let Some(rest) = path.strip_prefix("/api/") {
            // /api/<name>/<a>/<b>
            let mut it = rest.split('/');
            let name = it.next().unwrap_or("");
            let a = it.next().and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
            let b = it.next().and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
            match spinhaus_world::blob(name, a, b) {
                Ok(b) => tiny_http::Response::from_data(b),
                Err(e) => tiny_http::Response::from_data(e.into_bytes()).with_status_code(500),
            }
        } else {
            let file = std::path::Path::new(&dist).join(path.trim_start_matches('/'));
            match std::fs::read(&file) {
                Ok(b) => {
                    let ct = match file.extension().and_then(|e| e.to_str()) {
                        Some("html") => "text/html",
                        Some("js") => "application/javascript",
                        Some("css") => "text/css",
                        Some("woff2") => "font/woff2",
                        _ => "application/octet-stream",
                    };
                    tiny_http::Response::from_data(b).with_header(tiny_http::Header::from_bytes("Content-Type", ct).unwrap())
                }
                Err(_) => tiny_http::Response::from_data(b"not found".to_vec()).with_status_code(404),
            }
        };
        let _ = req.respond(resp);
    }
}
