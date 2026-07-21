use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    net::{UdpSocket},
    path::PathBuf,
    sync::Arc,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};

pub struct LocalMediaServer {
    pub url: String,
    pub path: PathBuf,
}

pub fn get_local_lan_ip() -> Option<String> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip().to_string())
}

impl LocalMediaServer {
    pub async fn start(file_path: PathBuf) -> Result<(Self, tokio::task::JoinHandle<()>), String> {
        if !file_path.exists() {
            return Err(format!("File does not exist: {:?}", file_path));
        }

        let lan_ip = get_local_lan_ip().ok_or_else(|| "Could not determine local LAN IP address".to_string())?;
        let listener = TcpListener::bind("0.0.0.0:0")
            .await
            .map_err(|e| format!("Failed to bind local HTTP server: {e}"))?;

        let port = listener.local_addr().map_err(|e| e.to_string())?.port();
        let file_name = file_path.file_name().and_then(|s| s.to_str()).unwrap_or("media.mp4");
        let url = format!("http://{lan_ip}:{port}/{file_name}");
        let path = file_path.clone();

        let path_clone = file_path.clone();
        let handle = tokio::spawn(async move {
            let file_path = Arc::new(path_clone);
            loop {
                if let Ok((socket, _)) = listener.accept().await {
                    let fp = Arc::clone(&file_path);
                    tokio::spawn(async move {
                        let _ = handle_http_request(socket, &fp).await;
                    });
                }
            }
        });

        Ok((Self { url, path }, handle))
    }
}

async fn handle_http_request(mut socket: TcpStream, file_path: &PathBuf) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut buf = [0u8; 2048];
    let n = socket.read(&mut buf).await?;
    if n == 0 {
        return Ok(());
    }

    let req_str = String::from_utf8_lossy(&buf[..n]);
    let metadata = std::fs::metadata(file_path)?;
    let total_size = metadata.len();

    let content_type = match file_path.extension().and_then(|s| s.to_str()).unwrap_or("").to_lowercase().as_str() {
        "mp4" | "m4v" => "video/mp4",
        "mkv" => "video/x-matroska",
        "webm" => "video/webm",
        "avi" => "video/x-msvideo",
        "mov" => "video/quicktime",
        "mp3" => "audio/mpeg",
        "flac" => "audio/flac",
        "aac" => "audio/aac",
        "m4a" => "audio/mp4",
        _ => "video/mp4",
    };

    let mut range_start = 0u64;
    let mut range_end = total_size.saturating_sub(1);
    let is_range = if let Some(line) = req_str.lines().find(|l| l.to_lowercase().starts_with("range:")) {
        if let Some(spec) = line.split('=').nth(1) {
            let parts: Vec<&str> = spec.trim().split('-').collect();
            if !parts[0].is_empty() {
                if let Ok(s) = parts[0].parse::<u64>() {
                    range_start = s;
                }
            }
            if parts.len() > 1 && !parts[1].is_empty() {
                if let Ok(e) = parts[1].parse::<u64>() {
                    range_end = e.min(total_size.saturating_sub(1));
                }
            }
            true
        } else {
            false
        }
    } else {
        false
    };

    let content_length = (range_end - range_start) + 1;

    let headers = if is_range {
        format!(
            "HTTP/1.1 206 Partial Content\r\n\
            Content-Type: {}\r\n\
            Accept-Ranges: bytes\r\n\
            Content-Range: bytes {}-{}/{}\r\n\
            Content-Length: {}\r\n\
            Connection: close\r\n\r\n",
            content_type, range_start, range_end, total_size, content_length
        )
    } else {
        format!(
            "HTTP/1.1 200 OK\r\n\
            Content-Type: {}\r\n\
            Accept-Ranges: bytes\r\n\
            Content-Length: {}\r\n\
            Connection: close\r\n\r\n",
            content_type, total_size
        )
    };

    socket.write_all(headers.as_bytes()).await?;

    let mut file = File::open(file_path)?;
    file.seek(SeekFrom::Start(range_start))?;

    let mut remaining = content_length;
    let mut chunk = [0u8; 64 * 1024];

    while remaining > 0 {
        let to_read = (remaining as usize).min(chunk.len());
        let read_bytes = file.read(&mut chunk[..to_read])?;
        if read_bytes == 0 {
            break;
        }
        socket.write_all(&chunk[..read_bytes]).await?;
        remaining -= read_bytes as u64;
    }

    Ok(())
}
