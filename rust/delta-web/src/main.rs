//! Dependency-free web shell for the assembly-backed Trellis Squeak image.

use std::env;
use std::net::TcpListener;

mod http;

fn argument_port(args: &[String], option: &str, default: u16) -> u16 {
    args.iter().position(|value| value == option).map_or(default, |position| {
        args.get(position + 1).and_then(|value| value.parse().ok()).unwrap_or_else(|| {
            eprintln!("{option} requires a port number");
            std::process::exit(2);
        })
    })
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.iter().any(|value| value == "-h" || value == "--help") {
        eprintln!("delta-web: Trellis Squeak web shell\n\nUSAGE:\n    delta-web [--port PORT] [--runtime-port PORT]");
        return;
    }
    let port = argument_port(&args, "--port", 8421);
    let runtime_port = argument_port(&args, "--runtime-port", 8422);
    let listener = TcpListener::bind(("127.0.0.1", port)).unwrap_or_else(|error| {
        eprintln!("cannot bind 127.0.0.1:{port}: {error}");
        std::process::exit(1);
    });
    println!("Trellis Squeak listening on http://127.0.0.1:{port}");
    println!("assembly image runtime: http://127.0.0.1:{runtime_port}");
    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                std::thread::spawn(move || {
                    if let Err(error) = http::handle_connection(stream, runtime_port) {
                        eprintln!("connection error: {error}");
                    }
                });
            }
            Err(error) => eprintln!("accept error: {error}"),
        };
    }
}
