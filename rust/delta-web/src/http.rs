//! A minimal single-purpose HTTP/1.1 server: static UI plus `/api/*` proxy to the
//! Scala agent runtime. This is a local dev tool, not exposed beyond loopback.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;

const INDEX_HTML: &str = include_str!("index.html");

pub fn handle_connection(stream: TcpStream, runtime_port: u16) -> std::io::Result<()> {
    stream.set_read_timeout(Some(std::time::Duration::from_secs(30)))?;
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut request_line = String::new();
    reader.read_line(&mut request_line)?;
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let path = parts.next().unwrap_or("/");

    let mut content_length = 0usize;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        if line.to_ascii_lowercase().starts_with("content-length:") {
            content_length = line
                .split(':')
                .nth(1)
                .and_then(|value| value.trim().parse().ok())
                .unwrap_or(0);
        }
    }

    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        reader.read_exact(&mut body)?;
    }

    let mut stream = stream;
    if path.starts_with("/api/") {
        return proxy_runtime(&mut stream, method, path, &body, runtime_port);
    }

    if method != "GET" {
        return respond(&mut stream, 405, "text/plain", b"method not allowed");
    }

    match path {
        "/" | "/index.html" => respond(&mut stream, 200, "text/html; charset=utf-8", INDEX_HTML.as_bytes()),
        _ => respond(&mut stream, 404, "text/plain", b"not found"),
    }
}

fn proxy_runtime(stream: &mut TcpStream, method: &str, path: &str, body: &[u8], runtime_port: u16) -> std::io::Result<()> {
    let upstream_path = path.replacen("/api", "", 1);
    match TcpStream::connect(("127.0.0.1", runtime_port)) {
        Ok(mut upstream) => {
            upstream.set_read_timeout(Some(std::time::Duration::from_secs(30)))?;
            write!(
                upstream,
                "{method} {upstream_path} HTTP/1.1\r\nHost: 127.0.0.1:{runtime_port}\r\nConnection: close\r\nContent-Length: {}\r\n\r\n",
                body.len()
            )?;
            upstream.write_all(body)?;
            upstream.flush()?;
            let mut response = Vec::new();
            upstream.read_to_end(&mut response)?;
            stream.write_all(&response)?;
            stream.flush()
        }
        Err(_) => respond(
            stream,
            502,
            "application/json",
            b"{\"error\":\"Trellis agent API is not running on port 8422\"}",
        ),
    }
}

fn respond(stream: &mut TcpStream, status: u16, content_type: &str, body: &[u8]) -> std::io::Result<()> {
    let reason = match status {
        200 => "OK",
        404 => "Not Found",
        405 => "Method Not Allowed",
        502 => "Bad Gateway",
        _ => "Error",
    };
    let header = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        body.len()
    );
    stream.write_all(header.as_bytes())?;
    stream.write_all(body)?;
    stream.flush()
}
