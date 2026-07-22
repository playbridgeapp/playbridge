use playbridge_cast_core::secure_ws::SecureWebSocket;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let url = args.next().ok_or("usage: check_pin <wss-url> <spki-pin>")?;
    let pin = args.next().ok_or("missing expected SPKI pin")?;
    let socket = SecureWebSocket::connect_pinned(&url, &pin).await?;
    println!("Active TLS SPKI pin verified.");
    socket.close().await?;
    Ok(())
}
