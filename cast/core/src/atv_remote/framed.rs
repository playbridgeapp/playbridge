use std::collections::VecDeque;
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use super::error::AtvError;
use super::proto::{FrameDecoder, encode_frame};
use super::tls::classify_stream;

pub(crate) struct AtvStream<S> {
    inner: S,
    decoder: FrameDecoder,
    pending: VecDeque<Vec<u8>>,
}

impl<S> AtvStream<S> {
    pub(crate) fn new(inner: S) -> Self {
        Self {
            inner,
            decoder: FrameDecoder::new(),
            pending: VecDeque::new(),
        }
    }

    pub(crate) fn next_frame(&mut self) -> Option<Vec<u8>> {
        self.pending.pop_front()
    }
}

impl<S> AtvStream<S>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    pub(crate) async fn read_frame(&mut self, timeout: Duration) -> Result<Vec<u8>, AtvError> {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            if let Some(frame) = self.next_frame() {
                return Ok(frame);
            }
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                return Err(AtvError::Timeout);
            }
            match tokio::time::timeout(remaining, self.read_some()).await {
                Ok(Ok(())) => {}
                Ok(Err(error)) => return Err(error),
                Err(_) => return Err(AtvError::Timeout),
            }
        }
    }

    pub(crate) async fn read_some(&mut self) -> Result<(), AtvError> {
        let mut buffer = [0_u8; 8192];
        let read = self.inner.read(&mut buffer).await;
        let count = match read {
            Ok(count) => count,
            Err(error) => return Err(classify_stream(error, !self.decoder.is_empty())),
        };
        if count == 0 {
            if !self.decoder.is_empty() {
                return self.decoder.finish();
            }
            if self.pending.is_empty() {
                return Err(AtvError::Closed);
            }
            return Ok(());
        }
        let frames = self.decoder.push(&buffer[..count])?;
        self.pending.extend(frames);
        Ok(())
    }

    pub(crate) async fn write_frame(
        &mut self,
        payload: &[u8],
        timeout: Duration,
    ) -> Result<(), AtvError> {
        let frame = encode_frame(payload)?;
        let write = async {
            self.inner.write_all(&frame).await?;
            self.inner.flush().await?;
            Ok::<(), std::io::Error>(())
        };
        match tokio::time::timeout(timeout, write).await {
            Ok(Ok(())) => Ok(()),
            Ok(Err(error)) => Err(classify_stream(error, false)),
            Err(_) => Err(AtvError::Timeout),
        }
    }
}
