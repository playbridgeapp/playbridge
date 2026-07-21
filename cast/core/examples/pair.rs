use std::io::{self, Write};

use playbridge_cast_core::playbridge::{PairingSession, ReceiverFrame, SenderFrame};
use playbridge_cast_core::secure_ws::SecureWebSocket;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let url = std::env::args().nth(1).ok_or("usage: pair <wss-url>")?;
    let mut socket = SecureWebSocket::connect_for_pairing(&url).await?;
    let served_pin = socket.served_spki_pin().to_owned();

    let hostname = std::env::var("HOSTNAME").unwrap_or_else(|_| "Rust CLI".into());
    let uuid = format!("rust-core-{hostname}");
    let (mut pairing, commit) = PairingSession::start(hostname, uuid)?;
    socket.send(&commit).await?;
    println!("pairing_commit sent; waiting for receiver challenge…");

    while let Some(message) = socket.receive().await? {
        match message {
            ReceiverFrame::PairingChallenge {
                tv_eph_pub,
                nonce_t,
            } => {
                let (sas, reveal) = pairing.accept_challenge(&tv_eph_pub, &nonce_t)?;
                socket.send(&reveal).await?;
                print!("Enter the six-digit code displayed by the receiver: ");
                io::stdout().flush()?;
                let mut entered = String::new();
                io::stdin().read_line(&mut entered)?;
                let confirmation = pairing.confirmation(entered.trim(), &sas)?;
                socket.send(&confirmation).await?;
                println!("pairing_confirmation sent; approve the sender if prompted…");
            }
            ReceiverFrame::PairingApproved { nonce, ciphertext } => {
                let credentials =
                    pairing.decrypt_credentials(&nonce, &ciphertext, Some(&served_pin))?;
                println!(
                    "Pairing succeeded. Players: {:?}; browsers: {:?}",
                    credentials.players, credentials.browsers
                );
                socket
                    .send(&SenderFrame::Command {
                        action: "context_query".into(),
                        payload: None,
                    })
                    .await?;
                socket.close().await?;
                return Ok(());
            }
            ReceiverFrame::PairingDenied => return Err("pairing denied by receiver".into()),
            _ => {}
        }
    }
    Err("receiver closed before pairing completed".into())
}
