//! delta-web: a local live viewer/navigator for the trellis `*.delta` chain.
//!
//! Every run rescans the resources directory, decodes every `*.delta` file,
//! links them into the single linear chain implied by their `dependencies`
//! field (no merges exist today -- each delta depends on exactly one
//! predecessor, or none for the first), replays them from the same F0
//! bootstrap graph `Bootstrap.scala` builds, and serves the resulting
//! per-layer graphs plus diffs as JSON to a small browser UI. Nothing is
//! cached across requests: edit a `.delta` file and reload the page to see
//! the new state, including exactly where a broken change stops the chain.
//!
//! Standalone and dependency-free, in keeping with the other tools under
//! `rust/`: this is an independent reimplementation of the canonical codec
//! and graph semantics from `Canon.scala` / `Delta.scala`, not a consumer of
//! them.

use std::env;
use std::net::TcpListener;
use std::path::PathBuf;

mod codec;
mod graph;
mod chain;
mod api;
mod http;

fn resources_from_args(args: &[String]) -> PathBuf {
    if let Some(pos) = args.iter().position(|x| x == "--resources") {
        if let Some(p) = args.get(pos + 1) {
            return PathBuf::from(p);
        }
        eprintln!("--resources requires a path");
        std::process::exit(2);
    }
    if let Ok(p) = env::var("TRELLIS_RESOURCES") {
        return PathBuf::from(p);
    }
    let local = PathBuf::from("src/main/resources/trellis");
    if local.is_dir() {
        return local;
    }
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/trellis")
}

fn port_from_args(args: &[String]) -> u16 {
    if let Some(pos) = args.iter().position(|x| x == "--port") {
        if let Some(p) = args.get(pos + 1) {
            return p.parse().unwrap_or_else(|_| {
                eprintln!("--port must be a number");
                std::process::exit(2);
            });
        }
        eprintln!("--port requires a number");
        std::process::exit(2);
    }
    8420
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.iter().any(|a| a == "-h" || a == "--help") {
        eprintln!(
            "delta-web: a local live viewer for the trellis *.delta chain\n\n\
             USAGE:\n    delta-web [--port PORT] [--resources DIR]\n\n\
             Serves http://127.0.0.1:PORT (default 8420). --resources points at the\n\
             directory containing foundations/ and products/ (default: autodetected\n\
             src/main/resources/trellis). Every request rescans that directory."
        );
        return;
    }
    let resources = resources_from_args(&args);
    let port = port_from_args(&args);

    if !resources.is_dir() {
        eprintln!("resources directory not found: {}", resources.display());
        std::process::exit(1);
    }

    // Fail fast if our independent F0 reconstruction has drifted from the
    // frozen root -- every later layer would otherwise be silently wrong.
    let f0 = graph::build_f0();
    let f0_id = graph::graph_id(&f0);
    if f0_id != graph::F0_ROOT {
        eprintln!(
            "delta-web's F0 reconstruction does not match the frozen root: {f0_id} != {}",
            graph::F0_ROOT
        );
        eprintln!("(this tool's build_f0() has drifted from Bootstrap.scala -- fix it before trusting any layer)");
        std::process::exit(1);
    }

    let listener = TcpListener::bind(("127.0.0.1", port)).unwrap_or_else(|e| {
        eprintln!("cannot bind 127.0.0.1:{port}: {e}");
        std::process::exit(1);
    });
    println!("delta-web listening on http://127.0.0.1:{port}");
    println!("resources: {}", resources.display());
    println!("(rescans the delta chain on every request -- edit files and reload)");

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let resources = resources.clone();
                std::thread::spawn(move || {
                    if let Err(e) = http::handle_connection(stream, &resources) {
                        eprintln!("connection error: {e}");
                    }
                });
            }
            Err(e) => eprintln!("accept error: {e}"),
        }
    }
}
